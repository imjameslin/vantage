import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
/** Screenshot-free tail attribution: java tools/lod/FlyingTailJfr.java <recording.jfr>. */
public class FlyingTailJfr {
 record Sample(Instant at,long thread,List<String> stack){}
 public static void main(String[] args)throws Exception{
  List<RecordedEvent> slow=new ArrayList<>(),jobs=new ArrayList<>();List<Sample> samples=new ArrayList<>();
  try(var f=new RecordingFile(Path.of(args[0]))){while(f.hasMoreEvents()){
   var e=f.readEvent();String n=e.getEventType().getName();
   if(n.equals("bench.SlowFrame"))slow.add(e);
   if(n.equals("minecraft.LodPrepare"))jobs.add(e);
   if(n.equals("jdk.ExecutionSample")||n.equals("jdk.NativeMethodSample")){
    var t=e.getThread("sampledThread");var s=e.getStackTrace();
    if(t!=null&&s!=null)samples.add(new Sample(e.getStartTime(),t.getJavaThreadId(),s.getFrames().stream().map(fr->fr.getMethod().getType().getName()+"."+fr.getMethod().getName()).toList()));
   }
  }}
  slow.sort(Comparator.comparing(RecordedEvent::getStartTime));long total=0,packTotal=0;int packMajor=0;
  Map<String,Integer> all=new TreeMap<>(), uploadTop=new HashMap<>();
  for(var e:slow){Instant end=e.getStartTime(),start=end.minusNanos(e.getLong("interval"));long ns=e.getLong("interval"),pack=0;Map<String,Integer> by=new TreeMap<>();
   for(var j:jobs){if(j.getThread().getJavaThreadId()!=e.getThread().getJavaThreadId())continue;Instant a=j.getStartTime().isAfter(start)?j.getStartTime():start,b=j.getEndTime().isBefore(end)?j.getEndTime():end;if(b.isAfter(a))pack+=Duration.between(a,b).toNanos();}
   for(var s:samples)if(s.thread==e.getThread().getJavaThreadId()&&!s.at.isBefore(start)&&!s.at.isAfter(end)){String cat=category(s.stack);by.merge(cat,1,Integer::sum);all.merge(cat,1,Integer::sum);if(cat.equals("LodGpu.upload"))uploadTop.merge(String.join(" <- ",s.stack.subList(0,Math.min(6,s.stack.size()))),1,Integer::sum);}
   total+=ns;packTotal+=pack;if(pack>ns/2)packMajor++;
   System.out.printf(Locale.ROOT,"row=%d interval=%.2fms synchronousPackOverlap=%.2fms tick=%.2f submit=%.2f wait=%.2f samples=%s%n",e.getInt("row"),ns/1e6,pack/1e6,e.getLong("tickBundle")/1e6,e.getLong("nativeSubmit")/1e6,e.getLong("timelineWait")/1e6,by);
  }
  System.out.printf(Locale.ROOT,"SUMMARY slowFrames=%d totalSlowMs=%.2f syncPackOverlapMs=%.2f packMajorityFrames=%d samples=%s%n",slow.size(),total/1e6,packTotal/1e6,packMajor,all);
  System.out.println("UPLOAD TOP "+uploadTop.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(10).toList());
  System.out.println("Intervals end at the next onFrame event; clipped packing overlap is wall time. Sampling is supporting attribution, not a duration measurement or FPS result.");
 }
 static String category(List<String>s){
  for(String p:List.of("LodPreparedItems.","LodGpu.upload", "LodGpu.captureItems","LodGpu.installItems","LodGpu.updateFades","LodManager.","MetalTimeline."))for(String x:s)if(x.contains(p))return p;
  return s.isEmpty()?"unknown":s.getFirst();
 }
}
