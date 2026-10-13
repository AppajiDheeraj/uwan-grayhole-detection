# Method, assumptions and derivation

The main detector is `Detectors.run('ours')`: C1+C3+C4 filtering, the area-based expectation, baseline cleaning for several grayholes, and the dynamic threshold. Thresholds are in `results/thresholds.csv`. The code and this explanation describe a simulation prototype, not a validated field deployment.

## 1. What problem do we solve?

A watchdog can miss a forward because a relay dropped the packet, because the packet never reached the relay, or because the monitor missed the relay's transmission. We estimate how many forwards an honest relay should have shown, then rank relays whose observed count is lower. A high score is evidence for investigation, not proof of malicious intent.

The network layer supplies the forwarding problem and response. Physical-layer notifications supply observations. UnetStack 7 is central; Python only analyses its CSV output.

## 2. What information is available?

| Input | Use and limitation |
|---|---|
| Own transmissions, decoded/overheard frames, corrupted-frame notifications, busy time | Locally observed at each monitor |
| Static node positions and sound speed, 1500 m/s | Assumed known; localisation errors are not evaluated |
| Distance loss curve `e(d) = 0.15 (d/1500)^2` | Assumed known by the proposed detector and CAD-style baseline; misspecification is a supporting test |
| All monitor counters (recorded runs) | Offline comparison of the detectors on exactly the same evidence; no communication cost is inferred from it |
| Counters actually delivered to the sink (live network) | Real report payloads, plus the sink's own observations; lost reports supply no evidence |
| Attacker identity and actual drops | Evaluation/diagnostics only; never passed to a detector |
| Full connectivity for route changes | Ideal central controller; route-update messages and localisation costs are not simulated |

Nodes are static; monitors report truthfully, including compromised forwarders. Attackers independently drop relayed packets with probability p. This is not a coordinated collusion or dishonest-report attack. Topologies are redrawn until connected. Findings therefore concern connected networks under this protocol model, not arbitrary deployments or ocean conditions.

## 3. How is a forwarding opportunity observed?

Let M be the monitor, A the sender, B the relay, and P the packet. Packet identity is `(origin, sequence)`. For frame duration `Tf`, sound speed `c`, and distance `d`, when M receives the end of A's frame at time t:

```
tA = t - d(A,M)/c                  (tA = t when M itself sent P)
IB = [tA + d(A,B)/c - Tf, tA + d(A,B)/c]
W  = [end(IB) + d(B,M)/c, end(IB) + Tw + Tf + d(B,M)/c]
```

`IB` is P's arrival interval at B. `W` is the expected observation window for B's forward. There is no link-layer ACK confirming receipt at B; upstream loss is part of the expected-loss model.

`Tw=2 s` without MAC coordination. The measured forwarding delays had a 50 ms 99th percentile and 1.12 s maximum forwarding delay. This is an allowance, not a guaranteed queueing bound (`unet/forward_delay.groovy`, `results/forward_delay.txt`). The 200 ms expiry ticker and 50 ms grace can extend a watch by up to 250 ms. The simulation data frame is about 1.02 s.

## 4. Which windows count?

Apply the same conditions to heard and missed forwards:

| Condition | Locally observable reason to exclude a window |
|---|---|
| C1 | M transmitted during W and was half-duplex deaf |
| C3 | M heard B transmit during P's arrival interval at B |
| C4 | A transmission known to M would overlap P's arrival at B |

For C4, shift a known transmission at node C by `d(C,B)/c`. Include M's own transmissions and cleanly decoded transmissions from nearby C, excluding A and B. Hidden or undecodable interference remains unknown.

An eligible window contributes either **HEARD** or **SILENT**. An excluded window is **BLIND/unknown** and contributes to neither count. The 32 counter bins retain sender/overhear status, each eligibility condition and the outcome, so ablations can reuse the evidence.

**C2 (a corrupted frame at M) is deliberately excluded from the filter.** B's attempted forward can itself produce that event; a drop produces no transmission. Rejecting such windows selectively removes forwarding attempts. The original calibration diagnostic found C2 pass rates 87.7% for forwarding attempts versus 98.4% for malicious drops, conditional on C1+C3+C4. The diagnostic output is kept in `results/diag_mechanisms.txt` (its script, removed in the simplification, is in git history).

C1+C3+C4 are observable exclusions, not a theorem of unbiased sampling. Decoding the interference used by C4 and later traffic can depend on forwarding outcomes. Small observed bias in a few calibration networks does not prove independence in general.

## 5. What is the derived formula?

For each eligible window w, approximate the channel-only chance of hearing an honest forward:

```
gw = (1 - e(dAB)) (1 - e(dBM))
```

For each monitor–relay link i, count `ni` windows and `Oi` heard forwards, and sum `Gi = sum(gw)`. The residual loss estimate is:

