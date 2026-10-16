package bench;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Process and GPU memory of this JVM for the soak mode (Soak), read through the FFM API (no subprocess, no game classes):
 * <ul>
 *   <li>{@code proc_pid_rusage(getpid(), RUSAGE_INFO_V2)}: {@code ri_phys_footprint} (offset 72; what Activity Monitor
 *       calls "Memory": dirty + compressed + IOKit/GPU memory attributed to the process) and {@code ri_resident_size}
 *       (offset 64, RSS). Offsets checked against libproc.h with clang (struct size 160);</li>
 *   <li>{@code [MTLCreateSystemDefaultDevice() currentAllocatedSize]}: bytes of all Metal resources this process has
 *       allocated on the GPU (buffers, textures, heaps, drawables). The device object is the process-wide default device
 *       the Metal backend (and MoltenVK / Apple's GL) also uses, so the number covers every backend.</li>
 * </ul>
 * Each reader returns -1 when its binding failed. Not thread-safe (one shared rusage buffer): call from one thread.
 */
final class NativeMem {
    private static boolean initialized;
    private static MethodHandle rusage;
    private static MemorySegment rusageBuf;
    private static int pid;
    private static MethodHandle msgSendLong;
    private static MemorySegment device = MemorySegment.NULL;
    private static MemorySegment selAllocated = MemorySegment.NULL;
    static String status = "not initialized";

    private NativeMem() {
    }

    static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        StringBuilder st = new StringBuilder();
        Linker linker = Linker.nativeLinker();
        try {
            rusage = linker.downcallHandle(linker.defaultLookup().find("proc_pid_rusage").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            rusageBuf = Arena.global().allocate(512, 8);
            pid = (int) ProcessHandle.current().pid();
            st.append("rusage ok");
        } catch (Throwable t) {
            rusage = null;
            st.append("rusage unavailable (").append(t).append(')');
        }
        try {
            SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
            SymbolLookup metal = SymbolLookup.libraryLookup("/System/Library/Frameworks/Metal.framework/Metal", Arena.global());
            MethodHandle create = linker.downcallHandle(metal.find("MTLCreateSystemDefaultDevice").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS));
            MethodHandle selRegister = linker.downcallHandle(objc.find("sel_registerName").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            msgSendLong = linker.downcallHandle(objc.find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            device = (MemorySegment) create.invokeExact();
            selAllocated = (MemorySegment) selRegister.invokeExact(Arena.global().allocateFrom("currentAllocatedSize"));
            if (device.address() == 0L || selAllocated.address() == 0L) {
                throw new IllegalStateException("no default MTLDevice");
            }
            st.append(", MTLDevice.currentAllocatedSize ok");
        } catch (Throwable t) {
            msgSendLong = null;
            st.append(", MTLDevice.currentAllocatedSize unavailable (").append(t).append(')');
        }
        status = st.toString();
    }

    private static long rusageField(final long offset) {
        if (rusage == null) {
            return -1L;
        }
        try {
            int rc = (int) rusage.invokeExact(pid, 2, rusageBuf);
            return rc == 0 ? rusageBuf.get(ValueLayout.JAVA_LONG, offset) : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** ri_phys_footprint in bytes, or -1. */
    static long physFootprint() {
        return rusageField(72L);
    }

    /** ri_resident_size in bytes, or -1. */
    static long resident() {
        return rusageField(64L);
    }

    /** MTLDevice.currentAllocatedSize in bytes, or -1. */
    static long metalAllocated() {
        if (msgSendLong == null) {
            return -1L;
        }
        try {
            return (long) msgSendLong.invokeExact(device, selAllocated);
        } catch (Throwable t) {
            return -1L;
        }
    }
}
