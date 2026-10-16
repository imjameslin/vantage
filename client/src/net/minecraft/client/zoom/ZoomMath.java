package net.minecraft.client.zoom;

/**
 * Zoom math with no game dependencies (docs/features/ZOOM.md), tested headlessly by tools/ZoomTest.java. {@link Zoom}
 * feeds it the per-frame inputs (key state, vanilla FOV, option FOV, spyglass, real frame time) and hands its outputs
 * to the hooks in Camera, MouseHandler, LevelExtractor and the block entity renderers.
 *
 * <p>FOV values are vertical angles in degrees. The zoom key's effect is a multiplier {@code m <= 1} on vanilla's FOV
 * (which already contains sprint/flying/bow/spyglass/fluid/death effects), eased per frame in log space so every
 * zoom step (4x, 5x, 6.25x, ...) animates at the same perceived speed. When the key is up and the animation has
 * settled, {@code m} is exactly 1 and every output is bit-identical to vanilla.
 */
public final class ZoomMath {
    /** Zoom level on key press (OptiFine: FOV / 4); reset on every press and release. */
    public static final double DEFAULT_ZOOM = 4.0;
    public static final double MIN_ZOOM = 1.5;
    public static final double MAX_ZOOM = 50.0;
    /** Zoom factor per scroll notch (scaled scroll offset 1.0; fractional trackpad offsets zoom fractionally). */
    public static final double SCROLL_STEP = 1.25;
    /** The zoom key never narrows the FOV below this (vanilla alone may: spyglass + death + water). */
    public static final double MIN_FOV_DEGREES = 1.0;
    /**
     * Time constant of the exponential ease. Vanilla eases its FOV modifier (spyglass, sprint) by 0.5 per 50 ms tick;
     * the continuous equivalent is 0.05 / ln 2 = 72 ms: 87.5 % of the way after 150 ms, at any frame rate.
     */
    public static final double EASE_TAU_SECONDS = 0.05 / Math.log(2.0);
    /** |ln m| below this (0.01 % of the FOV) snaps to the target, so a released zoom returns exactly to m = 1. */
    public static final double SNAP_EPSILON = 1.0E-4;
    /**
     * After the zoom key's animation or the spyglass ended, the distance scale follows the (shrinking) on-screen
     * magnification for this long, never rising, and only then fades to exactly 1 with {@link #EASE_TAU_SECONDS}.
     * Vanilla's spyglass FOV takes ~300 ms to ease back (and starts a tick late when the use times out), so far
     * entities are not dropped while the view is still magnified.
     */
    public static final double DISTANCE_HOLD_SECONDS = 0.4;
    /**
     * View bob (GameRenderer.bobView, bob <= 0.1) rotates the view by roll 3 b and pitch 5 b degrees at most, so by
     * less than 8 b degrees in all; this many degrees per unit of bob pad the zoom culling pyramid.
     */
    public static final double BOB_ROTATION_DEGREES_PER_BOB = 8.0;
    /** View bob moves the eye by (0.5 b, b) at most: |t| <= 1.118 b. */
    public static final double BOB_TRANSLATION_PER_BOB = 1.12;
    /** Extra padding of the zoom culling pyramid on top of the bob bound (float rounding of matrices, slack). */
    public static final double CULL_SLACK_DEGREES = 0.05;
    public static final double CULL_SLACK_BLOCKS = 0.01;
    /** Largest padded half-angle for which a narrowed culling pyramid is built (beyond: use vanilla's wide one). */
    public static final double MAX_CULL_HALF_ANGLE_DEGREES = 85.0;

    private ZoomMath() {
    }

    /** Largest zoom level that keeps {@code vanillaFov / zoom >= MIN_FOV_DEGREES}, within [MIN_ZOOM, MAX_ZOOM]. */
    public static double maxZoom(final double vanillaFov) {
        if (!(vanillaFov > 0.0) || Double.isInfinite(vanillaFov)) {
            return MAX_ZOOM;
        }

        return clamp(vanillaFov / MIN_FOV_DEGREES, MIN_ZOOM, MAX_ZOOM);
    }

