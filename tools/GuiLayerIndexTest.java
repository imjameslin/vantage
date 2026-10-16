import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiLayerIndex;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/**
 * Round 6 G1 (docs/metal/CONTRACT.md 16.G1): GuiLayerIndex gives vanilla's answer, so GuiRenderState builds the same
 * layer tree with the index on and off.
 *  1. Index vs linear scan: random rectangle sets (tiny / chart bars / text lines / huge / negative width or height /
 *     outside the grid / zero size) and random queries; every answer equal.
 *  2. GuiRenderState differential: the same random element sequence (adds, nextStratum, up, chart-like bar runs, F3-like
 *     text-line backgrounds, nested panels, elements without bounds, addBlitToCurrentLayer-like direct adds through up())
 *     into a state with GuiLayerIndex.enabled false and one with true, including toggling in the middle of a frame; the
 *     strata, node chains and per-node element lists (identity and order) must be identical.
 *  3. The F3 chart workload (background + 2 x 240 bars + labels): identical tree, and the indexed run is faster.
 *  4. GuiElementOrder against GuiRenderer.ELEMENT_SORT_COMPARATOR (read by reflection from the jar's GuiRenderer):
 *     same sign for every pair of random elements (null / shared / equal scissors, real GUI pipelines, null and proxy
 *     texture setups), and identical sorted lists.
 * Run by tools/gui-layer-index-test.sh against build/client.jar (no GPU, no game).
 */
