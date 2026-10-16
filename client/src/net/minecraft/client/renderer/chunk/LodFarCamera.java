package net.minecraft.client.renderer.chunk;

import java.util.Arrays;
import org.joml.Matrix4d;
import org.joml.Vector4d;

/**
 * Bounded CPU-only camera sidecar. No renderer, native calls, scheduling or image allocation.
 * All matrices/arrays are column-major (index = column * 4 + row), multiplying column vectors.
 *
 * <p>Derivation from terrain.vsh and TerrainVertexMsl.body/clusterVertexLod, including the original
 * BLOCK and compact LOD shaders: pos = (Position + (ChunkPosition - CameraBlockPos)) + CameraOffset;
 * clip = F * P * V * vec4(pos, 1), where F = diag(1,-1,1,1). GlobalSettingsUniform.update writes
 * CameraBlockPos = floor(C), CameraOffset = float(floor(C)-C), so mathematically pos = world-C.
 * LevelRenderer supplies viewRotationMatrix as V; P is the actual uploaded projection, including
 * any supported modifications, NOT the camera's separate culling projection. MslTranslator uses
 * FLIP_VERTEX_Y with FIXUP_DEPTH_CONVENTION=false: never introduce an OpenGL depth remap here.
 *
 * <p>The native mcmtl_far_engine_camera.renderOrigin is LodGpu's culling origin O, not another
 * camera translation in V. For origin-relative q = world-O, A = F*P*V*T(O-C). Consequently
 * Acurrent*T(Osource-Ocurrent)*inverse(Asource) =
 * F*Pcurrent*Vcurrent*T(Csource-Ccurrent)*inverse(F*Pguard*Vsource).
 * The implementation uses the latter form: origins cancel, double camera differences stay small,
 * and no huge absolute float translation is inverted. Both exact origins remain in the metadata.
 * GameRenderer's Globals update and LevelRenderer.prepareChunkRendersIndirectFast both use
 * cameraRenderState.pos, which reaches Source.camera via Lod.prepareFrame/LodGpu.frame.
 * Source.eye is that position PLUS TerrainFrustum's optical-centre offset for facing culling;
 * substituting it for C would count a projection-encoded bob offset twice. Captures must keep
 * Globals, P, V and this position from the same frame; record field names alone prove nothing.
 *
 * <p>This is projective camera math, not bit-identical float shader rasterization, a visibility
 * certificate or current shading. Camera-facing compact billboards change world positions and
 * cannot be made current by this transform alone. Guard culling/selection, dither offset correction,
 * disocclusion, clipping/coverage and repair remain future integration work.
 */
public final class LodFarCamera {
    /** Native target contract: three slots, two opaque layers, RGBA8 + D32 + R32 per pixel. */
    public static final long TARGET_BYTES_PER_PIXEL = 3L * 2L * (4L + 4L + 4L);
    private static final double MAX_CONDITION = 1.0e12;
    private static final double MAX_INVERSE_ERROR = 1.0e-8;

    public record Position(double x, double y, double z) { }

    /** Full image viewport, origin (0,0), Metal depth range [0,1]. No sub-viewports. */
    public record Viewport(int width, int height) { }

    /**
     * Exact captured camera position and culling/render origin, plus the actual shader P and V.
     * V consumes camera-relative positions; do not pass a world-space lookAt matrix that already
     * subtracts C. Arrays are copied on input/output. Malformed arrays throw IllegalArgumentException;
     * numeric validity is checked by prepare. Source P here is the UNGUARDED central projection.
     */
    public record Camera(float[] projection, float[] view, Position position, Position renderOrigin, Viewport viewport) {
        public Camera {
            projection = copy16(projection);
            view = copy16(view);
        }

        @Override public float[] projection() { return this.projection.clone(); }
        @Override public float[] view() { return this.view.clone(); }
    }

    public enum Reject {
        NONE, MISSING_INPUT, INVALID_VIEWPORT, INVALID_POSITION, INVALID_PADDING, INVALID_LIMIT,
        TARGET_TOO_LARGE, TARGET_BUDGET, NON_FINITE_MATRIX, SINGULAR_MATRIX, UNSTABLE_MATRIX,
        UNSUPPORTED_PROJECTION, UNSUPPORTED_VIEW
    }

    /** A declined result has camera == null, never an identity substitute. */
    public record Result(Reject rejection, String detail, LodFarCamera camera) {
        public boolean valid() { return this.rejection == Reject.NONE; }
    }

