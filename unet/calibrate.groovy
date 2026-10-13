// Calibration runs: no attacker, separate seeds (101-103) from the test seeds
// used by record.groovy and network.groovy, so no parameter is tuned on the data it is evaluated on.
//
// Run from inside unet/:   ../tools/unet-7.0.0/bin/unet $PWD/calibrate.groovy
// Output: ../results/calibration.txt
//
// Reports, per traffic load: delivery ratio, how long relays hold packets before
// forwarding (sets the watch window), and how many windows are eligible.

import org.arl.fjage.*

def SENSORS = 20
def LOADS = [0.001, 0.0025, 0.005, 0.01]
def SEEDS = [101, 102, 103]
def DURATION = 6.hours
def FWD_WAIT = 8000          // generous on purpose: we measure the real delays here

channel.model = DistanceLossChannel
channel.communicationRange = Topology.RANGE
channel.detectionRange = Topology.RANGE
channel.interferenceRange = Topology.RANGE
channel.pDecoding = 1.0
channel.edgeLoss = Detectors.EDGE_LOSS

def out = new File('../results/calibration.txt')
out.text = ''
def say = { s -> println s; out << s + '\n' }

say 'load     PDR    latency(s)  fwd delay ms (p50 / p95 / p99 / max)  late heard  eligible windows  honest-loss rate (eligible / all)'
for (load in LOADS) {
  def pdr = [], lat = [], delays = [], late = 0, elig = 0, all = 0, eligMiss = 0, allMiss = 0
  for (seed in SEEDS) {
    def topo = new Topology(SENSORS, seed)
    def routes = topo.routes([] as Set)
    AgentLocalRandom.setRootSeed(seed)
    def agents = [:]
    simulate DURATION, {
      topo.pos.each { addr, xy ->
        def a = new MonitorNode(addr: addr, nextHop: routes[addr], genRate: addr == Topology.SINK ? 0 : load,
                                dropProb: 0, topo: topo, fwdWait: FWD_WAIT)
        agents[addr] = a
        node "$addr", address: addr, location: [xy[0], xy[1], -50], stack: { c -> c.add 'wd', a }
      }
    }
    def sink = agents[Topology.SINK]
    pdr << sink.delivered.size() / agents.values().sum { it.generated }
    lat << (sink.delivered.sum { it[1] } ?: 0) / Math.max(1, sink.delivered.size()) / 1000
    agents.values().each { a ->
      delays.addAll(a.fwdDelays)
      late += a.lateHeard
      a.ev.values().each { c ->
        def (long n, long s) = Detectors.count(c, 'filter'); elig += n; eligMiss += s
        def (long na, long sa) = Detectors.count(c, 'all'); all += na; allMiss += sa
      }
    }
  }
  delays.sort()
  def q = { p -> delays ? delays[Math.min(delays.size() - 1, (int)(p * delays.size()))] : 0 }
  say sprintf('%-8s %.3f  %8.1f     %5d / %5d / %5d / %5d          %5d       %5.1f%%           %5.1f%% / %5.1f%%',
    load, pdr.sum() / pdr.size(), lat.sum() / lat.size(), q(0.5), q(0.95), q(0.99), delays ? delays[-1] : 0,
    late, 100.0 * elig / all, 100.0 * eligMiss / elig, 100.0 * allMiss / all)
}

println 'COMPLETE calibrate'
