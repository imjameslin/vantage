package com.mojang.renderpearl.backend.metal;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.ShadercIncludeResult;

/**
 * Default-off, render-thread material dependency snapshots (mc.lod.materialInputs). No GPU reads or waits.
 * Exact reuse requires zero block-light sampling and exclusion of ALL animated atlas footprints, including mips
 * and filtering. Stable light tokens ignore tick flicker and cannot prove an exact full-lightmap match.
 * These tokens do not prove geometry, fog, sampler or terrain-shader invariance.
 *
 * Only the audited Lightmap/TextureAtlas hooks below may publish. A recorded unknown texture write breaks
 * the raw-revision match. Atlas animation cannot repair that trust; a successful full upload can establish
 * a NEW family. Installed pipeline-cache replacement invalidates old publications, even for identical sources.
 */
public final class MetalLodMaterialInputs {
    public static final boolean ENABLED = Boolean.getBoolean("mc.lod.materialInputs");
    // Weak keys; publications/tokens never retain their texture, device, view or Lightmap owner.
    private static final WeakHashMap<MetalTexture, Publication> PUBLISHED = new WeakHashMap<>();

    private MetalLodMaterialInputs() {}

    private enum LightPolicy { ZERO_BLOCK, STABLE }

    /** Owns all 18 finite input float bits. Equality omits only the selected policy's inputs, never padding. */
    public static final class LightToken {
        private final Object family;
        private final Object shaderFamily;
        private final int[] bits;
        private final LightPolicy policy;

        private LightToken(Object family, Object shaderFamily, int[] bits, LightPolicy policy) {
            this.family = family;
            this.shaderFamily = shaderFamily;
            this.bits = bits.clone();
            this.policy = policy;
        }

        public boolean same(@Nullable LightToken other) {
            if (other == null || family != other.family || shaderFamily != other.shaderFamily || policy != other.policy) return false;
            for (int i = 0; i < bits.length; i++) {
                if (!ignored(i) && bits[i] != other.bits[i]) return false;
            }
            return true;
        }

        private boolean ignored(int index) {
            return index == 1 || policy == LightPolicy.ZERO_BLOCK && index >= 6 && index <= 8;
        }

        @Override public boolean equals(Object other) { return other instanceof LightToken t && same(t); }
        @Override public int hashCode() {
            int hash = 31 * (31 * System.identityHashCode(family) + System.identityHashCode(shaderFamily)) + policy.ordinal();
            for (int i = 0; i < bits.length; i++) if (!ignored(i)) hash = 31 * hash + bits[i];
            return hash;
        }
    }

    /** Static pixels outside regions stay immutable within this family, across both audited atlas copies. */
    public static final class AtlasToken {
        private final Object family = new Object();
        private final Object shaderFamily;
        private final int width, height, mipLevels;
        private final int[][] regions;

        private AtlasToken(Object shaderFamily, int width, int height, int mipLevels, int[][] regions) {
            this.shaderFamily = shaderFamily;
            this.width = width;
            this.height = height;
            this.mipLevels = mipLevels;
            this.regions = copyRegions(regions);
        }

        public Object family() { return family; }
        public int width() { return width; }
        public int height() { return height; }
        public int mipLevels() { return mipLevels; }
        /** Base-pixel x,y,w,h, half-open conservative union of each padded sprite's writes at EVERY mip.
         * This is write coverage, not a filtering guard: callers must include their sampling footprint. */
        public int[][] regions() { return copyRegions(regions); }
    }

    private record Publication(long revision, Object token) {}

    public static @Nullable LightToken captureLightmapZeroBlock(@Nullable GpuTextureView view) {
        Object token = capture(view, false);
        return token instanceof LightToken t ? t : null;
    }

    /** Ignores ONLY BlockFactor (tick flicker); BlockLightTint and all other inputs compare exact raw float bits.
     * Validity/revision/source checks are identical to zero-block capture. This is not an exact full-lightmap key. */
    public static @Nullable LightToken captureLightmapStable(@Nullable GpuTextureView view) {
        Object token = capture(view, false);
        return token instanceof LightToken t ? new LightToken(t.family, t.shaderFamily, t.bits, LightPolicy.STABLE) : null;
    }