public final class GuiLayerIndexTest {
    static int failures;
    static int checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            if (failures < 30) {
                System.out.println("FAIL: " + what);
            }
        }
    }

    /** A GUI element with bounds only (layering uses nothing else). */
    record El(int id, ScreenRectangle bounds) implements GuiElementRenderState {
        public void buildVertices(VertexConsumer vertexConsumer) {
        }

        public RenderPipeline pipeline() {
            return null;
        }

        public TextureSetup textureSetup() {
            return null;
        }

        public ScreenRectangle scissorArea() {
            return null;
        }
    }

    static ScreenRectangle randRect(SplittableRandom r) {
        int kind = r.nextInt(10);
        int x = r.nextInt(-100, 900);
        int y = r.nextInt(-100, 600);
        return switch (kind) {
            case 0 -> new ScreenRectangle(x, y, 1, r.nextInt(1, 60));             // chart bar
            case 1 -> new ScreenRectangle(x, y, r.nextInt(20, 300), 9);            // text line background
            case 2 -> new ScreenRectangle(x, y, r.nextInt(1, 17), r.nextInt(1, 17)); // icon
            case 3 -> new ScreenRectangle(r.nextInt(-50, 10), r.nextInt(-50, 10), r.nextInt(400, 2000), r.nextInt(300, 1500)); // huge
            case 4 -> new ScreenRectangle(x, y, -r.nextInt(1, 40), r.nextInt(-30, 30)); // negative width
            case 5 -> new ScreenRectangle(x, y, r.nextInt(-30, 30), -r.nextInt(1, 40)); // negative height
            case 6 -> new ScreenRectangle(x, y, 0, r.nextInt(0, 20));              // zero width
            case 7 -> new ScreenRectangle(r.nextInt(-5000, 30000), r.nextInt(-5000, 30000), r.nextInt(-100, 5000), r.nextInt(-100, 5000)); // outside grid
            case 8 -> new ScreenRectangle(r.nextInt(-1040, -1000), r.nextInt(16370, 16400), r.nextInt(1, 40), r.nextInt(1, 40)); // grid edges
            default -> new ScreenRectangle(x, y, r.nextInt(1, 120), r.nextInt(1, 80));
        };
    }

    // ---- 1. index vs linear ----
    static void indexVsLinear() throws Exception {
        java.lang.reflect.Constructor<GuiLayerIndex> ctor = GuiLayerIndex.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Method add = GuiLayerIndex.class.getDeclaredMethod("add", ScreenRectangle.class);
        add.setAccessible(true);
        Method query = GuiLayerIndex.class.getDeclaredMethod("intersectsAny", ScreenRectangle.class);
        query.setAccessible(true);
        SplittableRandom r = new SplittableRandom(42);
        long hits = 0;
        int queries = 0;
        for (int set = 0; set < 400; set++) {
            GuiLayerIndex idx = ctor.newInstance();
            List<ScreenRectangle> list = new ArrayList<>();
            int n = r.nextInt(1, 400);
            for (int i = 0; i < n; i++) {
                ScreenRectangle rect = randRect(r);
                list.add(rect);
                add.invoke(idx, rect);
                if (r.nextInt(4) == 0) {
                    ScreenRectangle q = randRect(r);
                    boolean expect = false;
                    for (ScreenRectangle e : list) {
                        if (e.intersects(q)) {
                            expect = true;
                            break;
                        }
                    }
                    boolean got = (Boolean) query.invoke(idx, q);
                    queries++;
                    if (expect) {
                        hits++;
                    }
                    check(got == expect, "index " + got + " vs linear " + expect + " for " + q + " (set " + set + ", n " + list.size() + ")");
                }
            }
        }
        System.out.println("index vs linear: " + queries + " queries, " + hits + " true");
        check(hits > queries / 10 && hits < queries * 9L / 10, "query mix has both answers");
    }

    // ---- 2. GuiRenderState differential ----
    interface Op {
        void apply(GuiRenderState s);
    }

    static List<Op> randomFrame(SplittableRandom r, int len) {
        List<Op> ops = new ArrayList<>();
        int id = 0;
        for (int i = 0; i < len; i++) {
            int k = r.nextInt(100);
            final int myId = id++;
            if (k < 3) {
                ops.add(GuiRenderState::nextStratum);
            } else if (k < 6) {
                ops.add(GuiRenderState::up);
            } else if (k < 10) {
                // chart run: background then bars, like AbstractDebugChart
                int left = r.nextInt(0, 500);
                int bottom = r.nextInt(80, 500);
                int width = r.nextInt(10, 260);
                final int base = myId * 1000;
                ops.add(s -> s.addGuiElement(new El(base, new ScreenRectangle(left, bottom - 60, width, 60))));
                for (int b = 0; b < width - 2; b++) {
                    int h = r.nextInt(0, 60);
                    final int bx = left + 1 + b;
                    final int bid = base + 1 + b;
                    ops.add(s -> s.addGuiElement(new El(bid, new ScreenRectangle(bx, bottom - h, 1, h))));
                    if (r.nextInt(3) == 0) {
                        int h2 = r.nextInt(0, 60);
                        ops.add(s -> s.addGuiElement(new El(bid + 500, new ScreenRectangle(bx, bottom - h2, 1, 1))));
                    }
                }
                i += width;
            } else if (k < 14) {
                // F3-like lines: backgrounds, then texts on top
                int n = r.nextInt(3, 30);
                boolean leftSide = r.nextBoolean();
                final int base = myId * 1000;
                int[] widths = new int[n];
                for (int l = 0; l < n; l++) {
                    widths[l] = r.nextInt(0, 200);
                }
                for (int l = 0; l < n; l++) {
                    if (widths[l] == 0) {
                        continue;
                    }
                    int w = widths[l];
                    int x = leftSide ? 2 : 750 - 2 - w;
                    int top = 2 + 9 * l;
                    final int eid = base + l;
                    ops.add(s -> s.addGuiElement(new El(eid, new ScreenRectangle(x - 1, top - 1, w + 2, 9))));
                }
                for (int l = 0; l < n; l++) {
                    if (widths[l] == 0) {
                        continue;
                    }
                    int w = widths[l];
                    int x = leftSide ? 2 : 750 - 2 - w;
                    int top = 2 + 9 * l;
                    final int eid = base + 500 + l;
                    ops.add(s -> s.addGuiElement(new El(eid, new ScreenRectangle(x, top, w, 8))));
                }
                i += 2 * n;
            } else if (k < 16) {
                ops.add(s -> s.addGuiElement(new El(myId, null)));   // no bounds: not added (vanilla)
            } else {
                ScreenRectangle rect = randRect(r);
                ops.add(s -> s.addGuiElement(new El(myId, rect)));
            }
        }
        return ops;
    }

    static Field f(Class<?> c, String name) throws Exception {
        Field x = c.getDeclaredField(name);
        x.setAccessible(true);
        return x;
    }

    /** The layer tree: per stratum, the node chain bottom-up, per node its element ids (order kept). */
    static String dump(GuiRenderState s) throws Exception {
        Field strataF = f(GuiRenderState.class, "strata");
        Class<?> nodeC = Class.forName("net.minecraft.client.renderer.state.gui.GuiRenderState$Node");
        Field up = f(nodeC, "up");
        Field el = f(nodeC, "elementStates");
        StringBuilder sb = new StringBuilder();
        List<?> strata = (List<?>) strataF.get(s);
        Map<Object, Integer> ids = new IdentityHashMap<>();
        for (Object stratum : strata) {
            sb.append("S[");
            for (Object node = stratum; node != null; node = up.get(node)) {
                sb.append("N(");
                List<?> els = (List<?>) el.get(node);
                if (els != null) {
                    for (Object e : els) {
                        sb.append(((El) e).id()).append(',');
                    }
                }
                sb.append(')');
            }
            sb.append(']');
        }
        Object current = f(GuiRenderState.class, "current").get(s);
        // where `current` is: stratum index and depth
        int si = 0;
        for (Object stratum : strata) {
            int d = 0;
            for (Object node = stratum; node != null; node = up.get(node), d++) {
                if (node == current) {
                    sb.append(" current=").append(si).append('/').append(d);
                }
            }
            si++;
        }
        return sb.toString();
    }

    static String run(List<Op> ops, int mode, SplittableRandom toggles) throws Exception {
        GuiRenderState s = new GuiRenderState();
        GuiLayerIndex.enabled = mode == 1;
        for (Op op : ops) {
            if (mode == 2 && toggles.nextInt(50) == 0) {
                GuiLayerIndex.enabled = !GuiLayerIndex.enabled;   // mid-frame switch
            }
            op.apply(s);
        }
        String d = dump(s);
        // reuse after reset (nodes and indexes from the last frame must not leak)
        s.reset();
        GuiLayerIndex.enabled = mode != 0;
        for (Op op : ops) {
            op.apply(s);
        }
        String d2 = dump(s);
        return d + "\n" + d2;
    }

    static void differential() throws Exception {
        SplittableRandom r = new SplittableRandom(7);
        int frames = 0;
        long elements = 0;
        for (int i = 0; i < 1500; i++) {
            List<Op> ops = randomFrame(r, r.nextInt(5, 900));
            String off = run(ops, 0, null);
            String on = run(ops, 1, null);
            String mixed = run(ops, 2, new SplittableRandom(i));
            check(off.equals(on), "frame " + i + ": index on differs from off");
            check(off.equals(mixed), "frame " + i + ": mid-frame toggling differs from off");
            frames++;
            elements += ops.size();
        }
        System.out.println("differential: " + frames + " frames, " + elements + " ops, trees identical (off / on / toggled)");
    }

    static List<Op> f3Frame(SplittableRandom r) {
        List<Op> ops = new ArrayList<>();
        ops.add(GuiRenderState::nextStratum);
        // F3 text lines (left, right), backgrounds then texts
        for (int side = 0; side < 2; side++) {
            for (int l = 0; l < 14; l++) {
                int w = 60 + r.nextInt(150);
                int x = side == 0 ? 2 : 754 - 2 - w;
                int top = 2 + 9 * l;
                int id = side * 100 + l;
                ops.add(s -> s.addGuiElement(new El(id, new ScreenRectangle(x - 1, top - 1, w + 2, 9))));
            }
            for (int l = 0; l < 14; l++) {
                int w = 60 + r.nextInt(150);
                int x = side == 0 ? 2 : 754 - 2 - w;
                int top = 2 + 9 * l;
                int id = 1000 + side * 100 + l;
                ops.add(s -> s.addGuiElement(new El(id, new ScreenRectangle(x, top, w, 8))));
            }
        }
        ops.add(GuiRenderState::nextStratum);
        for (int chart = 0; chart < 2; chart++) {
            int left = chart == 0 ? 0 : 754 - 242;
            int bottom = 491;
            int base = 10000 * (chart + 1);
            ops.add(s -> s.addGuiElement(new El(base, new ScreenRectangle(left, bottom - 60, 242, 60))));
            for (int b = 0; b < 240; b++) {
                int h = 5 + r.nextInt(50);
                int bx = left + 1 + b;
                int bid = base + 1 + b;
                ops.add(s -> s.addGuiElement(new El(bid, new ScreenRectangle(bx, bottom - h, 1, h))));
                if (chart == 1) {
                    int h2 = r.nextInt(50);
                    ops.add(s -> s.addGuiElement(new El(bid + 500, new ScreenRectangle(bx, bottom - h2 - 1, 1, 1))));
                }
            }
            for (int t = 0; t < 4; t++) {
                int id = base + 900 + t;
                int tx = left + 2 + 60 * t;
                ops.add(s -> s.addGuiElement(new El(id, new ScreenRectangle(tx, bottom - 69, 40, 8))));
            }
        }
        return ops;
    }

    static void f3Workload() throws Exception {
        SplittableRandom r = new SplittableRandom(3);
        List<Op> ops = f3Frame(r);
        check(run(ops, 0, null).equals(run(ops, 1, null)), "F3 frame: index on differs from off");
        long[] best = {Long.MAX_VALUE, Long.MAX_VALUE};
        for (int rep = 0; rep < 40; rep++) {
            for (int mode = 0; mode < 2; mode++) {
                GuiLayerIndex.enabled = mode == 1;
                GuiRenderState s = new GuiRenderState();
                long t0 = System.nanoTime();
                for (int k = 0; k < 20; k++) {
                    s.reset();
                    for (Op op : ops) {
                        op.apply(s);
                    }
                }
                best[mode] = Math.min(best[mode], (System.nanoTime() - t0) / 20);
            }
        }
        System.out.printf("F3 frame layering (%d adds): vanilla scan %.1f us, index %.1f us%n", ops.size(), best[0] / 1e3, best[1] / 1e3);
        check(best[1] * 2 < best[0], "index at least 2x faster on the F3 frame");
    }

    /** An element with scissor, pipeline and texture setup (for the comparator). */
    record Sortable(int id, ScreenRectangle scissorArea, RenderPipeline pipeline, TextureSetup textureSetup) implements GuiElementRenderState {
        public void buildVertices(VertexConsumer vertexConsumer) {
        }

        public ScreenRectangle bounds() {
            return null;
        }
    }

    static Object proxy(Class<?> type) {
        return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, m, a) -> switch (m.getName()) {
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == a[0];
            case "toString" -> type.getSimpleName() + "@" + System.identityHashCode(self);
            default -> null;
        });
    }

    @SuppressWarnings("unchecked")
    static void comparator() throws Exception {
        Field f = net.minecraft.client.gui.render.GuiRenderer.class.getDeclaredField("ELEMENT_SORT_COMPARATOR");
        f.setAccessible(true);
        java.util.Comparator<GuiElementRenderState> vanilla = (java.util.Comparator<GuiElementRenderState>) f.get(null);
        java.util.Comparator<GuiElementRenderState> fast = net.minecraft.client.gui.render.GuiElementOrder.INSTANCE;
        RenderPipeline[] pipelines = {
            net.minecraft.client.renderer.RenderPipelines.GUI, net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
            net.minecraft.client.renderer.RenderPipelines.GUI_TEXT, net.minecraft.client.renderer.RenderPipelines.GUI_INVERT,
            net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA
        };
        com.mojang.renderpearl.api.textures.GpuTextureView[] views = new com.mojang.renderpearl.api.textures.GpuTextureView[4];
        com.mojang.renderpearl.api.textures.GpuSampler[] samplers = new com.mojang.renderpearl.api.textures.GpuSampler[3];
        for (int i = 0; i < views.length; i++) {
            views[i] = (com.mojang.renderpearl.api.textures.GpuTextureView) proxy(com.mojang.renderpearl.api.textures.GpuTextureView.class);
        }
        for (int i = 0; i < samplers.length; i++) {
            samplers[i] = (com.mojang.renderpearl.api.textures.GpuSampler) proxy(com.mojang.renderpearl.api.textures.GpuSampler.class);
        }
        TextureSetup[] textures = {
            null, TextureSetup.noTexture(), new TextureSetup(null, null, null, null, null, null),
            TextureSetup.singleTexture(views[0], samplers[0]), TextureSetup.singleTexture(views[0], samplers[0]),
            TextureSetup.singleTexture(views[1], samplers[1]), TextureSetup.doubleTexture(views[2], samplers[2], views[3], samplers[0])
        };
        ScreenRectangle shared = new ScreenRectangle(10, 20, 30, 40);
        SplittableRandom r = new SplittableRandom(99);
        int pairs = 0;
        int lists = 0;
        for (int round = 0; round < 400; round++) {
            List<GuiElementRenderState> els = new ArrayList<>();
            int n = r.nextInt(1, 300);
            for (int i = 0; i < n; i++) {
                ScreenRectangle sc = switch (r.nextInt(5)) {
                    case 0, 1 -> null;
                    case 2 -> shared;
                    case 3 -> new ScreenRectangle(10, 20, 30, 40);
                    default -> new ScreenRectangle(r.nextInt(0, 4), r.nextInt(0, 4), r.nextInt(-2, 3), r.nextInt(-2, 3));
                };
                els.add(new Sortable(i, sc, pipelines[r.nextInt(pipelines.length)], textures[r.nextInt(textures.length)]));
            }
            for (int k = 0; k < 200; k++) {
                GuiElementRenderState a = els.get(r.nextInt(n));
                GuiElementRenderState b = els.get(r.nextInt(n));
                check(Integer.signum(vanilla.compare(a, b)) == Integer.signum(fast.compare(a, b)), "comparator sign " + a + " vs " + b);
                pairs++;
            }
            // Mostly-ordered lists (as GUI nodes are) and shuffled ones.
            if (r.nextBoolean()) {
                els.sort(vanilla);
                if (n > 2) {
                    java.util.Collections.swap(els, r.nextInt(n), r.nextInt(n));
                }
            }
            List<GuiElementRenderState> a = new ArrayList<>(els);
            List<GuiElementRenderState> b = new ArrayList<>(els);
            a.sort(vanilla);
            b.sort(fast);
            boolean same = a.size() == b.size();
            for (int i = 0; same && i < a.size(); i++) {
                same = a.get(i) == b.get(i);
            }
            check(same, "sorted lists identical (round " + round + ")");
            lists++;
        }
        System.out.println("comparator: " + pairs + " pairs same sign, " + lists + " lists sorted identically");
    }

    public static void main(String[] args) throws Exception {
        boolean start = GuiLayerIndex.enabled;
        System.out.println("GuiLayerIndex.enabled at start: " + start + " (-Dmc.gui.layerIndex=" + System.getProperty("mc.gui.layerIndex") + ")");
        indexVsLinear();
        differential();
        f3Workload();
        comparator();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
