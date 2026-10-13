// Pipeline step 1: record every monitor's evidence counters. Detection is applied
// afterwards by compare.groovy on exactly the same counters, so all methods see the same
// traffic, topology and attacks.
//
// Run from inside unet/:   ../tools/unet-7.0.0/bin/unet $PWD/record.groovy
//   SEEDS=201,202 (default 201..210: untouched final test set), SCENARIOS=load,weak, PART=_x
// Output: ../results/evidence<PART>.csv    scenario,load,drop,seed,attackers,snapshot,m,b,d,busy,
//                                          c0..c31 (counters), g0..g31 (channel-expected forwards),
//                                          s0..s31 (channel-loss sums); snapshot -1 = end of run,
//                                          0, 1, ... = every 30 min (one decision round each)
//         ../results/sims<PART>.csv one row per run: delivery ratio, eligible share
//         ../results/topology_seed<S>.csv  positions, routes and attacker of the example run
//
// Scenarios per seed (all at medium load unless stated):
//   load    - traffic sweep, 1 grayhole (busiest relay) dropping 30%, plus no-attack twins
//   drop    - 1 grayhole dropping 10, 20, 50, 100%
//   two     - 2 grayholes (two busiest relays);  three - 3 grayholes (baseline contamination)
//   near    - 1 grayhole whose neighbours are closest (tests the near-area weight)
//   large   - 40 sensors in 6 km x 6 km;  sparse - 20 sensors in 5 km x 5 km (both +/- attack)
//   hotspot - 3 most-connected sensors send 6x as much as the others, same total traffic
//             (busy monitors are deaf more often), +/- attack
//   weak    - 1 grayhole dropping 10, 20, 30% over 24 hours (detection over time)
//   csma    - the UnetStack CSMA MAC reserves the channel before each frame, +/- attack

import org.arl.fjage.*

def LOADS = [0.001, 0.0025, 0.005, 0.01]   // packets/s generated per sensor
def MID = 0.005
def DROP = 0.3
def SEEDS = System.getenv('SEEDS')?.split(',')*.toLong() ?: (201..210)
def SCENARIOS = System.getenv('SCENARIOS')?.split(',') ?:
                ['load', 'drop', 'two', 'three', 'near', 'large', 'sparse', 'hotspot', 'weak', 'csma']
def PART = System.getenv('PART') ?: ''
def EXAMPLE_SEED = SEEDS[0]
def HOURS = 6                              // default run length
def WEAK_HOURS = 24
def FWD_WAIT = 2000                        // ms; calibration: p99 forwarding delay 50 ms, max 1120 ms
def FWD_WAIT_CSMA = 10000                 // ms; calibrate_csma.groovy: p99 forwarding delay 8.5 s with CSMA
def CHECK_MS = 1800000                     // evidence snapshot = decision round every 30 min
def HOTSPOTS = 3, HOT_FACTOR = 6

channel.model = DistanceLossChannel
channel.communicationRange = Topology.RANGE
channel.detectionRange = Topology.RANGE
channel.interferenceRange = Topology.RANGE
channel.pDecoding = 1.0
channel.edgeLoss = Detectors.EDGE_LOSS

def ev = new File("../results/evidence${PART}.csv")
ev.text = 'scenario,load,drop,seed,attackers,snapshot,m,b,d,busy,' +
          ['c', 'g', 's'].collect { p -> (0..31).collect { "$p$it" }.join(',') }.join(',') + '\n'
def sims = new File("../results/sims${PART}.csv")
sims.text = 'scenario,load,drop,seed,attackers,pdr,eligible_frac\n'

// relays ordered by how much traffic they carry, and the "near" relay: carries >= 2
// nodes and has the closest neighbours on average
def busiest = { Topology t, Map r -> def l = t.load(r); l.keySet().sort { -l[it] } }
def nearest = { Topology t, Map r ->
  def l = t.load(r)
  l.keySet().findAll { l[it] >= 2 }.min { v -> t.neighbours(v).sum { t.dist(v, it) } / t.neighbours(v).size() }
}

