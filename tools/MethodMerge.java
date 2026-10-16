import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.classfile.*;
import java.lang.classfile.attribute.EnclosingMethodAttribute;
import java.lang.classfile.constantpool.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipFile;

/**
 * Method-level merge of the patched vanilla classes (docs/metal/CONTRACT.md 16.FAB, results/FABRIC.md).
 *
 * <p>client/src holds full source copies of the vanilla classes we patch, made from decompiled vanilla source
 * (src/vanilla). Compiled as they are, every method of such a class is the decompiler's reconstruction, not Mojang's
 * bytecode: inverted branches, merged returns, extra locals, renumbered lambdas. Fabric mods' mixins resolve injection
 * points against bytecode (returns, ordinals, locals, expressions, lambda names), so a reconstructed method can make a
 * mixin miss or hit the wrong place even where we changed nothing.
 *
 * <p>This tool keeps the source workflow and fixes the output: for every class file that exists in the vanilla jar, a
 * method is "ours" only if our source changed it, decided by compiling the decompiled vanilla source of the same class
 * (D) with the same javac and comparing D's method with ours (O) instruction by instruction (lambda bodies compared
 * recursively, lambda numbering ignored). Equal = our source did not touch it, so the shipped class gets Mojang's original
 * method (V). Different, or new = ours. Vanilla methods we removed are restored from V (dead code, so mixins and callers
 * that name them still link). Lambdas follow the methods that use them (renamed with a {@code $mc} suffix when ours and
 * vanilla's collide). Fields and class attributes are ours. A link check verifies that every kept vanilla method's
 * references to the patched classes resolve; a kept method that does not link is taken from ours instead.
 *
 * <p>Behaviour: an unchanged method's original bytecode does what its decompiled reconstruction did (that is the premise
 * of every full-copy patch); changed methods are byte-for-byte what we compiled. So nothing changes at run time except
 * that mixins see vanilla code wherever we did not patch.
 *
 * <p>usage: java tools/MethodMerge.java &lt;vanilla.jar&gt; &lt;classesDir&gt; &lt;client/src&gt; &lt;src/vanilla&gt; &lt;workDir&gt;
 * &lt;report.txt&gt; &lt;classpath&gt;: compiles D into workDir, rewrites the changed vanilla class files in classesDir in
 * place; exit 1 on an inconsistency (the report says which).
 */
public final class MethodMerge {
    static Map<String, byte[]> vanilla = new HashMap<>();
    static Path classes, dClasses;
    static List<ZipFile> libs = new ArrayList<>();
    static PrintWriter report;
    static int errors;

    /**
     * Compile fixes for the decompiled vanilla sources (D): the same fixes our copies carry where the decompiler's output
     * does not compile. Each is (file, D text from, D text to (inclusive line), our text from, our text to): the D region is
     * replaced by our region, so that method compiles like ours and, if nothing else in it changed, merges to vanilla's
     * original. Plain replacements have null "to" markers.
     */
    static final String[][] D_FIXES = {
        {"net/minecraft/client/Minecraft.java", "this.<CompletableFuture<Void>>submit(this::reloadResourcePacks)", null,
            "this.<CompletableFuture<Void>>submit((java.util.function.Supplier<CompletableFuture<Void>>)this::reloadResourcePacks)", null},
        // the glow pass reused the solid pass's locals, which lambdas cannot capture (our copy: own locals, same values)
        {"net/minecraft/client/renderer/blockentity/BeaconRenderer.java", "        float wnx = 0.0F;",
            "beamStart, beamEnd, wnx, wnz, enx, enz, wsx, wsz, esx, esz, 0.0F, 1.0F, vv1, vv2)",
            "        // Decompiler artifact fixed:", "beamStart, beamEnd, gwnx, gwnz, genx, genz, gwsx, gwsz, gesx, gesz, 0.0F, 1.0F, gvv1, gvv2)"},
    };

    static String applyFixes(String rel, String d, String ours) {
        for (String[] f : D_FIXES) {
            if (!f[0].equals(rel)) continue;
            if (f[2] == null) {
                if (!d.contains(f[1])) throw new IllegalStateException("D fix not applicable: " + rel + ": " + f[1]);
                d = d.replace(f[1], f[3]);
                continue;
            }
            int a = d.indexOf(f[1]), b = d.indexOf(f[2], a);
            int c = ours.indexOf(f[3]), e = ours.indexOf(f[4], c);
            if (a < 0 || b < 0 || c < 0 || e < 0) throw new IllegalStateException("D fix not applicable: " + rel + ": " + f[1]);
            d = d.substring(0, a) + ours.substring(c, e + f[4].length()) + d.substring(b + f[2].length());
        }
        return d;
    }

