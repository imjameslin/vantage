package net.minecraft.client.renderer.chunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.minecraft.client.renderer.chunk.LodFarCamera.Camera;
import net.minecraft.client.renderer.chunk.LodFarCamera.Position;
import net.minecraft.client.renderer.chunk.LodFarCamera.Viewport;
import org.joml.Matrix4f;

/** Bounded CPU fixture: raw UBO/cull bytes, independent plane/clip oracles and native warp ABI. */
public final class LodFarGuardTest {
    private static final int WIDTH = 853, HEIGHT = 479;
    private static final long BUDGET = 64L << 20;
    private static int checks;

    private record Fixture(LodFarGeometry.Source source, byte[][] uniforms, byte[] cull, double[] planes) { }

    public static void main(String[] args) {
        captureAndOwnership();
        selectionAndFloatResidual();
        integerCrops();
        shadingFootprint();
        nativeRotationAndMotionAdmission();
        shaderCameraPrecision();
        rejectionAndBudget();
        System.out.println("LodFarGuardTest: " + checks + " checks passed (bounded CPU only)");
    }

    private static Fixture fixture(int width, int height) {
        var source = source(29_000_000.1234567, 121.7, -28_000_000.375);
        float[] projection = {1.8f * height / width,-0.021f,0,0, 0.03125f,1.8f,0,0,
            0.015625f,-0.0234375f,0,-1, 0,0,0.5f,0};
        float[] view = new Matrix4f().rotationXYZ(-0.17f, 0.31f, 0.09f).get(new float[16]);
        byte[][] uniforms = {new byte[64], new byte[48], new byte[48], new byte[80]};
        for (int j = 0; j < uniforms.length; j++) for (int i = 0; i < uniforms[j].length; i++) {
            uniforms[j][i] = (byte)(31 * j + i);
        }
        floats(uniforms[0], projection);
        floats(uniforms[3], view);
        globals(uniforms[1], source);
        // Culling can use a separate projection: capture must widen THESE raw planes.
        float[] cullProjection = projection.clone(); cullProjection[0] *= 1.03f;
        double[] planes = planes(cullProjection, view);
        byte[] cull = new byte[128];
        for (int i = 0; i < cull.length; i++) cull[i] = (byte)(i * 7);
        ByteBuffer bytes = buffer(cull);
        for (int i = 0; i < planes.length; i++) bytes.putFloat(i * 4, (float)planes[i]);
        bytes.putFloat(80, 0.03125f).putInt(84, 15).putFloat(88, 0.125f);
        cullCamera(cull, source);
        return new Fixture(source, uniforms, cull, planes);
    }

    private static LodFarGeometry.Source source(double x, double y, double z) {
        return new LodFarGeometry.Source(1,2,3,4,5,6,7,8, 28_996_000,96,-28_004_000,
            x,y,z, x + 0.0625,y - 0.125,z + 0.1875);
    }

    private static void globals(byte[] bytes, LodFarGeometry.Source source) {
        double[] camera = {source.cameraX(), source.cameraY(), source.cameraZ()};
        ByteBuffer b = buffer(bytes);
        for (int axis = 0; axis < 3; axis++) {
            int block = (int)Math.floor(camera[axis]);
            b.putInt(axis * 4, block).putFloat(16 + axis * 4, (float)(block - camera[axis]));
        }
    }

    private static void cullCamera(byte[] bytes, LodFarGeometry.Source s) {
        ByteBuffer b = buffer(bytes);
        b.putFloat(96, (float)(s.cameraX() - s.originX())).putFloat(100, (float)(s.cameraY() - s.originY()))
            .putFloat(104, (float)(s.cameraZ() - s.originZ()));
        b.putFloat(112, (float)(s.eyeX() - s.originX())).putFloat(116, (float)(s.eyeY() - s.originY()))
            .putFloat(120, (float)(s.eyeZ() - s.originZ()));
    }

    private static LodFarGuard prepare(Fixture f, int w, int h, long budget) {
        return LodFarGuard.prepare(f.source, f.uniforms, f.cull, w, h, budget);
    }

