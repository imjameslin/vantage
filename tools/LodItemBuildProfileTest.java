package net.minecraft.client.renderer.chunk;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-free synthetic-clock accounting tests; no renderer, GPU, timing sleeps or performance claims. */
public final class LodItemBuildProfileTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void eq(long actual, long expected, String message) { check(actual == expected, message + ": " + actual + " != " + expected); }

    private static void full(LodItemBuildProfile p, int mode, long start) {
        p.begin(start, (mode & 2) != 0); p.groups((mode & 1) != 0);
        p.selected(10, 12);
        p.advance(LodItemBuildProfile.PREPARE_APPEND, start + 10);
        p.prepared(20, 18, 120, 600, 3);
        p.advance(LodItemBuildProfile.SECTION_RING, start + 50);
        p.allocated(false, 1024); p.copied(false, 288);
        p.advance(LodItemBuildProfile.ITEM_RING, start + 80);
        p.allocated(true, 8192); p.copied(true, 1600); p.copied(true, 320);
        p.finish(true, start + 100);
    }

    private static void partition(LodItemBuildProfile p, boolean success, int mode) {
        long sum = 0;
        for (int phase = 0; phase < 4; phase++) sum += p.phaseNs(success, mode, phase);
        eq(sum, p.totalNs(success, mode), "adjacent phases exactly partition elapsed time");
    }

    private static void accounting() {
        LodItemBuildProfile p = new LodItemBuildProfile();
        for (int mode = 0; mode < 4; mode++) {
            full(p, mode, 1000L * mode);
            eq(p.attempts(true, mode), 1, "sampled mode count");
            eq(p.attempts(false, mode), 0, "no failed totals in success");
            eq(p.totalNs(true, mode), 100, "whole elapsed");
            long[] expected = {10, 40, 30, 20};
            for (int phase = 0; phase < 4; phase++) {
                eq(p.phaseNs(true, mode, phase), expected[phase], "phase duration");
                eq(p.entered(true, mode, phase), 1, "phase entered");
                eq(p.completed(true, mode, phase), 1, "phase complete");
            }
            eq(p.count(true, mode, "selectionNodes"), 10, "selection size");
            eq(p.count(true, mode, "orderedNodes"), 12, "fade additions represented");
            eq(p.count(true, mode, "preparedSections"), 20, "prepared sections");
            eq(p.count(true, mode, "sectionRecords"), 18, "empty sections excluded from records");
            eq(p.count(true, mode, "clusterRecords"), 120, "clusters not faces");
            eq(p.count(true, mode, "quads"), 600, "quads independent from clusters");
            eq(p.count(true, mode, "nonemptyGroups"), 3, "nonempty groups");
            eq(p.count(true, mode, "sectionAllocations"), 1, "section allocation count");
            eq(p.count(true, mode, "itemAllocations"), 1, "item allocation count");
            eq(p.count(true, mode, "sectionAllocatedCapacityBytes"), 1024, "capacity is not payload");
            eq(p.count(true, mode, "itemAllocatedCapacityBytes"), 8192, "item capacity");
            eq(p.count(true, mode, "sectionCompletedCopyBytes"), 288, "section copied bytes");
            eq(p.count(true, mode, "itemCompletedCopyBytes"), 1920, "multiple completed group copies");
            partition(p, true, mode);
        }
        // No item payload: real code still allocates/copies a minimum section ring, then returns.
        p.begin(10000, true); p.groups(true); p.selected(3, 0);
        p.advance(1, 10005); p.prepared(0, 0, 0, 0, 0);
        p.advance(2, 10012); p.allocated(false, 65536); p.copied(false, 0); p.finish(true, 10020);
        eq(p.attempts(true, 3), 2, "empty build counts as completed");
        eq(p.entered(true, 3, 2), 2, "empty build runs section ring");
        eq(p.entered(true, 3, 3), 1, "empty build never enters item ring");
        eq(p.completed(true, 3, 2), 2, "empty section phase completes");
        eq(p.phaseNs(true, 3, 3), 20, "empty build adds zero item time");
        eq(p.count(true, 3, "itemCompletedCopyBytes"), 1920, "empty build adds zero item bytes");
        eq(p.count(true, 3, "sectionCompletedCopyBytes"), 288, "minimum capacity is not copied payload");
        eq(p.count(true, 3, "sectionAllocatedCapacityBytes"), 66560, "allocated capacity sums returned allocations");
        eq(p.count(true, 3, "nonemptyGroups"), 3, "scratch group count resets");
        partition(p, true, 3);

        String before = p.statsJson();
        p.finish(false, 20000);
        check(before.equals(p.statsJson()), "finish without active build does nothing");
        check(before.contains("\"preparedClusterPayloadBytes\":1920"), "JSON reports logical record payload");
        check(before.contains("\"failedTimesArePartial\":true"), "JSON labels partial failure times");
        check(before.contains("\"copyBytesExcludeThrowingCalls\":true"), "JSON labels successful-copy coverage");
    }

    private static void failures() {
        for (int broken = 0; broken < 4; broken++) {
            LodItemBuildProfile p = new LodItemBuildProfile();
            RuntimeException failure = new RuntimeException("original");
            try {
                p.begin(100, true); p.groups(false);
                for (int phase = 0; phase < broken; phase++) {
                    if (phase == 0) p.selected(5, 6);
                    if (phase == 1) p.prepared(7, 7, 50, 300, 2);
                    if (phase == 2) { p.allocated(false, 65536); p.copied(false, 112); }
                    p.advance(phase + 1, 110 + phase * 10);
                }
                if (broken == 3) { p.allocated(true, 1048576); p.copied(true, 128); }
                // A subsequent copy/preparation call throws, without a completed counter notification.
                throw failure;
            } catch (RuntimeException observed) {
                p.finish(false, 107 + broken * 10);
                check(observed == failure, "diagnostics preserve original exception");
            }
            eq(p.attempts(false, 2), 1, "failure separated by sampled mode");
            eq(p.attempts(true, 2), 0, "failed build not reported complete");
            for (int phase = 0; phase < 4; phase++) {
                eq(p.entered(false, 2, phase), phase <= broken ? 1 : 0, "failed phase coverage");
                eq(p.completed(false, 2, phase), phase < broken ? 1 : 0, "failed phase not completed");
                eq(p.phaseNs(false, 2, phase), phase < broken ? 10 : phase == broken ? 7 : 0, "elapsed failing prefix");
            }
            eq(p.count(false, 2, "clusterRecords"), broken >= 2 ? 50 : 0, "incomplete preparation not mislabeled");
            eq(p.count(false, 2, "itemCompletedCopyBytes"), broken == 3 ? 128 : 0, "throwing copy prefix not guessed");
            partition(p, false, 2);
            full(p, 1, 1000);
            eq(p.attempts(true, 1), 1, "fresh build after failed attempt");
            eq(p.count(true, 1, "itemCompletedCopyBytes"), 1920, "failed scratch bytes do not leak");
        }
        LodItemBuildProfile p = new LodItemBuildProfile();
        full(p, 0, Long.MAX_VALUE - 30);
        eq(p.totalNs(true, 0), 100, "nanoTime signed wrap uses modular subtraction");
        partition(p, true, 0);
    }

    private static void sourceWiring(Path source) throws Exception {
        String s = Files.readString(source);
        check(s.contains("private static final boolean ITEM_BUILD_PROFILE = Boolean.getBoolean(\"mc.lod.itemBuildProfile\");"), "exact default-off static-final property");
        check(s.contains("itemBuildProfile = ITEM_BUILD_PROFILE ? LodItemBuildProfile.create(true) : null;"), "disabled owner does not initialize/allocate profiler");
        String b = s.substring(s.indexOf("    private void buildItems("), s.indexOf("    private void appendCachedSection("));
        String sectionLoop = b.substring(b.indexOf("        for (LodManager.Node n : order)"), b.indexOf("        long bytes = 0L;"));
        check(!sectionLoop.contains("itemBuildProfile") && !sectionLoop.contains("nanoTime"), "no per-section/cluster instrumentation");
        String append = s.substring(s.indexOf("    private void appendCachedSection("), s.indexOf("    private void releaseSectionItems("));
        check(!append.contains("itemBuildProfile") && !append.contains("nanoTime"), "cached append has no diagnostic clock");
        eq(b.split("System.nanoTime\\(\\)", -1).length - 1, 3, "only three coarse boundary clocks");
        check(b.contains("if (ITEM_BUILD_PROFILE) this.itemBuildProfile.groups(this.buildCacheGroups);"), "group mode comes from existing sample");
        check(b.indexOf("advance(LodItemBuildProfile.PREPARE_APPEND") < b.indexOf("for (LodManager.Node n : order)"), "prepare starts before section loop");
        check(b.indexOf("advance(LodItemBuildProfile.SECTION_RING") > b.indexOf("long bytes = 0L;"), "payload sizing belongs to preparation");
        check(b.indexOf("advance(LodItemBuildProfile.SECTION_RING") < b.indexOf("this.itemRingIndex ="), "ring selection belongs to section stage");
        check(b.indexOf("advance(LodItemBuildProfile.ITEM_RING") > b.indexOf("if (bytes == 0L)"), "zero-byte return does not enter item stage");
        check(b.contains(".put(this.secItems, 0, 4 * this.secCount);\n        if (ITEM_BUILD_PROFILE) this.itemBuildProfile.copied(false, 16L * this.secCount);"), "section copy counter is after successful put");
        check(b.contains(".put(g.items, 0, 4 * g.count);\n                if (ITEM_BUILD_PROFILE) this.itemBuildProfile.copied(true, 16L * g.count);"), "item copy counter is after successful put");
        int scalarStart = s.indexOf("        if (!asyncHandled && (sel.version() !=");
        check(scalarStart >= 0, "scalar rebuild remains guarded by async preparation ownership");
        String frame = s.substring(scalarStart, s.indexOf("        int jobCount = 0;", scalarStart));
        check(frame.contains("if (ITEM_BUILD_PROFILE) this.itemBuildProfile.begin(b0, cachedItems);"), "begin uses original timer and sampled item mode");
        check(frame.contains("if (ITEM_BUILD_PROFILE) this.itemBuildProfile.finish(true, b0 + elapsed);"), "success shares original whole timer");
        check(frame.indexOf("finish(false, System.nanoTime())") < frame.indexOf("this.itemsDirty = true;"), "failure closes timing before recovery work");
        check(s.contains("(ITEM_BUILD_PROFILE ? this.itemBuildProfile.statsJson() : \"null\")"), "disabled stats are explicitly null");
    }

    public static void main(String[] args) throws Exception {
        boolean enabled = Boolean.getBoolean("mc.lod.itemBuildProfile");
        check(enabled == Boolean.parseBoolean(args[0]), "unset/false/true launch property");
        check((LodItemBuildProfile.create(enabled) != null) == enabled, "factory enable gate");
        accounting(); failures(); sourceWiring(Path.of(args[1]));
        System.out.println("LodItemBuildProfileTest: " + checks + " checks passed (enabled=" + enabled + ")");
        LodItemBuildProfile sample = new LodItemBuildProfile(); full(sample, 3, 100);
        System.out.println(sample.statsJson());
    }
}
