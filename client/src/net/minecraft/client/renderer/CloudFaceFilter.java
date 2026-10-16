package net.minecraft.client.renderer;

import java.nio.ByteBuffer;
import net.minecraft.client.renderer.chunk.TerrainFrustum;
import net.minecraft.client.renderer.chunk.TerrainToggles;

/**
 * S7 of docs/stage2/TERRAIN-PLAN.md: keeps only the cloud faces whose cell can intersect the view, so the vertex
 * stage no longer shades the ~70% of cloud quads that lie outside the frustum. The filtered list is uploaded only
 * when the reference below stops covering the frame (about every 10 degrees of rotation or 6 blocks of movement
 * relative to the cloud grid), not every frame.
 *
 * <p><b>Reference.</b> A cone with its apex at the camera, axis {@code d0} = the view direction at reference time,
 * half-angle {@code beta} = the widest corner ray of that frame's view volume plus 10 degrees; and the cloud offset
 * {@code O0} (the camera-relative cloud grid origin uploaded in the cloud UBO).
 *
 * <p><b>Selection.</b> A face is kept when the bounding sphere of its 12x4x12 cell (at {@code O0}), inflated by
 * {@link #MOVE_MARGIN} + {@link #EYE_MARGIN}, intersects the cone (exact sphere/cone test). Faces keep their
 * ring order (nearest first), which matters: clouds blend and write depth.
 *
 * <p><b>Why it is exact.</b> {@link #covers} accepts a frame only if all four corner rays of its view volume lie
 * within {@code beta} of {@code d0} (so the whole view volume, the conical hull of those rays from the frame's eye,
 * lies inside eye + cone, the cone being convex for {@code beta < 90} degrees), its eye is within {@link #EYE_MARGIN}
 * of the camera, and the cloud grid moved by at most {@link #MOVE_MARGIN}. A point of a cell visible in that frame is
 * then within cell radius + margins of the reference cell centre and inside the reference cone, so the cell was kept.
 * Hurt tilt, bob and nausea are part of the projection, so they are covered by the same test.
 */
public final class CloudFaceFilter {
    public static final double WIDEN = Math.toRadians(10.0);
    public static final double MAX_HALF_ANGLE = Math.toRadians(85.0);
    public static final double MOVE_MARGIN = 6.0;
    public static final double EYE_MARGIN = 0.5;
    public static final double CELL_RADIUS = Math.sqrt(6.0 * 6.0 + 2.0 * 2.0 + 6.0 * 6.0);
    private static final double KEEP_RADIUS = CELL_RADIUS + MOVE_MARGIN + EYE_MARGIN;
    private boolean active;
    private boolean all;
    private double axisX;
    private double axisY;
    private double axisZ;
    private double cosBeta;
    private double sinBeta;
    private double refX;
    private double refY;
    private double refZ;

    public void invalidate() {
        this.active = false;
    }

    public boolean isActive() {
        return this.active;
    }

    /** True when the current upload (made by {@link #reset}) is still valid for this frame. */
    public boolean covers(final TerrainFrustum frustum, final double offsetX, final double offsetY, final double offsetZ) {
        if (!this.active) {
            return false;
        }

        double[] dirs = filterableCorners(frustum);
        if (this.all) {
            // Everything is uploaded, which is always correct; switch to a filtered list once the frame allows one.
            return dirs == null || cone(dirs) == null;
        }

        if (dirs == null) {
            return false;
        }

        double dx = offsetX - this.refX;
        double dy = offsetY - this.refY;
        double dz = offsetZ - this.refZ;
        if (!(dx * dx + dy * dy + dz * dz <= MOVE_MARGIN * MOVE_MARGIN)) {
            return false;
        }

        for (int i = 0; i < 12; i += 3) {
            if (!(dirs[i] * this.axisX + dirs[i + 1] * this.axisY + dirs[i + 2] * this.axisZ >= this.cosBeta)) {
                return false;
            }
        }

        return true;
    }

    /** Makes this frame the reference. */
    public void reset(final TerrainFrustum frustum, final double offsetX, final double offsetY, final double offsetZ) {
        this.active = true;
        this.refX = offsetX;
        this.refY = offsetY;
        this.refZ = offsetZ;
        double[] dirs = filterableCorners(frustum);
        double[] cone = dirs == null ? null : cone(dirs);
        if (cone == null) {
            this.all = true;
            return;
        }

        this.all = false;
        this.axisX = cone[0];
        this.axisY = cone[1];
        this.axisZ = cone[2];
        this.cosBeta = Math.cos(cone[3]);
        this.sinBeta = Math.sin(cone[3]);
    }

