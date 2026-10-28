"""Seven-page TA results PDF: one or two graphs per page, with the key tables.

Run from the repo root:
    uv run --with matplotlib --with numpy python analysis/ta_report.py
Reads the same results files as analysis/plots.py; writes report/TA_Results_Team9.pdf.
Uncertainty: a topology is the independent unit; error bars are 95% t-intervals over topologies.
"""
import csv
import math
import textwrap
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.backends.backend_pdf import PdfPages

R = Path("results")
OUT = Path("report/TA_Results_Team9.pdf")
PAGES = 7

NAME = {"noattack": "No attack", "nodefense": "Attack, no defence", "fixed": "Fixed watchdog",
        "cad": "CAD-style", "ours": "Ours (proposed)"}
SHORT = {"noattack": "No\nattack", "nodefense": "No\ndefence", "fixed": "Fixed\nwatchdog", "cad": "CAD-style",
         "ours": "Ours"}
COLOR = {"noattack": "#7f8c8d", "nodefense": "#8e44ad", "fixed": "#c0392b", "cad": "#e67e22", "ours": "#1e8449"}
DEFENCES = ["nodefense", "fixed", "cad", "ours"]
T975 = {1: 12.71, 2: 4.30, 3: 3.18, 4: 2.78, 5: 2.57, 6: 2.45, 7: 2.36, 8: 2.31, 9: 2.26, 10: 2.23}

plt.rcParams.update({"font.size": 10, "axes.grid": True, "grid.alpha": 0.3, "axes.axisbelow": True,
                     "axes.spines.top": False, "axes.spines.right": False, "axes.titlesize": 11.5,
                     "axes.titleweight": "bold", "axes.labelsize": 10.5})


def read(name):
    with open(R / name) as f:
        return list(csv.DictReader(f))


def mean_ci(xs):
    a = np.array(xs, dtype=float)
    return a.mean(), T975.get(len(a) - 1, 1.96) * a.std(ddof=1) / math.sqrt(len(a))


network, iso, compare, scores = read("network.csv"), read("isolate_runs/network.csv"), read("compare_runs.csv"), read("suspect_scores.csv")
hours = float(network[0]["hours"])
seeds = sorted({r["seed"] for r in network})
SET = sorted({(r["grayholes"], r["load"]) for r in network}, key=lambda g: (int(g[0]), float(g[1])))
live = {(r["grayholes"], r["load"], r["case"], r["seed"]): r for r in network}
cut = {(r["grayholes"], r["load"], r["case"], r["seed"]): r for r in iso}
pdr = lambda r: 100 * float(r["pdr"])
throughput = lambda r: float(r["delivered"]) / hours
caught = lambda r: 100 * int(r["grayholes_caught"]) / int(r["grayholes"])
accused = lambda r: float(r["honest_suspects"])


def pooled(runs, case, value):
    """Mean over every setting, 95% t-interval over topologies."""
    return mean_ci([np.mean([value(runs[(*g, case, s)]) for g in SET]) for s in seeds])


def paired_ci(runs, case, value):
    """95% t-interval of the per-topology difference from 'attack, no defence'."""
    return mean_ci([np.mean([value(runs[(*g, case, s)]) - value(runs[(*g, "nodefense", s)]) for g in SET]) for s in seeds])[1]


def page(n, title, subtitle, caption):
    fig = plt.figure(figsize=(11.69, 8.27))   # A4 landscape
    fig.text(0.5, 0.955, title, ha="center", fontsize=18, fontweight="bold")
    fig.text(0.5, 0.92, subtitle, ha="center", fontsize=10.5, color="#444444")
    fig.text(0.5, 0.108, textwrap.fill(caption.replace("\n", " "), 185), ha="center", va="top", fontsize=8.8,
             color="#333333", linespacing=1.4)
    fig.add_artist(plt.Line2D([0.04, 0.96], [0.035, 0.035], color="#bbbbbb", lw=0.6))
    fig.text(0.04, 0.018, "Team 9 · CS301 Mini Project · Observability-aware trust-based grayhole detection (UnetStack 7.0.0)",
             fontsize=8, color="#666666")
    fig.text(0.96, 0.018, f"Page {n} of {PAGES}", ha="right", fontsize=8, color="#666666")
    return fig


