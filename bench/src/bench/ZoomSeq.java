package bench;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

/**
 * Temporal capture for zoom popping checks (docs/GOAL.md "Zoom": no visible popping while zooming). Enabled with
 * -Dbench.mode=zoomseq; frames go to <bench.shots>/screenshots/zoomseq/ with an index.csv.
 *
 * At spyglass_far's fixed view (Scenes: same position, angles, barrier stance, HUD hidden, world tick frozen, time
 * pinned), after the chunks have settled, it plays these segments of bench.zoomseq.ticks client ticks each:
 *   spy_in   use key held: the spyglass is raised and the FOV animates 70 -> 7;
 *   spy_out  use key released: back to 70;
 *   zoom_in  zoom key held at 10x (only when the patched client's zoom feature exists, see ZoomHook);
 *   zoom_out zoom key released.
 * The client runs on the Lockstep clock: every rendered frame advances exactly 1/bench.zoomseq.fpt tick, so frame n of a
 * segment shows the same FOV on every run and backend, whatever the FPS. Every bench.zoomseq.every-th frame of each
 * segment is saved as <segment>_<frame>.png (frame 0 = the frame before the first tick that sees the key change).
 * While screenshot writes are in flight the clock holds still (same frame again), which bounds memory without
 * changing the sequence; it also holds at a segment start until the server's echo of the item-use flag has arrived.
 * index.csv lists file, segment, global frame, ticks since the segment start, partial tick,
 * the camera's vertical FOV for that frame and whether the spyglass was raised.
 *
 * Properties: bench.zoomseq.fpt (frames per tick, default 2), bench.zoomseq.every (default 1),
 * bench.zoomseq.ticks (ticks per segment, default 16), bench.zoomseq.downscale (default 2, as Scenes).
 * Defaults give 32 frames per segment (FOV settles within ~12 ticks; the rest shows late LOD refinement).
 */
final class ZoomSeq {
    private enum Phase { SETUP, SETTLE, FREEZE, RUN, FINISH, DRAIN }

    static final int FPT = Math.max(1, Integer.getInteger("bench.zoomseq.fpt", 2));
    static final int EVERY = Math.max(1, Integer.getInteger("bench.zoomseq.every", 1));
    static final int TICKS = Math.max(1, Integer.getInteger("bench.zoomseq.ticks", 16));
    static final int DOWNSCALE = Math.max(1, Integer.getInteger("bench.zoomseq.downscale", 2));
    /** Lockstep ticks before the first segment (not captured). */
    private static final int LEAD_TICKS = 4;
    /** Screenshots in flight before the clock holds (each is a full-size GPU readback buffer plus an image). */
    private static final int MAX_PENDING = 3;
    /** LivingEntity.DATA_LIVING_ENTITY_FLAGS (bit 0: using an item), as last received from the server. */
    private static final EntityDataAccessor<Byte> LIVING_FLAGS = livingFlags();

    /** Key states held from the segment's first tick on. */
    private record Segment(String name, boolean use, boolean zoom) {
    }

    /** What a game-loop iteration rendered; captured at the next iteration's hook. */
    private record Rendered(long frame, int segment, long offset, boolean scoping) {
    }

    private final double ax, ay, az;
    private final Scenes.Scene view;
    private final BlockPos barrier;
    private final File shotDir;
    private final File outDir;
    private final List<Segment> segments = new ArrayList<>();
    private final StringBuilder index = new StringBuilder("file,segment,frame,ticks,partial,fov,scoping\n");
    private final AtomicInteger pending = new AtomicInteger();
    private final Scenes.ChunkWait chunks = new Scenes.ChunkWait();
    private Phase phase = Phase.SETUP;
    private long phaseStart;
    private long firstFrame;
    private long endFrame;
    private Rendered last;
    private long lastCaptured = Long.MIN_VALUE;
    private long holdSince;
    private boolean hudWasHidden;
    private Double savedFactor;
    private int captures;

    ZoomSeq(double[] anchor, File shotDir) {
        this.ax = anchor[0];
        this.ay = anchor[1];
        this.az = anchor[2];
        Scenes scenes = new Scenes(anchor, shotDir, null);
        this.view = scenes.scene("spyglass_far");
        this.barrier = scenes.barrier();
        this.shotDir = shotDir;
        this.outDir = new File(shotDir, "screenshots/zoomseq");
    }

