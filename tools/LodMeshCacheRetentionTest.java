package net.minecraft.client.renderer.chunk;

import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** CPU-only, single-owner handoffs; sparse pressure fixtures stay in the script's disposable directory. */
public final class LodMeshCacheRetentionTest {
    private static final long MB = 1L << 20;
    private static final long CAP = 64 * MB;
    private static final String REGION = "L0/m.0.0.bin";
    private static int checks;
    private static byte[] sample;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        String previous = System.getProperty("mc.lod.meshCacheMB");
        System.setProperty("mc.lod.meshCacheMB", "64");
        Path root = Files.createTempDirectory(Path.of(args[0]), "retention-");
        try {
            warmSwitch(root.resolve("warm"));
            sample = Files.readAllBytes(root.resolve("warm/a/" + REGION));
            openPressure(root.resolve("open-pressure"));
            growthPressure(root.resolve("growth-pressure"));
            headersAndPending(root.resolve("headers"));
            shrinkAndRetry(root.resolve("shrink"));
            oversizedActive(root.resolve("oversized"));
            staleTemporary(root.resolve("stale"));
            pathScope(root.resolve("scope"));
            System.out.println("LodMeshCacheRetentionTest: " + checks + " checks, 0 failures");
        } finally {
            if (previous == null) System.clearProperty("mc.lod.meshCacheMB");
            else System.setProperty("mc.lod.meshCacheMB", previous);
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    private static void check(String message, boolean ok) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static byte[] key(int tag) {
        byte[] key = new byte[20];
        Arrays.fill(key, (byte)tag);
        return key;
    }

    private static List<LodMesher.Section> mesh(int tag, int quads) {
        LodMesher.Section section = new LodMesher.Section(0, 0, 0, 0);
        byte[] bytes = new byte[quads * LodCompact.RECORD_BYTES];
        new java.util.Random(tag).nextBytes(bytes);
        section.vertices[0] = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        section.compact[0] = true;
        section.quads[0] = quads;
        return List.of(section);
    }

    private static void put(LodMeshCache cache, int nx) {
        cache.put(0, nx, 0, key(1), 0, 1, mesh(1, 1));
    }

    private static void warmSwitch(Path base) throws Exception {
        LodMeshCache a = LodMeshCache.open(base, "a");
        put(a, 0);
        a.flush();
        byte[] original = Files.readAllBytes(base.resolve("a/" + REGION));
        Files.setLastModifiedTime(base.resolve("a"), FileTime.fromMillis(1000));
        LodMeshCache b = LodMeshCache.open(base, "b");
        check("opening B retains A", Files.isRegularFile(base.resolve("a/" + REGION)));
        check("B cannot borrow A's matching content key", b.get(0, 0, 0, key(1)) == null);
        b.put(0, 0, 0, key(1), 0, 1, mesh(2, 1));
        b.flush();
        a = LodMeshCache.open(base, "a");
        check("return to A hits its own mesh", LodMeshCache.sameMesh(a.get(0, 0, 0, key(1)), 0, 1, mesh(1, 1)));
        check("A still checks the content key", a.get(0, 0, 0, key(2)) == null);
        check("return to A refreshes namespace recency", Files.getLastModifiedTime(base.resolve("a")).toMillis() > 1000);
        a.flush();
        a = LodMeshCache.open(base, "a");
        check("same namespace reopen hits without rewrite", a.get(0, 0, 0, key(1)) != null && a.stores == 0);
        check("A's bytes survive switches/reopens", Arrays.equals(original, Files.readAllBytes(base.resolve("a/" + REGION))));
        b = LodMeshCache.open(base, "b");
        check("B retains its independent mesh with the same key", LodMeshCache.sameMesh(b.get(0, 0, 0, key(1)), 0, 1, mesh(2, 1)));
        exactBudget("warm namespaces counted together", b, base);
    }

    private static void paddedNamespace(Path base, String name, long bytes, long age) throws Exception {
        Path dir = base.resolve(name);
        sparse(dir.resolve("padding.bin"), bytes);
        Files.setLastModifiedTime(dir, FileTime.fromMillis(age));
    }

    private static void sparse(Path file, long bytes) throws Exception {
        Files.createDirectories(file.getParent());
        try (FileChannel out = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            if (bytes > 0) out.position(bytes - 1).write(ByteBuffer.wrap(new byte[]{0}));
        }
    }

    private static void seed(Path namespace, byte[] bytes) throws Exception {
        Files.createDirectories(namespace.resolve("L0"));
        Files.write(namespace.resolve(REGION), bytes);
    }

    private static void openPressure(Path base) throws Exception {
        paddedNamespace(base, "old", 30 * MB, 1000);
        paddedNamespace(base, "recent", 30 * MB, 2000);
        paddedNamespace(base, "active", 15 * MB, 0);
        LodMeshCache cache = LodMeshCache.open(base, "active");
        check("oldest inactive evicted at open", !Files.exists(base.resolve("old")));
        check("newer inactive retained once aggregate fits", Files.exists(base.resolve("recent/padding.bin")));
        check("active namespace protected despite oldest timestamp", Files.exists(base.resolve("active/padding.bin")));
        exactBudget("open pressure bounded", cache, base);
    }

    private static void growthPressure(Path base) throws Exception {
        paddedNamespace(base, "old", 15 * MB, 1000);
        paddedNamespace(base, "recent", 15 * MB, 2000);
        paddedNamespace(base, "active", CAP - 30 * MB - sample.length + 1, 0);
        LodMeshCache cache = LodMeshCache.open(base, "active");
        check("no eager eviction while all namespaces fit", Files.exists(base.resolve("old")));
        put(cache, 0);
        check("growth evicts oldest inactive namespace", !Files.exists(base.resolve("old")));
        check("growth keeps newer inactive namespace", Files.exists(base.resolve("recent")));
        cache.flush();
        exactBudget("growth uses aggregate budget", cache, base);
        check("new mesh persists after pressure", LodMeshCache.open(base, "active").get(0, 0, 0, key(1)) != null);
    }

    private static void headersAndPending(Path base) throws Exception {
        paddedNamespace(base, "active", CAP - 2L * sample.length + 1, 0);
        LodMeshCache cache = LodMeshCache.open(base, "active");
        put(cache, 0);
        put(cache, 8);
        check("first pending region admitted", cache.get(0, 0, 0, key(1)) != null);
        check("second region rejected including its header and pending bytes", cache.get(0, 8, 0, key(1)) == null && cache.stores == 1);
        cache.flush();
        check("one complete region size charged", diskBytes(base) == CAP - sample.length + 1);
        exactBudget("headers included at flush", cache, base);
        cache = LodMeshCache.open(base, "active");
        put(cache, 0);
        cache.flush();
        check("same-size replacement does not charge a second header", cache.stores == 1);
        exactBudget("reopen replacement accounting", cache, base);
    }

    private static void shrinkAndRetry(Path base) throws Exception {
        LodMeshCache cache = LodMeshCache.open(base, "active");
        cache.put(0, 0, 0, key(1), 0, 1, mesh(1, 200));
        cache.flush();
        sparse(base.resolve("active/padding.bin"), CAP - Files.size(base.resolve("active/" + REGION)));
        cache = LodMeshCache.open(base, "active");
        cache.put(0, 0, 0, key(2), 0, 0, List.of());
        check("smaller replacement admitted at cap", cache.get(0, 0, 0, key(2)) != null);
        put(cache, 8);
        check("pending shrink has not freed disk space", cache.get(0, 8, 0, key(1)) == null);
        Path blocker = base.resolve("active/" + REGION + ".tmp");
        Files.createDirectory(blocker);
        cache.flush();
        exactBudget("failed shrink still charges old physical bytes", cache, base);
        put(cache, 8);
        check("failed write cannot free space", cache.get(0, 8, 0, key(1)) == null);
        Files.delete(blocker);
        cache.flush();
        put(cache, 8);
        check("successful shrink frees space for another region", cache.get(0, 8, 0, key(1)) != null);
        cache.flush();
        exactBudget("shrink retry and new region bounded", cache, base);
    }

    private static void oversizedActive(Path base) throws Exception {
        seed(base.resolve("active"), sample);
        paddedNamespace(base, "old", MB, 1000);
        paddedNamespace(base, "active", CAP + MB - sample.length, 0);
        LodMeshCache cache = LodMeshCache.open(base, "active");
        check("oversized active retains valid hits", cache.get(0, 0, 0, key(1)) != null);
        check("oversized active still evicts inactive", !Files.exists(base.resolve("old")));
        put(cache, 8);
        cache.flush();
        check("oversized active cannot grow", cache.stores == 0 && diskBytes(base) == CAP + MB);
    }

    private static void staleTemporary(Path base) throws Exception {
        seed(base.resolve("active"), sample);
        sparse(base.resolve("active/" + REGION + ".tmp"), 1024);
        LodMeshCache cache = LodMeshCache.open(base, "active");
        cache.put(0, 0, 0, key(2), 0, 0, List.of());
        cache.flush();
        check("stale temporary removed after replacement", !Files.exists(base.resolve("active/" + REGION + ".tmp")));
        exactBudget("stale temporary not charged twice", cache, base);
    }

    private static void rejectedOpen(Path base, String fingerprint) {
        boolean rejected = false;
        try { LodMeshCache.open(base, fingerprint); }
        catch (IllegalArgumentException | UncheckedIOException e) { rejected = true; }
        check("unsafe namespace/base rejected: " + fingerprint, rejected);
    }

    private static void pathScope(Path root) throws Exception {
        Path outside = root.resolve("outside");
        seed(outside, sample);
        for (String name : List.of("", ".", "..", "../outside", "a/b", "a\\b", outside.toString())) {
            rejectedOpen(root.resolve("invalid"), name);
        }
        Files.createSymbolicLink(root.resolve("linked-base"), outside);
        rejectedOpen(root.resolve("linked-base"), "a");
        Path aliases = Files.createDirectories(root.resolve("aliases"));
        Files.createSymbolicLink(aliases.resolve("alias"), outside);
        rejectedOpen(aliases, "alias");
        for (String kind : List.of("level", "region", "temporary")) {
            Path base = root.resolve(kind);
            Path dir = Files.createDirectories(base.resolve("active"));
            Path link;
            if (kind.equals("level")) {
                link = dir.resolve("L0");
                Files.createSymbolicLink(link, outside.resolve("L0"));
            } else {
                Files.createDirectory(dir.resolve("L0"));
                link = dir.resolve(REGION + (kind.equals("temporary") ? ".tmp" : ""));
                Files.createSymbolicLink(link, outside.resolve(REGION));
            }
            LodMeshCache cache = LodMeshCache.open(base, "active");
            check(kind + " symlink cannot return another namespace's entry", cache.get(0, 0, 0, key(1)) == null);
            cache.put(0, 0, 0, key(2), 0, 0, List.of());
            cache.flush();
            check(kind + " symlink not overwritten", Files.isSymbolicLink(link));
            check(kind + " symlink target unchanged", Arrays.equals(sample, Files.readAllBytes(outside.resolve(REGION))));
        }
        sparse(outside.resolve("large.bin"), 2 * CAP);
        Path base = root.resolve("prune");
        paddedNamespace(base, "old", 40 * MB, 1000);
        Files.createSymbolicLink(base.resolve("old/nested"), outside);
        Files.setLastModifiedTime(base.resolve("old"), FileTime.fromMillis(1000));
        paddedNamespace(base, "recent", 30 * MB, 2000);
        Files.createSymbolicLink(base.resolve("linked"), outside);
        LodMeshCache cache = LodMeshCache.open(base, "active");
        check("pruning unlinks nested symlink without following it", !Files.exists(base.resolve("old")) && Files.size(outside.resolve("large.bin")) == 2 * CAP);
        check("sibling symlink neither followed nor selected for eviction", Files.isSymbolicLink(base.resolve("linked")) && Files.exists(base.resolve("recent")));
        check("outside cache mesh survives pruning", Arrays.equals(sample, Files.readAllBytes(outside.resolve(REGION))));
        exactBudget("symlink targets excluded from budget", cache, base);
    }

    private static long diskBytes(Path base) throws Exception {
        long bytes = 0;
        try (var paths = Files.walk(base)) {
            for (Path p : paths.toList()) {
                BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isDirectory()) bytes += attrs.size();
            }
        }
        return bytes;
    }

    private static void exactBudget(String message, LodMeshCache cache, Path base) throws Exception {
        long actual = diskBytes(base);
        var used = LodMeshCache.class.getDeclaredField("diskUsed");
        used.setAccessible(true);
        check(message + " (actual size)", actual <= CAP);
        check(message + " (accounting)", used.getLong(cache) == actual);
    }
}