def table(ax, header, rows, highlight=None, col_widths=None, fontsize=10):
    ax.axis("off")
    t = ax.table(cellText=rows, colLabels=header, loc="center", cellLoc="center", colWidths=col_widths)
    t.auto_set_font_size(False)
    t.set_fontsize(fontsize)
    t.scale(1, 1.75)
    for (i, j), cell in t.get_celld().items():
        cell.set_edgecolor("#cccccc")
        if i == 0:
            cell.set_height(cell.get_height() * 1.7)
            cell.set_facecolor("#2c3e50")
            cell.set_text_props(color="white", fontweight="bold")
        elif highlight is not None and rows[i - 1][0] == highlight:
            cell.set_facecolor("#d5f5e3")
            cell.set_text_props(fontweight="bold")
    return t


def bars(ax, runs, cases, value, paired=False, fmt="{:.1f}"):
    for i, c in enumerate(cases):
        y, e = pooled(runs, c, value)
        if paired:
            e = 0.0 if c == "nodefense" else paired_ci(runs, c, value)
        ax.bar(i, y, 0.62, yerr=e, capsize=4, color=COLOR[c], edgecolor="black", linewidth=0.6)
        ax.text(i, y + e, fmt.format(y), ha="center", va="bottom", fontsize=10.5, fontweight="bold")
    ax.set_xticks(range(len(cases)), [SHORT[c] for c in cases])


pdf = PdfPages(OUT)

# ---------------------------------------------------------------- page 1: overview and headline table
fig = page(1, "Observability-Aware Trust-Based Grayhole Detection",
           "Underwater acoustic networks · Network layer · UnetStack 7.0.0 · Team 9 (241CS110, 241CS111, 241CS117)",
           "Live network: 12 h per run, 10 test topologies, 4 attack settings (1 grayhole at loads 0.005 and 0.01; 2 and 3 grayholes "
           "at 0.005), grayholes = busiest relays dropping 30%.\nAll five cases run on the same topologies with the same traffic. "
           "Values are means over all settings and topologies.")
fig.text(0.06, 0.85, "Problem", fontsize=12.5, fontweight="bold")
fig.text(0.06, 0.765,
         "A grayhole relay silently drops a fraction of the packets it should forward. Existing watchdogs count missed forwards, but in an\n"
         "acoustic network collisions and long, lossy links also cause missed forwards, so honest relays are falsely accused.",
         fontsize=10.5, va="bottom")
fig.text(0.06, 0.72, "Our improvement over the baselines (fixed-threshold watchdog, channel-aware CAD-style threshold)", fontsize=12.5, fontweight="bold")
fig.text(0.06, 0.615,
         "1. Observability filter: judge a relay only in windows where the monitor could actually hear its forward (no local collision).\n"
         "2. Expected normal loss: estimate each link's normal loss from its distance and neighbouring links, then score excess loss.\n"
         "3. Ranked suspect list: estimated drop rate per relay; confirm a suspect after two flagged 30-min rounds in a row.",
         fontsize=10.5, va="bottom", linespacing=1.6)
ax = fig.add_axes([0.06, 0.29, 0.88, 0.28])
rows = [[NAME[c], f"{pooled(live, c, caught)[0]:.0f}%" if c in DEFENCES[1:] else "-",
         f"{pooled(live, c, accused)[0]:.1f}", f"{pooled(live, c, pdr)[0]:.1f}%", f"{pooled(live, c, throughput)[0]:.0f}",
         f"{pooled(cut, c, pdr)[0]:.1f}%" if c != "noattack" else "-"]
        for c in ["noattack"] + DEFENCES]
table(ax, ["Case", "Grayholes\ncaught", "Honest nodes\naccused per run", "Delivery\nratio", "Throughput\n(packets/hour)",
           "Delivery ratio if\nsuspects blacklisted"], rows, highlight=NAME["ours"], col_widths=[0.2, 0.14, 0.18, 0.13, 0.15, 0.2])