    public static @Nullable AtlasToken captureStaticAtlas(@Nullable GpuTextureView view) {
        Object token = capture(view, true);
        return token instanceof AtlasToken t ? t : null;
    }

    private static @Nullable Object capture(@Nullable GpuTextureView view, boolean atlas) {
        if (!active() || !(view instanceof MetalTextureView metal) || view.isClosed()
            || view.baseMipLevel() != 0 || view.mipLevels() != metal.texture().getMipLevels()) return null;
        return trusted(metal.texture(), atlas);
    }

    private static @Nullable Object trusted(@Nullable GpuTexture texture, boolean atlas) {
        if (!(texture instanceof MetalTexture metal) || metal.isClosed()) return null;
        Publication p = PUBLISHED.get(metal);
        if (p == null) return null;
        Object shader = p.token instanceof AtlasToken t ? t.shaderFamily : ((LightToken)p.token).shaderFamily;
        if (p.revision != metal.contentRevision() || shader != Sources.family(atlas)) {
            PUBLISHED.remove(metal);
            return null;
        }
        return atlas == (p.token instanceof AtlasToken) ? p.token : null;
    }

    private static boolean active() { return ENABLED && RenderSystem.isOnRenderThread(); }

    /** Before destruction/reload or the first possible write. An abandoned/throwing operation stays invalid. */
    public static void invalidate(@Nullable GpuTexture texture) {
        if (active() && texture instanceof MetalTexture metal) PUBLISHED.remove(metal);
    }

    /** Opaque one-use ticket: created before the Lightmap write, published only after successful encoding. */
    public static final class LightUpdate {
        private final MetalTexture texture;
        private final LightToken token;
        private boolean consumed;
        private LightUpdate(MetalTexture texture, LightToken token) { this.texture = texture; this.token = token; }
    }

