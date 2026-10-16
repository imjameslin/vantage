package net.minecraft.client.compat;

import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Fabric mod compatibility (docs/GOAL.md "Mod compatibility", docs/metal/CONTRACT.md 16.FAB, results/FABRIC.md).
 *
 * <p>Mods that replace rendering are superseded by this client's own renderer / LOD / shaders: Sodium, Iris, Distant
 * Horizons, Voxy. Their mixins rewrite the classes our Metal renderer replaces, so they cannot run on top of it. The launch
 * configuration keeps Fabric from loading them ({@code -Dfabric.debug.disableModIds=sodium,iris,distanthorizons,voxy},
 * {@link #DISABLE_PROPERTY_VALUE}; tools/launch.py --fabric sets it), and this class tells the player, in game, which of
 * them are in the mods folder and why they are not running, instead of the game crashing or silently ignoring them.
 *
 * <p>Runs once, when the game has loaded ({@code Minecraft.onGameLoadFinished}): without Fabric on the class path it does
 * nothing (one class lookup). With Fabric it reads {@code fabric.mod.json} of every jar in the mods folder (no class
 * loading) and asks the loader which mods are loaded. Superseded mods found: a notice screen before the title screen (a
 * toast instead when quick play goes straight into a world), plus a log line per mod. Nothing a server can observe.
 */
public final class ModCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** id, display name, what replaces it. */
    static final String[][] SUPERSEDED = {
        {"sodium", "Sodium", "this client's Metal renderer"},
        {"iris", "Iris", "this client's own shaders (in development)"},
        {"distanthorizons", "Distant Horizons", "this client's own far-terrain LOD (in development)"},
        {"voxy", "Voxy", "this client's own far-terrain LOD (in development)"},
    };

    /** The value of {@code -Dfabric.debug.disableModIds} that keeps every superseded mod from loading. */
    public static final String DISABLE_PROPERTY_VALUE = "sodium,iris,distanthorizons,voxy";

    /** One superseded mod in the mods folder. {@code loaded}: Fabric loaded it anyway (launch configuration without the
     *  disable property). */
    public record Found(String id, String name, String version, String file, String replacement, boolean loaded) {
    }

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    private static volatile List<Found> last = List.of();

    private ModCompat() {
    }

    /** What the last {@link #scan} found (tests, bench config). */
    public static List<Found> lastFound() {
        return last;
    }

    /** Whether the Fabric loader is on the class path (without loading or initialising anything of it). */
    public static boolean fabricPresent() {
        try {
            Class.forName("net.fabricmc.loader.api.FabricLoader", false, ModCompat.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Superseded mods in the mods folder ({@code fabric.modsFolder} or {@code <gameDir>/mods}); empty without Fabric. */
    public static List<Found> scan(final File gameDirectory) {
        if (!fabricPresent()) {
            return List.of();
        }

        String folder = System.getProperty("fabric.modsFolder");
        Path mods = folder != null ? Path.of(folder) : gameDirectory.toPath().resolve("mods");
        return scanFolder(mods);
    }

    /** {@link #scan} on a given folder (also used by tools/ModCompatTest.java). */
    public static List<Found> scanFolder(final Path mods) {
        List<Found> out = new ArrayList<>();
        if (!Files.isDirectory(mods)) {
            last = out;
            return out;
        }

        List<Path> jars;
        try (Stream<Path> s = Files.list(mods)) {
            jars = s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            LOGGER.warn("[mod-compat] cannot list {}: {}", mods, e.toString());
            last = out;
            return out;
        }

        for (Path jar : jars) {
            String json = modJson(jar);
            if (json == null) {
                continue;
            }

            Matcher m = ID.matcher(json);
            if (!m.find()) {
                continue;
            }

            String id = m.group(1);
            for (String[] s : SUPERSEDED) {
                if (s[0].equals(id)) {
                    Matcher v = VERSION.matcher(json);
                    String version = v.find() ? v.group(1) : "?";
                    out.add(new Found(id, s[1], version, jar.getFileName().toString(), s[2], fabricModLoaded(id)));
                }
            }
        }

        last = List.copyOf(out);
        return last;
    }

    private static String modJson(final Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e == null) {
                return null;
            }

            try (InputStream in = z.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean fabricModLoaded(final String id) {
        try {
            Class<?> c = Class.forName("net.fabricmc.loader.api.FabricLoader", true, ModCompat.class.getClassLoader());
            Object loader = c.getMethod("getInstance").invoke(null);
            return (Boolean) c.getMethod("isModLoaded", String.class).invoke(loader, id);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return false;
        }
    }

    /** The notice text (one line per mod). */
    public static Component message(final List<Found> found) {
        StringBuilder b = new StringBuilder();
        boolean anyLoaded = found.stream().anyMatch(Found::loaded);
        b.append(anyLoaded
            ? "These mods replace rendering and conflict with this client's renderer:\n"
            : "This client has its own renderer, so these mods were not loaded:\n");
        for (Found f : found) {
            b.append("\n").append(f.name()).append(' ').append(f.version()).append(" - replaced by ").append(f.replacement());
        }

        b.append("\n\n");
        if (anyLoaded) {
            b.append("Remove them from the mods folder, or start the game with -Dfabric.debug.disableModIds=")
                .append(DISABLE_PROPERTY_VALUE).append(". Expect rendering errors until then.");
        } else {
            b.append("Your other mods are loaded as usual. Remove these from the mods folder to hide this message.");
        }

        return Component.literal(b.toString());
    }

    /**
     * Called by {@code Minecraft.onGameLoadFinished} with the step that shows the first screen: returns it unchanged when
     * nothing is superseded, otherwise a step that first shows the notice (or, with quick play, a toast and the step).
     */
    public static Runnable wrapInitialScreens(final Minecraft minecraft, final Runnable showScreen, final boolean quickPlay) {
        List<Found> found = scan(minecraft.gameDirectory);
        if (found.isEmpty()) {
            return showScreen;
        }

        for (Found f : found) {
            LOGGER.warn("[mod-compat] {} {} ({}) {}: superseded by {}", f.name(), f.version(), f.file(),
                f.loaded() ? "IS LOADED (conflicts with this client's renderer)" : "not loaded", f.replacement());
        }

        Component title = Component.literal(found.stream().anyMatch(Found::loaded) ? "Conflicting rendering mods" : "Rendering mods not loaded");
        Component message = message(found);
        if (quickPlay) {
            return () -> {
                showScreen.run();
                SystemToast.add(minecraft.gui.toastManager(), new SystemToast.SystemToastId(15000L), title,
                    Component.literal(String.join(", ", found.stream().map(Found::name).toList()) + ": see the log"));
            };
        }

        return () -> minecraft.gui.setScreen(new AlertScreen(showScreen, title, message, Component.literal("Continue"), true));
    }
}