ours_cut, fixed_cut = pooled(cut, "ours", pdr)[0], pooled(cut, "fixed", pdr)[0]
fig.text(0.5, 0.19,
         f"Headline:  ours catches {pooled(live, 'ours', caught)[0]:.0f}% of grayholes with {pooled(live, 'ours', accused)[0]:.1f} false accusations per run "
         f"(fixed watchdog: {pooled(live, 'fixed', accused)[0]:.1f}).\nWith blacklisting, ours keeps {ours_cut:.1f}% delivery vs {fixed_cut:.1f}% "
         f"for the fixed watchdog: +{ours_cut - fixed_cut:.1f} percentage points ({ours_cut / fixed_cut:.1f}× the delivery).",
         ha="center", va="center", fontsize=11.5, fontweight="bold", color="#145a32", linespacing=1.6,
         bbox=dict(boxstyle="round,pad=0.7", facecolor="#eafaf1", edgecolor="#1e8449"))
pdf.savefig(fig)
plt.close(fig)

# ---------------------------------------------------------------- page 2: detection and false accusations (live)
fig = page(2, "Detection vs False Accusation (Live Network)",
           "Same networks, same grayholes, same response · each method with its own threshold",
           f"Bars: mean over 4 settings × {len(seeds)} topologies; error bars: 95% t-interval over topologies. The fixed watchdog reaches 100% "
           "only by accusing ~7 honest relays per run;\nCAD-style accuses few but misses 4 in 10 grayholes. Ours keeps both: 95% caught, "
           "0.3 honest relays accused per run.")
ax1, ax2 = fig.add_axes([0.07, 0.17, 0.39, 0.66]), fig.add_axes([0.56, 0.17, 0.39, 0.66])
bars(ax1, live, DEFENCES[1:], caught, fmt="{:.0f}%")
ax1.set(title="(a) Grayholes caught   (higher is better)", ylabel="Grayholes caught (%)", ylim=(0, 115))
bars(ax2, live, DEFENCES[1:], accused)
ax2.set(title="(b) Honest relays falsely accused   (lower is better)", ylabel="Honest relays accused per run", ylim=(0, 10))
pdf.savefig(fig)
plt.close(fig)

# ---------------------------------------------------------------- page 3: delivery and throughput (live)
fig = page(3, "Delivery Ratio and Throughput Under Attack",
           "Suspects are routed around wherever another path exists (default response for every method)",
           "Bars: mean over 4 settings × 10 topologies; error bars: 95% t-interval of the difference from 'attack, no defence' on the same topology "
           "(paired).\nDashed line: the same networks with no attack (ceiling). Ours recovers the most delivery and throughput because it "
           "avoids the grayholes without avoiding honest relays.")
for k, (value, label, title) in enumerate([(pdr, "Delivery ratio (%)", "(a) Delivery ratio   (higher is better)"),
                                           (throughput, "Throughput (honest packets / hour)", "(b) Throughput   (higher is better)")]):
    ax = fig.add_axes([0.07 + 0.49 * k, 0.17, 0.39, 0.66])
    bars(ax, live, DEFENCES, value, paired=True, fmt="{:.1f}" if k == 0 else "{:.0f}")
    top = pooled(live, "noattack", value)[0]
    ax.axhline(top, color=COLOR["noattack"], ls="--", lw=1.6, label=f"No attack (ceiling): {top:.1f}" if k == 0 else f"No attack (ceiling): {top:.0f}")
    lo = pooled(live, "nodefense", value)[0]
    ax.set(title=title, ylabel=label, ylim=(0.85 * lo, 1.06 * top))
    ax.legend(loc="upper left", fontsize=9)
pdf.savefig(fig)
plt.close(fig)

