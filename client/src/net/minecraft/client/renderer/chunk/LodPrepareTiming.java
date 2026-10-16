package net.minecraft.client.renderer.chunk;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Opt-in, per-job diagnostics. Never initialized by the default path; no clocks in section loops.
 * CPU time excludes descheduling but includes time stalled inside running native instructions.
 * We only read an already-enabled supported counter, never change JVM or system configuration.
 */
final class LodPrepareTiming {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final AtomicLong IDS = new AtomicLong();
    private final long id = IDS.incrementAndGet();
    private final long startUptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
    private final long[] wall = new long[4], cpu = {-1, -1, -1, -1};
    private final Sample event = new Sample();
    private final long started;
    private long lastWall, lastCpu;
    private int phase;

    LodPrepareTiming() {
        this.event.begin();
        this.started = this.lastWall = System.nanoTime();
        this.lastCpu = cpuNow();
    }

    private static long cpuNow() {
        return THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled()
            ? THREADS.getCurrentThreadCpuTime() : -1;
    }

    void next() {
        long nowWall = System.nanoTime(), nowCpu = cpuNow();
        this.wall[this.phase] = nowWall - this.lastWall;
        this.cpu[this.phase] = this.lastCpu >= 0 && nowCpu >= this.lastCpu ? nowCpu - this.lastCpu : -1;
        this.lastWall = nowWall; this.lastCpu = nowCpu;
        this.phase++;
    }

    void finish(boolean returned, LodPreparedItems.Result result) {
        int lastPhase = this.phase;
        this.next();
        this.event.end();
        Sample e = this.event;
        e.job = this.id; e.startUptimeMs = this.startUptimeMs;
        e.outcome = !returned ? "error" : result == null ? "capacity" : "ok";
        e.lastPhase = lastPhase;
        e.wallNs = this.lastWall - this.started;
        e.cpuNs = 0;
        for (int i = 0; i <= lastPhase; i++) {
            if (this.cpu[i] < 0) { e.cpuNs = -1; break; }
            e.cpuNs += this.cpu[i];
        }
        e.admitWallNs = wall[0]; e.admitCpuNs = cpu[0];
        e.allocateWallNs = wall[1]; e.allocateCpuNs = cpu[1];
        e.fillWallNs = wall[2]; e.fillCpuNs = cpu[2];
        e.finishWallNs = wall[3]; e.finishCpuNs = cpu[3];
        e.sections = result == null ? 0 : result.sectionCount;
        e.clusters = result == null ? 0 : result.clusterCount;
        e.commit();
        // Log after the timed region. JFR supplies the matching timestamp/thread and other JVM events.
        System.out.println("[lod-prepare-profile] job=" + e.job + " thread=" + Thread.currentThread().getName()
            + " startUptimeMs=" + e.startUptimeMs + " outcome=" + e.outcome + " lastPhase=" + e.lastPhase
            + " wallNs=" + e.wallNs + " cpuNs=" + e.cpuNs
            + " admit=" + e.admitWallNs + "/" + e.admitCpuNs
            + " allocate=" + e.allocateWallNs + "/" + e.allocateCpuNs
            + " fill=" + e.fillWallNs + "/" + e.fillCpuNs
            + " finish=" + e.finishWallNs + "/" + e.finishCpuNs
            + " sections=" + e.sections + " clusters=" + e.clusters);
    }

    @Name("minecraft.LodPrepare") @Label("LOD CPU draw preparation")
    @Category({"Minecraft", "Farsight"}) @StackTrace(false)
    static final class Sample extends Event {
        long job, startUptimeMs, wallNs, cpuNs;
        long admitWallNs, admitCpuNs, allocateWallNs, allocateCpuNs;
        long fillWallNs, fillCpuNs, finishWallNs, finishCpuNs;
        String outcome;
        int lastPhase, sections, clusters;
    }
}
