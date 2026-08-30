package net.vertexdezign.vdt.app.state

/**
 * Which ground-layer planes this dashboard is showing, as a union across everything that wants one.
 *
 * The mod sweeps only the planes something is subscribed to — a sweep is a quarter of a million
 * engine density-map reads, so nothing is swept on the off chance. That makes the subscription a
 * live claim rather than a setting, and it has to be a *union*: a dashboard can hold two map widgets
 * on different planes, and one of them leaving composition must not report "nobody is looking" while
 * the other still is. Since the field overview counts the growth plane it never draws, subscribers
 * are not all map panels either.
 *
 * Keyed by an opaque token per subscriber, which is what makes two instances of the same screen
 * independent. Register on selection, and **always drop on dispose** — a token that is never removed
 * keeps the mod sweeping a plane nobody is looking at, for as long as the tab is open.
 *
 * Module-level rather than `remember`ed, because outliving any one composition is the whole point.
 * Only touched from the composition, which is single-threaded on wasm, so no synchronization is
 * needed. Nothing persists it: a subscription is about what is on screen right now.
 *
 * The server does the same thing one level up — it unions across connected sessions
 * (`MapLayerSubscriptions`) and restates the command whenever the mod's catalogue disagrees, which
 * is what makes this survive a channel the mod empties at every map load.
 */
object LayerSubscriptions {
  private val live = mutableMapOf<Any, List<String>>()

  /**
   * Record one subscriber's planes — or, with a null [selection], drop it — and return the union to
   * report to the server.
   *
   * The result is sorted and de-duplicated so an unchanged union is an equal list, and the caller can
   * send it unconditionally without the server seeing a stream of identical commands.
   */
  fun union(subscriber: Any, selection: List<String>?): List<String> {
    if (selection == null) live.remove(subscriber) else live[subscriber] = selection
    return live.values.flatten().distinct().sorted()
  }
}