    /**
     * The widened cone {@code (axisX, axisY, axisZ, beta)} around four corner rays, or null when it would be too wide
     * to filter (half-angle of at least {@link #MAX_HALF_ANGLE}).
     */
    static double[] cone(final double[] dirs) {
        double ax = 0.0;
        double ay = 0.0;
        double az = 0.0;

        for (int i = 0; i < 12; i += 3) {
            ax += dirs[i];
            ay += dirs[i + 1];
            az += dirs[i + 2];
        }

        double len = Math.sqrt(ax * ax + ay * ay + az * az);
        if (!(len > 1.0E-6)) {
            return null;
        }

        ax /= len;
        ay /= len;
        az /= len;
        double minCos = 1.0;

        for (int i = 0; i < 12; i += 3) {
            minCos = Math.min(minCos, dirs[i] * ax + dirs[i + 1] * ay + dirs[i + 2] * az);
        }

        double beta = Math.acos(Math.max(-1.0, Math.min(1.0, minCos))) + WIDEN;
        return beta < MAX_HALF_ANGLE ? new double[]{ax, ay, az, beta} : null;
    }

    /** Whether the reference uploads every face. */
    public boolean keepsAll() {
        return this.all;
    }

    /** Selection test for the cell at {@code (cellX, cellZ)} relative to the camera's cell (cloud grid units). */
    public boolean keepCell(final int cellX, final int cellZ) {
        if (this.all) {
            return true;
        }

        double cx = cellX * 12.0 + 6.0 + this.refX;
        double cy = this.refY + 2.0;
        double cz = cellZ * 12.0 + 6.0 + this.refZ;
        double distSq = cx * cx + cy * cy + cz * cz;
        double excessSq = distSq - KEEP_RADIUS * KEEP_RADIUS;
        if (excessSq <= 0.0) {
            return true;
        }

        // The sphere subtends gamma = asin(R / d). Keep when theta <= beta + gamma, i.e. (angles in [0, pi])
        // cos(theta) >= cos(beta) cos(gamma) - sin(beta) sin(gamma); multiplied by d:
        // dot(c, axis) >= cos(beta) sqrt(d^2 - R^2) - sin(beta) R.
        double dot = cx * this.axisX + cy * this.axisY + cz * this.axisZ;
        return dot >= this.cosBeta * Math.sqrt(excessSq) - this.sinBeta * KEEP_RADIUS - 1.0E-6;
    }

    /**
     * Copies the kept faces (3 bytes each, {@code CloudRenderer.encodeFace} layout) from {@code faces} into
     * {@code out}, in order; returns the number written.
     */
    public int filter(final byte[] faces, final int faceCount, final ByteBuffer out) {
        if (TerrainToggles.cloudRunCopy) {
            return this.filterRuns(faces, faceCount, out);
        }

        int written = 0;
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        boolean lastKeep = false;

        for (int i = 0; i < faceCount; i++) {
            int at = 3 * i;
            int flags = faces[at + 2] & 255;
            int cellX = faces[at] << 1 | flags >> 7 & 1;
            int cellZ = faces[at + 1] << 1 | flags >> 6 & 1;
            if (cellX != lastX || cellZ != lastZ) {
                lastX = cellX;
                lastZ = cellZ;
                lastKeep = this.keepCell(cellX, cellZ);
            }

            if (lastKeep) {
                out.put(faces, at, 3);
                written++;
            }
        }

        return written;
    }

    /**
     * {@link #filter} with one bulk put per run of consecutive kept faces (faces of one cell are adjacent in the ring
     * order, so runs are long) instead of one 3-byte put per face; same bytes in the same order
     * ({@link TerrainToggles#cloudRunCopy}).
     */
    private int filterRuns(final byte[] faces, final int faceCount, final ByteBuffer out) {
        int written = 0;
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        boolean lastKeep = false;
        int runStart = -1;

        for (int i = 0; i < faceCount; i++) {
            int at = 3 * i;
            int flags = faces[at + 2] & 255;
            int cellX = faces[at] << 1 | flags >> 7 & 1;
            int cellZ = faces[at + 1] << 1 | flags >> 6 & 1;
            if (cellX != lastX || cellZ != lastZ) {
                lastX = cellX;
                lastZ = cellZ;
                lastKeep = this.keepCell(cellX, cellZ);
            }

            if (lastKeep) {
                if (runStart < 0) {
                    runStart = at;
                }

                written++;
            } else if (runStart >= 0) {
                out.put(faces, runStart, at - runStart);
                runStart = -1;
            }
        }

        if (runStart >= 0) {
            out.put(faces, runStart, 3 * faceCount - runStart);
        }

        return written;
    }

    /** Corner rays of the frame's view volume, or null when the frame cannot be filtered (wide cone, far eye). */
    private static double[] filterableCorners(final TerrainFrustum frustum) {
        if (!frustum.hasEye()) {
            return null;
        }

        double ex = frustum.eyeX();
        double ey = frustum.eyeY();
        double ez = frustum.eyeZ();
        if (!(ex * ex + ey * ey + ez * ez <= EYE_MARGIN * EYE_MARGIN)) {
            return null;
        }

        return frustum.cornerDirections();
    }
}
