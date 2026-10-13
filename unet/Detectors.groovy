/**
 * Detection methods. All of them read the SAME evidence counters recorded by
 * MonitorNode, so they are compared on identical traffic, topology and attacks.
 *
 * Evidence row: [m: monitor, b: relay, d: m-b distance (m), busy: monitor busy ratio,
 *                c: long[32] counters, g: double[32] channel-only expected forwards,
 *                s: double[32] channel-loss sums]   (counter layout: MonitorNode.idx)
 *
 * Methods (each returns [flagged: Set, rank: List<[node, score]>, stats: Map node -> Map]):
 *   fixed - watchdog after Marti et al. (2000) and Chandavarkar & Suresh (2021): the sender
 *           watches its next hop, every miss counts, accuse if the miss rate > 30%.
 *   cad   - CAD-style baseline adapted from Shila et al. (2010), downstream monitoring only:
 *           normal loss = channel loss + collision probability estimated from the monitor's
 *           own channel-busy ratio (pure-ALOHA adaptation of their Sec. V), binomial test.
 *   area  - ours without the observability filter (every window counts).
 *   ours  - PROPOSED: observability filter C1+C3+C4, expected forwards from distances plus
 *           an area-median collision loss (leave-self-out, baseline cleaning), area-weighted trust O/E, one-sided
 *           over-dispersion, and a dynamic decision threshold (see decide()).
 * Supporting variants for the ablation: other window rules, frozen threshold, wrong
 * channel model, Mohit's fixed area weights (statistical()).
 */
class Detectors {

  static final double FIXED_THRESHOLD = 0.3   // fixed watchdog: miss rate that triggers an accusation
  static final int MIN_OBS = 20               // judge a relay only on >= 20 windows
  static final int MIN_ROW = 5                // a monitor-relay link joins the area baseline with >= 5 windows
  static final double ALPHA = 0.05            // nominal normal-tail budget; actual false alarms require calibration
  static final double Z_FIXED = 2.0           // fixed z threshold (version 3 and the CAD-style native rule)
  static final double S_MIN = 10.0            // and at least 10% estimated drop rate
  static final double EDGE_LOSS = 0.15        // channel frame loss at the edge of range (DistanceLossChannel)
  static final double CHI2_MEDIAN = 0.455     // median of z^2 for an honest relay (chi-square, 1 dof)

  /** Area weights (near, medium, far) for the supporting area-weight ablation. */
  static final Map WEIGHTS = [equal: [1, 1, 1], moderate: [0.8, 1, 1.2], strong: [0.5, 1, 1.5], derived: 'derived']

  /** Proposed configuration and its ablations (all through physics()). */
  static final Map OURS = [windows: 'filter', selfOut: true, disperse: 'neg', boundary: 'dynamic', channelScale: 1.0, peel: true]
  static final Map OPTIONS = [
    ours     : OURS,
    area     : OURS + [windows: 'all'],                             // no observability filter
    'ours-nopeel': OURS + [peel: false],                            // no baseline cleaning (weak with several grayholes)
    'ours-c2': OURS + [windows: 'filter+c2'],                       // keep C2 (biased, see METHOD.md)
    'ours-v3': OURS + [windows: 'filter+c2', boundary: 'fixed', peel: false],  // previous version
    'ours-fixedz': OURS + [boundary: 'fixed'],                      // same, z threshold not dynamic
    'ours-mean': OURS + [area: 'mean'],                            // area baseline by weighted average, not median
  ]
  static final List<String> METHODS = ['fixed', 'cad', 'area', 'ours']
  static final List<String> SUPPORT = ['ours-nopeel', 'ours-c2', 'ours-v3', 'ours-fixedz', 'ours-mean']

  static Map run(String method, List<Map> rows, Map override = [:]) {
    if (method == 'fixed') return fixed(rows, override)
    if (method == 'cad') return cad(rows, override)
    def opt = OPTIONS[method] + override
    return opt.peel ? peel(rows, opt) : physics(rows, opt)
  }

