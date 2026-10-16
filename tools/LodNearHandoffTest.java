package net.minecraft.client.renderer.chunk;

import static net.minecraft.client.renderer.chunk.LodAsyncItemsTest.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.metal.*;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;

/** CPU-backed real controller tests; no native cull, game, image or FPS qualification. */
public final class LodNearHandoffTest {
    static final long LOW=SectionPos.asLong(13,4,-4), HIGH=SectionPos.asLong(13,5,-4);
    public static void main(String[] args) throws Exception {
        System.setProperty("mc.lod", "true");
        RenderSystem.initRenderThread();
        MemorySegment page=MemorySegment.ofArray(new long[(int)MtlNative.STATUS_PAGE_SIZEOF/8]);
        MetalTimeline timeline=new MetalTimeline(0,page);
        MetalDevice device=MetalDevice.forTesting(0,page,timeline,null,false);
        GpuDevice facade=(GpuDevice)Proxy.newProxyInstance(GpuDevice.class.getClassLoader(),new Class<?>[]{GpuDevice.class},(p,m,a)->{
            if(m.getName().equals("createBuffer") && a.length==3 && a[2] instanceof Long n)
                return MetalBuffer.create(device,null,(int)a[1],n);
            throw new AssertionError("unexpected device call "+m.getName());
        });
        field(RenderSystem.class,"DEVICE").set(null,facade);
        policy(); readiness(device); installedAndMasks(device,page); retention(device,page); emptyOutsideDrawList(); legacyFadeBypass(); outsideViewReadiness();
        field(RenderSystem.class,"DEVICE").set(null,null);
        System.out.println("LodNearHandoffTest: "+checks+" checks passed (CPU only; gates pending)");
    }
    static void legacyFadeBypass() throws Exception {
        var manager=(LodManager)blank(LodManager.class);
        var gpu=new LodGpu(manager);set(manager,"gpu",gpu);
        var nodes=new java.util.concurrent.ConcurrentHashMap<Long,LodManager.Node>();
        set(manager,"nodes",nodes);
        var node=new LodManager.Node(0,13,-4);node.lastDrawnMillis=9900;
        nodes.put(LodManager.key(0,13,-4),node);
        var global=field(Lod.class,"manager");Object saved=global.get(null);
        long uploads=Lod.SECTION_UPLOADS.get(), skips=Lod.FADE_SKIPS.get();
        try {
            global.set(null,manager);
            check(Lod.sectionUploadTime(LOW,10000)==10000-3600000L,"inactive handoff preserves legacy recent-draw fade shortcut");
            set(gpu,"nearHandoffActive",true);
            check(Lod.sectionUploadTime(LOW,10000)==10000,"active handoff never treats retained membership as visible exposure");
            check(Lod.SECTION_UPLOADS.get()==uploads+1 && Lod.FADE_SKIPS.get()==skips+1,"active handoff cannot count a legacy fade skip");
            set(gpu,"nearHandoffActive",false);
            check(Lod.sectionUploadTime(LOW,10000)==10000-3600000L,"disabling handoff restores legacy behavior");
        } finally {global.set(null,saved);Lod.SECTION_UPLOADS.set(uploads);Lod.FADE_SKIPS.set(skips);gpu.close();}
    }
    static void policy() {
        LodNearCoverage c=new LodNearCoverage(64);
        c.bind(LOW,1);c.bind(HIGH,2);c.bind(LOW,3); // Old and replacement physical slots.
        c.beginFrame();c.observe(LOW,false);c.observe(HIGH,true);
        check(c.update() && c.coveredCount()==1,"mixed-height uncompiled slab stays exposed");
        ByteBuffer a=ByteBuffer.allocate(64),b=ByteBuffer.allocate(64);
        for(int i=0;i<64;i++){a.put(i,(byte)7);b.put(i,(byte)9);}
        c.write(a,64);check(a.get(1)==0 && a.get(2)==1 && a.get(3)==0 && a.get(63)==0,"full physical mask clear");
        long r=c.revision();c.beginFrame();c.observe(HIGH,true);
        check(!c.update() && c.revision()==r,"identical content leaves reuse revision stable");
        c.beginFrame();c.observe(LOW,true);c.observe(HIGH,true);c.observe(HIGH,false);
        check(c.update(),"ready replaces low slab, conflicting duplicate uncovers high");
        c.write(b,64);check(b.get(1)==1&&b.get(3)==1&&b.get(2)==0,"current coordinates update all leased slots");
        c.beginFrame();c.observe(LOW,false);c.observeEmpty(LOW);c.update();c.write(a,64);
        check(a.get(1)==1 && a.get(3)==1,"authoritative current air covers even without a compiled/visible mesh");
        c.clearBindings();c.bind(999L,1);c.beginFrame();c.observe(LOW,true);c.update();c.write(a,64);
        check(a.get(1)==0 && c.coveredCount()==0,"rotated coordinate/recycled slot has no inherited coverage");
        check(!LodNearCoverage.writable(12,11)&&LodNearCoverage.writable(12,12),"GPU lag blocks writes");
        // Narrowed history reset: only covers not backed by drawn near geometry require a whole-history reset.
        LodNearCoverage h=new LodNearCoverage(8);h.bind(LOW,1);h.bind(HIGH,2);
        h.beginFrame();h.observe(LOW,true);check(h.update()&&!h.changeNeedsHistoryReset()&&!h.changeWasUncoverOnly(),"drawn near mesh cover keeps history");
        h.beginFrame();check(h.update()&&!h.changeNeedsHistoryReset()&&h.changeWasUncoverOnly(),"uncover only keeps history");
        h.beginFrame();h.observeEmpty(HIGH);check(h.update()&&h.changeNeedsHistoryReset(),"authoritative-air cover resets history");
        h.beginFrame();h.observeEmpty(HIGH);h.observe(LOW,true);check(h.update()&&!h.changeNeedsHistoryReset(),"existing air cover plus drawn cover keeps history");
        h.clearBindings();h.bind(LOW,1,true);h.bind(HIGH,2,true);h.beginFrame();h.update();
        h.beginFrame();h.observeOutside(HIGH,true);check(h.update()&&h.changeNeedsHistoryReset(),"outside-view cover resets history");
        try{c.bind(1,64);throw new AssertionError("slot overflow accepted");}catch(IllegalArgumentException expected){check(true,"slot bounds");}
        // Independent Boolean reference across random bind/readiness/physical-buffer rotations.
        // A never-exposed, already-masked fallback must not skip a near fade on later frames.
        LodNearCoverage fades=new LodNearCoverage(4);fades.bind(LOW,1);Object mesh=new Object();
        fades.beginFrame();fades.observe(LOW,true);fades.update();fades.captureExposure(false);
        fades.beginFrame();check(!fades.fullVisibility(LOW,mesh,4L,true,0.2f),"masked-only membership preserves unrelated fade");
        fades.observe(LOW,false);fades.update();fades.captureExposure(false);
        fades.beginFrame();check(fades.fullVisibility(LOW,mesh,4L,true,0.3f),"genuine exposed fallback triggers full near visibility");
        fades.observe(LOW,true);fades.update();fades.captureExposure(false);
        fades.beginFrame();check(fades.fullVisibility(LOW,mesh,4L,true,0.4f),"ordinary recompile within section generation stays full after fallback is masked");
        fades.beginFrame();check(!fades.fullVisibility(LOW,mesh,5L,true,0.1f),"reset generation cannot inherit full-visibility latch");
        fades.captureExposure(true);fades.beginFrame();check(fades.fullVisibility(LOW,mesh,4L,true,0.2f),"OFF-to-ON uses old unmasked geometry");
        fades.beginFrame();check(!fades.fullVisibility(LOW,mesh,4L,true,1f),"completed ordinary fade releases latch");
        Random rng=new Random(731);Map<Long,List<Integer>> bindings=new HashMap<>();
        ByteBuffer[] buffers={a,b,ByteBuffer.allocate(64),ByteBuffer.allocate(64)};
        for(int f=0;f<1000;f++){
            if(f%7==0){c.clearBindings();bindings.clear();for(int slot=0;slot<64;slot++){
                long key=rng.nextInt(40)-20;bindings.computeIfAbsent(key,k->new ArrayList<>()).add(slot);c.bind(key,slot);
            }}
            c.beginFrame();Set<Long> good=new HashSet<>(),bad=new HashSet<>();
            for(int i=0;i<40;i++){long key=rng.nextInt(40)-20;boolean ready=rng.nextBoolean();c.observe(key,ready);(ready?good:bad).add(key);}
            boolean[] expected=new boolean[64];good.removeAll(bad);
            for(long key:good)for(int slot:bindings.getOrDefault(key,List.of()))expected[slot]=true;
            c.update();ByteBuffer out=buffers[f%4];c.write(out,64);
            for(int slot=0;slot<64;slot++)check((out.get(slot)!=0)==expected[slot],"random coverage frame "+f+" slot "+slot);
        }
    }
    @SuppressWarnings("unchecked")
    static CompiledSectionMesh mesh() throws Exception {
        CompiledSectionMesh m=new CompiledSectionMesh(null,new SectionCompiler.Results(),0L);
        Map<ChunkSectionLayer,SectionMesh.SectionDraw> draws=(Map<ChunkSectionLayer,SectionMesh.SectionDraw>)get(m,"draws");
        for(ChunkSectionLayer l:ChunkSectionLayer.values()){
            var d=new SectionMesh.SectionDraw(6,IndexType.INT,l==ChunkSectionLayer.TRANSLUCENT);
            draws.put(l,d);m.drawsByLayer[l.ordinal()]=d;TerrainDrawList.describe(m.hot,l.ordinal(),d);
        }
        return m;
    }
    static void readiness(MetalDevice device) throws Exception {
        check(!TerrainDrawList.nearMeshReady(CompiledSectionMesh.UNCOMPILED,null),"UNCOMPILED never covers");
        check(!TerrainDrawList.nearMeshReady(CompiledSectionMesh.EMPTY,null),"bare EMPTY has no validated section provenance");
        var empty=new CompiledSectionMesh(null,new SectionCompiler.Results(),0L);
        check(TerrainDrawList.nearMeshReady(empty,null),"block-entity-only compiled terrain is authoritative empty");
        var m=mesh();MetalBuffer v=MetalBuffer.create(device,null,GpuBuffer.USAGE_VERTEX,1024);
        MetalBuffer idx=MetalBuffer.create(device,null,GpuBuffer.USAGE_INDEX,1024);
        for(ChunkSectionLayer l:ChunkSectionLayer.values()){
            m.setUberSlice(l.ordinal(),false,v,0);
            check(!TerrainDrawList.nearMeshReady(m,null),"allocation without completed uploads is uncovered");
            m.setVertexBufferUploaded(l);
        }
        check(!TerrainDrawList.nearMeshReady(m,null),"custom index absent keeps all LOD layers");
        m.setUberSlice(2,true,idx,0);check(!TerrainDrawList.nearMeshReady(m,null),"custom index allocation not sufficient");
        m.setIndexBufferUploaded(ChunkSectionLayer.TRANSLUCENT);
        check(TerrainDrawList.nearMeshReady(m,null),"all required buffers ready");
        m.setUberSlice(1,false,null,0);check(!TerrainDrawList.nearMeshReady(m,null),"lost allocation uncovers whole slab");
        m.setUberSlice(1,false,v,0);
        // Exercise production emission with the handoff decision: visibility is in the real section UBO words.
        SectionRenderDispatcher.RenderSection section=(SectionRenderDispatcher.RenderSection)blank(SectionRenderDispatcher.RenderSection.class);
        set(section,"renderOrigin",new BlockPos.MutableBlockPos(208,64,-64));set(section,"sectionNode",LOW);
        Method add=TerrainDrawList.class.getDeclaredMethod("addCompiled",SectionRenderDispatcher.RenderSection.class,CompiledSectionMesh.class);add.setAccessible(true);
        TerrainDrawList list=new TerrainDrawList();
        list.begin(false,null,false,false,false,false,0,0,0,0,0,0,TerrainRuns.NO_SPLIT,0,0,100L,1000L,false,0,false,false);
        set(list,"nearFullVisibility",true);add.invoke(list,section,m);
        check(list.sectionCount()==1 && Float.intBitsToFloat(list.sectionDataArray()[3])==1f,"replacement emits full visibility into actual draw list");
        v.close();idx.close();
    }
    static LodGpu.GpuSection slab(int slot,int y) {
        var s=section(slot,slot*8);s.origin=new int[]{208,y,-64,0};return s;
    }
    @SuppressWarnings("unchecked")
    static void installedAndMasks(MetalDevice device,MemorySegment status) throws Exception {
        LodGpu gpu=fadeFixture(device);LodNearCoverage c=new LodNearCoverage(64);
        set(gpu,"nearCoverage",c);set(gpu,"nearHandoffActive",true);set(gpu,"nextSlot",4);
        var n=new LodManager.Node(0,13,-4);n.gpu=List.of(slab(1,64),slab(2,80));
        // Retain actual async snapshot; current resident slots subsequently change.
        Class<?> planType=Class.forName(LodGpu.class.getName()+"$ItemPlan");
        var geometry=new IdentityHashMap<LodManager.Node,List<LodGpu.GpuSection>>();geometry.put(n,n.gpu);
        Object plan=blank(planType);set(plan,"geometry",geometry);set(gpu,"activeItems",plan);
        n.gpu=List.of(slab(3,64));
        c.beginFrame();c.observe(HIGH,true);c.observe(LOW,false);
        Arrays.fill((long[])get(gpu,"lastOcclFrame"),18L);
        method("updateNearCoverage").invoke(gpu);
        check(c.coveredCount()==1 && (long)get(gpu,"visibilityRevision")==1,"installed retired slabs, not new resident geometry");
        for(long f:(long[])get(gpu,"lastOcclFrame"))check(f==Long.MIN_VALUE,"coverage change invalidates temporal occlusion");
        c.captureExposure(true);
        check(!gpu.observeNearSection(LOW,new Object(),4L,false,0.25f)&&gpu.observeNearSection(HIGH,new Object(),4L,true,0.25f),"full visibility only for ready exposed fallback coordinates");
        Class<?> slotType=Class.forName(LodGpu.class.getName()+"$Slot");var ctor=slotType.getDeclaredConstructor();ctor.setAccessible(true);
        Object slot=ctor.newInstance();Method mask=method("nearMask",slotType);
        GpuBuffer first=(GpuBuffer)mask.invoke(gpu,slot);long ptr=MetalClusterCull.contents(first);
        check(MemoryUtil.memGetByte(ptr+1)==0&&MemoryUtil.memGetByte(ptr+2)==1&&MemoryUtil.memGetByte(ptr+3)==0,"leased slot masked, resident replacement not masked");
        check(!gpu.observeNearSection(SectionPos.asLong(90,4,90),new Object(),4L,true,0.25f),"unrelated ready mesh does not suppress ordinary fade");
        c.beginFrame();c.observe(LOW,true);method("updateNearCoverage").invoke(gpu);
        GpuBuffer second=(GpuBuffer)mask.invoke(gpu,slot);
        check(second!=first && MemoryUtil.memGetByte(ptr+2)==1,"in-flight physical mask unchanged on coverage switch");
        check(MemoryUtil.memGetByte(MetalClusterCull.contents(second)+1)==1&&MemoryUtil.memGetByte(MetalClusterCull.contents(second)+2)==0,"replacement mask cleared and current");
        check(mask.invoke(gpu,slot)==second,"immutable identical mask can be read again without allocation");
        MetalTimeline t=device.timeline();long seq=t.pendingSeq();t.onCommitted(seq);status.set(ValueLayout.JAVA_LONG,MtlNative.STATUS_PAGE_COMPLETED_VALUE,seq);t.drain();
        c.beginFrame();method("updateNearCoverage").invoke(gpu);
        check(mask.invoke(gpu,slot)==second,"completed physical mask can be rewritten");
        check(MemoryUtil.memGetByte(MetalClusterCull.contents(second)+1)==0,"uncover clears previous physical bytes");
        set(gpu,"activeItems",null);second.close();gpu.close();
    }
    private static final class EmptyView extends net.minecraft.client.renderer.ViewArea {
        boolean contains;
        SectionRenderDispatcher.RenderSection section;
        private EmptyView(){ super(null,64,95,4,5,0,null); }
        @Override public SectionPos getCameraSectionPos(){return SectionPos.of(13,4,-4);}
        @Override public int getViewDistance(){return 0;}
        @Override public int minSectionY(){return 4;}
        @Override public int maxSectionY(){return 5;}
        @Override public SectionRenderDispatcher.RenderSection getRenderSectionAt(BlockPos p){return contains?section:null;}
    }
    static void emptyOutsideDrawList() throws Exception {
        var level=(net.minecraft.client.multiplayer.ClientLevel)blank(net.minecraft.client.multiplayer.ClientLevel.class);
        var chunks=(ChunkSource)blank(ChunkSource.class);set(level,"chunkSource",chunks);
        chunks.chunk=(net.minecraft.world.level.chunk.LevelChunk)blank(net.minecraft.world.level.chunk.LevelChunk.class);
        var low=(net.minecraft.world.level.chunk.LevelChunkSection)blank(net.minecraft.world.level.chunk.LevelChunkSection.class);
        var high=(net.minecraft.world.level.chunk.LevelChunkSection)blank(net.minecraft.world.level.chunk.LevelChunkSection.class);
        set(high,"nonEmptyBlockCount",(short)1);
        field(net.minecraft.world.level.chunk.ChunkAccess.class,"sections").set(chunks.chunk,new net.minecraft.world.level.chunk.LevelChunkSection[]{low,high});
        field(net.minecraft.world.level.chunk.ChunkAccess.class,"levelHeightAccessor").set(chunks.chunk,
            new net.minecraft.world.level.LevelHeightAccessor(){public int getMinY(){return 64;}public int getHeight(){return 32;}});
        var manager=(LodManager)blank(LodManager.class);set(manager,"level",level);
        var gpu=new LodGpu(manager);var c=new LodNearCoverage(4);set(gpu,"nearCoverage",c);
        c.bind(LOW,1);c.bind(HIGH,2);
        var view=(EmptyView)blank(EmptyView.class);view.contains=true;
        view.section=(SectionRenderDispatcher.RenderSection)blank(SectionRenderDispatcher.RenderSection.class);
        ByteBuffer mask=ByteBuffer.allocate(4);
        c.beginFrame();gpu.captureNearEmptySections(view);c.update();c.write(mask,4);
        check(mask.get(1)==1&&mask.get(2)==0,"current all-air section covers despite absence from visibleSections; solid sibling does not");
        set(low,"nonEmptyBlockCount",(short)1);
        c.beginFrame();gpu.captureNearEmptySections(view);c.update();c.write(mask,4);
        check(mask.get(1)==0,"air-to-solid change cannot inherit graph's stale empty marker");
        set(low,"nonEmptyBlockCount",(short)0);view.contains=false;
        c.beginFrame();gpu.captureNearEmptySections(view);c.update();c.write(mask,4);
        check(mask.get(1)==0,"outside near ViewArea never supplies air coverage");
        view.contains=true;chunks.chunk=null;
        c.beginFrame();gpu.captureNearEmptySections(view);c.update();c.write(mask,4);
        check(mask.get(1)==0,"unloaded chunk is unknown, never assumed air");
        gpu.close();
    }

