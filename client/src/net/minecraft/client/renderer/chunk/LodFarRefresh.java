package net.minecraft.client.renderer.chunk;

/**
 * Owned camera-only refresh of a still-leased far geometry recipe. Contains no GPU resources or lease
 * and has no close/release operation. The caller retains the original recipe/allocation ownership and
 * captures projection, materials and other draw inputs at the same prepareLodReuse frame.
 */
public final class LodFarRefresh {
    private final LodFarGeometry.Source source;
    private final byte[] view;

    // LodGpu transfers a freshly allocated, completeView-validated 128-byte array; never a live view.
    LodFarRefresh(LodFarGeometry.Source source, byte[] ownedView) {
        this.source = source;
        this.view = ownedView;
    }

    public LodFarGeometry.Source source() { return this.source; }

    /** Fresh caller-owned 128-byte cull view, safe to transfer to a producer worker. */
    public byte[] view() { return this.view.clone(); }
}
