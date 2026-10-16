package com.mojang.renderpearl.backend.metal;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/** CPU state-machine/source-guard proof using the real backend texture revisions and command encoder.
 * Synthetic handles only. Never creates a Metal device, submits GPU work, waits or reads GPU memory. */
public final class MetalLodMaterialInputsTest {
    private static int checks;
    private static Path root;
    private static Field current;
    private static void check(boolean yes, String why) {
        checks++;
        if (!yes) throw new AssertionError(why);
    }

    private static final class Sources implements ShaderSource {
        final Map<String, String> texts = new HashMap<>();
        CachedIncludeSource include;
        Sources(String override) throws Exception {
            Path dir = root.resolve("src/vanilla/assets/minecraft/shaders");
            for (String name : new String[]{"screenquad.vsh", "lightmap.fsh", "animate_sprite.vsh",
                    "animate_sprite_blit.fsh", "animate_sprite_interpolate.fsh"}) {
                String value = Files.readString(dir.resolve("core/" + name));
                texts.put(name, name.equals(override) ? value + "\n// changed installed stage\n" : value);
            }
            if (!"missing-include".equals(override)) {
                String value = Files.readString(dir.resolve("include/animation_sprite.glsl"));
                if ("include".equals(override)) value += "\n// changed include\n";
                include = "include-error".equals(override) ? CachedIncludeSource.createError("missing")
                    : CachedIncludeSource.create(Identifier.withDefaultNamespace("animation_sprite.glsl"), value);
            }
        }
        public String getShader(Identifier id, ShaderType type) {
            return texts.get(id.getPath().substring("core/".length()) + (type == ShaderType.VERTEX ? ".vsh" : ".fsh"));
        }
        public CachedIncludeSource getInclude(Identifier id) { return include; }
        public void close() { if (include != null) include.close(); }
    }

    private static final class Rig implements AutoCloseable {
        final MemorySegment page = MemorySegment.ofArray(new long[8]);
        final MetalTimeline timeline = new MetalTimeline(0, page);
        final MetalDevice device = MetalDevice.forTesting(0, page, timeline, null, false);
        final ArrayList<MetalTextureView> views = new ArrayList<>();
        final ArrayList<Sources> sources = new ArrayList<>();
        int submits;
        Rig() {
            timeline.testWaitHook = value -> { throw new AssertionError("unexpected wait"); };
            device.encoder().submitFunction = (d, i, r) -> {
                submits++;
                MemoryUtil.memPutInt(r + MtlNative.SUBMIT_RESULT_FLAGS, MtlNative.SUBMIT_RESULT_FLAG_COMMITTED);
                return MtlNative.OK;
            };
        }
        MetalTextureView texture(int w, int h, int mips) {
            MetalTexture t = MetalTexture.create(device, "material-input CPU", 15, GpuFormat.RGBA8_UNORM, w, h, 1, mips);
            var v = new MetalTextureView(t, 0, mips, 0);
            views.add(v);
            return v;
        }
        void install(String override) throws Exception {
            Sources s = new Sources(override);
            sources.add(s);
            current.set(null, new PipelineCache(null, s)); // Source guard never asks the cache to compile.
        }
        void write(MetalTextureView view) { device.encoder().clearColorTexture(view.texture(), new Vector4f()); }
        public void close() {
            ValueLayout.JAVA_LONG.varHandle().setRelease(page, MtlNative.STATUS_PAGE_COMPLETED_VALUE, Long.MAX_VALUE);
            for (var v : views) { v.close(); v.texture().close(); }
            device.close(); // Test-only fake submit drains any recorded CPU stream.
            for (Sources s : sources) s.close();
        }
    }

