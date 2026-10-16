package net.minecraft.client.renderer;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Stage 4 (docs/stage4/OWN-SHADERS-RT.md): our own shaders, slice 1. Off unless {@code -Dmc.shaders=true}; when off
 * nothing here runs and no pipeline differs from the shaders-off build.
 *
 * <p>Slice 1: one orthographic sun/moon shadow map around the camera (casters: every compiled SOLID/CUTOUT section
 * within {@link #CASTER_RADIUS} sections, not the camera's culled set), applied forward in core/terrain.fsh
 * (include/tessera.glsl): the fragment rebuilds its position from its depth and the frame's inverse view-projection,
 * its normal from the position's derivatives (exact on flat faces), and tints sunlit and shadowed sky-lit terrain.
 * Terrain samples {@link #terrainLightmap} (the lightmap with alpha = 0.5 + 0.5 * sky light) for the sky light level.
 * The tint fades out with the shadow range, so far (LOD) terrain and its reuse captures stay vanilla-lit.
 */
public final class Tessera {
    public static final boolean ENABLED = Boolean.getBoolean("mc.shaders");
    /** Runtime switch (A/B benches): off = vanilla lightmap and passes, the TESSERA pipelines then render as vanilla. */
    public static volatile boolean active = ENABLED;
    /** Cost probe (A/B benches): shadow map rendering. */
    public static volatile boolean shadowPass = true;
    /** Probe: caster list rebuilds (false = keep the last list). */
    public static volatile boolean casterUpdates = true;
    /** Probe: shadow map lookups in the terrain shader (false = everything lit). */
    public static volatile boolean shadowLookups = true;
    /** Probe: terrain lightmap copy updates (false = keep the first copy). */
    public static volatile boolean lightmapUpdates = true;
    /** A/B: false binds the 1x1 stand-in as the material mask (no foliage sway, plants and leaves lit as other blocks). */
    public static volatile boolean materials = true;
    /** GameRenderer: true while the level renders (passes bound by bindDefaultUniforms get the frame's light). */
    public boolean levelFrame;
    public static volatile long lightmapCopyCount;
    /** Probe: with lightmapUpdates off, still run the copy pass, into an unused texture. */
    public static volatile boolean dummyCopies = Boolean.getBoolean("mc.shaders.dummyCopies");
    private @Nullable TextureTarget dummyCopy;
    /** Shadow map renders and caster rebuilds so far (stats). */
    public static volatile long casterRebuilds;
    static final int SHADOW_SIZE = Integer.getInteger("mc.shaders.shadowSize", 2048);
    static final float SHADOW_HALF = Float.parseFloat(System.getProperty("mc.shaders.shadowHalf", "96"));
    static final float SHADOW_DEPTH = 320.0F;
    public static final int CASTER_RADIUS = (int)Math.ceil(SHADOW_HALF / 16.0F) + 1;
    /** NDC y of the top screen row sign; flip with -Dmc.shaders.flipY if the reconstruction is upside down. */
    private static final float SCREEN_Y_SIGN = Boolean.getBoolean("mc.shaders.flipY") ? -1.0F : 1.0F;
    private static final int LIGHT_UBO_SIZE = 64 * 3 + 16 * 27;
    private static final float DEBUG = Float.parseFloat(System.getProperty("mc.shaders.debug", "0"));

    public static final Tessera INSTANCE = new Tessera();

    private @Nullable TextureTarget shadow;
    private final @Nullable TextureTarget[] lightmapCopies = new TextureTarget[3];
    private int lightmapCopyIndex = -1;
    private long lightmapRevision = -1L;
    private @Nullable Object lightmapSource;
    private @Nullable TextureTarget standIn;
    private @Nullable MappableRingBuffer lightUbo;
    private @Nullable MappableRingBuffer offUbo;
    private @Nullable ProjectionMatrixBuffer identityProjection;
    private final Matrix4f shadowMat = new Matrix4f();
    private final Vector3f lightDir = new Vector3f(0.0F, 1.0F, 0.0F);
    private float strength;
    private final Vector3f sunColor = new Vector3f(1.05F, 1.0F, 0.93F);

    private Tessera() {
    }

    /** The lightmap terrain samples: the current Tessera copy once one exists, else the vanilla one. */
    public static GpuTextureView terrainLightmap(final GpuTextureView lightmap) {
        if (!ENABLED || !active) {
            return lightmap;
        }
        GpuTextureView twin = skyTwin(lightmap);
        if (twin != null) {
            return twin;
        }
        if (INSTANCE.lightmapCopyIndex < 0) {
            return lightmap;
        }
        return INSTANCE.lightmapCopies[INSTANCE.lightmapCopyIndex].getColorTextureView();
    }

    private static @Nullable GpuTextureView skyTwin(final GpuTextureView lightmap) {
        Lightmap owner = Lightmap.tesseraLightmap;
        return !lightmapUpdates || owner == null ? null : owner.skyTwin(lightmap);
    }

    /**
     * Copies the frame's lightmap into the terrain lightmap (16x16, alpha = 0.5 + 0.5 * sky light), only when it changed
     * (the copy's content revision is part of the LOD reuse key, LodReuse). Only when the lightmap kernel did not write the
     * twin (Lightmap.skyTwin): a render pass here sits in the queue behind the previous frame's fragment work and holds
     * back this frame's terrain vertex work (the 1% lows). The copies rotate over three textures so a
     * new copy never overwrites one that a frame still in flight samples (no cross-frame GPU dependency).
     */
    public void updateTerrainLightmap(final GpuTextureView lightmap) {
        if (skyTwin(lightmap) != null) {
            return;   // the lightmap kernel wrote the twin with this update (no pass in the frame's queue)
        }
        int w = lightmap.getWidth(0);
        int h = lightmap.getHeight(0);
        long revision = lightmap.texture() instanceof com.mojang.renderpearl.backend.metal.MetalTexture mt ? mt.contentRevision() : -1L;
        TextureTarget current = this.lightmapCopyIndex < 0 ? null : this.lightmapCopies[this.lightmapCopyIndex];
        if (current != null && current.width == w && current.height == h && (!lightmapUpdates || revision != -1L
            && revision == this.lightmapRevision && lightmap.texture() == this.lightmapSource)) {
            if (!lightmapUpdates && dummyCopies && (revision != this.lightmapRevision || lightmap.texture() != this.lightmapSource)) {
                // probe: the same pass into an unused texture (pass cost without a changed terrain lightmap)
                this.lightmapRevision = revision;
                this.lightmapSource = lightmap.texture();
                if (this.dummyCopy == null) {
                    this.dummyCopy = new TextureTarget("Tessera dummy lightmap", w, h, GpuFormat.RGBA8_UNORM, null);
                }
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                        .createRenderPass(() -> "Tessera terrain lightmap", this.dummyCopy.getColorTextureView(), Optional.empty())) {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TESSERA_LIGHTMAP));
                    pass.setUniform("Lightmap", lightmap, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                    pass.draw(3, 1, 0, 0);
                }
            }
            return;
        }
        lightmapCopyCount++;
        this.lightmapRevision = revision;
        this.lightmapSource = lightmap.texture();
        int next = (this.lightmapCopyIndex + 1) % this.lightmapCopies.length;
        TextureTarget target = this.lightmapCopies[next];
        if (target == null || target.width != w || target.height != h) {
            if (target != null) {
                target.destroyBuffers();
            }
            target = new TextureTarget("Tessera terrain lightmap " + next, w, h, GpuFormat.RGBA8_UNORM, null);
            this.lightmapCopies[next] = target;
        }
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "Tessera terrain lightmap", target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TESSERA_LIGHTMAP));
            pass.setUniform("Lightmap", lightmap, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
        this.lightmapCopyIndex = next;
    }

    /** Light direction steps (radians of sun/moon angle): the shadow map re-renders only when the step changes. */
    private static final float ANGLE_STEP = 0.0008F;
    /** Anchor grid of the shadow map in light space (whole texels, about 4 blocks). */
    private static final double ANCHOR_GRID = (2.0 * SHADOW_HALF / SHADOW_SIZE) * Math.round(4.0 / (2.0 * SHADOW_HALF / SHADOW_SIZE));
    private float lightAngle = Float.NaN;
    private boolean lightMoon;
    /** The sun's elevation sine this frame, unquantized (the lighting's time of day; lightDir is quantized for the map). */
    private float sunUp = 1.0F;
    /**
     * The sun's own direction (unquantized), or the moon's once the sun is well below the horizon: the sun disc and the
     * sky's glow follow it, so the sun sinks into the horizon and its glow lingers after sunset while the shadow light has
     * already changed to the moon.
     */
    private final Vector3f glowDir = new Vector3f(0.0F, 1.0F, 0.0F);
    private boolean glowSun = true;
    /** 0 clear .. 1 full rain (1 - the sky's rain brightness), for the overcast light (TesseraGrade.z). */
    private float rain;
    /** Light level (0..15) of what the player holds (either hand): a moving light at the player (TesseraGrade.w). */
    private float heldLight;

    private static float heldLight() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null || !heldLights) {
            return 0.0F;
        }
        return Math.max(itemLight(player.getMainHandItem()), itemLight(player.getOffhandItem()));
    }

    private static int itemLight(final net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        if (stack.is(net.minecraft.world.item.Items.LAVA_BUCKET)) {
            return 15;
        }
        return stack.getItem() instanceof net.minecraft.world.item.BlockItem block ? block.getBlock().defaultBlockState().getLightEmission() : 0;
    }

    /** Probe / option: the held item lights its surroundings. */
    public static volatile boolean heldLights = !"false".equals(System.getProperty("mc.shaders.heldLight"));
    private final Vector3f basisR = new Vector3f();
    private final Vector3f basisU = new Vector3f();
    private double anchorR;
    private double anchorU;
    private double anchorL;
    private boolean mapValid;
    private long renderedCasterKey;
    private int shadowRenders;
    public static volatile long shadowRendersTotal;

    /**
     * The frame's light: the sun above the horizon, else the moon, faded out near the horizon and in rain. Returns the
     * shadow map's camera-relative view (the terrain ModelView of the caster pass and the lighting pass's lookup), or null
     * when there is no shadow light. The map is anchored in the world (quantized direction, grid-snapped light-space
     * origin), so the same map stays valid while the camera moves inside a grid cell.
     */
    public @Nullable Matrix4fc prepareLight(final SkyRenderState sky, final double camX, final double camY, final double camZ) {
        Vector3f sunNow = new Vector3f(0.0F, 1.0F, 0.0F).rotateX(sky.sunAngle).rotateY((float)(-Math.PI / 2));
        Vector3f moonNow = new Vector3f(0.0F, 1.0F, 0.0F).rotateX(sky.moonAngle).rotateY((float)(-Math.PI / 2));
        float clear = Math.min(1.0F, Math.max(0.0F, sky.rainBrightness));
        this.rain = 1.0F - clear;
        boolean moon = sunNow.y <= 0.0F;
        this.sunUp = sunNow.y;
        this.glowSun = sunNow.y > -0.12F;
        this.glowDir.set(this.glowSun ? sunNow : moonNow);
        if (!moon) {
            // zero at the horizon like the moon's, so nothing that fades with it jumps when the light changes body
            this.strength = smooth(0.0F, 0.08F, sunNow.y) * clear;
            // Golden light low in the sky (sunrise / sunset), neutral-warm at noon.
            float high = smooth(0.0F, 0.45F, sunNow.y);
            this.sunColor.set(1.34F + (1.08F - 1.34F) * high, 0.84F + (1.03F - 0.84F) * high, 0.50F + (0.93F - 0.50F) * high);
        } else {
            this.strength = 0.45F * smooth(0.0F, 0.1F, moonNow.y) * clear;
            this.sunColor.set(0.92F, 0.97F, 1.1F);
        }
        // the direction (and sun / moon) is kept current even without a shadow light (full rain): the lighting's time of
        // day comes from it
        float angle = Math.round((moon ? sky.moonAngle : sky.sunAngle) / ANGLE_STEP) * ANGLE_STEP;
        if (angle != this.lightAngle || moon != this.lightMoon) {
            this.lightAngle = angle;
            this.lightMoon = moon;
            this.lightDir.set(0.0F, 1.0F, 0.0F).rotateX(angle).rotateY((float)(-Math.PI / 2));
            Vector3f l = this.lightDir;
            this.basisR.set(l).cross(0.0F, 0.0F, 1.0F);
            if (this.basisR.lengthSquared() < 1.0E-4F) {
                this.basisR.set(l).cross(1.0F, 0.0F, 0.0F);
            }
            this.basisR.normalize();
            this.basisU.set(this.basisR).cross(l).normalize();
            this.mapValid = false;
        }
        if (this.strength <= 0.001F) {
            this.strength = 0.0F;
            return null;
        }
        Vector3f l = this.lightDir;
        Vector3f r = this.basisR;
        Vector3f u = this.basisU;
        double cr = r.x * camX + r.y * camY + r.z * camZ;
        double cu = u.x * camX + u.y * camY + u.z * camZ;
        double cl = l.x * camX + l.y * camY + l.z * camZ;
        double ar = Math.floor(cr / ANCHOR_GRID + 0.5) * ANCHOR_GRID;
        double au = Math.floor(cu / ANCHOR_GRID + 0.5) * ANCHOR_GRID;
        double al = Math.floor(cl / ANCHOR_GRID + 0.5) * ANCHOR_GRID;
        if (ar != this.anchorR || au != this.anchorU || al != this.anchorL) {
            this.anchorR = ar;
            this.anchorU = au;
            this.anchorL = al;
            this.mapValid = false;
        }
        float h = SHADOW_HALF;
        float d = SHADOW_DEPTH;
        this.shadowMat.set(
            r.x / h, u.x / h, l.x / (2.0F * d), 0.0F,
            r.y / h, u.y / h, l.y / (2.0F * d), 0.0F,
            r.z / h, u.z / h, l.z / (2.0F * d), 0.0F,
            (float)((cr - ar) / h), (float)((cu - au) / h), (float)(0.5 + (cl - al) / (2.0 * d)), 1.0F
        );
        return this.shadowMat;
    }

    /** Whether the shadow map must be re-rendered this frame for the given caster list key. */
    public boolean needsShadowRender(final long casterKey) {
        return shadowPass && (this.shadow == null || !this.mapValid || casterKey != this.renderedCasterKey);
    }

    public void markShadowRendered(final long casterKey) {
        this.mapValid = true;
        this.renderedCasterKey = casterKey;
        this.shadowRenders++;
        shadowRendersTotal++;
    }

    private static final Identifier WATER_STILL = Identifier.withDefaultNamespace("block/water_still");
    private static final Identifier WATER_FLOW = Identifier.withDefaultNamespace("block/water_flow");
    /** Probe: the water look (sky reflection, glint, waves). */
    public static volatile boolean water = true;
    private final Vector3f skyColor = new Vector3f();
    private final float[] waterStill = new float[4];
    private final float[] waterFlow = new float[4];
    private final Vector3f cameraWrap = new Vector3f();
    private float seconds;
    private boolean waterOn;

    /** The frame's water inputs: sky colour, the water sprites' atlas bounds, the wrapped camera position and time. */
    public void prepareWater(final TextureAtlas blocks, final @Nullable Vector3fc sky, final double camX, final double camY, final double camZ, final float seconds) {
        this.updateMaterialMask(blocks);
        this.updateFarShadow(camX, camZ);
        this.heldLight = heldLight();
        // the camera and time feed far shadows, AO, shafts and the reflections' reprojection too: kept current without water
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
        this.cameraWrap.set((float)(camX - Math.floor(camX / 4096.0) * 4096.0), (float)(camY - Math.floor(camY / 4096.0) * 4096.0),
            (float)(camZ - Math.floor(camZ / 4096.0) * 4096.0));
        this.seconds = seconds;
        this.waterOn = water && sky != null;
        if (!this.waterOn) {
            return;
        }
        this.skyColor.set(sky);
        bounds(blocks.getSprite(WATER_STILL), this.waterStill);
        bounds(blocks.getSprite(WATER_FLOW), this.waterFlow);
    }

    /** Probe: Farsight's far shadows (LodShadow) on the terrain beyond the shadow map. */
    public static volatile boolean farShadows = true;
    private static final double FAR_SPAN = net.minecraft.client.renderer.chunk.LodShadow.COARSE.n * net.minecraft.client.renderer.chunk.LodShadow.COARSE.cell;
    private static final double FINE_SPAN = net.minecraft.client.renderer.chunk.LodShadow.FINE.n * net.minecraft.client.renderer.chunk.LodShadow.FINE.cell;
    private com.mojang.renderpearl.api.textures.@Nullable GpuTexture farTexture;
    private @Nullable GpuTextureView farView;
    private com.mojang.renderpearl.api.textures.@Nullable GpuTexture fineTexture;
    private @Nullable GpuTextureView fineView;
    private final int[] farRow = new int[1];
    private @Nullable GpuSampler farSampler;

    /** The far shadows' height images (LodShadow's sweeps: coarse and fine), a band of rows per frame each (outside any render pass). */
    private void updateFarShadow(final double camX, final double camZ) {
        if (!ENABLED || !net.minecraft.client.renderer.chunk.LodShadow.ENABLED || !net.minecraft.client.renderer.chunk.Lod.BUILD) {
            return;
        }
        var device = RenderSystem.getDevice();
        if (this.farSampler == null) {
            this.farSampler = device.createSampler(com.mojang.renderpearl.api.textures.AddressMode.REPEAT, com.mojang.renderpearl.api.textures.AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.LINEAR, 1, java.util.OptionalDouble.empty());
        }
        float lx = this.lightDir.x;
        float ly = this.strength > 0.0F ? this.lightDir.y : 0.0F;
        java.nio.ByteBuffer[] band = net.minecraft.client.renderer.chunk.LodShadow.COARSE.nextBand(camX, camZ, lx, ly, this.farRow);
        if (band != null) {
            if (this.farTexture == null) {
                this.farTexture = device.createTexture(() -> "Tessera far shadow heights", 5, GpuFormat.R32_FLOAT, net.minecraft.client.renderer.chunk.LodShadow.COARSE.n, net.minecraft.client.renderer.chunk.LodShadow.COARSE.n, 1, net.minecraft.client.renderer.chunk.LodShadow.COARSE.mips);
                this.farView = device.createTextureView(this.farTexture);
            }
            this.uploadBand(this.farTexture, net.minecraft.client.renderer.chunk.LodShadow.COARSE, band);
        }
        if (net.minecraft.client.renderer.chunk.LodShadow.FINE_ON) {
            band = net.minecraft.client.renderer.chunk.LodShadow.FINE.nextBand(camX, camZ, lx, ly, this.farRow);
            if (band != null) {
                if (this.fineTexture == null) {
                    this.fineTexture = device.createTexture(() -> "Tessera fine far shadow heights", 5, GpuFormat.R32_FLOAT, net.minecraft.client.renderer.chunk.LodShadow.FINE.n, net.minecraft.client.renderer.chunk.LodShadow.FINE.n, 1, net.minecraft.client.renderer.chunk.LodShadow.FINE.mips);
                    this.fineView = device.createTextureView(this.fineTexture);
                }
                this.uploadBand(this.fineTexture, net.minecraft.client.renderer.chunk.LodShadow.FINE, band);
            }
        }
    }

    private void uploadBand(final com.mojang.renderpearl.api.textures.GpuTexture texture, final net.minecraft.client.renderer.chunk.LodShadow map, final java.nio.ByteBuffer[] band) {
        var device = RenderSystem.getDevice();
        for (int m = 0; m < band.length; m++) {
            device.createCommandEncoder().writeToTexture(texture, band[m], m, 0, 0, this.farRow[0] >> m, map.n >> m, net.minecraft.client.renderer.chunk.LodShadow.BAND >> m);
        }
    }

    private com.mojang.renderpearl.api.textures.@Nullable GpuTexture materialTexture;
    private @Nullable GpuTextureView materialView;
    private @Nullable List<TextureAtlasSprite> materialSprites;
    /** Atlas texels per material mask texel (block sprites are 16 x 16 and stitched on that grid). */
    private static final int MATERIAL_CELL = 16;
    private static final String[] MATERIAL_PLANTS = {
        "short_grass", "tall_grass", "fern", "sapling", "dandelion", "poppy", "orchid", "allium", "azure_bluet", "tulip",
        "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush", "peony",
        "pitcher", "torchflower", "dead_bush", "sweet_berry_bush", "wheat_stage", "carrots_stage", "potatoes_stage",
        "beetroots_stage", "seagrass", "kelp", "sugar_cane", "bush", "petals", "wildflowers", "eyeblossom",
        "dry_grass", "propagule", "hanging_roots", "sprouts", "_roots"
    };

    /**
     * The terrain material mask (TesseraMaterial: r = 0 other, 1/3 plants, 2/3 leaves, 1 fire; one texel per MATERIAL_CELL atlas
     * texels), rebuilt when the block atlas is stitched again. Plants and leaves let sunlight through (tesseraTerrainLight).
     */
    private void updateMaterialMask(final TextureAtlas blocks) {
        List<TextureAtlasSprite> sprites = blocks.tesseraSprites();
        if (sprites == this.materialSprites || blocks.tesseraWidth() <= 0) {
            return;
        }
        this.materialSprites = sprites;
        int w = Math.max(1, blocks.tesseraWidth() / MATERIAL_CELL), h = Math.max(1, blocks.tesseraHeight() / MATERIAL_CELL);
        com.mojang.blaze3d.platform.NativeImage image = new com.mojang.blaze3d.platform.NativeImage(w, h, true);
        for (TextureAtlasSprite sprite : sprites) {
            int mat = material(sprite.contents().name().getPath());
            if (mat == 0) {
                continue;
            }
            int g = mat * 85;
            // from the sprite's own uvs (what the shader looks up), not its pixel position
            int x0 = Math.max(0, (int)Math.floor(sprite.getU0() * w)), y0 = Math.max(0, (int)Math.floor(sprite.getV0() * h));
            int x1 = Math.min(w, (int)Math.ceil(sprite.getU1() * w)), y1 = Math.min(h, (int)Math.ceil(sprite.getV1() * h));
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    image.setPixel(x, y, 0xFF000000 | g << 16 | g << 8 | g);
                }
            }
        }
        var device = RenderSystem.getDevice();
        if (this.materialTexture != null) {
            this.materialView.close();
            this.materialTexture.close();
        }
        this.materialTexture = device.createTexture(() -> "Tessera material mask", 5, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
        try (image) {
            device.createCommandEncoder().writeToTexture(this.materialTexture, image);
        }
        this.materialView = device.createTextureView(this.materialTexture);
    }

    private static int material(final String path) {
        if (!path.startsWith("block/")) {
            return 0;
        }
        if (path.contains("leaves") || path.contains("vine")) {
            return 2;   // vines hang on walls: lit like leaves, not as upward-facing plants
        }
        if (!path.contains("soul") && !path.contains("redstone") && !path.contains("torchflower")
            && (path.equals("block/torch") || path.equals("block/lantern") || path.equals("block/campfire_fire"))) {
            return 3;   // fire: its flame texels glow (orange, full block light, an emission term)
        }
        for (String plant : MATERIAL_PLANTS) {
            if (path.contains(plant)) {
                return 1;
            }
        }
        return 0;
    }

    private static void bounds(final TextureAtlasSprite sprite, final float[] out) {
        out[0] = sprite.getU0();
        out[1] = sprite.getV0();
        out[2] = sprite.getU1();
        out[3] = sprite.getV1();
    }

    public boolean hasLight() {
        return this.strength > 0.0F;
    }

    /** Opens the shadow caster pass (depth cleared to 0, reversed) with the identity projection bound. */
    public RenderPass beginShadowPass() {
        if (this.shadow == null) {
            this.shadow = new TextureTarget("Tessera shadow map", SHADOW_SIZE, SHADOW_SIZE, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        }
        if (this.identityProjection == null) {
            this.identityProjection = new ProjectionMatrixBuffer("tessera shadow");
        }
        GpuBufferSlice projection = this.identityProjection.getBuffer(new Matrix4f());
        RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "Tessera shadow casters", this.shadow.getColorTextureView(), Optional.of(new Vector4f()),
            this.shadow.getDepthTextureView(), OptionalDouble.of(0.0));
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("Projection", projection);
        return pass;
    }

    /**
     * Writes the frame's TesseraLight block (main camera: frame projection, view rotation, main target size). Strength 0
     * (no tint) while inactive or without a shadow light.
     */
    public void writeFrameLight(final int width, final int height, final Matrix4fc projection, final Matrix4fc viewRotation, final boolean lit) {
        if (this.lightUbo == null) {
            this.lightUbo = new MappableRingBuffer(() -> "Tessera light", 130, LIGHT_UBO_SIZE);
        }
        this.lightUbo.rotate();
        Matrix4f viewProj = new Matrix4f(projection).mul(viewRotation);
        Matrix4f invViewProj = new Matrix4f(viewProj).invert();
        // water reflections trace the previous frame's world image (TesseraBloom): its camera and matrix
        boolean reflect = active && this.reflectWritten && this.reflectWidth == width && this.reflectHeight == height;
        this.prevDelta.set((float)(this.camX - this.lastCamX), (float)(this.camY - this.lastCamY), (float)(this.camZ - this.lastCamZ),
            reflect ? 1.0F : 0.0F);
        this.prevViewProj.set(this.lastViewProj);
        this.write(this.lightUbo, invViewProj, width, height, lit && this.shadow != null ? this.strength : 0.0F, lit || this.waterOn);
        this.prevDelta.w = 0.0F;
        this.lastViewProj.set(viewProj);
        this.lastCamX = this.camX;
        this.lastCamY = this.camY;
        this.lastCamZ = this.camZ;
        this.reflectWritten = false;
    }

    /**
     * TesseraBloom: the scissor box (x, y from the bottom, width, height in pixels) of the sun glare: the screen box of the
     * square round the sun that its bloom reaches (0.8 tangent units), clipped at the camera plane; null when the sun is
     * down or off the view. The matrix is this frame's (writeFrameLight).
     */
    public int @Nullable [] sunGlareBox(final int width, final int height) {
        if (!active || this.sunDisc() <= 0.0F) {
            return null;
        }
        Vector3f l = this.glowDir;
        Vector3f e2 = new Vector3f(l).cross(0.0F, 0.0F, 1.0F).normalize();
        Vector3f e1 = new Vector3f(e2).cross(l);
        Vector4f[] cc = new Vector4f[4];
        for (int i = 0; i < 4; i++) {
            float a = i == 0 || i == 3 ? -0.8F : 0.8F;
            float b = i < 2 ? -0.8F : 0.8F;
            cc[i] = this.lastViewProj.transform(new Vector4f(
                (l.x + e1.x * a + e2.x * b) * 1000.0F, (l.y + e1.y * a + e2.y * b) * 1000.0F, (l.z + e1.z * a + e2.z * b) * 1000.0F, 1.0F));
        }
        final float eps = 1.0E-3F;
        float loX = Float.MAX_VALUE, loY = Float.MAX_VALUE, hiX = -Float.MAX_VALUE, hiY = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            Vector4f p0 = cc[i], p1 = cc[(i + 1) & 3];
            if (p0.w > eps) {
                loX = Math.min(loX, p0.x / p0.w); hiX = Math.max(hiX, p0.x / p0.w);
                loY = Math.min(loY, p0.y / p0.w); hiY = Math.max(hiY, p0.y / p0.w);
            }
            if ((p0.w > eps) != (p1.w > eps)) {
                float t = (eps - p0.w) / (p1.w - p0.w);
                float x = (p0.x + (p1.x - p0.x) * t) / eps, y = (p0.y + (p1.y - p0.y) * t) / eps;
                loX = Math.min(loX, x); hiX = Math.max(hiX, x);
                loY = Math.min(loY, y); hiY = Math.max(hiY, y);
            }
        }
        loX = Math.max(loX - 0.02F, -1.0F); hiX = Math.min(hiX + 0.02F, 1.0F);
        loY = Math.max(loY - 0.02F, -1.0F); hiY = Math.min(hiY + 0.02F, 1.0F);
        if (SCREEN_Y_SIGN < 0.0F) {
            float t = loY;
            loY = -hiY;
            hiY = -t;
        }
        int x0 = (int)Math.floor((loX + 1.0F) * 0.5F * width), x1 = (int)Math.ceil((hiX + 1.0F) * 0.5F * width);
        int y0 = (int)Math.floor((loY + 1.0F) * 0.5F * height), y1 = (int)Math.ceil((hiY + 1.0F) * 0.5F * height);
        return x1 > x0 && y1 > y0 ? new int[]{x0, y0, x1 - x0, y1 - y0} : null;
    }

    private double camX;
    private double camY;
    private double camZ;
    private double lastCamX;
    private double lastCamY;
    private double lastCamZ;
    private final Matrix4f lastViewProj = new Matrix4f();
    private final Matrix4f prevViewProj = new Matrix4f();
    /** xyz = this frame's camera minus the previous frame's, w = 1 when the reflection image of that frame is bound. */
    private final Vector4f prevDelta = new Vector4f();
    private boolean reflectWritten;
    private int reflectWidth;
    private int reflectHeight;
    private @Nullable GpuTextureView reflectView;

    private @Nullable GpuTextureView shaftsView;

    /** TesseraBloom: this frame's shadowed share of the light shafts (TESSERA_SHAFTS) for the next frame, or null (none). */
    public void shaftsWritten(final @Nullable GpuTextureView view) {
        this.shaftsView = view;
    }

    /** Whether the sun's light shafts show this frame (sun up past the horizon band, Tessera lighting on). */
    public boolean shaftsVisible() {
        return active && this.strength > 0.0F && !this.lightMoon && this.lightDir.y > 0.05F;
    }

    /** Binds the stand-in as TesseraShafts (the pass that writes the shafts texture must not also read it). */
    public void unbindShafts(final RenderPass pass) {
        pass.setUniform("TesseraShafts", this.standIn.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    }

    /** TesseraBloom: this frame's world image for the next frame's water reflections (frame size {@code width} x {@code height}). */
    public void reflectWritten(final GpuTextureView view, final int width, final int height) {
        this.reflectView = view;
        this.reflectWidth = width;
        this.reflectHeight = height;
        this.reflectWritten = true;
    }

    /** Probe: the AO pass writes 1 without sampling (its fixed cost: the pass and the composite's taps). */
    public static volatile boolean aoStub = Boolean.getBoolean("mc.shaders.aoStub");
    /** The clouds' march jumps across empty sky by the baked distance (TesseraProbe.x = 0); false: plain steps. */
    public static volatile boolean cloudSkip = !"false".equals(System.getProperty("mc.shaders.cloudSkip"));
    /** Free shader probes (TesseraProbe.yzw) for A/B tests without one-off costs. */
    public static volatile float probeY;
    public static volatile float probeZ = Float.parseFloat(System.getProperty("mc.shaders.probeZ", "0"));
    public static volatile float probeW;
    /** Probe: the clouds stop after the layer span (1) or after the march, before shading (2) (TesseraOptions.w). */
    public static volatile float cloudStub = Float.parseFloat(System.getProperty("mc.shaders.cloudStub", "0"));

    /** God rays: light shafts marched through the shadow map (TesseraOptions.y); false: unshadowed haze only. */
    public static volatile boolean shadowShafts = !"false".equals(System.getProperty("mc.shaders.godRays"));

    /** The Brightness option (0 Moody .. 1 Bright), which scales the night, rain, block light and cave floor (TesseraOptions.x). */
    private static float brightness() {
        return (float)Math.clamp(net.minecraft.client.Minecraft.getInstance().options.gamma().get(), 0.0, 1.0);
    }

    /**
     * Relative air mass towards a body at elevation sine e (Kasten and Young 1989; 1 at the zenith, ~38 at the horizon,
     * capped at 40 below it).
     */
    private static float airMass(final float e) {
        double h = Math.toDegrees(Math.asin(Math.max(-1.0F, Math.min(1.0F, e))));
        if (h <= -6.0) {
            return 40.0F;
        }
        return (float)Math.min(40.0, 1.0 / (Math.sin(Math.toRadians(h)) + 0.50572 * Math.pow(h + 6.07995, -1.6364)));
    }

    /** Sunlight's extinction per unit air mass (red, green, blue): short wavelengths scatter out first, so a low sun reddens. */
    private static final float[] EXTINCTION = {0.075F, 0.145F, 0.28F};
    /** Sunlight above the air (linear; warm-white). */
    private static final float[] SUN_TOP = {3.39F, 2.69F, 1.86F};
    /** Moonlight (linear; cool), scaled by the Brightness option. */
    private static final float[] MOON = {0.060F, 0.070F, 0.108F};
    /** Night sky light (linear), scaled by the Brightness option. */
    private static final float[] NIGHT_SKY = {0.035F, 0.047F, 0.079F};
    /** Overcast light (linear): the direct and the sky light under rain, by day. */
    private static final float[] RAIN_DIRECT = {0.13F, 0.125F, 0.12F};
    private static final float[] RAIN_SKY = {0.22F, 0.235F, 0.275F};

    /**
     * The lighting model's per-frame terms (tessera.glsl reads them instead of working them out per fragment), all linear:
     * the direct light (sun by day through the air mass, moon by night) and its fade while a body crosses the horizon,
     * the sky light and the day factor, the block light's daylight dimming, the low-sun amount, and the light shafts'
     * colour times their time weight (0 = none) with the low-sun amount squared.
     */
    private float[] sceneConstants() {
        float[] o = new float[16];
        float up = this.sunUp;                              // the sun's elevation sine
        float bright = brightness();
        float rain = this.rain;
        float day = smooth(-0.06F, 0.10F, up);
        float lowSun = 1.0F - smooth(0.0F, 0.6F, up);
        float m = airMass(up);
        float[] sky = {this.skyColor.x, this.skyColor.y, this.skyColor.z};
        float nightK = 0.55F + 0.9F * bright;
        float moonUp = smooth(0.0F, 0.25F, -up);
        float[] sunLight = new float[3];
        for (int i = 0; i < 3; i++) {
            sunLight[i] = SUN_TOP[i] * (float)Math.exp(-EXTINCTION[i] * m);
            // the sky's light: its colour by day (squared towards linear), warmed by the low sun, and the night's
            float skyDay = sky[i] * sky[i] * 1.12F + 0.058F;
            skyDay = mix(skyDay, skyDay * 0.6F + sunLight[i] * 0.12F, lowSun * 0.6F);
            float direct = this.lightMoon ? MOON[i] * nightK * moonUp : sunLight[i];
            float skyL = mix(NIGHT_SKY[i] * nightK, skyDay, day);
            direct = mix(direct, mix(MOON[i] * 0.25F * nightK, RAIN_DIRECT[i], day), rain);
            skyL = mix(skyL, mix(NIGHT_SKY[i] * 0.8F * nightK, RAIN_SKY[i] * (0.8F + 0.3F * bright), day), rain);
            o[i] = direct;
            o[4 + i] = skyL;
        }
        o[3] = smooth(0.004F, 0.06F, Math.abs(up));
        o[7] = day;
        o[8] = 0.6F * day * (1.0F - 0.5F * rain);
        o[10] = lowSun;
        // light shafts (the sun only): its light, paler by day, strongest with a low sun, faded while it crosses the horizon
        float shaftFade = smooth(0.03F, 0.16F, up);
        if (!this.lightMoon && shaftFade > 0.0F) {
            float k = shaftFade * (0.12F + 0.88F * (float)Math.exp(-5.0F * Math.max(up, 0.0F))) * 0.42F;
            float e = 0.55F + 0.45F * lowSun;
            for (int i = 0; i < 3; i++) {
                o[12 + i] = (float)Math.pow(sunLight[i], e) * k;
            }
        }
        o[15] = lowSun * lowSun;
        return o;
    }

    /**
     * The sky colours' per-frame terms (tessera.glsl's tesseraZenith, tesseraNightMiddle, tesseraLow, tesseraSunFactor,
     * tesseraGlowTint read them): the zenith colour and the sunrise / sunset amount, the night horizon colour and the day
     * factor, the sun glow's tint and strength. strength = TesseraLightDir.w.
     */
    private float[] skyConstants(final float strength) {
        float[] o = new float[24];
        float sunUp = this.sunUp;
        // the sunrise / sunset look: grows as the sun gets low, lingers a little after sunset (before sunrise), none in rain
        float low = (1.0F - smooth(0.02F, 0.45F, sunUp)) * smooth(-0.14F, 0.0F, sunUp) * (1.0F - this.rain);
        float dayF = smooth(-0.22F, 0.05F, sunUp);          // the sky stays lit a while after sunset
        float deep = smooth(0.0F, 0.6F, -sunUp);             // how deep into the night
        float rain = this.rain;
        float[] sky = {this.skyColor.x, this.skyColor.y, this.skyColor.z};
        float[] nightZ = {0.028F, 0.050F, 0.095F};
        float[] nightH = {0.105F, 0.140F, 0.205F};
        float[] zTint = {0.50F, 0.64F, 1.15F};
        float[] dusk = {0.60F, 0.52F, 0.58F};
        float[] z = new float[3];
        for (int i = 0; i < 3; i++) {
            float nightUp = (nightZ[i] + sky[i] * 0.4F) * (1.3F - 0.3F * deep);
            o[4 + i] = (nightH[i] + sky[i] * 0.5F) * (1.9F - 0.9F * deep);
            z[i] = mix(nightUp, mix(sky[i] * zTint[i], dusk[i], low * 0.85F), dayF);
        }
        float zl = z[0] * 0.2126F + z[1] * 0.7152F + z[2] * 0.0722F;
        float[] grey = {0.94F, 0.97F, 1.05F};
        float[] floorZ = {0.040F, 0.045F, 0.060F};
        float[] sun = {this.sunColor.x, this.sunColor.y, this.sunColor.z};
        float[] glowDay = {1.0F, 0.93F, 0.84F};
        float[] glowLow = {1.0F, 0.86F, 0.68F};
        for (int i = 0; i < 3; i++) {
            o[i] = Math.max(mix(z[i], zl * grey[i], rain * 0.85F), floorZ[i] * rain);
            o[8 + i] = mix(glowDay[i], mix(sun[i], glowLow[i], 0.45F), low);
        }
        o[3] = low;
        o[7] = dayF;
        // the glow round the sun (until well after sunset) or the moon: zero where it changes body
        float clear = 1.0F - rain;
        float g = this.glowSun ? smooth(-0.12F, 0.06F, sunUp) * clear : 0.45F * smooth(0.12F, 0.30F, -sunUp) * clear;
        o[11] = Math.min(g * g * 1.6F, 1.0F);
        if (this.glowSun) {
            // the low sun's gold (its colour at the horizon) rather than the light's, which is the moon's after sunset
            float[] gold = {1.34F, 0.84F, 0.50F};
            float[] sunset = {1.30F, 0.60F, 0.28F};   // round a sun touching the horizon: deep orange
            float high = smooth(0.0F, 0.45F, sunUp);
            float sink = smooth(-0.05F, 0.15F, sunUp);
            for (int i = 0; i < 3; i++) {
                float sunC = mix(gold[i], new float[]{1.08F, 1.03F, 0.93F}[i], high);
                o[8 + i] = mix(glowDay[i], mix(sunset[i], mix(sunC, glowLow[i], 0.45F), sink), low);
            }
        }
        // sun disc direction and weight, the warm haze towards it, the sun's elevation, the sky glare's weight
        o[12] = this.glowDir.x;
        o[13] = this.glowDir.y;
        o[14] = this.glowDir.z;
        o[15] = this.sunDisc();
        o[16] = this.glowSun ? smooth(-0.10F, 0.05F, sunUp) * clear : 0.0F;
        o[17] = sunUp;
        o[18] = this.glowSun ? smooth(-0.10F, 0.02F, sunUp) * clear : smooth(0.12F, 0.25F, -sunUp) * clear;
        o[19] = this.glowSun ? 1.0F : 0.0F;
        // the sun disc's colour relative to noon's (display multipliers of its virtual-HDR core): high up a warm white;
        // lower down blue, then green leave first, so it goes yellow (about 6 degrees), orange (2 to 3) and a deep
        // orange-red on the horizon, dimmer but still clearly a disc until the land covers it
        float a = smooth(-0.02F, 0.20F, sunUp);
        o[20] = 0.30F + 0.70F * a;
        o[21] = 0.06F + 0.94F * (float)Math.pow(a, 2.5);
        o[22] = a * a * a;
        return o;
    }

    /** The vanilla moon sprite's brightness (it is added onto the sky): faint while the sky is bright, full at night. */
    public float moonBrightness() {
        return 1.0F - 0.85F * smooth(-0.15F, 0.10F, this.sunUp);
    }

    /** How much of the sun disc shows: full until it reaches the horizon, gone about 3 degrees below it; none in rain. */
    private float sunDisc() {
        return this.glowSun ? smooth(-0.055F, 0.0F, this.sunUp) * (1.0F - this.rain) : 0.0F;
    }

    private static float mix(final float a, final float b, final float t) {
        return a + (b - a) * t;
    }

    private void write(final MappableRingBuffer ubo, final Matrix4fc invViewProj, final int width, final int height, final float strength, final boolean waterLook) {
        float texel = 2.0F * SHADOW_HALF / SHADOW_SIZE;
        float[] sc = this.sceneConstants();
        float[] sk = this.skyConstants(strength);
        try (GpuBufferSlice.MappedView view = ubo.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data())
                .putMat4f(invViewProj)
                .putMat4f(this.shadowMat)
                .putVec4(this.lightDir.x, this.lightDir.y, this.lightDir.z, strength)
                .putVec4(width, height, SHADOW_SIZE, texel)
                .putVec4(this.sunColor.x, this.sunColor.y, this.sunColor.z, SCREEN_Y_SIGN)
                .putVec4(0.62F, 0.70F, 0.88F, 0.8F)
                .putVec4(DEBUG, SHADOW_HALF * 0.75F, SHADOW_HALF, shadowLookups ? 0.0F : 1.0F)
                .putVec4(this.skyColor.x, this.skyColor.y, this.skyColor.z, waterLook && this.waterOn ? 1.0F : 0.0F)
                .putVec4(this.waterStill[0], this.waterStill[1], this.waterStill[2], this.waterStill[3])
                .putVec4(this.waterFlow[0], this.waterFlow[1], this.waterFlow[2], this.waterFlow[3])
                .putVec4(this.cameraWrap.x, this.cameraWrap.y, this.cameraWrap.z, this.seconds)
                .putVec4(this.lightScale, this.lightMoon ? 0.0F : 1.0F, this.rain, this.heldLight)
                .putMat4f(this.prevViewProj)
                .putVec4(this.prevDelta.x, this.prevDelta.y, this.prevDelta.z, this.prevDelta.w)
                .putVec4(this.cloudCamX, this.cloudCamZ, this.cloudBottom, this.cloudRange)
                .putVec4(this.cloudColor.x, this.cloudColor.y, this.cloudColor.z, this.cloudsOn() ? 1.0F : 0.0F)
                .putVec4(brightness(), shadowShafts ? 1.0F : 0.0F, aoStub ? 1.0F : 0.0F, cloudStub)
                .putVec4(cloudSkip ? 0.0F : 1.0F, probeY, probeZ, probeW)
                .putVec4(sc[0], sc[1], sc[2], sc[3])
                .putVec4(sc[4], sc[5], sc[6], sc[7])
                .putVec4(sc[8], sc[9], sc[10], sc[11])
                .putVec4(sc[12], sc[13], sc[14], sc[15])
                .putVec4(sk[0], sk[1], sk[2], sk[3])
                .putVec4(sk[4], sk[5], sk[6], sk[7])
                .putVec4(sk[8], sk[9], sk[10], sk[11])
                .putVec4((float)(this.camX - Math.floor(this.camX / FAR_SPAN) * FAR_SPAN), (float)this.camY, (float)(this.camZ - Math.floor(this.camZ / FAR_SPAN) * FAR_SPAN),
                    farShadows && this.farView != null && net.minecraft.client.renderer.chunk.LodShadow.COARSE.valid() && strength > 0.0F && Math.abs(this.lightDir.x) >= 0.02F && this.lightDir.y > 0.01F ? (float)(1.0 / FAR_SPAN) : 0.0F)
                .putVec4((float)(this.camX - Math.floor(this.camX / FINE_SPAN) * FINE_SPAN), 0.0F, (float)(this.camZ - Math.floor(this.camZ / FINE_SPAN) * FINE_SPAN),
                    farShadows && this.fineView != null && net.minecraft.client.renderer.chunk.LodShadow.FINE.valid() ? (float)(1.0 / FINE_SPAN) : 0.0F)
                .putVec4(sk[12], sk[13], sk[14], sk[15])
                .putVec4(sk[16], sk[17], sk[18], sk[19])
                .putVec4(sk[20], sk[21], sk[22], sk[23]);
        }
    }

    /** Probe: our own raymarched clouds (core/tessera_clouds.fsh) instead of vanilla's cloud boxes. */
    public static volatile boolean volumetricClouds = !"false".equals(System.getProperty("mc.shaders.clouds"));
    private float cloudCamX;
    private float cloudCamZ;
    private float cloudBottom;
    private float cloudRange;
    private final Vector3f cloudColor = new Vector3f(1.0F);
    private long cloudNanos;
    private CloudRenderer.@Nullable TextureData cloudCells;
    private java.util.concurrent.@Nullable CompletableFuture<com.mojang.blaze3d.platform.NativeImage> cloudBake;
    private com.mojang.renderpearl.api.textures.@Nullable GpuTexture cloudTexture;
    private @Nullable GpuTextureView cloudView;

    /** CloudRenderer: a (re)loaded cloud map; uploaded as an occupancy texture on first use. */
    public void cloudMap(final CloudRenderer.@Nullable TextureData cells) {
        this.cloudCells = cells;
        if (this.cloudBake != null) {
            // a superseded bake: free its image whenever it finishes
            this.cloudBake.thenAccept(com.mojang.blaze3d.platform.NativeImage::close);
            this.cloudBake = null;
        }
        if (cells != null) {
            this.cloudBake = java.util.concurrent.CompletableFuture.supplyAsync(() -> bakeCloudMap(cells), net.minecraft.util.Util.backgroundExecutor());
        }
        if (this.cloudView != null) {
            this.cloudView.close();
            this.cloudTexture.close();
            this.cloudView = null;
            this.cloudTexture = null;
        }
    }

    /**
     * CloudRenderer.prepare, each frame clouds are on: the camera's position in the cloud map (blocks, wrapped, wind
     * included), the cloud layer's bottom relative to the camera, the draw radius (blocks) and vanilla's cloud colour.
     */
    public void cloudFrame(final int color, final float camX, final float camZ, final float bottom, final float range) {
        this.cloudCamX = camX;
        this.cloudCamZ = camZ;
        this.cloudBottom = bottom;
        this.cloudRange = range;
        this.cloudColor.set(net.minecraft.util.ARGB.redFloat(color), net.minecraft.util.ARGB.greenFloat(color), net.minecraft.util.ARGB.blueFloat(color));
        this.cloudNanos = System.nanoTime();
        this.uploadCloudMap();
    }

    private GpuTextureView standInView() {
        if (this.standIn == null) {
            this.standIn = new TextureTarget("Tessera stand-in", 1, 1, GpuFormat.RGBA8_UNORM, null);
        }
        return this.standIn.getColorTextureView();
    }

    public boolean cloudsOn() {
        return volumetricClouds && this.cloudView != null && System.nanoTime() - this.cloudNanos < 250_000_000L;
    }

    /** Uploads the cloud occupancy map once its bake (a background thread, started by cloudMap) is done (outside any render pass). */
    private void uploadCloudMap() {
        if (this.cloudView == null && this.cloudBake != null && this.cloudBake.isDone()) {
            com.mojang.blaze3d.platform.NativeImage image = this.cloudBake.join();
            this.cloudBake = null;
            var device = RenderSystem.getDevice();
            this.cloudTexture = device.createTexture(() -> "Tessera cloud map", 5, GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, 1);
            try (image) {
                device.createCommandEncoder().writeToTexture(this.cloudTexture, image);
            }
            this.cloudView = device.createTextureView(this.cloudTexture);
        }
    }

    /**
     * The soft occupancy baked at CLOUD_SUB texels a cell (tessera_clouds.fsh samples it bilinearly, wrapped): each texel
     * blends its four nearest cells through a smoothstep across the cell edge (half-width CLOUD_ROUND cells).
     */
    private static com.mojang.blaze3d.platform.NativeImage bakeCloudMap(final CloudRenderer.TextureData cells) {
        int w = cells.width(), h = cells.height(), sub = CLOUD_SUB;
        com.mojang.blaze3d.platform.NativeImage image = new com.mojang.blaze3d.platform.NativeImage(w * sub, h * sub, false);
        int W = w * sub, H = h * sub;
        float[] occ = new float[W * H];
        for (int y = 0; y < h * sub; y++) {
            float gy = (y + 0.5F) / sub - 0.5F;
            int iy = (int) Math.floor(gy);
            float fy = cloudEdge(gy - iy);
            for (int x = 0; x < w * sub; x++) {
                float gx = (x + 0.5F) / sub - 0.5F;
                int ix = (int) Math.floor(gx);
                float fx = cloudEdge(gx - ix);
                float a = cloudCell(cells, ix, iy), b = cloudCell(cells, ix + 1, iy);
                float c = cloudCell(cells, ix, iy + 1), d = cloudCell(cells, ix + 1, iy + 1);
                float v = (a + (b - a) * fx) * (1.0F - fy) + (c + (d - c) * fx) * fy;
                occ[x + y * W] = v;
            }
        }
        // g: distance (texels, x4, capped at 63.75) to the nearest texel whose occupancy reaches 0.5, wrapped like the
        // map: the clouds' march skips empty sky by it. Chamfer passes (3-4 weights), two rounds for the wrap.
        int[] dist = new int[W * H];
        int far = 1 << 20;
        for (int i = 0; i < W * H; i++) {
            dist[i] = occ[i] >= 0.5F ? 0 : far;
        }
        for (int round = 0; round < 2; round++) {
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int i = x + y * W;
                    int dd = dist[i];
                    int xm = Math.floorMod(x - 1, W), xp = Math.floorMod(x + 1, W), ym = Math.floorMod(y - 1, H);
                    dd = Math.min(dd, dist[xm + y * W] + 3);
                    dd = Math.min(dd, dist[x + ym * W] + 3);
                    dd = Math.min(dd, dist[xm + ym * W] + 4);
                    dd = Math.min(dd, dist[xp + ym * W] + 4);
                    dist[i] = dd;
                }
            }
            for (int y = H - 1; y >= 0; y--) {
                for (int x = W - 1; x >= 0; x--) {
                    int i = x + y * W;
                    int dd = dist[i];
                    int xm = Math.floorMod(x - 1, W), xp = Math.floorMod(x + 1, W), yp = Math.floorMod(y + 1, H);
                    dd = Math.min(dd, dist[xp + y * W] + 3);
                    dd = Math.min(dd, dist[x + yp * W] + 3);
                    dd = Math.min(dd, dist[xp + yp * W] + 4);
                    dd = Math.min(dd, dist[xm + yp * W] + 4);
                    dist[i] = dd;
                }
            }
        }
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = x + y * W;
                int g = Math.round(occ[i] * 255.0F);
                // chamfer units of 3 per texel (an underestimate of the true distance on diagonals: safe)
                int e = Math.min(255, dist[i] * 4 / 3);
                image.setPixel(x, y, 0xFF000000 | g << 16 | e << 8 | g);
            }
        }
        return image;
    }

    private static final int CLOUD_SUB = 4;
    private static final float CLOUD_ROUND = 0.12F;

    private static float cloudEdge(final float f) {
        float t = Math.min(Math.max((f - (0.5F - CLOUD_ROUND)) / (2.0F * CLOUD_ROUND), 0.0F), 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    private static float cloudCell(final CloudRenderer.TextureData cells, final int x, final int y) {
        int w = cells.width(), h = cells.height();
        return cells.cells()[Math.floorMod(x, w) + Math.floorMod(y, h) * w] != 0L ? 1.0F : 0.0F;
    }

    /** Binds the cloud occupancy map (TesseraCloudMap: r = 1 where the cloud texture has a cell). */
    public void bindClouds(final RenderPass pass) {
        GpuTextureView view = this.cloudView != null ? this.cloudView : this.standInView();
        pass.setUniform("TesseraCloudMap", view, RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
    }

    /** Scale of the bound terrain lightmap (2 = the half-scale twin / copy, 1 = vanilla's); set each frame. */
    private float lightScale = 1.0F;

    /** Records which lightmap terrain samples this frame (call after {@link #updateTerrainLightmap}). */
    public void noteTerrainLightmap(final GpuTextureView vanilla) {
        this.lightScale = terrainLightmap(vanilla) != vanilla ? 2.0F : 1.0F;
    }

    private final @Nullable MappableRingBuffer[] captureUbos = new MappableRingBuffer[2];

    /**
     * Farsight reuse captures (LodReuse): writes a light block for the capture's own camera ({@code projection} and
     * {@code view} column-major, target {@code width} x {@code height}) into ring {@code slot} (0 = exact refresh, 1 = turn
     * strip; each used at most once a frame). The captured terrain then gets the same sun light and shadows as a live draw.
     */
    public void prepareCapture(final int slot, final int width, final int height, final float[] projection, final float[] view) {
        if (this.captureUbos[slot] == null) {
            this.captureUbos[slot] = new MappableRingBuffer(() -> "Tessera capture light " + slot, 130, LIGHT_UBO_SIZE);
        }
        this.captureUbos[slot].rotate();
        Matrix4f invViewProj = new Matrix4f().set(projection).mul(new Matrix4f().set(view)).invert();
        boolean lit = active && this.strength > 0.0F && this.shadow != null && this.mapValid;
        // the sky look (far haze) as on live draws, or the haze would switch with each reuse capture
        this.write(this.captureUbos[slot], invViewProj, width, height, lit ? this.strength : 0.0F, true);
    }

    /** Binds the light block {@link #prepareCapture} wrote for {@code slot}, with the shadow map. */
    public void bindCapture(final RenderPass pass, final int slot) {
        if (this.captureUbos[slot] == null || this.shadow == null) {
            this.bindTerrain(pass, false);
            return;
        }
        pass.setUniform("TesseraShadow", this.shadow.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        pass.setUniform("TesseraLight", this.captureUbos[slot].currentBuffer());
        if (this.farView != null) {
            pass.setUniform("TesseraFar", this.farView, this.farSampler);
        }
        if (this.fineView != null) {
            pass.setUniform("TesseraFarFine", this.fineView, this.farSampler);
        } else if (this.standIn != null) {
            pass.setUniform("TesseraFarFine", this.standIn.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        }
    }

    /** What a Farsight reuse capture's light depends on (a soft LodReuse key input: refreshed within its soft window). */
    public long captureKey() {
        if (!active || this.strength <= 0.0F) {
            return 0L;
        }
        long h = Float.floatToIntBits(this.lightAngle) * 31L + (this.lightMoon ? 1 : 0);
        h = h * 31L + Double.doubleToLongBits(this.anchorR);
        h = h * 31L + Double.doubleToLongBits(this.anchorU);
        h = h * 31L + Double.doubleToLongBits(this.anchorL);
        h = h * 31L + Float.floatToIntBits(this.strength);
        h = h * 31L + this.renderedCasterKey;
        return h * 31L + (this.mapValid ? 1 : 0);
    }

    /**
     * Binds the Tessera terrain uniforms: the frame's light ({@code frame} true: main camera passes) or no tint (other
     * cameras: LOD reuse captures, the shadow casters).
     */
    /** Creates bindTerrain's resources (outside any render pass: GameRenderer, at the start of each frame). */
    public void ensureResources() {
        if (this.shadow == null) {
            this.shadow = new TextureTarget("Tessera shadow map", SHADOW_SIZE, SHADOW_SIZE, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        }
        if (this.lightUbo == null) {
            this.writeFrameLight(1, 1, new Matrix4f(), new Matrix4f(), false);
        }
        if (this.offUbo == null) {
            this.offUbo = new MappableRingBuffer(() -> "Tessera light off", 130, LIGHT_UBO_SIZE);
            this.write(this.offUbo, new Matrix4f(), 1, 1, 0.0F, false);
        }
        if (this.standIn == null) {
            this.standIn = new TextureTarget("Tessera stand-in", 1, 1, GpuFormat.RGBA8_UNORM, null);
            // compiled up front: the god rays' pass is first used at a sunrise, mid-game (no hitch then)
            RenderSystem.getCompiledPipeline(RenderPipelines.TESSERA_SHAFTS);
            RenderSystem.getCompiledPipeline(RenderPipelines.TESSERA_BLOOM_TILES);
            RenderSystem.getCompiledPipeline(RenderPipelines.TESSERA_BLOOM_COMBINE);
            RenderSystem.getCompiledPipeline(RenderPipelines.TESSERA_REFLECT_SOURCE_AO);
        }
    }

    /** RenderSystem.bindDefaultUniforms: bindTerrain once its resources exist (it may run inside a pass). */
    public void bindDefault(final RenderPass pass) {
        if (this.shadow != null && this.lightUbo != null && this.offUbo != null && this.standIn != null) {
            this.bindTerrain(pass, this.levelFrame);
        }
    }

    public void bindTerrain(final RenderPass pass, final boolean frame) {
        if (this.shadow == null) {
            this.shadow = new TextureTarget("Tessera shadow map", SHADOW_SIZE, SHADOW_SIZE, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        }
        if (this.lightUbo == null) {
            this.writeFrameLight(1, 1, new Matrix4f(), new Matrix4f(), false);
        }
        if (this.offUbo == null) {
            this.offUbo = new MappableRingBuffer(() -> "Tessera light off", 130, LIGHT_UBO_SIZE);
            this.write(this.offUbo, new Matrix4f(), 1, 1, 0.0F, false);
        }
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        if (this.standIn == null) {
            this.standIn = new TextureTarget("Tessera stand-in", 1, 1, GpuFormat.RGBA8_UNORM, null);
        }
        // the shadow casters render into the shadow map: bind an unused 1x1 stand-in there
        GpuTextureView shadowView = frame ? this.shadow.getDepthTextureView() : this.standIn.getColorTextureView();
        pass.setUniform("TesseraShadow", shadowView, nearest);
        if (this.farView != null) pass.setUniform("TesseraFar", this.farView, this.farSampler);
        else pass.setUniform("TesseraFar", this.standIn.getColorTextureView(), nearest);
        if (this.fineView != null) pass.setUniform("TesseraFarFine", this.fineView, this.farSampler);
        else pass.setUniform("TesseraFarFine", this.standIn.getColorTextureView(), nearest);
        pass.setUniform("TesseraLight", frame ? this.lightUbo.currentBuffer() : this.offUbo.currentBuffer());
        pass.setUniform("TesseraReflect", frame && this.reflectView != null ? this.reflectView : this.standIn.getColorTextureView(), nearest);
        pass.setUniform("TesseraCloudMap", frame && this.cloudView != null ? this.cloudView : this.standIn.getColorTextureView(),
            RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
        pass.setUniform("TesseraMaterial", materials && this.materialView != null ? this.materialView : this.standIn.getColorTextureView(), nearest);
        pass.setUniform("TesseraShafts", frame && this.shaftsView != null ? this.shaftsView : this.standIn.getColorTextureView(),
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
    }

    private static float smooth(final float a, final float b, final float x) {
        float t = Math.min(1.0F, Math.max(0.0F, (x - a) / (b - a)));
        return t * t * (3.0F - 2.0F * t);
    }
}
