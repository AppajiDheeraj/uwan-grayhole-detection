// Pipeline step 3: the live network. Detection, reporting and response all happen inside
// the simulation, so false accusations and missed grayholes show up as lost packets.
//
// One 12-hour run per case. The grayholes (the K busiest relays, 30% drop) act from the start.
// Every 30 minutes (one decision round):
//   1. each monitor sends one report frame (up to 6 watched relays) to the sink over the
//      same acoustic channel as the data; it may be lost or dropped by a grayhole
//   2. the sink runs the detector on the latest delivered report of every link, plus its own
//      observations (ours also leaves confirmed suspects out of its "normal loss" baseline)
//   3. two-round confirmation (Detectors.confirm, same for every detector): a relay becomes
//      a suspect when flagged in two consecutive rounds and then stays one
//   4. routes are recomputed at once (no control-plane cost):
//        avoid   (POLICY=avoid, default) - suspects stop relaying wherever another path exists;
//                                          nodes with no other path keep theirs (a lossy path beats none)
//        isolate (POLICY=isolate)        - suspects are cut out completely; a grayhole that is the only
//                                          path for many nodes then disconnects them all
// Cases: noattack (no grayhole, no defence), nodefense (grayholes, no defence),
//        fixed / cad / ours (grayholes + that detector + reports + response).
// Thresholds: results/thresholds.csv from compare.groovy; RULE=own (default: each method's
// own setting) or RULE=budget (all methods at the same calibrated false-alarm budget).
//
// Run from inside unet/:
//   ../tools/unet-7.0.0/bin/unet $PWD/network.groovy     SEEDS=201 LOADS=0.005 GRAYHOLES=3 PART=_x
// Output: ../results/network<PART>.csv          one row per run
//         ../results/network_rounds<PART>.csv   delivery of the data generated in each round

import org.arl.fjage.*

def SEEDS = System.getenv('SEEDS')?.split(',')*.toLong() ?: (201..210)
def LOADS = System.getenv('LOADS')?.split(',')*.toDouble() ?: [0.005]
def GRAYHOLES = System.getenv('GRAYHOLES')?.toInteger() ?: 1
def POLICY = System.getenv('POLICY') ?: 'avoid'
def RULE = System.getenv('RULE') ?: 'own'
def PART = System.getenv('PART') ?: ''
def HOURS = System.getenv('HOURS')?.toInteger() ?: 12
def CASES = System.getenv('CASES')?.split(',') ?: ['noattack', 'nodefense', 'fixed', 'cad', 'ours']
def ROUND_MS = 1800000
def DROP = 0.3
def FWD_WAIT = 2000

channel.model = DistanceLossChannel   // range, loss curve: see DistanceLossChannel

def thresholds = [:]
new File('../results/thresholds.csv').readLines().drop(1).each { l ->
  def f = l.split(',', -1)
  thresholds[f[0]] = [own: f[1] as double, budget: f[2] as double]
}
assert ['fixed', 'cad', 'ours'].every { thresholds[it] } && POLICY in ['avoid', 'isolate'] && RULE in ['own', 'budget']

def runs = new File("../results/network${PART}.csv")
runs.text = 'grayholes,load,seed,policy,rule,case,generated,delivered,pdr,latency_s,hops,tx_per_delivered,report_tx,' +
            'reports_sent,reports_delivered,route_changes,first_response_min,honest_suspects,grayholes_caught,hours\n'
def roundsOut = new File("../results/network_rounds${PART}.csv")
roundsOut.text = 'grayholes,load,seed,policy,rule,case,round_end_min,generated,delivered,honest_suspects,grayholes_caught\n'

