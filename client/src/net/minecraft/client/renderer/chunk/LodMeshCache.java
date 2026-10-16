package net.minecraft.client.renderer.chunk;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.jspecify.annotations.Nullable;

/**
 * Stage 3 round 6: on-disk cache of built LOD meshes ({@code mc.lod.meshCache}, default true; {@code mc.lod.meshCacheMB}, default
 * 8192). A node's sections depend on the captured 3x3 node blobs (own data + neighbours), the node's flags, the mesher's
 * settings and the rendering context. An entry is keyed by the node (level, nx, nz) and checked by a SHA-1 content
 * key over its captured inputs; settings and the content identity from {@link LodMeshCacheContext} name the cache directory
 * ({@code <game dir>/lod/<store>/mesh-cache/<fingerprint>}): a changed mesher / format / option builds into a new directory and the
 * inactive directories stay warm within one aggregate disk budget, oldest first to be evicted. The selection rule
 * (tau, area / distance, occlusion, radius) is not part of either, so every
 * selection reuses the cache. Unknown Fabric model/color callback dependencies disable this persistent cache while
 * leaving ordinary meshing active. {@code mc.lod.meshCacheVerify=true} rebuilds hits and compares every mesh byte.
 *
 * <p>Layout: regions of 8 x 8 nodes per level, {@code L<level>/m.<rx>.<rz>.bin}: magic, level, rx, rz, 64 lengths, the entries
 * (deflated: content key, surface y range, sections). In memory an LRU of regions ({@code mc.lod.meshCacheRamMB}, default 256);
 * dirty regions are written whole (temp file + atomic rename) at eviction and flush. Thread-safe (one lock).
 */
final class LodMeshCache {
    static final boolean ENABLED = Lod.flag("mc.lod.meshCache", true);
    /** Bump when changing the on-disk representation. Class-content identity separately invalidates mesher code changes. */
    static final int FORMAT = 3;
    private static final int MAGIC = 0x4C4D4331;
    private static final int SIDE = 8;
    private static final int REGION_HEADER_BYTES = 16 + SIDE * SIDE * 4;
    private static final int MAX_ENTRY_BYTES = 128 << 20;
    static final boolean VERIFY = Lod.flag("mc.lod.meshCacheVerify", false);
    /** Properties that change the mesh output (everything mc.lod.* except the selection / runtime ones listed here).
     * Retired experiment names remain excluded for compatibility with existing launch settings; they have no runtime path. */
    private static final java.util.Set<String> RUNTIME = java.util.Set.of("mc.lod", "mc.lod.radius", "mc.lod.tau", "mc.lod.tauLevels", "mc.lod.area",
        "mc.lod.secOccl", "mc.lod.hiz", "mc.lod.draw", "mc.lod.occlude", "mc.lod.occlusion", "mc.lod.fusedCull", "mc.lod.layerMask", "mc.lod.fog", "mc.lod.fogDist",
        "mc.lod.fogEdge", "mc.lod.fogSkip", "mc.lod.gpuMB", "mc.lod.ramMB", "mc.lod.threads", "mc.lod.stats", "mc.lod.meshCache", "mc.lod.meshCacheMB",
        "mc.lod.meshCacheRamMB", "mc.lod.meshCacheVerify", "mc.lod.lean", "mc.lod.hysteresis", "mc.lod.occlMargin", "mc.lod.occlLag", "mc.lod.fadeMs", "mc.lod.disk", "mc.lod.sp",
        "mc.lod.genDiskMB", "mc.lod.maxLevel", "mc.lod.dumpDir", "mc.lod.flat0Px", "mc.lod.genOnly", "mc.lod.palette", "mc.lod.directQuads",
        "mc.lod.solidQuery", "mc.lod.solidQuery.memoryMiB", "mc.lod.compactWorkBuffers", "mc.lod.reuse", "mc.lod.layers", "mc.lod.layers.perceptual",
        "mc.lod.reusePartial", "mc.lod.reuseTiles",
        // Audited draw-metadata packing/lookup controls; cached mesh bytes are already produced before these run.
        "mc.lod.cacheItems", "mc.lod.cacheGroups", "mc.lod.cacheGroups.diagnostics", "mc.lod.itemBuildProfile", "mc.lod.prepareProfile",
        "mc.lod.cacheNodeItems", "mc.lod.cacheNodeItemsMB", "mc.lod.atomicMeshHandoff", "mc.lod.asyncItems", "mc.lod.nearHandoff",
        // Audited image scheduling/shading and material-publication controls: none produces CPU mesh bytes.
        // Enumerate exact names so an unknown future async/material setting still invalidates the cache.
        "mc.lod.async", "mc.lod.async.active", "mc.lod.async.holdExact", "mc.lod.async.producerProbe",
        "mc.lod.async.producerProbe.guard", "mc.lod.async.producerProbe.refreshes", "mc.lod.async.consumerProbe",
        "mc.lod.async.shade", "mc.lod.async.shadeLayers", "mc.lod.async.shadeDiagnostics", "mc.lod.async.shadeAtlasFamily",
        "mc.lod.materialInputs");

