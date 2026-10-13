/**
 * Random deployment and shortest-hop routing, shared by record.groovy and network.groovy.
 * Node 1 is the sink (surface buoy) at the origin; sensors are spread over
 * WIDTH x HEIGHT metres on the sink's side of the field.
 */
class Topology {

  static final int SINK = 1
  static final double RANGE = 1500     // m, communication range
  static final double WIDTH = 4000, HEIGHT = 4000   // default field (m)

  final Map<Integer, List<Double>> pos   // address -> [x, y]

  Topology(int sensors, long seed, double width = WIDTH, double height = HEIGHT) {
    def rnd = new Random(seed)
    // redraw until every sensor can reach the sink
    while (true) {
      pos = [(SINK): [0d, 0d]]
      (2..sensors + 1).each { pos[it] = [rnd.nextDouble() * width, (rnd.nextDouble() - 0.5) * height] }
      if (routes([] as Set).size() == sensors) break
    }
  }

  double dist(int a, int b) { Math.hypot(pos[a][0] - pos[b][0], pos[a][1] - pos[b][1]) }

  List<Integer> neighbours(int a) { pos.keySet().findAll { it != a && dist(a, it) <= RANGE } as List }

  /** Hop count to the sink for every node reachable without using `excluded`. */
  Map<Integer, Integer> hops(Set excluded) {
    def h = [(SINK): 0]
    def frontier = [SINK]
    for (int level = 1; frontier; level++) {
      frontier = pos.keySet().findAll { v ->
        h[v] == null && !(v in excluded) && frontier.any { dist(it, v) <= RANGE }
      }
      frontier.each { h[it] = level }
    }
    return h
  }

  /**
   * Next hop for every node that can still reach the sink: the nearest neighbour
   * one hop closer to the sink. Excluded nodes neither relay nor get a route.
   */
  Map<Integer, Integer> routes(Set excluded) {
    def h = hops(excluded)
    h.keySet().findAll { it != SINK }.collectEntries { v ->
      [v, h.keySet().findAll { h[it] == h[v] - 1 && dist(it, v) <= RANGE }.min { dist(it, v) }]
    }
  }

  /**
   * Routes that avoid `suspects` as relays wherever possible. A node with a
   * suspect-free path to the sink uses the shortest one (suspects themselves too,
   * so their own data still arrives). A node with no such path keeps its normal
   * route: a lossy path beats no path. No loops: suspect-free routes only lead to
   * suspect-free nodes, and fallback routes always move one hop closer to the sink.
   */
  Map<Integer, Integer> routesAvoiding(Set suspects) {
    def clean = hops(suspects)
    def normal = routes([] as Set)
    pos.keySet().findAll { it != SINK }.collectEntries { v ->
      def nbrs = neighbours(v).findAll { clean[it] != null && !(it in suspects) }
      if (!nbrs) return [v, normal[v]]
      int best = nbrs.collect { clean[it] }.min()
      [v, nbrs.findAll { clean[it] == best }.min { dist(v, it) }]
    }
  }

  /** Number of other nodes whose packets pass through each relay. */
  Map<Integer, Integer> load(Map<Integer, Integer> nextHop) {
    def through = [:].withDefault { 0 }
    nextHop.keySet().each { v ->
      for (def u = nextHop[v]; u != null && u != SINK; u = nextHop[u]) through[u]++
    }
    return through
  }
}
