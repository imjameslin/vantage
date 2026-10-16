package bench;

import java.io.InputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The bench result's {@code "config"} block (docs/metal/CONTRACT.md 16.R5-B): what was REALLY in effect during the run,
 * so a result can never again be attributed to the wrong configuration (TAIL-ANALYSIS C1 / E8: the round-4 JSONs did not
 * say whether present fusion was on). Always written (it only reads; {@code -Dbench.config=false} omits it):
 * <ul>
 *   <li>{@code jvm}: version, VM, input arguments, GC beans, heap limit, processors;</li>
 *   <li>{@code properties}: every {@code mc.* / mcmetal.* / bench.* / terrain.* / org.lwjgl.*} system property;</li>
 *   <li>{@code env}: every {@code MCMTL_* / MTL_* / METAL_* / SDL_*} variable and the JVM option variables;</li>
 *   <li>{@code toggles}: the effective static toggle values of the patched client's toggle classes (reflection: what
 *       the code read, defaults included), plus {@code -Dbench.configClasses=fqcn,...};</li>
 *   <li>{@code native}: the Metal library's effective configuration ({@code mcmtl_frametrace_config}: fusion mode, M2
 *       flags, ICB, occlusion, mailbox state of the surface, ...);</li>
 *   <li>{@code measured}: native decoder counters over the MEASURE phase: fused share, presented / skipped share,
 *       mailbox share, drawable wait per frame, fusion fallback and present skip reasons, ICB / occlusion activity;</li>
 *   <li>{@code game}: video options and window state; {@code jars}: sha1 of the client jar, bench jar and dylib.</li>
 * </ul>
 * Nothing here runs per frame: {@link #begin} once at the start of MEASURE (one native counters read, after the
 * handles were bound in warmup by {@link #prepare}), {@link #json} after the run.
 */
final class ConfigBlock {
    static final boolean ENABLED = !"false".equals(System.getProperty("bench.config"));
    private static final String[] PROPERTY_PREFIXES = {"mc.", "mcmetal.", "bench.", "terrain.", "org.lwjgl."};
    private static final String[] ENV_PREFIXES = {"MCMTL_", "MTL_", "METAL_", "SDL_"};
    private static final String[] ENV_NAMES = {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"};
    private static final String METAL = "com.mojang.renderpearl.backend.metal.";
    /** Toggle holders whose static state is read at the end of the run (missing classes are skipped). */
    private static final String[] COMMON_CLASSES = {
        "net.minecraft.client.renderer.chunk.TerrainToggles",
        "net.minecraft.client.renderer.chunk.TerrainCompact",
        "net.minecraft.client.renderer.chunk.TerrainStats",
        "net.minecraft.client.zoom.Zoom",
        "net.minecraft.client.gui.screens.UiTransitions",
    };
    /** Only read on the Metal backend (their static initializers have not necessarily run on other backends). */
    private static final String[] METAL_CLASSES = {
        METAL + "MetalDevice", METAL + "MetalCommandEncoder", METAL + "MetalTimeline", METAL + "MetalTransientMemory",
        METAL + "MetalRenderPass", METAL + "TerrainShaderVariants", METAL + "MetalLateThrottle", METAL + "LateThrottle",
    };
    /** Video options recorded from {@code Minecraft.options} (methods returning an OptionInstance). */
    private static final String[] OPTIONS = {
        "renderDistance", "simulationDistance", "framerateLimit", "enableVsync", "preferredGraphicsBackend", "graphicsPreset",
        "cloudStatus", "cloudRange", "improvedTransparency", "ambientOcclusion", "mipmapLevels", "maxAnisotropyBit",
        "textureFiltering", "biomeBlendRadius", "entityShadows", "entityDistanceScaling", "chunkSectionFadeInTime",
        "prioritizeChunkUpdates", "cutoutLeaves", "vignette", "weatherRadius", "inactivityFpsLimit", "fov", "guiScale",
    };

    private static long[] countersAtBegin;
    private static boolean prepared;

    private ConfigBlock() {
    }

    /** During warmup: resolve the backend and bind the native queries (first use costs milliseconds). */
    static void prepare() {
        if (!ENABLED || prepared) {
            return;
        }
        prepared = true;
        MetalTraceHook.resolve();
        countersAtBegin = MetalTraceHook.nativeCounters();
        MetalTraceHook.nativeConfig();
    }

    /** Start of MEASURE: one native counters read. */
    static void begin() {
        if (!ENABLED) {
            return;
        }
        prepare();
        countersAtBegin = MetalTraceHook.nativeCounters();
    }

    /** The {@code "config"} JSON value. {@code mc} is the Minecraft instance (Object: no game types in this class). */
    static String json(final Object mc, final long benchFrames) {
        if (!ENABLED) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\n    \"jvm\": ").append(jvm());
        sb.append(",\n    \"properties\": ").append(properties());
        sb.append(",\n    \"env\": ").append(env());
        sb.append(",\n    \"backendClass\": ").append(FrameTrace.quote(MetalTraceHook.backendClass));
        boolean metal = MetalTraceHook.backendClass.startsWith(METAL);
        sb.append(",\n    \"toggles\": ").append(toggles(metal));
        String nativeConfig = metal ? MetalTraceHook.nativeConfig() : null;
        sb.append(",\n    \"native\": ").append(nativeConfig == null ? "null" : nativeConfig);
        sb.append(",\n    \"measured\": ").append(measured(nativeConfig, benchFrames));
        sb.append(",\n    \"frameTrace\": {\"enabled\": ").append(FrameTrace.ENABLED).append(", \"metalStatus\": ")
            .append(FrameTrace.quote(MetalTraceHook.status())).append(", \"guardTripsSource\": ")
            .append(FrameTrace.quote(MetalTraceHook.guardSource)).append('}');
        sb.append(",\n    \"game\": ").append(game(mc));
        sb.append(",\n    \"jars\": ").append(jars());
        return sb.append("\n  }").toString();
    }

    private static String jvm() {
        StringBuilder sb = new StringBuilder("{");
        try {
            RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
            sb.append("\"version\": ").append(FrameTrace.quote(System.getProperty("java.runtime.version")));
            sb.append(", \"vm\": ").append(FrameTrace.quote(rt.getVmName() + " " + rt.getVmVersion()));
            sb.append(", \"processors\": ").append(Runtime.getRuntime().availableProcessors());
            sb.append(", \"maxHeapMB\": ").append(Runtime.getRuntime().maxMemory() >> 20);
            sb.append(", \"gc\": [");
            boolean first = true;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                sb.append(first ? "" : ", ").append(FrameTrace.quote(gc.getName()));
                first = false;
            }
            sb.append("], \"args\": [");
            first = true;
            for (String arg : rt.getInputArguments()) {
                sb.append(first ? "" : ", ").append(FrameTrace.quote(arg));
                first = false;
            }
            sb.append(']');
        } catch (Throwable t) {
            sb.append("\"error\": ").append(FrameTrace.quote(t.toString()));
        }
        return sb.append('}').toString();
    }

    private static String properties() {
        Map<String, String> out = new TreeMap<>();
        for (String key : System.getProperties().stringPropertyNames()) {
            for (String prefix : PROPERTY_PREFIXES) {
                if (key.startsWith(prefix)) {
                    out.put(key, System.getProperty(key));
                    break;
                }
            }
        }
        return object(out);
    }

    private static String env() {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            String key = e.getKey();
            boolean keep = List.of(ENV_NAMES).contains(key);
            for (String prefix : ENV_PREFIXES) {
                keep |= key.startsWith(prefix);
            }
            if (keep) {
                out.put(key, e.getValue());
            }
        }
        return object(out);
    }

    private static String object(final Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            sb.append(first ? "" : ", ").append(FrameTrace.quote(e.getKey())).append(": ").append(FrameTrace.quote(e.getValue()));
            first = false;
        }
        return sb.append('}').toString();
    }

    /** {"Class": {"FIELD": value, ...}, ...}: static primitive / String / String[] fields, any visibility. */
    private static String toggles(final boolean metal) {
        List<String> classes = new ArrayList<>(List.of(COMMON_CLASSES));
        if (metal) {
            classes.addAll(List.of(METAL_CLASSES));
        }
        String extra = System.getProperty("bench.configClasses");
        if (extra != null) {
            for (String c : extra.split(",")) {
                if (!c.isBlank()) {
                    classes.add(c.trim());
                }
            }
        }
        StringBuilder sb = new StringBuilder("{");
        boolean firstClass = true;
        for (String name : classes) {
            Class<?> c;
            try {
                c = Class.forName(name);
            } catch (Throwable absent) {
                continue;
            }
            StringBuilder fields = new StringBuilder();
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (!Modifier.isStatic(mod) || f.isSynthetic()) {
                    continue;
                }
                Class<?> type = f.getType();
                boolean simple = type.isPrimitive() || type == String.class || type == Boolean.class || type == Integer.class
                    || type == Long.class || type == Double.class || type == Float.class;
                if (!simple && type != String[].class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    String value;
                    if (v == null) {
                        value = "null";
                    } else if (v instanceof String s) {
                        value = FrameTrace.quote(s);
                    } else if (v instanceof String[] array) {
                        value = FrameTrace.quote(String.join(",", array));
                    } else if (v instanceof Character ch) {
                        value = FrameTrace.quote(String.valueOf(ch));
                    } else if (v instanceof Double d && (d.isNaN() || d.isInfinite()) || v instanceof Float fl && (fl.isNaN() || fl.isInfinite())) {
                        value = FrameTrace.quote(v.toString());
                    } else {
                        value = v.toString();
                    }
                    fields.append(fields.isEmpty() ? "" : ", ").append('"').append(f.getName()).append("\": ").append(value);
                } catch (Throwable ignored) {
                }
            }
            sb.append(firstClass ? "" : ", ").append(FrameTrace.quote(c.getSimpleName())).append(": {").append(fields).append('}');
            firstClass = false;
        }
        return sb.append('}').toString();
    }

    /** Native decoder counters over MEASURE (end - begin), with the derived shares. */
    private static String measured(final String nativeConfig, final long benchFrames) {
        long[] end = MetalTraceHook.backendClass.startsWith(METAL) ? MetalTraceHook.nativeCounters() : null;
        long[] begin = countersAtBegin;
        if (end == null || begin == null || nativeConfig == null) {
            return "null";
        }
        List<String> names = counterNames(nativeConfig);
        int count = Math.min(Math.min(end.length, begin.length), names.size());
        Map<String, Long> d = new TreeMap<>();
        StringBuilder sb = new StringBuilder("{\"counters\": {");
        for (int i = 0; i < count; i++) {
            long delta = end[i] - begin[i];
            d.put(names.get(i), delta);
            sb.append(i == 0 ? "" : ", ").append(FrameTrace.quote(names.get(i))).append(": ").append(delta);
        }
        sb.append('}');
        long frames = d.getOrDefault("frames", 0L);
        long records = d.getOrDefault("presentRecords", 0L);
        sb.append(", \"benchFrames\": ").append(benchFrames);
        share(sb, "fusedShare", d.getOrDefault("fusedFrames", 0L), records);
        share(sb, "presentedShare", d.getOrDefault("presentsEncoded", 0L), records);
        share(sb, "skippedShare", d.getOrDefault("presentsSkipped", 0L), records);
        share(sb, "mailboxShare", d.getOrDefault("mailboxRecords", 0L), records);
        share(sb, "drawableWaitMsPerFrame", d.getOrDefault("drawableWaitNs", 0L) / 1e6, frames);
        share(sb, "nativeSubmitMsPerFrame", d.getOrDefault("submitNs", 0L) / 1e6, frames);
        share(sb, "commandBuffersPerFrame", d.getOrDefault("commandBuffersCommitted", 0L), frames);
        share(sb, "untimedCommitsPerFrame", d.getOrDefault("untimedCommits", 0L), frames);
        share(sb, "icbGroupsPerFrame", d.getOrDefault("icbGroups", 0L), frames);
        share(sb, "occlusionFrameShare", d.getOrDefault("occlFrames", 0L), frames);
        sb.append(", \"fusionFallbacks\": {");
        boolean first = true;
        for (Map.Entry<String, Long> e : d.entrySet()) {
            if (e.getKey().startsWith("fuseFallback.") && e.getValue() != 0L) {
                sb.append(first ? "" : ", ").append(FrameTrace.quote(e.getKey().substring(13))).append(": ").append(e.getValue());
                first = false;
            }
        }
        sb.append("}, \"presentSkips\": {");
        first = true;
        for (Map.Entry<String, Long> e : d.entrySet()) {
            if (e.getKey().startsWith("presentSkip.") && e.getValue() != 0L) {
                sb.append(first ? "" : ", ").append(FrameTrace.quote(e.getKey().substring(12))).append(": ").append(e.getValue());
                first = false;
            }
        }
        return sb.append("}}").toString();
    }

    private static void share(final StringBuilder sb, final String key, final double num, final long den) {
        sb.append(", \"").append(key).append("\": ").append(den > 0 ? String.format(Locale.ROOT, "%.4f", num / den) : "null");
    }

    /** The {@code counterNames} array of the native config JSON (a flat list of quoted strings). */
    static List<String> counterNames(final String nativeConfig) {
        List<String> names = new ArrayList<>();
        int at = nativeConfig.indexOf("\"counterNames\"");
        if (at < 0) {
            return names;
        }
        int open = nativeConfig.indexOf('[', at);
        int close = nativeConfig.indexOf(']', open);
        if (open < 0 || close < 0) {
            return names;
        }
        for (String part : nativeConfig.substring(open + 1, close).split(",")) {
            String s = part.trim();
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                names.add(s.substring(1, s.length() - 1));
            }
        }
        return names;
    }

    private static String game(final Object mc) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        try {
            Object options = mc.getClass().getField("options").get(mc);
            for (String name : OPTIONS) {
                try {
                    Object instance = options.getClass().getMethod(name).invoke(options);
                    Object value = instance.getClass().getMethod("get").invoke(instance);
                    sb.append(first ? "" : ", ").append('"').append(name).append("\": ").append(FrameTrace.quote(String.valueOf(value)));
                    first = false;
                } catch (Throwable ignored) {
                }
            }
            Object window = mc.getClass().getMethod("getWindow").invoke(mc);
            for (String name : new String[]{"isFullscreen", "getWidth", "getHeight", "getGuiScale"}) {
                try {
                    Object value = window.getClass().getMethod(name).invoke(window);
                    sb.append(first ? "" : ", ").append("\"window.").append(name).append("\": ").append(FrameTrace.quote(String.valueOf(value)));
                    first = false;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            sb.append(first ? "" : ", ").append("\"error\": ").append(FrameTrace.quote(t.toString()));
        }
        return sb.append('}').toString();
    }

    private static String jars() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"client\": ").append(fileInfo(codeSource("net.minecraft.client.Minecraft")));
        sb.append(", \"bench\": ").append(fileInfo(codeSource("bench.Bench")));
        String dylib = null;
        try {
            Object p = Class.forName(METAL + "MtlNative$Loader").getMethod("path").invoke(null);
            dylib = p == null ? null : p.toString();
        } catch (Throwable ignored) {
        }
        sb.append(", \"dylib\": ").append(dylib == null ? "null" : fileInfo(Path.of(dylib)));
        String packaged = null;
        try (InputStream in = ConfigBlock.class.getClassLoader()
            .getResourceAsStream("META-INF/macos/arm64/com/mojang/renderpearl/backend/metal/libmcmetal.dylib.sha1")) {
            if (in != null) {
                packaged = new String(in.readAllBytes()).trim();
            }
        } catch (Throwable ignored) {
        }
        sb.append(", \"dylibPackagedSha1\": ").append(FrameTrace.quote(packaged));
        return sb.append('}').toString();
    }

    private static Path codeSource(final String className) {
        try {
            URL url = Class.forName(className).getProtectionDomain().getCodeSource().getLocation();
            return Path.of(url.toURI());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String fileInfo(final Path path) {
        if (path == null) {
            return "null";
        }
        String sha1 = null;
        long size = -1L;
        try {
            if (Files.isRegularFile(path)) {
                size = Files.size(path);
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                try (InputStream in = Files.newInputStream(path)) {
                    byte[] buffer = new byte[1 << 16];
                    for (int r; (r = in.read(buffer)) > 0; ) {
                        md.update(buffer, 0, r);
                    }
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : md.digest()) {
                    hex.append(String.format(Locale.ROOT, "%02x", b));
                }
                sha1 = hex.toString();
            }
        } catch (Throwable ignored) {
        }
        return "{\"path\": " + FrameTrace.quote(path.toString()) + ", \"size\": " + size + ", \"sha1\": " + FrameTrace.quote(sha1) + "}";
    }
}