    public enum PixelReject { NONE, NON_FINITE, OUTSIDE_INPUT, INVALID_DEPTH, BEHIND_EYE, OUTSIDE_TARGET }

    /** Top-left continuous pixel coordinates and UV; a pixel centre is (i+0.5,j+0.5). */
    public record Pixel(PixelReject rejection, double x, double y, double u, double v, double depth) {
        public boolean valid() { return this.rejection == PixelReject.NONE; }
    }

    private final Camera source, current;
    private final Viewport guardViewport;
    private final int guardOffsetX, guardOffsetY;
    private final long targetBytes;
    private final float[] guardProjection;
    private final double[] guardFrustumPlanes;
    private final Matrix4d sourceToCurrent, currentToSource;

    private LodFarCamera(Camera source, Camera current, Viewport guardViewport, int padX, int padY,
                         long targetBytes, float[] guardProjection, Matrix4d forward, Matrix4d backward) {
        this.source = source;
        this.current = current;
        this.guardViewport = guardViewport;
        this.guardOffsetX = padX;
        this.guardOffsetY = padY;
        this.targetBytes = targetBytes;
        this.guardProjection = guardProjection;
        this.guardFrustumPlanes = planes(matrix(guardProjection).mul(matrix(source.view)));
        this.sourceToCurrent = forward;
        this.currentToSource = backward;
    }

    /**
     * Scale EVERY coefficient in projection rows X/Y by old/guard size, leaving Z/W unchanged.
     * Symmetric padding is an integer number of pixels per side. The old centre viewport starts
     * at (padX,padY); pixel density and jitter in pixel units are preserved up to float UBO rounding.
     * Uses the rounded float guard projection for reprojection, exactly the bytes returned for slot 0.
     *
     * <p>Admits affine V and reversed-Z perspective P whose Z/W rows are (0,0,a,b), (0,0,-1,0),
     * with b > 0 and -1e-6f <= a <= 0.5 (finite far >= 3*near, or JOML's infinite-far epsilon).
     * Arbitrary finite X/Y rows, including off-axis jitter, shear and clip offsets, are retained.
     * Other projection forms (including bob/nausea that change Z/W), forward/OpenGL depth and
     * projective views decline explicitly. Jitter must already be encoded in P; viewport jitter,
     * nonlinear shaders and shader variants changing positions require separate admission.
     *
     * <p>maxTextureSize is a caller-supplied device limit; budget counts only logical native target
     * bytes, not driver padding or packet/geometry/material allocations. Both viewports are bounded.
     * Every admitted transform has a finite, conditioned, two-sided checked inverse.
     */
    public static Result prepare(Camera source, Camera current, int padX, int padY,
                                 int maxTextureSize, long targetBudgetBytes) {
        try {
            require(source != null && current != null, Reject.MISSING_INPUT, "Source/current camera required");
            require(maxTextureSize > 0 && targetBudgetBytes > 0, Reject.INVALID_LIMIT, "Positive size/budget required");
            require(padX >= 0 && padY >= 0, Reject.INVALID_PADDING, "Padding must be nonnegative integers");
            validateCamera(source, "source", maxTextureSize);
            validateCamera(current, "current", maxTextureSize);
            long width = (long)source.viewport.width + 2L * padX;
            long height = (long)source.viewport.height + 2L * padY;
            require(width <= maxTextureSize && height <= maxTextureSize,
                Reject.TARGET_TOO_LARGE, "Guard dimensions exceed maximum texture size");
            // width/height <= positive int max, so their product fits long; byte multiplication may not.
            long pixels = width * height;
            require(pixels <= targetBudgetBytes / TARGET_BYTES_PER_PIXEL,
                Reject.TARGET_BUDGET, "Three-slot/two-layer guard target exceeds logical byte budget");
            long bytes = pixels * TARGET_BYTES_PER_PIXEL;
            Viewport guard = new Viewport((int)width, (int)height);
            float[] gp = source.projection.clone();
            for (int col = 0; col < 4; col++) {
                gp[col * 4] = (float)(gp[col * 4] * ((double)source.viewport.width / width));
                gp[col * 4 + 1] = (float)(gp[col * 4 + 1] * ((double)source.viewport.height / height));
            }
            Matrix4d sourceClip = clip(gp, source.view);
            Matrix4d sourceInverse = inverse(sourceClip, "guard source clip");
            Matrix4d currentClip = clip(current.projection, current.view);
            inverse(currentClip, "current clip");
            double dx = source.position.x - current.position.x;
            double dy = source.position.y - current.position.y;
            double dz = source.position.z - current.position.z;
            require(finite(dx, dy, dz), Reject.INVALID_POSITION, "Camera difference overflow");
            Matrix4d forward;
            if (dx == 0.0 && dy == 0.0 && dz == 0.0 && Arrays.equals(gp, current.projection)
                && Arrays.equals(source.view, current.view)) {
                // Deliberate exact identity ONLY for equal validated cameras/projections, not failure recovery.
                forward = new Matrix4d();
            } else {
                forward = currentClip.translate(dx, dy, dz).mul(sourceInverse).assume(0);
            }
            Matrix4d backward = inverse(forward, "source-to-current clip");
            return new Result(Reject.NONE, "", new LodFarCamera(source, current, guard, padX, padY,
                bytes, gp, forward, backward));
        } catch (Declined failure) {
            return new Result(failure.rejection, failure.getMessage(), null);
        }
    }

