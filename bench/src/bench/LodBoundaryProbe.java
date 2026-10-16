package bench;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Read-only, explicitly non-qualifying observation of four known foliage-gap frames.
 * Nine collision rays per sampled frame, no world loads, worker waits, or renderer mutations.
 * Collision hits locate possible geometry, not proof of which cutout fragment owned a pixel.
 */
final class LodBoundaryProbe {
    static final boolean ENABLED = Boolean.getBoolean("bench.reuseBoundaryProbe");
    private static final boolean HANDOFF_PATCH = "handoff28".equals(System.getProperty("bench.reuseBoundaryPreset", ""));
    private static final int FIRST = HANDOFF_PATCH ? 28 : 32;
    private static final int[][] BOXES = HANDOFF_PATCH
        ? new int[][] {{1282,880,34,19}, {1231,881,57,20}, {1180,882,36,20}, {1128,883,36,22}}
        : new int[][] {{1074,886,59,21}, {1019,889,45,21}, {963,891,62,22}, {904,894,48,24}};

    static Map<String, Object> sample(Minecraft mc, int frame) throws ReflectiveOperationException {
        if (frame < FIRST || frame >= FIRST + BOXES.length) return Map.of();
        long begin = System.nanoTime(), millis = Util.getMillis();
        var out = new LinkedHashMap<String, Object>();
        out.put("observedNanos", begin);
        out.put("observedMillis", millis);
        out.put("limitation", "Post-render collision rays identify candidates, not fragment ownership; wall fades and scheduling are not locked. Diagnostic only.");
        var camera = mc.gameRenderer.mainCamera();
        var plane = camera.getNearPlane(camera.getFov());
        Vec3 eye = camera.position();
        ViewArea area = (ViewArea)field(mc.levelRenderer, "viewArea");
        Collection<?> visible = (Collection<?>)field(mc.levelRenderer, "visibleSections");
        Object manager = field(Class.forName("net.minecraft.client.renderer.chunk.Lod"), "manager");
        Object gpu = manager == null ? null : field(manager, "gpu");
        var samples = new ArrayList<Map<String, Object>>();
        int[] box = BOXES[frame - FIRST];
        out.put("box", box);
        for (int iy = 0; iy < 3; iy++) for (int ix = 0; ix < 3; ix++) {
            double px = box[0] + (ix + 0.5) * box[2] / 3, py = box[1] + (iy + 0.5) * box[3] / 3;
            Vec3 direction = plane.getPointOnPlane((float)(2 * px / 3024 - 1), (float)(1 - 2 * py / 1964)).normalize();
            var hit = mc.level.clip(new ClipContext(eye, eye.add(direction.scale(384)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            var sample = new LinkedHashMap<String, Object>();
            sample.put("pixel", List.of(px, py));
            sample.put("direction", List.of(direction.x, direction.y, direction.z));
            sample.put("hitType", hit.getType().toString());
            if (hit.getType() != HitResult.Type.MISS) {
                BlockPos pos = hit.getBlockPos();
                sample.put("block", List.of(pos.getX(), pos.getY(), pos.getZ()));
                sample.put("state", mc.level.getBlockState(pos).toString());
                sample.put("distance", hit.getLocation().distanceTo(eye));
                SectionRenderDispatcher.RenderSection section = area == null ? null : area.getRenderSectionAt(pos);
                sample.put("near", section == null ? null : near(section, visible, millis));
                sample.put("lod", manager == null ? List.of() : lod(manager, gpu, pos, begin, millis));
                sample.put("coverage", gpu == null ? null : coverage(gpu, pos));
            }
            samples.add(sample);
        }
        out.put("rays", samples);
        out.put("probeNanos", System.nanoTime() - begin);
        return out;
    }

    private static Map<String, Object> coverage(Object gpu, BlockPos pos) throws ReflectiveOperationException {
        Object coverage = field(gpu, "nearCoverage");
        long coord = SectionPos.asLong(pos);
        var out = new LinkedHashMap<String, Object>();
        out.put("active", field(gpu, "nearHandoffActive"));
        if (coverage == null) { out.put("allocated", false); return out; }
        out.put("allocated", true);
        for (String name : List.of("ready", "empty", "incomplete", "exposed"))
            out.put(name, ((Collection<?>)field(coverage, name)).contains(coord));
        for (String name : List.of("previousFull", "currentFull"))
            out.put(name, ((Map<?, ?>)field(coverage, name)).containsKey(coord));
        Object bound = ((Map<?, ?>)field(coverage, "slots")).get(coord);
        var slots = new ArrayList<Map<String, Object>>();
        Collection<?> masked = (Collection<?>)field(coverage, "masked");
        if (bound instanceof List<?> list) for (Object slot : list)
            slots.add(Map.of("slot", slot, "masked", masked.contains(slot)));
        out.put("slots", slots);
        return out;
    }

    private static Map<String, Object> near(SectionRenderDispatcher.RenderSection section, Collection<?> visible, long now) throws ReflectiveOperationException {
        var out = new LinkedHashMap<String, Object>();
        var mesh = section.getSectionMesh();
        BlockPos origin = section.getRenderOrigin();
        out.put("origin", List.of(origin.getX(), origin.getY(), origin.getZ()));
        out.put("visibleList", visible.contains(section));
        out.put("uncompiled", mesh == CompiledSectionMesh.UNCOMPILED);
        out.put("empty", mesh == CompiledSectionMesh.EMPTY);
        out.put("uploadedMillis", field(section, "uploadedTime"));
        out.put("visibilityAt750ms", section.getVisibility(now, 750));
        var draws = new LinkedHashMap<String, Object>();
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            var draw = mesh.getSectionDraw(layer);
            if (draw != null) draws.put(layer.name(), List.of(draw.indexCount(),
                !(mesh instanceof CompiledSectionMesh c) || c.isVertexBufferUploaded(layer),
                !(mesh instanceof CompiledSectionMesh c) || !draw.hasCustomIndexBuffer() || c.isIndexBufferUploaded(layer)));
        }
        out.put("draws", draws);
        return out;
    }

    private static List<Map<String, Object>> lod(Object manager, Object gpu, BlockPos pos, long now, long millis) throws ReflectiveOperationException {
        Method existing = manager.getClass().getDeclaredMethod("existing", int.class, int.class, int.class);
        existing.setAccessible(true);
        Object active = field(gpu, "activeItems");
        Map<?, ?> geometry = active == null ? new IdentityHashMap<>() : (Map<?, ?>)field(active, "geometry");
        Collection<?> drawn = (Collection<?>)field(gpu, "drawnSet");
        Map<?, ?> fades = (Map<?, ?>)field(gpu, "fades");
        var rows = new ArrayList<Map<String, Object>>();
        for (int level = 0; level <= 7; level++) {
            Object n = existing.invoke(manager, level, pos.getX() >> (4 + level), pos.getZ() >> (4 + level));
            if (n == null) continue;
            var row = new LinkedHashMap<String, Object>();
            row.put("level", level);
            row.put("nx", field(n, "nx")); row.put("nz", field(n, "nz"));
            row.put("drawn", drawn.contains(n)); row.put("activeGeometry", geometry.containsKey(n));
            row.put("hasData", field(n, "hasData")); row.put("resident", field(n, "resident"));
            row.put("lastDrawnAgeMillis", millis - (long)field(n, "lastDrawnMillis"));
            Object fade = fades.get(n);
            row.put("fade", fade == null ? null : Map.of("in", field(fade, "in"), "ageNanos", now - (long)field(fade, "start")));
            Object sections = active == null ? field(n, "gpu") : geometry.get(n);
            var sectionRows = new ArrayList<Map<String, Object>>();
            if (sections instanceof List<?> list) for (Object s : list) {
                int[] origin = (int[])field(s, "origin");
                // Include only the slab at the collision height. Origins and quads are immutable upload metadata.
                int size = 16 << level;
                if (origin == null || pos.getY() < origin[1] || pos.getY() >= origin[1] + size) continue;
                sectionRows.add(Map.of("slot", field(s, "slot"), "origin", origin, "quads", field(s, "quads")));
            }
            row.put("slabs", sectionRows);
            rows.add(row);
        }
        return rows;
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Class<?> cls = owner instanceof Class<?> c ? c : owner.getClass();
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner instanceof Class<?> ? null : owner);
    }
}
