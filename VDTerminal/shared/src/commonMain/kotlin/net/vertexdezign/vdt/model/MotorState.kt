package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * What the engine is doing, in the game's own four states (its `MotorState` enum, mod version 14 on).
 *
 * The middle two are the ones worth reading carefully. [IGNITION] is the key turned with the starter
 * untouched — where an ignition lock rests, and where a machine sits with its dashboard lit and its
 * engine silent. [STARTING] is the starter cranking, which lasts as long as that machine's start
 * duration. Neither is a running engine: **only [ON] is**, and it is the one state the game's own
 * `getIsMotorStarted` answers yes to.
 *
 * So a "is it running" test is `== ON` (or [isRunning]), never `!= OFF` — the mod up to version 13
 * had no IGNITION at all and called a cranking engine ON, which made `!= OFF` look right for as long
 * as the value it read was wrong.
 */
@Serializable
enum class MotorState {
  OFF,
  IGNITION,
  STARTING,
  ON,
  ;

  /** Whether the engine is actually running — see the note above on why cranking doesn't count. */
  val isRunning: Boolean
    get() = this == ON
}