    public Camera source() { return this.source; }
    public Camera current() { return this.current; }
    public Viewport sourceViewport() { return this.source.viewport; }
    public Viewport currentViewport() { return this.current.viewport; }
    public Viewport guardViewport() { return this.guardViewport; }
    public int guardOffsetX() { return this.guardOffsetX; }
    public int guardOffsetY() { return this.guardOffsetY; }
    public long targetBytes() { return this.targetBytes; }
    public float[] guardProjection() { return this.guardProjection.clone(); }

    /**
     * Five unnormalized (a,b,c,d) planes for q = world - source.position, inside iff dot >= 0.
     * EXACT TerrainFrustum.copyPlanes / TerrainDrawList.framePlanes order: left, right, bottom,
     * top, eye = r3+r0, r3-r0, r3+r1, r3-r1, r3 of UNFLIPPED Pguard*Vsource. Metal F would
     * swap the Y plane labels. No near/far planes and no render-origin translation are included.
     * Give the same owned planes to selection and capture culling, with their source camera.
     * For origin-relative cull tables keep camera-origin as the separate cull uniform, as LodGpu does.
     *
     * <p>Selection must retain its original central k = centralHeight/2 * abs(baseCameraP.m11).
     * Do not recompute k from guard m11 and centralHeight (which reduces detail). For this captured
     * projection guardHeight*abs(Pguard.m11)/2 equals centralHeight*abs(P.m11)/2 up to float rounding.
     * Lod.prepareFrame uses the pre-bob camera projection for k; it cannot in general be recovered
     * from the uploaded post-effect P supplied here, so the caller must retain that original k.
     */
    public double[] guardFrustumPlanes() { return this.guardFrustumPlanes.clone(); }

