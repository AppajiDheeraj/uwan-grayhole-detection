// Forwarding-delay calibration with the UnetStack CSMA MAC (calibration seeds only).
// Relays now wait for the channel, so the watch window T_w must be re-measured.
//
// Run from inside unet/:   ../tools/unet-7.0.0/bin/unet $PWD/calibrate_csma.groovy
// Output: ../results/calibration_csma.txt

import org.arl.fjage.*

channel.model = DistanceLossChannel
channel.communicationRange = Topology.RANGE
channel.detectionRange = Topology.RANGE
channel.interferenceRange = Topology.RANGE
channel.pDecoding = 1.0
channel.edgeLoss = Detectors.EDGE_LOSS

def out = new File('../results/calibration_csma.txt')
out.text = ''
def say = { s -> println s; out << s + '\n' }
say 'load    MAC    PDR    fwd delay ms (p50 / p95 / p99 / max)  late heard  refused'
for (load in [0.0025, 0.005, 0.01]) {
  for (csma in [false, true]) {
    def pdr = [], delays = [], late = 0, refused = 0
    for (seed in [101, 102, 103]) {
      def topo = new Topology(20, seed)
      def routes = topo.routes([] as Set)
      AgentLocalRandom.setRootSeed(seed)
      def agents = [:]
      simulate 6.hours, {
        topo.pos.each { addr, xy ->
          def a = new MonitorNode(addr: addr, nextHop: routes[addr], genRate: addr == Topology.SINK ? 0 : load,
                                  dropProb: 0, topo: topo, fwdWait: 30000, csma: csma)
          agents[addr] = a
          node "$addr", address: addr, location: [xy[0], xy[1], -50], stack: { c ->
            if (csma) c.add 'mac', new org.arl.unet.mac.CSMA()
            c.add 'wd', a
          }
        }
      }
      pdr << agents[Topology.SINK].delivered.size() / agents.values().sum { it.generated }
      agents.values().each { delays.addAll(it.fwdDelays); late += it.lateHeard; refused += it.refused }
    }
    delays.sort()
    def q = { p -> delays ? delays[Math.min(delays.size() - 1, (int)(p * delays.size()))] : 0 }
    say sprintf('%-7s %-6s %.3f  %6d / %6d / %6d / %6d       %5d   %5d', load, csma ? 'CSMA' : 'none',
                pdr.sum() / pdr.size(), q(0.5), q(0.95), q(0.99), delays ? delays[-1] : 0, late, refused)
  }
}

println 'COMPLETE calibrate_csma'
