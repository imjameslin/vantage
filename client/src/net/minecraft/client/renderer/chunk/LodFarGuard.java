package net.minecraft.client.renderer.chunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.minecraft.client.renderer.chunk.LodFarCamera.Camera;
import net.minecraft.client.renderer.chunk.LodFarCamera.Position;
import net.minecraft.client.renderer.chunk.LodFarCamera.Viewport;

/**
 * Bounded CPU-only capture metadata adapter; never allocates targets or touches a renderer.
 * Inputs must belong to one frame. Shader/billboard/fade admission is the coordinator's job.
 * Invalid layouts, camera mismatches and camera-math declines throw IllegalArgumentException;
 * callers must decline the capture, never substitute an identity or omit a camera check.
 */
public final class LodFarGuard {
    public static final int MAX_TEXTURE_SIZE = 16384;
    private static final int[] UNIFORM_SIZES = {64, 48, 48, 80};
    private final LodFarCamera camera;
    private final byte[][] uniformBytes;
    private final byte[] cullView;

    private LodFarGuard(LodFarCamera camera, byte[][] uniformBytes, byte[] cullView) {
        this.camera = camera;
        this.uniformBytes = uniformBytes;
        this.cullView = cullView;
    }

    /**
     * Pad each side by ceil(centralDimension/8): at least 25% extra total width/height.
     * Budget includes all three native slots and both layers (72 logical bytes/pixel).
     * Only Projection changes in the four UBOs; only the first 80 cull bytes change.
     * All arrays are owned, including arrays returned by accessors.
     */
    public static LodFarGuard prepare(LodFarGeometry.Source source, byte[][] uniforms, byte[] originalCullView,
                                      int width, int height, long targetBudgetBytes) {
        Viewport guard = guardViewport(width, height);
        byte[][] owned = copyUniforms(uniforms);
        Camera central = reconstruct(source, owned, width, height);
        LodFarCamera camera = admitted(LodFarCamera.prepare(central, central,
            (guard.width() - width) / 2, (guard.height() - height) / 2, MAX_TEXTURE_SIZE, targetBudgetBytes));
        require(originalCullView != null && originalCullView.length == 128, "Expected 128 cull-view bytes");
        byte[] view = originalCullView.clone();
        ByteBuffer cull = buffer(view);
        validateCullCamera(source, cull);
        double[] wide = widenFloatPlanes(cull, central.viewport(), guard);
        for (int i = 0; i < 20; i++) {
            float value = (float)wide[i];
            require(Float.isFinite(value), "Widened cull plane overflow");
            // Eye plane is never rewritten, preserving even the sign of a zero coefficient.
            if (i < 16) cull.putFloat(i * 4, value);
        }
        buffer(owned[0]).asFloatBuffer().put(camera.guardProjection());
        return new LodFarGuard(camera, owned, view);
    }

    public LodFarCamera camera() { return this.camera; }
    public byte[][] uniformBytes() { return copyUniforms(this.uniformBytes); }
    public byte[] cullView() { return this.cullView.clone(); }

    /** Shared sizing for target creation, producer capture and Lod.prepareFrame selection. */
    public static Viewport guardViewport(int width, int height) {
        require(width > 0 && height > 0, "Positive central viewport required");
        long w = width + 2L * (((long)width + 7) / 8);
        long h = height + 2L * (((long)height + 7) / 8);
        require(w <= MAX_TEXTURE_SIZE && h <= MAX_TEXTURE_SIZE, "Guard viewport exceeds 16384");
        return new Viewport((int)w, (int)h);
    }

    /**
     * Widen the original UNNORMALIZED double frame planes for Lod.prepareFrame, using the
     * exact same integer guard dimensions as capture. Keep the original central selection k
     * (centralHeight/2 * abs(baseCameraProjection.m11)); do not derive k from guarded P.
     * The double helper's strict consistency checks are intentionally retained here.
     */
    public static double[] selectionPlanes(double[] planes, int width, int height) {
        return LodFarCamera.widenFrustumPlanes(planes, new Viewport(width, height), guardViewport(width, height));
    }