    /** Compile the decompiled vanilla source of every patched class (with D_FIXES) into work/dcls; files that do not
     *  compile are left out (their classes merge nothing: every method ours) and reported. */
    static void compileD(Path clientSrc, Path vanillaSrc, Path work, String cp) throws IOException {
        Path dsrc = work.resolve("dsrc");
        dClasses = work.resolve("dcls");
        for (Path p : List.of(dsrc, dClasses)) {
            if (Files.exists(p)) try (var s = Files.walk(p)) { s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete()); }
            Files.createDirectories(p);
        }
        List<Path> files = new ArrayList<>();
        try (var s = Files.walk(clientSrc)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String rel = clientSrc.relativize(p).toString();
                if (!rel.endsWith(".java")) continue;
                Path vs = vanillaSrc.resolve(rel);
                if (!Files.exists(vs) || !vanilla.containsKey(rel.substring(0, rel.length() - 5) + ".class")) continue;
                String text = applyFixes(rel, Files.readString(vs), Files.readString(p));
                Path dst = dsrc.resolve(rel);
                Files.createDirectories(dst.getParent());
                Files.writeString(dst, text);
                files.add(dst);
            }
        }
        javax.tools.JavaCompiler jc = javax.tools.ToolProvider.getSystemJavaCompiler();
        for (int attempt = 0; attempt < 5 && !files.isEmpty(); attempt++) {
            javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diags = new javax.tools.DiagnosticCollector<>();
            try (var fm = jc.getStandardFileManager(diags, null, java.nio.charset.StandardCharsets.UTF_8)) {
                List<String> opts = List.of("--release", "25", "-g", "-nowarn", "-proc:none", "-Xlint:none", "-encoding", "UTF-8",
                    "-cp", cp, "-d", dClasses.toString());
                boolean ok = jc.getTask(null, fm, diags, opts, null, fm.getJavaFileObjectsFromPaths(files)).call();
                if (ok) break;
                Set<Path> bad = new HashSet<>();
                for (var dg : diags.getDiagnostics()) {
                    if (dg.getKind() == javax.tools.Diagnostic.Kind.ERROR && dg.getSource() != null) {
                        Path bp = Path.of(dg.getSource().toUri());
                        if (bad.add(bp)) report.println("D compile failed (class merges nothing): " + dsrc.relativize(bp) + ": " + dg.getMessage(null));
                    }
                }
                if (bad.isEmpty()) throw new IllegalStateException("D compile failed: " + diags.getDiagnostics());
                files.removeIf(bad::contains);
                try (var s = Files.walk(dClasses)) { s.sorted(Comparator.reverseOrder()).filter(x -> !x.equals(dClasses)).forEach(x -> x.toFile().delete()); }
            }
        }
        report.println("D: " + files.size() + " decompiled vanilla sources compiled");
    }

    public static void main(String[] a) throws Exception {
        if (a.length < 7) {
            System.err.println("usage: MethodMerge <vanilla.jar> <classesDir> <client/src> <src/vanilla> <workDir> <report.txt> <classpath> [lib jars...]");
            System.exit(2);
        }
        try (ZipFile z = new ZipFile(a[0])) {
            for (var e : Collections.list(z.entries())) {
                if (e.getName().endsWith(".class")) vanilla.put(e.getName(), z.getInputStream(e).readAllBytes());
            }
        }
        classes = Path.of(a[1]);
        report = new PrintWriter(Files.newBufferedWriter(Path.of(a[5])));
        if (a[6].startsWith("@")) a[6] = Files.readString(Path.of(a[6].substring(1))).strip();
        for (String j : a[6].split(java.io.File.pathSeparator)) if (j.endsWith(".jar") && !j.equals(a[0])) libs.add(new ZipFile(j));
        compileD(Path.of(a[2]), Path.of(a[3]), Path.of(a[4]), a[6]);

        List<String> cands = new ArrayList<>();
        try (var s = Files.walk(classes)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String rel = classes.relativize(p).toString();
                if (!rel.endsWith(".class")) continue;
                byte[] v = vanilla.get(rel);
                if (v != null && !Arrays.equals(v, Files.readAllBytes(p))) cands.add(rel);
            }
        }
        Collections.sort(cands);
        Set<String> candNames = new HashSet<>();
        for (String c : cands) candNames.add(c.substring(0, c.length() - 6));
        int keptTotal = 0, oursTotal = 0, restoredTotal = 0, identical = 0;
        Map<String, byte[]> out = new LinkedHashMap<>();
        Map<String, Merge> merges = new LinkedHashMap<>();
        for (String c : cands) {
            Merge m = new Merge(c.substring(0, c.length() - 6));
            merges.put(m.name, m);
        }
        // decide, then link-check across all classes until stable (a kept method that does not link becomes ours)
        for (Merge m : merges.values()) m.decide();
        boolean changed = true;
        int rounds = 0;
        while (changed && rounds++ < 20) {
            changed = false;
            for (Merge m : merges.values()) changed |= m.linkCheck(merges);
        }
        for (Merge m : merges.values()) {
            byte[] b = m.build();
            // the JVM's verifier rules, run at build time (a merged class that would fail to load fails the build)
            var verrs = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(RESOLVER)).verify(b);
            for (var ve : verrs) {
                errors++;
                report.printf("ERROR %s: verify: %s%n", m.name, ve.getMessage());
            }
            out.put(m.name, b);
            keptTotal += m.keptCount;
            oursTotal += m.oursCount;
            restoredTotal += m.restored.size();
            if (Arrays.equals(b, vanilla.get(m.name + ".class"))) identical++;
        }
        for (var e : out.entrySet()) Files.write(classes.resolve(e.getKey() + ".class"), e.getValue());
        report.printf("TOTAL: %d classes, vanilla methods kept %d, ours %d, vanilla methods restored %d, errors %d%n",
            out.size(), keptTotal, oursTotal, restoredTotal, errors);
        report.close();
        System.out.printf("method merge: %d classes, %d vanilla methods kept, %d ours, %d restored, %d errors%n",
            out.size(), keptTotal, oursTotal, restoredTotal, errors);
        System.exit(errors == 0 ? 0 : 1);
    }

    static ClassModel parse(byte[] b) {
        return ClassFile.of().parse(b);
    }

    static String key(MethodModel m) {
        return m.methodName().stringValue() + m.methodType().stringValue();
    }

    static boolean isLambda(MethodModel m) {
        return m.methodName().stringValue().startsWith("lambda$") && (m.flags().has(java.lang.reflect.AccessFlag.SYNTHETIC));
    }

    // ------------------------------------------------------------------------------------------------------------------
    // lambda-aware comparison

    /** Normalized instructions; lambda handles of the own class replaced by "λ", collected in order into lams. */
    static List<String> norm(ClassModel cls, MethodModel m, List<String> lams) {
        CodeModel code = m.code().orElse(null);
        if (code == null) return List.of("<no code> " + m.flags().flagsMask());
        Map<Label, Integer> pos = new HashMap<>();
        int n = 0;
        for (CodeElement e : code) {
            if (e instanceof LabelTarget lt) pos.put(lt.label(), n);
            else if (e instanceof Instruction) n++;
        }
        String self = cls.thisClass().asInternalName();
        List<String> outl = new ArrayList<>();
        outl.add("flags " + m.flags().flagsMask() + " maxLocals " + (code instanceof java.lang.classfile.attribute.CodeAttribute ca ? ca.maxLocals() : -1));
        for (CodeElement e : code) {
            switch (e) {
                case BranchInstruction b -> outl.add(b.opcode() + " L" + pos.get(b.target()));
                case TableSwitchInstruction t -> outl.add("TABLESWITCH " + t.lowValue() + ".." + t.highValue() + " d=L" + pos.get(t.defaultTarget())
                    + " " + t.cases().stream().map(c -> c.caseValue() + ":L" + pos.get(c.target())).toList());
                case LookupSwitchInstruction t -> outl.add("LOOKUPSWITCH d=L" + pos.get(t.defaultTarget())
                    + " " + t.cases().stream().map(c -> c.caseValue() + ":L" + pos.get(c.target())).toList());
                case ExceptionCatch c -> outl.add("CATCH L" + pos.get(c.tryStart()) + "-L" + pos.get(c.tryEnd()) + " -> L" + pos.get(c.handler())
                    + " " + c.catchType().map(t -> t.asInternalName()).orElse("*"));
                case InvokeDynamicInstruction i -> {
                    StringBuilder sb = new StringBuilder("INDY " + i.name().stringValue() + i.typeSymbol().descriptorString() + " "
                        + i.bootstrapMethod() + " [");
                    for (ConstantDesc arg : i.bootstrapArgs()) {
                        if (arg instanceof DirectMethodHandleDesc dm && dm.owner().descriptorString().equals("L" + self + ";")
                            && dm.methodName().startsWith("lambda$")) {
                            sb.append("λ").append(dm.lookupDescriptor()).append(',');
                            lams.add(dm.methodName() + dm.lookupDescriptor());
                        } else {
                            sb.append(arg).append(',');
                        }
                    }
                    outl.add(sb.append(']').toString());
                }
                case Instruction i -> outl.add(i.toString().replace("OP=LDC_W,", "OP=LDC,"));   // ldc vs ldc_w: constant pool index only
                default -> { }
            }
        }
        return outl;
    }

    static MethodModel find(ClassModel c, String key) {
        for (MethodModel m : c.methods()) if (key(m).equals(key)) return m;
        return null;
    }

    static boolean eq(ClassModel dc, MethodModel dm, ClassModel oc, MethodModel om, int depth) {
        if (depth > 20) return false;
        if (dm.flags().flagsMask() != om.flags().flagsMask()) return false;
        List<String> dl = new ArrayList<>(), ol = new ArrayList<>();
        if (!norm(dc, dm, dl).equals(norm(oc, om, ol))) return false;
        if (dl.size() != ol.size()) return false;
        for (int i = 0; i < dl.size(); i++) {
            MethodModel a = find(dc, dl.get(i)), b = find(oc, ol.get(i));
            if (a == null || b == null || !eq(dc, a, oc, b, depth + 1)) return false;
        }
        return true;
    }

    /** Lambda methods (keys) reachable from a method, transitively. */
    static void lambdaClosure(ClassModel c, MethodModel m, Set<String> acc) {
        List<String> l = new ArrayList<>();
        norm(c, m, l);
        for (String k : l) {
            if (acc.add(k)) {
                MethodModel lm = find(c, k);
                if (lm != null) lambdaClosure(c, lm, acc);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------

    static final class Merge {
        final String name;
        final ClassModel v, o, d;
        final Set<String> keepV = new LinkedHashSet<>();   // non-lambda methods taken from vanilla
        final Set<String> restored = new LinkedHashSet<>(); // vanilla methods absent from ours, restored from vanilla
        final Set<String> ours = new LinkedHashSet<>();     // non-lambda methods taken from ours
        final List<String> notes = new ArrayList<>();
        int keptCount, oursCount;

        Merge(String name) throws IOException {
            this.name = name;
            this.v = parse(vanilla.get(name + ".class"));
            this.o = parse(Files.readAllBytes(classes.resolve(name + ".class")));
            Path dp = dClasses.resolve(name + ".class");
            this.d = Files.exists(dp) ? parse(Files.readAllBytes(dp)) : null;
        }

        void decide() {
            if (d == null) notes.add("no decompiled-vanilla compile (D) for this class: every method ours");
            // anonymous / local classes must stay the same class in both
            var ve = v.findAttribute(Attributes.enclosingMethod());
            var oe = o.findAttribute(Attributes.enclosingMethod());
            if (ve.isPresent() != oe.isPresent() || (ve.isPresent() && !encl(ve.get()).equals(encl(oe.get())))) {
                err("enclosing method differs: vanilla " + ve.map(Merge::encl).orElse("-") + ", ours " + oe.map(Merge::encl).orElse("-"));
            }
            if (!v.superclass().map(ClassEntry::asInternalName).equals(o.superclass().map(ClassEntry::asInternalName))) {
                notes.add("superclass changed: " + v.superclass().map(ClassEntry::asInternalName).orElse("-") + " -> "
                    + o.superclass().map(ClassEntry::asInternalName).orElse("-"));
            }
            for (MethodModel om : o.methods()) {
                if (isLambda(om)) continue;
                String k = key(om);
                MethodModel vm = find(v, k);
                MethodModel dm = d == null ? null : find(d, k);
                if (vm != null && dm != null && !isLambda(vm) && eq(d, dm, o, om, 0) && vm.flags().flagsMask() == om.flags().flagsMask()) keepV.add(k);
                else ours.add(k);
            }
            // Restore removed vanilla methods only where that cannot change dispatch or identity: never when the class's
            // superclass changed (e.g. a record we turned into a class: its equals / hashCode would turn value-based), never
            // the Object methods, and no other non-private instance method when one of our methods is abstract (an
            // interface change); static, private and constructors are always safe.
            boolean superChanged = !v.superclass().map(ClassEntry::asInternalName).equals(o.superclass().map(ClassEntry::asInternalName));
            for (MethodModel vm : v.methods()) {
                if (isLambda(vm)) continue;
                String k = key(vm);
                if (find(o, k) != null) continue;
                String n = vm.methodName().stringValue();
                boolean objectMethod = k.equals("equals(Ljava/lang/Object;)Z") || k.equals("hashCode()I") || k.equals("toString()Ljava/lang/String;");
                boolean overrides = !vm.flags().has(java.lang.reflect.AccessFlag.STATIC) && !vm.flags().has(java.lang.reflect.AccessFlag.PRIVATE)
                    && !n.startsWith("<") && declaredInSupertype(o, k, new HashSet<>());
                if (superChanged || objectMethod || overrides) {
                    notes.add("removed vanilla method " + k + " not restored ("
                        + (superChanged ? "superclass changed" : objectMethod ? "Object method" : "would override a supertype method") + ")");
                    continue;
                }
                restored.add(k);
            }
        }

        static String encl(EnclosingMethodAttribute a) {
            return a.enclosingClass().asInternalName() + "." + a.enclosingMethodName().map(Utf8Entry::stringValue).orElse("")
                + a.enclosingMethodType().map(Utf8Entry::stringValue).orElse("");
        }

        void err(String s) {
            errors++;
            notes.add("ERROR " + s);
        }

        /** Returns true if a decision changed. */
        boolean linkCheck(Map<String, Merge> merges) {
            boolean changed = false;
            for (String k : new ArrayList<>(keepV)) {
                String bad = unresolved(v, find(v, k), merges);
                if (bad != null) {
                    keepV.remove(k);
                    ours.add(k);
                    notes.add("kept-vanilla " + k + " does not link (" + bad + "): ours");
                    changed = true;
                }
            }
            for (String k : new ArrayList<>(restored)) {
                String bad = unresolved(v, find(v, k), merges);
                if (bad != null) {
                    restored.remove(k);
                    notes.add("removed vanilla method " + k + " not restored (" + bad + ")");
                    changed = true;
                }
            }
            return changed;
        }

        /** First reference (of the method and its lambdas) to a patched class member that the merge does not provide. */
        String unresolved(ClassModel src, MethodModel m, Map<String, Merge> merges) {
            Set<String> lams = new LinkedHashSet<>();
            lambdaClosure(src, m, lams);
            List<MethodModel> all = new ArrayList<>();
            all.add(m);
            for (String l : lams) { MethodModel lm = find(src, l); if (lm != null) all.add(lm); }
            for (MethodModel mm : all) {
                CodeModel code = mm.code().orElse(null);
                if (code == null) continue;
                for (CodeElement e : code) {
                    if (e instanceof FieldInstruction fi) {
                        Merge t = merges.get(fi.owner().asInternalName());
                        if (t != null && !t.hasField(fi.name().stringValue(), fi.type().stringValue(), merges)) return "field " + fi.owner().asInternalName() + "." + fi.name().stringValue();
                    } else if (e instanceof InvokeInstruction ii) {
                        Merge t = merges.get(ii.owner().asInternalName());
                        if (t != null && !t.hasMethod(ii.name().stringValue() + ii.type().stringValue(), merges)) return "method " + ii.owner().asInternalName() + "." + ii.name().stringValue() + ii.type().stringValue();
                    } else if (e instanceof InvokeDynamicInstruction id) {
                        for (ConstantDesc arg : id.bootstrapArgs()) {
                            if (arg instanceof DirectMethodHandleDesc dm) {
                                String owner = dm.owner().descriptorString();
                                owner = owner.substring(1, owner.length() - 1);
                                Merge t = merges.get(owner);
                                if (t != null && t != this && !dm.methodName().startsWith("lambda$") && !t.hasMethod(dm.methodName() + dm.lookupDescriptor(), merges)
                                    && !t.hasField(dm.methodName(), dm.lookupDescriptor(), merges)) return "handle " + owner + "." + dm.methodName();
                            }
                        }
                    }
                }
            }
            return null;
        }

        boolean hasField(String n, String desc, Map<String, Merge> merges) {
            for (FieldModel f : o.fields()) if (f.fieldName().equalsString(n) && f.fieldType().equalsString(desc)) return true;
            return superHas(merges, true, n, desc);
        }

        boolean hasMethod(String k, Map<String, Merge> merges) {
            if (keepV.contains(k) || ours.contains(k) || restored.contains(k)) return true;
            if (k.startsWith("lambda$")) return find(o, k) != null || find(v, k) != null;
            return superHas(merges, false, k, null);
        }

        /** Inherited member (the superclass chain is outside the merge or another merge): assume resolvable unless the owner
         *  is a merged class whose superclass is java/lang/Object or a merged class lacking it. Conservative enough: the JVM
         *  resolves inherited members, and the patched set never moves members between a class and its superclass. */
        boolean superHas(Map<String, Merge> merges, boolean field, String n, String desc) {
            String sup = o.superclass().map(ClassEntry::asInternalName).orElse(null);
            if (sup == null || sup.equals("java/lang/Object")) {
                // interfaces / Object methods (toString etc. on records) or genuinely missing
                if (!field && (n.startsWith("toString()") || n.startsWith("hashCode()") || n.startsWith("equals(") || n.startsWith("getClass(")
                    || n.startsWith("clone()") || n.startsWith("wait(") || n.startsWith("notify"))) return true;
                return !o.interfaces().isEmpty() && !field;   // default methods of interfaces
            }
            Merge t = merges.get(sup);
            if (t == null) return true;
            return field ? t.hasField(n, desc, merges) : t.hasMethod(n, merges);
        }

        byte[] build() {
            Set<String> vLams = new LinkedHashSet<>(), oLams = new LinkedHashSet<>();
            for (String k : keepV) lambdaClosure(v, find(v, k), vLams);
            for (String k : restored) lambdaClosure(v, find(v, k), vLams);
            for (String k : ours) lambdaClosure(o, find(o, k), oLams);
            Set<String> vNames = new HashSet<>();
            for (String k : keepV) vNames.add(k.substring(0, k.indexOf('(')));
            for (String k : restored) vNames.add(k.substring(0, k.indexOf('(')));
            for (String k : vLams) vNames.add(k.substring(0, k.indexOf('(')));
            Map<String, String> rename = new HashMap<>();   // our lambda key -> new name
            Set<String> usedNames = new HashSet<>(vNames);
            for (String k : ours) usedNames.add(k.substring(0, k.indexOf('(')));
            for (String k : oLams) {
                String nm = k.substring(0, k.indexOf('('));
                if (vNames.contains(nm)) {
                    String nn = nm + "$mc";
                    int i = 2;
                    while (usedNames.contains(nn)) nn = nm + "$mc" + i++;
                    usedNames.add(nn);
                    rename.put(k, nn);
                } else {
                    usedNames.add(nm);
                }
            }
            keptCount = keepV.size();
            oursCount = ours.size();
            report.printf("%s: vanilla kept %d (+%d lambdas), ours %d (+%d lambdas, %d renamed), restored %d%n", name, keepV.size(),
                vLams.size(), ours.size(), oLams.size(), rename.size(), restored.size());
            for (String k : ours) {
                MethodModel vm = find(v, k);
                report.printf("    ours %s%s%n", k, vm == null ? " [new]" : "");
            }
            for (String k : restored) report.printf("    restored %s%n", k);
            for (String nt : notes) report.printf("    %s%n", nt);
            for (var e : rename.entrySet()) report.printf("    lambda %s -> %s%n", e.getKey(), e.getValue());

            ClassDesc self = o.thisClass().asSymbol();
            ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(RESOLVER));
            CodeTransform renamer = (b, e) -> {
                if (e instanceof InvokeDynamicInstruction id && !rename.isEmpty()) {
                    List<ConstantDesc> args = new ArrayList<>(id.bootstrapArgs());
                    boolean hit = false;
                    for (int i = 0; i < args.size(); i++) {
                        if (args.get(i) instanceof DirectMethodHandleDesc dm && dm.owner().equals(self)) {
                            String nn = rename.get(dm.methodName() + dm.lookupDescriptor());
                            if (nn != null) {
                                args.set(i, MethodHandleDesc.ofMethod(dm.kind(), dm.owner(), nn, MethodTypeDesc.ofDescriptor(dm.lookupDescriptor())));
                                hit = true;
                            }
                        }
                    }
                    if (hit) {
                        b.invokedynamic(DynamicCallSiteDesc.of(id.bootstrapMethod(), id.name().stringValue(), id.typeSymbol(), args.toArray(new ConstantDesc[0])));
                        return;
                    }
                }
                b.with(e);
            };
            return cf.build(self, cb -> {
                for (ClassElement ce : o) {
                    if (ce instanceof MethodModel) continue;
                    cb.with(ce);
                }
                // methods in our order, vanilla bytes where kept
                for (MethodModel om : o.methods()) {
                    String k = key(om);
                    if (keepV.contains(k)) {
                        cb.with(find(v, k));
                    } else if (ours.contains(k)) {
                        cb.transformMethod(om, MethodTransform.transformingCode(renamer));
                    } else if (oLams.contains(k)) {
                        String nn = rename.get(k);
                        if (nn == null) cb.transformMethod(om, MethodTransform.transformingCode(renamer));
                        else cb.withMethod(nn, om.methodTypeSymbol(), om.flags().flagsMask(), mb -> {
                            for (MethodElement me : om) {
                                if (me instanceof CodeModel cm) mb.transformCode(cm, renamer);
                                else mb.with(me);
                            }
                        });
                    }
                }
                for (MethodModel vm : v.methods()) {
                    String k = key(vm);
                    if (restored.contains(k) || vLams.contains(k)) cb.with(vm);
                }
            });
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // class hierarchy for stack map generation: our classes, vanilla, libraries, JDK

    static final ClassHierarchyResolver RESOLVER = ClassHierarchyResolver.defaultResolver()
        .orElse(ClassHierarchyResolver.ofResourceParsing((Function<ClassDesc, InputStream>) MethodMerge::open)).cached();

    /** Whether a supertype (superclass chain or interfaces, ours / vanilla / libraries / JDK) declares this method. */
    static boolean declaredInSupertype(ClassModel c, String key, Set<String> seen) {
        List<String> sups = new ArrayList<>();
        c.superclass().ifPresent(s -> sups.add(s.asInternalName()));
        for (ClassEntry i : c.interfaces()) sups.add(i.asInternalName());
        for (String s : sups) {
            if (!seen.add(s)) continue;
            ClassModel sm = load(s);
            if (sm == null) return true;   // unknown supertype: assume it might
            if (find(sm, key) != null) return true;
            if (declaredInSupertype(sm, key, seen)) return true;
        }
        return false;
    }

    static ClassModel load(String internal) {
        try (InputStream in = open(ClassDesc.ofInternalName(internal))) {
            if (in != null) return parse(in.readAllBytes());
        } catch (IOException e) {
            return null;
        }
        try (InputStream in = ClassLoader.getSystemResourceAsStream(internal + ".class")) {
            if (in != null) return parse(in.readAllBytes());
        } catch (IOException e) {
            return null;
        }
        try {
            var p = java.nio.file.FileSystems.getFileSystem(java.net.URI.create("jrt:/"));
            Path mods = p.getPath("modules");
            try (var s = Files.list(mods)) {
                for (Path m : (Iterable<Path>) s::iterator) {
                    Path cp = m.resolve(internal + ".class");
                    if (Files.exists(cp)) return parse(Files.readAllBytes(cp));
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    static InputStream open(ClassDesc cd) {
        String n = cd.descriptorString();
        n = n.substring(1, n.length() - 1) + ".class";
        try {
            Path p = classes.resolve(n);
            if (Files.exists(p)) return Files.newInputStream(p);
            byte[] b = vanilla.get(n);
            if (b != null) return new ByteArrayInputStream(b);
            for (ZipFile z : libs) {
                var e = z.getEntry(n);
                if (e != null) return z.getInputStream(e);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}
