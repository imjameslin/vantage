import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

// Compile alongside the ACTUAL tools/PerceptualDiff.java, never a copied metric.
// Writes raw sRGB RGBA8 fixtures to avoid PNG decoder/color-profile differences.
class CachedQualityReferenceTest {
    static final List<String> cases = new ArrayList<>();
    static byte[] solid(int w, int h, int value) {
        byte[] out = new byte[w*h*4];
        for (int i=0;i<w*h;i++) { Arrays.fill(out,4*i,4*i+3,(byte)value); out[4*i+3]=(byte)255; }
        return out;
    }
    static void white(byte[] a, int i) { Arrays.fill(a,4*i,4*i+3,(byte)255); }
    static float[][] planes(byte[] a) {
        float[][] out = new float[3][a.length/4];
        for (int i=0;i<a.length/4;i++) for (int ch=0;ch<3;ch++) out[ch][i]=a[4*i+ch]&255;
        return out;
    }
    static void emit(Path dir, String name, int w, int h, byte[] a, byte[] b) throws Exception {
        Files.write(dir.resolve(name+"-ref.rgba"),a); Files.write(dir.resolve(name+"-cand.rgba"),b);
        // Fixed panel bar for tiny fixtures: only compare() scales regionMax.
        var m=PerceptualDiff.metrics(planes(a),planes(b),w,h,256,true);
        cases.add(String.format(Locale.ROOT,
            "{\"name\":\"%s\",\"width\":%d,\"height\":%d,\"mean\":%.17g,\"p999\":%.17g,\"ssim\":%.17g,\"region\":%d,\"meanOk\":%s,\"p999Ok\":%s,\"ssimOk\":%s,\"regionOk\":%s,\"ok\":%s}",
            name,w,h,m.mean(),m.p999(),m.ssim(),m.region(),m.meanOk(),m.p999Ok(),m.ssimOk(),m.regionOk(),m.ok()));
    }
    public static void main(String[] args) throws Exception {
        Path dir=Path.of(args[0]); Files.createDirectories(dir);
        for (int[] size : new int[][]{{1,1},{1,17},{17,1},{67,43}}) {
            int w=size[0],h=size[1]; byte[] a=solid(w,h,127),b=a.clone();
            for (int i=0;i<w*h;i++) b[4*i+3]=0;
            emit(dir,"alpha-"+w+"x"+h,w,h,a,b);
            b[0]=(byte)240; emit(dir,"edge-"+w+"x"+h,w,h,a,b);
        }
        emit(dir,"black-white",67,43,solid(67,43,0),solid(67,43,255));
        emit(dir,"flat-one-code",67,43,solid(67,43,127),solid(67,43,128));
        for (int changed : new int[]{1,2}) {
            byte[] a=solid(40,25,0), b=a.clone();
            for (int i=0;i<changed;i++) white(b,i);
            emit(dir,"rank-"+changed,40,25,a,b);
        }
        for (int changed : new int[]{255,256}) {
            byte[] a=solid(300,300,0),b=a.clone();
            for (int i=0;i<changed;i++) white(b,i*300+i);
            emit(dir,"diagonal-"+changed,300,300,a,b);
        }
        byte[] edge=solid(67,43,0), b=edge.clone(); white(b,66); white(b,67);
        emit(dir,"no-row-wrap",67,43,edge,b);
        Random rng=new Random(7654321);
        for (int mode=0;mode<3;mode++) {
            byte[] a=solid(67,43,0), c=a.clone();
            for (int i=0;i<67*43;i++) for (int ch=0;ch<3;ch++) {
                int v=rng.nextInt(256); a[4*i+ch]=(byte)v;
                c[4*i+ch]=(byte)(mode==0 ? v : mode==1 ? Math.min(255,v+rng.nextInt(3)) : rng.nextInt(256));
            }
            emit(dir,"random-"+mode,67,43,a,c);
        }
        Files.writeString(dir.resolve("expected.json"),"[\n"+String.join(",\n",cases)+"\n]\n");
        System.out.println(cases.size()+" synthetic pairs evaluated by actual PerceptualDiff (CPU only)");
    }
}
