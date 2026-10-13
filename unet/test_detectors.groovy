// Self-check for Detectors and Topology, against hand-computed values.
// Run from inside unet/:   ../tools/unet-7.0.0/bin/unet $PWD/test_detectors.groovy   (prints PASS)

def near = { a, b, tol = 1e-6 -> Math.abs(a - b) < tol }
def OK = MonitorNode.idx(true, true, true, true, true)          // sender window, all conditions hold, heard
def MISS = MonitorNode.idx(true, true, true, true, false)       // same, missed

// A link with `heard` and `missed` clean sender windows; g = expected forwards from the channel.
def link = { m, b, d, int heard, int missed, double g = heard + missed, double busy = 0 ->
  def c = new long[32], gg = new double[32], s = new double[32]
  c[OK] = heard; c[MISS] = missed
  gg[OK] = g * heard / (heard + missed); gg[MISS] = g * missed / (heard + missed)
  [m: m, b: b, d: d, busy: busy, c: c, g: gg, s: s]
}

// ---- window rules: which counters each rule keeps
def c = new long[32]
c[MonitorNode.idx(true, true, true, true, true)] = 70      // clean, heard
c[MonitorNode.idx(true, true, true, true, false)] = 10     // clean, missed
c[MonitorNode.idx(true, true, false, true, false)] = 20    // C2 failed (corrupted frame at monitor)
c[MonitorNode.idx(true, true, true, false, false)] = 5     // C4 failed (collision at relay)
c[MonitorNode.idx(false, false, true, true, false)] = 7    // overheard, C1/C3 failed
assert Detectors.count(c, 'all') == [112, 42] as long[]
assert Detectors.count(c, 'sender') == [105, 35] as long[]
assert Detectors.count(c, 'filter') == [100, 30] as long[]        // C1, C3, C4 (C2 ignored)
assert Detectors.count(c, 'filter+c2') == [80, 10] as long[]
assert Detectors.count(c, 'v1') == [85, 15] as long[]                  // C1, C2, C3 (C4 ignored)

// ---- channel scale: exactly (1 - k*0.1)*(1 - k*0.2) for 100 windows
def g = new double[32], s1 = new double[32]
def counts = new long[32]
g[OK] = 72; s1[OK] = 30; counts[OK] = 100
assert near(Detectors.expected(g, s1, counts, 'all', 1.0), 72)
assert near(Detectors.expected(g, s1, counts, 'all', 1.5), 59.5)
assert near(Detectors.expected(g, s1, counts, 'all', 0.5), 85.5)

// No expected forwards means no identifiable drop rate, not NaN/infinite trust.
def silent = [link(2, 40, 100, 0, 100), link(3, 41, 100, 0, 100)]
assert Detectors.run('ours', silent).stats.isEmpty()

// ---- proposed method: channel predicts 90 of 100 forwards; collisions remove 10% more
def rows = [link(2, 40, 100, 81, 19, 90), link(3, 41, 200, 81, 19, 90), link(4, 42, 300, 81, 19, 90),
            link(5, 40, 600, 81, 19, 90), link(6, 41, 700, 81, 19, 90), link(7, 42, 800, 81, 19, 90),
            link(8, 40, 1200, 81, 19, 90), link(9, 41, 1300, 81, 19, 90), link(2, 43, 1400, 56, 44, 90)]  // 43 drops 30%
def r = Detectors.physics(rows, Detectors.OURS + [selfOut: false])
assert near(r.kappa[0], 0.1) && near(r.stats[40].trust, 1.0) && near(r.stats[40].score, 0)
assert near(r.stats[43].trust, 56 / 81d) && near(r.stats[43].score, 100 * (1 - 56 / 81d))    // about 30.9% drop
assert r.rank[0][0] == 43 && Detectors.run('ours', rows).rank[0][0] == 43
// baseline cleaning: relay 43's estimated drop >= 10%, so its link leaves the area baseline
def pr = Detectors.run('ours', rows)
assert pr.peeled == [43] as Set && pr.rank[0][0] == 43

// ---- decision boundary
assert near(Detectors.upperQuantile(0.025), 1.96, 1e-3) && near(Detectors.upperQuantile(0.05 / 7), 2.45, 5e-3)
def stats = { [1: [n: 50, z: 3.0d, score: 30d, Lobs: 0.4d], 2: [n: 50, z: 2.1d, score: 12d, Lobs: 0.2d],
               3: [n: 50, z: 1.0d, score: 5d, Lobs: 0.1d], 4: [n: 10, z: 9.0d, score: 50d, Lobs: 0.9d]] }
