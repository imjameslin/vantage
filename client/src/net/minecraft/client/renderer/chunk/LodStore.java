package net.minecraft.client.renderer.chunk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Stage 3 LOD store (docs/stage3/LOD-DESIGN.md §4): encoded {@link LodData} blobs per (level, node), grouped in regions of
 * 32 x 32 nodes. In memory: an LRU of regions bounded by {@code mc.lod.ramMB}; on disk (unless {@code -Dmc.lod.disk=false})
 * one file per region, {@code <dir>/L<level>/r.<rx>.<rz>.lod}: magic {@code LOD1}, level, rx, rz, 1024 blob lengths (0 = no
 * node), then the blobs in index order. A region is written whole (temp file + atomic rename) when dirty: by the periodic
 * flush, before it is evicted, and at close. All methods are thread-safe (one lock; disk I/O of one region at a time).
 */
final class LodStore {
    private static final int MAGIC = 0x4C4F4431;
    private final @Nullable Path dir;
    private final long ramBytes;
    private final LinkedHashMap<Long, Region> regions = new LinkedHashMap<>(256, 0.75F, true);
    private long blobBytes;
    long diskWrites;
    long diskReads;
    /** Round 3: disk cap (0 = none) and the distance of a region from the camera (farthest regions are deleted first). */
    private long diskCap;
    private @Nullable RegionDistance distance;
    /** Region key -> file size, for the disk cap (filled from the directory at the first write). */
    private @Nullable Map<Long, Long> fileSizes;
    private long fileBytes;
    long capDeletes;

    /** Distance in blocks from the camera to region (rx, rz) of level. */
    interface RegionDistance {
        double of(int level, int rx, int rz);
    }

    /** Round 3: keep at most {@code bytes} on disk; over it, the farthest region files are deleted (data that can be rebuilt). */
    synchronized void setDiskCap(final long bytes, final RegionDistance distance) {
        this.diskCap = bytes;
        this.distance = distance;
    }

    /** Bytes of this store's region files (tracked once a disk cap is set and something was written; else 0). */
    synchronized long trackedDiskBytes() {
        return this.fileBytes;
    }

