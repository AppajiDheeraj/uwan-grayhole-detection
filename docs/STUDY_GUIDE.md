# Study guide

Numbers are in `results/summary.md`; the five graphs are in `results/figs/`.

## Explain the problem

A grayhole forwards some packets and deliberately drops others. A watchdog listens for a relay to forward a packet. Silence is ambiguous: the relay might have dropped it, never received it, or forwarded it while the monitor could not hear.

Our improvement excludes some locally unobservable windows and compares observed forwards with an estimate for an honest relay under the simulated channel and traffic conditions.

## Follow the pipeline (`./run.sh`)

1. `Topology.groovy` places 20 sensors and the sink and computes shortest-hop routes.
2. `MonitorNode.groovy` is every node: it generates data, forwards packets, drops 30% of them if it is a grayhole, and watches every neighbouring relay (a watch window per packet, kept only if it was observable).
3. **record** (`record.groovy`) simulates many networks and stores every monitor's counters once.
4. **compare** (`compare.groovy`) replays the sink's decisions for the fixed watchdog, CAD-style and ours on exactly the same counters, and prints the ranked suspect list (`results/suspect_list.txt`).
5. **live network** (`network.groovy`) runs it for real: every 30 min each monitor sends one report frame to the sink; the sink runs the detector, confirms a suspect after two flagged rounds in a row (`Detectors.confirm`) and routes traffic around it. Delivery and throughput are measured with all of this traffic included.
6. **graphs** (`analysis/plots.py`) draws the five graphs and writes `results/summary.md`.

## Understand the score

If an honest relay is expected to produce 80 observable forwards and the monitors observe 60, the estimated excess drop fraction is `1 - 60/80 = 0.25`; the score is 25. This is illustrative arithmetic, not an experimental result.

A high score is not proof of an attack. The decision also requires enough observations and a deviation threshold. Zero expected forwards mean there is insufficient evidence to judge the relay.

## Explain adaptation honestly

The expectation changes with estimated natural losses. The deviation threshold also accounts for the number of judged relays. Its alpha parameter is tuned on separate no-attack networks that are never used for testing. Dependence and model approximation mean alpha is not a guaranteed real-world false-alarm probability.

## Questions to practise

- **What are the baselines?** A fixed watchdog and a CAD-style adaptation, not full reproductions of published protocols.
- **Why exclude the judged relay from its reference?** Its suspicious behaviour should not define its own normal behaviour, where other evidence permits this.
- **Why separate tuning and test networks?** Selecting thresholds on test outcomes would bias the evaluation.
- **Why measure false alarms alongside recall?** Flagging everyone detects every attacker but is not useful.
- **Why are multiple grayholes difficult, and how do we handle them?** Several grayholes in one area raise its "normal" loss, so honest relays look too good and the spread factor hides the grayholes, although they still rank first. Ours cleans the baseline: relays with at least 10% estimated drop, and confirmed suspects, stop defining "normal", so the next grayhole stands out.
- **What does the live network measure?** Delivery ratio, throughput, delay and transmissions with real reports and traffic really routed around suspects, assuming free central route updates.
- **Where is Mohit sir's area-weighted averaging?** Each link's expected forwards use the normal loss of its distance area (near/medium/far), and the relay's trust is the average of its monitors' trust values weighted by the forwards each expected: `sum(O)/sum(E)`. Inside an area we use the median, not the mean: in a single-round test on the tuning networks the mean let an attacker's lossy links look "normal" and cut single-grayhole detection from 96% to 75%; with baseline cleaning in the final pipeline the two perform about the same (`results/ablation.csv`).
- **Why two-round confirmation?** One noisy round should not remove an honest relay from routing. Honest false flags were mostly single rounds; the grayhole is flagged round after round.
- **Why does a suspect stay suspected?** Once traffic avoids it, it produces no new evidence, so silence cannot clear it. An earlier version cleared it and sent traffic back through it 8–9 times per run.
- **Why bundle reports?** A data frame costs the same 1.02 s of airtime with 1 or 6 relays inside. Bundling halved our report traffic, which competes with data on the channel.
- **Is the delivery gain real?** Pooled over all live settings: no defence 45.6% → fixed watchdog 47.0% → CAD-style 48.0% → ours 49.7% delivery, and 186.8 → 190.6 → 193.3 → 200.5 packets/h. On the same topologies ours beats no defence by +1.7 to +5.6 points (significant in 3 of 4 settings) and the fixed watchdog by +4.0 with 2 and 3 grayholes. Against CAD-style ours is ahead in every setting but within the uncertainty; its clear advantage there is detection (95% vs 58% of grayholes caught).
- **Why route around suspects instead of cutting them out?** A grayhole dropping 30% still delivers 70%; cut out, a grayhole that is the only path for many nodes delivers 0% for all of them (topology 205 fell to 4–8%). Blacklisting is shown in fig5(c): there the fixed watchdog's false accusations collapse delivery to 23%.
- **Demo in class:** `./run.sh demo` (about 2 minutes) prints each method's delivery and suspects for one network with three grayholes, then the ranked suspect list.
- **Is this proven for the ocean?** No. It is a static UnetStack protocol-level simulation with stated channel and observer assumptions.

Use `METHOD.md` for the derivation. Start an explanation with the problem, one packet and the three detector steps; discuss experiment orchestration when asked about reproducibility.
