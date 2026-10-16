import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.*;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.instruction.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Static mixin audit (results/FABRIC.md, docs/metal/CONTRACT.md 16.FAB): which mixins of a set of Fabric mods target
 * classes our client jar ships changed, and does every injection point / shadow / accessor that resolves in the vanilla
 * class also resolve, the same number of times, in ours?
 *
 * usage: java tools/MixinAudit.java <vanilla.jar> <client.jar> <mod.jar|dir>... [--all] [--api]
 *   --all  also list mixins whose injections all resolve identically (default: only differences)
 *   --api  also print, per changed class, vanilla members (fields / methods incl. lambdas) missing from ours
 * exit 0 = no injection resolves differently, 1 = differences (listed), 2 = usage error.
 *
 * What it models (Mixin 0.8 / MixinExtras): target classes from @Mixin(value / targets); injectors @Inject, @Redirect,
 * @ModifyArg(s), @ModifyVariable, @ModifyConstant, MixinExtras @WrapOperation, @WrapWithCondition, @ModifyExpressionValue,
 * @ModifyReturnValue, @ModifyReceiver, @WrapMethod; @Overwrite, @Shadow, @Accessor, @Invoker. Method selectors: name,
 * name + descriptor, owner prefix, trailing '*', "<init>" / "<clinit>", regex ("/.../"). Injection points counted per
 * target method: HEAD, RETURN, TAIL, INVOKE / INVOKE_ASSIGN / INVOKE_STRING (owner, name, descriptor), FIELD (+ opcode),
 * NEW, CONSTANT (args or @Constant), JUMP (opcode); LOAD / STORE (ModifyVariable) and MIXINEXTRAS:EXPRESSION are counted
 * as "unchecked" (they depend on local variable layout / expressions: compared by the local-variable signature instead).
 * Slices are ignored (counts are over the whole method), so a count difference is a finding to inspect, not always a
 * break; a zero where vanilla has matches (or an ordinal beyond our count) is a break.
 */
public final class MixinAudit {
    record Mod(String id, String jar) {}

    record Inj(String kind, List<String> methods, List<Map<String, Object>> ats, Map<String, Object> raw, String handler) {}

    record MixinCls(Mod mod, String name, List<String> targets, List<Inj> injs, List<String[]> shadows,
                    List<String[]> accessors) {}

    static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    static final Map<String, String> INJECTORS = Map.ofEntries(
        Map.entry("Lorg/spongepowered/asm/mixin/injection/Inject;", "Inject"),
        Map.entry("Lorg/spongepowered/asm/mixin/injection/Redirect;", "Redirect"),
        Map.entry("Lorg/spongepowered/asm/mixin/injection/ModifyArg;", "ModifyArg"),
        Map.entry("Lorg/spongepowered/asm/mixin/injection/ModifyArgs;", "ModifyArgs"),
        Map.entry("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;", "ModifyVariable"),
        Map.entry("Lorg/spongepowered/asm/mixin/injection/ModifyConstant;", "ModifyConstant"),
        Map.entry("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", "WrapOperation"),
        Map.entry("Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;", "WrapWithCondition"),
        Map.entry("Lcom/llamalad7/mixinextras/injector/WrapWithCondition;", "WrapWithCondition"),
        Map.entry("Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;", "ModifyExpressionValue"),
        Map.entry("Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;", "ModifyReturnValue"),
        Map.entry("Lcom/llamalad7/mixinextras/injector/ModifyReceiver;", "ModifyReceiver"),
        Map.entry("Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;", "WrapMethod"));

    // ---------------------------------------------------------------------------------------------------------------
    // jars

