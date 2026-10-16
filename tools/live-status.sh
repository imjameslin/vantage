#!/bin/zsh
# Live view of what the current agent round is doing: running test, latest results, files being edited, commits.
# Usage: zsh tools/live-status.sh [worktree]   (default: newest agent worktree)
ROOT="${0:A:h:h}"
W="${1:-$(ls -td "$ROOT"/.claude/worktrees/agent-* | head -1)}"
cd "$W" || exit 1
while true; do
  out=$(
  echo "=== LIVE STATUS  $(date +%H:%M:%S)   round worktree: ${W:t}"
  echo
  echo "--- Running now"
  run=$(pgrep -fl 'java .*-Dbench' | grep -o 'bench.label=[^ ]*' | head -1)
  if [[ -n $run ]]; then echo "  Minecraft test running: ${run#bench.label=}"; else
    b=$(pgrep -fl 'javac|clang|build-client' | head -1); [[ -n $b ]] && echo "  Building the game jar" || echo "  Agent is coding / thinking (no test running)"; fi
  echo
  echo "--- Latest test results (newest first)"
  for f in $(ls -t results/raw 2>/dev/null | grep 'json$' | grep -v startup | head -8); do
    python3 - "$f" <<'P' 2>/dev/null
import json,sys,os,time;f=sys.argv[1];p='results/raw/'+f;d=json.load(open(p));a=d.get('abab')
t=time.strftime('%H:%M',time.localtime(os.path.getmtime(p)))
if a and 'arms' in a:
  tg=a['toggles'][0]; e=a.get('effects',{}).get('median',{})
  print(f"  {t}  {f[:-5]:28s} A/B test {tg['field'].split('#')[1]}: {tg['a']} -> {round(a['arms']['A']['medianFps'])} FPS, {tg['b']} -> {round(a['arms']['B']['medianFps'])} FPS")
else: print(f"  {t}  {f[:-5]:28s} {round(d.get('medianFps',0))} FPS (1% low {round(d.get('p1LowFps',0))})")
P
  done
  echo
  echo "--- Files being changed (not yet saved in a commit)"
  git status --short | grep -v '^??' | sed 's/^/  /' | head -10
  echo
  echo "--- Recent commits"
  git log --since=8.hours --format='  %ad  %s' --date=format:%H:%M | grep -v Merge | head -5 | cut -c1-150
  )
  clear; print -r -- "$out"
  sleep 5
done
