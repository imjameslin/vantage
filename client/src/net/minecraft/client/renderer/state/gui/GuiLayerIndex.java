package net.minecraft.client.renderer.state.gui;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.jspecify.annotations.Nullable;

/**
 * Round 6 G1 (docs/metal/CONTRACT.md 16.G1): an exact spatial index for {@code GuiRenderState}'s layering search.
 *
 * <p><b>Why.</b> Every GUI element added through {@code GuiRenderState.findAppropriateNode} whose bounds are not inside
 * the previous element's walks the node chain of the last stratum from the top down and asks, per node, "does any element
 * of this node intersect my bounds?" ({@code hasIntersection} over the node's element, item, text and picture-in-picture
 * lists: a linear scan). A node that collects many mutually disjoint elements makes this quadratic. The F3 debug overlay's
 * FPS and TPS charts are exactly that: ~240 one-pixel-wide bars per chart (plus the TPS chart's extra bars), each a
 * {@code fill} next to the previous one (never inside it), all landing in the node above the chart background, so every
 * bar scans all bars before it. Measured (JFR, F3 + FPS chart, fixed view): 62 % of the render thread's Java samples
 * in {@code hasIntersection} / {@code navigateToAboveHighestElementWithIntersectingBounds}, ~0.3 ms per frame.
 *
 * <p><b>What.</b> Per node, once it holds {@link #THRESHOLD} intersect-tested states:
 * <ol>
 *   <li>a column hull: for every GUI x column in {@link #COL_MIN}..{@link #COL_MAX}, the smallest top and the largest
 *       bottom of the registered rectangles (width and height &gt;= 0, at most {@link #MAX_COL_SPAN} columns wide; a
 *       zero-width one is recorded in its column l) that cover it. A query with positive width and height &gt;= 0 whose
 *       columns' hulls all miss its y range intersects none of them (a chart bar in a fresh column: one array read; the
 *       TPS chart's empty 0-pixel bars too). Rectangles the hull does not cover (negative size, out of range, too wide)
 *       are kept in {@code nonColumn} and tested one by one;</li>
 *   <li>otherwise (built on the first such query) a uniform grid of {@link #CELL}-pixel cells maps each cell to the rectangles registered in it (every
 *       cell their closed coordinate range touches); the query tests the rectangles of its cells. Rectangles covering
 *       more than {@link #MAX_CELLS} cells or outside {@link #GRID_MIN}..{@link #GRID_MAX} are tested one by one.</li>
 * </ol>
 * Every final hit is decided by vanilla's own {@link ScreenRectangle#intersects}.
 *
 * <p><b>Exact.</b> The answer is a boolean (vanilla's {@code ||} over the four lists has no side effect besides
 * {@code bounds()}, which every indexed state type caches or stores). {@code intersects} is {@code a.l < b.r && a.r > b.l}
 * per axis (also for a negative width or height).
 * Hull: for a registered rectangle r (l &lt;= r.r, t &lt;= b, in the column range) and a query q (q.l &lt; q.r,
 * q.t &lt;= q.b), {@code r.intersects(q)} means {@code l < q.r, r.r > q.l, t < q.b, b > q.t}: for l &lt; r.r some column
 * c in [max(l, q.l), min(r.r, q.r)) is shared; for l == r.r, q.l &lt; l &lt; q.r, so column l is visited. The hull of
 * that column has top &lt;= t &lt; q.b and bottom &gt;= b &gt; q.t, so the hull test succeeds there: the filter never
 * rejects a true answer (also for zero heights).
 * Grid: if {@code intersects} holds, the closed ranges {@code [min(l, r), max(l, r)]} overlap on both axes (if they did
 * not, one of the strict inequalities would fail), so the two share a grid cell, whose list holds the rectangle.
 * Bounds are read once, when the state is registered; an index lives for one frame's node and is returned to a pool when
 * {@code GuiRenderState.reset} drops the nodes. States without bounds are never registered (vanilla skips them).
 *
 * <p><b>Toggle.</b> {@code -Dmc.gui.layerIndex} (default on; {@code false} or {@code 0} = vanilla's linear scan).
 * {@link #enabled} is volatile and read at every query (render thread): switchable at any frame boundary (abab) and even
 * mid-frame, since both paths give the same answer and the index is built from the node's lists on first use.
 */
