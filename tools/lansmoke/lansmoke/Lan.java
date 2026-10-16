package lansmoke;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * LAN multiplayer smoke test (round-4 integration). lan.role=host: create/open world "lan", publish it to LAN on
 * lan.port (online mode off: both test clients are offline accounts), stay until a second player has been connected and
 * left again or lan.hostSeconds pass. lan.role=guest (launched with --quickPlayMultiplayer; driven by tools/lan-smoke.sh): once in the world, wait
 * for all visible sections, measure frames for 15 s, take a screenshot, write the result, quit.
 *
 * <p>"timing" in both results (stage-2 W7 A/B, tools/lan-smoke.sh LAN_ARGS): milliseconds since the JVM started (RuntimeMXBean
 * uptime), frame-granular: firstFrame, loadFinished (the startup reload's completion callback has run; a guest started with
 * --quickPlayMultiplayer connects there), overlayGone (the startup loading overlay removed), then for the host title (title
 * screen, no overlay: it creates / opens the world now) and published (in the world, no screen or overlay: published to
 * LAN), for the guest level (client level and player exist) and joined (in the world, no screen or overlay).
 */
public final class Lan {
    static final String ROLE = System.getProperty("lan.role", "host");
    static final String OUT = System.getProperty("lan.out");
    static final int PORT = Integer.getInteger("lan.port", 25599);
    static final double HOST_S = Double.parseDouble(System.getProperty("lan.hostSeconds", "300"));
    static int state;
    static long t0 = System.nanoTime(), tState;
    static final List<String> log = new ArrayList<>();
    static int maxPlayers, frames;
    static long measureStart;
    static boolean shot;

    static double s(long since) { return (System.nanoTime() - since) / 1e9; }

    static void note(String m) {
        String line = String.format("%.1fs %s", s(t0), m);
        log.add(line);
        System.out.println("[lan] " + line);
    }

    static void finish(Minecraft mc, String extra) {
        StringBuilder sb = new StringBuilder("{\n  \"role\": \"" + ROLE + "\",\n");
        sb.append(extra);
        sb.append(timingJson());
        sb.append("  \"log\": [");
        for (int i = 0; i < log.size(); i++) sb.append(i == 0 ? "" : ", ").append('"').append(log.get(i).replace("\"", "'")).append('"');
        sb.append("]\n}\n");
        try { Files.writeString(Path.of(OUT), sb.toString()); } catch (Exception e) { e.printStackTrace(); }
        state = 99;
        mc.stop();
    }

