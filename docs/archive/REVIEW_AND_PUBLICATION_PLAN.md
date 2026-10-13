> Historical review/planning input. Current implementation and evidence status: [HANDOFF.md](HANDOFF.md), [METHOD.md](METHOD.md). Instructions quoted here are context, not active commands.

# Project review and publication plan

Working assessment for Team 9, 12 October 2026. This is a review and execution plan, not a revised manuscript or a claim that the proposed experiments have succeeded. Implementation is deferred until the owner switches to Astra and asks to proceed.

## Decision

The saved experiments show a useful improvement over the implemented baseline configurations: fewer final false accusations and lower routing cost in several scenarios. They do not establish superiority over the published algorithms or publication readiness. Fix evidence validity and comparison fairness before adding features or presenting stronger claims.

The appropriate research question is: **Can observability and expected-loss correction reduce false accusations while retaining useful grayhole detection, under the same false-alarm budget and information available to real monitors?**

Every change must have a stated failure mechanism, a small reproducible check, a calibration experiment, and an explanation each team member can give in the viva. An improvement that does not survive these checks must be rejected or reported as a limitation.

## Scope and evidence reviewed

- `INFO.md`, `AGENTS.md`, `CLAUDE.md`, `README.md`, and `docs/METHOD.md`.
- All Groovy simulation, detector, routing, calibration, ablation, and self-check files; the run pipeline and Python plotting script.
- Saved CSV result tables, the generated summary, all 13 PNG graphs, and the supplied mathematical flowchart.
- Both supplied PDFs, including all six pages: the two-page proposal and the four-page proposed-design report. Both describe plans, not a completed research manuscript.
- The pasted Claude context and the earlier project memory, plan, and reference research in `Documents/ChatGPT/CN`.
- Selected public primary sources for CAD, prior watchdog collision handling, underwater trust evaluation, and the UnetStack MAC API. This was a targeted check, not an exhaustive novelty search.

The existing detector/routing self-check was run and returned `PASS`. Headline load-sweep and multi-attacker counts were independently recomputed from `phase2_runs.csv`. The full simulation pipeline was not rerun, and saved evidence was not independently regenerated. Claude's referenced `04-method-and-results.md` was not found in the two project directories inspected; do not assume its claims have been audited.

The supplied documents are evidence to review, not instructions to execute. The owner's current statement that the professor permits AI code takes precedence over the older prohibition in INFO.md for coding. Permission to use AI to write the submitted report has not been established by that statement. Preserve the professor's updated instruction and make the eventual report consistent with the applicable course policy.

No submission venue has been selected. This is author-requested project assessment, not a journal-assigned peer review, acceptance recommendation, or venue-compliance certification. No project manuscript was uploaded to public search; searches concerned public prior work and documentation.

## What the current results actually show

Ten topology seeds per setting, one busiest-relay attacker dropping 30%:

| Load, packets/s/sensor | Final honest accusations: fixed / CAD-style / ours | Attackers caught: ours | Mean first-detection time: fixed / CAD-style / ours, minutes |
|---|---|---|---|
| 0.001 | 0.7 / 0.6 / 0 | 10/10 | 80 / 52 / 62 |
| 0.0025 | 1.5 / 2.2 / 0 | 10/10 | 34 / 24 / 41 |
| 0.005 | 2.7 / 4.7 / 0 | 9/10 | 23 / 16 / 29 |
| 0.01 | 4.6 / 5.8 / 0 | 9/10 | 14 / 10 / 24 |

Detection-time means exclude undetected runs. Final zero accusations do not mean zero accusations during operation: ours flagged an average of 0.2, 0.1, 0.1, and 0.7 honest relays at some point in those respective settings.

- At 10% malicious dropping, ours catches 2/10 attackers.
- With two attackers, ours catches 17/20, with both caught in 7/10 runs.
- With three attackers, ours catches 5/30, with all three caught in 1/10 runs.
- At medium load, estimated dropping is 29.7% for a true 30% and 52.0% for a true 50%. The unfiltered area method also estimates these well (30.7% and 51.9%); the full method is not uniquely superior on this metric.
- Saved AUCs are approximately perfect for CAD-style, the unfiltered area method, and ours. Top-1 ranking also ties across the main one-attacker settings. These plots cannot support a unique ranking advantage.
- Avoidance yields small positive mean PDR differences versus baselines, but the listed paired intervals include zero. Claiming PDR equivalence also requires an explicit equivalence margin; lack of significance is insufficient.
- Avoidance shows promising latency, hop-count, and transmission-count benefits. Recompute uncertainty before retaining significance claims, especially comparisons whose rounded interval endpoints approach zero.
- Strict isolation has much larger PDR gains, but is a separate response policy. It must not substitute for the main avoidance result.
- `tx_per_delivery` counts transmissions per delivered packet. It is an efficiency proxy, not measured modem energy in joules.

