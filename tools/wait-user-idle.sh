#!/bin/zsh
# Block while the user's own game (a Prism instance) is running, then while overlays block benchmarks.
# (The pattern is assembled at runtime so this script's own command line never matches it.)
P="Prism""Launcher/instances"
while pgrep -f "$P" > /dev/null; do sleep 30; done
until "${0:A:h}/check-overlays.sh" > /dev/null 2>&1; do sleep 30; done