# ---------------------------------------------------------------- page 4: blacklisting, mean and distribution
gain = [np.mean([pdr(cut[(*g, "ours", s)]) - pdr(cut[(*g, "fixed", s)]) for g in SET]) for s in seeds]
g_mean, g_ci = mean_ci(gain)
wins = sum(pdr(cut[(*g, "ours", s)]) > pdr(cut[(*g, "fixed", s)]) for g in SET for s in seeds)
fig = page(4, "Blacklisting Response: Delivery Ratio",
           "Same detectors, but every confirmed suspect is cut out of the network (blacklisted)",
           f"(a) Mean over 4 settings × 10 topologies; error bars: 95% t-interval over topologies; white label inside each bar: honest relays blacklisted per run. "
           f"(b) Each dot is one run\n(topology × setting, n = {len(SET) * len(seeds)} per method); box = quartiles, line = median. "
           f"Every honest relay the fixed watchdog blacklists takes the nodes behind it offline, so delivery collapses.")
ax = fig.add_axes([0.07, 0.17, 0.39, 0.66])
for i, c in enumerate(DEFENCES):
    y, e = pooled(cut, c, pdr)
    ax.bar(i, y, 0.62, yerr=e, capsize=4, color=COLOR[c], edgecolor="black", linewidth=0.6)
    ax.text(i, y + e + 0.8, f"{y:.1f}%", ha="center", va="bottom", fontsize=10.5, fontweight="bold")
    if c != "nodefense":
        ax.text(i, 2, f"{np.mean([accused(cut[(*g, c, s)]) for g in SET for s in seeds]):.1f}\nhonest\nblacklisted",
                ha="center", va="bottom", fontsize=8, color="white", fontweight="bold")
ax.annotate("", xy=(2.9, 66), xytext=(1, fixed_cut + 15),
            arrowprops=dict(arrowstyle="->", lw=2, color="#145a32", connectionstyle="arc3,rad=-0.25"))
ax.text(1.55, 71, f"+{ours_cut - fixed_cut:.1f} pp\n({ours_cut / fixed_cut:.1f}× delivery)", ha="center", fontsize=12,
        fontweight="bold", color="#145a32")
ax.set_xticks(range(4), [SHORT[c] for c in DEFENCES])
ax.set(title="(a) Mean delivery ratio with blacklisting", ylabel="Delivery ratio (%)", ylim=(0, 82))
ax = fig.add_axes([0.56, 0.17, 0.39, 0.66])
dist = [[pdr(cut[(*g, c, s)]) for g in SET for s in seeds] for c in DEFENCES]
bp = ax.boxplot(dist, positions=range(4), widths=0.5, patch_artist=True, showfliers=False,
                medianprops=dict(color="black", lw=1.8))
for patch, c in zip(bp["boxes"], DEFENCES):
    patch.set(facecolor=COLOR[c], alpha=0.35, edgecolor=COLOR[c])
rng = np.random.default_rng(0)
for i, (c, ys) in enumerate(zip(DEFENCES, dist)):
    ax.scatter(i + rng.uniform(-0.17, 0.17, len(ys)), ys, s=16, color=COLOR[c], edgecolor="black", linewidth=0.3, zorder=3)
ax.set_xticks(range(4), [SHORT[c] for c in DEFENCES])
ax.set(title="(b) Distribution over every run", ylabel="Delivery ratio per run (%)", ylim=(0, 75))
pdf.savefig(fig)
plt.close(fig)

# ---------------------------------------------------------------- page 5: blacklisting, per setting and paired gain
fig = page(5, "Blacklisting Response: Gain of Ours Over the Fixed Watchdog",
           "Paired comparison: both detectors judge the same topology, setting and traffic",
           f"(a) Mean delivery ratio per setting (10 topologies each). (b) Ours − fixed watchdog, one bar per topology (mean over the 4 settings). "
           f"Mean gain {g_mean:+.1f} ± {g_ci:.1f} pp (95% t-interval);\nours is higher in {wins} of {len(SET) * len(seeds)} runs. "
           "In topologies 205 and 209 ours gains least: there the correctly caught grayhole was the only path for many nodes, so cutting it out also cuts them off.")