for (seed in SEEDS) {
  def topo = new Topology(20, seed)
  def routes = topo.routes([] as Set)
  def order = busiest(topo, routes)
  // job: [scenario, load, drop, attackers, topology, hours, rates (node -> rate) or null, csma]
  def jobs = []
  def add = { s, load, drop, att, t = topo, hours = HOURS, rates = null, csma = false ->
    jobs << [s, load, drop, att, t, hours, rates, csma]
  }
  if ('load' in SCENARIOS) LOADS.each { x -> add('load', x, 0d, []); add('load', x, DROP, [order[0]]) }
  if ('drop' in SCENARIOS) [0.1, 0.2, 0.5, 1.0].each { d -> add('drop', MID, d, [order[0]]) }
  if ('two' in SCENARIOS) add('two', MID, DROP, order.take(2))
  if ('three' in SCENARIOS) add('three', MID, DROP, order.take(3))
  if ('near' in SCENARIOS) add('near', MID, DROP, [nearest(topo, routes)])
  [large: new Topology(40, seed, 6000, 6000), sparse: new Topology(20, seed, 5000, 5000)].each { name, t ->
    if (name in SCENARIOS) {
      add(name, MID, 0d, [], t)
      add(name, MID, DROP, [busiest(t, t.routes([] as Set))[0]], t)
    }
  }
  if ('hotspot' in SCENARIOS) {
    // same total traffic as uniform MID: HOTSPOTS sensors at HOT_FACTOR x, the rest at x
    def sensors = topo.pos.keySet().findAll { it != Topology.SINK }
    def hot = sensors.sort { -topo.neighbours(it).size() }.findAll { it != order[0] }.take(HOTSPOTS) as Set
    double x = MID * sensors.size() / (sensors.size() - HOTSPOTS + HOTSPOTS * HOT_FACTOR)
    def rates = sensors.collectEntries { [it, it in hot ? HOT_FACTOR * x : x] }
    add('hotspot', MID, 0d, [], topo, HOURS, rates)
    add('hotspot', MID, DROP, [order[0]], topo, HOURS, rates)
  }
  if ('weak' in SCENARIOS) [0.1, 0.2, 0.3].each { d -> add('weak', MID, d, [order[0]], topo, WEAK_HOURS) }
  if ('csma' in SCENARIOS) { add('csma', MID, 0d, [], topo, HOURS, null, true); add('csma', MID, DROP, [order[0]], topo, HOURS, null, true) }

  if (seed == EXAMPLE_SEED) {
    new File("../results/topology_seed${seed}.csv").text = 'node,x,y,next_hop,attacker\n' +
      topo.pos.collect { a, xy -> "$a,${xy[0]},${xy[1]},${routes[a] ?: ''},${a == order[0] ? 1 : 0}" }.join('\n') + '\n'
  }

  jobs.each { job ->
    def (scenario, load, drop, attackers, t, hours, rates, csma) = job
    def r = t.routes([] as Set)
    AgentLocalRandom.setRootSeed(seed)
    def agents = [:]
    simulate hours.hours, {
      t.pos.each { addr, xy ->
        double rate = addr == Topology.SINK ? 0 : rates ? rates[addr] : load
        def a = new MonitorNode(addr: addr, nextHop: r[addr], genRate: rate, dropProb: addr in attackers ? drop : 0,
                                topo: t, fwdWait: csma ? FWD_WAIT_CSMA : FWD_WAIT, checkpointMs: CHECK_MS, csma: csma)
        agents[addr] = a
        node "$addr", address: addr, location: [xy[0], xy[1], -50], stack: { c ->
          if (csma) c.add 'mac', new org.arl.unet.mac.CSMA()
          c.add 'wd', a
        }
      }
    }
    def key = [scenario, load, drop, seed, attackers.join(';')].join(',')
    def out = new StringBuilder()
    def fmt = { double[] xs -> xs.collect { it == 0 ? '0' : sprintf('%.3f', it) }.join(',') }
    agents.each { m, a ->
      def write = { idx, Map snap ->
        double busy = snap.t > 0 ? snap.busyMs / (double) snap.t : 0
        snap.ev.each { b, c ->
          out << "$key,$idx,$m,$b,${t.dist(m, b)},${sprintf('%.4f', busy)},${c.join(',')},${fmt(snap.expect[b])},${fmt(snap.lossSum[b])}\n"
        }
      }
      write(-1, a.snapshot(hours * 3600000L))   // the simulation clock is gone after simulate()
      a.snapshots.eachWithIndex { s, i -> write(i, s) }
    }
    ev << out
    def honest = agents.keySet().findAll { it != Topology.SINK && !(it in attackers) }
    def got = agents[Topology.SINK].delivered.count { (int) it[0] in honest }   // origin is stored as long
    long all = agents.values().sum { a -> a.ev.values().sum { Detectors.count(it, 'all')[0] } ?: 0 }
    long elig = agents.values().sum { a -> a.ev.values().sum { Detectors.count(it, 'filter')[0] } ?: 0 }
    sims << "$key,${got / honest.sum { agents[it].generated }},${elig / all}\n"
    println "seed $seed $scenario load=$load drop=$drop attackers=$attackers done"
  }
}

println 'RECORD_COMPLETE'
