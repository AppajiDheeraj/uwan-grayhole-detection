import java.nio.ByteBuffer
import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.phy.*

/**
 * Network node: generates data for the sink, relays packets, drops a fraction of
 * them if it is a grayhole, and monitors EVERY neighbouring relay by overhearing.
 *
 * Packets: data = 8 bytes [origin (1), sequence (2), generation time ms (4), hops (1)];
 * reports = that header + busy fraction (4), then one 17-byte entry per watched relay
 * [relay (1), windows (4), misses (4), expected forwards (8)], at most 6 entries (114 bytes)
 * so a report fits one 128-byte DATA frame. Entries carry cumulative evidence.
 *
 * Watch window. When monitor M sends packet P to relay B, or overhears another node
 * A send P to B, M computes from known positions and the speed of sound:
 *   P is at B during      I = [arrEnd - frame, arrEnd]
 *   B's forward is at M   W = [arrEnd + d(B,M)/c,  arrEnd + fwdWait + frame + d(B,M)/c]
 * When W closes, the window goes into one of 32 counters (see idx):
 *   sender  - M sent P itself (classic watchdog) or only overheard it
 *   C1, C3  - M did not transmit during W (half-duplex), and M did not hear B transmit
 *             during I (B was deaf, so B never got P)
 *   C2      - M received no corrupted frame during W. Kept as a SEPARATE bit: a corrupted
 *             frame can be B's own forward, so C2 depends on what B did (see docs/METHOD.md)
 *   C4      - no frame known to M (heard from another node, or sent by M) reached B during
 *             I, i.e. P did not collide at B
 *   heard   - M overheard B forwarding P inside W
 * Next to each counter M adds up, from distances only,
 *   g = (1 - e(d_AB)) (1 - e(d_BM))   the chance an honest forward reaches M if only the channel loses frames.
 * M also measures its busy time (transmitting or receiving), used by the CAD-style baseline.
 */
class MonitorNode extends UnetAgent {

  static final int SINK = Topology.SINK
  static final double SOUND = 1500.0   // m/s, ProtocolChannelModel default
  static final int CLASSES = 32
  static final int HEADER = 12, ENTRY = 17, MAX_ENTRIES = 6   // report layout (see above)

  /** Counter index: bit 4 = overheard (not sender), 3 = C1/C3 failed, 2 = C2 failed, 1 = C4 failed, 0 = missed. */
  static int idx(boolean sender, boolean c13, boolean c2, boolean c4, boolean heard) {
    (sender ? 0 : 16) + (c13 ? 0 : 8) + (c2 ? 0 : 4) + (c4 ? 0 : 2) + (heard ? 0 : 1)
  }

  // configuration (Groovy named arguments: new MonitorNode(addr: 3, nextHop: 1, ...))
  int addr
  Integer nextHop              // null at the sink and at isolated nodes; the admin may change it
  double genRate               // packets per second generated here
  double dropProb              // grayhole drop probability (0 = honest)
  Topology topo
  long fwdWait = 2000          // ms a relay may queue a packet before forwarding
  long checkpointMs = 1800000  // ms between evidence snapshots
  Closure onRound = null       // live network: the administrator's decision, run at the sink every checkpoint
  String reportRule = null    // live network: sender for baselines, filter for ours; null disables reports

  // statistics read by the simulation scripts
  int generated = 0, txCount = 0, lateHeard = 0
  int reportsSent = 0, reportsDelivered = 0, reportTx = 0   // reportTx: report frames sent (also in txCount)
  long busyMs = 0
  List<long[]> delivered = []                                          // sink: [origin, latency ms, hops, generation ms]
  List<Long> genTimes = []                                             // generation time of each data packet
  Map<Integer, long[]> ev = [:].withDefault { new long[CLASSES] }      // relay -> counters
  Map<Integer, double[]> expect = [:].withDefault { new double[CLASSES] }  // relay -> sum of g
  List<Map> snapshots = []
  Map<String, Map> reportRows = [:]   // sink only: latest DELIVERED report for each monitor/relay
  List<Long> fwdDelays = []                                            // forward_delay.groovy: how long relays hold packets

  private AgentID phy
  private long frameMs = 1020
  private long busyUntil = 0
  private int seq = 0
  private Map<String, Map> txPending = [:]           // TxFrameReq id -> [relay, id]
  private Map<String, Map> windows = [:]              // "relay:origin:seq" -> window
  private Set<String> closed = new HashSet<>()
  private List<long[]> myTx = [], bad = []
  private Map<Integer, List<long[]>> heardTx = [:].withDefault { [] }

