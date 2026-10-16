package bench;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/**
 * JFR event for a measured frame interval of at least {@code bench.frameTrace.jfrSlowMs} (default 0 = off; e.g. 4), so a
 * JFR recording started at launch lines up with the frame trace (TAIL-ANALYSIS V2: attributing the 6-13 ms travel frames).
 * Committed at the end of the slow interval (the next onFrame); the fields are that trace row's. Only with
 * {@code -Dbench.frameTrace=true}; one allocation per slow frame only while a JFR recording has the event enabled, none
 * otherwise (r5-int review fix).
 */
@Name("bench.SlowFrame")
@Label("Slow frame")
@Category("Bench")
@Description("A measured bench frame interval over the threshold, with its frame-trace attribution columns")
@StackTrace(false)
public final class SlowFrameEvent extends jdk.jfr.Event {
    @Label("Trace row")
    public int row;
    @Label("Frame interval")
    @Timespan(Timespan.NANOSECONDS)
    public long interval;
    @Label("Client ticks")
    public int ticks;
    @Label("Tick bundle")
    @Timespan(Timespan.NANOSECONDS)
    public long tickBundle;
    @Label("Before submit")
    @Timespan(Timespan.NANOSECONDS)
    public long preSubmit;
    @Label("Native submit")
    @Timespan(Timespan.NANOSECONDS)
    public long nativeSubmit;
    @Label("Timeline wait")
    @Timespan(Timespan.NANOSECONDS)
    public long timelineWait;
    @Label("GC events")
    public int gcEvents;
    @Label("Timeline value")
    public long seq;
}
