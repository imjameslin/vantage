package net.minecraft.client.renderer.chunk;

import java.util.Arrays;
import org.joml.Matrix4f;
import net.minecraft.client.renderer.chunk.LodFarCamera.Camera;
import net.minecraft.client.renderer.chunk.LodFarCamera.Pixel;
import net.minecraft.client.renderer.chunk.LodFarCamera.PixelReject;
import net.minecraft.client.renderer.chunk.LodFarCamera.Position;
import net.minecraft.client.renderer.chunk.LodFarCamera.Reject;
import net.minecraft.client.renderer.chunk.LodFarCamera.Result;
import net.minecraft.client.renderer.chunk.LodFarCamera.Viewport;

/** Independent world-point/shader oracle; only JDK + JOML, no client or native classes. */
public final class LodFarCameraTest {
    private static final Position CAMERA = new Position(29_000_000.125, 121.75, -28_000_000.375);
    private static final Position ORIGIN = new Position(28_996_000, 0, -28_004_000);
    private static final Viewport VIEW = new Viewport(800, 600);
    private static int checks;

    public static void main(String[] args) {
        identityAndOwnership();
        analyticTranslation();
        pureRotationAndCombinedMotion();
        guardPixelsJitterAndResize();
        widenedPlanesAndFocalLength();
        standalonePlaneWidening();
        reversedDepthAndPixelRejects();
        invalidInputs();
        System.out.println("LodFarCameraTest: " + checks + " checks passed (CPU only; no gate/FPS result)");
    }

    private static float[] identity() {
        return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    }

    /** Explicit infinite reversed-Z: raw clip = (fx*x, fy*y, near, -z). */
    private static float[] projection(float fx, float fy) {
        return new float[]{fx,0,0,0, 0,fy,0,0, 0,0,0,-1, 0,0,0.5f,0};
    }

    private static Camera camera(float[] p, float[] v, Position c, Position o, Viewport size) {
        return new Camera(p, v, c, o, size);
    }

    private static Camera base() { return camera(projection(1, 1), identity(), CAMERA, ORIGIN, VIEW); }

    private static Position offset(Position p, double x, double y, double z) {
        return new Position(p.x() + x, p.y() + y, p.z() + z);
    }

