#!/bin/zsh
# Stage-2 W4: JDK 25 AOT cache for the user's Prism Launcher instance (docs/metal/CONTRACT.md 16.S2-W4, results/STAGE2-R1.md).
# Install (the lead does it, with the user's OK; nothing here touches the instance by itself):
#   mkdir -p "<instance>/aot" && cp tools/prism/aot-wrap.sh "<instance>/aot/" && chmod +x "<instance>/aot/aot-wrap.sh"
#   Prism: instance > Settings > Custom commands > Wrapper command:  "<instance>/aot/aot-wrap.sh"
#   (instance.cfg: OverrideCommands=true, WrapperCommand=<that path>). Remove the wrapper command to uninstall.
#
# Prism runs `<wrapper> <java> <jvm args...> -cp <classpath> org.prismlauncher.EntryPoint` and feeds the launch script on
# stdin. This wrapper:
#  1. copies Prism's NewLaunch.jar out of its AppTranslocation path (a new random directory every Prism session, which would
#     invalidate the cache) to <dir>/NewLaunch-<sha1>.jar and rewrites that classpath entry;
#  2. keys the cache by the java binary (path, size, mtime), every JVM flag except -D / -Xdock (the JVM itself checks the
#     flags it cares about; a changed heap or GC flag must not reuse a cache) and every classpath entry (path, size, mtime:
#     an updated client jar = a new key);
#  3. cache present: adds -XX:AOTCache=<key>.aot (AOTMode=auto: the JVM silently runs without it if it cannot map it);
#     cache missing: this session trains it (-XX:AOTCacheOutput, JDK 25 one-step workflow: classes are recorded while the
#     game runs, the cache is written when the game quits normally, which takes a few seconds after the window closes);
#  4. keeps the newest 3 caches, logs every decision to <dir>/aot-wrap.log and the JVM's AOT log to <dir>/aot-jvm.log.
# Under ZGC (Mojang's flags) the cache holds class metadata only: same bytecode, same behaviour; the saving is class
# loading / linking at startup (measured: results/STAGE2-R1.md). `touch <dir>/disabled` turns it off without Prism.
# Env for tests: AOT_WRAP_DIR (default: this script's directory).
JAVA=$1; shift
DIR=${AOT_WRAP_DIR:-${0:A:h}}
LOG="$DIR/aot-wrap.log"
log() { print -r -- "$(date '+%Y-%m-%dT%H:%M:%S') $*" >> "$LOG"; }
if [[ -e "$DIR/disabled" || ! -x "$JAVA" ]]; then
  log "disabled or no java ($JAVA): running unchanged"
  exec "$JAVA" "$@"
fi
args=("$@")
# The classpath: the argument after -cp / -classpath / --class-path.
cpi=0
for (( i = 1; i <= $#args; i++ )); do
  if [[ $args[i] == (-cp|-classpath|--class-path) ]]; then cpi=$((i + 1)); break; fi
done
if (( cpi == 0 || cpi > $#args )); then
  log "no classpath argument: running unchanged"
  exec "$JAVA" "$@"
fi
entries=("${(@s/:/)args[cpi]}")
for (( i = 1; i <= $#entries; i++ )); do
  e=$entries[i]
  if [[ $e == */AppTranslocation/* && -f $e ]]; then
    sum=$(shasum -a 1 "$e" | cut -c1-12)
    stable="$DIR/${e:t:r}-$sum.jar"
    if [[ ! -f $stable ]]; then cp -p "$e" "$stable.tmp" && mv "$stable.tmp" "$stable"; fi
    entries[i]=$stable
  fi
done
args[cpi]=${(j/:/)entries}
# Key inputs: java binary, JVM flags (not -D, -Xdock, the classpath value or what follows the main class), classpath entries.
keyin=("java $JAVA $(stat -f '%z %m' "$JAVA" 2>/dev/null)")
for (( i = 1; i < cpi - 1; i++ )); do
  [[ $args[i] == -D* || $args[i] == -Xdock:* ]] || keyin+=("flag $args[i]")
done
for e in $entries; do keyin+=("cp $e $(stat -f '%z %m' "$e" 2>/dev/null)"); done
key=$(print -rl -- $keyin | shasum -a 1 | cut -c1-16)
cache="$DIR/$key.aot"
head=("${(@)args[1,cpi-2]}"); tail=("${(@)args[cpi-1,-1]}")
if [[ -f $cache ]]; then
  log "key $key: using $cache"
  touch "$cache"   # newest-used first for pruning
  exec "$JAVA" "${head[@]}" "-XX:AOTCache=$cache" "-Xlog:aot=info:file=$DIR/aot-jvm.log" "${tail[@]}"
fi
log "key $key: no cache, this session trains it"
print -rl -- $keyin > "$DIR/$key.inputs"
tmp="$DIR/$key.aot.tmp"
rm -f "$tmp"
# Run as a child (not exec) to rename the finished cache; forward Prism's kill to the game. `<&0`: a background job
# would otherwise read /dev/null, and Prism sends the launch script on stdin.
"$JAVA" "${head[@]}" "-XX:AOTCacheOutput=$tmp" "-Xlog:aot=warning:file=$DIR/aot-jvm.log" "${tail[@]}" <&0 &
pid=$!
trap 'kill -TERM $pid 2>/dev/null' TERM INT HUP
wait $pid; rc=$?
while kill -0 $pid 2>/dev/null; do wait $pid; rc=$?; done
if [[ -s $tmp ]]; then
  mv "$tmp" "$cache"
  log "key $key: trained ($(( $(stat -f %z "$cache") >> 20 )) MB), game exit $rc"
else
  rm -f "$tmp"
  log "key $key: no cache written (game exit $rc)"
fi
# Keep the newest 3 caches.
old=("$DIR"/*.aot(Nom[4,-1]))
for f in $old; do rm -f "$f" "${f:r}.inputs"; log "pruned ${f:t}"; done
exit $rc
