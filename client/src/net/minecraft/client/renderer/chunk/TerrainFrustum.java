package net.minecraft.client.renderer.chunk;

import org.joml.Matrix4d;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * The exact view volume of one frame, for camera-relative world positions: {@code clip = projection * modelView * p}
 * with exactly the matrices the shaders receive (docs/stage2/TERRAIN-PLAN.md S2, also used by S1 and S7).
 *
 * <p>Only the four side planes ({@code -w <= x <= w}, {@code -w <= y <= w}) and the eye plane ({@code w >= 0}) are
 * tested, never the near/far planes, so the test stays valid whatever the backend does there (clipping or depth
 * clamp). A fragment is produced only for a pixel centre inside the viewport, i.e. for a surface point in front of
 * the eye inside all four side half-spaces, so geometry entirely outside one of these five half-spaces produces no
 * fragments. Plane coefficients are computed in double from the float matrices; callers add a margin to their boxes
 * that dwarfs the GPU's float rounding.
 *
 * <p>Also provides the projection's centre (the eye, which view bob moves off the camera position) and the
 * orientation sign of the transform, which S1 needs to decide which faces are front faces.
 */
public final class TerrainFrustum {
    /**
     * Five planes {@code (a, b, c, d)}: left, right, bottom, top, eye ({@code w >= 0}); a point is inside when
     * {@code a*x + b*y + c*z + d >= 0}.
     */
    private final double[] planes = new double[20];
    private final Matrix4d matrix;
    private final double eyeX;
    private final double eyeY;
    private final double eyeZ;
    private final boolean hasEye;
    private final double determinant;

    private TerrainFrustum(final Matrix4d matrix) {
        this.matrix = matrix;
        for (int i = 0; i < 4; i++) {
            double r0 = matrix.get(i, 0);
            double r1 = matrix.get(i, 1);
            double r3 = matrix.get(i, 3);
            this.planes[i] = r3 + r0;
            this.planes[4 + i] = r3 - r0;
            this.planes[8 + i] = r3 + r1;
            this.planes[12 + i] = r3 - r1;
            this.planes[16 + i] = r3;
        }

        this.determinant = matrix.determinant();
        double ex = 0.0;
        double ey = 0.0;
        double ez = 0.0;
        boolean eye = false;
        if (Double.isFinite(this.determinant) && this.determinant != 0.0) {
            Matrix4d inverse = new Matrix4d(matrix).invert();
            // The centre of projection maps to clip (0, 0, k, 0): it is inverse * (0, 0, 1, 0) in homogeneous form.
            double hx = inverse.get(2, 0);
            double hy = inverse.get(2, 1);
            double hz = inverse.get(2, 2);
            double hw = inverse.get(2, 3);
            double scale = Math.max(Math.max(Math.abs(hx), Math.abs(hy)), Math.max(Math.abs(hz), Math.abs(hw)));
            if (Math.abs(hw) > scale * 1.0E-9 && Double.isFinite(hx / hw + hy / hw + hz / hw)) {
                ex = hx / hw;
                ey = hy / hw;
                ez = hz / hw;
                eye = true;
            }
        }

        this.eyeX = ex;
        this.eyeY = ey;
        this.eyeZ = ez;
        this.hasEye = eye;
    }

    /** {@code projection * modelView}, multiplied in double from the float matrices. */
    public static TerrainFrustum of(final Matrix4fc projection, final Matrix4fc modelView) {
        return new TerrainFrustum(product(projection, modelView));
    }

    public static Matrix4d product(final Matrix4fc a, final Matrix4fc b) {
        Matrix4d result = new Matrix4d();
        double[] m = new double[16];

        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                double sum = 0.0;

                for (int k = 0; k < 4; k++) {
                    sum += (double)a.get(k, row) * b.get(col, k);
                }

                m[col * 4 + row] = sum;
            }
        }

        return result.set(m);
    }

    /** True unless the box (camera-relative) lies entirely outside one of the five planes. NaN coordinates count as visible. */
    public boolean isBoxVisible(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
        double[] p = this.planes;

        for (int i = 0; i < 20; i += 4) {
            double a = p[i];
            double b = p[i + 1];
            double c = p[i + 2];
            double d = p[i + 3];
            double dist = a * (a >= 0.0 ? maxX : minX) + b * (b >= 0.0 ? maxY : minY) + c * (c >= 0.0 ? maxZ : minZ) + d;
            if (dist < 0.0) {
                return false;
            }
        }

        return true;
    }

    /** The 20 plane coefficients of {@link #isBoxVisible}, in its order (TerrainDrawList's unrolled copy of the test). */
    void copyPlanes(final double[] out) {
        System.arraycopy(this.planes, 0, out, 0, 20);
    }

    /** Round 12 (a): a copy of the 20 plane coefficients (tests). */
    public double[] planesCopy() {
        return this.planes.clone();
    }

    /** Whether the projection has a finite centre (false for orthographic or degenerate matrices). */
    public boolean hasEye() {
        return this.hasEye;
    }

    /** Centre of projection, camera-relative (view bob moves it off the origin). */
    public double eyeX() {
        return this.eyeX;
    }

    public double eyeY() {
        return this.eyeY;
    }

    public double eyeZ() {
        return this.eyeZ;
    }

    public double determinant() {
        return this.determinant;
    }

    /**
     * Unit directions of the four corner rays of the view volume (from the eye into the visible side), as 12 values
     * {@code x,y,z} per corner; null without a finite eye.
     */
    public double @Nullable [] cornerDirections() {
        if (!this.hasEye) {
            return null;
        }

        Matrix4d inverse = new Matrix4d(this.matrix).invert();
        double[] out = new double[12];
        int i = 0;

        for (int sy = -1; sy <= 1; sy += 2) {
            for (int sx = -1; sx <= 1; sx += 2) {
                // Any clip-space depth with w = 1 lies on the corner ray; x/w, y/w are the viewport corners.
                double hx = inverse.get(0, 0) * sx + inverse.get(1, 0) * sy + inverse.get(2, 0) * 0.5 + inverse.get(3, 0);
                double hy = inverse.get(0, 1) * sx + inverse.get(1, 1) * sy + inverse.get(2, 1) * 0.5 + inverse.get(3, 1);
                double hz = inverse.get(0, 2) * sx + inverse.get(1, 2) * sy + inverse.get(2, 2) * 0.5 + inverse.get(3, 2);
                double hw = inverse.get(0, 3) * sx + inverse.get(1, 3) * sy + inverse.get(2, 3) * 0.5 + inverse.get(3, 3);
                if (hw == 0.0 || !Double.isFinite(hw)) {
                    return null;
                }

                // Points e + t * (q - e) have clip w = t / hw, so the visible side is t * hw > 0.
                double sign = hw > 0.0 ? 1.0 : -1.0;
                double dx = (hx / hw - this.eyeX) * sign;
                double dy = (hy / hw - this.eyeY) * sign;
                double dz = (hz / hw - this.eyeZ) * sign;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (!(len > 0.0) || !Double.isFinite(len)) {
                    return null;
                }

                out[i++] = dx / len;
                out[i++] = dy / len;
                out[i++] = dz / len;
            }
        }

        return out;
    }
}