  @Override
  void startup() {
    phy = agentForService(Services.PHYSICAL)
    subscribe topic(phy)                  // frames overheard for other nodes, tx/rx events
    subscribe topic(Topics.DATAGRAM)      // frames addressed to this node
    if (genRate > 0) add new PoissonBehavior((long)(1000 / genRate), { generate() })
    add new TickerBehavior(200, { closeWindows() })
    add new TickerBehavior(checkpointMs, {
      snapshots << snapshot(currentTimeMillis())
      if (reportRule != null) sendReports()
      if (onRound) onRound(currentTimeMillis())
    })
  }

  /** Copy of the evidence at time t (ms), as the administrator would receive it in a report. */
  Map snapshot(long t) {
    def copy = { Map m -> m.collectEntries { k, v -> [k, v.clone()] } }
    [t: t, ev: copy(ev), expect: copy(expect), busyMs: busyMs]
  }

  /**
   * Freeze evidence at the checkpoint and bundle it: one frame per monitor (up to 6 relays).
   * Jitter transmissions over 10 min to reduce collisions between reports. The sink can
   * use its own observations immediately.
   */
  void sendReports() {
    long t = currentTimeMillis()
    def relays = ev.keySet().findAll { Detectors.count(ev[it], reportRule)[0] > 0 }.sort()
    relays.collate(MAX_ENTRIES).each { group ->
      byte[] pkt = packReport(group, t)
      if (addr == SINK) acceptReport(pkt, t)
      else add new WakerBehavior(AgentLocalRandom.current().nextInt(600000), {
        reportsSent++
        transmit(pkt)
      })
    }
  }

  byte[] packReport(List<Integer> relays, long t) {
    seq = (seq + 1) & 0xFFFF
    def buf = ByteBuffer.allocate(HEADER + ENTRY * relays.size())
      .put((byte)addr).putShort((short)seq).putInt((int)t).put((byte)0).putFloat((float)(busyMs / (double)t))
    relays.each { b ->
      def (long n, long miss) = Detectors.count(ev[b], reportRule)
      double g = Detectors.expected(expect[b], reportRule)
      buf.put((byte)b).putInt(Math.toIntExact(n)).putInt(Math.toIntExact(miss)).putDouble(g)
    }
    return buf.array()
  }

  static boolean isReport(byte[] pkt) {
    int k = (pkt.length - HEADER).intdiv(ENTRY)
    pkt.length == HEADER + ENTRY * k && k >= 1 && k <= MAX_ENTRIES
  }

  /** Validate received evidence; invalid entries, duplicates and older reports never add observations. */
  boolean acceptReport(byte[] pkt, long now) {
    if (!isReport(pkt)) return false
    def buf = ByteBuffer.wrap(pkt)
    int m = buf.get(0) & 0xFF
    long t = buf.getInt(3)
    double busy = buf.getFloat(8)
    if (!topo.pos.containsKey(m) || t <= 0 || t > now || !Double.isFinite(busy) || busy < 0 || busy > 1) return false
    boolean accepted = false
    for (int at = HEADER; at < pkt.length; at += ENTRY) {
      int b = buf.get(at) & 0xFF
      long n = buf.getInt(at + 1), miss = buf.getInt(at + 5)
      double g = buf.getDouble(at + 9)
      String key = "$m:$b"
      if (!topo.pos.containsKey(b) || m == b || b == SINK || topo.dist(m, b) > Topology.RANGE ||
          n <= 0 || miss < 0 || miss > n || !Double.isFinite(g) || g < 0 || g > n ||
          (reportRows[key] != null && reportRows[key].t >= t)) continue
      def c = new long[CLASSES], expected = new double[CLASSES]
      c[0] = n - miss; c[1] = miss; expected[0] = g
      reportRows[key] = [t: t, m: m, b: b, d: topo.dist(m, b), busy: busy,
                         c: c, g: expected]
      accepted = true
    }
    return accepted
  }

  /** Latest delivered report per link. Counters are cumulative, so a lost report only delays evidence. */
  List<Map> receivedEvidence() { reportRows.values() as List }

  private long delay(int a, int b) { Math.round(1000 * topo.dist(a, b) / SOUND) }

  private void generate() {
    generated++; genTimes << currentTimeMillis()
    seq = (seq + 1) & 0xFFFF
    def b = ByteBuffer.allocate(8).put((byte)addr).putShort((short)seq)
                      .putInt((int)currentTimeMillis()).put((byte)0)
    transmit(b.array())
  }

  private void transmit(byte[] pkt) {
    if (nextHop == null) return           // isolated: no route to the sink
    def req = new TxFrameReq(to: nextHop, type: Physical.DATA, data: pkt)
    def rsp = phy << req
    if (rsp?.performative != Performative.AGREE) return
    txPending[req.messageID] = [relay: nextHop, id: pktId(pkt), report: pkt.length > 8]
  }

  private void addBusy(long start, long end) {
    if (end <= busyUntil) return
    busyMs += end - Math.max(start, busyUntil)
    busyUntil = end
  }