  /**
   * Baseline cleaning for several grayholes. Relays whose estimated drop is >= S_MIN, and
   * confirmed suspects (opt.exclude), do not help define "normal" loss: their links leave the
   * area median, and the detector runs again (at most 3 rounds, until the set is stable).
   * Without it, several grayholes in one area make honest relays look "too good", inflate
   * phi and hide every grayhole although they still rank first.
   */
  static Map peel(List<Map> rows, Map opt) {
    def base = opt + [peel: false]
    def r = physics(rows, base)
    Set out = [] as Set
    for (int round = 0; round < 3; round++) {
      Set suspicious = r.stats.findAll { k, v -> v.n >= MIN_OBS && v.score >= S_MIN }.keySet() as Set
      if (suspicious == out) break
      out = suspicious
      r = physics(rows, base + [exclude: out + (opt.exclude ?: [])])
    }
    return r + [peeled: out]
  }

  /** Frame loss over distance d from the channel alone: e(d) = 0.15 (d / R)^2 (DistanceLossChannel). */
  static double channelLoss(double d) { EDGE_LOSS * Math.pow(d / Topology.RANGE, 2) }

  // ---------------------------------------------------------------- window rules

  /**
   * Which of the 32 counters a rule keeps:
   *   sender    - the sender's windows (classic watchdog), any eligibility
   *   all       - every window
   *   filter    - C1, C3 and C4 hold (proposed; C2 ignored)
   *   filter+c2 - C1, C2, C3 and C4 hold (version 3)
   *   v1        - C1, C2, C3 hold (version 1)
   */
  static boolean uses(int i, String rule) {
    switch (rule) {
      case 'sender': return i < 16
      case 'all': return true
      case 'filter': return (i & 10) == 0
      case 'filter+c2': return (i & 14) == 0
      case 'v1': return (i & 12) == 0
    }
    throw new IllegalArgumentException(rule)
  }

  /** [windows, misses] of one link under a rule. */
  static long[] count(long[] c, String rule) {
    long n = 0, s = 0
    for (int i = 0; i < c.length; i++) {
      if (uses(i, rule)) { n += c[i]; if (i % 2 == 1) s += c[i] }
    }
    return [n, s] as long[]
  }

  /**
   * Channel-only expected forwards of one link under a rule. channelScale k multiplies the
   * assumed loss curve e(d). Recover sum(eAB*eBM) = g - n + s1 from existing counters.
   * This makes the channel-model ablation exact, apart from CSV rounding.
   */
  static double expected(double[] g, double[] s1, long[] c, String rule, double k = 1.0) {
    double e = 0
    for (int i = 0; i < g.length; i++) if (uses(i, rule)) {
      e += g[i] + (1 - k) * s1[i] + (k * k - 1) * (g[i] - c[i] + s1[i])
    }
    return Math.max(0d, e)
  }

  // ---------------------------------------------------------------- baselines

  static Map fixed(List<Map> rows, Map opt = [:]) {
    double threshold = opt.threshold != null ? opt.threshold : FIXED_THRESHOLD
    def stats = [:]
    rows.each { r ->
      def (long n, long miss) = count(r.c, 'sender')
      if (n < MIN_OBS) return
      double rate = (double) miss / n   // cast: Groovy 2.5 gives 0.0 for "double x = long / long"
      def s = stats[r.b]
      if (s == null || rate > s.score / 100) stats[r.b] = [score: 100 * rate, n: n, monitor: r.m]
    }
    def flagged = stats.findAll { k, s -> s.score > 100 * threshold }.keySet()
    return [flagged: flagged as Set, rank: ranking(stats), stats: stats]
  }