public final class GuiLayerIndex {
    public static volatile boolean enabled = !"false".equalsIgnoreCase(System.getProperty("mc.gui.layerIndex", "true"))
        && !"0".equals(System.getProperty("mc.gui.layerIndex"));
    /** Intersect-tested states in a node before its index is built (below: vanilla's scan is cheaper). */
    static final int THRESHOLD = 8;
    static final int CELL_SHIFT = 4;
    static final int CELL = 1 << CELL_SHIFT;
    static final int MAX_CELLS = 64;
    /** Grid extent in GUI pixels (cells outside it: tested one by one). Wide enough for any window at GUI scale 1. */
    static final int GRID_MIN = -1024;
    static final int GRID_MAX = 16383;
    private static final int COLS = (GRID_MAX - GRID_MIN + 1) >> CELL_SHIFT;
    /** Column hull range (GUI x) and the widest rectangle it records. */
    static final int COL_MIN = -256;
    static final int COL_MAX = 4095;
    static final int MAX_COL_SPAN = 512;
    private static final int COL_COUNT = COL_MAX - COL_MIN + 1;
    private static final int POOL_MAX = 8;
    private static final ArrayDeque<GuiLayerIndex> POOL = new ArrayDeque<>();

    // Column hull (touched range colLo..colHi, reset on release).
    private final int[] colTop = new int[COL_COUNT];
    private final int[] colBottom = new int[COL_COUNT];
    private int colLo = Integer.MAX_VALUE;
    private int colHi = Integer.MIN_VALUE;
    private ScreenRectangle[] nonColumn = new ScreenRectangle[8];
    private int nonColumnCount;
    // Grid: cell key -> rectangles, open addressing over long keys, values are growable arrays.
    private long[] keys = new long[64];
    private ScreenRectangle[][] cells = new ScreenRectangle[64][];
    private int[] counts = new int[64];
    private int used;
    private ScreenRectangle[] unbounded = new ScreenRectangle[8];
    private int unboundedCount;
    // Every registered rectangle (the grid's source when it is built lazily).
    private ScreenRectangle[] all = new ScreenRectangle[32];
    private int allCount;
    private boolean gridBuilt;

    GuiLayerIndex() {
        Arrays.fill(this.keys, Long.MIN_VALUE);
        Arrays.fill(this.colTop, Integer.MAX_VALUE);
        Arrays.fill(this.colBottom, Integer.MIN_VALUE);
    }

    /** A cleared index (pooled; render thread, like every GuiRenderState). */
    static GuiLayerIndex obtain() {
        synchronized (POOL) {
            GuiLayerIndex index = POOL.pollFirst();
            if (index != null) {
                return index;
            }
        }
        return new GuiLayerIndex();
    }

    /** Clears this index and pools it; it must not be used afterwards. */
    void release() {
        if (this.colLo <= this.colHi) {
            Arrays.fill(this.colTop, this.colLo, this.colHi + 1, Integer.MAX_VALUE);
            Arrays.fill(this.colBottom, this.colLo, this.colHi + 1, Integer.MIN_VALUE);
        }
        this.colLo = Integer.MAX_VALUE;
        this.colHi = Integer.MIN_VALUE;
        Arrays.fill(this.nonColumn, 0, this.nonColumnCount, null);
        this.nonColumnCount = 0;
        if (this.used > 0) {
            for (int s = 0; s < this.keys.length; s++) {
                if (this.keys[s] != Long.MIN_VALUE) {
                    this.keys[s] = Long.MIN_VALUE;
                    Arrays.fill(this.cells[s], 0, this.counts[s], null);
                    this.counts[s] = 0;
                }
            }
            this.used = 0;
        }
        Arrays.fill(this.unbounded, 0, this.unboundedCount, null);
        this.unboundedCount = 0;
        Arrays.fill(this.all, 0, this.allCount, null);
        this.allCount = 0;
        this.gridBuilt = false;
        synchronized (POOL) {
            if (POOL.size() < POOL_MAX) {
                POOL.addFirst(this);
            }
        }
    }

