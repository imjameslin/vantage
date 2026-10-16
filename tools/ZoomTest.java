import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import net.minecraft.client.zoom.ZoomMath;

/**
 * Headless tests for the zoom key (docs/features/ZOOM.md): the pure math in {@link ZoomMath} (easing, frame-rate
 * independence, clamping, scroll steps, reset on press/release, mouse sensitivity scale, zoom-aware distance scale,
 * bit-identical vanilla output when not zooming), plus, when a built client jar is given, that the hooks are wired
 * into the patched classes (bytecode scan with java.lang.classfile). No GPU, window or game instance.
 *
 * <p>Run through tools/zoom-test.sh, which compiles ZoomMath.java on its own (no game classes on the classpath, so
 * a game dependency fails the build) and passes build/client.jar for the wiring checks if it exists.
 */
public final class ZoomTest {
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws IOException {
        constants();
        identityWhenNotZooming();
        easeInTiming();
        frameRateIndependence();
        smoothAtHighFps();
        settleAndRelease();
        dtEdgeCases();
        scrollSteps();
        scrollClampAndMinFov();
        resetOnPressAndRelease();
        sensitivity();
        magnificationAndDistance();
        distanceContinuity();
        cullPyramid();
        if (args.length > 0) {
            hookWiring(args[0]);
        } else {
            System.out.println("SKIP: hook wiring (no client jar given; run tools/build-client.sh first)");
        }

        System.out.printf("ZoomTest: %d checks, %d failures%n", checks, failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------------------------------------------------
    // helpers

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    private static double halfTan(double fov) {
        return Math.tan(Math.toRadians(fov) * 0.5);
    }

    /** Runs {@code seconds} of frames at {@code fps}; returns the last FOV. */
    private static double run(ZoomMath.State s, boolean active, double vanillaFov, double seconds, double fps) {
        int frames = (int)Math.round(seconds * fps);
        double fov = s.fov();
        for (int i = 0; i < frames; i++) {
            fov = s.update(active, vanillaFov, 70.0, false, 1.0 / fps, true);
        }

        return fov;
    }

    // ------------------------------------------------------------------------------------------------------------
    // math

    private static void constants() {
        check(ZoomMath.DEFAULT_ZOOM == 4.0, "default zoom is 4x (OptiFine FOV/4)");
        check(ZoomMath.MIN_ZOOM == 1.5 && ZoomMath.MAX_ZOOM == 50.0, "zoom range 1.5x..50x");
        check(ZoomMath.SCROLL_STEP == 1.25, "x1.25 per scroll notch");
        check(ZoomMath.MIN_FOV_DEGREES == 1.0, "zoom FOV floor 1 degree");
        // Vanilla eases the FOV modifier by 0.5 per 50 ms tick: the continuous ease must match that at tick boundaries.
        check(near(Math.exp(-0.05 / ZoomMath.EASE_TAU_SECONDS), 0.5, 1e-12), "ease time constant = vanilla 0.5 per tick");
    }

    private static void identityWhenNotZooming() {
        float[] vanilla = {70.0F, 80.5F, 110.0F * 1.15F, 30.0F, 60.0F, 7.0F, 3.0F, 0.86F, 30.0F * 0.1F / 3.0F, 95.123F};
        ZoomMath.State s = new ZoomMath.State();
        boolean exact = true;
        boolean mouseOne = true;
        boolean distOne = true;
        for (int frame = 0; frame < 2000; frame++) {
            float v = vanilla[frame % vanilla.length];
            double fov = s.update(false, v, 70.0, false, 0.001, true);
            exact &= fov == v && (float)fov == v;
            mouseOne &= s.mouseScale() == 1.0;
            distOne &= s.distanceScale() == 1.0;
        }

        check(exact, "not zooming: FOV bit-identical to vanilla (incl. sprint, water, spyglass, death values)");
        check(mouseOne, "not zooming: mouse scale exactly 1");
        check(distOne, "not zooming, not scoping: distance scale exactly 1 (also for water/bow FOV < option FOV)");
        check(!s.isZooming() && s.multiplier() == 1.0, "not zooming: multiplier exactly 1");
        check(ZoomMath.zoomedFov(70.0, 1.0) == 70.0 && ZoomMath.zoomedFov(70.0, 1.2) == 70.0, "zoomedFov identity for m >= 1");
    }

    private static void easeInTiming() {
        // 150 ms after pressing: ln m = -(1 - 2^-3) ln 4 (vanilla spyglass: 87.5 % after 3 ticks).
        double expectedMul = Math.pow(4.0, -0.875);
        for (double fps : new double[]{1000.0, 600.0, 240.0, 120.0, 60.0, 20.0}) {
            ZoomMath.State s = new ZoomMath.State();
            double fov = run(s, true, 70.0, 0.15, fps);
            check(near(fov / 70.0, expectedMul, 1e-9), "ease-in at " + fps + " FPS: multiplier after 150 ms = 4^-0.875 (got " + fov / 70.0 + ")");
        }
    }

    private static void frameRateIndependence() {
        // Press 300 ms, release 300 ms: sampled at the 60 FPS frame times, 1000 FPS and 60 FPS agree.
        ZoomMath.State fast = new ZoomMath.State();
        ZoomMath.State slow = new ZoomMath.State();
        double maxDiff = 0.0;
        for (int i = 1; i <= 36; i++) {
            boolean active = i <= 18;
            double slowFov = slow.update(active, 70.0, 70.0, false, 1.0 / 60.0, true);
            double fastFov = 0.0;
            for (int k = 0; k < 50; k++) { // 1000/60 frames per 60 FPS frame, done as 50 frames of 1/3000 s
                fastFov = fast.update(active, 70.0, 70.0, false, 1.0 / 3000.0, true);
            }

            maxDiff = Math.max(maxDiff, Math.abs(slowFov - fastFov));
        }

        check(maxDiff < 1e-9, "frame-rate independent: 3000 FPS vs 60 FPS max FOV difference " + maxDiff);
    }

    private static void smoothAtHighFps() {
        // At 1000 FPS every frame of the animation moves (no per-tick staircase: a per-tick lerp would hold the FOV for
        // 49 frames and jump on the 50th), and no frame moves more than the analytic bound ln(4) * dt / tau in log space.
        ZoomMath.State s = new ZoomMath.State();
        double prev = s.update(false, 70.0, 70.0, false, 0.001, true);
        double settledFov = 70.0 * Math.exp(-Math.log(4.0));
        double bound = Math.log(4.0) * 0.001 / ZoomMath.EASE_TAU_SECONDS + 1e-12;
        boolean strictlyDown = true;
        boolean noOvershoot = true;
        double maxLogStep = 0.0;
        for (int i = 0; i < 1000; i++) {
            double fov = s.update(true, 70.0, 70.0, false, 0.001, true);
            if (fov != settledFov && !(fov < prev)) {
                strictlyDown = false;
            }

            noOvershoot &= fov >= 17.5 - 1e-9;
            maxLogStep = Math.max(maxLogStep, Math.log(prev / fov));
            prev = fov;
        }

        check(strictlyDown, "1000 FPS ease-in: FOV changes every frame until settled");
        check(prev == settledFov, "1000 FPS ease-in settles exactly within 1 s");
        check(noOvershoot, "ease-in never overshoots 70/4");
        check(maxLogStep <= bound, "1000 FPS ease-in: largest per-frame step " + maxLogStep + " <= ln4*dt/tau " + bound);
        boolean strictlyUp = true;
        noOvershoot = true;
        for (int i = 0; i < 300; i++) {
            double fov = s.update(false, 70.0, 70.0, false, 0.001, true);
            if (s.isZooming() && !(fov > prev)) {
                strictlyUp = false;
            }

            noOvershoot &= fov <= 70.0;
            prev = fov;
        }

        check(strictlyUp, "1000 FPS ease-out: FOV changes every frame until settled");
        check(noOvershoot, "ease-out never overshoots vanilla FOV");
    }

    private static void settleAndRelease() {
        ZoomMath.State s = new ZoomMath.State();
        double fov = run(s, true, 70.0, 2.0, 1000.0);
        check(near(fov, 17.5, 1e-9), "held 2 s: FOV = 70/4 (got " + fov + ")");
        check(s.isZooming(), "held: zooming");
        fov = run(s, false, 70.0, 0.15, 1000.0);
        check(fov > 17.5 && fov < 70.0, "150 ms after release: between zoomed and vanilla");
        fov = run(s, false, 70.0, 2.0, 1000.0);
        check(fov == 70.0, "2 s after release: FOV exactly vanilla (snapped)");
        check(!s.isZooming() && s.multiplier() == 1.0 && s.mouseScale() == 1.0, "after release: multiplier and mouse scale exactly 1");
        // Composes multiplicatively with vanilla's own FOV changes (sprint) while held.
        s = new ZoomMath.State();
        run(s, true, 70.0, 2.0, 1000.0);
        fov = s.update(true, 80.5, 70.0, false, 0.001, true);
        check(near(fov, 80.5 / 4.0, 1e-9), "zoom multiplies onto vanilla FOV changes (sprint 80.5 -> 20.125)");
    }

    private static void dtEdgeCases() {
        ZoomMath.State s = new ZoomMath.State();
        s.update(true, 70.0, 70.0, false, 0.0, true);
        check(s.fov() == 70.0, "dt = 0: no movement");
        s.update(true, 70.0, 70.0, false, -5.0, true);
        check(s.fov() == 70.0, "dt < 0: no movement");
        s.update(true, 70.0, 70.0, false, Double.NaN, true);
        check(s.fov() == 70.0 && !Double.isNaN(s.fov()), "dt = NaN: no movement, no NaN");
        s.update(true, 70.0, 70.0, false, 10.0, true);
        check(near(s.fov(), 17.5, 1e-9), "dt = 10 s (hitch): lands on target");
        check(ZoomMath.ease(-1.0, -1.0, 0.001) == -1.0, "ease at target stays");
        check(ZoomMath.ease(0.0, -0.00005, 0.0) == -0.00005, "ease snaps within epsilon");
    }

    private static void scrollSteps() {
        ZoomMath.State s = new ZoomMath.State();
        check(!s.scroll(false, 1.0) && s.zoom() == 4.0, "scroll while not zooming: not consumed, level unchanged");
        s.update(true, 70.0, 70.0, false, 0.001, true);
        check(s.scroll(true, 1.0) && near(s.zoom(), 5.0, 1e-12), "scroll up one notch: 4 -> 5");
        check(s.scroll(true, 1.0) && near(s.zoom(), 6.25, 1e-12), "scroll up again: 5 -> 6.25");
        check(s.scroll(true, -3.0) && near(s.zoom(), 3.2, 1e-12), "scroll down three: 6.25 -> 3.2");
        check(s.scroll(true, 0.0) && near(s.zoom(), 3.2, 1e-12), "zero scroll while zooming: consumed, unchanged");
        check(s.scroll(true, 0.5) && near(s.zoom(), 3.2 * Math.sqrt(1.25), 1e-12), "fractional (trackpad) scroll zooms fractionally");
        check(s.scroll(true, Double.NaN) && near(s.zoom(), 3.2 * Math.sqrt(1.25), 1e-12), "NaN scroll: consumed, unchanged");
        double fov = run(s, true, 70.0, 2.0, 1000.0);
        check(near(fov, 70.0 / s.zoom(), 1e-9), "FOV follows the scrolled level");
    }

    private static void scrollClampAndMinFov() {
        ZoomMath.State s = new ZoomMath.State();
        s.update(true, 70.0, 70.0, false, 0.001, true);
        s.scroll(true, 1000.0);
        check(s.zoom() == 50.0, "scroll in clamps at 50x (FOV 70)");
        s.scroll(true, -1000.0);
        check(s.zoom() == 1.5, "scroll out clamps at 1.5x");
        // FOV option 30: 50x would be 0.6 degrees -> clamped to 30x (1 degree).
        check(ZoomMath.maxZoom(30.0) == 30.0, "maxZoom(30) = 30");
        s.scroll(true, 1000.0);
        s.update(true, 30.0, 30.0, false, 0.001, true);
        s.scroll(true, 1000.0);
        check(s.zoom() == 30.0, "scroll in at FOV 30 clamps at 30x");
        double fov = run(s, true, 30.0, 2.0, 1000.0);
        check(near(fov, 1.0, 1e-9), "FOV never below 1 degree from the zoom (got " + fov + ")");
        // Spyglass (vanilla 7 degrees) + zoom key: clamp at 7x, and scrolling out of a clamped level responds at once.
        ZoomMath.State t = new ZoomMath.State();
        t.update(true, 70.0, 70.0, false, 0.001, true);
        t.scroll(true, 1000.0);
        t.update(true, 7.0, 70.0, true, 0.001, true);
        fov = run(t, true, 7.0, 2.0, 1000.0);
        check(near(fov, 1.0, 1e-9), "spyglass + 50x: FOV floored at 1 degree");
        t.scroll(true, -1.0);
        check(near(t.zoom(), 7.0 / 1.25, 1e-12), "scroll out from a clamped level: first notch takes effect (7 -> 5.6)");
        check(ZoomMath.zoomedFov(0.8, 0.25) == 0.8, "vanilla FOV already below 1 degree: zoom leaves it alone");
        check(ZoomMath.zoomedFov(3.0, 0.1) == 1.0, "zoomedFov floors at 1 degree");
        check(ZoomMath.maxZoom(1.2) == 1.5 && ZoomMath.maxZoom(Double.NaN) == 50.0 && ZoomMath.maxZoom(0.0) == 50.0, "maxZoom edge cases");
    }

    private static void resetOnPressAndRelease() {
        ZoomMath.State s = new ZoomMath.State();
        s.update(true, 70.0, 70.0, false, 0.001, true);
        s.scroll(true, 2.0);
        check(near(s.zoom(), 6.25, 1e-12), "level 6.25 while held");
        s.update(false, 70.0, 70.0, false, 0.001, true);
        check(s.zoom() == 4.0, "release resets level to 4x");
        s.update(true, 70.0, 70.0, false, 0.001, true);
        double fov = run(s, true, 70.0, 2.0, 1000.0);
        check(near(fov, 17.5, 1e-9), "next press zooms to 4x again");
        // Press and scroll before the first frame of the press sees the key (scroll edge resets first).
        ZoomMath.State t = new ZoomMath.State();
        t.update(true, 70.0, 70.0, false, 0.001, true);
        t.scroll(true, 3.0);
        t.scroll(false, 0.0);                 // released (seen by the scroll handler)
        t.scroll(true, 1.0);                  // pressed again and scrolled before any frame
        check(near(t.zoom(), 5.0, 1e-12), "press edge seen by scroll: level reset to 4 then stepped to 5");
        t.update(false, 70.0, 70.0, false, 0.001, true);
        t.scroll(true, 0.0);
        check(t.zoom() == 4.0, "press edge after release: level 4x");
    }

    private static void sensitivity() {
        check(ZoomMath.sensitivityScale(70.0, 70.0) == 1.0, "sensitivity 1 when not zoomed");
        check(ZoomMath.sensitivityScale(70.0, 80.0) == 1.0, "sensitivity 1 when wider");
        check(ZoomMath.sensitivityScale(70.0, Double.NaN) == 1.0 && ZoomMath.sensitivityScale(70.0, 0.0) == 1.0, "sensitivity edge cases");
        double s4 = ZoomMath.sensitivityScale(70.0, 17.5);
        check(near(s4, halfTan(17.5) / halfTan(70.0), 1e-15) && near(s4, 0.2198, 2e-4), "70 -> 17.5: scale tan ratio 0.2198 (got " + s4 + ")");
        // Constant on-screen motion at the screen centre: pixels per radian = (h/2) / tan(fov/2).
        for (double zoom : new double[]{1.5, 4.0, 10.0, 50.0}) {
            double vanilla = 70.0;
            double zoomed = ZoomMath.zoomedFov(vanilla, 1.0 / zoom);
            double turn = Math.toRadians(0.05);
            double pxUnzoomed = Math.tan(turn) / halfTan(vanilla);
            double pxZoomed = Math.tan(turn * ZoomMath.sensitivityScale(vanilla, zoomed)) / halfTan(zoomed);
            check(near(pxZoomed / pxUnzoomed, 1.0, 1e-4), zoom + "x: on-screen motion per mouse count constant (ratio " + pxZoomed / pxUnzoomed + ")");
        }

        // Spyglass (vanilla FOV ~7) + zoom key: only the zoom key's part is compensated (vanilla already cut the
        // sensitivity by 8 for the spyglass), so the extra scale is ~1/4, not ~1/40.
        ZoomMath.State s = new ZoomMath.State();
        run(s, true, 7.0, 2.0, 1000.0);
        check(near(s.mouseScale(), 0.25, 0.002), "spyglass + 4x zoom: extra mouse scale ~0.25 (got " + s.mouseScale() + ")");
        ZoomMath.State t = new ZoomMath.State();
        run(t, false, 7.0, 2.0, 1000.0);
        check(t.mouseScale() == 1.0, "spyglass alone: no extra mouse scale (vanilla handles it)");
    }

    private static void magnificationAndDistance() {
        check(ZoomMath.magnification(70.0, 70.0) == 1.0, "magnification 1 at option FOV");
        check(ZoomMath.magnification(70.0, 80.0) == 1.0, "magnification 1 when wider (sprint)");
        check(ZoomMath.magnification(70.0, Double.NaN) == 1.0 && ZoomMath.magnification(70.0, 0.0) == 1.0, "magnification edge cases");
        double m4 = ZoomMath.magnification(70.0, 17.5);
        check(near(m4, 1.0 / ZoomMath.sensitivityScale(70.0, 17.5), 1e-12) && m4 > 4.0, "4x zoom at FOV 70: magnification " + m4 + " (>= FOV ratio)");
        double mSpy = ZoomMath.magnification(70.0, 7.0);
        check(mSpy > 10.0 && near(mSpy, halfTan(70.0) / halfTan(7.0), 1e-12), "spyglass at FOV 70: magnification " + mSpy);
        boolean inverse = true;
        for (double scale : new double[]{1.0001, 1.25, 4.55, 11.45, 160.0}) {
            for (double fov : new double[]{1.0, 7.0, 17.5, 59.5}) {
                inverse &= near(ZoomMath.magnification(ZoomMath.referenceFov(scale, fov), fov), scale, 1e-9 * scale);
            }
        }

        check(inverse, "referenceFov inverts magnification");
        check(ZoomMath.referenceFov(1.0, 60.0) == 60.0, "referenceFov(1, fov) = fov exactly");

        // Zoom key engages the distance scale.
        ZoomMath.State s = new ZoomMath.State();
        run(s, true, 70.0, 2.0, 1000.0);
        check(near(s.distanceScale(), m4, 1e-9), "zoom key held: distance scale = magnification");
        ZoomMath.State off = new ZoomMath.State();
        for (int i = 0; i < 2000; i++) {
            off.update(true, 70.0, 70.0, false, 0.001, false);
        }

        check(off.distanceScale() == 1.0, "-Dmc.zoomEntityDistance=false: distance scale exactly 1 while zoomed");

        // Spyglass engages it; on release it follows vanilla's FOV ease-back exactly (vanilla: fovModifier += (1 - m) / 2
        // per tick, frames lerp between ticks), then is exactly 1.
        ZoomMath.State spy = new ZoomMath.State();
        for (int i = 0; i < 20; i++) {
            spy.update(false, 70.0, 70.0, false, 0.001, true);
        }

        for (int i = 0; i < 500; i++) {
            spy.update(false, 7.0, 70.0, true, 0.001, true);
        }

        check(near(spy.distanceScale(), mSpy, 1e-9), "spyglass: distance scale = magnification");
        boolean exactDuringEase = true;
        double oldMod = 0.1;
        double mod = 0.1;
        for (int i = 0; i < 400; i++) { // 1000 FPS, 50 frames per tick
            if (i % 50 == 0) {
                oldMod = mod;
                mod = mod + (1.0 - mod) * 0.5;
            }

            double vanillaFov = 70.0 * (oldMod + (mod - oldMod) * (i % 50) / 50.0);
            spy.update(false, vanillaFov, 70.0, false, 0.001, true);
            exactDuringEase &= near(spy.distanceScale(), ZoomMath.magnification(70.0, vanillaFov), 1e-9);
        }

        check(exactDuringEase, "spyglass released: distance scale = on-screen magnification through vanilla's FOV ease-back (no entity pop)");
        for (int i = 0; i < 1000; i++) {
            spy.update(false, 70.0, 70.0, false, 0.001, true);
        }

        check(spy.distanceScale() == 1.0, "spyglass released and settled: distance scale exactly 1");
        // Spyglass use timing out in the player tick: vanilla's FOV only starts easing back on the next tick.
        ZoomMath.State timeout = new ZoomMath.State();
        for (int i = 0; i < 500; i++) {
            timeout.update(false, 7.0, 70.0, true, 0.001, true);
        }

        boolean held = true;
        for (int i = 0; i < 50; i++) {
            timeout.update(false, 7.0, 70.0, false, 0.001, true);
            held &= near(timeout.distanceScale(), mSpy, 1e-9);
        }

        check(held, "spyglass ends a tick before vanilla's FOV moves: distance scale held (no pop while still magnified)");
        // Spectating a scoping player: same as scoping (Zoom passes the camera entity's isScoping).
        // Underwater (vanilla FOV 60 < option 70) without zoom/spyglass: vanilla distances.
        ZoomMath.State water = new ZoomMath.State();
        for (int i = 0; i < 100; i++) {
            water.update(false, 60.0, 70.0, false, 0.001, true);
        }

        check(water.distanceScale() == 1.0, "underwater / bow FOV without zoom: distance scale exactly 1");
    }

    /** Largest |ln s| change between consecutive frames over {@code frames} frames. */
    private static double maxLogStep(ZoomMath.State s, int frames, boolean active, double vanillaFov, boolean scoping, double[] last) {
        double max = 0.0;
        for (int i = 0; i < frames; i++) {
            s.update(active, vanillaFov, 70.0, scoping, 0.001, true);
            max = Math.max(max, Math.abs(Math.log(s.distanceScale()) - Math.log(last[0])));
            last[0] = s.distanceScale();
        }

        return max;
    }

    private static void distanceContinuity() {
        // A frame's ln s may move by at most what the zoom ease itself moves the FOV in a frame (4x: ln 4 * dt / tau;
        // the tan ratio a bit more than the FOV ratio): no step from engaging/disengaging (bow drawn: vanilla FOV 59.5 <
        // option 70; a jump to the option-relative magnification would be ln 1.25 = 0.22).
        double bound = Math.log(4.0) * 0.001 / ZoomMath.EASE_TAU_SECONDS * 1.5;
        double bowFov = 70.0 * 0.85;
        ZoomMath.State s = new ZoomMath.State();
        double[] last = {1.0};
        double max = maxLogStep(s, 200, false, bowFov, false, last);
        check(s.distanceScale() == 1.0 && max == 0.0, "bow drawn, no zoom: distance scale exactly 1");
        s.update(true, bowFov, 70.0, false, 0.001, true);
        check(Math.log(s.distanceScale()) <= bound, "bow drawn, first zoomed frame: distance scale " + s.distanceScale() + " (one ease step), no jump to 1.25");
        last[0] = s.distanceScale();
        max = maxLogStep(s, 2000, true, bowFov, false, last);
        check(max <= bound, "bow drawn, zoom in: per-frame ln s step " + max + " <= " + bound);
        check(near(s.distanceScale(), ZoomMath.magnification(bowFov, bowFov / 4.0), 1e-9), "bow drawn + 4x: scale relative to the bow view");
        max = maxLogStep(s, 3000, false, bowFov, false, last);
        check(max <= bound, "bow drawn, zoom released: per-frame ln s step " + max + " <= " + bound + " (no drop at the end of the hold)");
        check(s.distanceScale() == 1.0, "bow drawn, zoom released and settled: distance scale exactly 1");

        // Bow drawn while zoomed: vanilla's FOV ends narrower than when the zoom began. After release the scale holds,
        // then fades to exactly 1 with the ease time constant (no step).
        ZoomMath.State t = new ZoomMath.State();
        last[0] = 1.0;
        maxLogStep(t, 100, false, 70.0, false, last);
        maxLogStep(t, 1000, true, 70.0, false, last);
        double fadeBound = bound;
        max = 0.0;
        for (int i = 0; i < 150; i++) { // vanilla FOV eases 70 -> 59.5 while zoomed
            double v = 59.5 + 10.5 * Math.pow(0.5, (i + 1) / 50.0);
            max = Math.max(max, maxLogStep(t, 1, true, v, false, last));
        }

        max = Math.max(max, maxLogStep(t, 600, true, bowFov, false, last));
        max = Math.max(max, maxLogStep(t, 3000, false, bowFov, false, last));
        check(max <= fadeBound, "bow drawn while zoomed, then released: per-frame ln s step " + max + " <= " + fadeBound);
        check(t.distanceScale() == 1.0, "bow drawn while zoomed: scale fades to exactly 1 after release");
        // Pressing again mid-fade continues from the current value.
        ZoomMath.State u = new ZoomMath.State();
        last[0] = 1.0;
        maxLogStep(u, 100, false, 70.0, false, last);
        maxLogStep(u, 1000, true, 70.0, false, last);
        maxLogStep(u, 300, true, bowFov, false, last);
        maxLogStep(u, 900, false, bowFov, false, last);
        double before = u.distanceScale();
        check(before > 1.0, "mid-fade: scale still > 1 (" + before + ")");
        max = maxLogStep(u, 2000, true, bowFov, false, last);
        check(max <= bound, "pressed again mid-fade: per-frame ln s step " + max + " <= " + bound);
        // Spyglass with slowness (vanilla FOV 63 before scoping): no jump when scoping starts.
        ZoomMath.State v = new ZoomMath.State();
        last[0] = 1.0;
        maxLogStep(v, 100, false, 63.0, false, last);
        v.update(false, 63.0 * 0.995, 70.0, true, 0.001, true);
        check(near(v.distanceScale(), 1.0, 0.01), "slowness + spyglass, first frame: scale ~1 (got " + v.distanceScale() + ")");
        // FOV option lowered while zoomed: reference never above the option.
        ZoomMath.State w = new ZoomMath.State();
        run(w, true, 70.0, 1.0, 1000.0);
        w.update(true, 30.0, 30.0, false, 0.001, true);
        check(w.distanceScale() <= ZoomMath.magnification(30.0, w.fov()) + 1e-9, "FOV option lowered: reference capped at the option");
    }

    private static void cullPyramid() {
        double[] out = new double[3];
        check(!ZoomMath.cullPyramid(Double.NaN, 1.6, 0.0, out) && !ZoomMath.cullPyramid(0.0, 1.6, 0.0, out)
            && !ZoomMath.cullPyramid(70.0, 0.0, 0.0, out) && !ZoomMath.cullPyramid(70.0, Double.POSITIVE_INFINITY, 0.0, out),
            "cullPyramid rejects invalid input");
        check(!ZoomMath.cullPyramid(170.0, 1.6, 0.0, out), "cullPyramid: too wide -> caller uses vanilla's frustum");
        check(ZoomMath.cullPyramid(7.0, 1.6, 0.0, out) && out[1] > halfTan(7.0) && out[1] < halfTan(7.2) && out[2] < 0.5,
            "spyglass, no bob: pyramid hugs the view (tanY " + out[1] + ", setback " + out[2] + ")");
        check(ZoomMath.cullPyramid(7.0, 1.6, 0.1, out) && out[1] < halfTan(70.0) && out[0] < 1.6 * halfTan(70.0) * 0.25,
            "spyglass, full bob: still far narrower than the unzoomed frustum (tanX " + out[0] + ")");

        // Every point the bobbed projection puts on screen (GameRenderer: projection * translate * rotZ * rotX, bob <= 0.1)
        // lies inside the padded pyramid with its apex moved back. Points on the screen border (corners included), at
        // depths from 0.3 to 3000 blocks, both rotation signs.
        int bad = 0;
        int tested = 0;
        String firstBad = null;
        for (double fov : new double[]{1.0, 1.4, 3.0, 7.0, 17.5, 46.7, 70.0, 82.0}) {
            for (double aspect : new double[]{0.5, 1.0, 1.6, 16.0 / 9.0, 2.4}) {
                for (double bob : new double[]{0.0, 0.02, 0.05, 0.1}) {
                    if (!ZoomMath.cullPyramid(fov, aspect, bob, out)) {
                        continue;
                    }

                    double tanY = halfTan(fov);
                    double tanX = tanY * aspect;
                    for (double walk = 0.0; walk < 2.0; walk += 0.0625) {
                        double sin = Math.sin(walk * Math.PI);
                        double cos = Math.cos(walk * Math.PI);
                        double tx = sin * bob * 0.5;
                        double ty = -Math.abs(cos * bob);
                        double rz = Math.toRadians(sin * bob * 3.0);
                        double rx = Math.toRadians(Math.abs(Math.cos(walk * Math.PI - 0.2) * bob) * 5.0);
                        for (int sign = -1; sign <= 1; sign += 2) {
                            for (int e = 0; e < 40; e++) {
                                double t = e / 10.0; // perimeter of the screen, 4 edges
                                double nx = t < 1 ? -1 + 2 * t : t < 2 ? 1 : t < 3 ? 1 - 2 * (t - 2) : -1;
                                double ny = t < 1 ? -1 : t < 2 ? -1 + 2 * (t - 1) : t < 3 ? 1 : 1 - 2 * (t - 3);
                                for (double depth : new double[]{0.3, 1.0, 5.0, 64.0, 700.0, 3000.0}) {
                                    // q: bobbed view space (camera looks down -z), on the screen border.
                                    double qx = nx * tanX * depth;
                                    double qy = ny * tanY * depth;
                                    double qz = -depth;
                                    // p = Rx^-1 Rz^-1 (q - t)
                                    double ax = qx - tx;
                                    double ay = qy - ty;
                                    double az = qz;
                                    double c = Math.cos(-sign * rz);
                                    double sn = Math.sin(-sign * rz);
                                    double bx = ax * c - ay * sn;
                                    double by = ax * sn + ay * c;
                                    double bz = az;
                                    c = Math.cos(-sign * rx);
                                    sn = Math.sin(-sign * rx);
                                    double px = bx;
                                    double py = by * c - bz * sn;
                                    double pz = by * sn + bz * c;
                                    double w = out[2] - pz; // distance in front of the moved-back apex
                                    tested++;
                                    if (!(w > 0.0) || Math.abs(px) > out[0] * w || Math.abs(py) > out[1] * w) {
                                        bad++;
                                        if (firstBad == null) {
                                            firstBad = "fov " + fov + " aspect " + aspect + " bob " + bob + " walk " + walk + " ndc " + nx + "," + ny + " depth " + depth;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        check(bad == 0, "zoom culling pyramid contains every bobbed on-screen point (" + tested + " tested, " + bad + " outside" + (firstBad == null ? "" : "; first: " + firstBad) + ")");
    }

    // ------------------------------------------------------------------------------------------------------------
    // hook wiring in the built jar

    private static final String ZOOM = "net/minecraft/client/zoom/Zoom";

    private static void hookWiring(String jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath)) {
            check(jar.getEntry("net/minecraft/client/zoom/ZoomMath.class") != null, "jar contains ZoomMath");
            check(jar.getEntry("net/minecraft/client/zoom/ZoomMath$State.class") != null, "jar contains ZoomMath$State");
            check(jar.getEntry(ZOOM + ".class") != null, "jar contains Zoom");

            List<String> fov = calls(jar, "net/minecraft/client/Camera", "calculateFov");
            check(indexOf(fov, ZOOM + ".onFrame") > indexOf(fov, "net/minecraft/client/Camera.modifyFovBasedOnDeathOrFluid")
                && indexOf(fov, "net/minecraft/client/Camera.modifyFovBasedOnDeathOrFluid") >= 0,
                "Camera.calculateFov: Zoom.onFrame applied after vanilla's death/fluid FOV");
            List<String> hud = calls(jar, "net/minecraft/client/Camera", "calculateHudFov");
            check(indexOf(hud, ZOOM + ".onFrame") < 0, "Camera.calculateHudFov: hand FOV not zoomed");
            List<String> cull = calls(jar, "net/minecraft/client/Camera", "createProjectionMatrixForCulling");
            check(cull.stream().noneMatch(c -> c.startsWith(ZOOM + ".")), "Camera.createProjectionMatrixForCulling untouched");

            List<String> scroll = calls(jar, "net/minecraft/client/MouseHandler", "onScroll");
            int zoomScroll = indexOf(scroll, ZOOM + ".onScroll");
            check(zoomScroll >= 0, "MouseHandler.onScroll calls Zoom.onScroll");
            check(zoomScroll < indexOf(scroll, "net/minecraft/client/ScrollWheelHandler.onMouseScroll")
                && zoomScroll < indexOf(scroll, "net/minecraft/world/entity/player/Inventory.setSelectedSlot")
                && zoomScroll > indexOf(scroll, "net/minecraft/client/gui/screens/Screen.mouseScrolled"),
                "MouseHandler.onScroll: zoom consumes the wheel before the hotbar/spectator handling, after the screen path");
            List<String> turn = calls(jar, "net/minecraft/client/MouseHandler", "turnPlayer");
            check(indexOf(turn, ZOOM + ".mouseScale") >= 0
                && indexOf(turn, ZOOM + ".mouseScale") < indexOf(turn, "net/minecraft/client/player/LocalPlayer.turn"),
                "MouseHandler.turnPlayer scales by Zoom.mouseScale before LocalPlayer.turn");

            String extractor = "net/minecraft/client/renderer/extract/LevelExtractor";
            List<String> ents = calls(jar, extractor, "extractVisibleEntities");
            check(indexOf(ents, ZOOM + ".distanceScale") >= 0
                && count(ents, "net/minecraft/world/entity/Entity.setViewScale") == 3
                && count(ents, extractor + ".isEntityVisible") == 2,
                "LevelExtractor.extractVisibleEntities: vanilla test first, then the scaled distance against the zoom frustum");
            List<String> extract = calls(jar, extractor, "extract");
            int setFrustum = indexOf(extract, ZOOM + ".setExtraRangeFrustum");
            check(setFrustum >= 0 && setFrustum < indexOf(extract, extractor + ".extractVisibleEntities")
                && extract.lastIndexOf(ZOOM + ".setExtraRangeFrustum") > indexOf(extract, extractor + ".extractVisibleBlockEntities")
                && indexOf(extract, extractor + ".zoomExtraRangeFrustum") >= 0,
                "LevelExtractor.extract: zoom frustum set before entities, cleared after block entities");
            check(indexOf(calls(jar, extractor, "zoomExtraRangeFrustum"), "net/minecraft/client/zoom/ZoomMath.cullPyramid") >= 0
                && indexOf(calls(jar, extractor, "zoomExtraRangeFrustum"), "net/minecraft/client/Camera.getFov") >= 0,
                "LevelExtractor.zoomExtraRangeFrustum: built from the camera's (zoomed) FOV via ZoomMath.cullPyramid");
            check(calls(jar, extractor, "extractBlockDestroyAnimation").stream().noneMatch(c -> c.startsWith(ZOOM + ".")),
                "LevelExtractor.extractBlockDestroyAnimation is vanilla (server sends crack progress within 32 blocks only)");
            List<String> beRender = calls(jar, "net/minecraft/client/renderer/blockentity/BlockEntityRenderer", "shouldRender");
            check(indexOf(beRender, ZOOM + ".inExtraBlockEntityRange") > indexOf(beRender, "net/minecraft/world/phys/Vec3.closerThan")
                && indexOf(beRender, "net/minecraft/world/phys/Vec3.closerThan") >= 0,
                "BlockEntityRenderer.shouldRender (default): vanilla distance first, then Zoom.inExtraBlockEntityRange");
            check(indexOf(calls(jar, ZOOM, "inExtraBlockEntityRange"), "net/minecraft/client/renderer/culling/Frustum.isVisible") >= 0,
                "Zoom.inExtraBlockEntityRange tests the zoom frustum");
            List<String> spell = calls(jar, "net/minecraft/client/particle/SpellParticle", "isCloseToScopingPlayer");
            check(indexOf(spell, ZOOM + ".isKeyActive") > indexOf(spell, "net/minecraft/client/player/LocalPlayer.isScoping")
                && indexOf(spell, "net/minecraft/client/player/LocalPlayer.isScoping") >= 0,
                "SpellParticle.isCloseToScopingPlayer: also while the zoom key is held");
            List<String> hook = calls(jar, "net/minecraft/client/renderer/entity/FishingHookRenderer", "getPlayerHandPos");
            check(indexOf(hook, ZOOM + ".keepScreenPosition") >= 0
                && indexOf(hook, ZOOM + ".keepScreenPosition") < indexOf(hook, "net/minecraft/world/phys/Vec3.add"),
                "FishingHookRenderer.getPlayerHandPos: first-person line start kept on screen while zoomed");
            List<String> frame = calls(jar, ZOOM, "onFrame");
            check(indexOf(frame, "net/minecraft/client/Minecraft.getCameraEntity") >= 0
                && indexOf(frame, "get:net/minecraft/client/Minecraft.player") < 0,
                "Zoom.onFrame: spyglass of the camera entity (spectated players too)");
            check(indexOf(calls(jar, "net/minecraft/client/renderer/blockentity/BeaconRenderer", "extract"), ZOOM + ".distanceScale") >= 0,
                "BeaconRenderer.extract uses Zoom.distanceScale");
            check(indexOf(calls(jar, "net/minecraft/client/renderer/blockentity/AbstractSignRenderer", "isOutlineVisible"), ZOOM + ".distanceScale") >= 0,
                "AbstractSignRenderer.isOutlineVisible uses Zoom.distanceScale");

            ClassModel options = parse(jar, "net/minecraft/client/Options");
            boolean hasField = false;
            for (FieldModel f : options.fields()) {
                hasField |= f.fieldName().equalsString("keyZoom") && f.fieldType().equalsString("Lnet/minecraft/client/KeyMapping;");
            }

            check(hasField, "Options.keyZoom field");
            List<String> init = calls(jar, "net/minecraft/client/Options", "<init>");
            check(indexOf(init, "get:net/minecraft/client/Options.keyZoom") >= 0, "Options.<init> puts keyZoom into keyMappings (saved, shown in Controls)");
            check(indexOf(calls(jar, ZOOM, "isKeyActive"), "get:net/minecraft/client/Options.keyZoom") >= 0, "Zoom reads Options.keyZoom");

            ZipEntry lang = jar.getEntry("assets/minecraft/lang/en_us.json");
            String json = lang == null ? "" : new String(jar.getInputStream(lang).readAllBytes(), StandardCharsets.UTF_8);
            check(json.contains("\"key.zoom\": \"Zoom\""), "en_us.json has key.zoom = Zoom");
            check(json.contains("\"key.saveToolbarActivator\""), "en_us.json still has the vanilla keys (merged, not replaced)");
        }
    }

    private static ClassModel parse(JarFile jar, String internalName) throws IOException {
        ZipEntry e = jar.getEntry(internalName + ".class");
        if (e == null) {
            throw new IOException("missing " + internalName);
        }

        try (InputStream in = jar.getInputStream(e)) {
            return ClassFile.of().parse(in.readAllBytes());
        }
    }

    /** Method calls ("owner.name") and field reads ("get:owner.name") of every method named {@code method}, in order. */
    private static List<String> calls(JarFile jar, String owner, String method) throws IOException {
        List<String> out = new ArrayList<>();
        boolean found = false;
        for (MethodModel m : parse(jar, owner).methods()) {
            if (!m.methodName().equalsString(method) || m.code().isEmpty()) {
                continue;
            }

            found = true;
            for (CodeElement el : m.code().get()) {
                if (el instanceof InvokeInstruction inv) {
                    out.add(inv.owner().asInternalName() + "." + inv.name().stringValue());
                } else if (el instanceof FieldInstruction fi && fi.opcode().name().startsWith("GET")) {
                    out.add("get:" + fi.owner().asInternalName() + "." + fi.name().stringValue());
                }
            }
        }

        check(found, "method " + owner + "." + method + " exists");
        return out;
    }

    private static int indexOf(List<String> calls, String call) {
        return calls.indexOf(call);
    }

    private static int count(List<String> calls, String call) {
        int n = 0;
        for (String c : calls) {
            if (c.equals(call)) {
                n++;
            }
        }

        return n;
    }
}
