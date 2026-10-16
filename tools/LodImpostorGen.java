import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * Stage 3 round 4 (LOD tree stand-ins, docs/stage3/LOD-DESIGN.md §3.2): renders the impostor sprites of the far trees from the
 * vanilla jar's own block textures at build time (tools/build-client.sh writes them into the client jar under
 * assets/minecraft/textures/block/lod_tree_*.png, where vanilla's block atlas "directory" source stitches them; nothing derived
 * from Mojang's textures is committed to git).
 *
 * <p>Each sprite is an orthographic view of a canonical vanilla-shaped tree (foliage placers' ring / blob profiles, snow layers
 * on the sky-exposed leaves of the snowy variants) seen from azimuth 45 degrees and a low elevation, as the far trees are seen
 * from the ground or a hill: per texel a ray from the vertical plane through the trunk axis along the view direction, first hit
 * of the voxel model (leaves, logs, 2/16 snow layers) with vanilla's face shades (top 1.0, north / south 0.8, east / west 0.6)
 * and the block's 16x16 texture, 16 texels per block. Conifers bake their constant leaf tint (BlockColors: spruce
 * 0x619961); broadleaf sprites hold the untinted (grey) leaves only and are tinted per tree by the vertex colour (biome foliage),
 * so their trunk is left out. The stand-in geometry reads the canonical dimensions from LodTrees.SPRITES (keep in sync).
 *
 * usage: java tools/LodImpostorGen.java <vanilla jar> <out dir>
 */
public class LodImpostorGen {
    static final int T = 16;                 // texels per block
    static final double ELEV = Math.toRadians(Double.parseDouble(System.getProperty("elev", "20")));
    static final double AZ = Math.toRadians(45.0);
    /** AO strength (fraction of brightness lost with all 8 in-plane neighbours occupied) and the factor for faces seen through leaf holes. */
    static final double AO = Double.parseDouble(System.getProperty("ao", "0.8"));
    /** Extra darkening of leaf texels (the canopy's reduced sky light inside the crown and the interior seen between rings). */
    static final double LEAF_SHADE = Double.parseDouble(System.getProperty("leaf", "0.65"));
    static final double INNER = Double.parseDouble(System.getProperty("inner", "0.4"));

    // voxel model: type per block in a 16 x 24 x 16 box, origin at (8, 0, 8) = trunk base
    static final int NX = 17, NY = 26, NZ = 17, OX = 8, OZ = 8;
    static final int AIR = 0, LEAF = 1, LOG = 2;
    static int[] vox = new int[NX * NY * NZ];
    static boolean[] snow = new boolean[NX * NY * NZ];

