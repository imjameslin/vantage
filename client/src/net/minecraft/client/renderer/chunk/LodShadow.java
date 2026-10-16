package net.minecraft.client.renderer.chunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Far shadows (Tessera): the shadow map only reaches the near terrain, so the far terrain was lit by its facing alone and
 * looked flat. Farsight's node data fills a world-anchored height map (wrapping, {@link #CELL} blocks per texel); the
 * sun and moon move in the X-Y plane, so one sweep per row along X gives each texel the height below which it lies in
 * shadow. The terrain shader compares its own height with that (one filtered lookup, tesseraFarShadow).
 */
public final class LodShadow {
    public static final boolean ENABLED = Lod.flag("mc.lod.farShadow", true);
    /** Far shadows past the shadow map: a fine image (1 block per texel, +-1024 blocks: tree shadows) and a coarse one
     * (4 blocks per texel, +-4096 blocks: hills), both swept from the same node heights ({@code mc.lod.fineShadow}). */
    public static final boolean FINE_ON = Lod.flag("mc.lod.fineShadow", true);
    public static final LodShadow COARSE = new LodShadow(2048, 4, 7);
    public static final LodShadow FINE = new LodShadow(2048, 1, 4);
    public static final int BAND = 64;
    private static final int TILE_SHIFT = 6;                 // 64 texels: a tile's texels belong to one world tile at a time
    private static final short UNKNOWN = Short.MIN_VALUE;
    private static final float NONE = -4096.0F;
    public final int n;
    public final int cell;
    /** Mip levels (averages): the far pixels read small levels instead of scattering over the whole image. */
    public final int mips;
    private final int mask;
    private final int tiles;
    private final short[] heights;
    private final byte[] levels;
    private final long[] tileTag;
    private volatile int version;

    private LodShadow(final int n, final int cell, final int mips) {
        this.n = n;
        this.cell = cell;
        this.mips = mips;
        this.mask = n - 1;
        this.tiles = n >> TILE_SHIFT;
        this.heights = new short[n * n];
        this.levels = new byte[n * n];
        this.tileTag = new long[this.tiles * this.tiles];
    }

    private static long tag(final int tileX, final int tileZ) {
        return ((long)tileX << 32 ^ tileZ & 0xFFFFFFFFL) * 2L + 1L;
    }

    /** Worker: a node's column tops into the height maps (finer levels win over coarser ones). */
    static void write(final @Nullable LodData d) {
        if (!ENABLED || d == null) {
            return;
        }
        COARSE.writeNode(d);
        if (FINE_ON) FINE.writeNode(d);
    }

