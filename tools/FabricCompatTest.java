import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.classfile.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.minecraft.client.compat.ModCompat;

/**
 * Fabric compatibility, headless (docs/metal/CONTRACT.md 16.FAB, results/FABRIC.md). Run by tools/fabric-compat-test.sh
 * with build/client.jar and the game libraries on the class path.
 *
 * <ol>
 *   <li>ModCompat: superseded rendering mods are found by their fabric.mod.json id (Sodium, Iris, Distant Horizons, Voxy;
 *       not other mods, not jars without fabric.mod.json), with name and version; the notice names each; without Fabric
 *       on the class path {@code scan} is a no-op.</li>
 *   <li>Method merge (tools/MethodMerge.java) in the jar: in every vanilla class the jar changes, each method is either
 *       byte-for-byte vanilla (normalized instructions) or listed as ours in build/method-merge.txt; the vanilla methods
 *       the test mods' mixins hit most are vanilla (the Hud / MouseHandler / TitleScreen methods we never patched), and the
 *       patched methods mods name keep vanilla's descriptor (LevelExtractor.extractVisibleEntities).</li>
 * </ol>
 * usage: java -cp client.jar:libs tools/FabricCompatTest.java <vanilla.jar> <client.jar> <method-merge.txt>
 */
public final class FabricCompatTest {
    static int checks, failures;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    static byte[] modJar(String json) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            if (json != null) {
                z.putNextEntry(new ZipEntry("fabric.mod.json"));
                z.write(json.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
            z.putNextEntry(new ZipEntry("a/B.class"));
            z.write(new byte[] {1, 2, 3});
            z.closeEntry();
        }
        return b.toByteArray();
    }