  /**
   * CAD-style (adapted from Shila et al. 2010, Sec. V-VI). Each sender monitors its next
   * hop. Normal loss of one monitored transmission pair:
   *   p_r = 1 - (1 - e(d))^2 (1 - p_o)^2,   p_o = 1 - (1 - R_b)^2
   * e(d): channel loss of the link (both hops have length d); p_o: pure-ALOHA collision
   * probability derived from the busy ratio R_b the monitor measures (an ALOHA frame
   * collides if another starts within one frame before or after it; CAD's own model is
   * 802.11). Pooled binomial z test; flag if z > zCrit (default Z_FIXED).
   */
  static Map cad(List<Map> rows, Map opt = [:]) {
    double zCrit = opt.zCrit != null ? opt.zCrit : Z_FIXED
    double k = opt.channelScale != null ? opt.channelScale : 1.0
    def stats = [:]
    rows.groupBy { it.b }.each { b, ls ->
      double n = 0, s = 0, mu = 0, var = 0, weighted = 0
      ls.each { r ->
        def (long ni, long si) = count(r.c, 'sender')
        if (ni == 0) return
        double e = Math.min(0.99d, k * channelLoss(r.d as double))
        double po = 1 - Math.pow(1 - r.busy, 2)
        double pr = Math.min(0.99d, Math.max(0.01d, 1 - Math.pow(1 - e, 2) * Math.pow(1 - po, 2)))
        n += ni; s += si; mu += ni * pr; var += ni * pr * (1 - pr)
        weighted += ni * (si / ni - pr) / (1 - pr)
      }
      if (n == 0) return
      stats[b] = [n: (long)n, Lobs: s / n, Lexp: mu / n, z: (s - mu) / Math.sqrt(var),
                  score: 100 * Math.max(0d, weighted / n)]
    }
    def flagged = stats.findAll { key, v -> v.n >= MIN_OBS && v.z > zCrit }.keySet()
    return [flagged: flagged as Set, rank: ranking(stats), stats: stats]
  }

  // ---------------------------------------------------------------- proposed method

  /**
   * For each monitor-relay link i, under the window rule:
   *   O_i = forwards heard,  G_i = channel-only expected forwards (expected())
   *   kappa_i = max(0, 1 - O_i / G_i)      loss the channel does not explain (collisions)
   * kappa_area = median kappa_i over links in the same distance area (near / medium / far
   *   tertiles), leaving out the judged relay's own links (selfOut),
   * E_i = G_i (1 - kappa_area)             forwards an honest relay should show,
   * T_B = sum O / sum E (trust) = the E-weighted average of each monitor's trust O_i / E_i
   *   (area-weighted averaging: each monitor counts by the forwards its area expects),
   * S_B = 100 max(0, 1 - T_B) (estimated drop %),
   * z_B = (sum E - sum O) / sqrt(sum E_i (1 - E_i / n_i)), rescaled by over-dispersion,
   * then decide().
   */
  static Map physics(List<Map> rows, Map opt) {
    double k = opt.channelScale != null ? opt.channelScale : 1.0
    def links = rows.collect { r ->
      def (long n, long s) = count(r.c, opt.windows)
      double g = expected(r.g, r.s, r.c, opt.windows, k)
      [m: r.m, b: r.b, d: r.d, n: n, o: n - s, g: g, k: g > 0 ? Math.max(0d, 1 - (n - s) / g) : 0d]
    }.findAll { it.n >= MIN_ROW && it.g > 0 }
    if (!links) return [flagged: [] as Set, rank: [], stats: [:], judged: 0]

    def ds = links*.d.sort()
    def cut = [ds[(int)(ds.size() / 3)], ds[(int)(2 * ds.size() / 3)]]
    links.each { it.zone = it.d < cut[0] ? 0 : it.d < cut[1] ? 1 : 2 }
    def exclude = (opt.exclude ?: []) as Set     // relays left out of every baseline (peeling)
    // Normal residual loss of an area: median of the other relays' links (robust to an
    // attacker's lossy links). 'mean' = evidence-weighted average 1 - sum(O)/sum(G) (ablation).
    def areaKappa = { Integer skip, int z ->
      def ls = links.findAll { it.zone == z && it.b != skip && !(it.b in exclude) }
      if (!ls) ls = links.findAll { it.zone == z }     // too few links: whole area (fallback)
      if (!ls) return 0d
      opt.area == 'mean' ? Math.max(0d, 1 - ls.sum { it.o } / ls.sum { it.g }) : median(ls*.k)
    }
    def kappa = (0..2).collect { areaKappa(null, it) }

    def stats = [:]
    links.groupBy { it.b }.each { b, ls ->
      def own = opt.selfOut ? (0..2).collect { areaKappa(b, it) } : kappa
      double n = ls.sum { it.n }, o = ls.sum { it.o }
      double e = ls.sum { it.g * (1 - own[it.zone]) }
      if (e <= 0) return  // no expected forwards: the drop rate is not identifiable
      double var = ls.sum { l -> double el = l.g * (1 - own[l.zone]); el * Math.max(0.01d, 1 - el / l.n) }
      stats[b] = [n: (long)n, monitors: ls.size(), Lobs: 1 - o / n, Lexp: 1 - e / n, trust: o / e,
                  z: (e - o) / Math.sqrt(var), score: 100 * Math.max(0d, 1 - o / e)]
    }
    double phi = disperse(stats, opt.disperse)
    def d = decide(stats, opt)
    return [flagged: d.flagged, rank: ranking(stats), stats: stats, kappa: kappa, cut: cut, phi: phi,
            zStar: d.zStar, judged: d.judged]
  }