    private static void captureAndOwnership() {
        Fixture f = fixture(WIDTH, HEIGHT);
        byte[][] before = copy(f.uniforms); byte[] cullBefore = f.cull.clone();
        LodFarGuard result = prepare(f, WIDTH, HEIGHT, BUDGET);
        Viewport guard = result.camera().guardViewport();
        check(guard.equals(new Viewport(1067,599)), "integer ceil padding and aspect");
        check(result.camera().guardOffsetX() == 107 && result.camera().guardOffsetY() == 60, "integer offsets");
        check(result.camera().targetBytes() == 1067L * 599 * 72, "native three-slot/two-layer target bytes");
        byte[][] out = result.uniformBytes(); byte[] cull = result.cullView();
        for (int slot = 1; slot < 4; slot++) check(Arrays.equals(out[slot], before[slot]), "unchanged UBO " + slot);
        ByteBuffer p = buffer(before[0]), gp = buffer(out[0]);
        for (int col = 0; col < 4; col++) for (int row = 0; row < 4; row++) {
            int offset = (col * 4 + row) * 4;
            float expected = p.getFloat(offset);
            if (row < 2) expected = (float)(expected * (row == 0 ? (double)WIDTH / guard.width() : (double)HEIGHT / guard.height()));
            bits(gp.getFloat(offset), expected, "projection row/byte order");
        }
        check(Arrays.equals(Arrays.copyOfRange(cull,64,128), Arrays.copyOfRange(cullBefore,64,128)),
            "eye plane, flags, epsilon, margin, camera/eye deltas and padding byte-exact");
        Camera nativeCamera = result.camera().guardedSource();
        check(nativeCamera.position().equals(new Position(f.source.cameraX(),f.source.cameraY(),f.source.cameraZ())),
            "native exact camera metadata, not optical eye or float reconstruction");
        check(nativeCamera.renderOrigin().equals(new Position(f.source.originX(),f.source.originY(),f.source.originZ())), "render origin");
        check(Arrays.equals(nativeCamera.projection(), read16(out[0])), "native guard projection equals slot 0");
        check(Arrays.equals(nativeCamera.view(), read16(before[3])), "native view equals first 64 Terrain bytes");
        check(Arrays.deepEquals(f.uniforms,before) && Arrays.equals(f.cull,cullBefore), "source arrays unchanged");
        // Independent input/output ownership in BOTH directions, including non-projection slots.
        for (byte[] slot : out) Arrays.fill(slot,(byte)0);
        Arrays.fill(cull,(byte)0);
        for (byte[] slot : f.uniforms) Arrays.fill(slot,(byte)0);
        Arrays.fill(f.cull,(byte)0);
        for (int slot = 1; slot < 4; slot++) check(Arrays.equals(result.uniformBytes()[slot], before[slot]), "owned slot " + slot);
        check(buffer(result.cullView()).getInt(84) == 15, "owned flags");
        check(Arrays.equals(result.camera().guardedSource().projection(),read16(result.uniformBytes()[0])), "owned camera P");
        for (int flags : new int[]{6,7,14,15,31}) {
            Fixture flagsFixture = fixture(WIDTH, HEIGHT); buffer(flagsFixture.cull).putInt(84,flags);
            check(buffer(prepare(flagsFixture,WIDTH,HEIGHT,BUDGET).cullView()).getInt(84) == flags, "preserve flags " + flags);
        }
    }

