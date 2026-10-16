import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.backend.metal.MetalBackend;
import com.mojang.renderpearl.backend.metal.MtlNative;
import com.mojang.renderpearl.backend.metal.StreamOps;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Headless proof of the Metal backend load path and ABI (architect-owned; run with tools/metal-smoke.sh).
 *
 *  0. Before loading anything: MtlNative constants and pure helpers (status page reads on heap memory, error
 *     mapping, lastError) work without the dylib (CONTRACT.md 11.3: Java unit tests need no natives).
 *  1. new MetalBackend().loadLibrary(): LWJGL extracts libmcmetal.dylib from build/client.jar, MtlNative binds
 *     every export through FFM, verifyAbi() compares mcmtl_abi_version + every struct size, mcmtl_probe calls
 *     MTLCreateSystemDefaultDevice.
 *  2. Cross-checks every MCMTL_ASSERT_OFFSET / MCMTL_ASSERT_SIZE and every numeric #define of
 *     native/include/mcmetal.h and mcmetal_stream.h against MtlNative / StreamOps (naming rule of
 *     CONTRACT.md 3.6), including the StructLayouts, and the reverse direction: every public static final
 *     int/long of MtlNative / StreamOps must correspond to a header define, offset or size.
 *  3. Calls a few exports directly (error plumbing, stubs) and createDevice (expects a clean
 *     BackendCreationException while MetalDevice is a skeleton).
 *
 * Usage: java --enable-native-access=ALL-UNNAMED -cp build/client.jar:<libraries> tools/MetalSmoke.java [repoRoot]
 * Exit code 0 = all checks passed.
 */
public class MetalSmoke {
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        System.out.println("java " + System.getProperty("java.version") + " (" + System.getProperty("java.vm.vendor") + "), os.arch "
            + System.getProperty("os.arch"));

        // ---- 0. pure helpers without the library ---------------------------------------------
        check(!MtlNative.Loader.isLoaded(), "library must not be loaded before step 1");
        MemorySegment fakePage = MemorySegment.ofArray(new long[(int)(MtlNative.STATUS_PAGE_SIZEOF / 8)]);
        fakePage.set(ValueLayout.JAVA_LONG, MtlNative.STATUS_PAGE_COMPLETED_VALUE, 41L);
        fakePage.set(ValueLayout.JAVA_INT, MtlNative.STATUS_PAGE_ERROR_COUNT, 3);
        check(MtlNative.statusLong(fakePage, MtlNative.STATUS_PAGE_COMPLETED_VALUE) == 41L, "statusLong on heap memory");
        check(MtlNative.statusInt(fakePage, MtlNative.STATUS_PAGE_ERROR_COUNT) == 3, "statusInt on heap memory");
        check(MtlNative.lastError().isEmpty(), "lastError() without the library");
        check(MtlNative.exceptionFor(MtlNative.E_DEVICE_LOST, "x") instanceof com.mojang.renderpearl.api.device.GpuDeviceLossException,
            "exceptionFor without the library");
        check(!MtlNative.Loader.isLoaded(), "pure helpers must not load the library");
        System.out.println("pure helpers without libmcmetal: OK (" + MtlNative.statusName(MtlNative.E_BAD_STREAM) + ")");

        // ---- 1. real load path --------------------------------------------------------------
        MetalBackend backend = new MetalBackend();
        long t0 = System.nanoTime();
        backend.loadLibrary();
        long loadMicros = (System.nanoTime() - t0) / 1000;
        MetalBackend.ProbeInfo probe = backend.probeInfo();
        System.out.printf(Locale.ROOT, "loadLibrary OK in %d us: %s%n", loadMicros, MtlNative.Loader.path());
        System.out.printf(Locale.ROOT, "mcmtl_abi_version() = %d (Java ABI_VERSION %d, stream version %d), %d struct sizes verified%n",
            MtlNative.abiVersion(), MtlNative.ABI_VERSION, StreamOps.STREAM_VERSION, MtlNative.verifiedStructCount());
        System.out.printf(Locale.ROOT, "probe: device '%s', families 0x%x%s, macOS %d.%d.%d, unified memory %s, maxBufferLength %d MiB, "
                + "recommendedMaxWorkingSetSize %d MiB%n",
            probe.deviceName(), probe.familyMask(), familyNames(probe.familyMask()), probe.osMajor(), probe.osMinor(), probe.osPatch(),
            probe.unifiedMemory(), probe.maxBufferLength() >> 20, probe.recommendedMaxWorkingSetSize() >> 20);
        backend.loadLibrary(); // idempotent
        check(MtlNative.timelineCompleted(0L) == 0L, "critical getter on a null handle returns 0");