    private void indexFiles() {
        this.fileSizes = new java.util.HashMap<>();
        this.fileBytes = 0L;
        if (this.dir == null) {
            return;
        }
        for (int level = 0; level < 8; level++) {
            Path d = this.dir.resolve(levelDir(level));
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (var s = Files.list(d)) {
                for (Path p : (Iterable<Path>)s::iterator) {
                    String n = p.getFileName().toString();
                    String[] parts = n.split("\\.");
                    if (parts.length == 4 && n.startsWith("r.") && n.endsWith(".lod")) {
                        try {
                            long size = Files.size(p);
                            this.fileSizes.put(regionKey(level, Integer.parseInt(parts[1]), Integer.parseInt(parts[2])), size);
                            this.fileBytes += size;
                        } catch (RuntimeException | IOException ignored) {
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void enforceCap(final Region justWritten) {
        if (this.diskCap <= 0L || this.dir == null || this.distance == null || this.fileSizes == null || this.fileBytes <= this.diskCap) {
            return;
        }
        List<long[]> all = new ArrayList<>();
        for (Map.Entry<Long, Long> e : this.fileSizes.entrySet()) {
            long k = e.getKey();
            int level = (int)(k >>> 56);
            int rx = (int)(k << 8 >> 36);
            int rz = (int)(k << 36 >> 36);
            all.add(new long[]{k, level, rx, rz, Double.doubleToLongBits(this.distance.of(level, rx, rz))});
        }
        all.sort((a, b) -> Double.compare(Double.longBitsToDouble(b[4]), Double.longBitsToDouble(a[4])));
        for (long[] r : all) {
            if (this.fileBytes <= this.diskCap * 9 / 10) {
                break;
            }
            if (r[0] == regionKey(justWritten.level, justWritten.rx, justWritten.rz)) {
                continue;
            }
            Region mem = this.regions.remove(r[0]);
            if (mem != null) {
                this.blobBytes -= mem.bytes;
            }
            try {
                Files.deleteIfExists(this.dir.resolve(levelDir((int)r[1])).resolve("r." + r[2] + "." + r[3] + ".lod"));
            } catch (IOException ignored) {
            }
            this.fileBytes -= this.fileSizes.remove(r[0]);
            this.capDeletes++;
        }
    }

    private static final class Region {
        final int level;
        final int rx;
        final int rz;
        final byte[][] blobs = new byte[1024][];
        boolean dirty;
        long bytes;

        Region(final int level, final int rx, final int rz) {
            this.level = level;
            this.rx = rx;
            this.rz = rz;
        }
    }

    LodStore(final @Nullable Path dir, final long ramBytes) {
        this.dir = dir;
        this.ramBytes = ramBytes;
    }

    static long regionKey(final int level, final int rx, final int rz) {
        return (long)level << 56 | ((long)rx & 0xFFFFFFFL) << 28 | (long)rz & 0xFFFFFFFL;
    }

    synchronized byte @Nullable [] get(final int level, final int nx, final int nz) {
        Region r = this.region(level, Math.floorDiv(nx, 32), Math.floorDiv(nz, 32));
        return r.blobs[Math.floorMod(nz, 32) * 32 + Math.floorMod(nx, 32)];
    }

    synchronized boolean has(final int level, final int nx, final int nz) {
        return this.get(level, nx, nz) != null;
    }

    synchronized void put(final int level, final int nx, final int nz, final byte[] blob) {
        Region r = this.region(level, Math.floorDiv(nx, 32), Math.floorDiv(nz, 32));
        int i = Math.floorMod(nz, 32) * 32 + Math.floorMod(nx, 32);
        byte[] old = r.blobs[i];
        long delta = blob.length - (old == null ? 0 : old.length);
        r.blobs[i] = blob;
        r.bytes += delta;
        r.dirty = true;
        this.blobBytes += delta;
        this.evict(r);
    }

    synchronized long memoryBytes() {
        return this.blobBytes;
    }

    synchronized int regionCount() {
        return this.regions.size();
    }

    private Region region(final int level, final int rx, final int rz) {
        long key = regionKey(level, rx, rz);
        Region r = this.regions.get(key);
        if (r == null) {
            r = new Region(level, rx, rz);
            this.read(r);
            this.regions.put(key, r);
            this.blobBytes += r.bytes;
            this.evict(r);
        }
        return r;
    }

    private void evict(final Region keep) {
        if (this.blobBytes <= this.ramBytes || this.dir == null) {
            return;
        }
        Iterator<Map.Entry<Long, Region>> it = this.regions.entrySet().iterator();
        while (this.blobBytes > this.ramBytes && it.hasNext()) {
            Region r = it.next().getValue();
            if (r == keep) {
                continue;
            }
            if (r.dirty) {
                this.write(r);
            }
            this.blobBytes -= r.bytes;
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
        return this.dir.resolve(levelDir(r.level)).resolve("r." + r.rx + "." + r.rz + ".lod");
    }

    /**
     * Directory of a level. Coarse levels carry the downsample rule's version (round 2: "c2" = snowy-leaves marker, "c3" = ice kept, "c4" = leaves over logs), so a
     * changed rule rebuilds them from level 0 instead of mixing old and new cells.
     */
    static String levelDir(final int level) {
        // Round 4: "t<max>" = tree lists (LodTrees) up to that level; their absence rebuilds coarse nodes from level 0.
        return level == 0 ? "L0" : "L" + level + "c4" + (LodData.LEAF_MIN != 4 ? "m" + LodData.LEAF_MIN : "") + (LodTrees.ENABLED ? "t" + LodTrees.MAX_LEVEL : "") + (LodData.FLUID_LEVELS ? "w" : "") + (LodData.ICE_LEVELS ? "i" : "");
    }

    private void read(final Region r) {
        if (this.dir == null) {
            return;
        }
        Path f = this.file(r);
        if (!Files.isRegularFile(f)) {
            return;
        }
        try (InputStream raw = Files.newInputStream(f); DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw, 1 << 16))) {
            if (in.readInt() != MAGIC || in.readInt() != r.level || in.readInt() != r.rx || in.readInt() != r.rz) {
                Lod.LOGGER.warn("[lod] ignoring foreign region file {}", f);
                return;
            }
            int[] lengths = new int[1024];
            for (int i = 0; i < 1024; i++) {
                lengths[i] = in.readInt();
            }
            for (int i = 0; i < 1024; i++) {
                if (lengths[i] > 0) {
                    byte[] b = new byte[lengths[i]];
                    in.readFully(b);
                    r.blobs[i] = b;
                    r.bytes += b.length;
                }
            }
            this.diskReads++;
        } catch (IOException e) {
            Lod.LOGGER.warn("[lod] unreadable region file {} ({}); starting it empty", f, e.toString());
            java.util.Arrays.fill(r.blobs, null);
            r.bytes = 0L;
        }
    }

    private void write(final Region r) {
        r.dirty = false;
        if (this.dir == null) {
            return;
        }
        Path f = this.file(r);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (OutputStream raw = Files.newOutputStream(tmp); DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(raw, 1 << 16))) {
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
            this.diskWrites++;
            if (this.diskCap > 0L) {
                if (this.fileSizes == null) {
                    this.indexFiles();
                }
                long size = Files.size(f);
                Long old = this.fileSizes.put(regionKey(r.level, r.rx, r.rz), size);
                this.fileBytes += size - (old == null ? 0L : old);
                this.enforceCap(r);
            }
        } catch (IOException e) {
            Lod.LOGGER.warn("[lod] could not write {}: {}", f, e.toString());
        }
    }

    /** Disk bytes under the store directory (stats; walks the files). */
    long diskBytes() {
        if (this.dir == null || !Files.isDirectory(this.dir)) {
            return 0L;
        }
        try (var s = Files.walk(this.dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return -1L;
        }
    }

    /** Node coordinates (level, nx, nz) of every node of every region file on disk of {@code level} (startup index). */
    List<long[]> listDiskRegions(final int level) {
        List<long[]> out = new ArrayList<>();
        if (this.dir == null) {
            return out;
        }
        Path d = this.dir.resolve(levelDir(level));
        if (!Files.isDirectory(d)) {
            return out;
        }
        try (var s = Files.list(d)) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.startsWith("r.") && n.endsWith(".lod")) {
                    String[] parts = n.split("\\.");
                    try {
                        out.add(new long[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2])});
                    } catch (RuntimeException ignored) {
                    }
                }
            });
        } catch (IOException ignored) {
        }
        return out;
    }
}
