/**
 * Detection methods. All of them read the SAME evidence counters recorded by
 * MonitorNode, so they are compared on identical traffic, topology and attacks.
 *
 * Evidence row: [m: monitor, b: relay, d: m-b distance (m), busy: monitor busy ratio,
 *                c: long[32] counters, g: double[32] channel-only expected forwards]
 *                (counter layout: MonitorNode.idx)
 *
 * Methods (each returns [flagged: Set, rank: List<[node, score]>, stats: Map node -> Map]):
 *   fixed - watchdog after Marti et al. (2000) and Chandavarkar & Suresh (2021): the sender
 *           watches its next hop, every miss counts, accuse if the miss rate > 30%.
 *   cad   - CAD-style baseline adapted from Shila et al. (2010), downstream monitoring only:
 *           normal loss = channel loss + collision probability estimated from the monitor's
 *           own channel-busy ratio (pure-ALOHA adaptation of their Sec. V), binomial test.
 *   ours  - PROPOSED: observability filter C1+C3+C4, expected forwards from distances plus
 *           an area-median normal loss (leave-self-out, baseline cleaning), area-weighted
 *           trust O/E, over-dispersion correction and a dynamic threshold (see ours()).
 * Ablations of ours (compare.groovy): area (no filter), ours-nopeel, ours-mean.
 */
class Detectors {

  static final double FIXED_THRESHOLD = 0.3   // fixed watchdog: miss rate that triggers an accusation
  static final double Z_FIXED = 2.0           // CAD-style: z threshold
  static final double ALPHA = 0.05            // ours: default false-alarm budget per decision (tuned in compare.groovy)
  static final int MIN_OBS = 20               // judge a relay only on >= 20 windows
  static final int MIN_ROW = 5                // a monitor-relay link joins the area baseline with >= 5 windows
  static final double S_MIN = 10.0            // and at least 10% estimated drop rate
  static final double EDGE_LOSS = 0.15        // channel frame loss at the edge of range (DistanceLossChannel)
  static final double CHI2_MEDIAN = 0.455     // median of z^2 for an honest relay (chi-square, 1 dof)

  /** Our method and its ablations. */
  static final Map OURS = [windows: 'filter', peel: true]
  static final Map OPTIONS = [
    ours         : OURS,
    area         : OURS + [windows: 'all'],   // no observability filter
    'ours-nopeel': OURS + [peel: false],      // no baseline cleaning (weak with several grayholes)
    'ours-mean'  : OURS + [area: 'mean'],     // area baseline by weighted average, not median
  ]

  static Map run(String method, List<Map> rows, Map override = [:]) {
    if (method == 'fixed') return fixed(rows, override)
    if (method == 'cad') return cad(rows, override)
    def opt = OPTIONS[method] + override
    return opt.peel ? peel(rows, opt) : ours(rows, opt)
  }

  /**
   * One decision round of a method at threshold th (fixed: miss rate, cad: z, ours: alpha).
   * Ours also leaves the confirmed suspects out of its "normal loss" baseline.
   */
  static Map decide(String method, List<Map> rows, double th, Set suspects) {
    run(method, rows, [threshold: th, zCrit: th, alpha: th, exclude: suspects])
  }

  /** Frame loss over distance d from the channel alone: e(d) = 0.15 (d / R)^2 (DistanceLossChannel). */
  static double channelLoss(double d) { EDGE_LOSS * Math.pow(d / Topology.RANGE, 2) }

  // ---------------------------------------------------------------- window rules

