// Diagnostic (calibration topologies only): is the multiple-attacker failure caused by
// attackers contaminating the learned collision baseline? Compares the rule without peeling with
//   oracle - attackers' links left out of the baseline (uses ground truth: diagnosis only)
//   peel   - iterative removal: flag, leave flagged relays out of the baseline, decide again,
//            until no new relay is flagged (at most 3 rounds). Uses no ground truth.
//   peelS  - same, but leave out every relay whose estimated drop is >= S_MIN (flagged or not)
// Run from inside unet/:   ../tools/unet-7.0.0/bin/unet $PWD/diag_contamination.groovy
// Output: ../results/diag_contamination.txt

def runs = [:].withDefault { [] }
new File('../results/evidence_cal.csv').eachLine { line, no ->
  if (no == 1) return
  def f = line.split(',', -1)
  if (f[5] == '-1') runs[f[0..4].join(',')] << Detectors.parseRow(f)
}
def peel = { rows ->
  def r = Detectors.physics(rows, Detectors.OURS)
  for (int round = 0; round < 3; round++) {
    def next = Detectors.physics(rows, Detectors.OURS + [exclude: r.flagged])
    if (next.flagged == r.flagged) break
    r = next
  }
  r
}
// peelS: relays whose estimated drop is >= S_MIN do not help define "normal", flagged or not
def peelS = { rows ->
  def r = Detectors.physics(rows, Detectors.OURS)
  Set out = [] as Set
  for (int round = 0; round < 3; round++) {
    Set suspicious = r.stats.findAll { k, v -> v.n >= Detectors.MIN_OBS && v.score >= Detectors.S_MIN }.keySet() as Set
    if (suspicious == out) break
    out = suspicious
    r = Detectors.physics(rows, Detectors.OURS + [exclude: out])
  }
  r
}
def acc = [:].withDefault { [runs: 0, honest: 0, att: 0, caught: 0] }
runs.each { key, rows ->
  def p = key.split(',', -1)
  Set attackers = p[4] ? p[4].split(';')*.toInteger() as Set : [] as Set
  def variants = ['no peeling': Detectors.physics(rows, Detectors.OURS),
                  oracle: Detectors.physics(rows, Detectors.OURS + [exclude: attackers]),
                  peel: peel(rows), peelS: peelS(rows)]
  variants.each { name, r ->
    def a = acc["${p[0]} ${attackers ? 'attack' : 'none'} $name".toString()]
    a.runs++; a.honest += (r.flagged - attackers).size(); a.att += attackers.size(); a.caught += r.flagged.intersect(attackers).size()
  }
}
def out = new File('../results/diag_contamination.txt')
out.text = 'scenario attack variant: runs, honest flagged per run, grayholes caught\n'
acc.sort { it.key }.each { k, a -> out << sprintf('%-28s runs %2d  honest/run %.2f  caught %d/%d\n', k, a.runs, a.honest / a.runs, a.caught, a.att) }
print out.text

println 'COMPLETE diag_contamination'