    static Map<String, byte[]> readJar(byte[] bytes, String prefix) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.isDirectory()) out.put(prefix + e.getName(), z.readAllBytes());
            }
        }
        return out;
    }

    /** All entries of a jar and (recursively) of its META-INF/jars nested jars, keyed "nested.jar!/entry". */
    static List<Map<String, byte[]>> jarAndNested(byte[] bytes) throws IOException {
        List<Map<String, byte[]>> out = new ArrayList<>();
        Map<String, byte[]> top = readJar(bytes, "");
        out.add(top);
        for (var en : top.entrySet()) {
            if (en.getKey().startsWith("META-INF/jars/") && en.getKey().endsWith(".jar")) {
                out.addAll(jarAndNested(en.getValue()));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // minimal JSON (fabric.mod.json, mixin configs)

    static Object json(String s) {
        int[] p = {0};
        return parse(s, p);
    }

    static void ws(String s, int[] p) {
        while (p[0] < s.length()) {
            char c = s.charAt(p[0]);
            if (Character.isWhitespace(c)) p[0]++;
            else if (c == '/' && p[0] + 1 < s.length() && s.charAt(p[0] + 1) == '/') { while (p[0] < s.length() && s.charAt(p[0]) != '\n') p[0]++; }
            else if (c == '/' && p[0] + 1 < s.length() && s.charAt(p[0] + 1) == '*') { p[0] = s.indexOf("*/", p[0] + 2) + 2; }
            else break;
        }
    }

    static Object parse(String s, int[] p) {
        ws(s, p);
        char c = s.charAt(p[0]);
        if (c == '{') {
            Map<String, Object> m = new LinkedHashMap<>();
            p[0]++;
            while (true) {
                ws(s, p);
                if (s.charAt(p[0]) == '}') { p[0]++; return m; }
                String k = (String) parse(s, p);
                ws(s, p); p[0]++; // :
                m.put(k, parse(s, p));
                ws(s, p);
                if (s.charAt(p[0]) == ',') p[0]++;
            }
        } else if (c == '[') {
            List<Object> l = new ArrayList<>();
            p[0]++;
            while (true) {
                ws(s, p);
                if (s.charAt(p[0]) == ']') { p[0]++; return l; }
                l.add(parse(s, p));
                ws(s, p);
                if (s.charAt(p[0]) == ',') p[0]++;
            }
        } else if (c == '"') {
            StringBuilder b = new StringBuilder();
            p[0]++;
            while (s.charAt(p[0]) != '"') {
                char d = s.charAt(p[0]++);
                if (d == '\\') {
                    char e = s.charAt(p[0]++);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 't' -> b.append('\t');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16)); p[0] += 4; }
                        default -> b.append(e);
                    }
                } else b.append(d);
            }
            p[0]++;
            return b.toString();
        } else {
            int st = p[0];
            while (p[0] < s.length() && ",}] \n\r\t".indexOf(s.charAt(p[0])) < 0) p[0]++;
            String t = s.substring(st, p[0]);
            return switch (t) { case "true" -> Boolean.TRUE; case "false" -> Boolean.FALSE; case "null" -> null; default -> t; };
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // annotations -> plain maps

    static Object val(AnnotationValue v) {
        return switch (v) {
            case AnnotationValue.OfString s -> s.stringValue();
            case AnnotationValue.OfClass c -> c.classSymbol().descriptorString();
            case AnnotationValue.OfEnum e -> e.constantName().stringValue();
            case AnnotationValue.OfAnnotation a -> map(a.annotation());
            case AnnotationValue.OfArray a -> a.values().stream().map(MixinAudit::val).toList();
            case AnnotationValue.OfConstant k -> k.resolvedValue();
        };
    }

    static Map<String, Object> map(Annotation a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("@", a.className().stringValue());
        for (AnnotationElement e : a.elements()) m.put(e.name().stringValue(), val(e.value()));
        return m;
    }

    static List<Annotation> annotations(AttributedElement el) {
        List<Annotation> out = new ArrayList<>();
        el.findAttribute(Attributes.runtimeInvisibleAnnotations()).ifPresent(a -> out.addAll(a.annotations()));
        el.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(a -> out.addAll(a.annotations()));
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) return (List<Object>) l;
        return List.of(o);
    }

    static String internal(String descOrName) {
        if (descOrName.startsWith("L") && descOrName.endsWith(";")) return descOrName.substring(1, descOrName.length() - 1);
        return descOrName.replace('.', '/');
    }

    // ---------------------------------------------------------------------------------------------------------------
    // mixin discovery

    static List<MixinCls> mixins(Path jarPath) throws IOException {
        List<MixinCls> out = new ArrayList<>();
        for (Map<String, byte[]> jar : jarAndNested(Files.readAllBytes(jarPath))) {
            byte[] fmj = jar.get("fabric.mod.json");
            if (fmj == null) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> mod = (Map<String, Object>) json(new String(fmj, java.nio.charset.StandardCharsets.UTF_8));
            Mod m = new Mod(String.valueOf(mod.get("id")), jarPath.getFileName().toString());
            for (Object cfg : list(mod.get("mixins"))) {
                String name = cfg instanceof Map<?, ?> cm ? String.valueOf(cm.get("config")) : String.valueOf(cfg);
                if (cfg instanceof Map<?, ?> cm && "server".equals(cm.get("environment"))) continue;
                byte[] cj = jar.get(name);
                if (cj == null) { System.err.println("missing mixin config " + name + " in " + m); continue; }
                @SuppressWarnings("unchecked")
                Map<String, Object> c = (Map<String, Object>) json(new String(cj, java.nio.charset.StandardCharsets.UTF_8));
                String pkg = String.valueOf(c.get("package")).replace('.', '/');
                List<Object> names = new ArrayList<>(list(c.get("mixins")));
                names.addAll(list(c.get("client")));
                for (Object n : names) {
                    String cls = pkg + "/" + String.valueOf(n).replace('.', '/');
                    byte[] b = jar.get(cls + ".class");
                    if (b == null) { System.err.println("missing mixin class " + cls + " in " + m); continue; }
                    out.add(mixinClass(m, cls, b));
                }
            }
        }
        return out;
    }

    static MixinCls mixinClass(Mod mod, String name, byte[] b) {
        ClassModel cm = ClassFile.of().parse(b);
        List<String> targets = new ArrayList<>();
        for (Annotation a : annotations(cm)) {
            if (!a.className().stringValue().equals(MIXIN)) continue;
            Map<String, Object> m = map(a);
            for (Object v : list(m.get("value"))) targets.add(internal((String) v));
            for (Object v : list(m.get("targets"))) targets.add(internal((String) v));
        }
        List<Inj> injs = new ArrayList<>();
        List<String[]> shadows = new ArrayList<>();
        List<String[]> accessors = new ArrayList<>();
        for (MethodModel mm : cm.methods()) {
            for (Annotation a : annotations(mm)) {
                String an = a.className().stringValue();
                Map<String, Object> m = map(a);
                String kind = INJECTORS.get(an);
                if (kind != null) {
                    List<String> methods = new ArrayList<>();
                    for (Object o : list(m.get("method"))) methods.add((String) o);
                    for (Object o : list(m.get("target"))) if (o instanceof Map<?, ?> d) methods.add(descSelector(d));
                    List<Map<String, Object>> ats = new ArrayList<>();
                    for (Object o : list(m.get("at"))) {
                        @SuppressWarnings("unchecked") Map<String, Object> at = (Map<String, Object>) o;
                        ats.add(at);
                    }
                    if (kind.equals("ModifyConstant") && ats.isEmpty()) {
                        Map<String, Object> at = new LinkedHashMap<>();
                        at.put("value", "CONSTANT");
                        at.put("constant", m.get("constant"));
                        ats.add(at);
                    }
                    if (kind.equals("ModifyReturnValue") || kind.equals("WrapMethod")) {
                        // ModifyReturnValue has at=RETURN; WrapMethod wraps the whole method
                        if (ats.isEmpty()) { Map<String, Object> at = new LinkedHashMap<>(); at.put("value", "HEAD"); ats.add(at); }
                    }
                    injs.add(new Inj(kind, methods, ats, m, mm.methodName().stringValue()));
                } else if (an.equals("Lorg/spongepowered/asm/mixin/Overwrite;")) {
                    injs.add(new Inj("Overwrite", List.of(mm.methodName().stringValue() + mm.methodType().stringValue()),
                        List.of(Map.of("value", "HEAD")), m, mm.methodName().stringValue()));
                } else if (an.equals("Lorg/spongepowered/asm/mixin/Shadow;")) {
                    String n = mm.methodName().stringValue();
                    String prefix = (String) m.getOrDefault("prefix", "shadow$");
                    if (n.startsWith(prefix)) n = n.substring(prefix.length());
                    shadows.add(new String[] {"M", n, mm.methodType().stringValue()});
                } else if (an.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;") || an.equals("Lorg/spongepowered/asm/mixin/gen/Invoker;")) {
                    String n = (String) m.getOrDefault("value", "");
                    String mn = mm.methodName().stringValue();
                    boolean inv = an.contains("Invoker");
                    if (n.isEmpty()) {
                        for (String p : inv ? new String[] {"invoke", "call", "new", "create"} : new String[] {"get", "set", "is"}) {
                            if (mn.startsWith(p) && mn.length() > p.length()) {
                                n = Character.toLowerCase(mn.charAt(p.length())) + mn.substring(p.length() + 1);
                                if (inv && (p.equals("new") || p.equals("create"))) n = "<init>";
                                break;
                            }
                        }
                    }
                    accessors.add(new String[] {inv ? "I" : "A", n, mm.methodType().stringValue(), mn});
                }
            }
        }
        for (FieldModel fm : cm.fields()) {
            for (Annotation a : annotations(fm)) {
                if (a.className().stringValue().equals("Lorg/spongepowered/asm/mixin/Shadow;")) {
                    String n = fm.fieldName().stringValue();
                    Map<String, Object> m = map(a);
                    String prefix = (String) m.getOrDefault("prefix", "shadow$");
                    if (n.startsWith(prefix)) n = n.substring(prefix.length());
                    shadows.add(new String[] {"F", n, fm.fieldType().stringValue()});
                }
            }
        }
        return new MixinCls(mod, name, targets, injs, shadows, accessors);
    }

    static String descSelector(Map<?, ?> desc) {
        return String.valueOf(desc.get("value"));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // resolution

    static final Pattern SEL = Pattern.compile("^(L[^;]+;)?([^(:]*)(\\(.*)?$");

    static List<MethodModel> select(ClassModel cm, String sel) {
        List<MethodModel> out = new ArrayList<>();
        if (sel.startsWith("desc=") || sel.startsWith("name=") || sel.startsWith("/")) {
            Pattern p = Pattern.compile(sel.replaceAll("^(desc=|name=)?/", "").replaceAll("/$", ""));
            for (MethodModel m : cm.methods()) if (p.matcher(m.methodName().stringValue()).find()) out.add(m);
            return out;
        }
        Matcher mt = SEL.matcher(sel.trim());
        if (!mt.matches()) return out;
        String name = mt.group(2);
        String desc = mt.group(3);
        boolean star = name.endsWith("*");
        if (star) name = name.substring(0, name.length() - 1);
        for (MethodModel m : cm.methods()) {
            String n = m.methodName().stringValue();
            if (!(name.isEmpty() ? star : n.equals(name))) continue;
            if (desc != null && !m.methodType().stringValue().equals(desc)) continue;
            out.add(m);
        }
        return out;
    }

    /** "Lowner;name(desc)ret" or "Lowner;name:Ltype;" -> {owner, name, desc} (owner/desc may be null). */
    static String[] member(String t) {
        String owner = null, rest = t;
        if (t.startsWith("L") && t.indexOf(';') > 0) { owner = t.substring(1, t.indexOf(';')); rest = t.substring(t.indexOf(';') + 1); }
        if (rest.startsWith(".")) rest = rest.substring(1);
        String name = rest, desc = null;
        int i = rest.indexOf('(');
        int j = rest.indexOf(':');
        if (i >= 0) { name = rest.substring(0, i); desc = rest.substring(i); }
        else if (j >= 0) { name = rest.substring(0, j); desc = rest.substring(j + 1); }
        return new String[] {owner, name, desc};
    }

    static boolean memberMatches(String[] want, String owner, String name, String desc) {
        if (want[0] != null && !want[0].equals(owner)) return false;
        if (!want[1].isEmpty() && !want[1].equals(name)) return false;
        return want[2] == null || want[2].equals(desc);
    }

    /** Matches of one @At in one method; -1 = not checkable statically. */
    static int count(MethodModel m, Map<String, Object> at, Map<String, Object> injRaw) {
        String v = String.valueOf(at.get("value"));
        String target = (String) at.get("target");
        Object opc = at.get("opcode");
        List<Object> args = list(at.get("args"));
        CodeModel code = m.code().orElse(null);
        if (code == null) return 0;
        int n = 0;
        switch (v) {
            case "HEAD": return 1;
            case "RETURN": case "TAIL": {
                for (CodeElement e : code) if (e instanceof ReturnInstruction) n++;
                return v.equals("TAIL") ? Math.min(n, 1) : n;
            }
            case "INVOKE": case "INVOKE_ASSIGN": case "INVOKE_STRING": {
                String[] w = member(target == null ? "" : target);
                for (CodeElement e : code) {
                    if (e instanceof InvokeInstruction ii && memberMatches(w, ii.owner().asInternalName(), ii.name().stringValue(), ii.type().stringValue())) n++;
                }
                return n;
            }
            case "FIELD": {
                String[] w = member(target == null ? "" : target);
                for (CodeElement e : code) {
                    if (e instanceof FieldInstruction fi && memberMatches(w, fi.owner().asInternalName(), fi.name().stringValue(), fi.type().stringValue())
                        && (opc == null || ((Number) opc).intValue() == fi.opcode().bytecode())) n++;
                }
                return n;
            }
            case "NEW": {
                String t = target == null ? "" : target;
                String cls = t.startsWith("L") ? member(t)[0] : t.replace('.', '/');
                if (t.startsWith("(")) cls = null;
                for (CodeElement e : code) if (e instanceof NewObjectInstruction no && (cls == null || no.className().asInternalName().equals(cls))) n++;
                return n;
            }
            case "JUMP": {
                for (CodeElement e : code) if (e instanceof BranchInstruction bi && (opc == null || ((Number) opc).intValue() == bi.opcode().bytecode())) n++;
                return n;
            }
            case "CONSTANT": {
                Object want = constantOf(args, at.get("constant"));
                if (want == null) return -1;
                for (CodeElement e : code) {
                    Object c = null;
                    if (e instanceof ConstantInstruction ci) c = ci.constantValue();
                    if (c == null) continue;
                    if (want instanceof Number wn && c instanceof Number cn && wn.getClass() == cn.getClass() && wn.equals(cn)) n++;
                    else if (want.equals(c)) n++;
                    else if (want instanceof java.lang.constant.ClassDesc wd && c instanceof java.lang.constant.ClassDesc cd && wd.descriptorString().equals(cd.descriptorString())) n++;
                }
                return n;
            }
            default: return -1;
        }
    }

    static Object constantOf(List<Object> args, Object constant) {
        for (Object a : args) {
            String s = String.valueOf(a);
            int eq = s.indexOf('=');
            if (eq < 0) continue;
            String k = s.substring(0, eq), v = s.substring(eq + 1);
            switch (k) {
                case "intValue": return Integer.valueOf(v);
                case "floatValue": return Float.valueOf(v);
                case "doubleValue": return Double.valueOf(v);
                case "longValue": return Long.valueOf(v);
                case "stringValue": return v;
                case "classValue": return java.lang.constant.ClassDesc.ofInternalName(v.replace('.', '/'));
                default: break;
            }
        }
        for (Object o : list(constant)) {
            if (o instanceof Map<?, ?> c) {
                for (String k : List.of("intValue", "floatValue", "doubleValue", "longValue", "stringValue")) {
                    if (c.containsKey(k)) {
                        Object v = c.get(k);
                        return v;
                    }
                }
                if (c.containsKey("classValue")) return java.lang.constant.ClassDesc.ofDescriptor((String) c.get("classValue"));
            }
        }
        return null;
    }

    static String lvtSig(MethodModel m) {
        return m.code().flatMap(c -> c.findAttribute(Attributes.localVariableTable()))
            .map(t -> t.localVariables().stream().map(l -> l.slot() + ":" + l.type().stringValue()).sorted().distinct().toList().toString())
            .orElse("(no LVT)");
    }

    // ---------------------------------------------------------------------------------------------------------------

    public static void main(String[] argv) throws Exception {
        boolean all = false, api = false;
        List<String> pos = new ArrayList<>();
        for (String a : argv) {
            if (a.equals("--all")) all = true;
            else if (a.equals("--api")) api = true;
            else if (a.equals("--methods")) methods = true;
            else pos.add(a);
        }
        if (pos.size() < 3) { System.err.println("usage: MixinAudit <vanilla.jar> <client.jar> <mod.jar|dir>... [--all] [--api]"); System.exit(2); }
        Map<String, byte[]> van = readJar(Files.readAllBytes(Path.of(pos.get(0))), "");
        Map<String, byte[]> ours = readJar(Files.readAllBytes(Path.of(pos.get(1))), "");
        Set<String> changed = new TreeSet<>();
        for (var e : ours.entrySet()) {
            String k = e.getKey();
            if (!k.endsWith(".class")) continue;
            byte[] vb = van.get(k);
            if (vb != null && !Arrays.equals(vb, e.getValue())) changed.add(k.substring(0, k.length() - 6));
        }
        Set<String> removed = new TreeSet<>();
        for (String k : van.keySet()) if (k.endsWith(".class") && !ours.containsKey(k)) removed.add(k);
        System.out.println("changed vanilla classes in " + pos.get(1) + ": " + changed.size() + (removed.isEmpty() ? "" : ", removed: " + removed));

        List<Path> jars = new ArrayList<>();
        for (String p : pos.subList(2, pos.size())) {
            Path pp = Path.of(p);
            if (Files.isDirectory(pp)) try (var s = Files.list(pp)) { s.filter(x -> x.toString().endsWith(".jar")).sorted().forEach(jars::add); }
            else jars.add(pp);
        }
        int problems = 0, checked = 0, mixinsOnChanged = 0;
        Map<String, Integer> perClass = new TreeMap<>();
        Map<String, Set<String>> modsPerClass = new TreeMap<>();
        for (Path jar : jars) {
            for (MixinCls mx : mixins(jar)) {
                for (String target : mx.targets()) {
                    if (!changed.contains(target)) continue;
                    mixinsOnChanged++;
                    perClass.merge(target, 1, Integer::sum);
                    modsPerClass.computeIfAbsent(target, k -> new TreeSet<>()).add(mx.mod().id());
                    ClassModel vc = ClassFile.of().parse(van.get(target + ".class"));
                    ClassModel oc = ClassFile.of().parse(ours.get(target + ".class"));
                    List<String> lines = new ArrayList<>();
                    boolean bad = false;
                    for (Inj inj : mx.injs()) {
                        for (String sel : inj.methods()) {
                            List<MethodModel> vms = select(vc, sel), oms = select(oc, sel);
                            for (Map<String, Object> at : inj.ats()) {
                                checked++;
                                int vn = 0, on = 0;
                                boolean unchecked = false;
                                for (MethodModel m : vms) { int c = count(m, at, inj.raw()); if (c < 0) unchecked = true; else vn += c; }
                                for (MethodModel m : oms) { int c = count(m, at, inj.raw()); if (c < 0) unchecked = true; else on += c; }
                                Object ord = at.get("ordinal");
                                int ordinal = ord == null ? -1 : ((Number) ord).intValue();
                                String atDesc = at.get("value") + (at.get("target") != null ? " " + at.get("target") : "")
                                    + (ord != null ? " ordinal=" + ord : "") + (at.get("args") != null ? " args=" + at.get("args") : "");
                                String verdict;
                                if (vms.isEmpty()) verdict = "n/a (no such method in vanilla either)";
                                else if (oms.isEmpty()) { verdict = "BROKEN: method missing in ours"; bad = true; }
                                else if (unchecked) {
                                    String vs = vms.stream().map(MixinAudit::lvtSig).toList().toString();
                                    String os = oms.stream().map(MixinAudit::lvtSig).toList().toString();
                                    boolean same = vs.equals(os);
                                    verdict = same ? "ok? (unchecked point, same locals)" : "CHECK: unchecked point, locals differ";
                                    if (!same) bad = true;
                                } else if (vn > 0 && on == 0) { verdict = "BROKEN: " + vn + " match(es) in vanilla, 0 in ours"; bad = true; }
                                else if (ordinal >= 0 && ordinal < vn && ordinal >= on) { verdict = "BROKEN: ordinal " + ordinal + " beyond our " + on; bad = true; }
                                else if (vn != on) { verdict = "DIFF: vanilla " + vn + ", ours " + on; bad = true; }
                                else verdict = "ok (" + vn + ")";
                                lines.add("    " + inj.kind() + " " + inj.handler() + " -> " + sel + " @ " + atDesc + ": " + verdict);
                            }
                        }
                    }
                    for (String[] s : mx.shadows()) {
                        boolean inV = has(vc, s), inO = has(oc, s);
                        if (inV && !inO) { bad = true; lines.add("    Shadow " + s[0] + " " + s[1] + " " + s[2] + ": BROKEN: missing in ours"); }
                        else if (all) lines.add("    Shadow " + s[0] + " " + s[1] + ": " + (inV ? "ok" : "n/a (not in vanilla: inherited?)"));
                    }
                    for (String[] s : mx.accessors()) {
                        boolean inV = hasAcc(vc, s), inO = hasAcc(oc, s);
                        if (inV && !inO) { bad = true; lines.add("    " + (s[0].equals("I") ? "Invoker " : "Accessor ") + s[1] + ": BROKEN: missing in ours"); }
                        else if (all) lines.add("    " + (s[0].equals("I") ? "Invoker " : "Accessor ") + s[1] + ": " + (inV ? "ok" : "n/a"));
                    }
                    if (bad) problems++;
                    if (bad || all) {
                        System.out.println((bad ? "PROBLEM " : "ok      ") + mx.mod().id() + " (" + mx.mod().jar() + ") " + mx.name() + " -> " + target);
                        for (String l : lines) if (all || !l.endsWith(")") || l.contains("BROKEN") || l.contains("DIFF") || l.contains("CHECK")) {
                            if (all || l.contains("BROKEN") || l.contains("DIFF") || l.contains("CHECK")) System.out.println(l);
                        }
                    }
                }
            }
        }
        System.out.println();
        System.out.println("mixins on changed classes: " + mixinsOnChanged + " (" + checked + " injection points checked)");
        for (var e : perClass.entrySet()) System.out.println("  " + e.getKey() + ": " + e.getValue() + " mixin(s) from " + modsPerClass.get(e.getKey()));
        if (api) {
            System.out.println();
            System.out.println("vanilla members missing from ours (per changed class):");
            for (String c : changed) {
                ClassModel vc = ClassFile.of().parse(van.get(c + ".class"));
                ClassModel oc = ClassFile.of().parse(ours.get(c + ".class"));
                Set<String> om = new HashSet<>();
                for (MethodModel m : oc.methods()) om.add(m.methodName().stringValue() + m.methodType().stringValue());
                for (FieldModel f : oc.fields()) om.add(f.fieldName().stringValue() + ":" + f.fieldType().stringValue());
                List<String> miss = new ArrayList<>();
                for (MethodModel m : vc.methods()) { String k = m.methodName().stringValue() + m.methodType().stringValue(); if (!om.contains(k)) miss.add(k); }
                for (FieldModel f : vc.fields()) { String k = f.fieldName().stringValue() + ":" + f.fieldType().stringValue(); if (!om.contains(k)) miss.add(k); }
                if (!miss.isEmpty()) System.out.println("  " + c + ": " + miss);
            }
        }
        if (methods) {
            System.out.println();
            System.out.println("methods whose code differs from vanilla (normalized: symbolic operands, no line numbers / LVT):");
            int same = 0, diff = 0;
            for (String c : changed) {
                ClassModel vc = ClassFile.of().parse(van.get(c + ".class"));
                ClassModel oc = ClassFile.of().parse(ours.get(c + ".class"));
                Map<String, MethodModel> om = new HashMap<>();
                for (MethodModel m : oc.methods()) om.put(m.methodName().stringValue() + m.methodType().stringValue(), m);
                List<String> d = new ArrayList<>();
                for (MethodModel m : vc.methods()) {
                    String k = m.methodName().stringValue() + m.methodType().stringValue();
                    MethodModel o = om.get(k);
                    if (o == null) { d.add(k + " [missing]"); diff++; continue; }
                    if (norm(m).equals(norm(o))) same++;
                    else { d.add(k + " [" + norm(m).size() + " -> " + norm(o).size() + " insns]"); diff++; }
                }
                Set<String> vk = new HashSet<>();
                for (MethodModel m : vc.methods()) vk.add(m.methodName().stringValue() + m.methodType().stringValue());
                for (String k : om.keySet()) if (!vk.contains(k)) d.add(k + " [added]");
                if (!d.isEmpty()) System.out.println("  " + c + ": " + d);
            }
            System.out.println("  vanilla methods identical: " + same + ", different or missing: " + diff);
        }
        System.out.println(problems == 0 ? "MIXIN AUDIT: no differences" : "MIXIN AUDIT: " + problems + " mixin(s) resolve differently");
        System.exit(problems == 0 ? 0 : 1);
    }

    static final Map<MethodModel, List<String>> NORM = new IdentityHashMap<>();

    /** Instructions as strings with branch targets as instruction indices (labels, line numbers, LVT and stack maps dropped). */
    static List<String> norm(MethodModel m) {
        return NORM.computeIfAbsent(m, k -> {
            CodeModel code = m.code().orElse(null);
            if (code == null) return List.of();
            Map<Label, Integer> pos = new HashMap<>();
            int n = 0;
            for (CodeElement e : code) {
                if (e instanceof LabelTarget lt) pos.put(lt.label(), n);
                else if (e instanceof Instruction) n++;
            }
            List<String> out = new ArrayList<>();
            for (CodeElement e : code) {
                switch (e) {
                    case BranchInstruction b -> out.add(b.opcode() + " L" + pos.get(b.target()));
                    case TableSwitchInstruction t -> out.add("TABLESWITCH " + t.lowValue() + ".." + t.highValue() + " d=L" + pos.get(t.defaultTarget())
                        + " " + t.cases().stream().map(c -> c.caseValue() + ":L" + pos.get(c.target())).toList());
                    case LookupSwitchInstruction t -> out.add("LOOKUPSWITCH d=L" + pos.get(t.defaultTarget())
                        + " " + t.cases().stream().map(c -> c.caseValue() + ":L" + pos.get(c.target())).toList());
                    case ExceptionCatch c -> out.add("CATCH L" + pos.get(c.tryStart()) + "-L" + pos.get(c.tryEnd()) + " -> L" + pos.get(c.handler())
                        + " " + c.catchType().map(t -> t.asInternalName()).orElse("*"));
                    case Instruction i -> out.add(i.toString().replace("OP=LDC_W,", "OP=LDC,"));   // ldc vs ldc_w depends only on the constant pool index
                    default -> { }
                }
            }
            return out;
        });
    }

    static boolean methods;

    static boolean has(ClassModel c, String[] s) {
        if (s[0].equals("F")) return c.fields().stream().anyMatch(f -> f.fieldName().equalsString(s[1]) && f.fieldType().equalsString(s[2]));
        return c.methods().stream().anyMatch(m -> m.methodName().equalsString(s[1]) && m.methodType().equalsString(s[2]));
    }

    static boolean hasAcc(ClassModel c, String[] s) {
        if (s[0].equals("A")) return c.fields().stream().anyMatch(f -> f.fieldName().equalsString(s[1]));
        return c.methods().stream().anyMatch(m -> m.methodName().equalsString(s[1]));
    }
}