    /** New zoom level after {@code notches} scroll notches (positive = zoom in), clamped to [MIN_ZOOM, maxZoom]. */
    public static double stepZoom(final double zoom, final double notches, final double vanillaFov) {
        double max = maxZoom(vanillaFov);
        // Start from the effective (clamped) level so scrolling out of a clamped state responds on the first notch.
        double start = clamp(Double.isNaN(zoom) ? DEFAULT_ZOOM : zoom, MIN_ZOOM, max);
        if (Double.isNaN(notches) || Double.isInfinite(notches)) {
            return start;
        }

        return clamp(start * Math.pow(SCROLL_STEP, notches), MIN_ZOOM, max);
    }

    /**
     * Frame-rate independent exponential approach of {@code current} to {@code target} over {@code dtSeconds} of real
     * time; snaps to the target once within {@link #SNAP_EPSILON}. Non-positive or NaN dt changes nothing.
     */
    public static double ease(final double current, final double target, final double dtSeconds) {
        if (current == target) {
            return target;
        }

        if (!(dtSeconds > 0.0)) {
            return Math.abs(target - current) < SNAP_EPSILON ? target : current;
        }

        double next = current + (target - current) * -Math.expm1(-dtSeconds / EASE_TAU_SECONDS);
        return Math.abs(target - next) < SNAP_EPSILON ? target : next;
    }

    /** ln of the FOV multiplier the animation heads for: {@code -ln zoom} while zooming, 0 otherwise. */
    public static double targetLogMultiplier(final boolean zooming, final double zoom) {
        return zooming ? -Math.log(zoom) : 0.0;
    }

    /** FOV multiplier for an eased log multiplier; exactly 1 for 0. */
    public static double multiplier(final double logMultiplier) {
        return logMultiplier == 0.0 ? 1.0 : Math.exp(logMultiplier);
    }

    /**
     * The FOV actually rendered: vanilla's FOV times the zoom multiplier, never narrower than
     * {@link #MIN_FOV_DEGREES} because of the zoom (a vanilla FOV already below it is left alone). Returns
     * {@code vanillaFov} unchanged for {@code multiplier >= 1}.
     */
    public static double zoomedFov(final double vanillaFov, final double multiplier) {
        if (!(multiplier < 1.0)) {
            return vanillaFov;
        }

        return Math.max(vanillaFov * multiplier, Math.min(vanillaFov, MIN_FOV_DEGREES));
    }

    /**
     * Mouse turn scale that keeps on-screen motion per mouse count constant at the screen centre: angular speed
     * proportional to tan(fov / 2). Compares against vanilla's FOV (spyglass included), so the spyglass part is not
     * reduced twice (vanilla already divides the sensitivity by 8 while scoping). 1 when not zoomed.
     */
    public static double sensitivityScale(final double vanillaFov, final double zoomedFov) {
        if (!(zoomedFov < vanillaFov) || !(zoomedFov > 0.0)) {
            return 1.0;
        }

        double scale = halfTan(zoomedFov) / halfTan(vanillaFov);
        return scale > 0.0 && scale < 1.0 ? scale : 1.0;
    }

    /**
     * On-screen magnification of the current view relative to a reference FOV: tan(reference / 2) / tan(current / 2),
     * at least 1. Used as the zoom-aware entity / block entity distance scale.
     */
    public static double magnification(final double referenceFov, final double currentFov) {
        if (!(currentFov > 0.0) || !(currentFov < referenceFov)) {
            return 1.0;
        }

        double m = halfTan(referenceFov) / halfTan(currentFov);
        return m > 1.0 && !Double.isInfinite(m) ? m : 1.0;
    }

    /** The reference FOV for which {@code magnification(reference, currentFov) == scale} (inverse of magnification). */
    public static double referenceFov(final double scale, final double currentFov) {
        if (!(scale > 1.0) || !(currentFov > 0.0)) {
            return currentFov;
        }

        return Math.toDegrees(2.0 * Math.atan(scale * halfTan(currentFov)));
    }

