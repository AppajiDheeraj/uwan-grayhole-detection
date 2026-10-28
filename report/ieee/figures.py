"""Figures for the IEEE-style report (report/ieee/main.tex) that results/figs does not already have.

Run from the repo root:
    uv run --with matplotlib --with numpy python report/ieee/figures.py
Reads results/network.csv and results/compare_runs.csv; writes report/ieee/figs/
  topology.pdf     test topology 201 with its three grayholes (the busiest relays)
  attack.pdf       delivery lost to the attack for 1, 2 and 3 grayholes (live network, no defence)
  detected.pdf     grayholes confirmed by ours for 1, 2 and 3 grayholes (recorded evidence)
  detect_time.pdf  time until every grayhole is confirmed, same runs
and copies the five result graphs from results/figs.
"""
import csv
import math
import shutil
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

R = Path("results")
OUT = Path("report/ieee/figs")
OUT.mkdir(parents=True, exist_ok=True)
plt.rcParams.update({"font.size": 9, "axes.spines.top": False, "axes.spines.right": False})

# Topology(20, 201) from unet/Topology.groovy: address -> (x, y, next hop). Node 1 is the sink.
TOPO = {1: (0.0, 0.0, None), 2: (2585.0, 632.8, 10), 3: (1438.2, 1661.4, 12), 4: (2412.3, 1266.9, 10),
        5: (3802.3, 1528.8, 4), 6: (223.1, 159.7, 1), 7: (893.8, -524.2, 1), 8: (2360.4, -317.8, 17),
        9: (1391.8, 1473.4, 12), 10: (1384.4, 429.2, 1), 11: (2130.2, 26.0, 10), 12: (1170.9, 461.6, 1),
        13: (1916.4, -1546.6, 17), 14: (791.2, -475.0, 1), 15: (2263.8, 208.0, 10), 16: (875.7, 1494.5, 12),
        17: (1314.3, -432.3, 1), 18: (3963.6, 1378.5, 5), 19: (2837.2, -565.9, 8), 20: (766.1, 1917.7, 16),
        21: (430.2, -1011.8, 1)}
GRAYHOLES = {10, 12, 17}
RELAYS = {nh for _, _, nh in TOPO.values() if nh not in (None, 1)}


def read(name):
    with open(R / name) as f:
        return list(csv.DictReader(f))


def bars(path, labels, values, ylabel, fmt):
    """One orange series with the value printed above each bar (style of the reference paper)."""
    fig, ax = plt.subplots(figsize=(3.4, 2.4))
    b = ax.bar(labels, values, color="orange", width=0.55)
    ax.bar_label(b, labels=[fmt.format(v) for v in values], fontsize=8)
    ax.set_xlabel("Number of grayholes")
    ax.set_ylabel(ylabel)
    ax.set_ylim(0, max(values) * 1.2)
    fig.tight_layout()
    fig.savefig(OUT / path)
    plt.close(fig)


def topology():
    # display only: nodes 7 and 14 are 103 m apart, draw them apart so both labels stay readable
    TOPO[7] = (TOPO[7][0] + 90, TOPO[7][1] - 90, TOPO[7][2])
    TOPO[14] = (TOPO[14][0] - 90, TOPO[14][1] + 90, TOPO[14][2])
    fig, ax = plt.subplots(figsize=(3.5, 3.3))
    for a, (x, y, nh) in TOPO.items():
        for b, (u, v, _) in TOPO.items():   # links in range: every neighbour can overhear
            if a < b and math.hypot(x - u, y - v) <= 1500:
                ax.plot([x, u], [y, v], color="0.85", lw=0.5, zorder=1)
    for a, (x, y, nh) in TOPO.items():
        if nh is not None:
            u, v, _ = TOPO[nh]
            ax.annotate("", (u, v), (x, y), zorder=2,
                        arrowprops=dict(arrowstyle="-|>", lw=0.9, shrinkA=4, shrinkB=4,
                                        color="red" if nh in GRAYHOLES else "black"))
    for a, (x, y, _) in TOPO.items():
        c = "#2ecc71" if a == 1 else "#e74c3c" if a in GRAYHOLES else "#f1c40f" if a in RELAYS else "#5dade2"
        ax.scatter(x, y, s=75, color=c, edgecolor="black", lw=0.6, zorder=3)
        ax.text(x, y, str(a), ha="center", va="center", fontsize=5, zorder=4)
    ax.plot([2600, 4100], [-1900, -1900], color="black", lw=1)
    ax.text(3350, -1830, "1500 m (range)", ha="center", fontsize=6)
    ax.set_aspect("equal")
    ax.axis("off")
    fig.tight_layout()
    fig.savefig(OUT / "topology.pdf")
    plt.close(fig)


def per_seed_mean(rows, value):
    by = defaultdict(list)
    for r in rows:
        by[r["seed"]].append(value(r))
    xs = [sum(v) / len(v) for v in by.values()]
    return sum(xs) / len(xs)


topology()

# Fig. attack: percentage points of honest delivery lost when the grayholes act, no defence (load 0.005)
net = [r for r in read("network.csv") if r["load"] == "0.005"]
pdr = {(r["grayholes"], r["seed"], r["case"]): float(r["pdr"]) for r in net}
lost = []
for k in ["1", "2", "3"]:
    seeds = {r["seed"] for r in net if r["grayholes"] == k}
    lost.append(100 * sum(pdr[k, s, "noattack"] - pdr[k, s, "nodefense"] for s in seeds) / len(seeds))
bars("attack.pdf", ["1", "2", "3"], lost, "Delivery ratio lost (absolute %)", "{:.1f}")

# Fig. detected and detection time: ours with its own rule, one 30% grayhole (load 0.005), two, three
runs = [r for r in read("compare_runs.csv") if r["method"] == "ours" and r["rule"] == "own"]
groups = [[r for r in runs if r["scenario"] == "load" and r["load"] == "0.005" and r["n_attackers"] == "1"],
          [r for r in runs if r["scenario"] == "two"], [r for r in runs if r["scenario"] == "three"]]
detected = [100 * sum(int(r["caught"]) for r in g) / sum(int(r["n_attackers"]) for r in g) for g in groups]
bars("detected.pdf", ["1", "2", "3"], detected, "Grayholes confirmed (%)", "{:.0f}")
times = [per_seed_mean([r for r in g if r["detect_min"] != "NA"], lambda r: float(r["detect_min"])) for g in groups]
bars("detect_time.pdf", ["1", "2", "3"], times, "Detected after (min)", "{:.0f}")
print("attack", lost, "detected", detected, "minutes", times)

for f in (R / "figs").glob("fig*.png"):
    shutil.copy(f, OUT / f.name)
