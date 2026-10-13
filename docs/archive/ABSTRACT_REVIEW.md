# Abstract and claim review — 12 October 2026

Working material for the team to understand and revise. The submitted proposal sources and PDFs are preserved. This is not a claim that the experiment has succeeded or a final submission-ready results abstract.

## What the proposal needs to change

| Existing statement or implication | Review and replacement |
|---|---|
| No reviewed detector checks whether a monitor could hear a forward | Too broad. Marti et al. explicitly discuss ambiguous and receiver collisions. Describe the particular eligibility checks implemented here; do not claim the first recognition of observability. |
| Watchdog detectors treat every missed overhear as malicious dropping | Qualify this as the behaviour of our simple fixed-threshold comparator. It is not a description of all published detectors. |
| CAD does not address listening conditions | Its normal-loss model includes medium-access collisions. Explain our local exclusions separately from its channel/collision estimation. |
| An observable window means the monitor certainly could hear | It means no listed locally observable conflict excluded it. Hidden interference, decoding errors and late forwarding remain possible. |
| Every node keeps and applies its own trust threshold (`main.tex`) | The current detector pools monitor evidence; Phase 3 decisions occur at the sink. Local agents collect counters. |
| Fixed near/medium/far weights are the proposed algorithm | Distances determine channel expectations and area membership; area residual losses determine expected forwards. Fixed multipliers are ablations. |
| Dynamic thresholds establish novelty | CAD already adapts thresholds to estimated losses. Our particular decision rule and evidence selection must be described and tested without this novelty shortcut. |
| Fewer accusations establishes overall superiority | Recall, timing, delivery and reporting cost must be assessed together, including losses under weak and multiple attacks. |
| Reporting overhead measures all control overhead | Report transmissions are simulated; central route distribution is instantaneous and free. Say reporting overhead. |
| The fixed watchdog reproduces the professor's paper | The supplied paper uses a destination-side timeout/counter mechanism. Our sender watchdog is a different comparator. |

The proposal also has a duplicated citation, `[3].~\cite{chandavarkar2021}`, which should become one LaTeX citation in its next revision. The CAD implementation was checked against the **2010 journal account**; cite that account when describing its adaptation instead of silently referring to the 2009 conference version.

## Revised one-paragraph working abstract

Underwater acoustic sensor networks rely on neighbouring nodes to relay data to a sink, but natural packet loss makes selective forwarding attacks difficult to distinguish from communication failures. A grayhole relay deliberately drops a fraction of the packets it receives, while an honest relay may also appear silent because its incoming packet was lost or its forward was not overheard. A simple watchdog that counts these misses against a fixed limit can therefore accuse honest relays. Previous watchdog research identifies collision-related monitoring failures, and channel-aware detection estimates normal losses before judging forwarding behaviour. This project investigates whether local observability checks combined with an expected-forward estimate can improve that distinction in a static underwater network simulation. We implement a network-layer detector in UnetStack 7 that excludes forwarding windows affected by specified locally observed transmission conflicts. For the remaining windows, known node distances and an assumed channel-loss curve provide a channel expectation, while other monitor–relay links provide an area-based estimate of residual loss. The detector pools observed and expected forwards to rank suspected relays and flags them only when observation-count, estimated-drop and deviation conditions are satisfied. Threshold settings are selected on separate no-attack calibration topologies. Our objectives are to compare false accusations, attacker recall and detection time against a fixed watchdog and a stated CAD-style adaptation; examine the effects of filtering, traffic, drop rate and multiple attackers; and measure packet delivery, delay and transmission cost during chronological reporting and route avoidance. The response experiment uses reports actually delivered to the sink, while assuming ideal central route updates. Its results will establish the conditions and trade-offs under which the method improves the implemented comparators.

This paragraph describes the work without inventing an outcome. Add verified numerical results and a bounded conclusion only after the test and response outputs are reviewed. Do not paste a favourable calibration number into that results sentence.

## Evidence needed for the final abstract

| Objective | Authoritative evidence | Required interpretation |
|---|---|---|
| Fewer false accusations | `matched_runs.csv`, `matched_budget.csv`, calibrated thresholds | Report test ever-flagged rate together with judged coverage; the calibration budget is not a test guarantee. |
| Useful attacker detection | Same files, `matched_timeline.csv` | Report recall and non-detections. A delay average among detected runs does not describe every attacker. |
| Benefit from the filter | Matched-budget `area` versus `ours`, component ablations | Keep other model choices fixed; expose scenarios where filtering loses evidence or does not help. |
| Benefit from adaptation | Matched `ours-frozen` versus `ours`, supporting fixed-z ablation | State exactly what each ablation removes; a frozen-loss cutoff removes more than only the dynamic z boundary. |
| Network benefit | `phase3_avoid.csv`, `phase3_rounds_avoid.csv`, per-seed differences | Whole-run delivery and cost, including waiting and actual reports; isolation is a separate policy. |
| Learnable implementation | `STUDY_GUIDE.md`, self-checks, main source files | Each teammate must trace one packet and explain expected forwards, score, threshold and reporting assumptions. |

“Better overall” must be supported by detection and network outcomes together. There is no established single weighted score in this project. Do not invent one after seeing the results, hide unfavourable scenarios, or let an improvement over an over-sensitive native threshold stand in for the calibrated comparison. If outcomes are mixed, identify the supported improvement and the unresolved weakness; further design work remains necessary for a stronger claim.

## Sources and scope

- [Marti et al., 2000, original watchdog/pathrater paper](https://shiftleft.com/mirrors/www.hpl.hp.com/personal/Mary_Baker/publications/mitigating.pdf), section 3.1: monitoring mechanism and collision limitations. The present fixed-rate threshold and routing controller are adaptations, not their complete implementation.
- [Shila, Cheng and Anjali, 2010, author-hosted paper](https://www.ece.iit.edu/~yucheng/YCheng_TWC10.pdf), *Mitigating Selective Forwarding Attacks with a Channel-Aware Approach in WMNs*, IEEE TWC 9(5), 1661–1675, DOI 10.1109/TWC.2010.05.090700: normal-loss estimation, upstream/downstream monitoring and adaptive detection thresholds. Our comparator omits several protocol components; see `METHOD.md`.
- [Liu et al., 2024](https://www.mdpi.com/1424-8220/24/9/2880): an adaptive underwater trust model. Its existence supports positioning this work among underwater trust approaches; it is not an implemented experimental baseline here. The publisher's full-page fetch was rate-limited during this pass; no detailed new claim about its algorithm is based on that failed fetch.
- [Han et al., 2023, full text](https://pmc.ncbi.nlm.nih.gov/articles/PMC10708718/): underwater trust-aware fuzzy routing cited in the earlier `main.tex` draft. It is background, not a reproduced comparator.
- Chandavarkar and Suresh, 2021: supplied local six-page paper, `Demonstration_of_Selective_Forwarding_Attack_and_Detection_Mechanism_in_UnetStack3.pdf` in Downloads. Its attack demonstration motivates the project; a superiority claim against its algorithm would require a faithful comparison not currently implemented.

This is a targeted claim check, not an exhaustive novelty survey. Claims about ARTMM beyond its published abstract still require a full-text review before appearing in a detailed literature comparison.
