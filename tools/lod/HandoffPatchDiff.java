import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Numerical attribution of the four known handoff patches, using unmodified native PNGs.
 * Compile with tools/PerceptualDiff.java. This is NOT an image or motion gate: regions are
 * selected from an earlier failing run, and settled references do not match animation clocks.
 */
public final class HandoffPatchDiff {
    private static final int[][] BOXES = {{1282,880,34,19}, {1231,881,57,20}, {1180,882,36,20}, {1128,883,36,22}};

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("settled-reference-dir off-dir on-dir");
        System.out.println("Diagnostic static-tree ROIs only; native pixels, 8px padding. NOT a quality gate.");
        System.out.println("frame  OFF mean-dE region>10  ON mean-dE region>10  pixels closer ON/OFF/tied");
        for (int frame = 28; frame <= 31; frame++) {
            int[] box = BOXES[frame - 28];
            int x = box[0]-8, y = box[1]-8, w = box[2]+16, h = box[3]+16;
            float[][][] rgb = new float[3][][];
            for (int arm = 0; arm < 3; arm++) {
                var image = ImageIO.read(Path.of(args[arm], "frame-%03d.png".formatted(frame)).toFile());
                if (image.getWidth() != 3024 || image.getHeight() != 1964) throw new IllegalArgumentException("native size required");
                rgb[arm] = PerceptualDiff.resample(image.getSubimage(x,y,w,h),w,h);
            }
            float[] off = PerceptualDiff.deltaE(rgb[0],rgb[1],w*h), on = PerceptualDiff.deltaE(rgb[0],rgb[2],w*h);
            double offMean=0,onMean=0; int better=0,worse=0,tied=0;
            for(int i=0;i<off.length;i++) {
                offMean+=off[i];onMean+=on[i];
                if(on[i]+1e-6<off[i])better++;else if(off[i]+1e-6<on[i])worse++;else tied++;
            }
            int offRegion=PerceptualDiff.largestRegion(off,w,h,new int[4]);
            int onRegion=PerceptualDiff.largestRegion(on,w,h,new int[4]);
            System.out.printf(Locale.ROOT,"%03d    %.3f %d    %.3f %d    %d/%d/%d%n",frame,
                offMean/off.length,offRegion,onMean/on.length,onRegion,better,worse,tied);
        }
    }
}