    private static void selectionAndFloatResidual() {
        Fixture f = fixture(WIDTH, HEIGHT);
        Viewport guard = LodFarGuard.guardViewport(WIDTH, HEIGHT);
        double[] original = f.planes.clone(), selected = LodFarGuard.selectionPlanes(f.planes,WIDTH,HEIGHT);
        check(Arrays.equals(f.planes,original), "selection input owned");
        byte[] actual = prepare(f,WIDTH,HEIGHT,BUDGET).cullView();
        double[] rounded = new double[20];
        for (int i = 0; i < 20; i++) rounded[i] = buffer(f.cull).getFloat(i * 4);
        // This realistic yaw/float upload MUST exercise the adapter, not an exact axis-aligned pair.
        rejects("unnormalized", () -> LodFarCamera.widenFrustumPlanes(rounded,new Viewport(WIDTH,HEIGHT),guard));
        for (int axis = 0; axis < 2; axis++) for (int col = 0; col < 4; col++) {
            double scale = axis == 0 ? (double)WIDTH / guard.width() : (double)HEIGHT / guard.height();
            int l = axis * 8 + col, r = l + 4;
            double centre = (rounded[l] + rounded[r]) / 2, side = (rounded[l] - rounded[r]) / 2;
            bits(buffer(actual).getFloat(l*4),(float)(centre+scale*side),"raw float widening left");
            bits(buffer(actual).getFloat(r*4),(float)(centre-scale*side),"raw float widening right");
            near(selected[l],(original[l]+original[r])/2+scale*(original[l]-original[r])/2,1e-14,"selection widening");
        }
        for (int i = 16; i < 20; i++) near(selected[i],original[i],0,"selection eye unchanged");
        double[] normalized = original.clone();
        for (int plane = 0; plane < 20; plane += 4) {
            double norm = Math.sqrt(normalized[plane]*normalized[plane]+normalized[plane+1]*normalized[plane+1]
                +normalized[plane+2]*normalized[plane+2]);
            for (int i = plane; i < plane + 4; i++) normalized[i] /= norm;
        }
        rejects("unnormalized", () -> LodFarGuard.selectionPlanes(normalized,WIDTH,HEIGHT));
        for (int i = 0; i < 20; i++) buffer(f.cull).putFloat(i*4,(float)normalized[i]);
        rejects("unnormalized", () -> prepare(f,WIDTH,HEIGHT,BUDGET));
        Fixture inconsistent = fixture(WIDTH,HEIGHT);
        buffer(inconsistent.cull).putFloat(0,buffer(inconsistent.cull).getFloat(0)+0.001f);
        rejects("unnormalized", () -> prepare(inconsistent,WIDTH,HEIGHT,BUDGET));
    }

    private static void integerCrops() {
        for (int[] size : new int[][]{{WIDTH,HEIGHT},{800,600},{1921,1081},{1,1},{8,7}}) {
            Fixture f = fixture(size[0],size[1]);
            LodFarGuard guard = prepare(f,size[0],size[1],Long.MAX_VALUE);
            Camera current = LodFarGuard.currentCamera(f.source,f.uniforms,size[0],size[1]);
            Camera source = guard.camera().guardedSource();
            byte[] nativeBytes = LodFarGuard.warpTransform(source,current);
            check(nativeBytes != null && nativeBytes.length == 144,"native crop size");
            ByteBuffer b = buffer(nativeBytes);
            double W = source.viewport().width(), H = source.viewport().height(), w = size[0], h = size[1];
            for (int i = 0; i < 16; i++) {
                float back = i == 0 ? (float)(w/W) : i == 5 ? (float)(h/H) : i == 10 || i == 15 ? 1 : 0;
                float forward = i == 0 ? (float)(W/w) : i == 5 ? (float)(H/h) : i == 10 || i == 15 ? 1 : 0;
                bits(b.getFloat(i*4),back,"exact native crop inverse ratio");
                bits(b.getFloat(64+i*4),forward,"exact native crop forward ratio");
            }
            check(b.getInt(128) == 1,"pure rotation declaration");
            for (int offset = 132; offset < 144; offset += 4) bits(b.getFloat(offset),0,"actual translation");
            double[] matrix = LodFarGuard.sourceToCurrentClip(source,current);
            double sx = guard.camera().guardOffsetX() + w * 0.31, sy = guard.camera().guardOffsetY() + h * 0.67;
            double[] mapped = multiply(matrix,new double[]{2*sx/W-1,1-2*sy/H,0.125,1});
            near((mapped[0]/mapped[3]+1)*w/2,w*0.31,2e-12,"integer crop x");
            near((1-mapped[1]/mapped[3])*h/2,h*0.67,2e-12,"integer crop y");
            near(matrix[0],W/w,0,"exact double crop ratio");
            // No second guard padding when consuming the native source.
            check(source.viewport().equals(guard.camera().guardViewport()),"already-guarded source");
        }
    }

