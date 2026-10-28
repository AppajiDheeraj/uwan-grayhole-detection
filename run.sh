#!/bin/bash
# One pipeline for the whole project. Run from the repository root.
#
#   ./run.sh demo      lab demo, ~3 min: one network, 3 grayholes, all five cases, ranked suspect list
#   ./run.sh           full results from the recorded evidence: test, compare, live network, graphs
#   ./run.sh record    re-record the evidence on calibration and test networks first (several hours)
#   ./run.sh dashboard rebuild dashboard/ from results/ on demand (not part of the pipeline) and open it
#
# Steps:
#   1. record   unet/record.groovy    simulate the networks, store every monitor's counters
#   2. compare  unet/compare.groovy   replay the sink's decisions for every method on the same
#                                     counters; thresholds chosen on calibration networks only
#   3. network  unet/network.groovy   live network: reports over the channel, detection,
#                                     confirmation, traffic routed around suspects, delivery measured
#   4. graphs   analysis/plots.py     five graphs + results/summary.md
set -eo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
UNET="$ROOT/tools/unet-7.0.0/bin/unet"
R="$ROOT/results"
JOBS=${JOBS:-4}   # more parallel simulators make the Mac swap and run slower overall
mkdir -p "$R/logs"

# Run one UnetStack script with environment settings; UnetStack can exit 0 after a Groovy
# error, so require the script's completion marker.   usage: sim <script> <marker> <log> [VAR=value ...]
sim() {
  local script=$1 marker=$2 log=$3
  shift 3
  (cd "$ROOT/unet" && env "$@" "$UNET" "$ROOT/unet/$script.groovy" > "$R/logs/$log.log" 2>&1) || true   # the marker check below reports failures
  grep -qx "$marker" "$R/logs/$log.log" || { echo "FAILED $script ($log): see results/logs/$log.log"; return 1; }
  echo "done $log"
}
export -f sim
export ROOT UNET R

# Concatenate CSV parts that share a header.   usage: merge <out> <part files...>
merge() {
  local out=$1
  shift
  head -1 "$1" > "$out"
  for f in "$@"; do tail -n +2 "$f" >> "$out"; done
}

record() {
  echo "== 1. record evidence (calibration 101-106, test 201-210)"
  { for s in 101 102 103 104 105 106; do echo "record RECORD_COMPLETE record_c$s SEEDS=$s SCENARIOS=load,two,three,hotspot PART=_c$s"; done
    for s in $(seq 201 210); do echo "record RECORD_COMPLETE record_t$s SEEDS=$s SCENARIOS=load,drop,two,three,near,sparse,hotspot PART=_t$s"; done
    for s in 201 202 203 204 205; do echo "record RECORD_COMPLETE record_w$s SEEDS=$s SCENARIOS=large PART=_w$s"; done
  } | xargs -P "$JOBS" -L 1 bash -c 'sim "$@"' _
  merge "$R/evidence_cal.csv" "$R"/evidence_c1??.csv
  merge "$R/evidence.csv" "$R"/evidence_t2??.csv "$R"/evidence_w2??.csv
}

network() {
  echo "== 3. live network (topologies 201-210; 1 grayhole at two loads, 2 and 3 grayholes)"
  for s in $(seq 201 210); do
    for job in "1 0.005" "1 0.01" "2 0.005" "3 0.005"; do
      set -- $job
      part="_g$1_$2_$s"
      grep -qx NETWORK_COMPLETE "$R/logs/network$part.log" 2>/dev/null && continue   # resume
      echo "network NETWORK_COMPLETE network$part SEEDS=$s GRAYHOLES=$1 LOADS=$2 PART=$part"
    done
  done | xargs -P "$JOBS" -L 1 bash -c 'sim "$@"' _
  merge "$R/network.csv" "$R"/network_g*_2??.csv
  merge "$R/network_rounds.csv" "$R"/network_rounds_g*_2??.csv
}

case "${1:-all}" in
  demo)
    sim test_detectors PASS test_detectors
    sim compare COMPARE_COMPLETE compare
    sim network NETWORK_COMPLETE network_demo SEEDS=201 GRAYHOLES=3 PART=_demo
    grep -aE "^topology|^ +[0-9]\." "$R/logs/network_demo.log"
    ;;
  record|all)
    sim test_detectors PASS test_detectors
    [ "$1" = record ] && record
    echo "== 2. compare detectors on the recorded evidence"
    sim compare COMPARE_COMPLETE compare
    cat "$R/suspect_list.txt"
    network
    echo "== 4. graphs"
    uv run --with matplotlib --with numpy python analysis/plots.py
    echo "PIPELINE COMPLETE — results/summary.md, results/figs/"
    ;;
  dashboard)
    sim dashboard DASHBOARD_COMPLETE dashboard
    open dashboard/index.html 2>/dev/null || echo "open dashboard/index.html in a browser"
    ;;
  *) echo "usage: ./run.sh [demo|record|dashboard]"; exit 1 ;;
esac
