package net.minecraft.client.renderer.chunk;

import java.lang.reflect.*;
import java.util.*;

/** CPU only. Actual packet/append/free methods versus the independent, pre-cache scalar packing
 * reference in LodSectionItemsTest. Expected grouping is a separate linear ordered model.
 * No device, native method, game, or renderer initialization. */
public final class LodNodeItemsTest {
    private static int checks;
    private static final Method SCALAR, RECORDS, APPEND, SECTION, CLEAR;
    static {
        try {
            SCALAR = LodSectionItemsTest.class.getDeclaredMethod("scalar", int.class, int[].class, int[].class, int[][].class);
            RECORDS = LodSectionItemsTest.class.getDeclaredMethod("records", int.class, Random.class);
            SCALAR.setAccessible(true); RECORDS.setAccessible(true);
            APPEND = method("appendCachedNode", List.class, boolean.class, int.class);
            SECTION = method("appendCachedSection", LodGpu.GpuSection.class, boolean.class, int.class);
            CLEAR = method("releaseAllNodeItems");
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static Field field(Class<?> c, String name) throws Exception { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
    private static Object get(Object o, String name) throws Exception { return field(o.getClass(), name).get(o); }
    private static void set(Object o, String name, Object value) throws Exception { field(o.getClass(), name).set(o, value); }
    private static Method method(String name, Class<?>... args) throws Exception {
        Method m = LodGpu.class.getDeclaredMethod(name, args); m.setAccessible(true); return m;
    }
    private static Object blank(Class<?> c) throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        return u.getMethod("allocateInstance", Class.class).invoke(field(u,"theUnsafe").get(null), c);
    }
    private static long count(LodGpu o, String name) throws Exception { return (long)get(o,name); }
    @SuppressWarnings("unchecked") private static List<Object> groups(LodGpu o) throws Exception { return (List<Object>)get(o,"groups"); }
    private static LodGpu owner(long budget) throws Exception {
        LodGpu o = (LodGpu)blank(LodGpu.class);
        set(o,"groups",new ArrayList<>()); set(o,"secItems",new int[4]);
        set(o,"drawnQuadsByLevel",new long[8]); set(o,"drawnQuadsBySlot",new long[8]);
        set(o,"groupLookupCalls",new long[2]); set(o,"groupLookupProbes",new long[2]);
        List<Object> arenas = new ArrayList<>();
        Class<?> a = Class.forName(LodGpu.class.getName()+"$Arena");
        for (int i=0;i<3;i++) { Object arena=blank(a); set(arena,"index",i); set(arena,"compact",i==1); arenas.add(arena); }
        set(o,"arenas",arenas); set(o,"nodeItemCache",new LodNodeItems.Cache(budget,64));
        set(o,"resident",new ArrayList<>()); set(o,"origins",new IdentityHashMap<>()); set(o,"deferredFrees",new ArrayList<>());
        set(o,"geometryLeases",new LodGeometryLeases(()->{},()->{}));
        return o;
    }
    private static LodGpu.GpuSection section(int seed, boolean empty) throws Exception {
        var s = new LodGpu.GpuSection(); s.slot=seed%2==0?0:1048575;
        Random r=new Random(seed);
        for (int l=0;l<3;l++) {
            s.arena[l]=(seed+l)%3; s.firstQuad[l]=(seed+1)*12345+l*2396744;
            s.clusters[l]=empty?(l==1?new int[0]:null):(int[])RECORDS.invoke(null,70+seed+l,r);
        }
        if(seed==2) { s.arena[2]=-1; s.clusters[1]=new int[0]; }
        return s;
    }
    private record Key(int layer,int arena,boolean fade,int level) { }
    private static final class Expected {
        final Key key; int[] words=new int[0]; long quads;
        Expected(Key key) { this.key=key; }
    }
    private static final class Reference {
        final List<Expected> groups=new ArrayList<>();
        final List<Integer> sections=new ArrayList<>();
        final long[] levels=new long[8], facing=new long[8];
        int count, sectionCount; long quads;
        void reset() {
            sections.clear(); Arrays.fill(levels,0); Arrays.fill(facing,0); count=sectionCount=0;quads=0;
            for(Expected g:groups) { g.words=new int[0];g.quads=0; }
        }
        void append(List<LodGpu.GpuSection> input,boolean fade,int level) throws Exception {
            for(var s:input) {
                Object raw=SCALAR.invoke(null,s.slot,s.arena,s.firstQuad,s.clusters);
                int[][] items=(int[][])get(raw,"items"); long[] q=(long[])get(raw,"quads"), f=(long[])get(raw,"facing");
                int[] bounds=(int[])get(raw,"section"); sectionCount++;
                if(bounds!=null) for(int word:bounds) sections.add(word);
                for(int l=0;l<3;l++) {
                    if(items[l]==null) continue;
                    Key key=new Key(l,s.arena[l],fade,LodGpu.DIAG_LEVELS?level:-1);
                    Expected g=null; for(Expected e:groups) if(e.key.equals(key)) { g=e;break; }
                    if(g==null) { g=new Expected(key);groups.add(g);groups.sort(Comparator.comparingInt((Expected e)->e.key.layer).thenComparingInt(e->e.key.arena)); }
                    int at=g.words.length; g.words=Arrays.copyOf(g.words,at+items[l].length);
                    System.arraycopy(items[l],0,g.words,at,items[l].length);
                    g.quads+=q[l];quads+=q[l];levels[Math.min(7,level)]+=q[l];count+=items[l].length/4;
                }
                for(int d=0;d<8;d++) facing[d]+=f[d];
            }
        }
        void compare(LodGpu o) throws Exception {
            List<Object> actual=groups(o); check(actual.size()==groups.size(),"including empty/persistent groups");
            for(int i=0;i<groups.size();i++) {
                Object a=actual.get(i);Expected e=groups.get(i);
                check(new Key((int)get(a,"layer"),(int)get(get(a,"arena"),"index"),(boolean)get(a,"fade"),(int)get(a,"level")).equals(e.key),"stable group order/layout/fade/level");
                check((int)get(a,"count")==e.words.length/4&&(long)get(a,"quads")==e.quads,"batch totals");
                check(Arrays.equals(e.words,Arrays.copyOf((int[])get(a,"items"),e.words.length)),"independent flattened cluster bytes");
            }
            check((int)get(o,"drawnSections")==sectionCount,"input sections including empty/duplicate");
            check((int)get(o,"secCount")*4==sections.size(),"nonempty section record count");
            check(Arrays.equals(Arrays.copyOf((int[])get(o,"secItems"),sections.size()),sections.stream().mapToInt(Integer::intValue).toArray()),"section order/union bytes");
            check((int)get(o,"drawnClusters")==count&&count(o,"drawnQuads")==quads,"global totals");
            check(Arrays.equals((long[])get(o,"drawnQuadsByLevel"),levels),"level totals");
            check(Arrays.equals((long[])get(o,"drawnQuadsBySlot"),facing),"facing totals");
        }
    }
    private static void reset(LodGpu o) throws Exception {
        set(o,"secCount",0);set(o,"drawnSections",0);set(o,"drawnClusters",0);set(o,"drawnQuads",0L);
        Arrays.fill((long[])get(o,"drawnQuadsByLevel"),0);Arrays.fill((long[])get(o,"drawnQuadsBySlot"),0);
        for(Object g:groups(o)) {set(g,"count",0);set(g,"quads",0L);}
    }
    private static boolean append(LodGpu o,List<LodGpu.GpuSection> input,boolean fade,int level,boolean nodes) throws Exception {
        boolean replay=nodes&&(boolean)APPEND.invoke(o,input,fade,level);
        if(!replay) for(var s:input) {set(o,"drawnSections",(int)get(o,"drawnSections")+1);SECTION.invoke(o,s,fade,level);}
        return replay;
    }
    private static void equivalence() throws Exception {
        for(long cap:new long[]{0,400,1L<<20}) {
            LodGpu o=owner(cap);Reference ref=new Reference();
            var a=section(0,false);var b=section(1,false);var c=section(2,false);var empty=section(3,true);
            List<List<LodGpu.GpuSection>> nodes=List.of(new ArrayList<>(List.of(b,empty,a,b)),new ArrayList<>(List.of(c,a)),new ArrayList<>(),new ArrayList<>(List.of(empty)));
            int[] order={0,1,3,0,2};
            for(int round=0;round<6;round++) {
                reset(o);ref.reset();set(o,"buildCacheGroups",round%2!=0);
                boolean enabled=round!=0&&round!=3;
                long priorHits=count(o,"nodeItemHits"), priorClusters=count(o,"nodeItemReplayedClusters"), actualClusters=0;
                LodNodeItems.Cache cache=(LodNodeItems.Cache)get(o,"nodeItemCache");int warm=0;
                for(int j=0;j<order.length;j++) {
                    var input=nodes.get(order[j]);boolean fade=(j+round)%2==0;int level=(j+round)%8;
                    boolean wasWarm=cache.get(input)!=null;
                    boolean replay=append(o,input,fade,level,enabled);
                    if(replay) { if(wasWarm)warm++;actualClusters+=cache.get(input).clusterCount; }
                    ref.append(input,fade,level);
                }
                ref.compare(o);
                check(count(o,"nodeItemHits")-priorHits==warm,"hits count only completed warm replays");
                check(count(o,"nodeItemReplayedClusters")-priorClusters==actualClusters,"actual replayed input, not cached potential");
                check(cache.chargedBytes()<=cap,"bounded ownership");
                if(!enabled) check(count(o,"nodeItemHits")==priorHits,"OFF has no replay activity");
            }
            if(cap==(1L<<20)) check(count(o,"nodeItemPacketBuilds")==4,"one build per immutable list; OFF/ON retained");
            if(cap==0) check(count(o,"nodeItemPacketBuilds")==0&&count(o,"nodeItemCapFallbacks")==20,"cap fallback on every attempted node, no packets");
        }
    }
    private static void budgetAndCopies() throws Exception {
        var s=section(4,false);List<LodGpu.GpuSection> source=new ArrayList<>(List.of(s,s));
        long charge=LodNodeItems.chargeFor(source,3);
        check(s.preparedItems==null,"preflight did not prepare section");
        for(int i=0;i<10;i++) check(LodNodeItems.build(source,3,charge-1)==null,"over cap rejected before reading unprepared records");
        LodGpu o=owner(charge-1);
        for(int i=0;i<10;i++) check(!(boolean)APPEND.invoke(o,source,false,0),"production over-cap rejects");
        check(s.preparedItems==null&&count(o,"itemCacheBuilds")==0,"production preflight before section/packet allocation");
        s.preparedItems=new LodSectionItems(s.slot,s.arena,s.firstQuad,s.clusters);
        LodNodeItems p=LodNodeItems.build(source,3,charge);
        check(p!=null&&p.chargedBytes==charge,"exact boundary charge");
        long payload=16L*p.sectionRecords()+16L*p.clusterCount+16L*p.batches()+64;
        check(p.payloadBytes==payload,"owned primitive payload");
        int[] saved=new int[4*p.clusters(0)];p.copyBatch(0,saved,0);
        for(int[] raw:s.clusters) if(raw!=null)Arrays.fill(raw,0);
        s.preparedItems=null;Arrays.fill(s.firstQuad,0);Arrays.fill(s.arena,-1);
        int[] now=new int[saved.length];p.copyBatch(0,now,0);check(Arrays.equals(saved,now),"packet owns copies, source mutation cannot change bytes");
        Arrays.fill(now,0);p.copyBatch(0,now,0);check(Arrays.equals(saved,now),"copy destination cannot mutate packet");
        var cache=new LodNodeItems.Cache(charge*2,2);var equalList=new ArrayList<>(source);
        check(source.equals(equalList)&&source!=equalList,"equal lists distinct identity");
        check(cache.put(source,p)&&cache.get(equalList)==null,"identity key only");
        check(!cache.put(source,p)&&cache.size()==1,"duplicate cannot double-account");
        check(cache.put(equalList,p)&&!cache.put(new Object(),p),"exact byte/entry caps");
        check(cache.payloadBytes()==2*p.payloadBytes&&cache.chargedBytes()==2*charge,"complete packet ownership accounting");
        cache.remove(source);cache.remove(source);check(cache.size()==1&&cache.chargedBytes()==charge,"idempotent release");
        cache.clear();cache.clear();check(cache.size()==0&&cache.payloadBytes()==0&&cache.chargedBytes()==0,"idempotent clear");
        var entryCap=new LodNodeItems.Cache(Long.MAX_VALUE,1);
        check(entryCap.put(source,p)&&!entryCap.put(equalList,p),"independent entry cap");
        LodNodeItems empty=LodNodeItems.build(List.of(),0,320);
        check(empty!=null&&empty.batches()==0&&empty.clusterCount==0&&empty.sectionCount==0,"zero arena/empty node");
    }
    @SuppressWarnings("unchecked") private static void lifetime() throws Exception {
        LodGpu o=owner(1<<20);LodNodeItems.Cache cache=(LodNodeItems.Cache)get(o,"nodeItemCache");
        for(boolean partial:new boolean[]{false,true}) {
            var s=section(5,false);var source=new ArrayList<>(List.of(s));
            var n=new LodManager.Node(1,0,0);n.gpu=source;n.resident=!partial;
            ((List<LodGpu.GpuSection>)get(o,"resident")).add(s);
            APPEND.invoke(o,source,false,1);check(cache.get(source)!=null,"installed current generation");
            // Real retirement asserts the CPU packet is already gone before any slot/range can retire.
            set(o,"geometryLeases",new LodGeometryLeases(()->check(cache.get(source)==null,"packet removed before geometry retirement"),()->{}));
            LodGpu.cacheNodeItems=false;o.free(n);
            check(n.gpu==null&&cache.size()==0&&s.preparedItems==null,"actual free: OFF/ON and partial-upload cleanup");
            var replacement=section(6,false);replacement.slot=s.slot;
            var next=new ArrayList<>(List.of(replacement));n.gpu=next;
            long builds=count(o,"nodeItemPacketBuilds");reset(o);APPEND.invoke(o,next,true,2);
            check(count(o,"nodeItemPacketBuilds")==builds+1&&cache.get(source)==null,"same slot different generation rebuilds");
            Reference ref=new Reference();ref.append(next,true,2);
            // Owner retains zero-count previous groups, so compare just current section bytes here.
            check(Arrays.equals(Arrays.copyOf((int[])get(o,"secItems"),ref.sections.size()),ref.sections.stream().mapToInt(Integer::intValue).toArray()),"replacement carries new range/bounds");
            set(o,"geometryLeases",new LodGeometryLeases(()->{},()->{}));o.free(n);o.free(n);
        }
        var n=new LodManager.Node(0,0,0);n.gpu=new ArrayList<>();APPEND.invoke(o,n.gpu,false,0);o.free(n);
        check(cache.size()==0&&cache.payloadBytes()==0,"empty-list free also drops packet");
        var remaining=new ArrayList<>(List.of(section(7,false)));APPEND.invoke(o,remaining,false,0);
        CLEAR.invoke(o);CLEAR.invoke(o);
        check(get(o,"nodeItemCache")==null&&cache.size()==0,"failure/close cleanup clears all references");
        LodGpu fresh=owner(1<<20);check(((LodNodeItems.Cache)get(fresh,"nodeItemCache")).get(remaining)==null,"owner reinit cannot inherit packets");
    }
    public static void main(String[] args) throws Exception {
        Field flag=field(LodGpu.class,"cacheNodeItems");
        check(Modifier.isPublic(flag.getModifiers())&&Modifier.isStatic(flag.getModifiers())&&Modifier.isVolatile(flag.getModifiers()),"runtime public volatile toggle");
        check(LodGpu.cacheNodeItems==Boolean.parseBoolean(args[0]),"default/property gating");
        equivalence();budgetAndCopies();lifetime();
        System.out.println("LodNodeItemsTest: "+checks+" checks, 0 failures (levels="+LodGpu.DIAG_LEVELS+")");
    }
}