    public static void main(String[] a) throws Exception {
        // 1. ModCompat
        Path dir = Files.createTempDirectory("modcompat");
        Files.write(dir.resolve("sodium-fabric-0.9.3-alpha.1+mc26.3.jar"), modJar(
            "{\n  \"schemaVersion\": 1,\n  \"id\": \"sodium\",\n  \"version\": \"0.9.3-alpha.1+mc26.3\",\n  \"name\": \"Sodium\",\n"
                + "  \"depends\": {\"minecraft\": \"26.3\"}\n}"));
        Files.write(dir.resolve("iris.jar"), modJar("{\"schemaVersion\":1,\"id\":\"iris\",\"version\":\"1.11.7\"}"));
        Files.write(dir.resolve("DistantHorizons-3.3.4-26.3-fabric-neoforge.jar"), modJar("{\"id\": \"distanthorizons\", \"version\": \"3.3.4-26.3\"}"));
        Files.write(dir.resolve("voxy.jar"), modJar("{\"id\":\"voxy\",\"version\":\"0.2.19-beta\"}"));
        Files.write(dir.resolve("appleskin.jar"), modJar("{\"id\":\"appleskin\",\"version\":\"3.0.10\",\"depends\":{\"sodium\":\"*\"}}"));
        Files.write(dir.resolve("plain.jar"), modJar(null));
        Files.writeString(dir.resolve("notes.txt"), "not a jar");
        Files.write(dir.resolve("broken.jar"), new byte[] {'P', 'K', 0, 0});
        List<ModCompat.Found> found = ModCompat.scanFolder(dir);
        List<String> ids = found.stream().map(ModCompat.Found::id).toList();
        check(ids.equals(List.of("distanthorizons", "iris", "sodium", "voxy")), "scan finds exactly the four superseded mods (sorted by file): " + ids);
        check(found.stream().noneMatch(ModCompat.Found::loaded), "no Fabric loader here: nothing reported as loaded");
        ModCompat.Found s = found.stream().filter(f -> f.id().equals("sodium")).findFirst().orElseThrow();
        check(s.name().equals("Sodium") && s.version().equals("0.9.3-alpha.1+mc26.3") && s.file().startsWith("sodium-fabric"),
            "sodium: name, version, file: " + s);
        String msg = ModCompat.message(found).getString();
        for (String n : List.of("Sodium 0.9.3-alpha.1+mc26.3", "Iris 1.11.7", "Distant Horizons 3.3.4-26.3", "Voxy 0.2.19-beta", "not loaded")) {
            check(msg.contains(n), "notice names " + n);
        }
        check(!msg.contains("appleskin"), "notice does not name other mods");
        List<ModCompat.Found> loadedOnes = List.of(new ModCompat.Found("sodium", "Sodium", "1", "s.jar", "x", true));
        check(ModCompat.message(loadedOnes).getString().contains("-Dfabric.debug.disableModIds=" + ModCompat.DISABLE_PROPERTY_VALUE),
            "loaded superseded mod: notice says how to disable it");
        check(ModCompat.scanFolder(dir.resolve("missing")).isEmpty(), "missing folder: empty");
        check(!ModCompat.fabricPresent() && ModCompat.scan(dir.toFile()).isEmpty(), "without Fabric on the class path scan is a no-op");

        // 2. method merge
        if (a.length >= 3) {
            Map<String, byte[]> van = new HashMap<>(), ours = new HashMap<>();
            for (var p : List.of(Map.entry(a[0], van), Map.entry(a[1], ours))) {
                try (ZipFile z = new ZipFile(p.getKey())) {
                    for (var e : Collections.list(z.entries())) {
                        if (e.getName().endsWith(".class")) p.getValue().put(e.getName(), z.getInputStream(e).readAllBytes());
                    }
                }
            }
            Map<String, Set<String>> oursListed = new HashMap<>();
            String cur = null;
            for (String line : Files.readAllLines(Path.of(a[2]))) {
                if (!line.startsWith(" ") && line.contains(": vanilla kept")) cur = line.substring(0, line.indexOf(':'));
                else if (cur != null && line.startsWith("    ours ")) oursListed.computeIfAbsent(cur, k -> new HashSet<>()).add(line.substring(9).replace(" [new]", ""));
                if (line.startsWith("ERROR") || line.contains("    ERROR")) check(false, "merge report error: " + line);
            }
            int changedClasses = 0, vanillaMethods = 0, unexplained = 0;
            for (var e : ours.entrySet()) {
                byte[] vb = van.get(e.getKey());
                if (vb == null || Arrays.equals(vb, e.getValue())) continue;
                changedClasses++;
                String cls = e.getKey().substring(0, e.getKey().length() - 6);
                ClassModel vc = ClassFile.of().parse(vb), oc = ClassFile.of().parse(e.getValue());
                Set<String> listed = oursListed.getOrDefault(cls, Set.of());
                for (MethodModel om : oc.methods()) {
                    String k = om.methodName().stringValue() + om.methodType().stringValue();
                    if (k.startsWith("lambda$")) continue;
                    MethodModel vm = vc.methods().stream().filter(m -> (m.methodName().stringValue() + m.methodType().stringValue()).equals(k)).findFirst().orElse(null);
                    boolean same = vm != null && MixinAudit.norm(vm).equals(MixinAudit.norm(om));
                    if (same) vanillaMethods++;
                    else if (!listed.contains(k)) {
                        unexplained++;
                        check(false, cls + "." + k + " differs from vanilla but the merge did not list it as ours");
                    }
                }
            }
            System.out.println("merged jar: " + changedClasses + " changed vanilla classes, " + vanillaMethods + " vanilla methods byte-identical, "
                + unexplained + " unexplained differences");
            check(changedClasses > 0 && vanillaMethods > 1000, "the merge kept vanilla bytecode for most methods");
            String[][] vanillaKept = {
                {"net/minecraft/client/gui/Hud", "extractEffects"}, {"net/minecraft/client/gui/Hud", "extractRenderState"},
                {"net/minecraft/client/MouseHandler", "onButton"}, {"net/minecraft/client/gui/screens/TitleScreen", "init"},
                {"net/minecraft/client/renderer/extract/LevelExtractor", "extractBlockOutline"},
                {"net/minecraft/client/renderer/extract/LevelExtractor", "allChanged"},
            };
            for (String[] t : vanillaKept) {
                ClassModel vc = ClassFile.of().parse(van.get(t[0] + ".class")), oc = ClassFile.of().parse(ours.get(t[0] + ".class"));
                for (MethodModel vm : vc.methods()) {
                    if (!vm.methodName().equalsString(t[1])) continue;
                    MethodModel om = oc.methods().stream().filter(m -> m.methodName().equals(vm.methodName()) && m.methodType().equals(vm.methodType())).findFirst().orElse(null);
                    check(om != null && MixinAudit.norm(vm).equals(MixinAudit.norm(om)), t[0] + "." + t[1] + vm.methodType().stringValue() + " is vanilla bytecode");
                }
            }
            ClassModel le = ClassFile.of().parse(ours.get("net/minecraft/client/renderer/extract/LevelExtractor.class"));
            List<String> descs = le.methods().stream().filter(m -> m.methodName().equalsString("extractVisibleEntities")).map(m -> m.methodType().stringValue()).toList();
            check(descs.equals(List.of("(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V")),
                "LevelExtractor.extractVisibleEntities: one method, vanilla's descriptor: " + descs);
        }
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
