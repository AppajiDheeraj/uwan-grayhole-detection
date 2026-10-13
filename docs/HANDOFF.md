# Handoff — 13 October 2026

## State

The project is one pipeline: `./run.sh` (see README). Earlier "phase" scripts were removed; their outputs are in `results/archive_before_pipeline/`, and full backups of the code and results before these changes are on the Desktop (`CN-Project-code-before-claude-2026-10-12.zip`, `CN-Project-results-before-claude-2026-10-12.zip`, plus the v1/v2/v3 checkpoint ZIPs).

| Step | Script | Output |
|---|---|---|
| record | `unet/record.groovy` | `results/evidence*.csv` (recorded once; re-record with `./run.sh record`) |
| compare | `unet/compare.groovy` | `results/thresholds.csv`, `compare_runs.csv`, `suspect_scores.csv`, `suspect_list.txt`, `ablation.csv` |
| live network | `unet/network.groovy` | `results/network.csv`, `network_rounds.csv` |
| graphs | `analysis/plots.py` | `results/figs/fig1..fig5.png`, `results/summary.md` |

## Design decisions made on 12–13 October (each checked on calibration networks 101–106 first)

1. **Bundled reports**: one frame per monitor per round (up to 6 relays); halved report traffic.
2. **Latest report kept** at the sink: lost reports only delay evidence (removed flag/clear flapping).
3. **Two-round confirmation** (`Detectors.confirm`, all methods): suspect after two flagged rounds in a row; stays a suspect.
4. **Baseline cleaning is now part of ours** (`Detectors.peel`): relays with ≥ 10% estimated drop and confirmed suspects leave the "normal loss" median. Several grayholes otherwise inflate the spread factor and hide every grayhole although they rank first. Calibration replay: 3 grayholes 4/18 → 13/18, 2 grayholes 9/12 → 12/12.
5. **Area average vs median**: the weighted average as area baseline lost recall on calibration (96% → 75%); the median stays; the weighted averaging is in combining monitors (METHOD.md §5).
6. **Comparison rules**: own rule (fixed miss > 30%, CAD z > 2, ours tuned alpha) for the live network; same 5% budget for the fair detection contest. Response = route around suspects (avoid) for all methods. Full isolation was tried first: it disconnected every node behind a correctly caught grayhole that was their only path (delivery 4–8% on topology 205); those runs are in `results/isolate_runs/`. The no-attack and no-defence runs never consult the response, so they were reused from that run rather than simulated twice.

## Known limits to state

Static topologies, known positions and channel curve, truthful monitors, free route updates. CAD-style is an adaptation. In the 3-grayhole case ours ties CAD-style on detection at the same budget; ours accuses more honest relays than the fixed watchdog at the same budget in hotspot and 40-node networks (see `results/summary.md`).
