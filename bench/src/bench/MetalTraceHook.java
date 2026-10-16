package bench;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflection bridge to the Metal backend's frame trace ({@code com.mojang.renderpearl.backend.metal.MetalFrameTrace},
 * docs/metal/CONTRACT.md 16.R5-B) and to the late-throttle guard-trip counter, so the bench still runs on the vanilla
 * jar, on other backends and on client builds without these classes (every accessor then reports "absent").
 *
 * <p>Per frame only allocation-free accesses are used: the {@code long[] C} counters array (element reads) and exact-typed
 * method handles ({@code ()V} sample, {@code ()J} guard trips) called with {@code invokeExact}. Everything else (native
 * counters, config, GPU records) is called a few times per run.
 */
final class MetalTraceHook {
    static final String TRACE = "com.mojang.renderpearl.backend.metal.MetalFrameTrace";
    /**
     * Where the late-throttle guard-trip count may live (CONTRACT.md 16.R5-B: guard trips); the first that resolves wins.
     * {@code -Dbench.guardTrips=fqcn#member} (static or backend-instance field / no-arg method of type long / int /
     * long[] (element 0) / AtomicLong / LongAdder) is tried first.
     */
    static final String[] GUARD_CANDIDATES = {
        "com.mojang.renderpearl.backend.metal.MetalLateThrottle#guardTrips",
        "com.mojang.renderpearl.backend.metal.MetalLateThrottle#GUARD_TRIPS",
        "com.mojang.renderpearl.backend.metal.LateThrottle#guardTrips",
        "com.mojang.renderpearl.backend.metal.MetalFrameThrottle#guardTrips",
        "com.mojang.renderpearl.backend.metal.MetalDevice#guardTrips",
        "com.mojang.renderpearl.backend.metal.MetalDevice#throttleGuardTrips",
        "com.mojang.renderpearl.backend.metal.MetalCommandEncoder#guardTrips",
        "com.mojang.renderpearl.backend.metal.MetalTimeline#guardTrips",
    };

    private static boolean resolved;
    private static Class<?> trace;
    /** MetalFrameTrace.C, or null. */
    static long[] counters;
    static int sFrames, sCommits, sSubmitNs, sWaitNs, sDrainNs, sEncoders, sDraws, sLastSeq, sLastFlags, sBlocksCreated,
        sBlocksFreed, sGuardTrips = -1;
    private static MethodHandle sample;
    /** {@code ()J} guard-trip source, or null; {@link #guardSource} names it. */
    private static MethodHandle guard;
    static String guardSource;
    /** The device's GpuDeviceBackend (MetalDevice on Metal), or null. */
    static Object backend;
    static String backendClass = "unknown";

    private MetalTraceHook() {
    }

