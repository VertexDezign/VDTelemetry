package net.vertexdezign.vdt

/**
 * The format version of every channel this terminal was built against — what lets it tell a player
 * that the mod and the terminal do not belong together, instead of panels quietly staying empty.
 *
 * Mod and terminal are released together but installed apart: the mod comes from ModHub, whose review
 * can hold an update back for days, the terminal from GitHub. So "new terminal, older mod" is a normal
 * state, not a mistake, and it is the one a player cannot diagnose alone — the lenient parser reads an
 * old file without complaint and the missing fields simply never arrive.
 *
 * Each entry is the mod-side `VERSION` constant of that channel (every channel versions on its own,
 * see CLAUDE.md), keyed by the channel's [ServerMessage] name. They move together with the Kotlin model:
 * bumping a channel on the Lua side means bumping it here. `ModFormatTest` reads the Lua constants and
 * fails when the two disagree, so this table cannot drift silently.
 *
 * Exact match, both ways. An older channel is "update the mod"; a newer one is "update the terminal" —
 * the parser tolerates unknown fields, but a bumped version can also mean a field changed meaning, and
 * either way the advice is the right one.
 */
object ModFormat {
  /** Channel → the format version this build reads, as the mod writes it (a decimal string). */
  val expected: Map<String, Int> =
    mapOf(
      "telemetry" to 36,
      "map" to 2,
      "mapVehicles" to 2,
      "mapLayers" to 5,
      "gpsCourse" to 1,
      "fieldInfo" to 1,
      "production" to 2,
      "storage" to 4,
      "husbandry" to 2,
      "fleet" to 3,
      "prices" to 1,
      "missions" to 2,
      "finance" to 1,
      "cropCalendar" to 1,
      "weather" to 1,
      "taskList" to 1,
      "cropRotation" to 1,
      "autoDrive" to 1,
      "invoices" to 1,
    )

  enum class Mismatch {
    /** The mod writes an older format than this terminal reads: update the mod. */
    ModOutdated,

    /** The mod writes a newer format than this terminal reads: update the terminal. */
    TerminalOutdated,
  }

  /**
   * How [version], as read off [channel]'s file, compares with what this build expects; null when it
   * matches, or when there is nothing to compare — an unknown channel, or a version that is blank or not
   * a number (an empty default means the file had none, which is not evidence either way).
   */
  fun check(channel: String, version: String): Mismatch? {
    val want = expected[channel] ?: return null
    val got = version.trim().toIntOrNull() ?: return null
    return when {
      got < want -> Mismatch.ModOutdated
      got > want -> Mismatch.TerminalOutdated
      else -> null
    }
  }
}