    private static void shadingFootprint() {
        // Our panel crop keeps 2x2 texture derivative/helper lanes aligned;
        // an integer crop shifted by one pixel is valid for copying but not shading.
        for(int[] size:new int[][]{{3024,1964,1},{853,479,0},{800,600,0},{16,16,1}}) {
            var f=fixture(size[0],size[1]);
            var guard=prepare(f,size[0],size[1],Long.MAX_VALUE);
            var current=LodFarGuard.currentCamera(f.source,f.uniforms,size[0],size[1]);
            check(LodFarGuard.canonicalCrop(guard.camera().guardedSource(),current),"copy crop remains admitted");
            check(LodFarGuard.exactShadingCrop(guard.camera().guardedSource(),current,current)==(size[2]==1),"helper-lane phase");
            float[] moved=current.view(); moved[12]+=0.01f;
            check(!LodFarGuard.exactShadingCrop(guard.camera().guardedSource(),current,new Camera(current.projection(),moved,
                current.position(),current.renderOrigin(),current.viewport())),"shading cannot trust moved coverage");
        }
        var f=fixture(800,800);
        buffer(f.uniforms[0]).putInt(0,0x3fc00000);
        var guard=prepare(f,800,800,Long.MAX_VALUE);
        var central=LodFarGuard.currentCamera(f.source,f.uniforms,800,800);
        buffer(f.uniforms[0]).putInt(0,0x3fc00001);
        var changed=LodFarGuard.currentCamera(f.source,f.uniforms,800,800);
        check(LodFarGuard.canonicalCrop(guard.camera().guardedSource(),changed),"distinct central projections collide after guard rounding");
        check(!LodFarGuard.exactShadingCrop(guard.camera().guardedSource(),central,changed),"original projection disambiguates rounded crop");
    }

    private static void nativeRotationAndMotionAdmission() {
        Fixture f = fixture(WIDTH,HEIGHT);
        LodFarGuard captured = prepare(f,WIDTH,HEIGHT,BUDGET);
        Camera source = captured.camera().guardedSource();
        Camera central = LodFarGuard.currentCamera(f.source,f.uniforms,WIDTH,HEIGHT);
        Camera current = new Camera(central.projection(),new Matrix4f().rotationXYZ(-0.12f,0.47f,0.065f).get(new float[16]),
            central.position(),new Position(-1e12,128,1e12),central.viewport());
        byte[] warp = LodFarGuard.warpTransform(source,current);
        check(warp != null,"nontrivial fixed-position rotation, unrelated origins");
        float[] back = read16(warp), forward = read16(Arrays.copyOfRange(warp,64,128));
        for (int i : new int[]{8,9,11}) {
            bits(back[i],0,"inverse structural zero"); bits(forward[i],0,"forward structural zero");
        }
        double[] relative = {4,2,-30,1};
        double[] sourceClip = clip(source,relative), currentClip = clip(current,relative);
        double[] mapped = multiply(asDouble(forward),sourceClip), restored = multiply(asDouble(back),currentClip);
        for (int i = 0; i < 3; i++) {
            near(mapped[i]/mapped[3],currentClip[i]/currentClip[3],1e-6,"rotation shader clip oracle");
            near(restored[i]/restored[3],sourceClip[i]/sourceClip[3],1e-6,"inverse shader clip oracle");
        }
        for (int axis = 0; axis < 3; axis++) {
            double[] xyz = {central.position().x(),central.position().y(),central.position().z()};
            xyz[axis] = Math.nextUp(xyz[axis]); // Even sub-float movement must decline.
            Camera moved = new Camera(current.projection(),current.view(),new Position(xyz[0],xyz[1],xyz[2]),
                current.renderOrigin(),current.viewport());
            check(LodFarGuard.warpTransform(source,moved) == null,"exact-position motion decline axis " + axis);
        }
        float[] changedView = current.view(); changedView[12] = 1e-20f;
        check(LodFarGuard.warpTransform(source,new Camera(current.projection(),changedView,current.position(),
            current.renderOrigin(),current.viewport())) == null,"do not zero a real view translation");
        float[] changedP = current.projection(); changedP[12] = 1e-20f;
        check(LodFarGuard.warpTransform(source,new Camera(changedP,current.view(),current.position(),
            current.renderOrigin(),current.viewport())) == null,"do not zero a real projection optical offset");
        changedP = central.projection(); changedP[8] += 0.00001f;
        Camera jitter = new Camera(changedP,central.view(),central.position(),central.renderOrigin(),central.viewport());
        byte[] jitterWarp = LodFarGuard.warpTransform(source,jitter);
        check(jitterWarp != null && buffer(jitterWarp).getFloat(48) != 0,"changed jitter must not snap to canonical crop");
        float[] invalid = current.projection(); invalid[11] = 1;
        Camera invalidCamera = new Camera(invalid,current.view(),new Position(0,0,0),current.renderOrigin(),current.viewport());
        rejects("UNSUPPORTED_PROJECTION", () -> LodFarGuard.warpTransform(source,invalidCamera));
    }

