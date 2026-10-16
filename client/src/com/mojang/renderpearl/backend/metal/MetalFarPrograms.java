package com.mojang.renderpearl.backend.metal;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Optional far-image PSOs built from the game's actually selected function, on a worker.
 * Request never compiles/waits/submits. It returns zero while pending or unsupported.
 * Native recipes pin original pipelines across reload; completion owns its independent PSO.
 * These programs do not render or replace any game draw by themselves.
 */
public final class MetalFarPrograms {
    public static final boolean REQUESTED = Boolean.getBoolean("mc.lod.async");
    private static final int MAX_PROGRAMS = 64;
    private static final IdentityHashMap<MetalDevice, Cache> CACHES = new IdentityHashMap<>();
    private static final class Worker {
        static final ExecutorService INSTANCE = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Metal far pipeline"); t.setDaemon(true); return t;
        });
    }
    private static boolean unavailable;
    private MetalFarPrograms() { }

    /** Render thread. Borrowed +0 handle; a snapshot must retain it before cache/device close.
     * Layer code 0/1/8/9 only. Faded and blended paths stay synchronous.
     */
    public static long request(MetalRenderPipeline pipeline, int code) {
        if (!REQUESTED || unavailable || (code & ~9) != 0 || (code & 7) > 1) return 0;
        RenderSystem.assertOnRenderThread();
        MetalDevice device = pipeline.device();
        if (pipeline.isClosed() || device.isClosed() || !device.shaderCache().farSourcesAvailable()) return 0;
        Cache cache = CACHES.computeIfAbsent(device, Cache::new);
        Key key = new Key(pipeline, pipeline.handle(), code);
        Entry entry = cache.entries.get(key);
        if (entry != null) return entry.program;
        if (cache.entries.size() >= MAX_PROGRAMS) return 0;
        entry = new Entry();
        cache.entries.put(key, entry);
        long recipe = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = stack.ncalloc(8, 1, 8);
            int status = (int)Native.PIN.invokeExact(device.handle(), key.pipeline, code, out);
            if (status != MtlNative.OK) {
                LogUtils.getLogger().warn("Far pipeline capture declined: layer={}, status={}", code, status);
                return 0;
            }
            recipe = MemoryUtil.memGetLong(out);
            if (recipe == 0) return 0;
            long ownedRecipe = recipe;
            Entry ownedEntry = entry;
            Worker.INSTANCE.execute(() -> compile(cache, ownedEntry, ownedRecipe, code));
            recipe = 0; // task owns it, including rejection after device close.
        } catch (Throwable error) {
            unavailable = true;
            if (error instanceof VirtualMachineError fatal) throw fatal;
            LogUtils.getLogger().warn("Far pipeline bridge unavailable; synchronous terrain retained", error);
        } finally {
            if (recipe != 0) drop(recipe);
        }
        return entry.program;
    }

    private static void compile(Cache cache, Entry entry, long recipe, int code) {
        long program = 0;
        boolean entered = false;
        try {
            if (cache.closed || !(entered = cache.device.beginCompile())) return;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long out = stack.ncalloc(8, 1, 8), error = stack.ncalloc(1, 1, 1024);
                int status = (int)Native.COMPILE.invokeExact(recipe, out, error, 1024);
                program = MemoryUtil.memGetLong(out);
                if (status != MtlNative.OK || program == 0) {
                    LogUtils.getLogger().warn("Far ID pipeline unsupported: layer={}, status={}, {}",
                        code, status, MemoryUtil.memUTF8(error));
                    return;
                }
            }
            synchronized (cache) {
                if (!cache.closed) {
                    entry.program = program; // release publication of a fully built immutable native object.
                    program = 0;
                    LogUtils.getLogger().info("Far ID pipeline ready: layer={} (worker compile; draws unchanged)", code);
                }
            }
        } catch (Throwable error) {
            if (error instanceof VirtualMachineError fatal) throw fatal;
            LogUtils.getLogger().warn("Far ID pipeline failed; synchronous terrain retained", error);
        } finally {
            if (program != 0) drop(program);
            drop(recipe);
            if (entered) cache.device.endCompile();
        }
    }

    /** Render thread, before native device shutdown. Never waits for queued or running work. */
    static void closeDevice(MetalDevice device) {
        Cache cache = CACHES.remove(device);
        if (cache == null) return;
        synchronized (cache) {
            cache.closed = true;
            for (Entry entry : cache.entries.values()) {
                long program = entry.program; entry.program = 0;
                if (program != 0) drop(program);
            }
            cache.entries.clear();
        }
        // Pending worker tasks own their recipes and observe closed before making device calls.
    }

    private static void drop(long object) {
        try { Native.DROP.invokeExact(object); }
        catch (Throwable error) {
            if (error instanceof VirtualMachineError fatal) throw fatal;
            LogUtils.getLogger().error("Far program release failed", error);
        }
    }
    /** Render thread: pin a ready program before transferring to a worker. Zero stays unavailable. */
    public static long retain(long borrowed) {
        RenderSystem.assertOnRenderThread();
        if (borrowed == 0) return 0;
        try { return (long)Native.RETAIN.invokeExact(borrowed); }
        catch (Throwable error) {
            if (error instanceof VirtualMachineError fatal) throw fatal;
            throw new IllegalStateException("Cannot pin far program", error);
        }
    }
    public static void release(long owned) { if (owned != 0) drop(owned); }
    // Java pipeline identity prevents a released/reallocated native address hitting an old cache entry.
    private record Key(MetalRenderPipeline owner, long pipeline, int code) { }
    private static final class Entry { volatile long program; }
    private static final class Cache {
        final MetalDevice device;
        final HashMap<Key, Entry> entries = new HashMap<>(); // render-thread-only map
        volatile boolean closed;
        Cache(MetalDevice device) { this.device = device; }
    }
    private static final class Native {
        static MethodHandle bind(String name, FunctionDescriptor descriptor) {
            return Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(MtlNative.Loader.address(name)), descriptor);
        }
        static final MethodHandle PIN = bind("mcmtl_ext_far_program_pin", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG));
        static final MethodHandle COMPILE = bind("mcmtl_ext_far_program_compile", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT));
        static final MethodHandle DROP = bind("mcmtl_ext_far_program_drop", FunctionDescriptor.ofVoid(JAVA_LONG));
        static final MethodHandle RETAIN = bind("mcmtl_ext_far_program_retain", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
    }
}