    private static final class ChunkSource extends net.minecraft.client.multiplayer.ClientChunkCache {
        private ChunkSource(){super(null,0);}
        net.minecraft.world.level.chunk.LevelChunk chunk;
        @Override public net.minecraft.world.level.chunk.LevelChunk getChunk(int x,int z,net.minecraft.world.level.chunk.status.ChunkStatus s,boolean load){
            check(!load,"handoff never loads chunks");return chunk;
        }
    }

    static void outsideViewReadiness() throws Exception {
        var projection=new org.joml.Matrix4f().perspective((float)Math.toRadians(70),1f,0.1f,1000f);
        double[] frustum=TerrainFrustum.of(projection,new org.joml.Matrix4f()).planesCopy();
        check(LodGpu.nearFallbackOutside(frustum,208,64,-64),"padded valid L0 envelope is offscreen");
        check(!LodGpu.nearFallbackOutside(frustum,0,0,-200),"inside-view readiness needs a draw-list observation");
        check(!LodGpu.nearFallbackOutside(frustum,20,0,-10),"overhang envelope intersects view");
        check(!LodGpu.nearFallbackOutside(null,208,64,-64),"absent exact frustum fails closed");
        check(!LodGpu.nearFallbackOutside(frustum,Double.NaN,0,-100)
            && !LodGpu.nearFallbackOutside(frustum,Double.POSITIVE_INFINITY,0,-100),"nonfinite camera-relative coordinates fail closed");
        int lo=TerrainClusters.pack(0,0,0,false),hi=TerrainClusters.pack(16,16,16,true);
        var valid=new LodSectionItems(1,new int[]{0,-1,-1},new int[3],new int[][]{new int[]{0,lo,hi},null,null});
        var invalid=new LodSectionItems(1,new int[]{0,-1,-1},new int[3],new int[][]{new int[]{1<<25,lo,hi},null,null});
        check(valid.hasBoundedSection()&&!invalid.hasBoundedSection(),"unusable native bounds forbid extra coverage");
        LodNearCoverage c=new LodNearCoverage(4);c.bind(LOW,1,true);c.bind(LOW,2,false);
        check(!c.hasBoundedFallback(LOW),"one unbounded installed slab forbids proof for the coordinate");
        c.clearBindings();c.bind(LOW,1,true);check(c.hasBoundedFallback(LOW),"rebinding cannot inherit unbounded metadata");
        c.beginFrame();c.observeOutside(LOW,true);c.clearBindings();c.bind(LOW,2,false);c.update();
        check(c.coveredCount()==0,"replacement with unbounded geometry invalidates earlier outside-only evidence");
        c.observe(LOW,true);c.update();check(c.coveredCount()==1,"actual visible ready observation survives outside-proof rejection");
        c.beginFrame();c.observeOutside(LOW,true);c.observeEmpty(LOW);c.update();
        check(c.coveredCount()==1,"authoritative air survives outside-proof rejection");
        c.clearBindings();c.bind(LOW,1,true);
        var gpu=new LodGpu(null);set(gpu,"nearCoverage",c);
        var view=(EmptyView)blank(EmptyView.class);view.contains=true;
        var section=(SectionRenderDispatcher.RenderSection)blank(SectionRenderDispatcher.RenderSection.class);
        set(section,"sectionNode",LOW);set(section,"renderOrigin",new BlockPos.MutableBlockPos(208,64,-64));
        set(section,"sectionMesh",new java.util.concurrent.atomic.AtomicReference<SectionMesh>(new CompiledSectionMesh(null,new SectionCompiler.Results(),0L)));
        view.section=section;boolean saved=LodGpu.stableNearCoverage;LodGpu.stableNearCoverage=true;
        try {
            c.beginFrame();c.update();c.captureExposure(false);
            c.beginFrame();gpu.captureNearReadyOutsideView(view,null,frustum,0,0,0,100,1000);
            check(c.update()&&c.coveredCount()==1,"real ready mesh outside the view covers its installed bounded fallback");
            c.captureExposure(false);c.beginFrame();
            check(gpu.observeNearSection(LOW,section,section.handoffGeneration(),true,.2f),"outside-to-visible handoff preserves the same-generation full-visibility latch");
            check(!c.update(),"entering the visible list does not change stable readiness coverage");
            c.beginFrame();gpu.captureNearReadyOutsideView(view,null,frustum,0,0,0,100,1000);
            check(!c.update(),"leaving the visible list does not change stable readiness coverage");
            c.beginFrame();gpu.captureNearReadyOutsideView(view,null,frustum,208,64,136,100,1000);c.update();
            check(c.coveredCount()==0,"inside-view mesh omitted by graph is not silently covered");
            c.beginFrame();gpu.captureNearReadyOutsideView(view,null,frustum,0,0,0,100,1000);c.observe(LOW,false);c.update();
            check(c.coveredCount()==0,"incomplete draw-list evidence overrides the extra ready read");
            set(section,"sectionMesh",new java.util.concurrent.atomic.AtomicReference<SectionMesh>(CompiledSectionMesh.UNCOMPILED));
            c.beginFrame();gpu.captureNearReadyOutsideView(view,null,frustum,0,0,0,100,1000);c.update();
            check(c.coveredCount()==0,"reset to uncompiled cannot retain stale readiness");
            set(section,"sectionMesh",new java.util.concurrent.atomic.AtomicReference<SectionMesh>(new CompiledSectionMesh(null,new SectionCompiler.Results(),0L)));
            set(section,"sectionNode",HIGH);c.beginFrame();gpu.captureNearReadyOutsideView(view,null,frustum,0,0,0,100,1000);c.update();
            check(c.coveredCount()==0,"rotated RenderSection cannot mask its old coordinate");
            set(section,"sectionNode",LOW);LodGpu.stableNearCoverage=false;
            c.beginFrame();gpu.captureNearReadyOutsideView(view,null,frustum,0,0,0,100,1000);c.update();
            check(c.coveredCount()==0,"default-off path supplies no extra coverage");
        } finally {LodGpu.stableNearCoverage=saved;gpu.close();}
    }
    static void retention(MetalDevice device,MemorySegment status) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var level=(net.minecraft.client.multiplayer.ClientLevel)blank(net.minecraft.client.multiplayer.ClientLevel.class);
        var chunks=(ChunkSource)blank(ChunkSource.class);chunks.chunk=(net.minecraft.world.level.chunk.LevelChunk)blank(net.minecraft.world.level.chunk.LevelChunk.class);
        set(level,"chunkSource",chunks);
        boolean saved=LodGpu.asyncItems;LodGpu.asyncItems=true;
        for(boolean async:new boolean[]{false,true})for(boolean on:new boolean[]{false,true}) {
            LodGpu gpu=fadeFixture(device);set(gpu,"nearHandoffActive",on);
            var n=new LodManager.Node(0,13,-4);n.gpu=List.of(slab(1,64));
            try {
                fadeBuild(gpu,level,new LodManager.Selection(new LodManager.Node[]{n},new boolean[]{true},1),1_000_000_000L,async,status);
                check((int)get(gpu,"drawnClusters")== (on?1:0),"inside-RD data presence retains L0 only with handoff, async="+async+" on="+on);
            } finally {gpu.close();}
        }
        LodGpu.asyncItems=saved;
    }
}