    public static @Nullable LightUpdate beginLightmap(Object family, @Nullable GpuTexture texture, LightmapRenderState state) {
        if (!active()) return null;
        invalidate(texture);
        if (!(texture instanceof MetalTexture metal) || !shape(metal, 16, 16, 1) || family == null) return null;
        Object shader = Sources.family(false);
        if (shader == null) return null;
        try {
            float[] all = {state.skyFactor, state.blockFactor, state.nightVisionEffectIntensity,
                state.darknessEffectScale, state.bossOverlayWorldDarkening, state.brightness,
                state.blockLightTint.x(), state.blockLightTint.y(), state.blockLightTint.z(),
                state.skyLightColor.x(), state.skyLightColor.y(), state.skyLightColor.z(),
                state.ambientColor.x(), state.ambientColor.y(), state.ambientColor.z(),
                state.nightVisionColor.x(), state.nightVisionColor.y(), state.nightVisionColor.z()};
            int[] bits = new int[18];
            for (int i = 0; i < all.length; i++) {
                if (!Float.isFinite(all[i])) return null; // Ignored inputs must also be finite: 0 * NaN is unsafe.
                bits[i] = Float.floatToRawIntBits(all[i]);
            }
            return new LightUpdate(metal, new LightToken(family, shader, bits, LightPolicy.ZERO_BLOCK));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void finishLightmap(@Nullable LightUpdate update) {
        if (!active() || update == null || update.consumed) return;
        update.consumed = true;
        if (update.token.shaderFamily == Sources.family(false) && shape(update.texture, 16, 16, 1)) {
            publish(update.texture, update.token);
        }
    }

    /** Opaque one-use ticket bracketing initial upload, or the existing all-path animation wrapper. */
    public static final class AtlasUpdate {
        private final AtlasToken token;
        private boolean consumed;
        private AtlasUpdate(AtlasToken token) { this.token = token; }
    }

    /** Only TextureAtlas.upload may establish a family. slots contains ALL animated sprites, including padding. */
    public static @Nullable AtlasUpdate beginAtlasUpload(@Nullable GpuTexture texture, int @Nullable [][] slots) {
        if (!active()) return null;
        invalidate(texture);
        if (!(texture instanceof MetalTexture metal)) return null;
        int w = metal.getWidth(0), h = metal.getHeight(0), mips = metal.getMipLevels();
        Object shader = Sources.family(true);
        if (shader == null || !shape(metal, w, h, mips) || Integer.bitCount(w) != 1 || Integer.bitCount(h) != 1) return null;
        int[][] regions = mipRegions(w, h, mips, slots);
        return regions == null ? null : new AtlasUpdate(new AtlasToken(shader, w, h, mips, regions));
    }

    public static @Nullable AtlasUpdate beginAtlasAnimation(@Nullable GpuTexture current, @Nullable GpuTexture spare, boolean spareValid) {
        if (!active()) return null;
        Object token = trusted(current, true);
        Object other = spareValid ? trusted(spare, true) : null;
        // Revoke BOTH before copies, draw passes or native compute, including on a failed trust check.
        invalidate(current);
        invalidate(spare);
        if (!(token instanceof AtlasToken atlas) || (spareValid && other != token)) return null;
        return new AtlasUpdate(atlas);
    }

    /** spareValid is true only after the audited full-mip copy has initialized its static pixels. */
    public static void finishAtlas(@Nullable AtlasUpdate update, @Nullable GpuTexture current,
                                   @Nullable GpuTexture spare, boolean spareValid) {
        if (!active() || update == null || update.consumed) return;
        update.consumed = true;
        AtlasToken t = update.token;
        if (t.shaderFamily != Sources.family(true) || !shape(current, t.width, t.height, t.mipLevels)
            || (spareValid && !shape(spare, t.width, t.height, t.mipLevels))) return;
        publish((MetalTexture)current, t);
        if (spareValid) publish((MetalTexture)spare, t);
    }

    private static boolean shape(@Nullable GpuTexture t, int width, int height, int mips) {
        return t instanceof MetalTexture metal && !metal.isClosed() && metal.handle() != 0
            && t.getFormat() == GpuFormat.RGBA8_UNORM && !metal.isCube() && t.getDepthOrLayers() == 1
            && width > 0 && height > 0 && width <= MetalTexture.MAX_SIZE && height <= MetalTexture.MAX_SIZE
            && mips > 0 && mips <= 15 && (width >> (mips - 1)) > 0 && (height >> (mips - 1)) > 0
            && t.getWidth(0) == width && t.getHeight(0) == height && t.getMipLevels() == mips;
    }

    private static void publish(MetalTexture texture, Object token) {
        PUBLISHED.put(texture, new Publication(texture.contentRevision(), token));
    }

    private static int[][] copyRegions(int[][] regions) {
        int[][] copy = new int[regions.length][];
        for (int i = 0; i < copy.length; i++) copy[i] = regions[i].clone();
        return copy;
    }

    static int @Nullable [][] mipRegions(int width, int height, int mips, int[][] slots) {
        if (slots == null || slots.length > 65536 || mips < 1 || mips > 15) return null;
        int[][] regions = new int[slots.length][4];
        for (int i = 0; i < slots.length; i++) {
            int[] s = slots[i];
            if (s == null || s.length != 4 || s[0] < 0 || s[1] < 0 || s[2] <= 0 || s[3] <= 0
                || (long)s[0] + s[2] > width || (long)s[1] + s[3] > height) return null;
            int x0 = s[0], y0 = s[1], x1 = s[0] + s[2], y1 = s[1] + s[3];
            for (int m = 1; m < mips; m++) {
                int w = s[2] >> m, h = s[3] >> m;
                if (w == 0 || h == 0) continue; // Degenerate raster rectangle, no writes.
                int x = s[0] >> m, y = s[1] >> m;
                if (x + w > (width >> m) || y + h > (height >> m)) return null;
                x0 = Math.min(x0, x << m); y0 = Math.min(y0, y << m);
                x1 = Math.max(x1, (x + w) << m); y1 = Math.max(y1, (y + h) << m);
            }
            regions[i] = new int[]{x0, y0, x1 - x0, y1 - y0};
        }
        return regions;
    }

    /** Installed-source guard, never the live ResourceManager (which can be ahead during reload).
     * Fingerprints pin the audited ORIGINAL stages and their complete include graph, not overridable jar resources.
     * PipelineCache owns an immutable installed ShaderSource; memoization follows that same engine contract.
     */
    private static final class Sources {
        private static final @Nullable MethodHandle CURRENT, SOURCE;
        private static WeakReference<PipelineCache> checked = new WeakReference<>(null);
        private static Object epoch = new Object();
        private static boolean light, atlas;
        static {
            MethodHandle current = null, source = null;
            try {
                var lookup = MethodHandles.lookup();
                current = MethodHandles.privateLookupIn(RenderSystem.class, lookup)
                    .findStaticGetter(RenderSystem.class, "currentPipelineCache", PipelineCache.class);
                source = MethodHandles.privateLookupIn(PipelineCache.class, lookup)
                    .findGetter(PipelineCache.class, "shaderSource", ShaderSource.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { current = source = null; }
            CURRENT = current; SOURCE = source;
        }

        static @Nullable Object family(boolean forAtlas) {
            if (CURRENT == null || SOURCE == null) return null;
            try {
                PipelineCache cache = (PipelineCache)CURRENT.invokeExact();
                if (cache == null) return null;
                if (checked.get() != cache) {
                    checked = new WeakReference<>(cache);
                    epoch = new Object();
                    light = atlas = false;
                    ShaderSource s = (ShaderSource)SOURCE.invokeExact(cache);
                    light = shader(s, "screenquad", ShaderType.VERTEX, "b47ff78239a84617b6b90246f9d6164516d492b7e3abbe391b02221b41aee68c")
                        && shader(s, "lightmap", ShaderType.FRAGMENT, "360d9dd915b69682e56636b0e1b1d8da2bd5fea315bd081e12117de12600c928");
                    atlas = shader(s, "animate_sprite", ShaderType.VERTEX, "0475cc3b1906af35371bbbf6f4f808eda8f8eacc0df6c56d24f3354eed6fdb3b")
                        && shader(s, "animate_sprite_blit", ShaderType.FRAGMENT, "55d0d883bd6506054e0a037902ded6df7139382d4bfe69b7911772b5700b7ae3")
                        && shader(s, "animate_sprite_interpolate", ShaderType.FRAGMENT, "307561a222112dfb7046cb4f8191dc812f3982b6a5e61676dbb9032e2e2d0af8")
                        && include(s);
                }
                return (forAtlas ? atlas : light) ? epoch : null;
            } catch (Throwable e) { light = atlas = false; return null; }
        }

        private static boolean shader(ShaderSource source, String name, ShaderType type, String hash) throws Exception {
            return digest(source.getShader(Identifier.withDefaultNamespace("core/" + name), type), hash);
        }

        private static boolean include(ShaderSource source) throws Exception {
            Identifier id = Identifier.withDefaultNamespace("animation_sprite.glsl");
            ShaderSource.CachedIncludeSource cached = source.getInclude(id);
            if (cached == null || cached.includeResultPtr() == 0) return false;
            ShadercIncludeResult result = ShadercIncludeResult.create(cached.includeResultPtr());
            return id.toString().equals(MemoryUtil.memUTF8(result.source_name()))
                && digest(MemoryUtil.memUTF8(result.content()), "2b3d3099ed31c627c0c04ab2c8b741298c99e35afca0f7a69104add6d9e9af2b");
        }

        private static boolean digest(@Nullable String text, String expected) throws Exception {
            return text != null && expected.equals(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))));
        }
    }
}