    private static void shaderCameraPrecision() {
        Fixture f = fixture(WIDTH,HEIGHT);
        // 121.7's double fraction is not the float shader offset: round-trip equality is too strict.
        float offset = buffer(f.uniforms[1]).getFloat(20);
        check(f.source.cameraY() != buffer(f.uniforms[1]).getInt(4) - (double)offset,"fixture needs shader rounding");
        check(prepare(f,WIDTH,HEIGHT,BUDGET) != null,"accept actual float encoding");
        for (int axis = 0; axis < 3; axis++) {
            int a = axis;
            Fixture badBlock = fixture(WIDTH,HEIGHT);
            ByteBuffer block = buffer(badBlock.uniforms[1]); block.putInt(a*4,block.getInt(a*4)+1);
            rejects("Globals camera mismatch", () -> prepare(badBlock,WIDTH,HEIGHT,BUDGET));
            Fixture badOffset = fixture(WIDTH,HEIGHT);
            ByteBuffer off = buffer(badOffset.uniforms[1]); off.putFloat(16+a*4,Math.nextUp(off.getFloat(16+a*4)));
            rejects("Globals camera mismatch", () -> prepare(badOffset,WIDTH,HEIGHT,BUDGET));
            rejects("Globals camera mismatch", () -> LodFarGuard.currentCamera(badOffset.source,badOffset.uniforms,WIDTH,HEIGHT));
            for (int base : new int[]{96,112}) {
                Fixture delta = fixture(WIDTH,HEIGHT);
                buffer(delta.cull).putFloat(base+a*4,Math.nextUp(buffer(delta.cull).getFloat(base+a*4)));
                rejects("Cull camera/eye mismatch", () -> prepare(delta,WIDTH,HEIGHT,BUDGET));
            }
        }
        // Different exact doubles that the shader cannot distinguish remain admissible.
        var roundedSame = source(f.source.cameraX(),Math.nextUp(f.source.cameraY()),f.source.cameraZ());
        check(LodFarGuard.currentCamera(roundedSame,f.uniforms,WIDTH,HEIGHT).position().y() == roundedSame.cameraY(),
            "retain exact Source.camera while accepting identical shader offset");
        for (double y : new double[]{0,-0.0,-0.0000000001,-1,-1.9999999999,1.9999999999}) {
            var s = source(f.source.cameraX(),y,f.source.cameraZ()); byte[][] u = copy(f.uniforms); globals(u[1],s);
            check(LodFarGuard.currentCamera(s,u,WIDTH,HEIGHT).position().y() == y,"signed/floor boundary " + y);
        }
        var nonfinite = source(Double.NaN,0,0);
        rejects("Invalid Source.camera", () -> LodFarGuard.currentCamera(nonfinite,f.uniforms,WIDTH,HEIGHT));
    }

