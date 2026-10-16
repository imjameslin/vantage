package net.minecraft.client.zoom;

import java.util.function.LongSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * OptiFine-style zoom key, client-only (docs/features/ZOOM.md). Glue between the game and {@link ZoomMath.State}; all
 * calls come from the render/main thread.
 *
 * <ul>
 *   <li>Camera.calculateFov -> {@link #onFrame}: once per frame, eases the zoom with real frame time and returns the
 *       FOV to render (vanilla FOV times the zoom multiplier).</li>
 *   <li>MouseHandler.turnPlayer -> {@link #mouseScale}; MouseHandler.onScroll -> {@link #onScroll} (consumes the wheel
 *       while zooming, so the hotbar slot and its packet never change).</li>
 *   <li>LevelExtractor (entity view scale, {@link #setExtraRangeFrustum}), BlockEntityRenderer.shouldRender
 *       ({@link #inExtraBlockEntityRange}), BeaconRenderer beam width, AbstractSignRenderer outline ->
 *       {@link #distanceScale}.</li>
 *   <li>SpellParticle ({@link #isKeyActive}: hides the near-eye effect swirls like vanilla does while scoping),
 *       FishingHookRenderer ({@link #keepScreenPosition}: line start stays at the rod tip).</li>
 * </ul>
 *
 * Toggles (system properties, default on): {@code mc.zoom} (the key; off = the key does nothing) and
 * {@code mc.zoomEntityDistance} (zoom/spyglass-aware distance checks; off = vanilla distances).
 */
public final class Zoom {
    public static final boolean KEY_ENABLED = flag("mc.zoom", true);
    public static final boolean DISTANCE_ENABLED = flag("mc.zoomEntityDistance", true);
    /** Block entities are tested as their block grown by this much (banners, beds, double chests reach ~2 blocks out). */
    private static final double BLOCK_ENTITY_MARGIN = 2.0;
    /** End gateway beams reach up to the build limit (TheEndGatewayRenderer): tested as the whole column. */
    private static final double BEAM_HALF_HEIGHT = 4096.0;
    private static final ZoomMath.State STATE = new ZoomMath.State();
    /** Frame clock for the easing; the bench's zoomseq swaps in its lockstep clock so captures are deterministic. */
    public static LongSupplier clockNanos = System::nanoTime;
    private static long lastFrameNanos;
    private static boolean hasLastFrame;
    private static @Nullable Frustum extraRangeFrustum;

    private Zoom() {
    }

    /** Whether the zoom key currently zooms: held, no screen open, in a world. */
    public static boolean isKeyActive(final Minecraft minecraft) {
        return KEY_ENABLED && minecraft.player != null && minecraft.gui.screen() == null && minecraft.options.keyZoom.isDown();
    }

    /** Per frame, from Camera.calculateFov (not in panoramic mode). Returns the FOV to render. */
    public static float onFrame(final Minecraft minecraft, final float vanillaFov) {
        long now = clockNanos.getAsLong();
        double dt = hasLastFrame ? (now - lastFrameNanos) * 1.0E-9 : 0.0;
        lastFrameNanos = now;
        hasLastFrame = true;
        // The camera's player, like vanilla's own spyglass FOV (Camera.tickFov): also a spectated player who scopes.
        boolean scoping = minecraft.getCameraEntity() instanceof AbstractClientPlayer player && player.isScoping();
        double optionFov = minecraft.options.fov().get().intValue();
        double fov = STATE.update(isKeyActive(minecraft), vanillaFov, optionFov, scoping, dt, DISTANCE_ENABLED);
        return fov == vanillaFov ? vanillaFov : (float)fov;
    }

    /** Mouse wheel while in a world without a screen; true = consumed by the zoom (vanilla must not see it). */
    public static boolean onScroll(final Minecraft minecraft, final double notches) {
        return STATE.scroll(isKeyActive(minecraft), notches);
    }

    /** Factor for mouse turn deltas; exactly 1 when not zoomed. */
    public static double mouseScale() {
        return STATE.mouseScale();
    }

    /** Factor for client-side render distance checks (zoom key and spyglass); exactly 1 when neither is in use. */
    public static double distanceScale() {
        return STATE.distanceScale();
    }

    /**
     * Set by LevelExtractor around entity / block entity extraction: the culling frustum that objects brought in only by
     * {@link #distanceScale} must also pass (the zoomed view plus a view-bob margin, or vanilla's wide one when the view
     * is distorted); null = no extra range this frame (vanilla distances).
     */
    public static void setExtraRangeFrustum(final @Nullable Frustum frustum) {
        extraRangeFrustum = frustum;
    }

    /** The frustum last set by {@link #setExtraRangeFrustum} (LevelExtractor.extractVisibleEntities reads it, so that
     *  method keeps vanilla's signature for Fabric mixins; docs/metal/CONTRACT.md 16.FAB). */
    public static @Nullable Frustum extraRangeFrustum() {
        return extraRangeFrustum;
    }

    /**
     * For a block entity beyond its vanilla view distance: whether the zoom brings it in (within {@code viewDistance}
     * times the distance scale and inside the zoomed view). Off-screen-rendered ones (structure / test blocks: their
     * boxes reach far from the block) skip the view test.
     */
    public static boolean inExtraBlockEntityRange(
        final Vec3 center, final Vec3 cameraPosition, final double viewDistance, final boolean renderedOffScreen, final boolean tallBeam
    ) {
        Frustum frustum = extraRangeFrustum;
        double scale = STATE.distanceScale();
        if (frustum == null || !(scale > 1.0) || !center.closerThan(cameraPosition, viewDistance * scale)) {
            return false;
        }

        if (renderedOffScreen) {
            return true;
        }

        double halfHeight = tallBeam ? BEAM_HALF_HEIGHT : 0.5 + BLOCK_ENTITY_MARGIN;
        double halfWidth = 0.5 + BLOCK_ENTITY_MARGIN;
        return frustum.isVisible(
            new AABB(center.x - halfWidth, center.y - halfHeight, center.z - halfWidth, center.x + halfWidth, center.y + halfHeight, center.z + halfWidth)
        );
    }

    /**
     * A first-person screen-anchored offset from the eye (FishingHookRenderer's line start, placed for vanilla's FOV)
     * moved so it projects to the same screen position through the zoomed projection: its sideways part relative to
     * {@code forward} shrinks by tan(zoomed / 2) / tan(vanilla / 2). Returns {@code offset} itself when not zooming.
     */
    public static Vec3 keepScreenPosition(final Vec3 offset, final Vector3fc forward) {
        double k = STATE.mouseScale();
        if (k == 1.0) {
            return offset;
        }

        double fx = forward.x();
        double fy = forward.y();
        double fz = forward.z();
        double along = offset.x * fx + offset.y * fy + offset.z * fz;
        return new Vec3(fx * along + (offset.x - fx * along) * k, fy * along + (offset.y - fy * along) * k, fz * along + (offset.z - fz * along) * k);
    }

    /** Bench contract (bench/src/bench/ZoomHook): magnification a press starts at; FOV = unzoomed FOV / factor. */
    public static void setZoomFactor(final double factor) {
        STATE.setPressZoom(factor);
    }

    public static double getZoomFactor() {
        return STATE.pressZoom();
    }

    /** Current zoom level (for debugging). */
    public static double zoomLevel() {
        return STATE.zoom();
    }

    private static boolean flag(final String name, final boolean defaultValue) {
        String value = System.getProperty(name);
        return value == null ? defaultValue : !value.equalsIgnoreCase("false") && !value.equals("0");
    }
}