  @Override
  void processMessage(Message msg) {
    long now = currentTimeMillis()
    if (msg instanceof TxFrameStartNtf) {
      frameMs = (long)(msg.txDuration / 1000)
      myTx << ([now, now + frameMs] as long[])
      addBusy(now, now + frameMs)
    } else if (msg instanceof RxFrameStartNtf) {
      addBusy(now, now + (long)(msg.rxDuration / 1000))
    } else if (msg instanceof TxFrameNtf) {
      txCount++
      def p = txPending.remove(msg.inReplyTo)
      if (p?.report) reportTx++
      if (p) openWindow(p.relay, p.id, now + delay(addr, p.relay), addr, true)
    } else if (msg instanceof BadFrameNtf) {
      bad << ([now - frameMs, now] as long[])
    } else if (msg instanceof RxFrameNtf && msg.type == Physical.DATA && msg.data && (msg.data.length == 8 || isReport(msg.data))) {
      int from = msg.from
      long txEnd = now - delay(from, addr)
      heardTx[from] << ([txEnd - frameMs, txEnd] as long[])
      String id = pktId(msg.data)
      String key = "$from:$id".toString()
      def w = windows[key]
      if (w) {
        w.heard = true
        fwdDelays << txEnd - frameMs - w.arrEnd
      } else if (closed.contains(key)) {
        lateHeard++
      }
      if (msg.to == addr) onPacket(msg.data)
      else openWindow(msg.to, id, txEnd + delay(from, msg.to), from, false)
    }
  }

  private void openWindow(int relay, String id, long arrEnd, int src, boolean sender) {
    if (relay == SINK || relay == addr || !topo.pos[relay] || topo.dist(addr, relay) > Topology.RANGE) return
    long back = delay(relay, addr)
    windows["$relay:$id".toString()] = [relay: relay, id: id, src: src, sender: sender, heard: false,
      arrStart: arrEnd - frameMs, arrEnd: arrEnd,
      wStart: arrEnd + back, wEnd: arrEnd + fwdWait + frameMs + back]
  }

  private void onPacket(byte[] pkt) {
    def b = ByteBuffer.wrap(pkt)
    boolean report = pkt.length > 8
    if (addr == SINK) {
      if (report) { reportsDelivered++; acceptReport(pkt, currentTimeMillis()) }
      else delivered << ([b.get(0) & 0xFF, currentTimeMillis() - b.getInt(3), (b.get(7) & 0xFF) + 1, b.getInt(3)] as long[])
      return
    }
    if (dropProb > 0 && AgentLocalRandom.current().nextDouble() < dropProb) return   // grayhole drops it
    pkt[7] = (byte)((pkt[7] & 0xFF) + 1)
    transmit(pkt)
  }

  private void closeWindows() {
    long now = currentTimeMillis()
    def it = windows.entrySet().iterator()
    while (it.hasNext()) {
      def e = it.next()
      def w = e.value
      if (now < w.wEnd + 50) continue
      boolean c13 = !overlaps(myTx, w.wStart, w.wEnd) && !overlaps(heardTx[w.relay], w.arrStart, w.arrEnd)
      boolean c2 = !overlaps(bad, w.wStart, w.wEnd)
      boolean c4 = relayClear(w)
      int i = idx(w.sender, c13, c2, c4, w.heard)
      double eAB = Detectors.channelLoss(topo.dist(w.src, w.relay)), eBM = Detectors.channelLoss(topo.dist(w.relay, addr))
      ev[w.relay][i]++
      expect[w.relay][i] += (1 - eAB) * (1 - eBM)
      closed << e.key
      it.remove()
    }
    long old = now - 120000
    myTx.removeAll { it[1] < old }
    bad.removeAll { it[1] < old }
    heardTx.values().each { l -> l.removeAll { it[1] < old } }
  }

  /**
   * C4: true if no frame known to M reached relay B while P was arriving there.
   * A frame sent by node C during [t0, t1] is at B during [t0, t1] + d(C,B)/c.
   * Known frames: those M received cleanly from any C other than P's sender and B,
   * and M's own transmissions (when M is not P's sender).
   */
  private boolean relayClear(Map w) {
    int b = w.relay
    def hits = { int c, List<long[]> spans ->
      if (c == w.src || c == b || topo.dist(c, b) > Topology.RANGE) return false
      long shift = delay(c, b)
      spans.any { it[0] + shift < w.arrEnd && it[1] + shift > w.arrStart }
    }
    !hits(addr, myTx) && !heardTx.any { c, spans -> hits(c, spans) }
  }

  private static boolean overlaps(List<long[]> spans, long a, long b) {
    spans.any { it[0] < b && it[1] > a }
  }

  private static String pktId(byte[] pkt) {
    def b = ByteBuffer.wrap(pkt)
    "${b.get(0) & 0xFF}:${b.getShort(1) & 0xFFFF}".toString()
  }
}