    private static float[] inputs() {
        return new float[]{1, 1.4f, 0, 0, 0, .5f, 1, .7f, .4f, 1, .98f, .95f, .04f, .04f, .04f, 1, 1, 1};
    }
    private static LightmapRenderState state(float[] f) {
        var s = new LightmapRenderState();
        s.skyFactor = f[0]; s.blockFactor = f[1]; s.nightVisionEffectIntensity = f[2];
        s.darknessEffectScale = f[3]; s.bossOverlayWorldDarkening = f[4]; s.brightness = f[5];
        s.blockLightTint = new Vector3f(f[6], f[7], f[8]);
        s.skyLightColor = new Vector3f(f[9], f[10], f[11]);
        s.ambientColor = new Vector3f(f[12], f[13], f[14]);
        s.nightVisionColor = new Vector3f(f[15], f[16], f[17]);
        return s;
    }
    private static MetalLodMaterialInputs.LightToken light(Rig r, Object family, MetalTextureView v, float[] f) {
        var u = MetalLodMaterialInputs.beginLightmap(family, v.texture(), state(f));
        check(MetalLodMaterialInputs.captureLightmapZeroBlock(v) == null, "revoked before light write");
        r.write(v);
        MetalLodMaterialInputs.finishLightmap(u);
        return MetalLodMaterialInputs.captureLightmapZeroBlock(v);
    }
    private static MetalLodMaterialInputs.AtlasToken atlas(Rig r, MetalTextureView v, int[][] slots) {
        var u = MetalLodMaterialInputs.beginAtlasUpload(v.texture(), slots);
        r.write(v);
        MetalLodMaterialInputs.finishAtlas(u, v.texture(), null, false);
        return MetalLodMaterialInputs.captureStaticAtlas(v);
    }