    /**
     * Culling pyramid for the entities / block entities that only the zoom-aware distance scale brings in: the view
     * actually on screen (vertical FOV {@code fovDegrees}, {@code aspect} = width / height), widened so it contains
     * every view that vanilla's view bob ({@code bob}, 0 when off) can turn or shift it to. Writes {tan of the padded
     * horizontal half-angle, tan of the padded vertical half-angle, how far to move the apex back along the view
     * direction} into {@code out} and returns true, or returns false when the padded pyramid would be too wide to be
     * worth it (callers then use vanilla's culling frustum).
     *
     * <p>A rotation by at most {@code a} moves every direction by at most {@code a}. Within the other (padded) pair of
     * planes, a direction at angular distance {@code a} from a side plane is at most {@code asin(sin a / cos(otherHalf))}
     * beyond that plane's half-angle, so each half-angle is padded by that (iterated: the pads depend on each other).
     * A shift of the eye by at most {@code r} is covered by moving the apex back by {@code r / tanMin + r} (the shifted
     * pyramid is then inside the padded one). tools/ZoomTest.java checks containment against GameRenderer's bob.
     */
    public static boolean cullPyramid(final double fovDegrees, final double aspect, final double bob, final double[] out) {
        if (!(fovDegrees > 0.0) || !(fovDegrees < 180.0) || !(aspect > 0.0) || Double.isInfinite(aspect)) {
            return false;
        }

        double b = Double.isFinite(bob) ? Math.min(Math.abs(bob), 1.0) : 1.0;
        double alpha = Math.toRadians(BOB_ROTATION_DEGREES_PER_BOB * b + CULL_SLACK_DEGREES);
        double r = BOB_TRANSLATION_PER_BOB * b + CULL_SLACK_BLOCKS;
        double halfY = Math.toRadians(fovDegrees) * 0.5;
        double halfX = Math.atan(aspect * Math.tan(halfY));
        double max = Math.toRadians(MAX_CULL_HALF_ANGLE_DEGREES);
        double padX = alpha;
        double padY = alpha;
        for (int i = 0; i < 4; i++) {
            double cosX = Math.cos(halfX + padX);
            double cosY = Math.cos(halfY + padY);
            if (!(halfX + padX < max) || !(halfY + padY < max) || !(cosX > 0.0) || !(cosY > 0.0)) {
                return false;
            }

            double nextPadY = Math.asin(Math.min(1.0, Math.sin(alpha) / cosX));
            double nextPadX = Math.asin(Math.min(1.0, Math.sin(alpha) / cosY));
            padX = Math.max(padX, nextPadX);
            padY = Math.max(padY, nextPadY);
        }

        // One more widening step on top of the converged pads keeps the result conservative.
        padX *= 1.01;
        padY *= 1.01;
        if (!(halfX + padX < max) || !(halfY + padY < max)) {
            return false;
        }

        double tanX = Math.tan(halfX + padX);
        double tanY = Math.tan(halfY + padY);
        out[0] = tanX;
        out[1] = tanY;
        out[2] = r / Math.min(tanX, tanY) + r;
        return true;
    }

    private static double halfTan(final double fovDegrees) {
        return Math.tan(Math.toRadians(fovDegrees) * 0.5);
    }