**Defensible present conclusion:** the method reduces final accusations compared with these baseline configurations, with detection-speed and weak/multiple-attacker trade-offs, in a static protocol-level simulation.

## Issues to resolve before pursuing stronger results

### 1. The attached flowchart is not the implemented algorithm

The image uses linear distance observability, density and PDR confidence weights, weighted local trust, and an additive dynamic threshold. None of that is the current `Detectors.physics` decision. The code computes channel-informed expected forwards, an area collision baseline, pooled O/E trust, dispersion correction, and three decision conditions.

The image also assumes bounded trust, but O/E can exceed one under sampling variation. Its PDR normalization can be negative below the stated minimum, its threshold needs bounds, and zero denominators are unspecified. Do not add these arbitrary formulas merely to make the implementation match the picture.

Replace it later with a simple original vector diagram of the verified implementation. Include insufficient-evidence output, assumptions, and a separation between ranking, flagging, and optional routing response. Its example scores must be labelled illustrative unless generated from a named run. The decorative diagram's duplicated node labels and dense text also need correction.

### 2. Eligibility is not proven independent of forwarding

`MonitorNode` records every `BadFrameNtf`, and C2 excludes windows containing those events. An attempted forward from B can itself generate a bad frame; a dropped packet generates no such transmission. Therefore the statement in METHOD.md that every condition concerns only other-node events does not establish `P(eligible|forward)=P(eligible|drop)`.

Construct a small controlled counterexample with a relay forward corrupted at the monitor versus a malicious drop, with the same background traffic. Measure eligibility, observed counts, expected counts, and estimated drop bias. Simulator ground truth may verify the experiment but must not enter the detector. Determine whether to change the filter, derive an expectation conditional on selection, or limit the claim. Treat the current unbiasedness lemma as unverified until resolved.

### 3. Multiple monitors do not provide independent packet outcomes

The same packet's receipt at B and B's drop decision affect all monitors. Summing their windows increases `n` but not the number of independent malicious decisions by that factor. The quasi-binomial correction has not been shown to capture this dependence. The proposed-design PDF explicitly cautions against counting repeated reports as independent proof.

Retain packet identifiers in diagnostic evidence and check false-alarm calibration at the relay/run level. Compare pooled decisions against a one-monitor-per-event diagnostic and assess covariance or effective sample size. Use topology seeds as the independent unit for uncertainty in final experimental comparisons. The z-test error rate and detection-time derivation need appropriate dependence assumptions.

### 4. Baseline names and thresholds need a fairness audit

The implemented `cad` is sender-only loss correction with a distance-only normal-loss estimate. The authors' 2010 CAD journal account includes upstream/downstream monitoring and normal-loss estimation for channel impairment and medium-access collisions. It is not enough to give the current simplified baseline the exact distance-loss curve and call it faithful CAD.

Retrieve the cited 2009 version and verify each component against it. Until then use **CAD-style distance-loss baseline**, not a claim to reproduce published CAD. Likewise use **fixed-threshold watchdog inspired by prior work** unless the Chandavarkar-Suresh algorithm has been verified in full.

Keep legacy configurations for context, but calibrate all primary comparators under the same no-attack false-alarm budget, decision frequency, and information access. A threshold sweep is essential because CAD-style ranking is already excellent. Full reproduction of one relevant stronger published detector should precede a state-of-the-art superiority claim; do not add unrelated machine learning for appearance.

