package bench;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.util.RandomSource;
import org.joml.Vector3fc;

/** Benchmark-only deterministic light prefix and read-only verification of frozen sprite phases.
 * No production renderer hooks. The local benchmark game exits after capture.
 */
final class ReferenceImageInputs {
    private static final long LIGHT_SEED=0x5eed64L;
    private Map<String,List<Sprite>> initialSprites;
    private final List<Float> flickerByTick=new ArrayList<>();
    private final RandomSource lightRandom=RandomSource.create(LIGHT_SEED);
    private record Sprite(Object identity, int frame, int subFrame, boolean dirty) {}

    void beginArm(Minecraft mc) throws ReflectiveOperationException {
        checkSprites(mc);
        Object extractor=field(mc.gameRenderer,"lightmapRenderStateExtractor").get(mc.gameRenderer);
        ((RandomSource)field(extractor,"randomSource").get(extractor)).setSeed(LIGHT_SEED);
        field(extractor,"blockLightFlicker").setFloat(extractor,0.0F);
        field(extractor,"needsUpdate").setBoolean(extractor,true);
    }

    /** Same vanilla flicker math, indexed by logical client tick rather than wall-time callbacks.
     * Both arms refresh lightmap inputs at the same camera on logical client ticks.
     * This is capture-fixture input control, never a performance run or production change.
     */
    void prepareRender(Minecraft mc,long frame,int framesPerTick) throws ReflectiveOperationException {
        float flicker=flicker(frame,framesPerTick);
        Object extractor=field(mc.gameRenderer,"lightmapRenderStateExtractor").get(mc.gameRenderer);
        field(extractor,"blockLightFlicker").setFloat(extractor,flicker);
        field(extractor,"needsUpdate").setBoolean(extractor,frame%framesPerTick==0);
    }

    float flicker(long frame,int framesPerTick) {
        int tick=Math.toIntExact(frame/framesPerTick);
        if(tick<0 || tick>1024)throw new IllegalStateException("reference light-clock range");
        while(flickerByTick.size()<=tick) {
            float flicker=flickerByTick.isEmpty()?0:flickerByTick.getLast();
            flicker+=(lightRandom.nextFloat()-lightRandom.nextFloat())*lightRandom.nextFloat()*lightRandom.nextFloat()*0.1F;
            flicker*=0.9F;
            flickerByTick.add(flicker);
        }
        return flickerByTick.get(tick);
    }

    private void checkSprites(Minecraft mc) throws ReflectiveOperationException {
        var atlases=new TreeMap<String,TextureAtlas>();
        mc.getAtlasManager().forEach((id,atlas)->atlases.put(id.toString(),atlas));
        var snapshot=new TreeMap<String,List<Sprite>>();
        for(var entry:atlases.entrySet()) {
            var states=(List<?>)field(entry.getValue(),"animatedTexturesStates").get(entry.getValue());
            var row=new ArrayList<Sprite>();
            for(Object s:states) row.add(new Sprite(s,field(s,"frame").getInt(s),field(s,"subFrame").getInt(s),field(s,"isDirty").getBoolean(s)));
            snapshot.put(entry.getKey(),row);
        }
        if(initialSprites==null) initialSprites=snapshot;
        else if(!initialSprites.equals(snapshot)) throw new IllegalStateException("sprite identities/phases changed during frozen reference capture");
    }

    Map<String,Object> rendered(Minecraft mc) throws ReflectiveOperationException {
        checkSprites(mc);
        var game=(GameRenderState)field(mc.gameRenderer,"gameRenderState").get(mc.gameRenderer);
        LightmapRenderState s=game.lightmapRenderState;
        var out=new TreeMap<String,Object>();
        out.put("lightSeed",LIGHT_SEED);
        out.put("frozenSpriteCount",initialSprites.values().stream().mapToInt(List::size).sum());
        out.put("blockFactor",s.blockFactor); out.put("blockTint",vec(s.blockLightTint));
        out.put("skyFactor",s.skyFactor); out.put("skyColor",vec(s.skyLightColor));
        out.put("ambient",vec(s.ambientColor)); out.put("brightness",s.brightness);
        out.put("darkness",s.darknessEffectScale); out.put("nightVision",s.nightVisionEffectIntensity);
        out.put("nightColor",vec(s.nightVisionColor)); out.put("bossDarkening",s.bossOverlayWorldDarkening);
        // A packet may change ClientLevel before this next-frame capture hook. Read the
        // previous render's extracted values, not the now-current mutable world clock.
        var level=game.levelRenderState;
        out.put("gameTime",level.gameTime);
        out.put("worldPartial",level.worldPartialTicks);
        var sky=level.skyRenderState;
        out.put("sunAngle",sky.sunAngle); out.put("moonAngle",sky.moonAngle);
        out.put("starAngle",sky.starAngle); out.put("starBrightness",sky.starBrightness);
        out.put("rainBrightness",sky.rainBrightness); out.put("skybox",sky.skybox.toString());
        out.put("moonPhase",sky.moonPhase.toString());
        out.put("skyBackground",sky.skyColor==null?null:vec(sky.skyColor));
        out.put("weatherIntensity",level.weatherRenderState.intensity);
        out.put("rainColumns",level.weatherRenderState.rainColumns.size());
        out.put("snowColumns",level.weatherRenderState.snowColumns.size());
        var fog=level.cameraRenderState.fogData;
        out.put("fog",List.of(fog.environmentalStart,fog.environmentalEnd,fog.renderDistanceStart,fog.renderDistanceEnd,fog.skyEnd,fog.cloudEnd,
            fog.color.x,fog.color.y,fog.color.z,fog.color.w));
        return out;
    }

    private static List<Float> vec(Vector3fc v) { return List.of(v.x(),v.y(),v.z()); }
    private static Field field(Object owner,String name) throws ReflectiveOperationException {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f;
    }
}
