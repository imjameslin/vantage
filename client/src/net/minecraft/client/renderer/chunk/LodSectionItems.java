package net.minecraft.client.renderer.chunk;

/** Immutable CPU metadata for one uploaded section's fixed slot and arena ranges.
 * Does not retain or mutate the mesh arrays. Fade/group/level selection stays in LodGpu.
 */
final class LodSectionItems {
    private final int[][] items = new int[3][];
    private final long[] quads = new long[3];
    private final long[] facing = new long[8];
    private final int[] section;
    /** Owned primitive-array payload only; excludes JVM object/array headers and references. */
    final long payloadBytes;

    LodSectionItems(int slot, int[] arenas, int[] firstQuad, int[][] clusters) {
        if (slot < 0 || slot >= 1 << 20 || arenas.length != 3 || firstQuad.length != 3 || clusters.length != 3)
            throw new IllegalArgumentException("Invalid section item metadata");
        int lx = 1023, ly = 1023, lz = 1023, hx = 0, hy = 0, hz = 0;
        boolean any = false, unusable = false;
        long bytes = 8L * (this.quads.length + this.facing.length);
        for (int l = 0; l < 3; l++) {
            int[] input = clusters[l];
            if (input == null || arenas[l] < 0) continue;
            if (input.length % 3 != 0) throw new IllegalArgumentException("Incomplete cluster record");
            // An empty present layer still creates its group in the scalar path.
            int[] output = new int[Math.multiplyExact(input.length / 3, 4)];
            this.items[l] = output;
            bytes += 4L * output.length;
            int base = 4 * firstQuad[l];
            for (int c = 0, at = 0; c < input.length; c += 3, at += 4) {
                int c0 = input[c], lo = input[c + 1], hi = input[c + 2];
                int q = (c0 >>> 16 & 63) + 1;
                int direction = c0 >>> 22 & 7;
                output[at] = base + 4 * (c0 & 0xFFFF);
                output[at + 1] = slot | (q - 1) << 20 | direction << 26 | (c0 >>> 25 & 1) << 29;
                output[at + 2] = lo;
                output[at + 3] = hi;
                this.quads[l] += q;
                this.facing[direction] += q;
                any = true;
                unusable |= (c0 >>> 25 & 1) != 0;
                lx = Math.min(lx, lo & 1023);
                ly = Math.min(ly, lo >>> 10 & 1023);
                lz = Math.min(lz, lo >>> 20 & 1023);
                hx = Math.max(hx, hi & 1023);
                hy = Math.max(hy, hi >>> 10 & 1023);
                hz = Math.max(hz, hi >>> 20 & 1023);
            }
        }
        this.section = any ? new int[]{0, slot | (unusable ? 1 << 29 : 0),
            lx | ly << 10 | lz << 20, hx | hy << 10 | hz << 20} : null;
        this.payloadBytes = bytes + (any ? 16 : 0);
    }

    boolean hasLayer(int layer) { return this.items[layer] != null; }
    int clusterCount(int layer) { return this.items[layer].length / 4; }
    long quadCount(int layer) { return this.quads[layer]; }
    long facingQuads(int direction) { return this.facing[direction]; }
    boolean hasSection() { return this.section != null; }
    /** Every L0 vertex is inside the packed [-32,32) bounds envelope. */
    boolean hasBoundedSection() { return this.section != null && (this.section[1] & (1 << 29)) == 0; }

    void copyLayer(int layer, int[] target, int offset) {
        int[] source = this.items[layer];
        System.arraycopy(source, 0, target, offset, source.length);
    }

    void copySection(int[] target, int offset) {
        System.arraycopy(this.section, 0, target, offset, 4);
    }
}