    /** Reconstruct and validate a central/current camera from same-frame uniforms and Source. */
    public static Camera currentCamera(LodFarGeometry.Source source, byte[][] uniforms, int width, int height) {
        Camera current = reconstruct(source, copyUniforms(uniforms), width, height);
        admitted(LodFarCamera.prepare(current, current, 0, 0, MAX_TEXTURE_SIZE, Long.MAX_VALUE));
        return current;
    }

    /**
     * Metal guard clip -> current clip for an ALREADY guarded native source camera. Never
     * pad it again. Both cameras are validated before either path is used. For the same
     * position/view and the exact rounded projection produced by symmetric integer padding,
     * return the canonical crop diag(guardWidth/currentWidth, guardHeight/currentHeight,1,1).
     * This deliberately removes subpixel UBO-rounding/inversion noise at integer crop edges.
     * A changed projection, pose or incompatible viewport uses LodFarCamera's full transform.
     * Render origins cancel algebraically and need not match. No target is allocated here.
     */
    public static double[] sourceToCurrentClip(Camera guardedSource, Camera current) {
        LodFarCamera transform = admitted(LodFarCamera.prepare(guardedSource, current, 0, 0,
            MAX_TEXTURE_SIZE, Long.MAX_VALUE));
        if (canonicalCrop(guardedSource, current)) {
            return new double[]{(double)guardedSource.viewport().width() / current.viewport().width(),0,0,0,
                0,(double)guardedSource.viewport().height() / current.viewport().height(),0,0,
                0,0,1,0, 0,0,0,1};
        }
        return transform.sourceToCurrentClip();
    }

    /**
     * Owned native-order 144-byte MCMTLFarWarpTransform, or null for unsupported motion.
     * Layout: currentToSource float[16], sourceToCurrent float[16], motion int=1,
     * translation float[3]=0. Invalid cameras throw, including before motion admission.
     * EXACT Source/current position equality is required, never a float/epsilon comparison.
     * Pure view rotation about the same camera is supported; changing an optical-centre
     * offset encoded in P/V is translation too. Scene/billboard/fade admission stays with
     * the coordinator. No native calls or image/target allocations occur here.
     */
    public static byte[] warpTransform(Camera guardedSource, Camera current) {
        LodFarCamera transform = admitted(LodFarCamera.prepare(guardedSource, current, 0, 0,
            MAX_TEXTURE_SIZE, Long.MAX_VALUE));
        if (!samePosition(guardedSource, current)) return null;
        boolean crop = canonicalCrop(guardedSource, current);
        if (!crop && !sameOpticalCentre(guardedSource, current)) return null;
        float[] backward = new float[16], forward = new float[16];
        if (crop) {
            // mcmetal_farwarp.h recognition requires BOTH ratios rounded once from double.
            backward[0] = (float)((double)current.viewport().width() / guardedSource.viewport().width());
            backward[5] = (float)((double)current.viewport().height() / guardedSource.viewport().height());
            forward[0] = (float)((double)guardedSource.viewport().width() / current.viewport().width());
            forward[5] = (float)((double)guardedSource.viewport().height() / current.viewport().height());
            backward[10] = backward[15] = forward[10] = forward[15] = 1;
        } else {
            double[] b = transform.currentToSourceClip(), f = transform.sourceToCurrentClip();
            for (int i = 0; i < 16; i++) {
                backward[i] = (float)b[i]; forward[i] = (float)f[i];
                if (!Float.isFinite(backward[i]) || !Float.isFinite(forward[i])) return null;
            }
            // Only after exact positional equality AND independent optical-centre proof.
            backward[8] = backward[9] = backward[11] = 0;
            forward[8] = forward[9] = forward[11] = 0;
            if (backward[10] <= 0 || forward[10] <= 0 || !nativeInverseConsistent(backward, forward)) return null;
        }
        byte[] bytes = new byte[144];
        ByteBuffer out = buffer(bytes);
        out.asFloatBuffer().put(backward).put(forward);
        out.putInt(128, 1); // PureRotation; actual displacement was proved exactly zero.
        return bytes;
    }

