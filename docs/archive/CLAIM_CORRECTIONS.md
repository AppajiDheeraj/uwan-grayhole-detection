# Correction of earlier chat claims — 12 October 2026

The earlier numerical statements match saved v2 development results, but describing them as “final” and calling the project a success was too confident. They did not establish a fair comparison or a deployable network benefit. The earlier chat did warn that publication readiness was unresolved; that warning does not make the stronger wording appropriate.

## What was checked again

The source of the older results is `CN-Project-checkpoint-v2-final-2026-10-12.zip`, specifically its `results/phase2_runs.csv` and `results/phase3_runs_isolate.csv`. The current source is the completed review run's `matched_runs.csv`, `matched_budget.csv`, `phase3_avoid.csv` and `phase3_isolate.csv`.

| Earlier statement | Archived evidence | Correct interpretation now |
|---|---|---|
| Zero final false accusations for ours versus 4.6 fixed and 5.8 CAD-style at the highest attack load | Confirmed in v2: means 0.0, 4.6, 5.8; ours caught 9/10 and both baselines 10/10 | True for those development settings. Final-only accusations hide temporary flags, and the old baseline rules were not calibrated to a common false-alarm budget. |
| Isolation delivery approximately 46%, 29%, 27% | Confirmed in v2: ours 46.44%, fixed 28.72%, CAD-style 27.00%; no defence 44.40% | Old post-decision experiment. It does not establish current chronological performance with detection delay and actual delivered reports. |
| Ours estimates 30% dropping near 30% | Old development observation | Current main estimate is 27.2% in that setting. Claims must identify the version and scenario. |
| Numerical z cutoff fixed at 2; effective boundary adapts | Applied to the older detector | Current main rule uses a numerical quantile depending on alpha and the number of judged relays. Alpha is calibrated and then fixed. Do not combine the old explanation with new results. |
| Corrupted-reception windows are excluded | Applied to the earlier C2 filter | C2 was removed from the main filter because a corrupted attempted forward can itself cause exclusion. Current filter is C1+C3+C4. |
| Course-project success / useful routing benefits | Provisional interpretation of saved results | Too confident as an overall verdict. The current study demonstrates a detection improvement in named scenarios, with unresolved operational weaknesses. |

## Results supported by the completed review run

Main four-load sweep, one 30%-dropping attacker, ten topology seeds reused across loads:

| Method | Attackers caught at end | Honest relay–run identities ever flagged | Pooled ever-flagged rate |
|---|---:|---:|---:|
| Fixed watchdog | 7/40 (17.5%) | 25/516 | 4.84% |
| CAD-style adaptation | 21/40 (52.5%) | 18/522 | 3.45% |
| Proposed method | 33/40 (82.5%) | 17/528 | 3.22% |

These denominators are judged relay–run identities, not independent physical nodes. The test rates are achieved outcomes, not exactly matched rates. All thresholds were selected under the same pooled 5% no-attack calibration upper budget. The proposed method's small false-alarm-rate difference from CAD-style is not established as significant.

A paired topology-level comparison of recall, averaging each seed's four loads first, gives a proposed-minus-CAD difference of 30 percentage points, with an exploratory 95% t interval [11.5, 48.5]. It uses ten independent topology averages rather than pretending there are 40 independent networks. The comparison concerns the implemented CAD-style adaptation.

For avoidance at load 0.005, ours-minus-CAD delivery is +0.94 percentage points with an interval approximately [-1.06, 2.94]. At load 0.01 it is +0.88 [-0.23, 1.99]. Both include zero. Ours has greater mean report traffic and transmission cost. Three-attacker recall is 5/30 for ours versus 22/30 for CAD-style. None of these failures is removed from the new draft.

## Tomorrow's draft

`report/reviewed/main.tex` uses the supplied article layout and our own content. Its strongest claim is improved detection in the calibrated main comparison, with reporting costs and multiple-attacker failures disclosed. It contains five figures and uses the new results only. `report/reviewed/evidence.json` records the input hashes.

This does not fulfil the stronger goal of demonstrated overall network superiority. No new detector or routing policy has been introduced after viewing these test outcomes. Further changes would require development evaluation and a new holdout before new confirmatory claims. A paper can report bounded improvements and negative results; neither formatting nor improved graphs guarantees acceptance or a novel contribution.
