#!/bin/zsh
# Headless test of tools/prism/aot-wrap.sh (stage-2 W4) with the real JDK 25 runtime (the same Microsoft 25.0.1 build as the
# Prism instance's), no game: a small program stands in for Prism's NewLaunch.jar, placed in a new fake AppTranslocation
# directory per run, reading the "launch script" from stdin and printing whether the AOT cache is mapped.
#  run 1: no cache -> trains (cache written at exit), stdin reaches the program, Prism-style flags (-Xdock:name with spaces) kept
#  run 2: other translocation path -> same key, cache mapped
#  run 3: a classpath jar changed (mtime) -> new key, trains again
#  run 4: <dir>/disabled -> command unchanged, nothing mapped
# usage: tools/prism/aot-wrap-test.sh   (exit 0 = pass)
ROOT="${0:A:h:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
W="$ROOT/build/aot-wrap-test"; rm -rf "$W"; mkdir -p "$W/src" "$W/aot dir" "$W/classes"
fail=0; ok() { echo "ok:   $1"; }; bad() { echo "FAIL: $1"; fail=1; }
cat > "$W/src/Main.java" <<'EOF'
public class Main {
    public static void main(String[] a) throws Exception {
        String line = new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
        // load a few hundred JDK classes, as a real launch would
        java.util.List<Object> l = new java.util.ArrayList<>();
        l.add(java.time.ZonedDateTime.now()); l.add(java.util.regex.Pattern.compile("a+b")); l.add(new java.util.concurrent.ConcurrentSkipListMap<>());
        System.out.println("stdin=" + line + " mapped=" + jdk.internal.misc.CDS.isUsingArchive() + " n=" + l.size());
        System.exit(0);
    }
}
EOF
"$JH/bin/javac" --add-exports java.base/jdk.internal.misc=ALL-UNNAMED -d "$W/classes" "$W/src/Main.java" || { echo "javac failed"; exit 1; }
"$JH/bin/jar" cf "$W/NewLaunch.jar" -C "$W/classes" . && "$JH/bin/jar" cf "$W/lib.jar" -C "$W/src" Main.java
run() {  # run <n>: new translocation dir, wrapper with Prism-like args; prints the program's output line
  local t="$W/T/AppTranslocation/$RANDOM$RANDOM/d/Prism Launcher.app/Contents/MacOS/jars"; mkdir -p "$t"; cp -p "$W/NewLaunch.jar" "$t/"
  print "launch-script-$1" | AOT_WRAP_DIR="$W/aot dir" zsh "$ROOT/tools/prism/aot-wrap.sh" "$JH/bin/java" -XX:+UseZGC -XX:+UseCompactObjectHeaders \
    --add-exports java.base/jdk.internal.misc=ALL-UNNAMED -Xdock:name="Prism Launcher: 26.3 Metal" -XstartOnFirstThread -Xms64m -Xmx256m \
    -Duser.language=en -cp "$t/NewLaunch.jar:$W/lib.jar" Main 2>&1 | grep "stdin="
}
o=$(run 1); [[ $o == "stdin=launch-script-1 mapped=false n=3" ]] && ok "run 1 trains, stdin passed ($o)" || bad "run 1: $o"
n=$(ls "$W/aot dir"/*.aot 2>/dev/null | wc -l | tr -d ' '); (( n == 1 )) && ok "run 1 wrote one cache" || bad "run 1 caches: $n"
o=$(run 2); [[ $o == "stdin=launch-script-2 mapped=true n=3" ]] && ok "run 2 maps the cache from another translocation path ($o)" || bad "run 2: $o"
grep -q "using" "$W/aot dir/aot-wrap.log" && ok "log says using" || bad "log: no 'using'"
touch -t 202001010000 "$W/lib.jar"
o=$(run 3); [[ $o == *"mapped=false"* ]] && ok "run 3 (changed jar) does not map a stale cache ($o)" || bad "run 3: $o"
n=$(ls "$W/aot dir"/*.aot 2>/dev/null | wc -l | tr -d ' '); (( n == 2 )) && ok "run 3 trained a second cache" || bad "run 3 caches: $n"
touch "$W/aot dir/disabled"
o=$(run 4); [[ $o == *"mapped=false"* ]] && ok "run 4 disabled: unchanged ($o)" || bad "run 4: $o"
grep -q "disabled" "$W/aot dir/aot-wrap.log" && ok "log says disabled" || bad "log: no 'disabled'"
echo "--- aot-wrap.log"; cat "$W/aot dir/aot-wrap.log"
(( fail == 0 )) && echo "aot-wrap-test: PASS" || echo "aot-wrap-test: FAIL"
exit $fail