  /**
   * Decision boundary. A judged relay (>= MIN_OBS windows) is flagged if
   *   z_B > z*  and  S_B >= S_MIN   (adaptive: E and phi adapt to the network), where
   *   dynamic: z* = upper-tail normal quantile of ALPHA / k, k = number of judged relays
   *            (nominal Bonferroni boundary, not a calibrated probability guarantee)
   *   fixed:   z* = Z_FIXED
   *   frozen:  ignore E and z; flag if the observed loss 1 - O/n >= opt.lossThreshold
   * opt.alpha, opt.zCrit, opt.sMin override the defaults (threshold sweeps).
   */
  static Map decide(Map stats, Map opt) {
    def judged = stats.findAll { k, v -> v.n >= MIN_OBS }
    double sMin = opt.sMin != null ? opt.sMin : S_MIN
    if (opt.boundary == 'frozen') {
      def flagged = judged.findAll { k, v -> v.Lobs >= opt.lossThreshold }.keySet() as Set
      return [flagged: flagged, zStar: null, judged: judged.size()]
    }
    double zStar = opt.zCrit != null ? opt.zCrit :
                   opt.boundary == 'dynamic' ? upperQuantile((opt.alpha != null ? opt.alpha : ALPHA) / Math.max(1, judged.size())) : Z_FIXED
    def flagged = judged.findAll { k, v -> v.z > zStar && v.score >= sMin }.keySet() as Set
    return [flagged: flagged, zStar: zStar, judged: judged.size()]
  }

  /** z with P(Z > z) = p for a standard normal Z (Abramowitz & Stegun 26.2.23, error < 4.5e-4). */
  static double upperQuantile(double p) {
    assert p >= 0 && p <= 1
    if (p == 0) return Double.POSITIVE_INFINITY
    if (p >= 0.5) return 0
    double t = Math.sqrt(-2 * Math.log(p))
    return t - (2.515517 + 0.802853 * t + 0.010328 * t * t) / (1 + 1.432788 * t + 0.189269 * t * t + 0.001308 * t * t * t)
  }

  /**
   * Over-dispersion factor phi; rescales every relay's z by 1/sqrt(phi).
   *   'all' - median z^2 over judged relays / 0.455
   *   'neg' - median z^2 over judged relays with z < 0. A grayhole raises loss, so it
   *           usually has z > 0; this reduces its direct effect, but gives no immunity. Honest relays scatter on
   *           both sides. Falls back to 'all' with fewer than 2 such relays.
   * Phi is an empirical scale correction; it does not prove monitor independence.
   */
  static double disperse(Map stats, def mode) {
    if (!mode) return 1
    def judged = stats.values().findAll { it.n >= MIN_OBS }*.z
    def neg = judged.findAll { it < 0 }
    def zs = (mode == 'neg' && neg.size() >= 2 ? neg : judged).collect { it * it }
    if (!zs) return 1
    double phi = Math.max(1d, median(zs) / CHI2_MEDIAN)
    stats.values().each { it.z = it.z / Math.sqrt(phi) }
    return phi
  }