  /**
   * Which of the 32 counters a rule keeps:
   *   sender - the sender's windows (classic watchdog), any eligibility
   *   all    - every window
   *   filter - C1, C3 and C4 hold (proposed; C2 ignored, see METHOD.md)
   */
  static boolean uses(int i, String rule) {
    switch (rule) {
      case 'sender': return i < 16
      case 'all': return true
      case 'filter': return (i & 10) == 0
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

  /** Channel-only expected forwards of one link under a rule. */
  static double expected(double[] g, String rule) {
    double e = 0
    for (int i = 0; i < g.length; i++) if (uses(i, rule)) e += g[i]
    return e
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
    def stats = [:]
    rows.groupBy { it.b }.each { b, ls ->
      double n = 0, s = 0, mu = 0, var = 0, weighted = 0
      ls.each { r ->
        def (long ni, long si) = count(r.c, 'sender')
        if (ni == 0) return
        double e = Math.min(0.99d, channelLoss(r.d as double))
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
   * Baseline cleaning for several grayholes. Relays whose estimated drop is >= S_MIN, and
   * confirmed suspects (opt.exclude), do not help define "normal" loss: their links leave the
   * area median, and the detector runs again (at most 3 rounds, until the set is stable).
   * Without it, several grayholes in one area make honest relays look "too good", inflate
   * phi and hide every grayhole although they still rank first.
   */
  static Map peel(List<Map> rows, Map opt) {
    def r = ours(rows, opt)
    Set out = [] as Set
    for (int round = 0; round < 3; round++) {
      Set suspicious = r.stats.findAll { k, v -> v.n >= MIN_OBS && v.score >= S_MIN }.keySet() as Set
      if (suspicious == out) break
      out = suspicious
      r = ours(rows, opt + [exclude: out + (opt.exclude ?: [])])
    }
    return r + [peeled: out]
  }

  /**
   * For each monitor-relay link i, under the window rule:
   *   O_i = forwards heard,  G_i = channel-only expected forwards (expected())
   *   kappa_i = max(0, 1 - O_i / G_i)      loss the channel does not explain (collisions)
   * kappa_area = median kappa_i over links in the same distance area (near / medium / far
   *   tertiles), leaving out the judged relay's own links and excluded relays,
   * E_i = G_i (1 - kappa_area)             forwards an honest relay should show,
   * T_B = sum O / sum E (trust) = the E-weighted average of each monitor's trust O_i / E_i
   *   (area-weighted averaging: each monitor counts by the forwards its area expects),
   * S_B = 100 max(0, 1 - T_B) (estimated drop %),
   * z_B = (sum E - sum O) / sqrt(sum E_i (1 - E_i / n_i)), rescaled by over-dispersion (disperse()),
   * flagged if n_B >= MIN_OBS, S_B >= S_MIN and z_B > z* = upper-normal quantile of alpha / k,
   *   k = number of judged relays (a dynamic threshold; alpha is tuned, not a guarantee).
   */
  static Map ours(List<Map> rows, Map opt) {
    def links = rows.collect { r ->
      def (long n, long s) = count(r.c, opt.windows)
      double g = expected(r.g, opt.windows)
      [m: r.m, b: r.b, d: r.d, n: n, o: n - s, g: g, k: g > 0 ? Math.max(0d, 1 - (n - s) / g) : 0d]
    }.findAll { it.n >= MIN_ROW && it.g > 0 }
    if (!links) return [flagged: [] as Set, rank: [], stats: [:]]

    def ds = links*.d.sort()
    def cut = [ds[(int)(ds.size() / 3)], ds[(int)(2 * ds.size() / 3)]]
    links.each { it.zone = it.d < cut[0] ? 0 : it.d < cut[1] ? 1 : 2 }
    def exclude = (opt.exclude ?: []) as Set
    // Normal residual loss of an area: median of the other relays' links (robust to an
    // attacker's lossy links). 'mean' = evidence-weighted average 1 - sum(O)/sum(G) (ablation).
    def areaKappa = { int skip, int z ->
      def ls = links.findAll { it.zone == z && it.b != skip && !(it.b in exclude) }
      if (!ls) ls = links.findAll { it.zone == z }     // too few links: whole area (fallback)
      if (!ls) return 0d
      opt.area == 'mean' ? Math.max(0d, 1 - ls.sum { it.o } / ls.sum { it.g }) : median(ls*.k)
    }

    def stats = [:]
    links.groupBy { it.b }.each { b, ls ->
      def own = (0..2).collect { areaKappa(b, it) }
      double n = ls.sum { it.n }, o = ls.sum { it.o }
      double e = ls.sum { it.g * (1 - own[it.zone]) }
      if (e <= 0) return  // no expected forwards: the drop rate is not identifiable
      double var = ls.sum { l -> double el = l.g * (1 - own[l.zone]); el * Math.max(0.01d, 1 - el / l.n) }
      stats[b] = [n: (long)n, monitors: ls.size(), trust: o / e,
                  z: (e - o) / Math.sqrt(var), score: 100 * Math.max(0d, 1 - o / e)]
    }
    disperse(stats)
    def judged = stats.findAll { k, v -> v.n >= MIN_OBS }
    double zStar = upperQuantile((opt.alpha != null ? opt.alpha : ALPHA) / Math.max(1, judged.size()))
    def flagged = judged.findAll { k, v -> v.z > zStar && v.score >= S_MIN }.keySet() as Set
    return [flagged: flagged, rank: ranking(stats), stats: stats]
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
   * Over-dispersion factor phi = median z^2 / 0.455 over judged relays with z < 0 (all judged
   * relays if fewer than 2); rescales every z by 1/sqrt(phi). A grayhole raises loss, so it
   * usually has z > 0 and does not inflate phi directly. An empirical scale correction only.
   */
  static double disperse(Map stats) {
    def judged = stats.values().findAll { it.n >= MIN_OBS }*.z
    def neg = judged.findAll { it < 0 }
    def zs = (neg.size() >= 2 ? neg : judged).collect { it * it }
    if (!zs) return 1
    double phi = Math.max(1d, median(zs) / CHI2_MEDIAN)
    stats.values().each { it.z = it.z / Math.sqrt(phi) }
    return phi
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

  /** One evidence row from a CSV line written by record.groovy (columns c0..c31, then g0..g31). */
  static Map parseRow(String[] f) {
    def c = new long[32], g = new double[32]
    for (int i = 0; i < 32; i++) {
      c[i] = f[10 + i] as long
      g[i] = f[42 + i] as double
    }
    [m: f[6] as int, b: f[7] as int, d: f[8] as double, busy: f[9] as double, c: c, g: g]
  }
}
