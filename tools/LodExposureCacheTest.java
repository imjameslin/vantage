package net.minecraft.client.renderer.chunk;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.util.Random;

/** Differential state/lifetime checks for exact memoization, no GPU or FPS claim. */
public final class LodExposureCacheTest {
    private static int checks;
    private static final Field EXPOSED;
    static {
        try { EXPOSED=LodNearCoverage.class.getDeclaredField("exposed"); EXPOSED.setAccessible(true); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    private static void check(boolean pass, String message) {
        checks++; if (!pass) throw new AssertionError(message);
    }
    private static LongOpenHashSet exposed(LodNearCoverage c) throws Exception {
        return (LongOpenHashSet)EXPOSED.get(c);
    }
    public static void main(String[] args) throws Exception {
        LodNearCoverage c=new LodNearCoverage(8);
        c.bind(10,1); c.bind(10,2); c.bind(20,3);
        c.beginFrame(); c.observe(10,true); c.update(); c.captureExposure(false,true);
        check(exposed(c).equals(new LongOpenHashSet(new long[]{20})),"masked coordinate is not exposed");
        c.captureExposure(false,true);
        check(c.exposureHits()==1 && c.exposureBuilds()==1,"unchanged inputs reuse exposure");
        c.beginFrame(); c.update(); c.captureExposure(false,true);
        check(exposed(c).contains(10) && c.exposureBuilds()==2,"uncover invalidates");
        c.beginFrame(); c.observe(10,true); c.update(); c.captureExposure(false,true);
        check(!exposed(c).contains(10),"cover invalidates");
        c.captureExposure(true,true);check(exposed(c).contains(10),"unmasked switch invalidates");
        c.captureExposure(false,true);check(!exposed(c).contains(10),"masked switch invalidates");
        c.bind(30,4);c.captureExposure(false,true);check(exposed(c).contains(30),"added binding invalidates");
        c.clearBindings();c.bind(40,3);c.captureExposure(false,true);
        check(exposed(c).equals(new LongOpenHashSet(new long[]{40})),"recycled slot cannot keep old coordinate");
        long builds=c.exposureBuilds();c.clearExposure();
        check(exposed(c).isEmpty(),"failed cull clears exposure");
        c.captureExposure(false,true);check(c.exposureBuilds()==builds+1 && exposed(c).contains(40),"next successful cull rebuilds after failure");
        c.resetVisibilityHistory();c.captureExposure(false,true);
        check(c.exposureBuilds()==builds+2,"history reset invalidates cached result");
        c.captureExposure(false,false);check(c.exposureBuilds()==builds+3,"control path always rebuilds");

        LodNearCoverage reference=new LodNearCoverage(64), candidate=new LodNearCoverage(64);
        Random random=new Random(0x51a7L);Object[] owners={new Object(),new Object(),new Object()};
        for (int frame=0;frame<1000;frame++) {
            if (frame%7==0) {
                reference.clearBindings();candidate.clearBindings();
                for (int slot=0;slot<64;slot++) {
                    long coordinate=random.nextInt(24)-12;
                    reference.bind(coordinate,slot);candidate.bind(coordinate,slot);
                }
            }
            reference.beginFrame();candidate.beginFrame();
            for (long coordinate=-12;coordinate<12;coordinate++) {
                boolean ready=random.nextBoolean();
                Object owner=owners[random.nextInt(owners.length)];long generation=frame/13;
                float visibility=random.nextBoolean()?0.25f:1;
                check(reference.fullVisibility(coordinate,owner,generation,ready,visibility)
                    == candidate.fullVisibility(coordinate,owner,generation,ready,visibility),"ready/fade latch parity");
                reference.observe(coordinate,ready);candidate.observe(coordinate,ready);
                if (random.nextInt(5)==0) {reference.observeEmpty(coordinate);candidate.observeEmpty(coordinate);}
            }
            check(reference.update()==candidate.update(),"mask revision parity");
            if (frame%19==0) {reference.resetVisibilityHistory();candidate.resetVisibilityHistory();}
            if (frame%11==0) {reference.clearExposure();candidate.clearExposure();}
            boolean unmasked=frame%23==0;
            // Includes turning the optimization off and on while all ownership state stays live.
            reference.captureExposure(unmasked,false);candidate.captureExposure(unmasked,frame%3!=0);
            check(exposed(reference).equals(exposed(candidate)),"fresh vs memoized exposure parity");
            reference.captureExposure(unmasked,false);candidate.captureExposure(unmasked,true);
            check(exposed(reference).equals(exposed(candidate)),"cache-hit exposure parity");
        }
        check(candidate.exposureHits()>=1000,"steady captures actually hit cache");
        System.out.println("LodExposureCacheTest: "+checks+" checks passed; CPU only");
    }
}