    private static void rejectionAndBudget() {
        Fixture f = fixture(WIDTH,HEIGHT);
        long exactBudget = 1067L * 599 * 72;
        check(prepare(f,WIDTH,HEIGHT,exactBudget).camera().targetBytes() == exactBudget,"exact padded budget");
        rejects("TARGET_BUDGET", () -> prepare(f,WIDTH,HEIGHT,exactBudget-1));
        rejects("TARGET_BUDGET", () -> prepare(f,WIDTH,HEIGHT,(long)WIDTH*HEIGHT*72));
        rejects("INVALID_LIMIT", () -> prepare(f,WIDTH,HEIGHT,0));
        rejects("Positive", () -> LodFarGuard.guardViewport(0,HEIGHT));
        rejects("16384", () -> LodFarGuard.guardViewport(Integer.MAX_VALUE,HEIGHT));
        rejects("16384", () -> LodFarGuard.guardViewport(16384,1));
        check(LodFarGuard.guardViewport(13106,1).width() == 16384,"maximum padded width");
        rejects("16384", () -> LodFarGuard.guardViewport(13107,1));
        rejects("128", () -> LodFarGuard.prepare(f.source,f.uniforms,new byte[127],WIDTH,HEIGHT,BUDGET));
        rejects("Source", () -> LodFarGuard.prepare(null,f.uniforms,f.cull,WIDTH,HEIGHT,BUDGET));
        for (int i = 0; i < 4; i++) {
            byte[][] shortSlot = copy(f.uniforms); shortSlot[i] = new byte[shortSlot[i].length-1];
            rejects("layout", () -> LodFarGuard.prepare(f.source,shortSlot,f.cull,WIDTH,HEIGHT,BUDGET));
            byte[][] missing = copy(f.uniforms); missing[i] = null;
            rejects("layout", () -> LodFarGuard.prepare(f.source,missing,f.cull,WIDTH,HEIGHT,BUDGET));
        }
        Fixture invalidP = fixture(WIDTH,HEIGHT); buffer(invalidP.uniforms[0]).putFloat(11*4,1);
        rejects("UNSUPPORTED_PROJECTION", () -> prepare(invalidP,WIDTH,HEIGHT,BUDGET));
        Fixture singular = fixture(WIDTH,HEIGHT); Arrays.fill(singular.uniforms[0],(byte)0);
        rejects("SINGULAR_MATRIX", () -> prepare(singular,WIDTH,HEIGHT,BUDGET));
        Fixture nan = fixture(WIDTH,HEIGHT); buffer(nan.uniforms[0]).putFloat(0,Float.NaN);
        rejects("NON_FINITE_MATRIX", () -> prepare(nan,WIDTH,HEIGHT,BUDGET));
        Fixture badView = fixture(WIDTH,HEIGHT); buffer(badView.uniforms[3]).putFloat(3*4,0.125f);
        rejects("UNSUPPORTED_VIEW", () -> prepare(badView,WIDTH,HEIGHT,BUDGET));
        Fixture badPlanes = fixture(WIDTH,HEIGHT); buffer(badPlanes.cull).putFloat(0,Float.POSITIVE_INFINITY);
        rejects("Non-finite", () -> prepare(badPlanes,WIDTH,HEIGHT,BUDGET));
    }

    private static double[] planes(float[] p, float[] v) {
        double[] product = new double[16], planes = new double[20];
        for (int col = 0; col < 4; col++) for (int row = 0; row < 4; row++) for (int k = 0; k < 4; k++) {
            product[col*4+row] += (double)p[k*4+row]*v[col*4+k];
        }
        for (int col = 0; col < 4; col++) {
            planes[col] = product[col*4+3]+product[col*4];
            planes[4+col] = product[col*4+3]-product[col*4];
            planes[8+col] = product[col*4+3]+product[col*4+1];
            planes[12+col] = product[col*4+3]-product[col*4+1];
            planes[16+col] = product[col*4+3];
        }
        return planes;
    }

    private static double[] clip(Camera camera, double[] relative) {
        double[] result = multiply(asDouble(camera.projection()),multiply(asDouble(camera.view()),relative));
        result[1] = -result[1]; return result;
    }

    private static double[] multiply(double[] matrix, double[] point) {
        double[] result = new double[4];
        for (int row = 0; row < 4; row++) for (int col = 0; col < 4; col++) result[row] += matrix[col*4+row]*point[col];
        return result;
    }

    private static double[] asDouble(float[] values) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) result[i] = values[i];
        return result;
    }

    private static byte[][] copy(byte[][] bytes) { return Arrays.stream(bytes).map(byte[]::clone).toArray(byte[][]::new); }
    private static ByteBuffer buffer(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()); }
    private static void floats(byte[] bytes, float[] values) { buffer(bytes).asFloatBuffer().put(values); }
    private static float[] read16(byte[] bytes) { float[] out = new float[16]; buffer(bytes).asFloatBuffer().get(out); return out; }
    private static void bits(float actual, float expected, String label) {
        check(Float.floatToRawIntBits(actual) == Float.floatToRawIntBits(expected),label + ": " + actual + " != " + expected);
    }
    private static void near(double actual, double expected, double tolerance, String label) {
        check(Double.isFinite(actual) && Math.abs(actual-expected) <= tolerance,label + ": " + actual + " != " + expected);
    }
    private static void check(boolean condition, String detail) { checks++; if (!condition) throw new AssertionError(detail); }
    private static void rejects(String message, Runnable call) {
        try { call.run(); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains(message),expected.getMessage()); return; }
        throw new AssertionError("Expected rejection containing " + message);
    }
}