    private static LodFarCamera prepare(Camera source, Camera current, int x, int y) {
        Result result = LodFarCamera.prepare(source, current, x, y, 16384, 1L << 30);
        check(result.valid() && result.camera() != null, "prepare: " + result.rejection() + " " + result.detail());
        return result.camera();
    }

    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }

    private static void near(double actual, double expected, double tolerance, String detail) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
            detail + ": expected " + expected + ", got " + actual);
    }

    private static void exactIdentity(double[] matrix) {
        for (int i = 0; i < 16; i++) near(matrix[i], i % 5 == 0 ? 1 : 0, 0, "exact identity " + i);
    }

    private static void identityAndOwnership() {
        float[] p = projection(1, 1), v = identity();
        Camera s = camera(p, v, CAMERA, ORIGIN, VIEW);
        // Even a wholly different cull-origin basis must not translate the optical camera.
        Camera c = camera(p, v, CAMERA, new Position(-1e12, 1024, 1e12), VIEW);
        p[0] = 99; v[0] = 99;
        s.projection()[0] = 99; s.view()[0] = 99;
        LodFarCamera math = prepare(s, c, 0, 0);
        exactIdentity(math.sourceToCurrentClip());
        exactIdentity(math.currentToSourceClip());
        near(math.source().projection()[0], 1, 0, "owned input matrix");
        check(math.source().position().equals(CAMERA) && math.source().renderOrigin().equals(ORIGIN), "exact metadata");
        math.guardProjection()[0] = 42;
        math.sourceToCurrentClip()[0] = 42;
        math.currentToSourceClip()[0] = 42;
        math.guardFrustumPlanes()[0] = 42;
        exactIdentity(math.sourceToCurrentClip());
        exactIdentity(math.currentToSourceClip());
        near(math.guardProjection()[0], 1, 0, "owned guard matrix");
        near(math.guardFrustumPlanes()[0], 1, 0, "owned planes");
        double[] point = {CAMERA.x() + 4, CAMERA.y() + 2, CAMERA.z() - 16};
        verifyPoint(math, point);
        Pixel pixel = math.sourcePixelToCurrent(500.5, 200.5, 0.03125);
        pixel(pixel, 500.5, 200.5, 0.03125, VIEW, 1e-12);
        // A non-identity rotation's rounded float coefficients must still give an exact identity warp.
        float[] rotated = new Matrix4f().rotationXYZ(0.17f, -0.23f, 0.09f).get(new float[16]);
        s = camera(projection(1.25f, 1.75f), rotated, CAMERA, ORIGIN, VIEW);
        exactIdentity(prepare(s, s, 0, 0).sourceToCurrentClip());
    }

    private static void analyticTranslation() {
        Camera s = base();
        Position cpos = offset(CAMERA, 0.125, -0.5, -2);
        Camera c = camera(s.projection(), s.view(), cpos, offset(ORIGIN, 8192, 4096, -8192), VIEW);
        LodFarCamera math = prepare(s, c, 0, 0);
        // P*T(Cs-Cc)*P^-1 including both Metal Y flips: x += -0.25*z,
        // y += -1*z, w += -4*z. A float absolute camera would lose the 0.125 move.
        double[] expected = {1,0,0,0, 0,1,0,0, -0.25,-1,1,-4, 0,0,0,1};
        double[] actual = math.sourceToCurrentClip();
        for (int i = 0; i < 16; i++) near(actual[i], expected[i], 1e-14, "analytic translation matrix " + i);
        double[] world = {CAMERA.x() + 4, CAMERA.y() + 2, CAMERA.z() - 16};
        verifyPoint(math, world);
        pixel(math.sourcePixelToCurrent(500, 337.5, 0.5 / 16),
            (1 + 3.875 / 14) * 400, (1 + 2.5 / 14) * 300, 0.5 / 14, VIEW, 1e-10);
        for (double[] delta : new double[][]{{0.125,0,0}, {0,0.125,0}, {0,0,-0.125}}) {
            Camera axis = camera(s.projection(), s.view(), offset(CAMERA, delta[0], delta[1], delta[2]), ORIGIN, VIEW);
            verifyPoint(prepare(s, axis, 0, 0), world);
        }
    }

    private static void pureRotationAndCombinedMotion() {
        Camera s = camera(projection(0.25f, 1), identity(), CAMERA, ORIGIN, VIEW);
        // Exact 90-degree yaw: (x,y,z) -> (z,y,-x).
        float[] yaw = {0,0,-1,0, 0,1,0,0, 1,0,0,0, 0,0,0,1};
        Camera c = camera(s.projection(), yaw, CAMERA, ORIGIN, VIEW);
        LodFarCamera math = prepare(s, c, 0, 0);
        verifyPoint(math, new double[]{CAMERA.x() + 32, CAMERA.y() + 4, CAMERA.z() - 64});
        pixel(math.sourcePixelToCurrent(450, 318.75, 0.5 / 64), 200, 337.5, 0.5 / 32, VIEW, 1e-10);

        float[] vs = new Matrix4f().rotationXYZ(0.09f, -0.11f, 0.07f).get(new float[16]);
        float[] vc = new Matrix4f().rotationXYZ(-0.07f, 0.13f, -0.05f).get(new float[16]);
        float[] ps = projection(1.25f, 1.5f), pc = projection(1.125f, 1.75f);
        ps[8] = 0.015625f; pc[9] = -0.0078125f;
        s = camera(ps, vs, CAMERA, ORIGIN, VIEW);
        c = camera(pc, vc, offset(CAMERA, 0.5, -0.25, -1), offset(ORIGIN, -4000, 5, 4096), new Viewport(1024, 768));
        math = prepare(s, c, 80, 60);
        for (int z : new int[]{16, 32, 128}) for (int x : new int[]{-2, 0, 3}) for (int y : new int[]{-1, 2}) {
            verifyPoint(math, new double[]{CAMERA.x() + x, CAMERA.y() + y, CAMERA.z() - z});
        }
    }

    private static void guardPixelsJitterAndResize() {
        float[] p = projection(1.25f, 1.75f);
        p[1] = -0.0625f; p[4] = 0.125f; // Non-diagonal X/Y coefficients must scale too.
        p[8] = 0.0078125f; p[9] = -0.015625f; // Off-axis projection jitter.
        p[12] = 0.0625f; p[13] = -0.03125f; // Optical-centre offsets already encoded by P.
        Camera s = camera(p, identity(), CAMERA, ORIGIN, new Viewport(801, 603));
        Camera c = camera(projection(1, 1.5f), identity(), CAMERA, ORIGIN, new Viewport(1231, 719));
        LodFarCamera math = prepare(s, c, 101, 73);
        check(math.guardViewport().equals(new Viewport(1003, 749)), "odd full guard dimensions");
        check(math.guardOffsetX() == 101 && math.guardOffsetY() == 73, "integer central offsets");
        check(math.sourceViewport().equals(s.viewport()) && math.currentViewport().equals(c.viewport()), "both viewports");
        check(math.targetBytes() == 1003L * 749 * 72, "three-slot target bytes");
        float[] guard = math.guardProjection();
        for (int col = 0; col < 4; col++) for (int row = 0; row < 4; row++) {
            int i = col * 4 + row;
            float expected = row == 0 ? (float)(p[i] * 801.0 / 1003) : row == 1 ? (float)(p[i] * 603.0 / 749) : p[i];
            check(Float.floatToIntBits(guard[i]) == Float.floatToIntBits(expected), "guard row coefficient " + i);
        }
        for (int x : new int[]{-4, 0, 5}) {
            double[] world = {CAMERA.x() + x, CAMERA.y() + 3, CAMERA.z() - 32};
            double[] old = screen(shaderClip(s, world), s.viewport());
            double[] enlarged = screen(shaderClip(math.guardedSource(), world), math.guardViewport());
            near(enlarged[0] - old[0], 101, 2e-5, "guard X pixel offset including jitter");
            near(enlarged[1] - old[1], 73, 2e-5, "guard Y pixel offset including jitter");
            near(enlarged[2], old[2], 0, "guard depth unchanged");
            verifyPoint(math, world);
        }
        near(math.guardViewport().height() * guard[5] / 2.0, 603.0 * p[5] / 2, 1e-4, "central focal length k");
        check(math.guardedSource().position().equals(CAMERA) && math.guardedSource().renderOrigin().equals(ORIGIN), "guard native metadata");
    }

    private static void widenedPlanesAndFocalLength() {
        Camera s = base();
        LodFarCamera math = prepare(s, s, 200, 150);
        double scale = math.guardProjection()[0];
        double[] expected = {scale,0,-1,0, -scale,0,-1,0, 0,scale,-1,0, 0,-scale,-1,0, 0,0,-1,0};
        double[] planes = math.guardFrustumPlanes();
        for (int i = 0; i < 20; i++) near(planes[i], expected[i], 0, "five-plane order " + i);
        double[] central = prepare(s, s, 0, 0).guardFrustumPlanes();
        for (double[] q : new double[][]{{12,0,-10}, {-12,0,-10}, {0,12,-10}, {0,-12,-10}}) {
            check(!inside(central, q) && inside(planes, q), "guard admits newly exposed side");
        }
        for (double[] q : new double[][]{{16,0,-10}, {-16,0,-10}, {0,16,-10}, {0,-16,-10}, {0,0,1}}) {
            check(!inside(planes, q), "guard rejects outside/behind");
        }
        check(inside(planes, new double[]{0,0,-0.001}), "five planes deliberately omit near plane");
        near(900.0 * math.guardProjection()[5] / 2, 600.0 * s.projection()[5] / 2, 2e-5, "k unchanged by padding");

        // Camera-relative plane d terms with a translated affine view; no absolute camera/origin in planes.
        float[] view = new Matrix4f().rotationXYZ(0.1f, -0.2f, 0.07f).translate(0.25f, -0.5f, 0.125f).get(new float[16]);
        s = camera(projection(1.25f, 1.5f), view, CAMERA, ORIGIN, VIEW);
        math = prepare(s, s, 100, 50);
        double[] world = {CAMERA.x()+2, CAMERA.y()-1, CAMERA.z()-20};
        double[] clip = shaderClip(math.guardedSource(), world);
        double[] values = {clip[3]+clip[0], clip[3]-clip[0], clip[3]-clip[1], clip[3]+clip[1], clip[3]};
        planes = math.guardFrustumPlanes();
        for (int i = 0; i < 5; i++) near(dot(planes, i, new double[]{2,-1,-20}), values[i], 1e-12, "rotated plane dot " + i);
        verifyPoint(math, world);
    }

    private static void reversedDepthAndPixelRejects() {
        Camera s = base();
        LodFarCamera math = prepare(s, s, 0, 0);
        for (double depth : new double[]{1, 0.5, 0.03125, 1e-9}) {
            pixel(math.sourcePixelToCurrent(400, 300, depth), 400, 300, depth, VIEW, 1e-12);
        }
        for (float far : new float[]{1000, Float.POSITIVE_INFINITY}) {
            // Same JOML reversed near/far argument order as vanilla Projection.getMatrix.
            float[] p = new Matrix4f().setPerspective(1.2f, 4.0f/3, far, 0.05f, true).get(new float[16]);
            Camera frame = camera(p, identity(), CAMERA, ORIGIN, VIEW);
            LodFarCamera motion = prepare(frame, camera(p, identity(), offset(CAMERA, 0.125, 0.25, -1), ORIGIN, VIEW), 100, 50);
            verifyPoint(motion, new double[]{CAMERA.x()+2, CAMERA.y()+1, CAMERA.z()-32});
        }
        for (double depth : new double[]{0, -0.1, 1.01}) rejectPixel(math.sourcePixelToCurrent(400, 300, depth), PixelReject.INVALID_DEPTH);
        rejectPixel(math.sourcePixelToCurrent(400, 300, Double.NaN), PixelReject.NON_FINITE);
        rejectPixel(math.sourcePixelToCurrent(Double.POSITIVE_INFINITY, 300, 0.5), PixelReject.NON_FINITE);
        for (double[] xy : new double[][]{{-0.1,2}, {800,2}, {2,600}, {2,-0.1}}) {
            rejectPixel(math.sourcePixelToCurrent(xy[0], xy[1], 0.5), PixelReject.OUTSIDE_INPUT);
        }
        pixel(math.sourcePixelToCurrent(0.5, 0.5, 0.5), 0.5, 0.5, 0.5, VIEW, 1e-11);
        pixel(math.sourcePixelToCurrent(799.5, 599.5, 0.5), 799.5, 599.5, 0.5, VIEW, 1e-11);
        Camera behind = camera(s.projection(), s.view(), offset(CAMERA, 0, 0, -32), ORIGIN, VIEW);
        rejectPixel(prepare(s, behind, 0, 0).sourcePixelToCurrent(400,300,0.5/16), PixelReject.BEHIND_EYE);
        Camera onEye = camera(s.projection(), s.view(), offset(CAMERA, 0, 0, -16), ORIGIN, VIEW);
        rejectPixel(prepare(s, onEye, 0, 0).sourcePixelToCurrent(400,300,0.5/16), PixelReject.BEHIND_EYE);
        Camera tooNear = camera(s.projection(), s.view(), offset(CAMERA, 0, 0, -15.75), ORIGIN, VIEW);
        rejectPixel(prepare(s, tooNear, 0, 0).sourcePixelToCurrent(400,300,0.5/16), PixelReject.INVALID_DEPTH);
        Camera outside = camera(s.projection(), s.view(), offset(CAMERA, 32, 0, 0), ORIGIN, VIEW);
        rejectPixel(prepare(s, outside, 0, 0).sourcePixelToCurrent(400,300,0.5/16), PixelReject.OUTSIDE_TARGET);
        rejectPixel(prepare(s, s, 100, 100).sourcePixelToCurrent(0.5,0.5,0.5), PixelReject.OUTSIDE_TARGET);
    }

    private static void standalonePlaneWidening() {
        Camera s = base();
        Viewport guard = new Viewport(1200,900);
        double[] original = prepare(s,s,0,0).guardFrustumPlanes();
        double[] saved = original.clone();
        double[] wide = LodFarCamera.widenFrustumPlanes(original,VIEW,guard);
        double[] projected = prepare(s,s,200,150).guardFrustumPlanes();
        for (int i = 0; i < 20; i++) near(wide[i],projected[i],3e-8,"plane widening vs float guard projection " + i);
        check(Arrays.equals(original,saved),"widening leaves synchronous planes unchanged");
        double[] same = LodFarCamera.widenFrustumPlanes(original,VIEW,VIEW);
        check(same != original && Arrays.equals(same,original),"zero padding copies exact coefficients");
        for (int i = 16; i < 20; i++) {
            check(Double.doubleToRawLongBits(wide[i]) == Double.doubleToRawLongBits(original[i]),"eye bitwise unchanged");
        }
        original[0] = 42;
        near(wide[0],2.0/3,0,"owned widened planes");

        // Post-effect P changes Z/W and is intentionally outside camera helper's bounded admission,
        // but its exact five-plane linear frustum can still be widened without inferring the optical eye.
        float[] p = new Matrix4f(projection(1.25f,1.75f)).rotateX(0.17f).translate(0.25f,-0.125f,0.5f).get(new float[16]);
        float[] v = new Matrix4f().rotationXYZ(-0.12f,0.23f,0.07f).get(new float[16]);
        double[] central = new double[20];
        for (int col = 0; col < 4; col++) {
            double[] basis = new double[4]; basis[col] = 1;
            double[] c = multiply(p,multiply(v,basis));
            central[col] = c[3]+c[0]; central[4+col] = c[3]-c[0];
            central[8+col] = c[3]+c[1]; central[12+col] = c[3]-c[1]; central[16+col] = c[3];
        }
        guard = new Viewport(1002,746);
        wide = LodFarCamera.widenFrustumPlanes(central,VIEW,guard);
        for (double[] q : new double[][]{{1,2,-16},{-4,-2,-32},{0,0,1}}) {
            double[] clip = multiply(p,multiply(v,new double[]{q[0],q[1],q[2],1}));
            double sx = (double)VIEW.width()/guard.width(), sy = (double)VIEW.height()/guard.height();
            double[] dots = {clip[3]+sx*clip[0],clip[3]-sx*clip[0],clip[3]+sy*clip[1],clip[3]-sy*clip[1],clip[3]};
            for (int i = 0; i < 5; i++) near(dot(wide,i,q),dots[i],1e-12,"post-effect plane widening " + i);
        }
        double[] bad = saved.clone(); bad[0] /= 2;
        throwsArgument(() -> LodFarCamera.widenFrustumPlanes(bad,VIEW,new Viewport(1200,900)),"inconsistent planes");
        double[] nonfinite = saved.clone(); nonfinite[3] = Double.NaN;
        throwsArgument(() -> LodFarCamera.widenFrustumPlanes(nonfinite,VIEW,VIEW),"NaN planes");
        throwsArgument(() -> LodFarCamera.widenFrustumPlanes(new double[20],VIEW,VIEW),"degenerate planes");
        throwsArgument(() -> LodFarCamera.widenFrustumPlanes(new double[19],VIEW,VIEW),"malformed planes");
        throwsArgument(() -> LodFarCamera.widenFrustumPlanes(null,VIEW,VIEW),"null planes");
        for (Viewport invalid : new Viewport[]{new Viewport(801,600),new Viewport(800,601),new Viewport(799,600),new Viewport(0,0)}) {
            throwsArgument(() -> LodFarCamera.widenFrustumPlanes(saved,VIEW,invalid),"invalid symmetric guard dimensions");
        }
        throwsArgument(() -> LodFarCamera.widenFrustumPlanes(saved,new Viewport(0,600),VIEW),"invalid central dimensions");
    }

    private static void throwsArgument(Runnable action, String message) {
        boolean threw = false;
        try { action.run(); } catch (IllegalArgumentException expected) { threw = true; }
        check(threw,message);
    }

    private static void invalidInputs() {
        Camera s = base();
        bad(LodFarCamera.prepare(null,s,0,0,16384,1L<<30), Reject.MISSING_INPUT);
        for (int size : new int[]{0,-1}) {
            Camera badSize = camera(s.projection(), s.view(), CAMERA, ORIGIN, new Viewport(size,600));
            bad(LodFarCamera.prepare(badSize,s,0,0,16384,1L<<30), Reject.INVALID_VIEWPORT);
        }
        bad(LodFarCamera.prepare(s,s,-1,0,16384,1L<<30), Reject.INVALID_PADDING);
        bad(LodFarCamera.prepare(s,s,0,-1,16384,1L<<30), Reject.INVALID_PADDING);
        bad(LodFarCamera.prepare(s,s,0,0,0,1), Reject.INVALID_LIMIT);
        bad(LodFarCamera.prepare(s,s,0,0,16384,0), Reject.INVALID_LIMIT);
        bad(LodFarCamera.prepare(s,s,Integer.MAX_VALUE,0,Integer.MAX_VALUE,Long.MAX_VALUE), Reject.TARGET_TOO_LARGE);
        bad(LodFarCamera.prepare(s,s,1,0,800,Long.MAX_VALUE), Reject.TARGET_TOO_LARGE);
        Camera huge = camera(s.projection(), s.view(), CAMERA, ORIGIN, new Viewport(Integer.MAX_VALUE,Integer.MAX_VALUE));
        bad(LodFarCamera.prepare(huge,huge,0,0,Integer.MAX_VALUE,Long.MAX_VALUE), Reject.TARGET_BUDGET);
        long bytes = 1000L * 700 * 72;
        check(LodFarCamera.prepare(s,s,100,50,1000,bytes).valid(), "exact budget/max-size admission");
        bad(LodFarCamera.prepare(s,s,100,50,1000,bytes-1), Reject.TARGET_BUDGET);
        Camera badCurrent = camera(s.projection(), s.view(), CAMERA, ORIGIN, new Viewport(16385,600));
        bad(LodFarCamera.prepare(s,badCurrent,0,0,16384,Long.MAX_VALUE), Reject.TARGET_TOO_LARGE);
        for (Position p : new Position[]{new Position(Double.NaN,0,0), new Position(0,Double.POSITIVE_INFINITY,0)}) {
            rejected(camera(s.projection(),s.view(),p,ORIGIN,VIEW), s, Reject.INVALID_POSITION);
            rejected(s,camera(s.projection(),s.view(),CAMERA,p,VIEW), Reject.INVALID_POSITION);
        }
        rejected(camera(s.projection(),s.view(),new Position(Double.MAX_VALUE,0,0),ORIGIN,VIEW),
            camera(s.projection(),s.view(),new Position(-Double.MAX_VALUE,0,0),ORIGIN,VIEW), Reject.INVALID_POSITION);
        rejected(camera(s.projection(),s.view(),null,ORIGIN,VIEW),s,Reject.MISSING_INPUT);
        rejected(s,camera(s.projection(),s.view(),CAMERA,null,VIEW),Reject.MISSING_INPUT);
        for (int i = 0; i < 16; i++) {
            float[] p = s.projection(), v = s.view(); p[i] = Float.NaN; v[i] = Float.POSITIVE_INFINITY;
            rejected(camera(p,s.view(),CAMERA,ORIGIN,VIEW),s,Reject.NON_FINITE_MATRIX);
            rejected(s,camera(s.projection(),v,CAMERA,ORIGIN,VIEW),Reject.NON_FINITE_MATRIX);
        }
        Camera zero = camera(new float[16],s.view(),CAMERA,ORIGIN,VIEW);
        rejected(zero,zero,Reject.SINGULAR_MATRIX); // No same-camera identity fallback on corrupt inputs.
        float[] v = identity(); v[0] = 0;
        rejected(s,camera(s.projection(),v,CAMERA,ORIGIN,VIEW),Reject.SINGULAR_MATRIX);
        v[0] = 1e-15f;
        rejected(camera(s.projection(),v,CAMERA,ORIGIN,VIEW),s,Reject.UNSTABLE_MATRIX);
        v = identity(); v[3] = 0.01f;
        rejected(s,camera(s.projection(),v,CAMERA,ORIGIN,VIEW),Reject.UNSUPPORTED_VIEW);
        for (float[] p : new float[][]{
            identity(), new Matrix4f().setPerspective(1.2f,1,0.05f,1000,true).get(new float[16]),
            new Matrix4f().setPerspective(1.2f,1,1000,0.05f,false).get(new float[16]),
            new Matrix4f().setPerspective(1.2f,1,Float.POSITIVE_INFINITY,0.05f,false).get(new float[16]),
            new Matrix4f(s.projection()).rotateX(0.1f).get(new float[16]),
            new Matrix4f(s.projection()).translate(0,0,0.1f).get(new float[16])}) {
            rejected(camera(p,s.view(),CAMERA,ORIGIN,VIEW),s,Reject.UNSUPPORTED_PROJECTION);
            rejected(s,camera(p,s.view(),CAMERA,ORIGIN,VIEW),Reject.UNSUPPORTED_PROJECTION);
        }
        for (float[] malformed : new float[][]{null, new float[15], new float[17]}) {
            boolean threw = false;
            try { camera(malformed,s.view(),CAMERA,ORIGIN,VIEW); } catch (IllegalArgumentException expected) { threw = true; }
            check(threw, "malformed matrix rejected before copy");
        }
    }

    /** Oracle uses original terrain fetch/Globals formulas, not production's matrix composition or inverse. */
    private static double[] shaderClip(Camera camera, double[] world) {
        double[] cam = {camera.position().x(), camera.position().y(), camera.position().z()};
        double[] relative = new double[4]; relative[3] = 1;
        for (int axis = 0; axis < 3; axis++) {
            int chunk = (int)Math.floor(world[axis] / 16) * 16;
            int cameraBlock = (int)Math.floor(cam[axis]);
            float position = (float)(world[axis] - chunk);
            float cameraOffset = (float)(cameraBlock - cam[axis]);
            relative[axis] = (position + (float)(chunk - cameraBlock)) + cameraOffset;
        }
        double[] raw = multiply(camera.projection(), multiply(camera.view(), relative));
        raw[1] = -raw[1]; // Exactly the original Metal shader's final operation. No depth fixup.
        return raw;
    }

    private static double[] multiply(float[] matrix, double[] point) {
        double[] result = new double[4];
        for (int row = 0; row < 4; row++) for (int col = 0; col < 4; col++) result[row] += matrix[col*4+row] * point[col];
        return result;
    }

    private static double[] multiply(double[] matrix, double[] point) {
        double[] result = new double[4];
        for (int row = 0; row < 4; row++) for (int col = 0; col < 4; col++) result[row] += matrix[col*4+row] * point[col];
        return result;
    }

    private static double[] screen(double[] clip, Viewport size) {
        return new double[]{(1 + clip[0]/clip[3]) * size.width()/2, (1 - clip[1]/clip[3]) * size.height()/2, clip[2]/clip[3]};
    }

    private static void verifyPoint(LodFarCamera math, double[] world) {
        double[] source = shaderClip(math.guardedSource(),world), current = shaderClip(math.current(),world);
        double[] warped = multiply(math.sourceToCurrentClip(),source), back = multiply(math.currentToSourceClip(),current);
        for (int i = 0; i < 4; i++) {
            near(warped[i],current[i],1e-9,"direct shader clip forward " + i + " at " + Arrays.toString(world));
            near(back[i],source[i],1e-9,"direct shader clip backward " + i);
        }
        double[] a = screen(source,math.guardViewport()), b = screen(current,math.currentViewport());
        pixel(math.sourcePixelToCurrent(a[0],a[1],a[2]),b[0],b[1],b[2],math.currentViewport(),1e-9);
        pixel(math.currentPixelToSource(b[0],b[1],b[2]),a[0],a[1],a[2],math.guardViewport(),1e-9);
    }

    private static void pixel(Pixel pixel, double x, double y, double depth, Viewport viewport, double tolerance) {
        check(pixel.valid(), "valid pixel: " + pixel.rejection());
        near(pixel.x(),x,tolerance,"pixel X"); near(pixel.y(),y,tolerance,"pixel Y");
        near(pixel.u(),x/viewport.width(),tolerance,"pixel U"); near(pixel.v(),y/viewport.height(),tolerance,"pixel V");
        near(pixel.depth(),depth,tolerance,"reversed depth, no remap");
    }

    private static void rejectPixel(Pixel pixel, PixelReject reason) {
        check(!pixel.valid() && pixel.rejection() == reason, "pixel rejection: expected " + reason + ", got " + pixel.rejection());
        check(Double.isNaN(pixel.x()) && Double.isNaN(pixel.depth()), "no usable values for rejected pixel");
    }

    private static double dot(double[] planes, int plane, double[] q) {
        int i = plane*4;
        return planes[i]*q[0] + planes[i+1]*q[1] + planes[i+2]*q[2] + planes[i+3];
    }

    private static boolean inside(double[] planes, double[] q) {
        for (int i = 0; i < 5; i++) if (dot(planes,i,q) < 0) return false;
        return true;
    }

    private static void rejected(Camera source, Camera current, Reject reason) {
        bad(LodFarCamera.prepare(source,current,0,0,16384,1L<<30),reason);
    }

    private static void bad(Result result, Reject reason) {
        check(!result.valid() && result.camera() == null && result.rejection() == reason && !result.detail().isBlank(),
            "rejection: expected " + reason + ", got " + result.rejection() + " " + result.detail());
    }
}