    void addAll(final @Nullable List<? extends ScreenArea> states) {
        if (states != null) {
            for (int i = 0, n = states.size(); i < n; i++) {
                this.add(states.get(i).bounds());
            }
        }
    }

    void add(final @Nullable ScreenRectangle r) {
        if (r == null) {
            return;
        }
        int l = r.left();
        int rr = r.right();
        int t = r.top();
        int b = r.bottom();
        // Column hull.
        if (l <= rr && t <= b && l >= COL_MIN && Math.max(rr - 1, l) <= COL_MAX && rr - l <= MAX_COL_SPAN) {
            int c0 = l - COL_MIN;
            int c1 = Math.max(rr - 1, l) - COL_MIN;   // a zero-width rectangle is recorded in column l
            for (int c = c0; c <= c1; c++) {
                if (t < this.colTop[c]) {
                    this.colTop[c] = t;
                }
                if (b > this.colBottom[c]) {
                    this.colBottom[c] = b;
                }
            }
            if (c0 < this.colLo) {
                this.colLo = c0;
            }
            if (c1 > this.colHi) {
                this.colHi = c1;
            }
        } else {
            if (this.nonColumnCount == this.nonColumn.length) {
                this.nonColumn = Arrays.copyOf(this.nonColumn, this.nonColumnCount * 2);
            }
            this.nonColumn[this.nonColumnCount++] = r;
        }
        if (this.allCount == this.all.length) {
            this.all = Arrays.copyOf(this.all, this.allCount * 2);
        }
        this.all[this.allCount++] = r;
        if (this.gridBuilt) {
            this.gridAdd(r);
        }
    }