    public static void onFrame() {
        if (state == 99) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            stamp("firstFrame");
            if (mc.isGameLoadFinished()) stamp("loadFinished");
            if (mc.gui.overlay() != null) overlaySeen = true;
            else if (overlaySeen) stamp("overlayGone");
            if (ROLE.equals("host")) host(mc); else guest(mc);
        } catch (Throwable t) {
            t.printStackTrace();
            note("exception " + t);
            finish(mc, "  \"error\": \"" + t.toString().replace("\"", "'") + "\",\n");
        }
    }

    static void host(Minecraft mc) {
        if (mc.gui.screen() instanceof PauseScreen) mc.gui.setScreen(null);
        switch (state) {
            case 0 -> {
                if (mc.gui.screen() instanceof TitleScreen screen && mc.gui.overlay() == null) {
                    stamp("title");
                    state = 1;
                    if (mc.getLevelSource().levelExists("lan")) {
                        mc.createWorldOpenFlows().openWorld("lan", () -> mc.gui.setScreen(screen));
                    } else {
                        LevelSettings settings = new LevelSettings("lan", GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
                        mc.createWorldOpenFlows().createFreshLevel("lan", settings, new WorldOptions(4242L, true, false), WorldPresets::createNormalWorldDimensions, screen);
                    }
                }
            }
            case 1 -> {
                if (mc.level != null && mc.player != null && mc.gui.screen() == null && mc.gui.overlay() == null) {
                    stamp("published");
                    IntegratedServer server = mc.getSingleplayerServer();
                    server.setUsesAuthentication(false);
                    boolean ok = server.publishServer(MinecraftServer.MultiplayerScope.LAN, true, PORT);
                    note("publishServer(LAN, commands, " + PORT + ") = " + ok + ", published " + server.isPublished() + ", port " + server.getPort());
                    try { Files.writeString(Path.of(OUT + ".ready"), "port " + server.getPort() + "\n"); } catch (Exception e) { }
                    state = 2;
                    tState = System.nanoTime();
                }
            }
            case 2 -> {
                IntegratedServer server = mc.getSingleplayerServer();
                int n = server.getPlayerCount();
                if (n != maxPlayers && n > maxPlayers) note("players " + n + ": " + String.join(",", server.getPlayerList().getPlayerNamesArray()));
                if (n < maxPlayers) note("players " + n);
                if (n > maxPlayers) maxPlayers = n;
                if ((maxPlayers >= 2 && n == 1) || s(tState) > HOST_S) {
                    note("host done: maxPlayers " + maxPlayers + ", fps " + mc.getFps());
                    finish(mc, "  \"maxPlayers\": " + maxPlayers + ",\n  \"published\": " + server.isPublished() + ",\n");
                }
            }
            default -> { }
        }
    }

    static void guest(Minecraft mc) {
        if (mc.gui.screen() instanceof PauseScreen) mc.gui.setScreen(null);
        switch (state) {
            case 0 -> {
                if (mc.level != null && mc.player != null) stamp("level");
                if (mc.level != null && mc.player != null && mc.gui.screen() == null && mc.gui.overlay() == null) {
                    stamp("joined");
                    note("joined: localServer " + mc.isLocalServer() + ", server " + (mc.getCurrentServer() == null ? "null" : mc.getCurrentServer().ip)
                        + ", players " + mc.getConnection().getOnlinePlayers().size() + ", pos " + mc.player.blockPosition());
                    state = 1;
                    tState = System.nanoTime();
                } else if (s(t0) > 240) {
                    note("timeout joining, screen " + mc.gui.screen());
                    finish(mc, "  \"joined\": false,\n");
                }
            }
            case 1 -> {
                mc.player.setXRot(10f);
                if ((s(tState) > 5 && mc.levelRenderer.hasRenderedAllSections()) || s(tState) > 90) {
                    note("sections ready after " + String.format("%.1f", s(tState)) + "s (all " + mc.levelRenderer.hasRenderedAllSections() + "), visible " + mc.levelRenderer.visibleSections().size());
                    state = 2;
                    measureStart = System.nanoTime();
                    frames = 0;
                }
            }
            case 2 -> {
                frames++;
                mc.player.setYRot(mc.player.getYRot() + 0.5f);
                if (s(measureStart) >= 15) {
                    double fps = frames / s(measureStart);
                    note(String.format("measured %.1f fps over 15 s, visible sections %d", fps, mc.levelRenderer.visibleSections().size()));
                    File dir = new File(System.getProperty("lan.shots", "."));
                    Screenshot.grab(dir, "lan-guest.png", mc.gameRenderer.mainRenderTarget(), 1, msg -> {});
                    state = 3;
                    tState = System.nanoTime();
                    measureStartFps = fps;
                }
            }
            case 3 -> {
                if (s(tState) > 3) {
                    finish(mc, String.format("  \"joined\": true,\n  \"fps\": %.1f,\n  \"renderDistance\": %d,\n  \"players\": %d,\n  \"backend\": \"%s\",\n",
                        measureStartFps, mc.options.getEffectiveRenderDistance(), mc.getConnection().getOnlinePlayers().size(),
                        com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().backendName()));
                }
            }
            default -> { }
        }
    }

    static double measureStartFps;
    static final java.util.Map<String, Long> timing = new java.util.LinkedHashMap<>();
    static boolean overlaySeen;

    /** Uptime stamp (first call per name wins). */
    static void stamp(String name) {
        timing.putIfAbsent(name, java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
    }

    static String timingJson() {
        StringBuilder sb = new StringBuilder("  \"timing\": {");
        int i = 0;
        for (java.util.Map.Entry<String, Long> e : timing.entrySet()) {
            sb.append(i++ == 0 ? "" : ", ").append('"').append(e.getKey()).append("Ms\": ").append(e.getValue());
        }
        return sb.append("},\n").toString();
    }
}
