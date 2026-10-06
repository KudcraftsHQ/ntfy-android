#!/usr/bin/env bash
# kudcrafts: synthesizes the three notification sound classes (spec section 7.5, decision Q5) into
# app/src/main/res/raw/kc_{default,alert,urgent}.ogg. Placeholder tones until designed sounds exist.
# Needs sox (CI: apt-get install sox libsox-fmt-all). The OGG step falls back to ffmpeg when this sox
# build has no Vorbis handler. Outputs are generated, not committed (see .gitignore).
set -euo pipefail

SOX="${SOX:-sox}"
OUT="${1:-$(cd "$(dirname "$0")/.." && pwd)/app/src/main/res/raw}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

tone() { # tone <file> <seconds> <synth args...>
  local file="$1" secs="$2"; shift 2
  "$SOX" -n -r 48000 -c 1 -b 16 "$WORK/$file" synth "$secs" "$@" gain -8 fade q 0.005 "$secs" 0.04
}
silence() { "$SOX" -n -r 48000 -c 1 -b 16 "$WORK/$1" trim 0 "$2"; }

# default: soft two-note chime, rising (~0.45 s)
tone d1.wav 0.16 sine 880
tone d2.wav 0.26 sine 1318.5
"$SOX" "$WORK/d1.wav" "$WORK/d2.wav" "$WORK/default.wav" gain -n -6

# alert: three quick bright beeps (~0.6 s)
tone a.wav 0.11 square 1046.5
silence gap.wav 0.07
"$SOX" "$WORK/a.wav" "$WORK/gap.wav" "$WORK/a.wav" "$WORK/gap.wav" "$WORK/a.wav" "$WORK/alert.wav" lowpass 4000 gain -n -4

# urgent: three rising sweeps (~1.5 s), meant to cut through
tone u.wav 0.42 sawtooth 600-1400
silence ugap.wav 0.06
"$SOX" "$WORK/u.wav" "$WORK/ugap.wav" "$WORK/u.wav" "$WORK/ugap.wav" "$WORK/u.wav" "$WORK/urgent.wav" lowpass 5000 gain -n -3

for name in default alert urgent; do
  dest="$OUT/kc_$name.ogg"
  if ! "$SOX" "$WORK/$name.wav" -C 3 "$dest" 2>/dev/null; then
    ffmpeg -loglevel error -y -i "$WORK/$name.wav" -c:a libvorbis -q:a 3 "$dest"
  fi
  size=$(wc -c < "$dest")
  if [ "$size" -gt 102400 ]; then
    echo "kc_$name.ogg is $size bytes (> 100 KB)" >&2
    exit 1
  fi
  echo "$dest ($size bytes)"
done
