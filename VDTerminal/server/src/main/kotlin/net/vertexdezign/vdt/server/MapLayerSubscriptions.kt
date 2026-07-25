package net.vertexdezign.vdt.server

/**
 * Which ground-layer raster planes the connected dashboards are showing, and therefore which ones the
 * mod should sweep at all (see the mod's `setMapLayers` command).
 *
 * The mod has one subscription; the server may have many dashboards, each showing its own overlay —
 * so this holds a set per WebSocket session and hands the mod their **union**. A session's selection
 * lives exactly as long as its socket: [forget] on disconnect is what makes closing the last dashboard
 * mean "nobody is looking", which is the state where the channel costs nothing.
 *
 * [publish] fires only when the union actually changes, so a second dashboard selecting the same plane
 * — or one reconnecting and re-sending what it already had — writes no command and doesn't disturb a
 * sweep in flight.
 *
 * Thread-safe: sessions come and go on their own coroutines.
 */
class MapLayerSubscriptions(
  private val publish: (List<String>) -> Unit,
) {
  private val bySession = mutableMapOf<Long, Set<String>>()
  private var union: Set<String> = emptySet()

  @Synchronized
  fun show(
    session: Long,
    ids: Collection<String>,
  ) {
    bySession[session] = ids.toSet()
    recompute()
  }

  @Synchronized
  fun forget(session: Long) {
    if (bySession.remove(session) != null) recompute()
  }

  /**
   * Compare what the mod says it is sweeping ([modActive]) against what the dashboards want, and
   * restate the command when they disagree. Called whenever the mod republishes its catalogue.
   *
   * This is what makes the subscription survive the things a fire-and-forget command cannot: the mod
   * deletes `commands.xml` at every map load (so anything sent while the game was at a menu or
   * loading is gone unread), and a server restart leaves the mod sweeping for dashboards that no
   * longer exist. Publishing only on change can't recover from either — the desire never changed,
   * only the mod's memory of it.
   *
   * Compared against [offered], the planes this map actually has, so a stale selection the mod
   * doesn't know (a layer id persisted by the app from a map that had it) is never asked for — that
   * would be a mismatch the mod could never resolve, and this would restate it on every catalogue
   * write forever.
   */
  @Synchronized
  fun reconcile(
    modActive: Collection<String>,
    offered: Collection<String>,
  ) {
    val want = union.intersect(offered.toSet())
    if (want == modActive.toSet()) return
    publish(want.sorted())
  }

  /** The union as last published; for tests and diagnostics. */
  @Synchronized
  fun current(): List<String> = union.sorted()

  private fun recompute() {
    val next = bySession.values.flatten().toSet()
    if (next == union) return
    union = next
    // Sorted, so the command the mod receives is a function of the SET alone — map iteration order
    // would otherwise rewrite the file with a reordered, identical list.
    publish(next.sorted())
  }
}