        // ---- 2. header <-> Java cross-check ----------------------------------------------------
        int[] mcmetal = crossCheck(root.resolve("native/include/mcmetal.h"), MtlNative.class, "MtlNative");
        int[] stream = crossCheck(root.resolve("native/include/mcmetal_stream.h"), StreamOps.class, "StreamOps");
        System.out.printf(Locale.ROOT, "header cross-check: mcmetal.h %d offsets, %d sizes, %d defines; mcmetal_stream.h %d offsets, %d sizes, %d defines%n",
            mcmetal[0], mcmetal[1], mcmetal[2], stream[0], stream[1], stream[2]);
        System.out.printf(Locale.ROOT, "reverse cross-check: %d MtlNative + %d StreamOps constants checked against the headers%n",
            mcmetal[3], stream[3]);
        check(StreamOps.header(StreamOps.OP_DRAW, 32, 7) == (48L | (32L << 16) | (7L << 32)), "StreamOps.header packing");

        // ---- 3. direct calls ---------------------------------------------------------------------
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long desc = stack.ncalloc(8, 1, (int)MtlNative.DEVICE_DESC_SIZEOF);
            MemoryUtil.memPutInt(desc + MtlNative.DEVICE_DESC_STRUCT_SIZE, (int)MtlNative.DEVICE_DESC_SIZEOF);
            MemoryUtil.memPutInt(desc + MtlNative.DEVICE_DESC_FRAMES_IN_FLIGHT, 2);
            long out = stack.ncalloc(8, 1, 8);
            int status = MtlNative.deviceCreate(desc, out);
            String error = MtlNative.lastError();
            System.out.printf(Locale.ROOT, "mcmtl_device_create -> %s%s%n", MtlNative.statusName(status), error.isEmpty() ? "" : " (" + error + ")");
            check(status == MtlNative.OK || status == MtlNative.E_UNIMPLEMENTED, "device_create returns OK or E_UNIMPLEMENTED");
            if (status == MtlNative.OK) {
                MtlNative.check(MtlNative.deviceDestroy(MemoryUtil.memGetLong(out)), "mcmtl_device_destroy");
            }
            long badProbe = stack.ncalloc(8, 1, (int)MtlNative.PROBE_INFO_SIZEOF);
            MemoryUtil.memPutInt(badProbe, 1);
            int badStatus = MtlNative.probe(badProbe);
            check(badStatus == MtlNative.E_ABI_MISMATCH, "probe rejects a wrong structSize");
            try {
                MtlNative.check(badStatus, "mcmtl_probe");
                check(false, "check() must throw");
            } catch (IllegalStateException expected) {
                System.out.println("error mapping: " + expected.getMessage());
            }
        }
        try {
            backend.createDevice(new GpuDebugOptions(0, false, false, false));
            System.out.println("createDevice: returned a device (MetalDevice implemented)");
        } catch (BackendCreationException e) {
            System.out.println("createDevice: BackendCreationException (" + e.getReason().displayName() + "): " + e.getMessage());
        }

        System.out.println(failures == 0 ? "METAL SMOKE PASSED" : "METAL SMOKE FAILED (" + failures + " failure(s))");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String familyNames(int mask) {
        String[] names = {"Apple7", "Apple8", "Apple9", "Apple10", "Mac2", "Metal3", "Metal4"};
        List<String> present = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            if ((mask & (1 << i)) != 0) {
                present.add(names[i]);
            }
        }
        return " [" + String.join(", ", present) + "]";
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    /** CONTRACT.md 3.6 naming rule: camelCase field -> UPPER_SNAKE. */
    static String snake(String camel) {
        String s = camel.replaceAll("([a-z])([A-Z])", "$1_$2");
        s = s.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");
        return s.toUpperCase(Locale.ROOT);
    }

    private static final Pattern OFFSET = Pattern.compile("MCMTL_ASSERT_OFFSET\\((\\w+), (\\w+), (\\d+)\\)");
    private static final Pattern SIZE = Pattern.compile("MCMTL_ASSERT_SIZE\\((\\w+), (\\d+)\\)");
    private static final Pattern DEFINE = Pattern.compile("^#define (MCMTL_\\w+)\\s+(.+?)\\s*(/\\*.*)?$", Pattern.MULTILINE);
    private static final Pattern SHIFT = Pattern.compile("\\(1u? << (\\d+)\\)");
    private static final Pattern NUMBER = Pattern.compile("(-?\\d+)u?");

    private static int[] crossCheck(Path header, Class<?> mirror, String mirrorName) throws Exception {
        String text = Files.readString(header);
        boolean stream = mirror == StreamOps.class;
        int offsets = 0;
        int sizes = 0;
        int defines = 0;
        Class<?> layouts = Class.forName(mirror.getName() + "$Layouts");
        Set<String> expectedNames = new HashSet<>();

        Matcher m = OFFSET.matcher(text);
        while (m.find()) {
            String prefix = prefix(m.group(1), stream);
            long expected = Long.parseLong(m.group(3));
            String constant = prefix + "_" + snake(m.group(2));
            expectedNames.add(constant);
            Long actual = constant(mirror, constant);
            check(actual != null && actual == expected, mirrorName + "." + constant + " = " + actual + ", header says " + expected);
            StructLayout layout = (StructLayout)layouts.getField(prefix).get(null);
            long layoutOffset = layout.byteOffset(MemoryLayout.PathElement.groupElement(m.group(2)));
            check(layoutOffset == expected, mirrorName + ".Layouts." + prefix + "." + m.group(2) + " offset " + layoutOffset + " != " + expected);
            offsets++;
        }
        m = SIZE.matcher(text);
        while (m.find()) {
            String prefix = prefix(m.group(1), stream);
            long expected = Long.parseLong(m.group(2));
            expectedNames.add(prefix + "_SIZEOF");
            Long actual = constant(mirror, prefix + "_SIZEOF");
            check(actual != null && actual == expected, mirrorName + "." + prefix + "_SIZEOF = " + actual + ", header says " + expected);
            StructLayout layout = (StructLayout)layouts.getField(prefix).get(null);
            check(layout.byteSize() == expected, mirrorName + ".Layouts." + prefix + " byteSize " + layout.byteSize() + " != " + expected);
            sizes++;
        }
        m = DEFINE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String value = m.group(2).trim();
            if (name.equals("MCMTL_API") || name.endsWith("_H")) {
                continue;
            }
            Long expected = evaluate(value);
            if (expected == null) {
                continue; // non-numeric macro
            }
            String constant = name.substring("MCMTL_".length());
            if (stream && constant.startsWith("CMD_SIZE_")) {
                constant = "SIZE_" + constant.substring("CMD_SIZE_".length());
            }
            expectedNames.add(constant);
            Long actual = constant(mirror, constant);
            check(actual != null && actual.longValue() == expected.longValue(), mirrorName + "." + constant + " = " + actual + ", header " + name + " = " + value);
            defines++;
        }
        // Reverse direction: no Java constant without a header counterpart (catches strays and misspellings).
        int reverse = 0;
        for (Field field : mirror.getDeclaredFields()) {
            int mod = field.getModifiers();
            Class<?> type = field.getType();
            if (!Modifier.isPublic(mod) || !Modifier.isStatic(mod) || !Modifier.isFinal(mod) || (type != int.class && type != long.class)) {
                continue;
            }
            check(expectedNames.contains(field.getName()), mirrorName + "." + field.getName() + " has no counterpart in " + header.getFileName());
            reverse++;
        }
        return new int[]{offsets, sizes, defines, reverse};
    }

    private static String prefix(String struct, boolean stream) {
        String base = stream ? struct.substring("mcmtl_cmd_".length()) : struct.substring("mcmtl_".length());
        return base.toUpperCase(Locale.ROOT);
    }

    private static Long evaluate(String value) {
        if (value.equals("UINT64_MAX")) {
            return -1L;
        }
        Matcher shift = SHIFT.matcher(value);
        if (shift.matches()) {
            return 1L << Integer.parseInt(shift.group(1));
        }
        Matcher number = NUMBER.matcher(value);
        if (number.matches()) {
            return Long.parseLong(number.group(1));
        }
        return null;
    }

    private static Long constant(Class<?> mirror, String name) {
        try {
            Field field = mirror.getField(name);
            Object value = field.get(null);
            return value instanceof Number n ? n.longValue() : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
