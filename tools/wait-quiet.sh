#!/bin/zsh
# Block until the 1-minute load average is below a threshold (default 3.0) or a timeout (default 600 s)
# elapses, so benchmarks don't run while builds/tests/agents compete for the CPU or GPU.
LIMIT=${1:-3.0}; TIMEOUT=${2:-600}; waited=0
while true; do
  load=$(sysctl -n vm.loadavg | awk '{print $2}')
  if (( load < LIMIT )); then echo "quiet: load $load"; exit 0; fi
  if (( waited >= TIMEOUT )); then echo "WARNING: load still $load after ${TIMEOUT}s; results may be noisy"; exit 1; fi
  sleep 10; waited=$((waited + 10))
done
