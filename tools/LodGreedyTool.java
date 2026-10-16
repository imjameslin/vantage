import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.renderer.chunk.LodGreedy;
import net.minecraft.client.renderer.chunk.TerrainMeshInfo;

/**
 * Stage 3 round 1b diagnostics: offline quad-reduction analysis of LOD slab-layers dumped by the game with
 * {@code -Dmc.lod.dumpDir=<dir>} (LodMesher#dump: per record level, layer, quads, atlas w, atlas h, then the 28-byte vertices).
 * usage: java -Dmc.lod.greedyDiag=true -cp <client jar>:<libs> tools/LodGreedyTool.java <dir> [tol...]
 */
public class LodGreedyTool {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        int[] tols = new int[Math.max(1, args.length - 1)];
        tols[0] = 4;
        for (int i = 1; i < args.length; i++) {
            tols[i - 1] = Integer.parseInt(args[i]);
        }
        for (int level = 0; level < 8; level++) {
            Path f = dir.resolve("lod-l" + level + ".bin");
            if (!Files.exists(f)) {
                continue;
            }
            byte[] all = Files.readAllBytes(f);
            ByteBuffer in = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
            long[] quadsByLayer = new long[3];
            long[] slotCount = new long[7];
            long records = 0;
            java.util.List<Object[]> recs = new java.util.ArrayList<>();
            while (in.remaining() >= 20) {
                int lv = in.getInt();
                int layer = in.getInt();
                int quads = in.getInt();
                int aw = in.getInt();
                int ah = in.getInt();
                byte[] v = new byte[quads * 112];
                in.get(v);
                recs.add(new Object[]{layer, quads, aw, ah, v});
                quadsByLayer[layer] += quads;
                records++;
                ByteBuffer b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN);
                float[] p = new float[12];
                for (int q = 0; q < quads; q++) {
                    for (int k = 0; k < 4; k++) {
                        for (int c = 0; c < 3; c++) {
                            p[3 * k + c] = b.getFloat(q * 112 + k * 28 + 4 * c);
                        }
                    }
                    slotCount[TerrainMeshInfo.classifyQuad(p)]++;
                }
            }
            System.out.printf("level %d: %d slab-layers, quads SOLID %d CUTOUT %d TRANSLUCENT %d, slots (D N E U X S W) %s%n", level, records,
                quadsByLayer[0], quadsByLayer[1], quadsByLayer[2], java.util.Arrays.toString(slotCount));
            for (int tol : tols) {
                for (int pairs = 0; pairs < 2; pairs++) {
                    LodGreedy.QUADS_IN.set(0);
                    LodGreedy.QUADS_OUT.set(0);
                    LodGreedy.PAIRS_REMOVED.set(0);
                    LodGreedy.MERGED_FACES.set(0);
                    LodGreedy.REJ_PARTIAL.set(0);
                    LodGreedy.REJ_COLOR.set(0);
                    LodGreedy.REJ_UV.set(0);
                    LodGreedy.REJ_COVER.set(0);
                    LodGreedy.SINGLES.set(0);
                    long t0 = System.nanoTime();
                    String only = System.getenv("LOD_LAYER");
                    for (Object[] r : recs) {
                        if (only != null && Integer.parseInt(only) != (Integer)r[0]) {
                            continue;   // LOD_LAYER=<0 SOLID | 1 CUTOUT | 2 TRANSLUCENT>: one layer only
                        }
                        byte[] v = (byte[])r[4];
                        int quads = (Integer)r[1];
                        ByteBuffer d = ByteBuffer.allocateDirect(v.length).order(ByteOrder.nativeOrder());
                        d.put(v).flip();
                        LodGreedy.reduce(d, quads, pairs == 1 && (Integer)r[0] == 0, tol, (Integer)r[2], (Integer)r[3]);
                    }
                    long in2 = LodGreedy.QUADS_IN.get();
                    long out = LodGreedy.QUADS_OUT.get();
                    System.out.printf("  tol %3d pairs %d: %d -> %d (%.1f %%), pairs removed %d, merged faces %d, rej partial %d color %d uv %d cover %d, single groups %d, %.1f ms%n",
                        tol, pairs, in2, out, 100.0 * out / Math.max(1, in2), LodGreedy.PAIRS_REMOVED.get(), LodGreedy.MERGED_FACES.get(), LodGreedy.REJ_PARTIAL.get(),
                        LodGreedy.REJ_COLOR.get(), LodGreedy.REJ_UV.get(), LodGreedy.REJ_COVER.get(), LodGreedy.SINGLES.get(), (System.nanoTime() - t0) / 1e6);
                }
            }
        }
    }
}
