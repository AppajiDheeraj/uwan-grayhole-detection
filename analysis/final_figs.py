"""Results graphs for the final report and slides.

Run from the repo root:
    uv run --with matplotlib --with numpy python analysis/final_figs.py
Reads results/isolate_runs/network.csv and results/suspect_scores.csv.
Writes:
  blacklist.pdf      (REPORT and SLIDES) delivery ratio and honest relays blacklisted when the sink blacklists suspects
  drop_estimate.pdf  (REPORT) estimated against true drop rate of one grayhole
A topology is the independent unit; error bars are 95% t-intervals over the 10 test topologies.
"""
import csv
import math
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

R = Path("results")
REPORT, SLIDES = Path("report/final/figs"), Path("report/slides/figs")
NAME = {"noattack": "No attack", "nodefense": "Attack, no defence", "fixed": "Fixed watchdog", "cad": "CAD-style",
        "ours": "Ours (proposed)"}
COLOR = {"noattack": "#7f8c8d", "nodefense": "#8e44ad", "fixed": "#c0392b", "cad": "#e67e22", "ours": "#1e8449"}
DETECTORS = ["fixed", "cad", "ours"]
T975_9 = 2.262  # t quantile, 9 degrees of freedom (10 topologies)

plt.rcParams.update({"font.size": 10, "axes.grid": True, "grid.alpha": 0.3, "axes.axisbelow": True,
                     "axes.spines.top": False, "axes.spines.right": False, "axes.titlesize": 11,
                     "legend.frameon": True, "legend.framealpha": 0.95})


def read(name):
    with open(R / name) as f:
        return list(csv.DictReader(f))


def mean_ci(xs):
    xs = np.asarray(xs, dtype=float)
    return xs.mean(), T975_9 * xs.std(ddof=1) / math.sqrt(len(xs))


def save(fig, name, *dirs):
    for d in dirs:
        fig.savefig(d / name, bbox_inches="tight")
    plt.close(fig)


# ---------------------------------------------------------------- blacklist.pdf
rows = read("isolate_runs/network.csv")
SET = sorted({(r["grayholes"], r["load"]) for r in rows})
seeds = sorted({r["seed"] for r in rows})
run = {(r["grayholes"], r["load"], r["case"], r["seed"]): r for r in rows}


def per_topology(case, value):
    """One value per topology: the mean over the 4 attack settings."""
    return [np.mean([value(run[(*g, case, s)]) for g in SET]) for s in seeds]


pdr = lambda r: 100 * float(r["pdr"])
honest = lambda r: float(r["honest_suspects"])
CASES = ["noattack", "nodefense", "cad", "fixed", "ours"]   # fixed next to ours, so the gap between them is easy to read
delivery = {c: mean_ci(per_topology(c, pdr)) for c in CASES}
gain, gain_ci = mean_ci(np.array(per_topology("ours", pdr)) - np.array(per_topology("fixed", pdr)))

fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(11, 4.3), gridspec_kw={"width_ratios": [1.45, 1]})
lo, hi = delivery["fixed"][0], delivery["ours"][0]
x_fixed, x_ours = CASES.index("fixed"), CASES.index("ours")
ax1.fill_between([x_fixed - 0.31, x_ours + 0.31], lo, hi, color=COLOR["ours"], alpha=0.10, lw=0)
for y in (lo, hi):
    ax1.plot([x_fixed - 0.31, x_ours + 0.31], [y, y], color="black", ls=":", lw=1)
ax1.text((x_fixed + x_ours) / 2, 68, f"Ours minus fixed watchdog:\n+{gain:.1f}% absolute (±{gain_ci:.1f}%)",
         ha="center", va="center", fontsize=9.5, fontweight="bold", color="#145a32",
         bbox=dict(boxstyle="round,pad=0.35", facecolor="white", edgecolor=COLOR["ours"]))
for i, c in enumerate(CASES):
    y, e = delivery[c]
    ax1.bar(i, y, 0.62, yerr=e, capsize=4, color=COLOR[c], edgecolor="black", linewidth=0.6, label=NAME[c])
    ax1.text(i, 2, f"{y:.1f}%", ha="center", va="bottom", fontsize=10, fontweight="bold", color="white")
ax1.set_xticks(range(len(CASES)), ["No\nattack", "Attack,\nno defence", "CAD-style", "Fixed\nwatchdog", "Ours"])
ax1.set(title="(a) Delivery ratio of honest data packets", ylabel="Delivery ratio (%)", ylim=(0, 80))
ax1.grid(axis="x", visible=False)

for i, c in enumerate(DETECTORS):
    y, e = mean_ci(per_topology(c, honest))
    ax2.bar(i, y, 0.6, yerr=e, capsize=4, color=COLOR[c], edgecolor="black", linewidth=0.6)
    ax2.text(i, y + e + 0.15, f"{y:.2f}", ha="center", va="bottom", fontsize=10, fontweight="bold")
ax2.set_xticks(range(len(DETECTORS)), ["Fixed\nwatchdog", "CAD-style", "Ours"])
ax2.set(title="(b) Honest relays blacklisted per run", ylabel="Honest relays blacklisted per run", ylim=(0, 7))
ax2.grid(axis="x", visible=False)
fig.suptitle("Blacklisting response: 12 h live network, 4 attack settings, 10 test topologies", fontsize=12, fontweight="bold")
fig.tight_layout()
save(fig, "blacklist.pdf", REPORT, SLIDES)
print(f"blacklist: ours - fixed = +{gain:.1f} ± {gain_ci:.1f}%")

# ---------------------------------------------------------------- drop_estimate.pdf
scores = [r for r in read("suspect_scores.csv") if r["scenario"] == "drop" and r["attacker"] == "1"]
drops = sorted({float(r["drop"]) for r in scores})
fig, ax = plt.subplots(figsize=(6.2, 4.3))
ax.plot([0, 100], [0, 100], color="grey", ls=":", lw=1.5, label="Perfect estimate")
for m, marker in [("cad", "^"), ("ours", "o")]:
    ys, es = zip(*[mean_ci([float(r["score"]) for r in scores if r["method"] == m and float(r["drop"]) == d]) for d in drops])
    ax.errorbar([100 * d for d in drops], ys, yerr=es, color=COLOR[m], marker=marker, ms=7, lw=2, capsize=4, label=NAME[m])
ax.set(title="Estimated against true drop rate of one grayhole", xlabel="True drop rate of the grayhole (%)",
       ylabel="Estimated drop rate (%)", xlim=(0, 105), ylim=(-3, 105))
ax.legend(loc="upper left", fontsize=9)
fig.tight_layout()
save(fig, "drop_estimate.pdf", REPORT)
print(f"wrote blacklist.pdf into {REPORT} and {SLIDES}; drop_estimate.pdf into {REPORT}")