assert Detectors.decide(stats(), [boundary: 'fixed']).flagged == [1, 2] as Set              // z > 2, S >= 10, n >= 20
assert Detectors.decide(stats(), [boundary: 'dynamic']).flagged == [1] as Set               // z* = q(0.05/3) = 2.13 > 2.1
assert Detectors.decide(stats(), [boundary: 'dynamic', alpha: 0d]).flagged.isEmpty()
assert near(Detectors.decide(stats(), [boundary: 'dynamic']).zStar, Detectors.upperQuantile(0.05 / 3))
assert Detectors.decide(stats(), [boundary: 'frozen', lossThreshold: 0.15]).flagged == [1, 2] as Set

// ---- over-dispersion from z < 0 only
def mk = { [1: [n: 50, z: -1.0d], 2: [n: 50, z: 1.0d], 3: [n: 50, z: 20d], 4: [n: 50, z: 25d], 5: [n: 50, z: -0.5d]] }
assert near(Detectors.disperse(mk(), 'neg'), ((1 + 0.25) / 2) / 0.455)
assert near(Detectors.disperse(mk(), 'all'), 1 / 0.455)

// ---- fixed watchdog: worst sender monitor's miss rate, accuse if > 30%
def fx = Detectors.fixed([link(2, 14, 900, 28, 22), link(6, 14, 900, 32, 18)])
assert near(fx.stats[14].score, 44.0) && fx.flagged == [14] as Set

// ---- CAD-style: normal loss from channel and busy ratio
// d = 0 -> no channel loss; busy 0.1 -> p_o = 1 - 0.9^2 = 0.19 -> p_r = 1 - 0.81^2 = 0.3439
def cd = Detectors.cad([link(2, 50, 0, 66, 34, 100, 0.1), link(3, 51, 0, 40, 60, 100, 0.1)])
assert near(cd.stats[50].Lexp, 0.3439) && cd.stats[50].z < 0.1 && cd.flagged == [51] as Set

// ---- routing around suspects never loops and reaches the sink
def rnd = new Random(7)
(1..10).each { seed ->
  def topo = new Topology(20, seed)
  50.times {
    Set suspects = (2..21).findAll { rnd.nextDouble() < 0.2 } as Set
    def nh = topo.routesAvoiding(suspects)
    nh.keySet().each { v ->
      int steps = 0
      for (def u = v; u != Topology.SINK; u = nh[u]) assert ++steps < 30 && nh[u] != null
    }
    def clean = topo.hops(suspects)
    nh.each { v, u -> if (clean[v] != null && !(v in suspects)) assert !(u in suspects) }
  }
}

// ---- reports carry evidence and replace older counters; the latest one is kept
def topo = new Topology(20, 101)
int monitor = 2
int relay = topo.neighbours(monitor).find { it != Topology.SINK }
def source = new MonitorNode(addr: monitor, topo: topo, reportRule: 'filter', busyMs: 180000)
source.ev[relay] = rows[0].c.clone()
source.expect[relay] = rows[0].g.clone()
def sink = new MonitorNode(addr: Topology.SINK, topo: topo)
def packet = source.packReport([relay], 1800000)
assert packet.length == 29 && sink.receivedEvidence().isEmpty()  // unsent report conveys nothing
assert sink.acceptReport(packet, 1801000)
def received = sink.receivedEvidence()[0]
assert Detectors.count(received.c, 'filter') == Detectors.count(source.ev[relay], 'filter')
assert near(Detectors.expected(received.g, received.s, received.c, 'filter'), 90)
assert near(received.busy, 0.1)
assert !sink.acceptReport(packet, 1802000) && sink.reportRows.size() == 1
assert sink.receivedEvidence().size() == 1   // a lost later report keeps the last evidence
assert !sink.acceptReport(new byte[9], 1801000)
def malformed = packet.clone()
java.nio.ByteBuffer.wrap(malformed).putInt(17, 101)  // misses cannot exceed windows
assert !sink.acceptReport(malformed, 1801000)

// ---- two-round confirmation: one noisy round accuses nobody; confirmed suspects stay
assert Detectors.confirm([] as Set, [] as Set, [5] as Set).isEmpty()
assert Detectors.confirm([] as Set, [5] as Set, [5] as Set) == [5] as Set
assert Detectors.confirm([5] as Set, [] as Set, [] as Set) == [5] as Set

// ---- bundling: one frame carries several relays and fits the 128-byte DATA frame
int other = topo.neighbours(monitor).find { it != Topology.SINK && it != relay }
source.ev[other] = rows[0].c.clone()
source.expect[other] = rows[0].g.clone()
def bundle = source.packReport([relay, other], 3600000)
assert bundle.length == 12 + 2 * 17 && 12 + 6 * 17 <= 128
assert sink.acceptReport(bundle, 3601000) && sink.receivedEvidence().size() == 2
println 'PASS'
