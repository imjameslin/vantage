package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
import com.mojang.renderpearl.backend.metal.*;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.*;
import java.util.*;
import org.lwjgl.system.MemoryUtil;

/** Actual controller/buffer-copy/lifetime checks using CPU-backed Metal test buffers. No GPU or game. */
public final class LodAsyncItemsTest {
    static int checks, allocations, failAt = -1;
    static void check(boolean ok, String reason) { checks++; if (!ok) throw new AssertionError(reason); }
    static Field field(Class<?> c, String name) throws Exception { var f=c.getDeclaredField(name); f.setAccessible(true); return f; }
    static Object get(Object o,String name)throws Exception { return field(o.getClass(),name).get(o); }
    static void set(Object o,String name,Object value)throws Exception { field(o.getClass(),name).set(o,value); }
    static Object blank(Class<?> c)throws Exception {
        Class<?> u=Class.forName("sun.misc.Unsafe");
        return u.getMethod("allocateInstance",Class.class).invoke(field(u,"theUnsafe").get(null),c);
    }
    static Method method(String name,Class<?>... args)throws Exception {var m=LodGpu.class.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    static LodGpu.GpuSection section(int slot,int first) {
        var s=new LodGpu.GpuSection(); s.slot=slot;s.arena[0]=0;s.firstQuad[0]=first;
        s.clusters[0]=new int[]{2 | 3<<16 | 4<<22, 1|2<<10|3<<20, 4|5<<10|6<<20};
        s.origin=new int[]{slot*16,32,48,1};return s;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception {
        LodGpu.asyncExactRevalidate=Boolean.getBoolean("test.exactRevalidate");
        packingIdentity();
        RenderSystem.initRenderThread();
        MemorySegment page=MemorySegment.ofArray(new long[(int)MtlNative.STATUS_PAGE_SIZEOF/8]);
        MetalTimeline timeline=new MetalTimeline(0,page);
        MetalDevice device=MetalDevice.forTesting(0,page,timeline,null,false);
        GpuDevice facade=(GpuDevice)Proxy.newProxyInstance(GpuDevice.class.getClassLoader(),new Class<?>[]{GpuDevice.class},(p,m,a)->{
            if(m.getName().equals("createBuffer") && a.length==3 && a[2] instanceof Long size) {
                if(++allocations==failAt)throw new GpuOutOfMemoryException("injected allocation failure");
                return MetalBuffer.create(device,null,(int)a[1],size);
            }
            throw new AssertionError("Unexpected device use: "+m.getName());
        });
        Field global=field(RenderSystem.class,"DEVICE");global.set(null,facade);
        LodGpu gpu=new LodGpu(null);
        MetalBuffer infos=MetalBuffer.create(device,null,GpuBuffer.USAGE_MAP_WRITE,256);
        set(gpu,"sectionInfos",infos);
        Class<?> arenaType=Class.forName(LodGpu.class.getName()+"$Arena");
        Object arena=blank(arenaType);set(arena,"index",0);set(arena,"buffer",MetalBuffer.create(device,null,GpuBuffer.USAGE_MAP_WRITE,256));
        ((List<Object>)get(gpu,"arenas")).add(arena);
        var leases=(LodGeometryLeases)get(gpu,"geometryLeases");
        var prepare=method("prepareItemsAsync",net.minecraft.client.multiplayer.ClientLevel.class,LodManager.Selection.class,long.class);
        var capture=method("captureItems",net.minecraft.client.multiplayer.ClientLevel.class,LodManager.Selection.class,long.class);
        Class<?> planType=Class.forName(LodGpu.class.getName()+"$ItemPlan");
        var install=method("installItems",planType,LodPreparedItems.Result.class,long.class);
        var sectionItems=method("sectionItems",LodGpu.GpuSection.class);
        var releaseItems=method("releaseSectionItems",LodGpu.GpuSection.class);
        check(Modifier.isVolatile(field(LodGpu.GpuSection.class,"preparedItems").getModifiers()),
            "worker cache reference safely publishes/clears immutable records");
        var n=new LodManager.Node(1,0,0); n.gpu=List.of(section(0,8)); n.resident=true;
        sectionItems.invoke(gpu,n.gpu.getFirst());
        var sel=new LodManager.Selection(new LodManager.Node[]{n},new boolean[]{false},1);
        set(gpu,"frame",10L); LodGpu.asyncItems=true;
        long now=System.nanoTime();
        check((boolean)prepare.invoke(gpu,null,sel,now),"bootstrap installed without a worker wait");
        check((long)get(gpu,"asyncBootstrap")==1 && (long)get(gpu,"asyncAccepted")==0,"bootstrap distinct from actual worker acceptance");
        check((long)get(gpu,"asyncRecordSections")==1 && (long)get(gpu,"asyncRecordClusters")==1
            && (long)get(gpu,"asyncScalarSections")==0,"bootstrap actually bulk-copies the immutable cache");
        check(leases.count()==1 && get(gpu,"pendingItems")==null,"one active lease, no pending worker");
        int ring=(int)get(gpu,"itemRingIndex");
        GpuBuffer[] rings=(GpuBuffer[])get(gpu,"itemRing");
        int[] expected={4*(8+2),3<<20|4<<26,1|2<<10|3<<20,4|5<<10|6<<20};
        long ptr=MetalClusterCull.contents(rings[ring]);
        for(int i=0;i<4;i++)check(MemoryUtil.memGetInt(ptr+4L*i)==expected[i],"exact packed GPU input word "+i);
        Object oldPlan=get(gpu,"activeItems");
        long retired=leases.retire();
        check(!leases.canRecycle(retired,0,100,6),"active draw pins retired geometry beyond old frame grace");
        var oldSections=n.gpu;
        n.gpu=List.of(section(1,20)); set(gpu,"geometryEpoch",2L);
        sectionItems.invoke(gpu,n.gpu.getFirst());
        var second=new LodManager.Selection(new LodManager.Node[]{n},new boolean[]{false},2);
        Object plan=capture.invoke(gpu,null,second,now);
        Object input=get(plan,"input");Method build=input.getClass().getDeclaredMethod("build");build.setAccessible(true);
        releaseItems.invoke(gpu,n.gpu.getFirst()); // Retirement may clear the memo after capture, before worker reads it.
        var packed=(LodPreparedItems.Result)build.invoke(input);
        check(packed.cachedSectionCount==0 && packed.clusterCount==1,"cleared cache after capture safely uses immutable raw metadata");
        failAt=allocations+2; // Second allocation fails after new section input allocation succeeded.
        try {install.invoke(gpu,plan,packed,now);throw new AssertionError("allocation failure escaped");}
        catch(InvocationTargetException e){check(e.getCause() instanceof GpuOutOfMemoryException,"injected allocation failure propagated");}
        failAt=-1;
        check(get(gpu,"activeItems")==oldPlan && (int)get(gpu,"itemRingIndex")==ring,"failed installation preserves complete old list/ring");
        check(leases.count()==1,"failed candidate relinquishes its lease");
        for(int i=0;i<4;i++)check(MemoryUtil.memGetInt(ptr+4L*i)==expected[i],"failed install leaves old buffer bytes intact");
        // Fade writes must target the old captured geometry while a replacement Node.gpu is already live.
        method("writeVisibility",LodManager.Node.class,float.class).invoke(gpu,n,3.0f);
        check(MemoryUtil.memGetFloat(infos.contents()+12)==3.0f,"captured old slot receives terminal fade");
        check(oldSections!=n.gpu,"fixture replaced node GPU identity");
        sectionItems.invoke(gpu,n.gpu.getFirst());
        Object ready=capture.invoke(gpu,null,second,now);
        Object readyInput=get(ready,"input");
        var readyPacked=(LodPreparedItems.Result)build.invoke(readyInput);
        check(readyPacked.cachedSectionCount==1 && readyPacked.cachedClusterCount==1,"replacement uses one complete cached object");
        check(Arrays.equals(packed.sectionItems,readyPacked.sectionItems)
            && Arrays.equals(packed.groups.getFirst().items(),readyPacked.groups.getFirst().items()),"cached and cleared paths produce exact same replacement bytes");
        releaseItems.invoke(gpu,n.gpu.getFirst());
        check((boolean)install.invoke(gpu,ready,readyPacked,now),"ready replacement installs");
        check(leases.count()==2,"old lease remains until GPU completion after swap");
        check(!leases.canRecycle(retired,0,100,6),"swap alone cannot recycle old geometry");
        long seq=timeline.pendingSeq(); timeline.onCommitted(seq);
        page.set(ValueLayout.JAVA_LONG,MtlNative.STATUS_PAGE_COMPLETED_VALUE,seq); timeline.drain();
        check(leases.count()==1 && leases.canRecycle(retired,0,100,6),"completion releases only old snapshot protection");
        var entered=new java.util.concurrent.CountDownLatch(1);
        var proceed=new java.util.concurrent.CountDownLatch(1);
        LodItemWorker<Object,LodPreparedItems.Result> controlled=new LodItemWorker<>("lod-item-prepare-test",request->{
            entered.countDown();
            try {
                if(!proceed.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("worker deadline");
                return (LodPreparedItems.Result)build.invoke(request);
            } catch(Exception e){throw new RuntimeException(e);}
        });
        set(gpu,"itemWorker",controlled); set(gpu,"itemsDirty",true); set(gpu,"frame",20L);
        Object beforeWork=get(gpu,"activeItems");
        check((boolean)prepare.invoke(gpu,null,second,now),"async offer retains complete active list");
        check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS),"actual CPU worker entered builder");
        check(get(gpu,"pendingItems")!=null && get(gpu,"activeItems")==beforeWork,"running request cannot publish into active state");
        set(gpu,"chunkRevision",1L); // A one-frame chunk event remains latched until acceptance.
        proceed.countDown();
        awaitResult(controlled);
        prepare.invoke(gpu,null,second,now);
        if(LodGpu.asyncExactRevalidate) {
            check((long)get(gpu,"asyncRejected")==0 && get(gpu,"activeItems")!=beforeWork,
                "fresh equal packing accepts completed records despite unrelated chunk event");
            check((long)get(gpu,"asyncEquivalent")==1 && (long)get(get(gpu,"activeItems"),"chunks")==1,
                "acceptance installs fresh ownership and current chunk revision");
        } else {
            check((long)get(gpu,"asyncRejected")==1 && get(gpu,"activeItems")==beforeWork,
                "latched chunk change rejects stale result without exposing it");
            awaitResult(controlled); // The rejected job made room for one current request, not an unbounded queue.
            prepare.invoke(gpu,null,second,now);
        }
        check((long)get(gpu,"asyncAccepted")==1 && (long)get(gpu,"asyncAcceptedClusters")==1,
            "actual worker completion installs nonempty exact input");
        check((long)get(gpu,"asyncWorkerNs")>0 && get(gpu,"pendingItems")==null,"worker activity and ownership completion recorded");
        seq=timeline.pendingSeq();timeline.onCommitted(seq);page.set(ValueLayout.JAVA_LONG,MtlNative.STATUS_PAGE_COMPLETED_VALUE,seq);timeline.drain();
        if(LodGpu.asyncExactRevalidate) {
            set(gpu,"itemsDirty",true); set(gpu,"frame",30L);
            prepare.invoke(gpu,null,second,now); awaitResult(controlled);
            Object beforeReplacement=get(gpu,"activeItems");
            n.gpu=List.of(section(2,32)); set(gpu,"geometryEpoch",3L);
            prepare.invoke(gpu,null,second,now);
            check((long)get(gpu,"asyncRejected")==1 && get(gpu,"activeItems")==beforeReplacement,
                "changed geometry rejects completed words even with exact revalidation enabled");
            awaitResult(controlled); prepare.invoke(gpu,null,second,now);
            check((long)get(gpu,"asyncAccepted")==2,"replacement becomes visible only after its new pack completes");
            int nextRing=(int)get(gpu,"itemRingIndex");
            check(MemoryUtil.memGetInt(MetalClusterCull.contents(rings[nextRing]))==4*(32+2),
                "new GPU input uses the replacement range, never old completed words");
            seq=timeline.pendingSeq();timeline.onCommitted(seq);page.set(ValueLayout.JAVA_LONG,MtlNative.STATUS_PAGE_COMPLETED_VALUE,seq);timeline.drain();
        }
        LodGpu.asyncItems=false;
        check(!(boolean)prepare.invoke(gpu,null,second,now),"OFF selects synchronous path");
        check(get(gpu,"activeItems")==null && (boolean)get(gpu,"itemsDirty"),"OFF invalidates experimental state and requests ordinary rebuild");
        check(leases.count()==1,"OFF also defers last active lease");
        // World close does not require another LodGpu.frame; global timeline drain owns the final release.
        gpu.close(); check(!leases.disposed(),"world close waits for last GPU-owned lease");
        seq=timeline.pendingSeq();timeline.onCommitted(seq);page.set(ValueLayout.JAVA_LONG,MtlNative.STATUS_PAGE_COMPLETED_VALUE,seq);timeline.drain();
        check(leases.disposed(),"post-world timeline completion disposes geometry");
        boolean savedResidentSet = LodGpu.residentSet;
        try {
            for (boolean useSet : new boolean[]{false, true}) {
                LodGpu.residentSet = useSet;
                failedSecondUpload(device);
            }
        } finally { LodGpu.residentSet = savedResidentSet; }
        arrivingChunkDuringFade(device,page);
        global.set(null,null);
        System.out.println("LodAsyncItemsTest: "+checks+" checks passed; CPU-backed buffers, no GPU/game");
    }

    static void packingIdentity() throws Exception {
        Class<?> type=Class.forName(LodGpu.class.getName()+"$ItemInput");
        var ctor=type.getDeclaredConstructor(List.class,List.class); ctor.setAccessible(true);
        var same=type.getDeclaredMethod("samePacking",type); same.setAccessible(true);
        var a=List.of(section(0,0)); var b=List.of(section(1,1));
        var na=new LodPreparedItems.Node(a,true,0); var nb=new LodPreparedItems.Node(b,true,1);
        var key=new LodPreparedItems.Key(0,0,true,-1); var other=new LodPreparedItems.Key(1,0,true,-1);
        Object base=ctor.newInstance(List.of(na,nb),List.of(key,other));
        check((boolean)same.invoke(base,ctor.newInstance(List.of(new LodPreparedItems.Node(a,true,0),nb),List.of(key,other))),
            "fresh wrappers preserve identical ordered immutable inputs");
        for(var changed:List.of(List.of(nb,na),List.of(na),List.of(new LodPreparedItems.Node(a,false,0),nb),
                List.of(new LodPreparedItems.Node(a,true,1),nb),
                List.of(new LodPreparedItems.Node(new ArrayList<>(a),true,0),nb)))
            check(!(boolean)same.invoke(base,ctor.newInstance(changed,List.of(key,other))),
                "order, count, fade, level or source-list identity change rejects reuse");
        check(!(boolean)same.invoke(base,ctor.newInstance(List.of(na,nb),List.of(other,key))),
            "seed group order must also match");
    }
    /** Constructor is never run: only getChunk is used by the actual draw controllers. */
    private static final class TestChunkSource extends net.minecraft.client.multiplayer.ClientChunkCache {
        net.minecraft.world.level.chunk.LevelChunk available;
        private TestChunkSource() { super(null, 0); }
        @Override public net.minecraft.world.level.chunk.LevelChunk getChunk(int x, int z,
                net.minecraft.world.level.chunk.status.ChunkStatus status, boolean load) {
            check(!load, "fade handoff must not request a chunk load");
            return available;
        }
    }

    @SuppressWarnings("unchecked")
    static LodGpu fadeFixture(MetalDevice device) throws Exception {
        LodGpu gpu=new LodGpu(null);
        set(gpu,"sectionInfos",MetalBuffer.create(device,null,GpuBuffer.USAGE_MAP_WRITE,256));
        Class<?> type=Class.forName(LodGpu.class.getName()+"$Arena");
        Object arena=blank(type);set(arena,"index",0);
        set(arena,"buffer",MetalBuffer.create(device,null,GpuBuffer.USAGE_MAP_WRITE,256));
        ((List<Object>)get(gpu,"arenas")).add(arena);
        return gpu;
    }

    static void fadeBuild(LodGpu gpu,net.minecraft.client.multiplayer.ClientLevel level,
            LodManager.Selection selection,long now,boolean async,MemorySegment status) throws Exception {
        set(gpu,"frame",(long)get(gpu,"frame")+10L);
        if(async) {
            // Exercise the real starvation fallback, not installItems in isolation.
            set(gpu,"asyncRejectStreak",2);
            method("prepareItemsAsync",net.minecraft.client.multiplayer.ClientLevel.class,LodManager.Selection.class,long.class)
                .invoke(gpu,level,selection,now);
        } else method("buildItems",net.minecraft.client.multiplayer.ClientLevel.class,LodManager.Selection.class,long.class,boolean.class)
            .invoke(gpu,level,selection,now,true);
        // Simulate completion between the ten-frame steps; otherwise three retained leases
        // correctly stop new captures despite no GPU work in this CPU-backed rig.
        MetalTimeline t=((MetalBuffer)get(gpu,"sectionInfos")).device().timeline();
        long seq=t.pendingSeq();t.onCommitted(seq);
        status.set(ValueLayout.JAVA_LONG,MtlNative.STATUS_PAGE_COMPLETED_VALUE,seq);t.drain();
    }

    @SuppressWarnings("unchecked")
    static void arrivingChunkDuringFade(MetalDevice device,MemorySegment status) throws Exception {
        check(LodGpu.FADE_NANOS>0,"transition fixture needs production fades");
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var level=(net.minecraft.client.multiplayer.ClientLevel)blank(net.minecraft.client.multiplayer.ClientLevel.class);
        var chunks=(TestChunkSource)blank(TestChunkSource.class);
        set(level,"chunkSource",chunks);
        var chunk=(net.minecraft.world.level.chunk.LevelChunk)blank(net.minecraft.world.level.chunk.LevelChunk.class);
        var update=method("updateFades",long.class);
        boolean saved=LodGpu.asyncItems;LodGpu.asyncItems=true;
        for(boolean chunkBeforeDeparture:new boolean[]{false,true}) {
            chunks.available=null;
            LodGpu[] controllers={fadeFixture(device),fadeFixture(device)};
            LodManager.Node[] nodes=new LodManager.Node[2];
            long start=1_000_000_000L, departure=start+2*LodGpu.FADE_NANOS;
            try {
                for(int arm=0;arm<2;arm++) {
                    var n=new LodManager.Node(0,0,0);nodes[arm]=n;
                    n.gpu=List.of(section(0,8));n.resident=true;
                    fadeBuild(controllers[arm],level,new LodManager.Selection(new LodManager.Node[]{n},new boolean[]{false},1),start,arm==1,status);
                    update.invoke(controllers[arm],departure);
                }
                if(chunkBeforeDeparture)chunks.available=chunk;
                var empty=new LodManager.Selection(new LodManager.Node[0],new boolean[0],2);
                for(int arm=0;arm<2;arm++)fadeBuild(controllers[arm],level,empty,departure,arm==1,status);
                chunks.available=chunk;
                long quarter=departure+LodGpu.FADE_NANOS/4;
                for(int arm=0;arm<2;arm++) {
                    LodGpu gpu=controllers[arm];
                    update.invoke(gpu,quarter);
                    fadeBuild(gpu,level,new LodManager.Selection(empty.nodes(),empty.insideRd(),3),quarter,arm==1,status);
                    int expected=chunkBeforeDeparture?0:1;
                    check(((Number)get(gpu,"drawnClusters")).intValue()==expected,
                        "chunk arrival "+(chunkBeforeDeparture?"before departure removes":"during existing fade retains")+" geometry, arm="+arm);
                    var fades=(Map<Object,Object>)get(gpu,"fades");
                    check(fades.containsKey(nodes[arm])==!chunkBeforeDeparture,"fade membership matches synchronous controller, arm="+arm);
                    if(!chunkBeforeDeparture) {
                        check((long)get(fades.get(nodes[arm]),"start")==departure,"chunk arrival does not restart the outgoing fade");
                        long ptr=MetalClusterCull.contents((GpuBuffer)get(gpu,"sectionInfos"));
                        check(MemoryUtil.memGetFloat(ptr+12)==2.25f,"outgoing geometry preserves quarter-fade visibility, arm="+arm);
                        int r=(int)get(gpu,"itemRingIndex");
                        long items=MetalClusterCull.contents(((GpuBuffer[])get(gpu,"itemRing"))[r]);
                        int[] expectedWords={40,3<<20|4<<26,1|2<<10|3<<20,4|5<<10|6<<20};
                        for(int i=0;i<4;i++)check(MemoryUtil.memGetInt(items+4L*i)==expectedWords[i],"retained source cluster word "+i+", arm="+arm);
                    }
                    update.invoke(gpu,departure+LodGpu.FADE_NANOS+1);
                    if(arm==1 && !chunkBeforeDeparture) {
                        long ptr=MetalClusterCull.contents((GpuBuffer)get(gpu,"sectionInfos"));
                        check(MemoryUtil.memGetFloat(ptr+12)==3f,"completed async fade discards until replacement is installed");
                    }
                    fadeBuild(gpu,level,new LodManager.Selection(empty.nodes(),empty.insideRd(),4),departure+LodGpu.FADE_NANOS+1,arm==1,status);
                    check(((Number)get(gpu,"drawnClusters")).intValue()==0,"completed outgoing geometry leaves packed list, arm="+arm
                        +" count="+get(gpu,"drawnClusters")+" error="+get(gpu,"asyncItemError")+" pending="+get(gpu,"pendingItems"));
                    check(((Map<?,?>)get(gpu,"fades")).isEmpty(),"completed fade state removed, arm="+arm);
                }
            } finally { for(LodGpu gpu:controllers)gpu.close(); }
        }
        LodGpu.asyncItems=saved;
    }
    @SuppressWarnings("unchecked")
    static void failedSecondUpload(MetalDevice device)throws Exception {
        // Exercise the real uploadPending ownership boundary: the first slab is already resident
        // when the second slab's immutable memo fails. No native allocation failure is required.
        LodManager manager=(LodManager)blank(LodManager.class);
        var uploads=new java.util.concurrent.ConcurrentLinkedQueue<LodManager.Node>();
        var handoff=new LodMeshHandoff<LodManager.Node,List<LodMesher.Section>>();
        set(manager,"uploads",uploads);set(manager,"meshHandoff",handoff);
        LodGpu gpu=new LodGpu(manager);
        set(gpu,"sectionInfos",MetalBuffer.create(device,null,GpuBuffer.USAGE_MAP_WRITE,256));
        set(gpu,"sectionTable",MetalBuffer.create(device,null,GpuBuffer.USAGE_MAP_WRITE,512));
        GpuBuffer[] vis=(GpuBuffer[])get(gpu,"visBuffers");
        for(int i=0;i<vis.length;i++)vis[i]=MetalBuffer.create(device,null,GpuBuffer.USAGE_MAP_WRITE,256);
        Class<?> arenaType=Class.forName(LodGpu.class.getName()+"$Arena");
        var ctor=arenaType.getDeclaredConstructor(GpuBuffer.class,int.class,boolean.class);ctor.setAccessible(true);
        Object arena=ctor.newInstance(MetalBuffer.create(device,null,GpuBuffer.USAGE_MAP_WRITE,112L*32),0,false);
        var ranges=(TreeMap<Integer,Integer>)get(arena,"free");ranges.clear();ranges.put(0,32);
        ((List<Object>)get(gpu,"arenas")).add(arena);
        var first=new LodMesher.Section(0,0,0,1);
        var second=new LodMesher.Section(0,32,0,1);
        first.vertices[0]=MemoryUtil.memCalloc(112);second.vertices[0]=MemoryUtil.memCalloc(112);
        first.quads[0]=second.quads[0]=1;
        first.clusters[0]=new int[]{0,0,1|1<<10|1<<20};
        second.clusters[0]=new int[]{0,0}; // Real memo-constructor rejection after range/slot allocation.
        var node=new LodManager.Node(1,0,0);
        node.pending=List.of(first,second);node.pendingStamp=7;node.meshStamp=99;
        uploads.add(node);handoff.offer(node,node.pending,7,224);
        boolean oldAsync=LodGpu.asyncItems,oldCache=LodGpu.cacheItems;
        LodGpu.asyncItems=true;LodGpu.cacheItems=true;
        try {
            try {gpu.uploadPending(4096,0,0,0);throw new AssertionError("invalid second memo accepted");}
            catch(IllegalArgumentException expected){check(expected.getMessage().equals("Incomplete cluster record"),"second memo failure propagated");}
            check((get(gpu,"resident") instanceof LinkedHashSet<?>) == LodGpu.residentSet,"actual upload uses requested resident container");
            check((long)get(gpu,"asyncUploadRecordSections")==1,"first section cache built before second section failed");
            check(node.gpu==null && !node.resident && node.gpuBytes==0 && node.gpuStamp==-1,"no partial node survives failed batch");
            check(node.meshStamp==99,"upload cleanup preserves newer producer scheduling marker");
            check(((Collection<?>)get(gpu,"resident")).isEmpty() && ((Map<?,?>)get(gpu,"origins")).isEmpty(),"resident and origin ownership both released");
            check((long)get(gpu,"gpuBytes")==0 && (long)get(gpu,"residentAllocationBytes")==0,"both payload and allocated GPU accounting return to zero");
            check((long)get(gpu,"itemCachePayloadBytes")==0,"earlier immutable memo payload released");
            check((int)get(gpu,"freeSlotCount")==1 && ((List<?>)get(gpu,"deferredFrees")).size()==2,"unpublished failing slab immediate, earlier slab safely deferred");
            set(gpu,"frame",100L);method("releaseDeferred").invoke(gpu);
            check(ranges.equals(Map.of(0,32)),"all ranges coalesce after failed batch retirement");
            check((int)get(gpu,"freeSlotCount")==2 && ((List<?>)get(gpu,"deferredFrees")).isEmpty(),"both slots reclaimed with no orphan deferred entries");
            int[] slots=(int[])get(gpu,"freeSlots");
            check(slots[0]!=slots[1] && slots[0]+slots[1]==1,"each acquired slot reclaimed exactly once");
        } finally {
            gpu.close();MemoryUtil.memFree(first.vertices[0]);MemoryUtil.memFree(second.vertices[0]);
            LodGpu.asyncItems=oldAsync;LodGpu.cacheItems=oldCache;
        }
    }
    static void awaitResult(LodItemWorker<?,?> worker)throws Exception {
        Field completed=field(LodItemWorker.class,"completed");
        long until=System.nanoTime()+5_000_000_000L;
        while(System.nanoTime()<until) {
            synchronized(worker){if(completed.get(worker)!=null)return;}
            Thread.yield();
        }
        throw new AssertionError("worker completion deadline");
    }
}
