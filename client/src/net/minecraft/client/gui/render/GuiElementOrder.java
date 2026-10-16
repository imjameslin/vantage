package net.minecraft.client.gui.render;

import java.util.Comparator;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

/**
 * Round 6 G1 (docs/metal/CONTRACT.md 16.G1): {@code GuiRenderer.ELEMENT_SORT_COMPARATOR} written out by hand.
 *
 * <p>Vanilla builds it from {@code Comparator.comparing / thenComparing / nullsFirst} with boxed {@code Integer} keys:
 * scissor area ({@code nullsFirst}, then top, bottom, left, right), then {@code pipeline().getSortKey()}, then
 * {@code textureSetup()} ({@code nullsFirst}, then {@code getSortKey()}). Every comparison goes through 6-9 lambda and
 * boxing calls; {@code GuiRenderState.sortElements} runs it at least n - 1 times per node per frame (TimSort on an
 * already ordered list), which was 15 % of the render thread's Java samples with the F3 overlay and charts.
 *
 * <p>Exact: same keys, same order of precedence, same null handling, and {@code Integer.compareTo} has the sign of
 * {@link Integer#compare}, so every comparison returns a value of the same sign (TimSort only looks at the sign), the sort
 * is deterministic for a given comparison sequence, and the sorted lists are identical. A null pipeline throws a
 * {@code NullPointerException} in both. Used by the patched {@code GuiRenderer.prepare} when {@code GuiLayerIndex.enabled}.
 */
public final class GuiElementOrder implements Comparator<GuiElementRenderState> {
    public static final GuiElementOrder INSTANCE = new GuiElementOrder();

    private GuiElementOrder() {
    }

    @Override
    public int compare(final GuiElementRenderState a, final GuiElementRenderState b) {
        ScreenRectangle sa = a.scissorArea();
        ScreenRectangle sb = b.scissorArea();
        if (sa != sb) {
            if (sa == null) {
                return -1;
            }
            if (sb == null) {
                return 1;
            }
            int c = Integer.compare(sa.top(), sb.top());
            if (c != 0) {
                return c;
            }
            c = Integer.compare(sa.bottom(), sb.bottom());
            if (c != 0) {
                return c;
            }
            c = Integer.compare(sa.left(), sb.left());
            if (c != 0) {
                return c;
            }
            c = Integer.compare(sa.right(), sb.right());
            if (c != 0) {
                return c;
            }
        }
        int c = Integer.compare(a.pipeline().getSortKey(), b.pipeline().getSortKey());
        if (c != 0) {
            return c;
        }
        TextureSetup ta = a.textureSetup();
        TextureSetup tb = b.textureSetup();
        if (ta == null) {
            return tb == null ? 0 : -1;
        }
        if (tb == null) {
            return 1;
        }
        return Integer.compare(ta.getSortKey(), tb.getSortKey());
    }
}
