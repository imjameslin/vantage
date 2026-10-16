package bench;

import java.util.ArrayList;
import java.util.List;

/** CPU clock policy; actual timer/camera/light matching is separately required in every game capture. */
public final class ReadyReferenceClockTest {
    static int checks;
    static void check(boolean value) { checks++; if(!value)throw new AssertionError("clock check "+checks); }
    static ReuseSequence.Clock tick() {
        float delta=Lockstep.delta();Lockstep.step();
        return new ReuseSequence.Clock(Lockstep.frame(),Lockstep.steps(),Lockstep.nanos(),delta,Lockstep.partial(),1,0.5F);
    }
    public static void main(String[] args) throws Exception {
        var reference=new ArrayList<ReuseSequence.Clock>();
        Lockstep.start(8);
        for(int i=0;i<32;i++)tick();
        for(int i=0;i<120;i++) {
            Lockstep.hold(true);
            for(int wait=0;wait<1+i%17;wait++) { var held=tick();check(held.deltaTicks()==0 && held.frame()==31+i); }
            Lockstep.hold(false);var rendered=tick();reference.add(rendered);
            check(rendered.frame()==32+i && rendered.deltaTicks()==0.125F
                && rendered.steps()==rendered.frame()+1+Lockstep.heldSteps());
            Lockstep.hold(true);var capture=tick();
            check(capture.frame()==rendered.frame() && capture.steps()==rendered.steps()+1 && capture.deltaTicks()==0);
        }
        Lockstep.start(8);check(Lockstep.heldSteps()==0);
        for(int i=0;i<32;i++)tick();
        for(int i=0;i<120;i++) {
            var candidate=tick();
            check(Lockstep.heldSteps()==0 && candidate.steps()==candidate.frame()+1);
            check(ReuseSequence.sameRenderClock(reference.get(i),candidate,true));
            check(!ReuseSequence.sameRenderClock(reference.get(i),candidate,false));
        }
        var a=reference.get(0);
        for(var bad:List.of(new ReuseSequence.Clock(a.frame()+1,a.steps(),a.nanos(),a.deltaTicks(),a.playerPartial(),a.worldPartial(),a.cameraPartial()),
            new ReuseSequence.Clock(a.frame(),a.steps(),a.nanos()+1,a.deltaTicks(),a.playerPartial(),a.worldPartial(),a.cameraPartial()),
            new ReuseSequence.Clock(a.frame(),a.steps(),a.nanos(),0,a.playerPartial(),a.worldPartial(),a.cameraPartial()),
            new ReuseSequence.Clock(a.frame(),a.steps(),a.nanos(),a.deltaTicks(),0.3F,a.worldPartial(),a.cameraPartial()),
            new ReuseSequence.Clock(a.frame(),a.steps(),a.nanos(),a.deltaTicks(),a.playerPartial(),0.5F,a.cameraPartial()),
            new ReuseSequence.Clock(a.frame(),a.steps(),a.nanos(),a.deltaTicks(),a.playerPartial(),a.worldPartial(),0.2F)))
            check(!ReuseSequence.sameRenderClock(a,bad,true));
        check(new LodReferenceReadiness.State(2,2,0,0,0,true,0,List.of(),true,0,0).ready());
        for(var bad:List.of(new LodReferenceReadiness.State(2,2,1,0,0,true,0,List.of(),true,0,0),new LodReferenceReadiness.State(2,2,0,1,0,true,0,List.of(),true,0,0),
            new LodReferenceReadiness.State(2,2,0,0,1,true,0,List.of(),true,0,0),new LodReferenceReadiness.State(2,2,0,0,0,false,0,List.of(),true,0,0)))check(!bad.ready());
        check(!new LodReferenceReadiness.State(2,2,0,0,0,true,1,List.of(),false,0,0).ready());
        check(!new LodReferenceReadiness.State(2,2,0,0,0,true,1,List.of(),true,1,0).ready());
        check(!new LodReferenceReadiness.State(2,2,0,0,0,true,1,List.of(),true,0,1).ready());
        var lod=com.google.gson.JsonParser.parseString("{\"idle\":true,\"refusedOverBudget\":0,\"pendingMeshes\":0,\"queue\":0,\"inFlight\":0,\"selected\":100,\"gpu\":{\"fading\":0}}").getAsJsonObject();
        check(ReuseSequence.referenceLodReady(lod));
        for(String key:List.of("refusedOverBudget","pendingMeshes","queue","inFlight")) {
            var bad=lod.deepCopy();bad.addProperty(key,1);check(!ReuseSequence.referenceLodReady(bad));
        }
        var bad=lod.deepCopy();bad.addProperty("idle",false);check(!ReuseSequence.referenceLodReady(bad));
        bad=lod.deepCopy();bad.addProperty("selected",0);check(!ReuseSequence.referenceLodReady(bad));
        bad=lod.deepCopy();bad.getAsJsonObject("gpu").addProperty("fading",1);check(!ReuseSequence.referenceLodReady(bad));
        // Compare against the real vanilla extractor tick, not a second copy of our formula.
        var vanilla=new net.minecraft.client.renderer.LightmapRenderStateExtractor(null,null);
        var random=vanilla.getClass().getDeclaredField("randomSource");random.setAccessible(true);
        ((net.minecraft.util.RandomSource)random.get(vanilla)).setSeed(0x5eed64L);
        var value=vanilla.getClass().getDeclaredField("blockLightFlicker");value.setAccessible(true);
        var inputs=new ReferenceImageInputs();
        float[] expected=new float[152];
        for(int frame=0;frame<152;frame++){
            if(frame%8==0)vanilla.tick();
            expected[frame]=value.getFloat(vanilla);
            check(Float.floatToIntBits(inputs.flicker(frame,8))==Float.floatToIntBits(expected[frame]));
        }
        for(int repeat=0;repeat<3;repeat++)for(int frame=0;frame<152;frame++)
            check(Float.floatToIntBits(inputs.flicker(frame,8))==Float.floatToIntBits(expected[frame]));
        Lockstep.stop();System.out.println("ReadyReferenceClockTest: "+checks+" checks passed; CPU policy only");
    }
}
