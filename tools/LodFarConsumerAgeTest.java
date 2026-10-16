package net.minecraft.client.renderer.chunk;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.mojang.renderpearl.backend.metal.MetalLodMaterialInputs;
import net.minecraft.client.renderer.chunk.LodFarCamera.Camera;
import net.minecraft.client.renderer.chunk.LodFarCamera.Position;
import net.minecraft.client.renderer.chunk.LodFarCamera.Viewport;

/** Production predicates and Scene key only: no renderer, native loading or GPU. */
public final class LodFarConsumerAgeTest {
    private static int checks;
    private static Method fresh, canShade;
    private static Constructor<LodFarConsumerProbe.Scene> sceneConstructor;
    private static final long NOW=1_000_000_000L, FRAME=42;
    private static final Object[] IDENTITIES={new Object(),new Object(),new Object(),new Object()};
    private static final float[] IDENTITY={1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    private static final float[] PROJECTION={1.5f,0,0,0, 0,1.5f,0,0, 0,0,0,-1, 0,0,0.5f,0};
    private static boolean requested;

    private static void check(boolean ok,String why) {
        ++checks; if(!ok) throw new AssertionError(why);
    }
    private static Camera camera(float[] projection,float[] view,Position position,int width,int height) {
        return new Camera(projection,view,position,new Position(0,0,0),new Viewport(width,height));
    }
    private static Camera central() { return camera(PROJECTION,IDENTITY,new Position(0,0,0),800,800); }
    private static Camera guard(int size) {
        float[] p=PROJECTION.clone(); p[0]=(float)(p[0]*(800.0/size)); p[5]=(float)(p[5]*(800.0/size));
        return camera(p,IDENTITY,new Position(0,0,0),size,size);
    }
    private static LodFarGeometry.Source geometry(int changed) {
        long[] key={1,2,3,4,5,6}; int[] origin={0,0,0};
        if(changed>=0 && changed<6) ++key[changed];
        if(changed>=6) ++origin[changed-6];
        return new LodFarGeometry.Source(key[0],key[1],key[2],key[3],key[4],key[5],FRAME,1,
            origin[0],origin[1],origin[2],0,0,0,0,0,0);
    }
    private static LodFarConsumerProbe.Scene scene(Camera central,Camera guarded,int category,int geometryChange) throws Exception {
        byte[][] u={new byte[64],new byte[48],new byte[48],new byte[80]};
        long[] values={10,20,30,40,800,800,7,0x303,0,100};
        Object[] identities=IDENTITIES.clone();
        switch(category) {
            case 1 -> ++values[0]; case 2 -> ++values[1]; case 4 -> ++values[2];
            case 8 -> ++values[4]; case 16 -> ++values[9]; case 32 -> ++u[2][0];
            case 64 -> ++u[1][44]; case 128 -> ++u[3][64]; case 256 -> identities[0]=new Object();
            default -> { }
        }
        return sceneConstructor.newInstance(geometry(geometryChange),central,guarded,u,identities,values,null);
    }
    private static boolean shade(LodFarConsumerProbe.Scene source,LodFarConsumerProbe.Scene current,int mask) throws Exception {
        return (boolean)canShade.invoke(null,source,current,mask);
    }
    private static boolean age(long time,long sourceFrame,long now,long frame,boolean exact) throws Exception {
        ByteBuffer image=ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        // Poison other ABI fields: source timestamp/frame must come from 24/32.
        image.putLong(0,Long.MIN_VALUE).putLong(8,Long.MAX_VALUE).putLong(16,0);
        image.putLong(24,time).putLong(32,sourceFrame).putLong(40,Long.MAX_VALUE);
        image.position(48);
        boolean result=(boolean)fresh.invoke(null,image,now,frame,exact);
        check(image.position()==48 && image.getLong(24)==time && image.getLong(32)==sourceFrame,"descriptor unchanged");
        return result;
    }
    private static void ages() throws Exception {
        for(boolean exact:new boolean[]{false,true}) {
            for(long elapsed:new long[]{0,1,99_999_999L,100_000_000L,100_000_001L,NOW-1})
                check(age(NOW-elapsed,FRAME,NOW,FRAME,exact)==(exact || elapsed<=100_000_000L),"age boundary "+elapsed+" exact="+exact);
            for(long time:new long[]{Long.MIN_VALUE,-1,0,NOW+1,Long.MAX_VALUE})
                check(!age(time,FRAME,NOW,FRAME,exact),"invalid timestamp "+time+" exact="+exact);
            for(long frame:new long[]{Long.MIN_VALUE,-1,0,FRAME+1,Long.MAX_VALUE})
                check(!age(NOW,frame,NOW,FRAME,exact),"invalid source frame "+frame+" exact="+exact);
            for(long frame:new long[]{1,FRAME-1,FRAME})
                check(age(NOW,frame,NOW,FRAME,exact),"valid frame order "+frame);
            for(long now:new long[]{Long.MIN_VALUE,-1,0})
                check(!age(1,1,now,1,exact),"invalid/future clock order "+now);
            check(!age(NOW,1,NOW,0,exact),"source after current frame zero");
            check(age(1,1,Long.MAX_VALUE,Long.MAX_VALUE,exact)==exact,"largest positive elapsed age");
            check(age(Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,exact),"max equal timestamp/frame");
            check(age(1,1,1,1,exact),"smallest positive timestamp/frame");
        }
    }
    private static void eligibility() throws Exception {
        Camera central=central(),guard=guard(1000);
        var source=scene(central,guard,0,-1); var current=scene(central,null,0,-1);
        check(source.differences(current)==0,"baseline scene identity");
        check(shade(source,current,0)==requested,"both feature flags required");
        // All combinations of allowed atlas/light/fog differences; no other bit is exempt.
        for(int subset=0;subset<8;++subset) {
            int mask=(subset&3)|((subset&4)<<3);
            check(shade(source,current,mask)==requested,"allowed mask "+mask);
            check(age(1,FRAME,NOW,FRAME,shade(source,current,mask))==requested,"old exact source mask "+mask);
        }
        for(int bit=0;bit<32;++bit) {
            int mask=1<<bit;
            boolean allowed=bit==0 || bit==1 || bit==5;
            check(shade(source,current,mask)==(requested && allowed),"single scene bit "+bit);
            if(!allowed) check(!shade(source,current,mask|35),"forbidden bit mixed with allowed "+bit);
        }
        for(int category:new int[]{1,2,4,8,16,32,64,128,256}) {
            var changed=scene(central,null,category,-1); int actual=source.differences(changed);
            check(actual==category,"real scene difference "+category);
            boolean allowed=category==1 || category==2 || category==32;
            check(age(1,FRAME,NOW,FRAME,shade(source,changed,actual))==(requested && allowed),"real scene old-source admission "+category);
        }
        for(int field=0;field<9;++field) {
            var changed=scene(central,null,0,field);
            check(source.differences(changed)==512,"geometry/origin field "+field);
            check(!age(1,FRAME,NOW,FRAME,shade(source,changed,source.differences(changed))),"changed geometry cannot exempt age "+field);
        }
        float[] rotated=IDENTITY.clone(); rotated[1]=0.001f;
        float[] changedP=PROJECTION.clone(); changedP[0]=Float.intBitsToFloat(0x3fc00001);
        Camera roundedCollision=camera(changedP,IDENTITY,central.position(),800,800);
        check(LodFarGuard.canonicalCrop(guard,roundedCollision),"old guard projection has real float collision");
        Camera[] changedCameras={
            camera(PROJECTION,IDENTITY,new Position(Math.nextUp(0.0),0,0),800,800),
            camera(PROJECTION,rotated,central.position(),800,800),roundedCollision,
            camera(PROJECTION,IDENTITY,central.position(),799,800)};
        for(Camera changed:changedCameras) {
            var moved=scene(changed,null,0,-1);
            check(!shade(source,moved,0),"nonexact pose/projection/viewport");
            check(!age(1,FRAME,NOW,FRAME,shade(source,moved,0)),"nonexact old image remains bounded");
        }
        var oddPhase=scene(central,guard(1002),0,-1);
        check(LodFarGuard.canonicalCrop(oddPhase.guardedCamera,central),"odd helper phase is a valid copy crop");
        check(!shade(oddPhase,current,0),"copy crop alone cannot exempt age");
        // Even exact proof cannot override malformed order or the older-image copy policy.
        boolean eligible=shade(source,current,0);
        check(!age(0,FRAME,NOW,FRAME,eligible),"exact proof cannot admit zero timestamp");
        check(!age(NOW+1,FRAME,NOW,FRAME,eligible),"exact proof cannot admit future timestamp");
        check(!age(NOW,FRAME+1,NOW,FRAME,eligible),"exact proof cannot admit future frame");
        check(!age(1,FRAME,NOW,FRAME,false),"older copied source never inherits exact exemption");
    }
    public static void main(String[] args) throws Exception {
        requested=Boolean.parseBoolean(args[0]);
        fresh=LodFarConsumerProbe.class.getDeclaredMethod("fresh",ByteBuffer.class,long.class,long.class,boolean.class);
        canShade=LodFarConsumerProbe.class.getDeclaredMethod("canShade",LodFarConsumerProbe.Scene.class,LodFarConsumerProbe.Scene.class,int.class);
        sceneConstructor=LodFarConsumerProbe.Scene.class.getDeclaredConstructor(LodFarGeometry.Source.class,Camera.class,Camera.class,
            byte[][].class,Object[].class,long[].class,MetalLodMaterialInputs.AtlasToken.class);
        fresh.setAccessible(true); canShade.setAccessible(true); sceneConstructor.setAccessible(true);
        ages(); eligibility();
        System.out.println("LodFarConsumerAgeTest: "+checks+" checks passed; exact shading requested="+requested+" (CPU only)");
    }
}