```
kappa_i = max(0, 1 - Oi/Gi)
```

Group links into near, medium and far distance tertiles. For each judged relay B, estimate an area's residual loss by the median of the other relays' link estimates in that area. Then:

```
Ei = Gi (1 - kappa_area)
T_B = sum(Oi) / sum(Ei)
S_B = 100 max(0, 1 - T_B)
```

**Derivation:** if the honest forward expectation E is correct and independent malicious dropping retains each received packet with probability `1-p`, then `E[O] = (1-p) E`. Hence `1-O/E` estimates p under that model. Because E is estimated from other links, this is a model-based estimator, not a general unbiasedness result. Residual kappa absorbs unmodelled reception losses; it is not a direct measurement of collisions alone.

Example: 100 windows, channel expectation 90, residual loss 10% → expected forwards `E=81`. Hearing 56 gives trust `56/81=0.691` and estimated drop `30.9%`. The self-check verifies this calculation. Trust can exceed one through sampling variation; the suspiciousness score then remains zero. If E is zero, the relay is unjudgeable, rather than assigned infinite or undefined trust.

**Mohit sir's area-weighted averaging.** It appears in two places:

1. *Per area:* every link's expected forwards use the normal loss of its distance area (near, medium or far), not one network-wide value.
2. *Across monitors:* trust is the weighted average of each monitor's own trust, weighted by how many forwards that monitor expected: `T_B = sum_i Ei (Oi/Ei) / sum_i Ei = sum(Oi)/sum(Ei)`. A monitor with more evidence counts more, and nearby, far-away and medium links each enter with their own area's expectation.

Inside an area we use the **median** of the other relays' link losses rather than their weighted mean, because the median ignores a minority of bad links. In a single-round test on the tuning networks (before confirmation and baseline cleaning), the weighted mean lowered single-grayhole recall from 96% to 75%: the attacker's own lossy links pull the area's "normal" loss up and hide it. In the final pipeline, where cleaning already removes suspicious links, the two give similar detection (`results/ablation.csv`, row "area average instead of median"). The first design's fixed near/medium/far multipliers (0.5/1/1.5) are no longer used: the expected forwards already depend on distance, so a hand-set weight is not needed.

## 6. Several grayholes: clean the baseline

A median resists a minority of bad **links**, not necessarily a minority of nodes. With two or three busy grayholes in one area, the area's "normal" loss rises, honest relays look *better* than expected (strongly negative z), the over-dispersion factor phi (section 7) grows to 50–300, and every grayhole's z is divided by it. The grayholes still **rank first** with 25–40% estimated drop while honest relays sit near 0%, but none crosses the threshold.

The detector therefore cleans its baseline (`Detectors.peel`): relays whose estimated drop is at least 10%, and relays already confirmed as suspects, leave the area median, and the estimate is repeated (at most 3 times, until stable). Replaying the live decision loop on the calibration networks only, this raised three-grayhole detection from 4/18 to 13/18 and two-grayhole detection from 9/12 to 12/12, for 0.29 instead of 0.21 honest suspects per no-attack run. The version without cleaning is kept as the `ours-nopeel` ablation.

An earlier checkpoint rejected cleaning because, judged one 30-min round at a time at a 5% per-round budget, it needed a very strict threshold. In the pipeline a suspect needs two flagged rounds in a row, which removes most single-round noise, so the cleaning step no longer costs single-grayhole detection.

## 7. How does ranking become a flag?

A pooled deviation score is:

```
zB = (sum(Ei)-sum(Oi)) / sqrt(sum(Ei * max(0.01, 1-Ei/ni)))
phi = max(1, median(zB^2 for judged relays with zB<0) / 0.455)
z* = upper_normal_quantile(alpha/k), k = number of judged relays
flag B if nB >= 20, zB/sqrt(phi) > z*, and S_B >= 10
```

Only links with at least five windows enter the proposed estimator. With fewer than two negative-z relays, phi uses all judged relays. This is a heuristic scale correction; attackers can influence it. The `0.01` variance floor is a numerical safeguard matching the code.

Several monitors watch the same packet. Their outcomes are correlated. The updated diagnostic compares pair misses with the marginal miss probability of those same pairs; it includes heterogeneous links and is descriptive. Phi does not establish independence or correct Gaussian tails. Consequently alpha is a tuning parameter, **not a proven false-alarm probability**, and the Bonferroni-shaped rule is not a guarantee over repeated rounds.

Ranking uses S; flagging also needs exposure and deviation evidence. A relay can rank first without being flagged. The 10% score floor makes 10%-drop attacks difficult; it does not mathematically make their detection impossible, since noisy estimates can exceed the floor.

## 8. What is the fair comparison?

