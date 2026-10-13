> Historical review/planning input. Current implementation and evidence status: [HANDOFF.md](HANDOFF.md), [METHOD.md](METHOD.md). Instructions quoted here are context, not active commands.

# Short handoff for Claude

Improve and validate the final detector, Ours v3. Use UnetStack on native macOS. Keep every change explainable in the exam. Use ASD-STE100 writing principles and remove filler with the unslop skill.

## First checks

Preserve the current code and results. Fix the empty Phase 1 plot and incorrect threshold labels. Check whether corrupted forwards bias the observability filter. Check dependence between monitors that observe the same packet.

The current z threshold is fixed. Expected loss and dispersion adapt. Explain this as an adaptive decision boundary. Do not claim an explicit dynamic threshold unless the code implements and validates it.

Draw one framework that matches the code: monitoring → observability → expected loss → decision → suspect report → optional route change. State all assumptions. Use attacker labels and hidden simulation events only to evaluate the detector.

## Fair comparison

Compare fixed watchdog, verified CAD or a clearly labelled CAD-style baseline, ours without filtering, and complete ours. Give each method its required inputs and document differences. Use the same recorded network evidence where possible. Calibrate thresholds on separate networks to the same false-alarm limit.

Count final false accusations and honest relays ever flagged. Report eligible observations and judged-node coverage. Apply the filter to both successful and missed forwards. Compare frozen and adaptive decision boundaries while keeping other components unchanged.

## Improvements to test

| Test | Reason and acceptance check |
|---|---|
| Busy-monitor traffic | Test deafness under uneven traffic. Keep total traffic equal. Accept a filter change only if it improves detection or false alarms at a fair operating point. |
| Weak attacks at 10%, 20%, and 30% dropping | Test longer observation periods. Check the 10% minimum-score rule first. Longer runs alone may not solve that limit. |
| Two and three attackers | Check contamination of the expected-loss baseline. Try iterative removal only after confirming this cause. Stop using a calibrated rule and reject changes that cause false-accusation cascades. |
| Channel-model error | Change the assumed loss curve by prespecified amounts. Check whether the improvement depends on knowing the exact simulator model. |
| One supported CSMA setting | Check whether results survive MAC coordination. Reserve transmission time through the MAC and recalibrate forwarding windows. Adding the agent alone is insufficient. |

Test one change at a time. Start with small correctness checks. Adopt changes on calibration evidence, then freeze the method before untouched test runs.

## Keep five main graphs

| Graph | Comparison and justification |
|---|---|
| 1. False accusations and recall versus load | Baselines versus complete ours. Shows benefit and missed attacks together. |
| 2. Contribution of filtering and adaptation | Filter off versus on, then frozen versus adaptive boundary. Use uniform and busy-monitor traffic. Shows which component causes the improvement. |
| 3. Detection over time | Baselines versus ours at 10%, 20%, and 30% dropping. Include undetected attackers. Shows the speed trade-off. |
| 4. Network performance before and after defense | No attack, attack without defense, baseline defenses, and ours. Show delivery and delay. Include detection time, reporting, and route changes. Keep avoidance and isolation separate. |
| 5. Performance gain over baselines | Plot paired differences in delivery, delay, and transmissions per delivered packet. Show confidence intervals. This is a second view of graph 4's data, not independent evidence. |

For every caption, name the before condition, after condition, changed component, fixed settings, sample count, and uncertainty. State the gain numerically. Use percentage points for delivery-ratio differences. Do not call transmission count measured energy.

Use a small supporting table for multiple attackers and channel-model errors. Keep all planned results. Put the framework diagram outside the five result graphs. Do not select figures only because they favor ours.

## Code and completion

Write readable Groovy and Python. Use descriptive names, short functions, existing helpers, and simple control flow. Comment the reason for a formula or threshold. Avoid compressed one-line logic and unnecessary abstractions. Add small checks for changed logic.

Save raw data, seeds, parameters, and run instructions. Align the abstract, framework, formulas, code, and results. Claim only the improvements that fresh tests support. If a change fails, keep the result and explain the limit.
