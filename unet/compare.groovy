// Pipeline step 2: compare the detectors on the SAME recorded evidence (record.groovy).
//
// For every run it replays the sink's 30-min decision loop exactly as the live network
// (network.groovy) runs it:
//   detector -> two-round confirmation (Detectors.confirm); confirmed suspects stay suspects,
//   and for ours they also leave the "normal loss" baseline of later rounds.
// Two threshold rules per method:
//   own     - the setting each method would be deployed with: fixed watchdog miss rate > 30%,
//             CAD-style z > 2, ours alpha chosen on the tuning networks (= its budget value)
//   budget  - every method held to the same false-alarm budget: the loosest threshold whose
//             confirmed honest suspects stay <= 5% of honest judged relays on the no-attack
//             tuning runs (topologies 101-106); frozen, then applied to the test runs
//
// Run from inside unet/:   ../tools/unet-7.0.0/bin/unet $PWD/compare.groovy
// Output: ../results/thresholds.csv     method, own and budget thresholds
//         ../results/compare_runs.csv   one row per test run, method and rule
//         ../results/suspect_scores.csv final ranking of every relay, every test run (ours, CAD-style)
//         ../results/suspect_list.txt   the administrator's ranked list for one 3-grayhole run
//         ../results/ablation.csv       the method with one part removed, at the same budget

def BUDGET = 0.05
def SCENARIOS = ['load', 'drop', 'two', 'three', 'near', 'hotspot', 'large', 'sparse']
def OWN = [fixed: Detectors.FIXED_THRESHOLD, cad: Detectors.Z_FIXED]        // published settings
def SWEEP = [   // loose -> strict; the first value meeting the budget is the loosest
  fixed: [0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0],
  cad  : [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10, 15, 20, 50, Double.POSITIVE_INFINITY],
  ours : [0.05, 0.01, 1e-3, 1e-4, 1e-5, 1e-6, 1e-8, 1e-10, 0d],
]

/** run key -> [snapshot index -> evidence rows]; index -1 (end of run) is not a decision round. */
def load = { String suffix ->
  def runs = [:].withDefault { [:].withDefault { [] } }
  new File("../results/evidence${suffix}.csv").eachLine { line, no ->
    if (no == 1) return
    def f = line.split(',', -1)
    if (f[0] in SCENARIOS && (f[5] as int) >= 0) runs[f[0..4].join(',')][f[5] as int] << Detectors.parseRow(f)
  }
  runs.collect { key, snaps ->
    def p = key.split(',', -1)
    [scenario: p[0], load: p[1], drop: p[2], seed: p[3],
     attackers: p[4] ? p[4].split(';')*.toInteger() as Set : [] as Set,
     rounds: snaps.keySet().sort().collect { snaps[it] }]
  }
}

/** Replay the sink's decision loop for one run; returns the outcome and the last round's ranking. */
def replay = { String method, double th, Map run ->
  Set suspects = [] as Set, previous = [] as Set, judged = [] as Set
  Integer detected = null
  def last = null
  run.rounds.eachWithIndex { rows, i ->
    last = Detectors.decide(method, rows, th, suspects)
    judged.addAll(last.stats.findAll { k, v -> v.n >= Detectors.MIN_OBS }.keySet())
    suspects = Detectors.confirm(suspects, previous, last.flagged as Set)
    previous = last.flagged as Set
    if (detected == null && run.attackers && suspects.containsAll(run.attackers)) detected = (i + 1) * 30
  }
  [caught: suspects.intersect(run.attackers).size(), honest: (suspects - run.attackers).size(),
   judged: (judged - run.attackers).size(), detected: detected, suspects: suspects, last: last]
}

def tuning = load('_cal').findAll { it.scenario == 'load' && !it.attackers }   // no-attack tuning networks
def test = load('')
assert tuning && test && tuning*.seed.intersect(test*.seed).isEmpty(): 'Tuning and test topologies must differ'