    /** @return true when the sequence is done */
    boolean step(Minecraft mc) {
        LocalPlayer p = mc.player;
        mc.gui.toastManager().clear();
        double t = (System.nanoTime() - phaseStart) / 1e9;
        switch (phase) {
            case SETUP -> {
                Scenes.cmd(mc, "tick unfreeze");
                mc.gui.setScreen(null);
                Scenes.cmd(mc, String.format(Locale.ROOT, "execute in minecraft:overworld run tp @s %.2f %.2f %.2f %.1f %.1f",
                    ax, ay, az, view.yaw(), view.pitch()));
                for (String c : view.setup()) {
                    Scenes.cmd(mc, c);
                }
                hudWasHidden = mc.gui.hud.isHidden();
                next(Phase.SETTLE);
            }
            case SETTLE -> {
                inputs(mc, p, false, false);
                boolean ready = mc.level != null && mc.levelRenderer.hasRenderedAllSections()
                    && mc.level.dimension().identifier().getPath().equals("overworld") && chunks.stable(mc)
                    && p != null && p.getMainHandItem().is(Items.SPYGLASS) && !p.getAbilities().flying;
                if ((t >= 3.0 && ready) || t > 40) {
                    Scenes.cmd(mc, "tick freeze");
                    next(Phase.FREEZE);
                }
            }
            case FREEZE -> {
                inputs(mc, p, false, false);
                Scenes.pinTime(mc);
                if (t >= 1.5) {
                    begin();
                    next(Phase.RUN);
                }
            }
            case RUN -> run(mc, p, t);
            case FINISH -> {
                inputs(mc, p, false, false);
                Scenes.pinTime(mc);
                if (pending.get() > 0 && t < 60) {
                    return false;
                }
                if (savedFactor != null) {
                    ZoomHook.setFactor(savedFactor);
                }
                try {
                    Files.writeString(new File(outDir, "index.csv").toPath(), index);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                Scenes.hideHud(mc, hudWasHidden);
                Scenes.cmd(mc, "tick unfreeze");
                for (String c : view.cleanup()) {
                    Scenes.cmd(mc, c);
                }
                System.out.println("[bench] zoomseq done: " + captures + " frames in " + outDir
                    + (pending.get() > 0 ? " (" + pending.get() + " writes unfinished)" : ""));
                next(Phase.DRAIN);
            }
            case DRAIN -> {
                // Give the server time to apply the cleanup commands (they edit the shared scene world).
                return t > 2.0;
            }
        }
        return false;
    }

    private void begin() {
        segments.add(new Segment("spy_in", true, false));
        segments.add(new Segment("spy_out", false, false));
        if (ZoomHook.available()) {
            savedFactor = ZoomHook.factor();
            ZoomHook.setFactor(ZoomHook.FACTOR);
            segments.add(new Segment("zoom_in", false, true));
            segments.add(new Segment("zoom_out", false, false));
        } else {
            System.out.println("[bench] zoomseq: zoom key segments skipped (" + ZoomHook.missing() + ")");
        }
        outDir.mkdirs();
        File[] old = outDir.listFiles((d, n) -> n.endsWith(".png") || n.equals("index.csv"));
        if (old != null) {
            for (File f : old) {
                f.delete();
            }
        }
        firstFrame = (long) LEAD_TICKS * FPT;
        endFrame = firstFrame + (long) segments.size() * TICKS * FPT;
        Lockstep.start(FPT);
        ZoomHook.bindClock(true);
    }

    private void run(Minecraft mc, LocalPlayer p, double t) {
        Scenes.pinTime(mc);
        if (Lockstep.steps() == 0) {
            inputs(mc, p, false, false);
            if (t > 5) {
                throw new IllegalStateException("lockstep clock not running: BenchAgent did not hook DeltaTracker$Timer"
                    + " (-Dbench.mode=zoomseq must be on the JVM command line)");
            }
            return;
        }
        long f = Lockstep.frame();
        // 1. Save the frame the previous iteration rendered (this hook runs before this iteration's render).
        if (last != null && last.frame() != lastCaptured) {
            lastCaptured = last.frame();
            if (last.segment() >= 0 && last.offset() % EVERY == 0) {
                capture(mc, last);
            }
        }
        if (f >= endFrame) {
            Lockstep.stop();
            ZoomHook.bindClock(false);
            next(Phase.FINISH);
            return;
        }
        // 2. Keys for the client tick that may run before the next frame (ticks run at frames that are multiples of FPT).
        //    The zoom key is different: Camera reads it while rendering this iteration's frame f (per-frame easing, no
        //    tick), so it follows f's segment; holding it from f+1's segment would zoom one frame early.
        int nextSeg = segmentOf(f + 1);
        Segment in = nextSeg >= 0 ? segments.get(nextSeg) : null;
        int curSeg = segmentOf(f);
        Segment now = curSeg >= 0 ? segments.get(curSeg) : null;
        inputs(mc, p, in != null && in.use(), now != null && now.zoom());
        // 3. Hold the clock while screenshot writes are in flight, and before a segment's first tick until the server's
        //    echo of the item-use flag matches the client (LocalPlayer.onSyncedDataUpdated starts or stops using the
        //    item when an echo disagrees, so a late "using" echo after spy_out's release would raise the spyglass again).
        boolean writes = pending.get() >= MAX_PENDING;
        boolean echo = segmentOf(f + 1) >= 0 && f + 1 == segmentStart(segmentOf(f + 1)) && !useSynced(p);
        boolean hold = writes || echo;
        if (!hold) {
            holdSince = 0L;
        } else if (holdSince == 0L) {
            holdSince = System.nanoTime();
        } else if (System.nanoTime() - holdSince > 30_000_000_000L) {
            throw new IllegalStateException("zoomseq: clock held for 30 s (" + (writes ? "screenshot writes stalled" : "no server echo of item use") + ")");
        }
        Lockstep.hold(hold);
        // 4. What this iteration renders.
        int seg = segmentOf(f);
        last = new Rendered(f, seg, seg >= 0 ? f - segmentStart(seg) : -1L, p != null && p.isScoping());
    }

    private void capture(Minecraft mc, Rendered r) {
        Segment s = segments.get(r.segment());
        String file = String.format(Locale.ROOT, "%s_%03d.png", s.name(), r.offset());
        float partial = (float) Math.floorMod(r.frame(), FPT) / FPT;
        index.append(String.format(Locale.ROOT, "%s,%s,%d,%.3f,%.3f,%.4f,%b%n",
            file, s.name(), r.frame(), (double) r.offset() / FPT, partial, Scenes.cameraFov(mc), r.scoping()));
        pending.incrementAndGet();
        Screenshot.grab(shotDir, "zoomseq/" + file, mc.gameRenderer.mainRenderTarget(), DOWNSCALE, msg -> pending.decrementAndGet());
        captures++;
    }

    /** True when the item-use flag last received from the server equals the client's own (predicted) state. */
    private static boolean useSynced(LocalPlayer p) {
        if (LIVING_FLAGS == null || p == null) {
            return true;
        }
        return ((p.getEntityData().get(LIVING_FLAGS) & 1) != 0) == p.isUsingItem();
    }

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Byte> livingFlags() {
        try {
            Field f = LivingEntity.class.getDeclaredField("DATA_LIVING_ENTITY_FLAGS");
            f.setAccessible(true);
            return (EntityDataAccessor<Byte>) f.get(null);
        } catch (Throwable t) {
            System.out.println("[bench] zoomseq: item-use flag not readable (" + t + "); not waiting for server echoes");
            return null;
        }
    }

    /** Segment index of a lockstep frame, or -1 outside the segments. */
    private int segmentOf(long frame) {
        if (frame < firstFrame || frame >= endFrame) {
            return -1;
        }
        return (int) ((frame - firstFrame) / ((long) TICKS * FPT));
    }

    private long segmentStart(int seg) {
        return firstFrame + (long) seg * TICKS * FPT;
    }

    /** Fixed view, grounded stance, hidden HUD; use key (spyglass) and zoom key as given. */
    private void inputs(Minecraft mc, LocalPlayer p, boolean use, boolean zoom) {
        Scenes.aim(p, view.yaw(), view.pitch());
        Scenes.stand(mc, barrier);
        Scenes.hideHud(mc, true);
        Scenes.holdSpyglass(mc, use);
        if (ZoomHook.available()) {
            ZoomHook.hold(ZoomHook.key(), zoom);
        }
    }

    private void next(Phase p) {
        phase = p;
        phaseStart = System.nanoTime();
    }
}