    static int idx(int x, int y, int z) { return (y * NZ + z) * NX + x; }
    static int get(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= NX || y >= NY || z >= NZ) return AIR;
        return vox[idx(x, y, z)];
    }
    static void set(int x, int y, int z, int t) {
        if (x < 0 || y < 0 || z < 0 || x >= NX || y >= NY || z >= NZ) return;
        if (t == LEAF && vox[idx(x, y, z)] == LOG) return;
        vox[idx(x, y, z)] = t;
    }
    static void clear() { java.util.Arrays.fill(vox, AIR); java.util.Arrays.fill(snow, false); }

    static void ring(int y, int r, boolean skipCorners) {
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            if (skipCorners && r > 0 && Math.abs(dx) == r && Math.abs(dz) == r) continue;
            set(OX + dx, y, OZ + dz, LEAF);
        }
    }

    /** Vanilla SpruceFoliagePlacer with start radius 0, leaf radius 3: trunk 0..trunk-1, foliage from top down. */
    static int spruce(int trunk, int foliageTop, int foliageHeight, int leafRadius) {
        for (int y = 0; y < trunk; y++) set(OX, y, OZ, LOG);
        int r = 0, max = 1, min = 0;
        for (int y = foliageTop; y > foliageTop - foliageHeight - 1; y--) {
            ring(y, r, true);
            if (r >= max) { r = min; min = 1; max = Math.min(max + 1, leafRadius); } else r++;
        }
        return foliageTop;
    }

    /** Vanilla PineFoliagePlacer (radius 1, offset 1): radius grows 0, 1, 1, ... and shrinks on the last row. */
    static int pine(int trunk, int foliageHeight) {
        for (int y = 0; y < trunk; y++) set(OX, y, OZ, LOG);
        int top = trunk;     // offset 1 above the trunk top (trunk - 1)
        int r = 0;
        for (int y = top; y >= top - foliageHeight; y--) {
            ring(y, r, true);
            if (r >= 1 && y == top - foliageHeight + 1) r--; else if (r < 1) r++;
        }
        return top;
    }

    /** Vanilla BlobFoliagePlacer (oak: radius 2, height 3): rows top-1 .. top-3 radius 2 with random corners kept half the time, top rows radius 1. */
    static int blob(int trunk) {
        for (int y = 0; y < trunk; y++) set(OX, y, OZ, LOG);
        int top = trunk;
        for (int y = top; y >= top - 3; y--) {
            int r = y >= top - 1 ? 1 : 2;
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
                if (corner && (y == top || ((dx * 7 + dz * 13 + y * 5) & 1) == 0)) continue;
                set(OX + dx, y, OZ + dz, LEAF);
            }
        }
        return top;
    }

    /** Snow layers on every column's top leaf (vanilla freeze_top_layer). */
    static void snowTops() {
        for (int z = 0; z < NZ; z++) for (int x = 0; x < NX; x++) {
            for (int y = NY - 1; y >= 0; y--) {
                int t = get(x, y, z);
                if (t == AIR) continue;
                if (t == LEAF && y + 1 < NY) snow[idx(x, y + 1, z)] = true;
                break;
            }
        }
    }

    static int[] tex(ZipFile jar, String name) throws Exception {
        try (InputStream in = jar.getInputStream(jar.getEntry("assets/minecraft/textures/block/" + name + ".png"))) {
            BufferedImage img = ImageIO.read(in);
            int[] px = new int[T * T];
            for (int y = 0; y < T; y++) for (int x = 0; x < T; x++) px[y * T + x] = img.getRGB(x * img.getWidth() / T, y * img.getWidth() / T);
            return px;
        }
    }

    /** Snowy sprites: share of snow in a snow-layer texel, the rest the leaves under it (LOD_SPRITE_SNOW, default 1 = solid snow). */
    static final double SNOW_COVER = Double.parseDouble(System.getenv().getOrDefault("LOD_SPRITE_SNOW", "1"));

    static int mix(int a, int b, double t) {
        int out = 0xFF000000;
        for (int sh = 0; sh < 24; sh += 8) {
            int ca = a >>> sh & 255, cb = b >>> sh & 255;
            out |= (int)Math.round(ca + (cb - ca) * t) << sh;
        }
        return out;
    }

    static int mul(int argb, int tint, double shade) {
        int a = argb >>> 24;
        int r = (int)Math.round(((argb >> 16 & 255) * (tint >> 16 & 255) / 255.0) * shade);
        int g = (int)Math.round(((argb >> 8 & 255) * (tint >> 8 & 255) / 255.0) * shade);
        int b = (int)Math.round(((argb & 255) * (tint & 255) / 255.0) * shade);
        return a << 24 | Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b);
    }

    /**
     * Renders the current model: sprite w x h blocks, ground (y 0) at sprite row h - ground, trunk axis at the centre. Returns
     * the image. {@code withLogs}: false leaves logs transparent (broadleaf: tinted per tree).
     */
    static BufferedImage render(int wBlocks, int hBlocks, double y0, int[] leaf, int leafTint, int[] logSide, int[] logTop, int[] snowTex, boolean withLogs) {
        int W = wBlocks * T, H = hBlocks * T;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        double dx = Math.cos(ELEV) * Math.cos(AZ), dy = -Math.sin(ELEV), dz = Math.cos(ELEV) * Math.sin(AZ);
        double rx = -Math.sin(AZ), rz = Math.cos(AZ);   // horizontal right vector of the view
        double cx = OX + 0.5, cz = OZ + 0.5;
        for (int j = 0; j < H; j++) for (int i = 0; i < W; i++) {
            double s = (i + 0.5) / T - wBlocks / 2.0;
            double y = y0 + (H - j - 0.5) / T;
            double px = cx + s * rx, py = y, pz = cz + s * rz;
            // march from 12 blocks in front of the plane
            double t = -14.0, step = 0.01;
            int hit = 0;
            boolean through = false;
            for (; t < 14.0; t += step) {
                double qx = px + t * dx, qy = py + t * dy, qz = pz + t * dz;
                int bx = (int)Math.floor(qx), by = (int)Math.floor(qy), bz = (int)Math.floor(qz);
                int v = get(bx, by, bz);
                boolean sn = bx >= 0 && by >= 0 && bz >= 0 && bx < NX && by < NY && bz < NZ && snow[idx(bx, by, bz)] && qy - by < 0.125;
                if (v == AIR && !sn) continue;
                if (v == LOG && !withLogs) { hit = -1; break; }
                // face: which boundary did we cross last step
                double ox = qx - step * dx, oy = qy - step * dy, oz = qz - step * dz;
                int face;   // 0 top, 1 x-side, 2 z-side
                if (sn) {
                    face = oy >= by + 0.125 ? 0 : (int)Math.floor(ox) != bx ? 1 : 2;
                } else {
                    face = (int)Math.floor(oy) != by ? 0 : (int)Math.floor(ox) != bx ? 1 : 2;
                }
                double fu, fv;
                if (face == 0) { fu = qx - bx; fv = qz - bz; }
                else if (face == 1) { fu = qz - bz; fv = 1.0 - (qy - by); }
                else { fu = qx - bx; fv = 1.0 - (qy - by); }
                int tu = Math.min(T - 1, (int)(fu * T)), tv = Math.min(T - 1, (int)(fv * T));
                double shade = face == 0 ? 1.0 : face == 1 ? 0.6 : 0.8;
                // Vanilla smooth lighting: leaves have shade brightness 0.2, so faces next to other leaves / logs are darkened by
                // ambient occlusion (the real canopy is much darker than its texture). Average AO of the face from the 8 blocks
                // around the cell in front of it (in the face plane).
                int fx = face == 1 ? bx - 1 : bx, fy = face == 0 ? by + 1 : by, fz = face == 2 ? bz - 1 : bz;
                int occ = 0;
                for (int a = -1; a <= 1; a++) for (int b2 = -1; b2 <= 1; b2++) {
                    if (a == 0 && b2 == 0) continue;
                    int ox2 = a, oy2 = 0, oz2 = b2;
                    if (face == 1) { ox2 = 0; oy2 = a; oz2 = b2; }
                    if (face == 2) { ox2 = a; oy2 = b2; oz2 = 0; }
                    if (get(fx + ox2, fy + oy2, fz + oz2) != AIR) occ++;
                }
                shade *= 1.0 - AO * occ / 8.0;
                if (through) shade *= INNER;
                int c;
                if (sn) {
                    c = mul(snowTex[tv * T + tu], 0xFFFFFF, shade);
                    if (SNOW_COVER < 1.0) c = mix(mul(leaf[tv * T + tu] | 0xFF000000, leafTint, shade * LEAF_SHADE), c, SNOW_COVER);
                }
                else if (v == LOG) c = mul((face == 0 ? logTop : logSide)[tv * T + tu], 0xFFFFFF, shade);
                else {
                    int texel = leaf[tv * T + tu];
                    if ((texel >>> 24) < 128) { through = true; continue; }   // cutout leaves: the ray continues through the hole
                    c = mul(texel | 0xFF000000, leafTint, shade * LEAF_SHADE);
                }
                img.setRGB(i, j, c | 0xFF000000);
                hit = 1;
                break;
            }
        }
        return img;
    }

    /**
     * Writes {@code img} (canvas bottom at {@code y0} blocks above the sprite's anchor) cropped to its opaque texels in whole
     * blocks (symmetric about the trunk axis; whole blocks keep every sprite a multiple of 16 texels, so the block atlas keeps its
     * mip levels), and appends "name width height bottom" (blocks; bottom relative to the anchor) to the metadata.
     */
    static void write(BufferedImage img, double y0, File dir, String name, StringBuilder meta) throws Exception {
        int W = img.getWidth(), H = img.getHeight();
        int minX = W, maxX = -1, minY = H, maxY = -1;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if ((img.getRGB(x, y) >>> 24) != 0) { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
        }
        int half = Math.max(W / 2 - minX, maxX + 1 - W / 2);
        int halfBlocks = (half + T - 1) / T;
        int x0 = W / 2 - halfBlocks * T;
        int j0 = minY / T * T;
        int j1 = (maxY / T + 1) * T;
        BufferedImage out = img.getSubimage(Math.max(0, x0), j0, Math.min(W, 2 * halfBlocks * T), j1 - j0);
        ImageIO.write(out, "png", new File(dir, name + ".png"));
        meta.append(String.format(java.util.Locale.ROOT, "%s %d %d %.4f%n", name, out.getWidth() / T, out.getHeight() / T, y0 + (H - j1) / (double)T));
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args[1]);
        out.mkdirs();
        StringBuilder meta = new StringBuilder("# LOD tree impostor sprites (tools/LodImpostorGen.java): name width height bottom (blocks)\n");
        try (ZipFile jar = new ZipFile(args[0])) {
            int[] snowTex = tex(jar, "snow");
            int[] spruceLeaves = tex(jar, "spruce_leaves");
            int[] spruceLog = tex(jar, "spruce_log");
            int[] spruceLogTop = tex(jar, "spruce_log_top");
            int evergreen = 0x619961;
            // Conifers: anchor = trunk base (canvas bottom).
            for (boolean snowy : new boolean[]{false, true}) {
                clear();
                spruce(7, 8, 6, 3);
                if (snowy) snowTops();
                write(render(12, 15, 0.0, spruceLeaves, evergreen, spruceLog, spruceLogTop, snowTex, true), 0.0, out, "lod_tree_spruce" + (snowy ? "_snowy" : ""), meta);
                clear();
                pine(9, 4);
                if (snowy) snowTops();
                write(render(12, 15, 0.0, spruceLeaves, evergreen, spruceLog, spruceLogTop, snowTex, true), 0.0, out, "lod_tree_pine" + (snowy ? "_snowy" : ""), meta);
            }
            // Broadleaf crowns: anchor = the crown's lowest row (top - 3); leaves untinted (vertex tint), no trunk.
            String[] leaves = {"oak_leaves", "birch_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "cherry_leaves",
                "azalea_leaves", "flowering_azalea_leaves", "pale_oak_leaves"};
            for (String l : leaves) {
                int[] lt;
                try {
                    lt = tex(jar, l);
                } catch (Exception e) {
                    System.err.println("skip " + l + ": " + e);
                    continue;
                }
                clear();
                int top = blob(5);
                write(render(9, 8, top - 5, lt, 0xFFFFFF, spruceLog, spruceLogTop, snowTex, false), -2.0, out, "lod_tree_" + l.replace("_leaves", ""), meta);
            }
        }
        if (args.length > 2) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(args[2]), meta.toString());
        } else {
            System.out.print(meta);
        }
    }
}