    private final Path dir;
    private final long ramBytes;
    private final long diskCap;
    private final LinkedHashMap<Long, Region> regions = new LinkedHashMap<>(64, 0.75F, true);
    private long ramUsed;
    private long diskUsed;
    long hits;
    long misses;
    long stores;
    long loadNanos;
    long verified;
    long verifyFailures;

    private static final class Region {
        final int level;
        final int rx;
        final int rz;
        final byte[][] blobs = new byte[SIDE * SIDE][];
        boolean dirty;
        long bytes;
        long fileBytes;
        long reservedBytes;

        Region(final int level, final int rx, final int rz) {
            this.level = level;
            this.rx = rx;
            this.rz = rz;
        }
    }

    private LodMeshCache(final Path dir) {
        this.dir = dir;
        this.ramBytes = Math.max(32L, Long.getLong("mc.lod.meshCacheRamMB", 256L)) << 20;
        this.diskCap = Math.min(Long.MAX_VALUE >> 20, Math.max(64L, Long.getLong("mc.lod.meshCacheMB", 8192L))) << 20;
    }

    /** The mesher settings that shape the output (plus the atlas and the vanilla options the LOD compilers read). */
    static String fingerprint(final int blendRadius, final boolean ao, final boolean cutoutLeaves, final int atlasW, final int atlasH,
        final String renderingContext) {
        TreeMap<String, String> props = new TreeMap<>();
        for (String k : System.getProperties().stringPropertyNames()) {
            if (k.startsWith("mc.terrain.") || k.equals("mc.terrain")
                || k.startsWith("mc.lod.") && !RUNTIME.contains(k) && !k.startsWith("mc.lod.wave")) {
                props.put(k, System.getProperty(k));
            }
        }
        String s = "format=" + FORMAT + ";compact=" + LodCompact.RECORD_BYTES + ";blend=" + blendRadius + ";ao=" + ao + ";cutoutLeaves=" + cutoutLeaves
            + ";atlas=" + atlasW + "x" + atlasH + ";context=" + renderingContext
            + ";version=" + net.minecraft.SharedConstants.getCurrentVersion().id() + ";props=" + props;
        return hex(sha1(s.getBytes(StandardCharsets.UTF_8)), 12);
    }

