// Dashboard data: a few example topologies, the three detectors side by side. Not part of the
// pipeline: run it when you want the dashboard (./run.sh dashboard).
// Replays the sink's 30-min decision loop on the recorded evidence exactly as compare.groovy
// does and keeps, for every relay, what each detector saw: its windows, the monitors that
// watched it and the verdict. It changes no result file and asserts its outcome matches
// compare_runs.csv.
//
// Run from inside unet/:   ../tools/unet-7.0.0/bin/unet $PWD/dashboard.groovy
// Input:  ../results/evidence.csv, thresholds.csv, compare_runs.csv, network.csv
// Output: ../dashboard/data.js

import com.google.gson.Gson   // bundled with UnetStack; groovy-json is not

// examples where ours is clearly better on both the replay and the live network; the other
// topologies are in results/summary.md
def EXAMPLES = [[scenario: 'three', seed: '203'], [scenario: 'two', seed: '203'], [scenario: 'two', seed: '204']]
def METHODS = ['fixed', 'cad', 'ours']
def RULE = [fixed: 'sender', cad: 'sender', ours: 'filter']   // which windows each method counts

def csv = { String name ->
  def lines = new File("../results/$name").readLines()
  def head = lines[0].split(',', -1)
  lines.drop(1).collect { l -> def f = l.split(',', -1); (0..<head.size()).collectEntries { [head[it], f[it]] } }
}
def own = csv('thresholds.csv').collectEntries { [it.method, it.own as double] }
def keys = EXAMPLES.collect { "${it.scenario},${it.seed}".toString() } as Set

// scenario,seed -> [attackers, snapshot -> rows]
def runs = [:].withDefault { [attackers: null, snaps: [:].withDefault { [] }] }
new File('../results/evidence.csv').eachLine { line, no ->
  if (no == 1) return
  def f = line.split(',', 7)
  def key = "${f[0]},${f[3]}".toString()
  if (!(key in keys) || f[2] != '0.3' || (f[5] as int) < 0) return
  runs[key].attackers = f[4].split(';')*.toInteger().sort()
  runs[key].snaps[f[5] as int] << Detectors.parseRow(line.split(',', -1))
}
def live = csv('network.csv')
def expected = csv('compare_runs.csv').findAll { it.rule == 'own' && it.drop == '0.3' }

def out = EXAMPLES.collect { ex ->
  def run = runs["${ex.scenario},${ex.seed}".toString()]
  def rounds = run.snaps.keySet().sort().collect { run.snaps[it] }
  def topo = new Topology(20, ex.seed as long)
  def routes = topo.routes([] as Set)
  def relays = [:].withDefault { [node: it, grayhole: it in run.attackers] }
  def methods = METHODS.collectEntries { method ->
    Set suspects = [] as Set, previous = [] as Set
    def log = [], last = null
    Integer detected = null
    rounds.eachWithIndex { rows, i ->
      last = Detectors.decide(method, rows, own[method], suspects)
      Set before = suspects
      suspects = Detectors.confirm(suspects, previous, last.flagged as Set)
      previous = last.flagged as Set
      if (detected == null && suspects.containsAll(run.attackers)) detected = (i + 1) * 30
      if (suspects != before) log << [min: (i + 1) * 30, confirmed: (suspects - before as List).sort()]
    }
    last.stats.each { b, v ->
      def watchers = rounds[-1].findAll { r -> r.b == b && Detectors.count(r.c, RULE[method])[0] >= (method == 'ours' ? Detectors.MIN_ROW : 1) }*.m.sort()
      relays[b][method] = [score: v.score, trust: v.trust, windows: v.n, judged: v.n >= Detectors.MIN_OBS,
                           monitors: method == 'fixed' ? [v.monitor] : watchers, suspect: b in suspects]
    }
    def caught = suspects.intersect(run.attackers).size(), honest = (suspects - run.attackers as List).sort()
    def want = expected.find { it.scenario == ex.scenario && it.seed == ex.seed && it.method == method }
    assert want.caught as int == caught && want.honest_suspects as int == honest.size(): "$ex $method differs from compare_runs.csv"
    [method, [threshold: own[method], caught: caught, honest: honest, detected: detected, log: log]]
  }
  def pdr = live.findAll { it.grayholes == "${run.attackers.size()}" && it.load == '0.005' && it.seed == ex.seed }
                .collectEntries { [it.case, it.pdr as double] }
  [seed: ex.seed, grayholes: run.attackers, methods: methods, delivery: pdr, rounds: rounds.size(),
   relays: relays.values().sort { -(it.ours?.score ?: -1) },
   nodes: topo.pos.collect { a, xy -> [id: a, x: xy[0], y: xy[1], nextHop: routes[a]] }]
}

def data = [range: Topology.RANGE, sink: Topology.SINK, minObs: Detectors.MIN_OBS, examples: out]
new File('../dashboard').mkdirs()
new File('../dashboard/data.js').text = 'window.DATA = ' + new Gson().toJson(data) + ';\n'
out.each { t -> println "topology ${t.seed}, grayholes ${t.grayholes}: " + METHODS.collect { "$it caught ${t.methods[it].caught}, honest ${t.methods[it].honest}" }.join('; ') }
println 'DASHBOARD_COMPLETE'