Primary reference: [Shila, Cheng and Anjali, 2010](https://ece.iit.edu/~yucheng/YCheng_TWC10.pdf). It is an extended account, not silently interchangeable with the cited 2009 conference version.

### 5. Several robustness statements are too strong

- An attacker can have negative observed z through random variation or a contaminated expected baseline. Negative-z-only dispersion estimation is a robustness heuristic, not immunity to arbitrary attackers. The code falls back to all relays when fewer than two negative-z relays are available.
- A median's 50% breakdown property applies to contaminated observations. Here observations are links, not nodes: a few high-degree attackers can dominate an area's links. Shared packets further reduce independence.
- Leave-self-out falls back to using the entire area if no alternative links exist. Test this sparse case and report the fallback instead of saying a relay's own data can never influence its reference.
- The current three-attacker scenario uses independently dropping relays. It does not implement false reports, coordinated timing, or a shared strategy. Describe it as multiple attackers causing baseline contamination, unless true collusion is added and evaluated.

### 6. Low-rate detection cannot be fixed by duration alone

`S_MIN=10` requires estimated dropping of at least 10%. An unbiased estimate for a true 10% attacker is at the decision boundary; an estimate biased below 10% may never cross it, regardless of duration. Dispersion may also change with time. The approximation `t proportional to 1/p²` is not an established performance guarantee and is not derived for the complete implemented stopping rule.

Test 10%, 20%, and 30% at predeclared durations, with matched false-alarm calibration. Separate statistical evidence from the practical minimum-drop condition. Longer runs are an experiment, not a promise to catch all 10% attackers.

### 7. Phase 3 and overhead are narrower than deployment claims

Phase 3 starts a new simulation using the final suspects from an earlier six-hour run. This measures post-decision routing consequences. It does not charge the operational cost of waiting to detect, transient accusations, reporting packets, or route updates.

For an operational-benefit claim, run detection and response chronologically with no future information. Keep the current post-decision experiment labelled clearly. The 9-byte report estimate omits the channel sums required by v3, framing, identifiers/metadata as needed, routing hops, and delivery cost. Specify an actual v3 report representation or explicitly label overhead as an incomplete analytical estimate.

### 8. Existing plot and reporting defects

- `fig0_phase1.png` is empty. CSV booleans are lowercase, but `plots.py` selects `True` and `False`. Fix the selector and check selected row counts; do not change data.
- Sensitivity captions state z=3 while current code uses z=2. Derive displayed parameters from the run configuration. Historical numbers need versioned provenance.
- The current 95% intervals use 1.96 times SE with only ten runs. Use a justified small-sample interval for run means; binomial intervals for independent one-attacker recall; cluster resampling for multi-attacker/pooled metrics. Ten successes do not establish a true 100% success rate, and zero observed events do not establish zero population risk.
- Report final and ever-flagged false alarms separately. Include all undetected attackers in cumulative detection curves and mark administrative censoring at the simulation horizon.
- Show threshold-dependent decisions at matched false-alarm rates. A score-only ROC does not represent ours' joint score, z, and minimum-evidence decision rule.
- Main plots currently repeat six closely overlapping methods and long legends. Use three primary methods; place versions and component ablations separately. Distinguish methods by markers/line styles as well as color.
- Export vector PDF/SVG later and inspect at manuscript size. Improved aesthetics cannot repair invalid experimental comparisons.

## Execution plan for Astra

### Stage A: establish a trustworthy starting point

1. Preserve current code, raw results, graphs, and parameter history in a versioned checkpoint. Do not overwrite the only existing evidence. Recover the missing evolution notes if available.
2. Write a claim-to-evidence table and an information-access table. Distinguish monitor-observable events, assumed positions/channel models, offline aggregation, and evaluation-only attacker ground truth.
3. Repair the empty plot, threshold labels, energy terminology, baseline labels, and final-versus-ever-flagged wording.
4. Add small event-level checks for corrupted-forward selection, repeated-monitor correlation, window timing/late forwarding, sparse-area fallback, and dispersion fallback. Resolve central failures before expensive simulations.

**Exit check:** every formula and headline claim has a matching code path and evidence source; detector inputs do not use attacker labels or hidden simulator events; existing tests and new mechanism checks pass. No claim of an unbiased estimator remains without justification.

Affected scope: `MonitorNode.groovy`, `Detectors.groovy`, self-checks/diagnostic simulations, evidence format and readers if needed, `METHOD.md`, `plots.py`, README, report sources, and the replacement diagram. Changes to evidence require coordinated updates to phase2, detect, ablation, and plotting; do not patch one caller alone.

### Stage B: make the comparisons scientifically fair

1. Audit the published comparator definitions and document adaptations. Implement one stronger relevant comparator if its required information is actually available.
2. Select the primary no-attack false-alarm budget and detection horizon before evaluating attacks. A provisional design target is at most 5% of honest judged relays ever flagged during six hours; confirm its practical meaning before freezing it. Also report probability of any false accusation per network and judged-node coverage.
3. Calibrate thresholds using development/calibration seeds only, giving each method the same tuning budget. Keep test seeds 1-10 as development evidence because they have already influenced changes.
4. Compare recall, precision, and time at equal false-alarm budgets. For ablation, hold the expectation model and decision procedure fixed while changing only C1-C4 eligibility. Separately ablate the expected-loss model and dispersion estimator.

**Exit check:** differences cannot be explained solely by different thresholds, monitor coverage, or unavailable information. A tie or baseline win is retained in the report.

### Stage C: run the smallest experiments that can establish improvement

| Experiment | Justification | Controlled design | Required output |
|---|---|---|---|
| Monitor traffic heterogeneity | Tests when observable evidence differs across monitors | Uniform plus prespecified hotspot strengths; keep total offered traffic constant for the main comparison, with a separate increased-load check | Full method versus the same method without filtering: recall, ever false alarms, eligibility and detection time |
| Channel/position mismatch | Tests dependence on knowing the simulator's exact loss curve | Nominal plus prespecified errors in loss curve or positions; identical comparator information | Degradation curves and honest-node calibration |
| Weak attacks | Tests the 10% score boundary and claimed time scaling | 10/20/30% attacks at predeclared 6/12/24-hour simulated horizons; thresholds frozen first | Fraction detected by time, undetected share, false alarms, estimates; theory overlay only if assumptions survive |
| Multiple attackers | Tests contamination of learned normal loss | 0/1/2/3 attackers; report attack share of links in each area | Per-attacker recall, all-attacker success, false alarms, estimation error |
| CSMA validation | Tests whether conclusions survive a less ideal access pattern | Existing unscheduled model plus one supported CSMA configuration | Same detector comparisons, timing checks, and calibration |
| Online response | Tests end-to-end benefit after paying detection delay | Chronological monitoring, reporting and route response for all methods | Delivery over the whole run, latency, route changes, control traffic, transmissions/delivery |

Hotspots and CSMA may increase or reduce the filter's benefit; no trend is promised. Adding a CSMA agent alone is insufficient because the current node transmits directly via `TxFrameReq`. A client must reserve the channel and wait for `START`; recalibrate forwarding windows and check refusals/queues. Use installed examples and APIs rather than inventing a MAC. Keep the contribution at the network layer; MAC is an evaluation condition.

Reference: [official UnetStack MAC service](https://unetstack.net/handbook/307_mac.html).

For multiple attackers, first test a reference learned from separate verified-benign calibration runs to diagnose contamination. This is a diagnostic with an explicit benign-start assumption, not a free deployment solution. Consider iterative removal only if the failure mechanism is demonstrated. Remove nodes only after a calibrated decision, use a bounded stopping rule, and test cascade false alarms on no-attack data. Never repeatedly remove the highest-ranked node until the expected result appears.

**Exit check:** adopt a change only if it improves a declared metric at a matched false-alarm budget without hiding recall or delay harm. If filtering adds little, frame the contribution around corrected forwarding expectations and report that result. Fixing every threat is unnecessary for a narrowly scoped paper.

### Stage D: freeze and evaluate on untouched networks

1. Freeze code, simulator/runtime versions, parameter configuration, baselines, analysis, exclusions, primary outcomes, and figure definitions.
2. Use untouched seeds, provisionally 201-220 after checking they have not been used. Choose sample size from desired precision and development variability; twenty runs are a starting point, not a publication rule. Use extra runs if intervals remain too wide.
3. Run paired scenarios with the same topologies and traffic design. Separate random streams for topology, traffic, channel, and attacks where supported: reusing one root seed does not guarantee identical packet-level randomness after routes or attack decisions change.
4. Store a run manifest, script/version identifiers, parameters, data hashes, successful/failed jobs, and analysis outputs. Make parallel runs fail visibly rather than silently merging missing/incomplete outputs. Pin analysis dependencies.
5. Compute paired effects and uncertainty using the independent topology/run structure. Predeclare primary comparisons or account for multiplicity; retain all planned settings.

**Exit check:** a clean native macOS run reproduces the tables and graphs, all expected jobs are present, and each claim reports its effect size, uncertainty, scope and trade-offs. If test results motivate changes, label the set development data and reserve a new test set.

### Stage E: prepare the paper and viva from verified evidence

1. Select the venue and article type, then verify its current scope, reproducibility, figure, and AI policies. Publication acceptance cannot be guaranteed by simulation results.
2. Update the abstract to state the implemented method, evaluation conditions, comparator status, main verified improvement and principal limitation. The proposal's future-tense objectives and broad novelty sentence are not a completed research abstract. Remove the duplicated `[3]` citation and resolve tool/report differences: current plots use Python, not MATLAB.
3. Build a literature matrix covering watchdog ambiguity, receiver collisions, channel-aware loss estimation, underwater trust, correlated observations and baseline contamination. Separate common ingredients from the specific verified contribution.
4. Draw an original architecture diagram and algorithm from code. Present uncertainty, insufficient evidence, administrator ranking, and optional response explicitly. Remove personal contact details from the public manuscript.
5. Assemble a viva sheet: one packet timeline, one hand-calculated trust score and decision, why each threshold was chosen, how natural loss differs from malicious dropping, the ablation outcome, and two cases where the method fails.
6. Provide a small deterministic demo plus a full regeneration command and environment/setup instructions for the actual lab platform. The lab machine's OS/tool availability remains to be confirmed; do not claim it was tested.

Prior watchdog work already addresses collision ambiguity: [Watchdog Alert/Confirmation, 2007](https://www.inderscience.com/info/e_inarticle.php?artid=13793), and [E-watchdog, 2019](https://journals.sagepub.com/doi/10.1177/1550147718824008). This is enough to reject an unsupported universal novelty claim; full-text comparison is still needed to establish the narrower gap.

ATEM's published setup uses MATLAB, 100 nodes, a three-dimensional deployment and mobility. Its plotted percentages cannot be directly ranked against our static 20-sensor runs: [Liu et al., 2024](https://pmc.ncbi.nlm.nih.gov/articles/PMC11086150/). Use it for positioning unless a faithful shared-condition comparison is feasible.

## Figure set to aim for

| Figure | Scientific purpose | Status |
|---|---|---|
| Actual detector workflow and packet timeline | Makes the algorithm defendable | Replace attached conceptual diagram after verification |
| Recall versus ever false-alarm rate | Fair primary comparison; show selected operating points | Needs calibrated threshold sweep |
| False accusations versus load, attack/no attack | Shows the existing strongest result | Existing data; correct wording and uncertainty |
| Cumulative detection by time at 10/20/30% dropping | Shows speed and missed attacks together | Snapshot evidence for existing runs; longer runs only if justified |
| Paired network-performance effects | Quantifies benefit and uncertainty | Existing post-decision data plus online validation |
| Component ablation under uniform/hotspot traffic | Establishes what actually causes improvement | Existing ablation plus controlled heterogeneity experiment |
| Multiple-attacker and model-mismatch robustness | Defines the boundary of the claim | Additional controlled experiments |

Keep topology/example scores and detailed version history in supporting material. Keep isolation separate from avoidance. Repair Phase 1 or omit it with justification; never include an empty result figure. If all primary methods tie on ranking, the ROC plot belongs in supporting material, not as evidence of superiority.

## Priorities and stopping rules

Before the 22 October progress review: prioritize Stages A and B, one small controlled mechanism demonstration, a consistent abstract/diagram, and a runnable lab demo. Before the 2 November final deadline: prioritize controlled improvement experiments, frozen fresh tests, and evidence-backed reports. Exact compute time is unmeasured; Claude's hour estimates are not commitments. INFO.md incorrectly labels 2 November 2026 as Thursday; it is Monday. Verify the actual course deadline separately before changing its date.

Do not add mobility, neural models, arbitrary density weights, complex consensus, or a new simulator solely to create a more impressive graph. Honest-monitor and static-network assumptions can remain if the paper's claim is narrowed accordingly.

The publication gate is evidence of a specific improvement over a fair relevant comparator, credible novelty positioning, valid uncertainty, practical information requirements, and reproducibility. Strong course results do not automatically satisfy that gate. Failure to dominate every metric does not make the project a failure; unresolved selection bias or unfair comparisons would make its stronger claims indefensible.

Only this plan was added during the review. No simulation implementation, thresholds, saved datasets, graphs, abstract, or existing project report was changed.