    private void writeNode(final LodData d) {
        int s = 1 << d.level;
        int baseY = d.cellMinY * s;
        int[] tops = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int top = Integer.MIN_VALUE;
                for (int y = d.height - 1; y >= 0; y--) {
                    if (d.states[LodData.index(x, y, z)] != LodData.AIR_ID) {
                        top = (y + 1) * s + baseY;
                        break;
                    }
                }
                tops[z * 16 + x] = top;
            }
        }
        int x0 = d.nx * 16 * s;
        int z0 = d.nz * 16 * s;
        int span = 16 * s / this.cell;                            // texels per side (4 at level 0)
        int tx0 = Math.floorDiv(x0, this.cell);
        int tz0 = Math.floorDiv(z0, this.cell);
        boolean any = false;
        synchronized (this.heights) {
            for (int j = 0; j < span; j++) {
                for (int i = 0; i < span; i++) {
                    int top = Integer.MIN_VALUE;
                    if (s >= this.cell) {
                        top = tops[(j * this.cell / s) * 16 + i * this.cell / s];
                    } else {
                        int k = this.cell / s;
                        for (int b = 0; b < k; b++) {
                            for (int a = 0; a < k; a++) {
                                top = Math.max(top, tops[(j * k + b) * 16 + i * k + a]);
                            }
                        }
                    }
                    int tx = tx0 + i;
                    int tz = tz0 + j;
                    int tile = (tz >> TILE_SHIFT & this.tiles - 1) * this.tiles + (tx >> TILE_SHIFT & this.tiles - 1);
                    long tag = tag(tx >> TILE_SHIFT, tz >> TILE_SHIFT);
                    if (this.tileTag[tile] != tag) {
                        this.tileTag[tile] = tag;
                        int bx = tx >> TILE_SHIFT << TILE_SHIFT & this.mask;
                        int bz = tz >> TILE_SHIFT << TILE_SHIFT & this.mask;
                        for (int r = 0; r < 1 << TILE_SHIFT; r++) {
                            java.util.Arrays.fill(this.heights, (bz + r) * this.n + bx, (bz + r) * this.n + bx + (1 << TILE_SHIFT), UNKNOWN);
                            java.util.Arrays.fill(this.levels, (bz + r) * this.n + bx, (bz + r) * this.n + bx + (1 << TILE_SHIFT), (byte)127);
                        }
                    }
                    int idx = (tz & this.mask) * this.n + (tx & this.mask);
                    if (top == Integer.MIN_VALUE || this.levels[idx] < d.level) {
                        continue;
                    }
                    short h = (short)Math.max(-4000, Math.min(4000, top));
                    if (this.heights[idx] != h || this.levels[idx] != d.level) {
                        this.heights[idx] = h;
                        this.levels[idx] = (byte)d.level;
                        any = true;
                    }
                }
            }
        }
        if (any) {
            this.version++;
        }
    }

    // ------------------------------------------------------------------------------------------------ render thread side

    private @Nullable CompletableFuture<ByteBuffer[]> sweep;
    private ByteBuffer @Nullable [] ready;
    private int readyRow;
    private int sweptVersion = -1;
    private float sweptLx = 9.0F;
    private float sweptLy = 9.0F;
    private double sweptX = Double.NaN;
    private double sweptZ = Double.NaN;
    private long sweptNanos;
    private boolean uploaded;

    /** True once a whole shadow height image has been handed out by {@link #nextBand}. */
    public boolean valid() {
        return uploaded;
    }

    /**
     * Render thread, once per frame: starts a sweep when the heights, the light or the camera's window changed, and hands
     * out the finished image in bands of {@link #BAND} rows (R32F, row {@code result[0]}), or null.
     */
    public ByteBuffer @Nullable [] nextBand(final double camX, final double camZ, final float lx, final float ly, final int[] row) {
        if (!ENABLED) {
            return null;
        }
        if (sweep != null && sweep.isDone() && ready == null) {
            ready = sweep.join();
            sweep = null;
            readyRow = 0;
        }
        long now = System.nanoTime();
        if (sweep == null && ready == null && now - sweptNanos > 2_000_000_000L) {
            int v = version;
            boolean moved = Double.isNaN(sweptX) || Math.abs(camX - sweptX) > 128.0 || Math.abs(camZ - sweptZ) > 128.0;
            if (v != sweptVersion || lx != sweptLx || ly != sweptLy || moved) {
                sweptVersion = v;
                sweptLx = lx;
                sweptLy = ly;
                sweptX = camX;
                sweptZ = camZ;
                sweptNanos = now;
                int cx = (int)Math.floor(camX / this.cell);
                int cz = (int)Math.floor(camZ / this.cell);
                sweep = CompletableFuture.supplyAsync(() -> this.sweep(cx, cz, lx, ly));
            }
        }
        if (ready == null) {
            return null;
        }
        row[0] = readyRow;
        ByteBuffer[] band = new ByteBuffer[this.mips];
        for (int m = 0; m < this.mips; m++) {
            band[m] = ready[m].duplicate().order(ByteOrder.nativeOrder());
            band[m].position((readyRow >> m) * (this.n >> m) * 4).limit(((readyRow + BAND) >> m) * (this.n >> m) * 4);
        }
        readyRow += BAND;
        if (readyRow >= this.n) {
            ready = null;
            uploaded = true;
        }
        return band;
    }

    /** Per texel: the height below which the sun / moon (towards (lx, ly, 0)) is hidden by the terrain up-light of it. */
    private ByteBuffer[] sweep(final int cx, final int cz, final float lx, final float ly) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(this.n * this.n * 4).order(ByteOrder.nativeOrder());
        FloatBuffer out = bytes.asFloatBuffer();
        if (ly <= 0.01F || Math.abs(lx) < 0.02F) {
            for (int i = 0; i < this.n * this.n; i++) {
                out.put(i, NONE);
            }
            return mips(bytes);
        }
        float drop = this.cell * ly / Math.abs(lx);
        int step = lx > 0.0F ? -1 : 1;                       // away from the light
        int start = lx > 0.0F ? cx + this.n / 2 - 1 : cx - this.n / 2;
        for (int tz = cz - this.n / 2; tz < cz + this.n / 2; tz++) {
            int rowIdx = (tz & this.mask) * this.n;
            int tileRow = (tz >> TILE_SHIFT & this.tiles - 1) * this.tiles;
            float s = NONE;
            int tx = start;
            for (int i = 0; i < this.n; i++, tx += step) {
                int idx = rowIdx + (tx & this.mask);
                out.put(idx, s);
                if (this.tileTag[tileRow + (tx >> TILE_SHIFT & this.tiles - 1)] == tag(tx >> TILE_SHIFT, tz >> TILE_SHIFT)) {
                    short h = this.heights[idx];
                    if (h != UNKNOWN && h > s) {
                        s = h;
                    }
                }
                s = Math.max(NONE, s - drop);
            }
        }
        return mips(bytes);
    }

    private ByteBuffer[] mips(final ByteBuffer base) {
        ByteBuffer[] all = new ByteBuffer[this.mips];
        all[0] = base;
        for (int m = 1; m < this.mips; m++) {
            int n = this.n >> m;
            FloatBuffer src = all[m - 1].asFloatBuffer();
            all[m] = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder());
            FloatBuffer dst = all[m].asFloatBuffer();
            for (int z = 0; z < n; z++) {
                for (int x = 0; x < n; x++) {
                    int i = z * 2 * (n * 2) + x * 2;
                    dst.put(z * n + x, (src.get(i) + src.get(i + 1) + src.get(i + n * 2) + src.get(i + n * 2 + 1)) * 0.25F);
                }
            }
        }
        return all;
    }
}