| Comparator | Information and decision |
|---|---|
| Fixed watchdog | Sender watches its next hop; all windows; worst sender's miss fraction exceeds a threshold |
| CAD-style | Sender windows; normal loss `pr=1-(1-e(d))^2(1-po)^2`, `po=1-(1-busy)^2`; pooled deviation exceeds a threshold |
| No-filter variant | Same proposed estimator with every window eligible |
| Frozen-loss variants | Filter on/off, but one constant observed-loss cutoff replaces expected-loss and deviation decisions |

The CAD-style formula is a local ALOHA-inspired adaptation of the channel/collision idea in Shila et al. (2010), sections V–VI. Its monitor busy ratio is only a proxy for both reception locations. It omits the published algorithm's upstream monitoring, PROBE/ACK exchange and signed reports. Our fixed watchdog does **not** reproduce the Chandavarkar–Suresh destination-timeout detector.

`unet/compare.groovy` replays the sink's real decision loop (detector, two-round confirmation, confirmed suspects stay) for every method on the same recorded counters. Two rules are reported:

- **own rule:** each method as it would be deployed: fixed watchdog miss rate > 30%, CAD-style z > 2, ours with its alpha chosen on the calibration networks;
- **same budget:** every method's threshold is the loosest value whose confirmed honest suspects stay at most 5% of honest judged relays on the no-attack calibration runs (topologies 101–106), then frozen and applied to the test topologies 201–210 (large network 201–205).

Topologies, not packets or monitors, are the uncertainty unit. Paired network comparisons match topology seeds; reporting and routing change random draws, so packet-level streams need not stay identical.

## 9. What does the live network charge?

At each 30 min checkpoint, a monitor freezes its evidence and bundles it into one report frame: a 12-byte header (the 8-byte packet header plus a 32-bit busy fraction) and one 17-byte entry per watched relay (relay ID, two 32-bit counts, one 64-bit expected-forward sum). Up to 6 entries (114 bytes) fit the simulator's 128-byte DATA frame, and a frame takes the same 1.02 s of airtime whatever it carries. Baselines send sender-window summaries (usually one relay); ours sends eligible-window summaries for every relay the monitor overhears. Bundling roughly halved our report transmissions; the same rule applies to every detector.

Packets are jittered over ten minutes, transmitted, relayed and dropped on the same channel as data. The sink updates a link only when its report arrives. Older/duplicate reports do not accumulate counts. The sink keeps the latest delivered report of every link: counts are cumulative, so a lost report only delays evidence, and a later report recovers it. (An earlier version discarded reports older than 60 min. With about 40% report delivery, the grayhole's evidence kept disappearing, and it was flagged and cleared 8–9 times per run.) Its own observations need no radio report. Falsified counters, authentication and retransmission are outside scope.

Every 30 min the sink applies its detector, then **two-round confirmation** (`Detectors.confirm`, the same for every detector): a relay becomes a suspect only when flagged in two consecutive rounds, so a single noisy round accuses nobody. A suspect stays one until an administrator reviews it, because once traffic avoids it, it produces no new evidence that could clear it. The response is the same for every method: suspects stop being used as relays wherever another path exists, and nodes with no other path keep theirs (a 30%-lossy path still delivers 70%). Wrongly avoided honest relays force detours: longer, more collision-prone paths. We first tried full **isolation** (blacklisting, `POLICY=isolate`); it disconnected every node behind a correctly caught grayhole that was their only path (delivery fell to 4–8% on topology 205), so avoidance is the response used for all methods. The isolation runs are kept in `results/isolate_runs/`. For ours, confirmed suspects also leave the "normal loss" baseline of later rounds (section 6). **Route distribution is instantaneous and free in this model.** Report traffic, delay and losses are simulated; claiming that every deployment cost is paid would be false.

Report whole-run honest-source delivery, latency among delivered packets, transmissions per delivered honest packet, report delivery and false accusations. Exclude the designated attacker's own data in all five cases, including its no-attack twin, to keep the source cohort fixed. Latency is conditional on delivery; lower delay can arise by losing slow packets. Transmission counts are not energy in joules. Packets still in flight at the end are undelivered within the horizon.

## 10. What claims are allowed?

Quote numbers from `results/summary.md`, which `./run.sh` regenerates from the recorded runs. The demo (`./run.sh demo`) is one network, not the test result. A missing or nonsignificant difference does not establish equivalence. Multiple plotted comparisons are exploratory; do not claim family-wise statistical significance from unadjusted intervals.

The project can demonstrate improvements over its implemented comparators in named scenarios. It cannot establish superiority over the full published CAD or professor's algorithm, nor universal novelty, field performance or resistance to dishonest monitors. An overall improvement must include recall, false alarms and network costs together, with failures visible.