    /** One owner per dedicated cache base, under the launcher's existing game-directory serialization. Flush the
     * previous owner before reopening; this is not a multi-writer protocol. The current namespace is never evicted,
     * even if a lowered cap leaves it oversized (further growth is refused). Directory mtime records last open/use. */
    static LodMeshCache open(final Path base, final String fingerprint) {
        if (!fingerprint.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("invalid mesh cache namespace");
        }
        try {
            Files.createDirectories(base);
            if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("mesh cache base is not a real directory");
            }
            Path dir = base.toRealPath().resolve(fingerprint);
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(dir);
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("mesh cache namespace is not a real directory");
            }
            LodMeshCache cache = new LodMeshCache(dir);
            cache.diskUsed = diskBytes(dir.getParent());
            cache.makeRoom(0L);
            Files.setLastModifiedTime(dir, FileTime.fromMillis(System.currentTimeMillis()));
            Lod.LOGGER.info("[lod] mesh cache {}", dir);
            return cache;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("cannot open mesh cache", e);
        }
    }

    private record Namespace(Path path, long lastUsed) { }

    /** diskUsed includes all siblings and reservations for dirty regions, including their headers. */
    private boolean makeRoom(final long extra) {
        if (this.diskUsed <= this.diskCap - extra) return true;
        try {
            this.checkNamespace();
            List<Namespace> inactive = new ArrayList<>();
            try (var paths = Files.list(this.dir.getParent())) {
                for (Path p : (Iterable<Path>)paths::iterator) {
                    if (!p.equals(this.dir) && p.getFileName().toString().matches("[A-Za-z0-9_-]+")
                        && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                        inactive.add(new Namespace(p, Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis()));
                    }
                }
            }
            inactive.sort(java.util.Comparator.comparingLong(Namespace::lastUsed).thenComparing(n -> n.path.toString()));
            for (Namespace n : inactive) {
                long before = diskBytes(n.path);
                deleteTree(n.path);
                // A failed/partial delete must not manufacture free space.
                this.diskUsed -= Math.max(0L, before - diskBytes(n.path));
                if (this.diskUsed <= this.diskCap - extra) return true;
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            Lod.LOGGER.warn("[lod] could not prune mesh cache: {}", e.toString());
        }
        return false;
    }

    private static void deleteTree(final Path p) {
        try (var w = Files.walk(p)) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(q -> {
                try {
                    Files.deleteIfExists(q);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException | java.io.UncheckedIOException ignored) {
        }
    }

    // ------------------------------------------------------------------------------------------------------------- key

    /** SHA-1 content key of a node mesh: level, position, flags and the 3x3 input blobs (null = missing). */
    static byte[] key(final int level, final int nx, final int nz, final boolean flat, final boolean stripTrees, final byte @Nullable [][] blobs) {
        MessageDigest md = digest();
        ByteBuffer h = ByteBuffer.allocate(16).putInt(level).putInt(nx).putInt(nz).putInt((flat ? 1 : 0) | (stripTrees ? 2 : 0));
        md.update(h.array());
        for (byte[] b : blobs) {
            ByteBuffer l = ByteBuffer.allocate(4).putInt(b == null ? -1 : b.length);
            md.update(l.array());
            if (b != null) {
                md.update(b);
            }
        }
        return md.digest();
    }

    /** Store blobs are immutable; retain the same nine versions for BOTH the key and a cache-miss rebuild. */
    record Inputs(byte[] key, byte[][] blobs) {
        LodData[] decode() throws IOException {
            LodData[] data = new LodData[9];
            for (int i = 0; i < data.length; i++) {
                data[i] = this.blobs[i] == null ? null : LodData.decode(this.blobs[i]);
            }
            return data;
        }

        /** As decode, through {@code decoder} (index 0-8, blob). */
        LodData[] decode(final BlobDecoder decoder) throws IOException {
            LodData[] data = new LodData[9];
            for (int i = 0; i < data.length; i++) {
                data[i] = this.blobs[i] == null ? null : decoder.decode(i, this.blobs[i]);
            }
            return data;
        }
    }

    interface BlobDecoder {
        LodData decode(int index, byte[] blob) throws IOException;
    }

    static Inputs capture(final int level, final int nx, final int nz, final boolean flat, final boolean stripTrees,
        final java.util.function.BiFunction<Integer, Integer, byte[]> read) {
        byte[][] blobs = new byte[9][];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                blobs[(dz + 1) * 3 + dx + 1] = read.apply(nx + dx, nz + dz);
            }
        }
        return new Inputs(key(level, nx, nz, flat, stripTrees, blobs), blobs);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha1(final byte[] b) {
        return digest().digest(b);
    }

    private static String hex(final byte[] b, final int chars) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.substring(0, Math.min(chars, sb.length()));
    }

    // ------------------------------------------------------------------------------------------------------------- entries

    /** A cached mesh: the node's surface y range and its sections. */
    record Entry(float yMin, float yMax, List<LodMesher.Section> sections) {
    }

    /** Verification mode rebuilds using the same input snapshot and checks every GPU byte, not just a screenshot. */
    synchronized void verify(final Entry cached, final float yMin, final float yMax, final List<LodMesher.Section> sections) {
        this.verified++;
        if (!sameMesh(cached, yMin, yMax, sections)) {
            this.verifyFailures++;
            Lod.LOGGER.error("[lod] mesh cache verification mismatch ({} failures / {} verified)", this.verifyFailures, this.verified);
        }
    }

    static boolean sameMesh(final Entry cached, final float yMin, final float yMax, final List<LodMesher.Section> sections) {
        if (Float.floatToIntBits(cached.yMin) != Float.floatToIntBits(yMin)
            || Float.floatToIntBits(cached.yMax) != Float.floatToIntBits(yMax) || cached.sections.size() != sections.size()) {
            return false;
        }
        for (int i = 0; i < sections.size(); i++) {
            LodMesher.Section a = cached.sections.get(i), b = sections.get(i);
            if (a.originX != b.originX || a.originY != b.originY || a.originZ != b.originZ || a.level != b.level
                || !java.util.Arrays.equals(a.compact, b.compact) || !java.util.Arrays.equals(a.quads, b.quads)) {
                return false;
            }
            for (int l = 0; l < 3; l++) {
                if (!java.util.Objects.equals(a.vertices[l], b.vertices[l]) || !java.util.Arrays.equals(a.clusters[l], b.clusters[l])) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The cached mesh of node (level, nx, nz) if its content key matches, else null. */
    @Nullable Entry get(final int level, final int nx, final int nz, final byte[] key) {
        byte[] blob;
        synchronized (this) {
            Region r = this.region(level, Math.floorDiv(nx, SIDE), Math.floorDiv(nz, SIDE));
            blob = r.blobs[Math.floorMod(nz, SIDE) * SIDE + Math.floorMod(nx, SIDE)];
        }
        if (blob == null || blob.length < 24) {
            synchronized (this) {
                this.misses++;
            }
            return null;
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            if (blob[i] != key[i]) {
                synchronized (this) {
                    this.misses++;
                }
                return null;
            }
        }
        try {
            Entry e = decode(blob);
            synchronized (this) {
                this.hits++;
                this.loadNanos += System.nanoTime() - t0;
            }
            return e;
        } catch (IOException | RuntimeException ex) {
            Lod.LOGGER.warn("[lod] bad mesh cache entry {} {} {}: {}", level, nx, nz, ex.toString());
            synchronized (this) {
                this.misses++;
            }
            return null;
        }
    }

    /** Stores the mesh of node (level, nx, nz) under {@code key} (unless the disk cap is reached). */
    void put(final int level, final int nx, final int nz, final byte[] key, final float yMin, final float yMax, final List<LodMesher.Section> sections) {
        byte[] blob;
        try {
            blob = encode(key, yMin, yMax, sections);
        } catch (IOException e) {
            return;
        }
        synchronized (this) {
            Region r = this.region(level, Math.floorDiv(nx, SIDE), Math.floorDiv(nz, SIDE));
            int i = Math.floorMod(nz, SIDE) * SIDE + Math.floorMod(nx, SIDE);
            byte[] old = r.blobs[i];
            long delta = blob.length - (old == null ? 0 : old.length);
            // A smaller dirty replacement cannot free its old on-disk bytes until the atomic rename succeeds.
            long reserved = Math.max(r.fileBytes, REGION_HEADER_BYTES + r.bytes + delta);
            long growth = reserved - r.reservedBytes;
            if (growth > 0 && !this.makeRoom(growth)) {
                return;
            }
            r.blobs[i] = blob;
            r.bytes += delta;
            r.dirty = true;
            this.ramUsed += delta;
            this.diskUsed += growth;
            r.reservedBytes = reserved;
            this.stores++;
            this.evict(r);
        }
    }

    private static byte[] encode(final byte[] key, final float yMin, final float yMax, final List<LodMesher.Section> sections) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(1 << 16);
        DataOutputStream out = new DataOutputStream(raw);
        out.writeFloat(yMin);
        out.writeFloat(yMax);
        out.writeInt(sections.size());
        for (LodMesher.Section s : sections) {
            out.writeInt(s.originX);
            out.writeInt(s.originY);
            out.writeInt(s.originZ);
            out.writeInt(s.level);
            for (int l = 0; l < 3; l++) {
                ByteBuffer v = s.vertices[l];
                int[] c = s.clusters[l];
                out.writeBoolean(v != null);
                if (v == null) {
                    continue;
                }
                out.writeBoolean(s.compact[l]);
                out.writeInt(s.quads[l]);
                ByteBuffer d = v.duplicate();
                byte[] b = new byte[d.remaining()];
                d.get(b);
                out.writeInt(b.length);
                out.write(b);
                out.writeInt(c == null ? -1 : c.length);
                if (c != null) {
                    for (int x : c) {
                        out.writeInt(x);
                    }
                }
            }
        }
        out.flush();
        byte[] plain = raw.toByteArray();
        if (plain.length > MAX_ENTRY_BYTES) {
            throw new IOException("mesh cache entry too large");
        }
        Deflater def = new Deflater(1);
        def.setInput(plain);
        def.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream(plain.length / 2 + 64);
        z.write(key, 0, 20);
        z.write(ByteBuffer.allocate(4).putInt(plain.length).array());
        byte[] buf = new byte[1 << 16];
        while (!def.finished()) {
            int n = def.deflate(buf);
            z.write(buf, 0, n);
        }
        def.end();
        return z.toByteArray();
    }

    private static Entry decode(final byte[] blob) throws IOException {
        int plainLen = ByteBuffer.wrap(blob, 20, 4).getInt();
        if (plainLen < 12 || plainLen > MAX_ENTRY_BYTES) {
            throw new IOException("invalid entry size");
        }
        byte[] plain = new byte[plainLen];
        Inflater inf = new Inflater();
        inf.setInput(blob, 24, blob.length - 24);
        try {
            int got = 0;
            while (got < plainLen) {
                int n = inf.inflate(plain, got, plainLen - got);
                if (n == 0) {
                    throw new IOException("incomplete compressed entry");
                }
                got += n;
            }
            // Consume the trailer too: exact output length alone doesn't validate the zlib checksum.
            if (inf.inflate(new byte[1]) != 0 || !inf.finished() || inf.getRemaining() != 0) {
                throw new IOException("invalid compressed entry length or trailer");
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException(e);
        } finally {
            inf.end();
        }
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(plain));
        float yMin = in.readFloat();
        float yMax = in.readFloat();
        int n = in.readInt();
        if (!Float.isFinite(yMin) || !Float.isFinite(yMax) || yMin > yMax || n < 0 || n > 256 || n > in.available() / 19) {
            throw new IOException("invalid section header");
        }
        List<LodMesher.Section> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            LodMesher.Section s = new LodMesher.Section(in.readInt(), in.readInt(), in.readInt(), in.readInt());
            if (s.level < 0 || s.level > 7) {
                throw new IOException("invalid LOD level");
            }
            for (int l = 0; l < 3; l++) {
                if (!in.readBoolean()) {
                    continue;
                }
                s.compact[l] = in.readBoolean();
                s.quads[l] = in.readInt();
                int len = in.readInt();
                int stride = s.compact[l] ? LodCompact.RECORD_BYTES : 4 * LodMesher.STRIDE;
                if (len < 0 || len > in.available() - 4 || s.quads[l] < 0 || (s.compact[l] ? len < (long)s.quads[l] * stride || len % 16 != 0 : len != (long)s.quads[l] * stride)) {
                    throw new IOException("invalid vertex length");
                }
                ByteBuffer v = ByteBuffer.allocateDirect(len).order(ByteOrder.nativeOrder());
                byte[] b = new byte[len];
                in.readFully(b);
                v.put(b).flip();
                s.vertices[l] = v;
                int cl = in.readInt();
                if (cl < -1 || cl > in.available() / 4 || cl > (long)s.quads[l] * TerrainClusters.CLUSTER_INTS
                    || cl >= 0 && cl % TerrainClusters.CLUSTER_INTS != 0) {
                    throw new IOException("invalid cluster length");
                }
                if (cl >= 0) {
                    int[] c = new int[cl];
                    for (int k = 0; k < cl; k++) {
                        c[k] = in.readInt();
                    }
                    for (int k = 0; k < cl; k += TerrainClusters.CLUSTER_INTS) {
                        int first = c[k] & 0xffff, count = ((c[k] >>> 16) & 63) + 1, slot = (c[k] >>> 22) & 7;
                        if (first + count > s.quads[l] || slot >= TerrainMeshInfo.SLOT_COUNT) {
                            throw new IOException("cluster outside vertex range");
                        }
                    }
                    s.clusters[l] = c;
                }
            }
            out.add(s);
        }
        if (in.available() != 0) {
            throw new IOException("trailing entry data");
        }
        return new Entry(yMin, yMax, out);
    }

    // ------------------------------------------------------------------------------------------------------------- regions

    private Region region(final int level, final int rx, final int rz) {
        long k = (long)level << 56 | ((long)rx & 0xFFFFFFFL) << 28 | (long)rz & 0xFFFFFFFL;
        Region r = this.regions.get(k);
        if (r == null) {
            r = new Region(level, rx, rz);
            this.read(r);
            this.regions.put(k, r);
            this.ramUsed += r.bytes;
            this.evict(r);
        }
        return r;
    }

    private void evict(final Region keep) {
        Iterator<Map.Entry<Long, Region>> it = this.regions.entrySet().iterator();
        while (this.ramUsed > this.ramBytes && it.hasNext()) {
            Region r = it.next().getValue();
            if (r == keep) {
                continue;
            }
            if (r.dirty) {
                this.write(r);
                if (r.dirty) this.diskUsed -= r.reservedBytes - r.fileBytes;
            }
            this.ramUsed -= r.bytes;
            it.remove();
        }
    }

    synchronized void flush() {
        for (Region r : this.regions.values()) {
            if (r.dirty) {
                this.write(r);
            }
        }
    }

    private Path file(final Region r) {
        return this.dir.resolve("L" + r.level).resolve("m." + r.rx + "." + r.rz + ".bin");
    }

    private void checkNamespace() throws IOException {
        if (!Files.isDirectory(this.dir.getParent(), LinkOption.NOFOLLOW_LINKS)
            || !Files.isDirectory(this.dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("mesh cache path is not a real directory");
        }
    }

    private void checkRegionDirectory(final Path level, final boolean create) throws IOException {
        this.checkNamespace();
        if (create && !Files.exists(level, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(level);
        if (!Files.isDirectory(level, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("mesh cache level is not a real directory");
        }
    }

    private void read(final Region r) {
        Path f = this.file(r);
        try {
            this.checkRegionDirectory(f.getParent(), false);
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) return;
            r.fileBytes = Files.size(f);
            r.reservedBytes = r.fileBytes;
        } catch (IOException e) {
            return;
        }
        try (InputStream raw = Files.newInputStream(f, LinkOption.NOFOLLOW_LINKS);
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw, 1 << 16))) {
            if (in.readInt() != MAGIC || in.readInt() != r.level || in.readInt() != r.rx || in.readInt() != r.rz) {
                return;
            }
            int[] lengths = new int[SIDE * SIDE];
            long payloadBytes = 0;
            for (int i = 0; i < lengths.length; i++) {
                lengths[i] = in.readInt();
                if (lengths[i] < 0 || lengths[i] > MAX_ENTRY_BYTES + (1 << 20)) {
                    throw new IOException("invalid region entry length");
                }
                payloadBytes += lengths[i];
            }
            if (payloadBytes != Files.size(f) - REGION_HEADER_BYTES) {
                throw new IOException("invalid region size");
            }
            for (int i = 0; i < lengths.length; i++) {
                if (lengths[i] > 0) {
                    byte[] b = new byte[lengths[i]];
                    in.readFully(b);
                    r.blobs[i] = b;
                    r.bytes += b.length;
                }
            }
        } catch (IOException e) {
            java.util.Arrays.fill(r.blobs, null);
            r.bytes = 0L;
        }
    }

    private void write(final Region r) {
        Path f = this.file(r);
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        boolean created = false;
        try {
            this.checkRegionDirectory(f.getParent(), true);
            if (Files.exists(f, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("mesh cache region is not a regular file");
            }
            // A stale temporary file was counted at open. Never truncate a temporary symlink or directory.
            if (Files.isRegularFile(tmp, LinkOption.NOFOLLOW_LINKS)) {
                long stale = Files.size(tmp);
                Files.delete(tmp);
                this.diskUsed -= stale;
            }
            try (OutputStream raw = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
                DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(raw, 1 << 16))) {
                created = true;
                out.writeInt(MAGIC);
                out.writeInt(r.level);
                out.writeInt(r.rx);
                out.writeInt(r.rz);
                for (byte[] b : r.blobs) {
                    out.writeInt(b == null ? 0 : b.length);
                }
                for (byte[] b : r.blobs) {
                    if (b != null) {
                        out.write(b);
                    }
                }
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            created = false;
            r.fileBytes = REGION_HEADER_BYTES + r.bytes;
            this.diskUsed += r.fileBytes - r.reservedBytes;
            r.reservedBytes = r.fileBytes;
            r.dirty = false;
        } catch (IOException e) {
            Lod.LOGGER.warn("[lod] could not write mesh cache {}: {}", f, e.toString());
        } finally {
            if (created) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    // If cleanup fails, count the leftover file before accepting any more growth.
                    try {
                        this.diskUsed += Files.size(tmp);
                    } catch (IOException statFailure) {
                        this.diskUsed = Long.MAX_VALUE;
                    }
                }
            }
        }
    }

    private static long diskBytes(final Path path) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return 0L;
        }
        long[] bytes = {0L};
        // No FOLLOW_LINKS: neither budget accounting nor pruning may reach outside the cache base.
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                bytes[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return bytes[0];
    }

    synchronized String statsJson() {
        return String.format(java.util.Locale.ROOT, "{\"hits\": %d, \"misses\": %d, \"stores\": %d, \"loadMsAvg\": %.3f, \"ramMB\": %.1f, \"diskMB\": %.1f, \"verified\": %d, \"verifyFailures\": %d}",
            this.hits, this.misses, this.stores, this.hits == 0 ? 0.0 : this.loadNanos / 1e6 / this.hits, this.ramUsed / 1048576.0, Math.max(0L, this.diskUsed) / 1048576.0,
            this.verified, this.verifyFailures);
    }
}