    /** The grid is built on the first query the column hull cannot answer (nodes of chart bars never need it). */
    private void gridAdd(final ScreenRectangle r) {
        int l = r.left();
        int rr = r.right();
        int t = r.top();
        int b = r.bottom();
        int x0 = Math.min(l, rr);
        int x1 = Math.max(l, rr);
        int y0 = Math.min(t, b);
        int y1 = Math.max(t, b);
        if (x0 < GRID_MIN || y0 < GRID_MIN || x1 > GRID_MAX || y1 > GRID_MAX) {
            this.addUnbounded(r);
            return;
        }
        int cx0 = (x0 - GRID_MIN) >> CELL_SHIFT;
        int cx1 = (x1 - GRID_MIN) >> CELL_SHIFT;
        int cy0 = (y0 - GRID_MIN) >> CELL_SHIFT;
        int cy1 = (y1 - GRID_MIN) >> CELL_SHIFT;
        if ((long) (cx1 - cx0 + 1) * (cy1 - cy0 + 1) > MAX_CELLS) {
            this.addUnbounded(r);
            return;
        }
        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                this.put((long) cy * COLS + cx, r);
            }
        }
    }

    boolean intersectsAny(final ScreenRectangle q) {
        int l = q.left();
        int rr = q.right();
        int t = q.top();
        int b = q.bottom();
        if (l < rr && t <= b) {
            // Column hull filter (exact negative): no hull hit and no non-column rectangle hit = no intersection.
            boolean maybe = false;
            int c0 = Math.max(l - COL_MIN, this.colLo);
            int c1 = Math.min(rr - 1 - COL_MIN, this.colHi);
            for (int c = c0; c <= c1; c++) {
                if (this.colTop[c] < b && this.colBottom[c] > t) {
                    maybe = true;
                    break;
                }
            }
            if (!maybe) {
                for (int i = 0; i < this.nonColumnCount; i++) {
                    if (this.nonColumn[i].intersects(q)) {
                        return true;
                    }
                }
                return false;
            }
        }
        if (!this.gridBuilt) {
            this.gridBuilt = true;
            for (int i = 0; i < this.allCount; i++) {
                this.gridAdd(this.all[i]);
            }
        }
        return this.gridIntersects(q, l, rr, t, b);
    }

    private boolean gridIntersects(final ScreenRectangle q, final int l, final int rr, final int t, final int b) {
        for (int i = 0; i < this.unboundedCount; i++) {
            if (this.unbounded[i].intersects(q)) {
                return true;
            }
        }
        if (this.used == 0) {
            return false;
        }
        // Clamp the query's closed range to the grid: a registered rectangle lies inside the grid, so a shared cell is
        // inside the clamped range too.
        int x0 = Math.max(Math.min(l, rr), GRID_MIN);
        int x1 = Math.min(Math.max(l, rr), GRID_MAX);
        int y0 = Math.max(Math.min(t, b), GRID_MIN);
        int y1 = Math.min(Math.max(t, b), GRID_MAX);
        if (x0 > x1 || y0 > y1) {
            return false;
        }
        int cx0 = (x0 - GRID_MIN) >> CELL_SHIFT;
        int cx1 = (x1 - GRID_MIN) >> CELL_SHIFT;
        int cy0 = (y0 - GRID_MIN) >> CELL_SHIFT;
        int cy1 = (y1 - GRID_MIN) >> CELL_SHIFT;
        if ((long) (cx1 - cx0 + 1) * (cy1 - cy0 + 1) > (long) this.used * 2L) {
            // A query wider than the occupied cells: visit the occupied cells instead (same rectangles, same test).
            for (int s = 0; s < this.keys.length; s++) {
                long k = this.keys[s];
                if (k != Long.MIN_VALUE) {
                    int cx = (int) (k % COLS);
                    int cy = (int) (k / COLS);
                    if (cx >= cx0 && cx <= cx1 && cy >= cy0 && cy <= cy1 && this.cellHit(s, q)) {
                        return true;
                    }
                }
            }
            return false;
        }
        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                int s = this.find((long) cy * COLS + cx);
                if (s >= 0 && this.cellHit(s, q)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean cellHit(final int slot, final ScreenRectangle q) {
        ScreenRectangle[] list = this.cells[slot];
        for (int i = 0, n = this.counts[slot]; i < n; i++) {
            if (list[i].intersects(q)) {
                return true;
            }
        }
        return false;
    }

    private void addUnbounded(final ScreenRectangle r) {
        if (this.unboundedCount == this.unbounded.length) {
            this.unbounded = Arrays.copyOf(this.unbounded, this.unboundedCount * 2);
        }
        this.unbounded[this.unboundedCount++] = r;
    }

    private static int hash(final long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }

    private int find(final long key) {
        int mask = this.keys.length - 1;
        for (int s = hash(key) & mask; ; s = (s + 1) & mask) {
            long k = this.keys[s];
            if (k == key) {
                return s;
            }
            if (k == Long.MIN_VALUE) {
                return -1;
            }
        }
    }

    private void put(final long key, final ScreenRectangle r) {
        if ((this.used + 1) * 2 > this.keys.length) {
            this.rehash();
        }
        int mask = this.keys.length - 1;
        int s = hash(key) & mask;
        while (true) {
            long k = this.keys[s];
            if (k == key) {
                break;
            }
            if (k == Long.MIN_VALUE) {
                this.keys[s] = key;
                if (this.cells[s] == null) {
                    this.cells[s] = new ScreenRectangle[4];
                }
                this.used++;
                break;
            }
            s = (s + 1) & mask;
        }
        ScreenRectangle[] list = this.cells[s];
        int n = this.counts[s];
        if (n == list.length) {
            list = Arrays.copyOf(list, n * 2);
            this.cells[s] = list;
        }
        list[n] = r;
        this.counts[s] = n + 1;
    }

    private void rehash() {
        long[] oldKeys = this.keys;
        ScreenRectangle[][] oldCells = this.cells;
        int[] oldCounts = this.counts;
        int cap = oldKeys.length * 2;
        this.keys = new long[cap];
        Arrays.fill(this.keys, Long.MIN_VALUE);
        this.cells = new ScreenRectangle[cap][];
        this.counts = new int[cap];
        int mask = cap - 1;
        for (int i = 0; i < oldKeys.length; i++) {
            long k = oldKeys[i];
            if (k != Long.MIN_VALUE) {
                int s = hash(k) & mask;
                while (this.keys[s] != Long.MIN_VALUE) {
                    s = (s + 1) & mask;
                }
                this.keys[s] = k;
                this.cells[s] = oldCells[i];
                this.counts[s] = oldCounts[i];
            }
        }
    }
}