    private static boolean samePosition(Camera source, Camera current) {
        return source.position().x() == current.position().x()
            && source.position().y() == current.position().y()
            && source.position().z() == current.position().z();
    }

    private static boolean sameOpticalCentre(Camera source, Camera current) {
        float[] sp = source.projection(), cp = current.projection(), sv = source.view(), cv = current.view();
        // Admitted P has W=-z and invertible XY rows. Zero XY translation proves that
        // the view-space optical centre is the origin, regardless of jitter/shear/FOV.
        // Equal affine views share that centre even with a fixed eye offset. Otherwise
        // require zero view translations, proving both map the world-relative origin to it.
        return sp[12] == 0 && sp[13] == 0 && cp[12] == 0 && cp[13] == 0
            && (Arrays.equals(sv, cv) || sv[12] == 0 && sv[13] == 0 && sv[14] == 0
                && cv[12] == 0 && cv[13] == 0 && cv[14] == 0);
    }

    private static boolean nativeInverseConsistent(float[] a, float[] b) {
        for (int row = 0; row < 4; row++) for (int col = 0; col < 4; col++) {
            double ab = 0, ba = 0;
            for (int k = 0; k < 4; k++) {
                ab += (double)a[k * 4 + row] * b[col * 4 + k];
                ba += (double)b[k * 4 + row] * a[col * 4 + k];
            }
            if (Math.abs(ab - (row == col ? 1 : 0)) > 0.0002
                || Math.abs(ba - (row == col ? 1 : 0)) > 0.0002) return false;
        }
        return true;
    }

    /** Same integer crop plus the original 2x2 fragment helper-lane phase. */
    public static boolean exactShadingCrop(Camera source,Camera sourceCentral,Camera current) {
        // Widening rounds to float: distinct central projections can produce the
        // same guarded coefficient. Compare the original, not its rounded image.
        return Arrays.equals(sourceCentral.projection(),current.projection()) && canonicalCrop(source,current)
            && ((source.viewport().width()-current.viewport().width())&3)==0
            && ((source.viewport().height()-current.viewport().height())&3)==0;
    }

    /** Exact pose and integer guard crop. Re-shading additionally needs the
     * helper-lane alignment checked by exactShadingCrop. */
    public static boolean canonicalCrop(Camera source, Camera current) {
        int dw = source.viewport().width() - current.viewport().width();
        int dh = source.viewport().height() - current.viewport().height();
        if (dw < 0 || dh < 0 || (dw & 1) != 0 || (dh & 1) != 0
            || !samePosition(source, current) || !Arrays.equals(source.view(), current.view())) return false;
        float[] expected = current.projection();
        for (int col = 0; col < 4; col++) {
            expected[col * 4] = (float)(expected[col * 4] * ((double)current.viewport().width() / source.viewport().width()));
            expected[col * 4 + 1] = (float)(expected[col * 4 + 1] * ((double)current.viewport().height() / source.viewport().height()));
        }
        return Arrays.equals(expected, source.projection());
    }

    private static Camera reconstruct(LodFarGeometry.Source source, byte[][] uniforms, int width, int height) {
        require(source != null, "Source camera required");
        // GlobalSettingsUniform.update: ivec3 floor(C) at 0; vec3 float(floor(C)-C) at 16.
        // Match each uploaded float, not a loose epsilon on a huge absolute world position.
        // Distinct exact positions which round to the same shader offset are admissible.
        ByteBuffer globals = buffer(uniforms[1]);
        double[] position = {source.cameraX(), source.cameraY(), source.cameraZ()};
        for (int axis = 0; axis < 3; axis++) {
            double c = position[axis], block = Math.floor(c);
            require(Double.isFinite(c) && block >= Integer.MIN_VALUE && block <= Integer.MAX_VALUE,
                "Invalid Source.camera axis " + axis);
            require(globals.getInt(axis * 4) == (int)block
                && globals.getFloat(16 + axis * 4) == (float)(block - c),
                "Globals camera mismatch on axis " + axis);
        }
        float[] projection = new float[16], view = new float[16];
        buffer(uniforms[0]).asFloatBuffer().get(projection);
        buffer(uniforms[3]).asFloatBuffer().get(view); // Terrain's final 16 bytes are not a matrix.
        // mcmtl_far_engine_camera: double position[3], double renderOrigin[3], float V[16], float P[16].
        return new Camera(projection, view, new Position(position[0], position[1], position[2]),
            new Position(source.originX(), source.originY(), source.originZ()), new Viewport(width, height));
    }