  /**
   * Supporting ablation only: area-average expected LOSS with area weights (versions 1-2,
   * Mohit sir's near/medium/far weights). Flags with the fixed z threshold.
   */
  static Map statistical(List<Map> rows, Map opt) {
    def links = rows.collect { r ->
      def (long n, long s) = count(r.c, opt.windows)
      [m: r.m, b: r.b, d: r.d, n: n, s: s, L: n ? s / n : 0d]
    }.findAll { it.n >= MIN_ROW }
    if (!links) return [flagged: [] as Set, rank: [], stats: [:]]
    def ds = links*.d.sort()
    def cut = [ds[(int)(ds.size() / 3)], ds[(int)(2 * ds.size() / 3)]]
    links.each { it.zone = it.d < cut[0] ? 0 : it.d < cut[1] ? 1 : 2 }
    def areaLoss = { Integer skip, int z ->
      def ls = links.findAll { it.zone == z && it.b != skip }*.L
      if (!ls) ls = links.findAll { it.zone == z }*.L
      Math.max(0.01d, ls ? median(ls) : 0.01d)
    }
    def lexp = (0..2).collect { areaLoss(null, it) }
    def stats = [:]
    links.groupBy { it.b }.each { b, ls ->
      def own = opt.selfOut ? (0..2).collect { areaLoss(b, it) } : lexp
      ls.each { it.Lx = own[it.zone] }
      double n = ls.sum { it.n }, s = ls.sum { it.s }
      double mu = ls.sum { it.n * it.Lx }, var = ls.sum { it.n * it.Lx * (1 - it.Lx) }
      double weighted = ls.sum { l -> l.n * (opt.weights == 'derived' ? 1 / (1 - l.Lx) : opt.weights[l.zone]) * (l.L - l.Lx) }
      stats[b] = [n: (long)n, Lobs: s / n, Lexp: mu / n, z: (s - mu) / Math.sqrt(var), score: 100 * Math.max(0d, weighted / n)]
    }
    disperse(stats, opt.disperse)
    def flagged = stats.findAll { k, v -> v.n >= MIN_OBS && v.z > Z_FIXED && v.score >= S_MIN }.keySet()
    return [flagged: flagged as Set, rank: ranking(stats), stats: stats, lexp: lexp, cut: cut]
  }

  // ---------------------------------------------------------------- response (live network)

  /**
   * Two-round confirmation. A relay becomes a suspect when flagged in two consecutive rounds,
   * so one noisy round accuses nobody. A suspect stays one (until an administrator reviews
   * it): once traffic avoids it, it produces no new evidence that could clear it.
   */
  static Set confirm(Set suspects, Set previous, Set flagged) {
    suspects + flagged.intersect(previous)
  }

  // ---------------------------------------------------------------- helpers

  static double median(List xs) {
    def a = xs.sort(false)
    int k = a.size().intdiv(2)
    return a.size() % 2 ? a[k] : (a[k - 1] + a[k]) / 2
  }

  private static List ranking(Map stats) {
    stats.collect { k, v -> [k, v.score] }.sort { -it[1] }
  }

  /** One evidence row from a CSV line written by record.groovy. */
  static Map parseRow(String[] f) {
    def c = new long[32], g = new double[32], s = new double[32]
    for (int i = 0; i < 32; i++) {
      c[i] = f[10 + i] as long
      g[i] = f[42 + i] as double
      s[i] = f[74 + i] as double
    }
    [m: f[6] as int, b: f[7] as int, d: f[8] as double, busy: f[9] as double, c: c, g: g, s: s]
  }
}