ax = fig.add_axes([0.05, 0.44, 0.42, 0.36])
rows = []
for g in SET:
    vals = {c: np.mean([pdr(cut[(*g, c, s)]) for s in seeds]) for c in DEFENCES}
    rows.append([f"{g[0]} grayhole{'s' if g[0] != '1' else ''}, {g[1]}"] + [f"{vals[c]:.1f}%" for c in DEFENCES]
                + [f"+{vals['ours'] - vals['fixed']:.1f} pp"])
rows.append(["All settings"] + [f"{pooled(cut, c, pdr)[0]:.1f}%" for c in DEFENCES] + [f"+{ours_cut - fixed_cut:.1f} pp"])
t = table(ax, ["Grayholes, load", "No\ndefence", "Fixed\nwatchdog", "CAD-\nstyle", "Ours", "Ours −\nfixed"], rows,
          col_widths=[0.29, 0.13, 0.15, 0.13, 0.12, 0.18], fontsize=9)
for j in range(6):
    t[len(rows), j].set_text_props(fontweight="bold")
    t[len(rows), j].set_facecolor("#eafaf1")
ax.set_title("(a) Delivery ratio with blacklisting, per setting", pad=8)
ax = fig.add_axes([0.05, 0.17, 0.42, 0.18])
honest = [[NAME[c], f"{np.mean([accused(cut[(*g, c, s)]) for g in SET for s in seeds]):.2f}",
           f"{np.mean([caught(cut[(*g, c, s)]) for g in SET for s in seeds]):.0f}%"] for c in DEFENCES[1:]]
table(ax, ["Detector", "Honest relays\nblacklisted per run", "Grayholes\ncaught"], honest, highlight=NAME["ours"],
      col_widths=[0.34, 0.4, 0.26], fontsize=9.5)
ax = fig.add_axes([0.56, 0.17, 0.39, 0.66])
ax.bar(range(len(seeds)), gain, 0.65, color=[COLOR["ours"] if v > 0 else COLOR["fixed"] for v in gain], edgecolor="black", linewidth=0.6)
for i, v in enumerate(gain):
    ax.text(i, v + (0.6 if v >= 0 else -0.6), f"{v:+.0f}", ha="center", va="bottom" if v >= 0 else "top", fontsize=9)
ax.axhline(g_mean, color="#145a32", ls="--", lw=1.6, label=f"Mean gain {g_mean:+.1f} pp")
ax.axhspan(g_mean - g_ci, g_mean + g_ci, color=COLOR["ours"], alpha=0.12, label="95% t-interval")
ax.axhline(0, color="black", lw=0.8)
ax.set_xticks(range(len(seeds)), seeds)
ax.set(title="(b) Delivery gain of ours over fixed, per topology", xlabel="Test topology", ylabel="Ours − fixed watchdog (percentage points)",
       ylim=(min(0, min(gain)) - 3, max(gain) + 6))
ax.legend(loc="upper right", fontsize=9)
pdf.savefig(fig)
plt.close(fig)

# ---------------------------------------------------------------- page 6: why the fixed watchdog fails
load_runs = [r for r in compare if r["scenario"] == "load" and r["rule"] == "own"]
loads = sorted({float(r["load"]) for r in load_runs})
fig = page(6, "Why the Fixed Watchdog Fails: False Accusations as Traffic Grows",
           "Recorded traffic, 6 h per run, 20-node networks, 10 test topologies per point · each method with its own threshold",
           "Error bars: 95% t-intervals over topologies. More traffic means more collisions; the fixed watchdog counts every unheard forward "
           "as a drop and accuses up to 6 honest relays per run.\nOurs skips windows the monitor could not observe and expects each "
           "link's normal loss, so it accuses at most 0.2 honest relays per run, with or without an attacker.")