    private static double clamp(final double v, final double lo, final double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /**
     * Per-frame zoom state machine (one instance in {@link Zoom}). Inputs are plain values, so tests drive it with
     * synthetic frame times.
     */
    public static final class State {
        private double zoom = DEFAULT_ZOOM;
        /** Level a press starts at ({@link #DEFAULT_ZOOM}; the bench sets 10 to match the spyglass). */
        private double pressZoom = DEFAULT_ZOOM;
        private double logMultiplier;
        private boolean wasActive;
        private double lastVanillaFov = 70.0;
        private double fov = 70.0;
        private double mouseScale = 1.0;
        private double distanceScale = 1.0;
        /** Invariant after every frame: {@code distanceScale == magnification(distanceReferenceFov, fov)}. */
        private double distanceReferenceFov = 70.0;
        private double secondsSinceEngaged = Double.POSITIVE_INFINITY;

        /**
         * Advances one frame and returns the FOV to render.
         *
         * @param active          zoom key held, no screen open, player present
         * @param vanillaFov      vanilla's FOV for this frame (all vanilla effects applied)
         * @param optionFov       the player's FOV option (upper bound of the distance scale's reference FOV)
         * @param scoping         the camera's player is using a spyglass (engages the distance scale like the zoom key)
         * @param dtSeconds       real time since the previous frame
         * @param distanceEnabled zoom-aware distance checks on ({@code -Dmc.zoomEntityDistance})
         */
        public double update(
            final boolean active, final double vanillaFov, final double optionFov, final boolean scoping, final double dtSeconds, final boolean distanceEnabled
        ) {
            this.edge(active);
            this.lastVanillaFov = vanillaFov;
            this.logMultiplier = ease(this.logMultiplier, targetLogMultiplier(active, this.zoom), dtSeconds);
            this.fov = zoomedFov(vanillaFov, ZoomMath.multiplier(this.logMultiplier));
            this.mouseScale = sensitivityScale(vanillaFov, this.fov);
            this.updateDistanceScale(distanceEnabled && (this.logMultiplier != 0.0 || scoping), distanceEnabled, optionFov, dtSeconds);
            return this.fov;
        }

        /**
         * Zoom-aware distance scale, continuous in every case (no entity pops from the scale itself):
         *
         * <ul>
         *   <li>Resting (not engaged, scale 1): exactly 1; the reference FOV tracks the current FOV, capped at the FOV
         *       option, so engaging starts from exactly the view on screen (a drawn bow, slowness or water FOV below the
         *       option does not make the scale jump on the first zoomed frame).</li>
         *   <li>Engaged (zoom key animating/held or spyglass): the magnification relative to that frozen reference.</li>
         *   <li>Released: follows the shrinking magnification but never rises, for {@link #DISTANCE_HOLD_SECONDS};
         *       then additionally fades to exactly 1 in log space with {@link #EASE_TAU_SECONDS} (only matters when
         *       vanilla's FOV narrowed during the zoom, e.g. a bow drawn while zoomed). The reference is re-derived
         *       from the scale, so engaging again mid-fade continues from the current value.</li>
         * </ul>
         */
        private void updateDistanceScale(final boolean engaged, final boolean enabled, final double optionFov, final double dtSeconds) {
            if (!enabled) {
                this.distanceScale = 1.0;
                this.distanceReferenceFov = Math.min(optionFov, this.fov);
                this.secondsSinceEngaged = Double.POSITIVE_INFINITY;
                return;
            }

            if (this.distanceReferenceFov > optionFov) {
                this.distanceReferenceFov = optionFov;
            }

            double following = magnification(this.distanceReferenceFov, this.fov);
            if (engaged) {
                this.secondsSinceEngaged = 0.0;
                this.distanceScale = following;
                return;
            }

            if (dtSeconds > 0.0) {
                this.secondsSinceEngaged += dtSeconds;
            }

            double scale = Math.min(following, this.distanceScale);
            if (scale > 1.0 && this.secondsSinceEngaged >= DISTANCE_HOLD_SECONDS) {
                double faded = ease(Math.log(this.distanceScale), 0.0, dtSeconds);
                scale = Math.min(scale, faded == 0.0 ? 1.0 : Math.exp(faded));
            }

            if (!(scale > 1.0)) {
                this.distanceScale = 1.0;
                this.distanceReferenceFov = Math.min(optionFov, this.fov);
            } else {
                this.distanceScale = scale;
                if (scale != following) {
                    this.distanceReferenceFov = Math.min(optionFov, referenceFov(scale, this.fov));
                }
            }
        }

        /** A scroll of {@code notches} while {@code active}: changes the zoom level and returns true (consumed). */
        public boolean scroll(final boolean active, final double notches) {
            this.edge(active);
            if (!active) {
                return false;
            }

            this.zoom = stepZoom(this.zoom, notches, this.lastVanillaFov);
            return true;
        }

        /** Every press and every release resets the zoom level to the press level ({@link #DEFAULT_ZOOM}). */
        private void edge(final boolean active) {
            if (active != this.wasActive) {
                this.wasActive = active;
                this.zoom = this.pressZoom;
            }
        }

        /** Sets the press level (clamped to [MIN_ZOOM, MAX_ZOOM]); also the current level while the key is up. */
        public void setPressZoom(final double zoom) {
            this.pressZoom = Double.isNaN(zoom) ? DEFAULT_ZOOM : clamp(zoom, MIN_ZOOM, MAX_ZOOM);
            if (!this.wasActive) {
                this.zoom = this.pressZoom;
            }
        }

        public double pressZoom() {
            return this.pressZoom;
        }

        public double zoom() {
            return this.zoom;
        }

        public double multiplier() {
            return ZoomMath.multiplier(this.logMultiplier);
        }

        public double fov() {
            return this.fov;
        }

        public double mouseScale() {
            return this.mouseScale;
        }

        public double distanceScale() {
            return this.distanceScale;
        }

        public boolean isZooming() {
            return this.logMultiplier != 0.0;
        }
    }
}
