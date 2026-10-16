package bench;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Read-only admission for a deliberately slow, fully populated synchronous image reference.
 * It neither loads chunks nor compiles/uploads anything. Candidate renders never call this.
 */
final class LodReferenceReadiness {
    record State(int visible, int loadedNonAir, int uncompiled, int uploadPending, int fading, boolean queueEmpty,
                 int compiledEmpty, java.util.List<String> missing, boolean graphReady, int replacements, int dirty) {
        boolean ready() { return graphReady && queueEmpty && uncompiled == 0 && uploadPending == 0 && fading == 0 && replacements == 0 && dirty == 0; }
    }
    private static Field visibleSections;
    private static Method emptyReady;

    static State read(Minecraft mc) throws ReflectiveOperationException {
        if (visibleSections == null) {
            visibleSections = mc.levelRenderer.getClass().getDeclaredField("visibleSections");
            visibleSections.setAccessible(true);
        }
        Collection<?> visible = (Collection<?>)visibleSections.get(mc.levelRenderer);
        if(emptyReady==null)emptyReady=SectionRenderDispatcher.RenderSection.class.getMethod("handoffEmptyReady");
        int nonAir=0, uncompiled=0, upload=0, fading=0, compiledEmpty=0, replacements=0, dirty=0;
        var missing=new ArrayList<String>();
        var tracker=(SectionUpdateTracker)get(mc.levelExtractor,"sectionUpdateTracker");
        if(tracker==null)throw new IllegalStateException("reference has no near update tracker");
        long now=Util.getMillis(), fadeDuration=Util.toMillis(mc.options.chunkSectionFadeInTime().get());
        var dispatcher=mc.levelRenderer.sectionRenderDispatcher();
        if(dispatcher==null)throw new IllegalStateException("reference has no near dispatcher");
        dispatcher.lock();
        try {
        for (Object item : visible) {
            var section=(SectionRenderDispatcher.RenderSection)item;
            var origin=section.getRenderOrigin();
            var chunk=mc.level.getChunk(origin.getX()>>4, origin.getZ()>>4, ChunkStatus.FULL, false);
            if (chunk == null) continue; // Outside loaded near terrain; synchronous LOD owns it.
            int sy=mc.level.getSectionIndex(origin.getY());
            if (sy < 0 || sy >= chunk.getSectionsCount() || chunk.getSection(sy).hasOnlyAir()) continue;
            nonAir++;
            var dirtyState=tracker.getDirtyState(section.getSectionNode());
            if(dirtyState!=null && dirtyState.isDirty())dirty++;
            Object task=get(section,"lastCompileTask");
            // compileSync does not set isCompleted. Publication sequence is the proof for
            // both sync and async compiles; subsequent GPU uploads are checked below.
            if(task!=null && !(boolean)((AtomicBoolean)get(task,"isCancelled")).get()
                    && ((Number)get(task,"meshSeq")).longValue()>((Number)get(section,"appliedMeshSeq")).longValue())replacements++;
            var mesh=section.getSectionMesh();
            // Non-air can legitimately compile to zero faces (e.g. surrounded solid sections).
            // Accept only the current-generation completed compile, never the graph's EMPTY CAS.
            if(mesh==CompiledSectionMesh.EMPTY && Boolean.TRUE.equals(emptyReady.invoke(section))) {
                compiledEmpty++; continue;
            }
            if (mesh == CompiledSectionMesh.UNCOMPILED || mesh == CompiledSectionMesh.EMPTY) {
                uncompiled++;
                if(missing.size()<12)missing.add(origin.toShortString()+":"+(mesh==CompiledSectionMesh.EMPTY?"unproven-empty":"uncompiled"));
                continue;
            }
            if (!(mesh instanceof CompiledSectionMesh compiled))
                throw new IllegalStateException("reference readiness has no proof for generic near mesh");
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                var draw=compiled.getSectionDraw(layer);
                if (draw != null && (!compiled.isVertexBufferUploaded(layer)
                        || draw.hasCustomIndexBuffer() && !compiled.isIndexBufferUploaded(layer))) upload++;
            }
            if (section.getVisibility(now,fadeDuration) < 1.0F) fading++;
        }
        } finally { dispatcher.unlock(); }
        return new State(visible.size(),nonAir,uncompiled,upload,fading,mc.levelRenderer.hasRenderedAllSections(),compiledEmpty,missing,
            graphReady(mc),replacements,dirty);
    }

    private static boolean graphReady(Minecraft mc) throws ReflectiveOperationException {
        Object graph=mc.levelRenderer.sectionOcclusionGraph();
        Future<?> full=(Future<?>)get(graph,"fullUpdateTask");
        if((boolean)get(graph,"needsFullUpdate") || full!=null && !full.isDone()
                || ((AtomicBoolean)get(graph,"needsFrustumUpdate")).get())return false;
        if(full!=null)try{full.get();}catch(Exception e){throw new IllegalStateException("reference visibility discovery failed",e);}
        Object current=((AtomicReference<?>)get(graph,"currentGraph")).get();
        Queue<?> next=(Queue<?>)get(graph,"nextSectionsToPropagateFrom");
        return current!=null && (next==null || next.isEmpty()) && ((Queue<?>)get(current,"sectionsToPropagateFrom")).isEmpty();
    }

    private static final ClassValue<java.util.Map<String,Field>> FIELDS=new ClassValue<>() {
        protected java.util.Map<String,Field> computeValue(Class<?> type){return new java.util.HashMap<>();}
    };
    private static Object get(Object owner,String name) throws ReflectiveOperationException {
        var fields=FIELDS.get(owner.getClass()); Field field=fields.get(name);
        if(field==null){
            for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())try{
                field=type.getDeclaredField(name);field.setAccessible(true);fields.put(name,field);break;
            }catch(NoSuchFieldException ignored){}
            if(field==null)throw new NoSuchFieldException(name);
        }
        return field.get(owner);
    }
}