for k, (attacked, title) in enumerate([(False, "(a) No attacker in the network"), (True, "(b) One grayhole (30% drop)")]):
    ax = fig.add_axes([0.07 + 0.49 * k, 0.17, 0.39, 0.66])
    for m, mk in [("fixed", "s"), ("cad", "^"), ("ours", "o")]:
        ys, es = [], []
        for load in loads:
            by_seed = defaultdict(list)
            for r in load_runs:
                if r["method"] == m and float(r["load"]) == load and (r["n_attackers"] != "0") == attacked:
                    by_seed[r["seed"]].append(float(r["honest_suspects"]))
            y, e = mean_ci([np.mean(v) for v in by_seed.values()])
            ys.append(y), es.append(e)
        ax.errorbar(loads, ys, yerr=es, color=COLOR[m], marker=mk, ms=7, lw=2, capsize=4, label=NAME[m])
    ax.set(title=title, xlabel="Traffic load (packets/s per node, log scale)", xscale="log", ylim=(-0.3, 8))
    ax.set_xticks(loads, [f"{x:g}" for x in loads])
    ax.minorticks_off()
    ax.set_ylabel("Honest relays falsely accused per run")
    ax.legend(loc="upper left", fontsize=9)
pdf.savefig(fig)
plt.close(fig)

# ---------------------------------------------------------------- page 7: fair contest and what the admin sees
SCEN = [("load", "Load\nsweep"), ("drop", "Drop\n10–100%"), ("hotspot", "Traffic\nhotspots"), ("near", "Near\nneighbours"),
        ("sparse", "Sparse\nnetwork"), ("large", "40-node\nnetwork"), ("two", "2\ngrayholes"), ("three", "3\ngrayholes")]


def rate(method, scenario, num, den):
    rs = [r for r in compare if r["rule"] == "budget" and r["method"] == method and r["scenario"] == scenario]
    return 100 * sum(float(r[num]) for r in rs) / sum(float(r[den]) for r in rs)


fig = page(7, "Fair Contest and the Administrator's Suspect List",
           "Every threshold tuned on 6 separate practice networks to accuse at most 5% of honest relays with no attacker",
           "(a) Grayholes detected on the 10 test topologies when every method is held to the same false-alarm budget. Ours detects the most "
           "in 7 of 8 scenarios (ties CAD-style with 3 grayholes).\n(b) Ranked suspect list from one test topology with 3 grayholes: "
           "the three grayholes (red) rank first and are confirmed; honest relays sit near 0% estimated drop.")
ax = fig.add_axes([0.06, 0.2, 0.5, 0.63])
x = np.arange(len(SCEN))
for i, m in enumerate(["fixed", "cad", "ours"]):
    b = ax.bar(x + (i - 1) * 0.27, [rate(m, s, "caught", "n_attackers") for s, _ in SCEN], 0.27, color=COLOR[m], label=NAME[m],
               edgecolor="black", linewidth=0.4)
    ax.bar_label(b, fmt="%.0f", fontsize=7.5, padding=1)
ax.set_xticks(x, [label for _, label in SCEN], fontsize=8.5)
ax.set(title="(a) Grayholes detected at the same false-alarm budget", ylabel="Grayholes detected (%)", ylim=(0, 118))
ax.legend(loc="upper center", ncol=3, fontsize=9)
ax = fig.add_axes([0.66, 0.2, 0.3, 0.63])
ex_seed = min(r["seed"] for r in scores if r["scenario"] == "three")
ex = sorted([r for r in scores if r["scenario"] == "three" and r["seed"] == ex_seed and r["method"] == "ours"], key=lambda r: -float(r["score"]))
ax.barh([f"node {r['node']}" for r in ex][::-1], [float(r["score"]) for r in ex][::-1],
        color=[COLOR["fixed"] if r["attacker"] == "1" else "#95a5a6" for r in ex][::-1], edgecolor="black", linewidth=0.4)
for k, r in enumerate(ex[::-1]):
    tag = "SUSPECT" if r["suspect"] == "1" else ("missed" if r["attacker"] == "1" else "honest")
    ax.text(float(r["score"]) + 0.8, k, f"{float(r['score']):.1f}%  {tag}", va="center", fontsize=8.5,
            fontweight="bold" if r["attacker"] == "1" else "normal")
ax.set(title=f"(b) Ranked suspects, topology {ex_seed}", xlabel="Estimated drop rate (%)", xlim=(0, 65))
ax.grid(axis="y", visible=False)
pdf.savefig(fig)
plt.close(fig)

pdf.close()
print(f"wrote {OUT}")