    /** Resolves everything once (call during warmup, never in the measured window). */
    static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        backend = findBackend();
        backendClass = backend == null ? "unknown" : backend.getClass().getName();
        try {
            trace = Class.forName(TRACE);
            counters = (long[]) trace.getField("C").get(null);
            sFrames = slot("FRAMES");
            sCommits = slot("COMMITS");
            sSubmitNs = slot("SUBMIT_NATIVE_NS");
            sWaitNs = slot("WAIT_NS");
            sDrainNs = slot("DRAIN_NS");
            sEncoders = slot("ENCODERS");
            sDraws = slot("DRAWS");
            sLastSeq = slot("LAST_SEQ");
            sLastFlags = slot("LAST_RESULT_FLAGS");
            sBlocksCreated = slot("BLOCKS_CREATED");
            sBlocksFreed = slot("BLOCKS_FREED");
            sGuardTrips = slot("GUARD_TRIPS");
            sample = MethodHandles.publicLookup().findStatic(trace, "sample", MethodType.methodType(void.class));
        } catch (Throwable t) {
            trace = null;
            counters = null;
            sample = null;
        }
        resolveGuard();
    }

    private static int slot(final String name) throws ReflectiveOperationException {
        return trace.getField(name).getInt(null);
    }

    /** True when the backend's per-frame counters are live (Metal backend, trace class present and enabled). */
    static boolean live() {
        return counters != null && backendClass.startsWith("com.mojang.renderpearl.backend.metal.") && enabled();
    }

    static boolean enabled() {
        try {
            return trace != null && trace.getField("ENABLED").getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Per frame: refresh derived slots. No allocation. */
    static void sample() {
        MethodHandle h = sample;
        if (h != null) {
            try {
                h.invokeExact();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Per frame: cumulative guard trips, or -1 when no source resolved. No allocation. */
    static long guardTrips() {
        MethodHandle h = guard;
        if (h == null) {
            return -1L;
        }
        try {
            return (long) h.invokeExact();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static Object call(final String method, final Class<?>[] types, final Object... args) {
        if (trace == null) {
            return null;
        }
        try {
            return trace.getMethod(method, types).invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

    static String status() {
        Object s = call("status", new Class<?>[0]);
        return s == null ? null : s.toString();
    }

    static String clockJson() {
        Object s = call("clockJson", new Class<?>[0]);
        return s == null ? null : s.toString();
    }

    static long[] nativeCounters() {
        return (long[]) call("nativeCounters", new Class<?>[]{Object.class}, backend);
    }

    static String nativeConfig() {
        Object s = call("nativeConfig", new Class<?>[]{Object.class}, backend);
        return s == null ? null : s.toString();
    }

    /** MetalFrameTrace.gpuRecords; -1 when unavailable. */
    static int gpuRecords(final long firstSeq, final int count, final long[] out) {
        Object r = call("gpuRecords", new Class<?>[]{long.class, int.class, long[].class}, firstSeq, count, out);
        return r instanceof Integer i ? i : -1;
    }

    /** RenderSystem.getDevice() is the frontend; its private {@code backend} field holds the GpuDeviceBackend. */
    private static Object findBackend() {
        try {
            Object device = Class.forName("com.mojang.blaze3d.systems.RenderSystem").getMethod("getDevice").invoke(null);
            for (Class<?> c = device.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && f.getType().getName().endsWith("GpuDeviceBackend")) {
                        f.setAccessible(true);
                        return f.get(device);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void resolveGuard() {
        List<String> specs = new ArrayList<>();
        String override = System.getProperty("bench.guardTrips");
        if (override != null && !override.isBlank()) {
            specs.add(override.trim());
        }
        specs.addAll(List.of(GUARD_CANDIDATES));
        // MetalFrameTrace#guardTrips (fed by MetalFrameTrace.countGuardTrip()) is deliberately not a default: it reads 0
        // unless a guard is wired to it, which would pass for "no trips". Name it with -Dbench.guardTrips when wired.
        for (String spec : specs) {
            MethodHandle h = longSource(spec, backend);
            if (h != null) {
                guard = h;
                guardSource = spec;
                return;
            }
        }
    }

    /**
     * {@code ()J} handle reading {@code fqcn#member}: a static or instance field (long, int, long[] element 0, AtomicLong,
     * LongAdder) or a no-arg method returning long / int. Instance members are bound to {@code backendDevice} or to an
     * object one of its no-arg methods returns. Null when it does not resolve.
     */
    static MethodHandle longSource(final String spec, final Object backendDevice) {
        int hash = spec.indexOf('#');
        if (hash <= 0) {
            return null;
        }
        try {
            Class<?> c = Class.forName(spec.substring(0, hash));
            String member = spec.substring(hash + 1);
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodType target = MethodType.methodType(long.class);
            Field field = findField(c, member);
            if (field != null) {
                field.setAccessible(true);
                boolean isStatic = Modifier.isStatic(field.getModifiers());
                Object receiver = isStatic ? null : receiverFor(c, backendDevice);
                if (!isStatic && receiver == null) {
                    return null;
                }
                Class<?> type = field.getType();
                if (type == long.class || type == int.class) {
                    MethodHandle getter = lookup.unreflectGetter(field);
                    return (isStatic ? getter : getter.bindTo(receiver)).asType(target);
                }
                Object value = field.get(receiver);
                if (value instanceof long[] array && array.length > 0) {
                    MethodHandle element = MethodHandles.arrayElementGetter(long[].class).bindTo(array);
                    return MethodHandles.insertArguments(element, 0, 0).asType(target);
                }
                if (value instanceof java.util.concurrent.atomic.AtomicLong atomic) {
                    return lookup.findVirtual(java.util.concurrent.atomic.AtomicLong.class, "get", target).bindTo(atomic);
                }
                if (value instanceof java.util.concurrent.atomic.LongAdder adder) {
                    return lookup.findVirtual(java.util.concurrent.atomic.LongAdder.class, "sum", target).bindTo(adder);
                }
                return null;
            }
            Method method = findMethod(c, member);
            if (method == null || (method.getReturnType() != long.class && method.getReturnType() != int.class)) {
                return null;
            }
            method.setAccessible(true);
            MethodHandle handle = lookup.unreflect(method);
            if (!Modifier.isStatic(method.getModifiers())) {
                Object receiver = receiverFor(c, backendDevice);
                if (receiver == null) {
                    return null;
                }
                handle = handle.bindTo(receiver);
            }
            return handle.asType(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(final Class<?> c, final String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(final Class<?> c, final String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /** {@code backendDevice} if it is a {@code c}, else the result of one of its public no-arg methods returning a {@code c}. */
    private static Object receiverFor(final Class<?> c, final Object backendDevice) {
        if (backendDevice == null) {
            return null;
        }
        if (c.isInstance(backendDevice)) {
            return backendDevice;
        }
        for (Method m : backendDevice.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && c.isAssignableFrom(m.getReturnType())) {
                try {
                    return m.invoke(backendDevice);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }
}