    private static void lightTests(Rig r) throws Exception {
        var a = r.texture(16, 16, 1); var b = r.texture(16, 16, 1);
        Object family = new Object();
        float[] base = inputs();
        var first = light(r, family, a, base);
        check(first != null && first.same(first) && !first.same(null), "initial light token");
        var stable = MetalLodMaterialInputs.captureLightmapStable(a);
        check(stable != null && stable.same(stable) && !stable.same(null), "initial stable token");
        check(!first.same(stable) && !stable.same(first) && !first.equals(stable) && !stable.equals(first),
            "different policies never compare equal, even for the same publication");
        for (int i = 0; i < 18; i++) {
            float[] f = base.clone(); f[i] += .125f;
            var next = light(r, family, b, f);
            boolean ignored = i == 1 || i >= 6 && i <= 8;
            check(next != null && first.same(next) == ignored, "exact input dependency " + i);
            check(first.equals(next) == ignored, "equals dependency " + i);
            if (ignored) check(first.hashCode() == next.hashCode(), "equal hash");
            var nextStable = MetalLodMaterialInputs.captureLightmapStable(b);
            check(nextStable != null && stable.same(nextStable) == (i == 1), "stable exact dependency " + i);
            check(stable.equals(nextStable) == (i == 1), "stable equals dependency " + i);
            check(!next.same(nextStable) && !nextStable.same(next), "policy separation " + i);
            if (i == 1) check(stable.hashCode() == nextStable.hashCode(), "stable equal hash ignores flicker");
        }
        for (int i = 0; i < 18; i++) for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            float[] f = base.clone(); f[i] = invalid;
            check(light(r, family, b, f) == null, "nonfinite even when ignored " + i);
            check(MetalLodMaterialInputs.captureLightmapStable(b) == null, "stable also rejects every nonfinite input " + i);
        }
        float[] signedZero = base.clone(); signedZero[2] = -0.0f;
        check(!first.same(light(r, family, b, signedZero)), "exact signed-zero input bits");
        check(!stable.same(MetalLodMaterialInputs.captureLightmapStable(b)), "stable exact signed-zero bits");
        check(!first.same(light(r, new Object(), b, base)), "different lightmap owner family");
        var s = state(base);
        var u = MetalLodMaterialInputs.beginLightmap(family, b.texture(), s);
        ((Vector3f)s.skyLightColor).set(8, 9, 10);
        ((Vector3f)s.blockLightTint).set(6, 7, 8);
        r.write(b); MetalLodMaterialInputs.finishLightmap(u);
        check(first.same(MetalLodMaterialInputs.captureLightmapZeroBlock(b)), "owned pre-write snapshot");
        check(stable.same(MetalLodMaterialInputs.captureLightmapStable(b)), "all input bits owned before publication, including tint");
        r.write(b);
        check(MetalLodMaterialInputs.captureLightmapStable(b) == null, "stable rejects unknown write");
        check(MetalLodMaterialInputs.captureLightmapZeroBlock(b) == null, "unknown light write invalidates");
        MetalLodMaterialInputs.finishLightmap(u);
        check(MetalLodMaterialInputs.captureLightmapZeroBlock(b) == null, "ticket cannot republish after reuse");
        check(first.same(light(r, family, b, base)), "audited full light rewrite can recover");
        MetalLodMaterialInputs.beginLightmap(family, b.texture(), state(base));
        r.write(b); // Simulated encoding exception: no finish.
        check(MetalLodMaterialInputs.captureLightmapZeroBlock(b) == null, "failed light operation stays revoked");
        var fresh = r.texture(16, 16, 1);
        check(MetalLodMaterialInputs.captureLightmapZeroBlock(fresh) == null, "new texture is untrusted");
        check(light(r, family, r.texture(32, 16, 1), base) == null, "wrong light dimensions");
        boolean[] off = new boolean[1];
        Thread t = new Thread(() -> off[0] = MetalLodMaterialInputs.captureLightmapZeroBlock(a) == null);
        t.start(); t.join(); check(off[0], "off-thread capture fails closed");
        a.close(); check(MetalLodMaterialInputs.captureLightmapZeroBlock(a) == null, "closed view");
    }

    private static void atlasTests(Rig r) throws Exception {
        var a = r.texture(128, 64, 5); var b = r.texture(128, 64, 5);
        int[][] slots = {{19, 17, 23, 21}, {80, 32, 32, 32}};
        var first = atlas(r, a, slots);
        check(first != null && first.width() == 128 && first.height() == 64 && first.mipLevels() == 5, "atlas metadata");
        check(Arrays.equals(first.regions()[0], new int[]{16, 16, 26, 22}), "all-mip union expanded to base pixels");
        slots[0][0] = 0;
        int[][] exposed = first.regions(); exposed[0][0] = 99; exposed[1] = new int[4];
        check(first.regions()[0][0] == 16 && first.regions()[1][0] == 80, "regions are deeply immutable");
        var u = MetalLodMaterialInputs.beginAtlasAnimation(a.texture(), null, false);
        check(MetalLodMaterialInputs.captureStaticAtlas(a) == null, "atlas revoked before copy");
        for (int m = 0; m < 5; m++) r.device.encoder().copyTextureToTexture(a.texture(), b.texture(), m,
            0, 0, 0, 0, 128 >> m, 64 >> m);
        r.write(b);
        MetalLodMaterialInputs.finishAtlas(u, b.texture(), a.texture(), true);
        check(MetalLodMaterialInputs.captureStaticAtlas(a).family() == first.family()
            && MetalLodMaterialInputs.captureStaticAtlas(b).family() == first.family(), "initialized pingpong copies share family");
        for (int frame = 0; frame < 96; frame++) {
            u = MetalLodMaterialInputs.beginAtlasAnimation(a.texture(), b.texture(), true);
            check(u != null, "trusted asynchronous frame " + frame);
            check(MetalLodMaterialInputs.captureStaticAtlas(b) == null, "both copies revoked inside transaction");
            if (frame % 3 != 0) r.write(b); // animation/lag repair, and no-op ticks
            MetalLodMaterialInputs.finishAtlas(u, b.texture(), a.texture(), true);
            check(MetalLodMaterialInputs.captureStaticAtlas(b).family() == first.family(), "stable logical family " + frame);
            var tmp = a; a = b; b = tmp;
        }
        check(r.timeline.completedSeq() == 0 && r.submits == 0, "no GPU completion or submission needed");
        // Real encoder copy to a NONZERO mip: every view must lose trust.
        r.device.encoder().copyTextureToTexture(a.texture(), b.texture(), 3, 0, 0, 0, 0, 2, 2);
        check(MetalLodMaterialInputs.captureStaticAtlas(b) == null, "unknown mip write invalidates parent");
        u = MetalLodMaterialInputs.beginAtlasAnimation(a.texture(), b.texture(), true);
        check(u == null, "unknown spare invalidates whole family use");
        r.write(a); MetalLodMaterialInputs.finishAtlas(u, a.texture(), b.texture(), true);
        check(MetalLodMaterialInputs.captureStaticAtlas(a) == null, "animation cannot bless unknown static pixels");
        var replacement = atlas(r, a, new int[][]{{16, 16, 32, 32}});
        check(replacement != null && replacement.family() != first.family(), "full upload establishes NEW family");
        u = MetalLodMaterialInputs.beginAtlasAnimation(a.texture(), b.texture(), false);
        r.write(b); // Simulated failed copy/animation, no finish.
        check(MetalLodMaterialInputs.captureStaticAtlas(a) == null, "failure does not leave old publication");
        var partial = new MetalTextureView(a.texture(), 1, 1, 0);
        atlas(r, a, new int[0][]);
        check(MetalLodMaterialInputs.captureStaticAtlas(partial) == null, "partial mip view rejected"); partial.close();
        check(atlas(r, a, new int[][]{{-1, 0, 1, 1}}) == null, "negative region");
        check(atlas(r, a, new int[][]{{Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 1}}) == null, "overflow region");
        check(atlas(r, a, new int[][]{{0, 0, 129, 1}}) == null, "outside region");
        check(atlas(r, a, new int[][]{{0, 0, 0, 1}}) == null, "zero width");
        check(atlas(r, a, new int[][]{null}) == null, "unknown slot");
        check(atlas(r, a, null) == null, "missing padding metadata");
        check(atlas(r, a, new int[0][]).regions().length == 0, "fully static atlas");
        a.texture().close(); check(MetalLodMaterialInputs.captureStaticAtlas(a) == null, "closed texture");
        // Independent coverage oracle: every written mip texel's base-pixel cell must be excluded.
        for (int x = 0; x < 24; x++) for (int w = 1; w <= 24; w++) {
            int[] region = MetalLodMaterialInputs.mipRegions(64, 64, 6, new int[][]{{x, 7, w, 31}})[0];
            for (int m = 0; m < 6; m++) for (int yy = 7 >> m; yy < (7 >> m) + (31 >> m); yy++)
                for (int xx = x >> m; xx < (x >> m) + (w >> m); xx++) {
                    int scale = 1 << m;
                    check(xx * scale >= region[0] && (xx + 1) * scale <= region[0] + region[2]
                        && yy * scale >= region[1] && (yy + 1) * scale <= region[1] + region[3], "mip exclusion coverage");
                }
        }
    }

    private static void sourceTests(Rig r) throws Exception {
        var l = r.texture(16, 16, 1); var a = r.texture(64, 64, 4);
        Object family = new Object();
        var oldLight = light(r, family, l, inputs()); var oldAtlas = atlas(r, a, new int[0][]);
        check(oldLight != null && oldAtlas != null, "baseline installed source");
        r.install(null); // Identical source, new installed pipeline cache/resource generation.
        check(MetalLodMaterialInputs.captureLightmapZeroBlock(l) == null, "reload revokes light");
        check(MetalLodMaterialInputs.captureStaticAtlas(a) == null, "reload revokes atlas");
        check(!oldLight.same(light(r, family, l, inputs())), "shader generation participates in equality");
        check(atlas(r, a, new int[0][]).family() != oldAtlas.family(), "reload atlas new family");
        for (String changed : new String[]{"screenquad.vsh", "lightmap.fsh", "animate_sprite.vsh",
                "animate_sprite_blit.fsh", "animate_sprite_interpolate.fsh", "include", "missing-include", "include-error"}) {
            r.install(changed);
            boolean badLight = changed.equals("screenquad.vsh") || changed.equals("lightmap.fsh");
            check((light(r, family, l, inputs()) == null) == badLight, "light original-source guard " + changed);
            check((MetalLodMaterialInputs.captureLightmapStable(l) == null) == badLight, "stable source guard " + changed);
            check((atlas(r, a, new int[0][]) == null) == !badLight, "atlas original-source guard " + changed);
        }
        r.install(null);
        var update = MetalLodMaterialInputs.beginAtlasUpload(a.texture(), new int[0][]);
        r.write(a); r.install("animate_sprite.vsh");
        MetalLodMaterialInputs.finishAtlas(update, a.texture(), null, false);
        check(MetalLodMaterialInputs.captureStaticAtlas(a) == null, "source change mid-operation fails publication");
        current.set(null, null);
        check(light(r, family, l, inputs()) == null && atlas(r, a, new int[0][]) == null, "no installed source fails closed");
    }

    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]); RenderSystem.initRenderThread();
        current = RenderSystem.class.getDeclaredField("currentPipelineCache"); current.setAccessible(true);
        Object previous = current.get(null);
        try (Rig r = new Rig()) {
            r.install(null);
            if (!MetalLodMaterialInputs.ENABLED) {
                var l = r.texture(16, 16, 1); var a = r.texture(64, 64, 4);
                check(light(r, new Object(), l, inputs()) == null, "default off light");
                check(MetalLodMaterialInputs.captureLightmapStable(l) == null, "default off stable light");
                check(atlas(r, a, new int[0][]) == null, "default off atlas");
            } else {
                lightTests(r); atlasTests(r); sourceTests(r);
            }
            check(r.submits == 0 && r.timeline.completedSeq() == 0, "all snapshots CPU-only, no waiting/submitting");
        } finally { current.set(null, previous); }
        System.out.println("LOD MATERIAL INPUTS PASSED: " + checks + " checks, enabled=" + MetalLodMaterialInputs.ENABLED);
    }
}
