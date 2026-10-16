package bench;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** No game/GPU/native library. Exercise bounded admission and owned-image completion on every path. */
public class CaptureWriterTest {
    static int checks;
    static void check(boolean ok, String reason) { checks++; if(!ok)throw new AssertionError(reason); }
    static void await(CountDownLatch latch) throws Exception {
        if(!latch.await(10, TimeUnit.SECONDS))throw new AssertionError("writer deadline");
    }
    public static void main(String[] args) throws Exception {
        var writer = new CaptureWriter(2, 3);
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(5);
        var closed = new AtomicInteger(); var successes = new AtomicInteger(); var failures = new AtomicInteger();
        for(int i=0;i<2;i++) writer.submit(closed::incrementAndGet, ()->{started.countDown();await(release);}, error->{
            if(error==null)successes.incrementAndGet();else failures.incrementAndGet();completed.countDown();
        });
        await(started);
        for(int i=0;i<3;i++) {
            int id=i;
            writer.submit(closed::incrementAndGet, ()->{if(id==1)throw new IOException("encode failure");}, error->{
                if(error==null)successes.incrementAndGet();else failures.incrementAndGet();completed.countDown();
            });
        }
        var rejected = new AtomicInteger(); var rejectionClosed = new AtomicInteger();
        writer.submit(rejectionClosed::incrementAndGet, ()->{throw new AssertionError("queue overflow ran");}, error->{if(error!=null)rejected.incrementAndGet();});
        check(rejected.get()==1 && rejectionClosed.get()==1,"full queue rejects and closes synchronously");
        check(writer.peakActive()==2 && completed.getCount()==5,"exactly two active writers; queued work has not run");
        writer.close(); // Must return with work still active; never wait/join from the render hook.
        check(completed.getCount()==5,"close is nonblocking");
        writer.submit(rejectionClosed::incrementAndGet, ()->{throw new AssertionError("closed queue ran");}, error->{if(error!=null)rejected.incrementAndGet();});
        check(rejected.get()==2 && rejectionClosed.get()==2,"closed queue rejects, closes and reports once");
        release.countDown();await(completed);
        check(closed.get()==5,"all successful/failed encodes close images exactly once");
        check(successes.get()==4 && failures.get()==1,"failed encoder reports error while remaining work drains");
        check(writer.peakActive()==2,"writer cap held through drain");
        var closeFailure = new CaptureWriter(1,1);var done=new CountDownLatch(1);var gotFailure=new AtomicInteger();
        closeFailure.submit(()->{throw new IOException("close failure");},()->{},error->{if(error!=null)gotFailure.incrementAndGet();done.countDown();});
        closeFailure.close();await(done);check(gotFailure.get()==1,"image close error is reported once");
        System.out.println("CaptureWriterTest: "+checks+" checks passed");
    }
}