for (seed in SEEDS) {
  def topo = new Topology(20, seed)
  def normal = topo.routes([] as Set)
  def carried = topo.load(normal)
  Set grayholes = carried.keySet().sort { -carried[it] }.take(GRAYHOLES) as Set   // busiest relays
  for (load in LOADS) {
    for (name in CASES) {
      Set attackers = name == 'noattack' ? [] as Set : grayholes
      boolean defended = name in ['fixed', 'cad', 'ours']
      AgentLocalRandom.setRootSeed(seed)
      def agents = [:]
      def state = [suspects: [] as Set, lastFlagged: [] as Set, changes: 0, first: null, timeline: [:], ranking: null]
      simulate HOURS.hours, {
        topo.pos.each { addr, xy ->
          def a = new MonitorNode(addr: addr, nextHop: normal[addr], genRate: addr == Topology.SINK ? 0 : load,
                                  dropProb: addr in attackers ? DROP : 0, topo: topo, fwdWait: FWD_WAIT,
                                  checkpointMs: ROUND_MS, reportRule: defended ? (name == 'ours' ? 'filter' : 'sender') : null)
          agents[addr] = a
          node "$addr", address: addr, location: [xy[0], xy[1], -50], stack: { c -> c.add 'wd', a }
        }
        // The sink (administrator) only sees delivered reports, never other agents' memory.
        agents[Topology.SINK].onRound = { long t ->
          if (defended) {
            def rows = agents[Topology.SINK].receivedEvidence()
            def result = rows ? Detectors.decide(name, rows, thresholds[name][RULE], state.suspects) : null
            Set flagged = result ? result.flagged as Set : [] as Set
            Set suspects = Detectors.confirm(state.suspects, state.lastFlagged, flagged)
            state.lastFlagged = flagged
            state.ranking = result
            if (suspects != state.suspects) {
              state.changes++
              state.suspects = suspects
              if (state.first == null) state.first = t
              def nh = POLICY == 'isolate' ? topo.routes(suspects) : topo.routesAvoiding(suspects)
              agents.each { m, a -> if (m != Topology.SINK) a.nextHop = nh[m] }
            }
          }
          state.timeline[t] = state.suspects
        }
      }

      // honest data only; the grayholes' own packets never count
      def honest = agents.keySet().findAll { it != Topology.SINK && !(it in grayholes) }
      def got = agents[Topology.SINK].delivered.findAll { (int) it[0] in honest }   // origin is stored as long
      long gen = honest.sum { agents[it].generated }
      long tx = agents.values().sum { it.txCount }
      def tag = [GRAYHOLES, load, seed, POLICY, RULE, name]
      runs << (tag + [gen, got.size(), got.size() / gen, got ? got.sum { it[1] } / got.size() / 1000 : 0,
               got ? got.sum { it[2] } / got.size() : 0, got ? tx / got.size() : 0,
               agents.values().sum { it.reportTx }, agents.values().sum { it.reportsSent },
               agents[Topology.SINK].reportsDelivered, state.changes,
               state.first != null ? state.first / 60000 : 'NA', (state.suspects - attackers).size(),
               state.suspects.intersect(attackers).size(), HOURS]).join(',') + '\n'

      // delivery of the data generated in each round: the network before and after the response
      int rounds = (int)(HOURS * 3600000L / ROUND_MS)
      (1..rounds).each { k ->
        long lo = (k - 1) * ROUND_MS, hi = k * ROUND_MS
        long g = honest.sum { agents[it].genTimes.count { it >= lo && it < hi } }
        long d = got.count { it[3] >= lo && it[3] < hi }
        def last = state.timeline.findAll { tt, s -> tt <= hi }.max { it.key }
        Set sus = last ? last.value : [] as Set
        roundsOut << (tag + [hi / 60000, g, d, (sus - attackers).size(), sus.intersect(attackers).size()]).join(',') + '\n'
      }

      // the administrator's view at the end of the run
      println "topology $seed, $GRAYHOLES grayhole(s) ${grayholes.sort()}, load $load, $name: " +
              "delivery ${sprintf('%.1f', 100 * got.size() / gen)}%, suspects ${state.suspects.sort()}"
      if (name == 'ours' && state.ranking) {
        state.ranking.rank.take(5).eachWithIndex { e, i ->
          println sprintf('   %d. node %2d  est. drop %5.1f%%  %s%s', i + 1, e[0], e[1],
                          e[0] in state.suspects ? 'SUSPECT' : '-', e[0] in grayholes ? '  (grayhole)' : '')
        }
      }
    }
  }
}
println 'NETWORK_COMPLETE'