/** The loosest threshold whose honest suspects stay within BUDGET on the no-attack tuning networks. */
def tune = { String method, List values ->
  def chosen = values.find { th ->
    def r = tuning.collect { replay(method, th, it) }
    double rate = r.sum { it.honest } / Math.max(1, r.sum { it.judged })
    println sprintf('tuning %-11s threshold %-8s honest suspects %.3f', method, th, rate)
    rate <= BUDGET
  }
  assert chosen != null: "No $method threshold meets the budget"
  return chosen
}

// ---- thresholds: every method gets the same budget on the tuning networks
def thresholds = SWEEP.collectEntries { method, values ->
  def chosen = tune(method, values)
  [method, [own: OWN[method] != null ? OWN[method] : chosen, budget: chosen]]
}
new File('../results/thresholds.csv').text = 'method,own,budget\n' +
  thresholds.collect { m, t -> "$m,${t.own},${t.budget}" }.join('\n') + '\n'

// ---- test runs
def out = new File('../results/compare_runs.csv')
out.text = 'scenario,load,drop,seed,n_attackers,method,rule,threshold,caught,honest_suspects,honest_judged,detect_min\n'
def scores = new File('../results/suspect_scores.csv')
scores.text = 'scenario,load,drop,seed,method,node,score,trust,windows,monitors,attacker,suspect\n'
def example = test.findAll { it.scenario == 'three' }.min { it.seed as int }
def exampleResult = null
test.each { run ->
  ['fixed', 'cad', 'ours'].each { method ->
    def byThreshold = [:]   // ours uses the same threshold for both rules: replay once
    ['own', 'budget'].each { rule ->
      double th = thresholds[method][rule]
      def r = byThreshold[th] = byThreshold[th] ?: replay(method, th, run)
      out << [run.scenario, run.load, run.drop, run.seed, run.attackers.size(), method, rule, th, r.caught,
              r.honest, r.judged, r.detected != null ? r.detected : 'NA'].join(',') + '\n'
      if (rule == 'own' && method != 'fixed') {
        r.last.stats.each { b, v ->
          scores << [run.scenario, run.load, run.drop, run.seed, method, b, v.score, v.trust != null ? v.trust : '',
                     v.n, v.monitors != null ? v.monitors : '', b in run.attackers ? 1 : 0, b in r.suspects ? 1 : 0].join(',') + '\n'
        }
      }
      if (run.is(example) && method == 'ours' && rule == 'own') exampleResult = r
    }
  }
}

// ---- what each part of our method contributes: remove one part, tune it to the same budget
def abl = new File('../results/ablation.csv')
abl.text = 'variant,alpha,scenario,detected,attackers,honest_suspects,honest_judged\n'
[['full method', 'ours'], ['no observability filter', 'area'], ['no baseline cleaning', 'ours-nopeel'],
 ['area average instead of median', 'ours-mean']].each { label, method ->
  def chosen = tune(method, SWEEP.ours)
  test.findAll { it.scenario in ['load', 'two', 'three', 'hotspot'] }.groupBy { it.scenario }.each { scenario, runs ->
    def r = runs.collect { replay(method, chosen, it) }
    abl << [label, chosen, scenario, r.sum { it.caught }, runs.sum { it.attackers.size() }, r.sum { it.honest }, r.sum { it.judged }].join(',') + '\n'
  }
}

// ---- the administrator's ranked list for one run with three grayholes
def lines = ["Ranked suspect list (ours) - topology ${example.seed}, 3 grayholes dropping 30%, load ${example.load}, after 6 h",
             '', String.format('%-5s %-5s %-15s %-7s %-8s %-9s %-10s %s', 'rank', 'node', 'est. drop (%)', 'trust', 'windows', 'monitors', 'decision', 'truth')]
exampleResult.last.rank.eachWithIndex { e, i ->
  def v = exampleResult.last.stats[e[0]]
  lines << String.format('%-5d %-5d %-15.1f %-7.2f %-8d %-9d %-10s %s', i + 1, e[0], v.score, v.trust, v.n, v.monitors,
                         e[0] in exampleResult.suspects ? 'SUSPECT' : '-', e[0] in example.attackers ? 'grayhole' : 'honest')
}
new File('../results/suspect_list.txt').text = lines.join('\n') + '\n'
println lines.join('\n')
println 'COMPARE_COMPLETE'