    /**
     * Standalone widening of an existing exact TerrainDrawList.framePlanes snapshot. This does not
     * require projection admission: any linear P*V producing these five unnormalized planes works.
     * Returns a new camera-relative array in the SAME order, with the eye plane copied unchanged.
     * The caller can retain the central planes for synchronous draws and use this copy for producer
     * selection/culling. Keep central selection k unchanged (see guardFrustumPlanes).
     *
     * <p>For each opposing side pair, r3=(L+R)/2, r0=(L-R)/2, wideL/R=r3 +/- scale*r0;
     * likewise B/T and r1. Scale is central/guard dimension. This equals projection row scaling in
     * real arithmetic; the actual float guard UBO may differ slightly by rounding, so retain the
     * culler's existing conservative bounds margin. Independently normalized planes are unsupported.
     * Invalid dimensions, non-finite or inconsistent planes throw IllegalArgumentException.
     */
    public static double[] widenFrustumPlanes(double[] framePlanes, Viewport central, Viewport guard) {
        if (framePlanes == null || framePlanes.length != 20 || central == null || guard == null
            || central.width <= 0 || central.height <= 0 || guard.width < central.width || guard.height < central.height
            || ((guard.width - central.width) & 1) != 0 || ((guard.height - central.height) & 1) != 0) {
            throw new IllegalArgumentException("Expected 20 planes and symmetric integer guard viewport padding");
        }
        for (double value : framePlanes) if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite frustum plane");
        }
        double[] wide = framePlanes.clone();
        for (int axis = 0; axis < 2; axis++) {
            double scale = axis == 0 ? (double)central.width / guard.width : (double)central.height / guard.height;
            double sideMagnitude = 0;
            for (int col = 0; col < 4; col++) {
                int left = axis * 8 + col, right = left + 4;
                double l = framePlanes[left], r = framePlanes[right], eye = framePlanes[16 + col];
                double centre = l * 0.5 + r * 0.5, side = l * 0.5 - r * 0.5;
                sideMagnitude = Math.max(sideMagnitude, Math.abs(side));
                double magnitude = Math.max(1.0, Math.max(Math.abs(l), Math.max(Math.abs(r), Math.abs(eye))));
                if (Math.abs(centre - eye) > 1e-12 * magnitude) {
                    throw new IllegalArgumentException("Expected unnormalized opposing planes with the same eye row");
                }
                if (scale != 1.0) {
                    wide[left] = centre + scale * side;
                    wide[right] = centre - scale * side;
                    if (!Double.isFinite(wide[left]) || !Double.isFinite(wide[right])) {
                        throw new IllegalArgumentException("Widened plane overflow");
                    }
                }
            }
            if (sideMagnitude == 0) throw new IllegalArgumentException("Degenerate side planes");
        }
        double eyeMagnitude = 0;
        for (int i = 16; i < 20; i++) eyeMagnitude = Math.max(eyeMagnitude, Math.abs(framePlanes[i]));
        if (eyeMagnitude == 0) throw new IllegalArgumentException("Degenerate eye plane");
        return wide;
    }

    /** Actual source metadata for mcmtl_far_engine_camera + full guard target dimensions. */
    public Camera guardedSource() {
        return new Camera(this.guardProjection, this.source.view, this.source.position, this.source.renderOrigin, this.guardViewport);
    }

    /** Metal homogeneous source GUARD clip to current clip; includes F on both sides, no depth fixup. */
    public double[] sourceToCurrentClip() { return this.sourceToCurrent.get(new double[16]); }
    /** Algebraic inverse. Using it requires CURRENT depth; this is not an inverse visibility search. */
    public double[] currentToSourceClip() { return this.currentToSource.get(new double[16]); }

    public Pixel sourcePixelToCurrent(double x, double y, double depth) {
        return mapPixel(this.sourceToCurrent, this.guardViewport, this.current.viewport, x, y, depth);
    }

    public Pixel currentPixelToSource(double x, double y, double depth) {
        return mapPixel(this.currentToSource, this.current.viewport, this.guardViewport, x, y, depth);
    }

    private static Pixel mapPixel(Matrix4d matrix, Viewport input, Viewport output, double x, double y, double depth) {
        if (!finite(x, y, depth)) return invalidPixel(PixelReject.NON_FINITE);
        if (x < 0 || x >= input.width || y < 0 || y >= input.height) return invalidPixel(PixelReject.OUTSIDE_INPUT);
        // Reversed-Z clear is zero; never turn a clear sample into a point at infinity.
        if (!(depth > 0.0 && depth <= 1.0)) return invalidPixel(PixelReject.INVALID_DEPTH);
        // Metal viewport is top-left: y_ndc = 1-2v. The shader's Y flip is already in the matrix.
        Vector4d p = matrix.transform(new Vector4d(2.0 * x / input.width - 1.0, 1.0 - 2.0 * y / input.height, depth, 1.0));
        if (!finite(p.x, p.y, p.z) || !Double.isFinite(p.w)) return invalidPixel(PixelReject.NON_FINITE);
        if (!(p.w > 0.0)) return invalidPixel(PixelReject.BEHIND_EYE);
        double u = (p.x / p.w + 1.0) * 0.5;
        double v = (1.0 - p.y / p.w) * 0.5;
        double z = p.z / p.w;
        if (!finite(u, v, z)) return invalidPixel(PixelReject.NON_FINITE);
        if (!(z > 0.0 && z <= 1.0)) return invalidPixel(PixelReject.INVALID_DEPTH);
        if (!(u >= 0.0 && u < 1.0 && v >= 0.0 && v < 1.0)) return invalidPixel(PixelReject.OUTSIDE_TARGET);
        return new Pixel(PixelReject.NONE, u * output.width, v * output.height, u, v, z);
    }

    private static Pixel invalidPixel(PixelReject reason) {
        return new Pixel(reason, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    private static void validateCamera(Camera camera, String label, int maxSize) {
        require(camera.position != null && camera.renderOrigin != null && camera.viewport != null,
            Reject.MISSING_INPUT, label + ": position, render origin and viewport required");
        require(finite(camera.position.x, camera.position.y, camera.position.z)
            && finite(camera.renderOrigin.x, camera.renderOrigin.y, camera.renderOrigin.z),
            Reject.INVALID_POSITION, label + ": non-finite position/origin");
        require(camera.viewport.width > 0 && camera.viewport.height > 0,
            Reject.INVALID_VIEWPORT, label + ": positive viewport dimensions required");
        require(camera.viewport.width <= maxSize && camera.viewport.height <= maxSize,
            Reject.TARGET_TOO_LARGE, label + ": viewport exceeds maximum texture size");
        Matrix4d p = matrix(camera.projection), v = matrix(camera.view);
        inverse(p, label + " projection");
        inverse(v, label + " view");
        require(p.m02() == 0 && p.m12() == 0 && p.m03() == 0 && p.m13() == 0
            && p.m23() == -1 && p.m33() == 0 && p.m22() >= (double)-1.0e-6f && p.m22() <= 0.5 && p.m32() > 0,
            Reject.UNSUPPORTED_PROJECTION, label + ": require reversed-Z [0,1] perspective (far >= 3*near); unsupported projection/jitter");
        require(v.m03() == 0 && v.m13() == 0 && v.m23() == 0 && v.m33() == 1,
            Reject.UNSUPPORTED_VIEW, label + ": view must be affine and consume camera-relative positions");
    }

    private static Matrix4d clip(float[] projection, float[] view) {
        // Left multiplication flips an entire ROW, not a column or only the diagonal.
        Matrix4d p = matrix(projection);
        for (int col = 0; col < 4; col++) p.set(col, 1, -p.get(col, 1));
        return p.assume(0).mul(matrix(view)).assume(0);
    }

    private static Matrix4d matrix(float[] values) {
        // Never infer an orthonormal inverse from the approximate float camera rotation.
        return new Matrix4d(values).assume(0);
    }

    private static double[] planes(Matrix4d matrix) {
        double[] planes = new double[20];
        for (int col = 0; col < 4; col++) {
            double x = matrix.get(col, 0), y = matrix.get(col, 1), w = matrix.get(col, 3);
            planes[col] = w + x;
            planes[4 + col] = w - x;
            planes[8 + col] = w + y;
            planes[12 + col] = w - y;
            planes[16 + col] = w;
        }
        return planes;
    }

    private static Matrix4d inverse(Matrix4d matrix, String label) {
        require(matrix.isFinite(), Reject.NON_FINITE_MATRIX, label + ": non-finite coefficients");
        double determinant = new Matrix4d(matrix).assume(0).determinant();
        require(Double.isFinite(determinant) && determinant != 0.0,
            Reject.SINGULAR_MATRIX, label + ": singular or non-finite determinant");
        Matrix4d inverse = new Matrix4d(matrix).assume(0).invert().assume(0);
        require(inverse.isFinite() && norm(matrix) * norm(inverse) <= MAX_CONDITION,
            Reject.UNSTABLE_MATRIX, label + ": inverse exceeds condition bound");
        require(identityError(new Matrix4d(matrix).assume(0).mul(inverse)) <= MAX_INVERSE_ERROR
            && identityError(new Matrix4d(inverse).assume(0).mul(matrix)) <= MAX_INVERSE_ERROR,
            Reject.UNSTABLE_MATRIX, label + ": inverse residual exceeds bound");
        return inverse;
    }

    private static double norm(Matrix4d matrix) {
        double maximum = 0;
        for (int row = 0; row < 4; row++) {
            double sum = 0;
            for (int col = 0; col < 4; col++) sum += Math.abs(matrix.get(col, row));
            maximum = Math.max(maximum, sum);
        }
        return maximum;
    }

    private static double identityError(Matrix4d matrix) {
        double error = 0;
        for (int col = 0; col < 4; col++) for (int row = 0; row < 4; row++) {
            error = Math.max(error, Math.abs(matrix.get(col, row) - (col == row ? 1 : 0)));
        }
        return error;
    }

    private static float[] copy16(float[] values) {
        if (values == null || values.length != 16) throw new IllegalArgumentException("Expected 16 column-major matrix elements");
        return values.clone();
    }

    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    private static void require(boolean condition, Reject rejection, String detail) {
        if (!condition) throw new Declined(rejection, detail);
    }

    private static final class Declined extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Reject rejection;
        private Declined(Reject rejection, String detail) {
            super(detail);
            this.rejection = rejection;
        }
    }
}