    private static void validateCullCamera(LodFarGeometry.Source source, ByteBuffer cull) {
        double[] camera = {source.cameraX(), source.cameraY(), source.cameraZ()};
        double[] eye = {source.eyeX(), source.eyeY(), source.eyeZ()};
        int[] origin = {source.originX(), source.originY(), source.originZ()};
        for (int axis = 0; axis < 3; axis++) {
            require(Double.isFinite(eye[axis]), "Invalid Source.eye axis " + axis);
            float cameraDelta = (float)(camera[axis] - origin[axis]);
            float eyeDelta = (float)(eye[axis] - origin[axis]);
            require(Float.isFinite(cameraDelta) && Float.isFinite(eyeDelta)
                && cull.getFloat(96 + axis * 4) == cameraDelta && cull.getFloat(112 + axis * 4) == eyeDelta,
                "Cull camera/eye mismatch on axis " + axis);
        }
    }

    /** Adapt only the float serialization residual; never relax the shared double helper. */
    private static double[] widenFloatPlanes(ByteBuffer cull, Viewport central, Viewport guard) {
        double[] raw = new double[20], exact = new double[20], residual = new double[8];
        for (int i = 0; i < 20; i++) {
            raw[i] = exact[i] = cull.getFloat(i * 4);
            require(Double.isFinite(raw[i]), "Non-finite cull plane");
        }
        for (int axis = 0; axis < 2; axis++) for (int col = 0; col < 4; col++) {
            int l = axis * 8 + col, r = l + 4;
            double centre = (raw[l] + raw[r]) * 0.5, eye = raw[16 + col];
            double error = centre - eye;
            // Each original coefficient was rounded independently to float. The centre's
            // rounding bound is (ulp(L)+ulp(R))/4, plus ulp(E)/2 for the eye coefficient.
            double tolerance = 0.25 * ((double)Math.ulp((float)raw[l]) + Math.ulp((float)raw[r]))
                + 0.5 * Math.ulp((float)eye) + 4 * Math.ulp(Math.max(Math.abs(centre), Math.abs(eye)));
            require(Math.abs(error) <= tolerance, "Inconsistent unnormalized float cull planes");
            residual[axis * 4 + col] = error;
            double side = (raw[l] - raw[r]) * 0.5;
            exact[l] = eye + side;
            exact[r] = eye - side;
        }
        double[] wide = LodFarCamera.widenFrustumPlanes(exact, central, guard);
        // Restore the measured pair centre so this is widening the original raw planes,
        // not replacing them with planes reconstructed from a different projection/view.
        for (int axis = 0; axis < 2; axis++) for (int col = 0; col < 4; col++) {
            wide[axis * 8 + col] += residual[axis * 4 + col];
            wide[axis * 8 + col + 4] += residual[axis * 4 + col];
        }
        return wide;
    }

    private static byte[][] copyUniforms(byte[][] uniforms) {
        require(uniforms != null && uniforms.length == 4, "Expected Projection/Globals/Fog/Terrain uniforms");
        byte[][] copy = new byte[4][];
        for (int i = 0; i < 4; i++) {
            require(uniforms[i] != null && uniforms[i].length == UNIFORM_SIZES[i], "Invalid uniform layout at slot " + i);
            copy[i] = uniforms[i].clone();
        }
        return copy;
    }

    private static ByteBuffer buffer(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()); }

    private static LodFarCamera admitted(LodFarCamera.Result result) {
        require(result.valid(), result.rejection() + ": " + result.detail());
        return result.camera();
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalArgumentException(detail);
    }
}
