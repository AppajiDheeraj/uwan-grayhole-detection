// Mechanism checks (evaluation only: uses the simulator's ground truth to measure bias;
// detectors never see these logs).
//   1. Does condition C2 (no corrupted frame at the monitor) select dropped and forwarded
//      packets at different rates? A forward that collides at the monitor makes its own
//      window fail C2; a dropped packet sends nothing, so it cannot.
//   2. How strongly do monitors that watch the same packet agree? Several monitors see the
//      same drop decision, so their windows are not independent.
//
// Run from inside unet/:   ../tools/unet-7.0.0/bin/unet $PWD/diag_mechanisms.groovy
// Output: ../results/diag_mechanisms.txt

import org.arl.fjage.*

def SEEDS = [101, 102, 103]       // calibration topologies only
def LOAD = 0.005
def DROP = 0.3

channel.model = DistanceLossChannel
channel.communicationRange = Topology.RANGE
channel.detectionRange = Topology.RANGE
channel.interferenceRange = Topology.RANGE
channel.pDecoding = 1.0
channel.edgeLoss = Detectors.EDGE_LOSS

def out = new File('../results/diag_mechanisms.txt')
out.text = ''
def say = { s -> println s; out << s + '\n' }
def pct = { a, b -> b ? sprintf('%5.1f%%', 100.0 * a / b) : '   - ' }

// totals over seeds: [eligible-without-C2, of which C2 also ok] per fate
def c2 = [forwarded: [0, 0], dropped: [0, 0], notReceived: [0, 0]]
def share = [all: [0, 0], c13c4: [0, 0], full: [0, 0]]     // [drops, received] in attacker windows
def pairs = [same: 0, total: 0, missedEnds: 0], monitorsPerPacket = []
for (seed in SEEDS) {
  def topo = new Topology(20, seed)
  def routes = topo.routes([] as Set)
  def l = topo.load(routes)
  int attacker = l.keySet().max { l[it] }
  AgentLocalRandom.setRootSeed(seed)
  def agents = [:]
  simulate 6.hours, {
    topo.pos.each { addr, xy ->
      def a = new MonitorNode(addr: addr, nextHop: routes[addr], genRate: addr == Topology.SINK ? 0 : LOAD,
                              dropProb: addr == attacker ? DROP : 0, topo: topo, logWindows: true)
      agents[addr] = a
      node "$addr", address: addr, location: [xy[0], xy[1], -50], stack: { c -> c.add 'wd', a }
    }
  }
  def fate = { int relay, String id ->
    def b = agents[relay]
    id in b.droppedIds ? 'dropped' : id in b.receivedIds ? 'forwarded' : 'notReceived'
  }
  def byPacket = [:].withDefault { [] }       // honest relay packets: eligible miss indicators per monitor
  agents.each { m, a ->
    a.windowLog.each { w ->
      String f = fate(w.relay, w.id)
      if (w.c13 && w.c4) { c2[f][0]++; if (w.c2) c2[f][1]++ }
      if (w.relay == attacker && f != 'notReceived') {
        boolean drop = f == 'dropped'
        share.all[0] += drop ? 1 : 0; share.all[1]++
        if (w.c13 && w.c4) { share.c13c4[0] += drop ? 1 : 0; share.c13c4[1]++ }
        if (w.c13 && w.c4 && w.c2) { share.full[0] += drop ? 1 : 0; share.full[1]++ }
      }
      if (w.relay != attacker && w.c13 && w.c4) byPacket["$w.relay:$w.id"] << (w.heard ? 0 : 1)
    }
  }
  // agreement between two monitors of the same honest packet vs chance agreement
  byPacket.values().findAll { it.size() >= 2 }.each { xs ->
    monitorsPerPacket << xs.size()
    for (int i = 0; i < xs.size(); i++) for (int j = i + 1; j < xs.size(); j++) {
      pairs.total++; pairs.missedEnds += xs[i] + xs[j]
      if (xs[i] == 1 && xs[j] == 1) pairs.same++
    }
  }
}

say "Mechanism checks on calibration topologies ${SEEDS}, load ${LOAD}, busiest relay drops ${(int)(DROP * 100)}%"
say ''
say '1. Does C2 select by outcome? Share of windows (already passing C1, C3, C4) that also pass C2:'
c2.each { f, v -> say sprintf('   relay %-12s %s of %d windows', f, pct(v[1], v[0]), v[0]) }
say ''
say '   Monitor-window-weighted drop share among received attacker packets (unfiltered first):'
say "     all windows            ${pct(share.all[0], share.all[1])}"
say "     passing C1, C3, C4     ${pct(share.c13c4[0], share.c13c4[1])}"
say "     passing C1-C4 and C2   ${pct(share.full[0], share.full[1])}"
say ''
double q = pairs.missedEnds / (2d * pairs.total)
say "2. Honest packets watched by 2+ eligible monitors: ${monitorsPerPacket.size()}, mean monitors ${sprintf('%.2f', monitorsPerPacket.sum() / monitorsPerPacket.size())}"
say "   C1+C3+C4: P(both miss) = ${pct(pairs.same, pairs.total)} vs pooled pair-marginal q^2 = ${sprintf('%5.1f%%', 100 * q * q)} (q ${sprintf('%.3f', q)})"
say '   Descriptive dependence check, including link heterogeneity; not an independence test or effective sample size.'

println 'COMPLETE diag_mechanisms'
