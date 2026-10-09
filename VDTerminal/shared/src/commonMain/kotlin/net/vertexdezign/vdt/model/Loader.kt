package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * A tool on a loader's tool joint — the shovel, fork or grab on a front loader, wheel loader,
 * telehandler or skid steer (mod version 30, issue #169).
 *
 * Both readings are **raw**. [pitch] is the angle of the tool's root node. Nothing in the engine makes
 * that node parallel to a shovel floor or a fork's tines — but on every tool captured so far it is
 * within a few degrees of it (a shovel -0.69°, a pallet fork +0.19° lying flat), which is how GIANTS
 * models them. So the root node is the **default** level, and a reference the player sets per tool
 * model corrects the tool that is the exception. See [inclination].
 */
@Serializable
data class LoaderTool(
  /** The joint the tool hangs on; null for one this app does not know yet. */
  val joint: LoaderJoint? = null,
  /**
   * Which tool it is, by the tool specialization it carries rather than its modder-named `type`. Null
   * on a capture from before the mod reported it, or a kind this app does not know yet.
   */
  val kind: LoaderToolKind? = null,
  /** Degrees against the horizon, positive nose up. */
  val pitch: Float = 0f,
  /**
   * Metres to the nearest thing under the tool that is not part of the rig — ground, bale, pallet,
   * trailer. Negative when the only hit was above it (buried in a heap); null when nothing was within
   * the mod's 30 m reach.
   */
  val distance: Float? = null,
  /**
   * The raw [pitch] and [distance] this tool model read when the player said "this is level" — set by
   * [net.vertexdezign.vdt.ClientMessage.SetLoaderReference], kept by the mod per tool *model* and
   * shared by every copy of it. Null until set, and then the root node is level (see [inclination]).
   */
  val reference: LoaderReference? = null,
) {
  /** Degrees off level, positive nose up: off the player's [reference], or off the root node without one. */
  val inclination: Float get() = pitch - (reference?.pitch ?: 0f)

  /**
   * Metres above where the tool sat when it was zeroed, or the raw [distance] without a [reference] —
   * null only when nothing is under the tool within reach. A reference taken with nothing under the
   * tool zeroes the angle only.
   */
  val height: Float? get() = distance?.let { it - (reference?.distance ?: 0f) }
}

/** A tool model's zero (mod version 30): the raw readings it was set at. See [LoaderTool.reference]. */
@Serializable
data class LoaderReference(val pitch: Float = 0f, val distance: Float? = null)

/**
 * A loader tool by what it does. [FORK] is anything that carries what it picks up on tines — a pallet
 * fork and a bale spike both mount their load the same way — and [OTHER] a tool with none of the
 * tool specializations (a plate, a sweeper, a modded attachment).
 */
@Serializable
enum class LoaderToolKind { SHOVEL, FORK, BALE_GRAB, LOG_GRAB, OTHER }

/**
 * The joint a loader tool hangs on. [FORKLIFT] is no joint at all: a forklift's forks are part of the
 * machine, and the mod reads them off their pallet-mount node instead (export 32).
 */
@Serializable
enum class LoaderJoint { FRONTLOADER, TELEHANDLER, WHEEL_LOADER, SKID_STEER, LOADER_FORK, FORKLIFT }

/**
 * One front-loader-driven cylinder (mod version 30): where it is along its stroke. Carried by whatever
 * owns the cylinder — the loader on a tractor, the self-propelled loader itself, or a tool with a
 * clamp of its own.
 *
 * Not a world reading: the tilt cylinder's [travel] says where the cylinder is, not whether the tool
 * is level, because the arm under it moves the horizon. That is [LoaderTool]'s job.
 */
@Serializable
data class LoaderCylinder(
  val role: LoaderCylinderRole = LoaderCylinderRole.AUX,
  /** The engine's input axis name, e.g. `AXIS_FRONTLOADER_TOOL2` — what tells two AUX cylinders apart. */
  val axis: String = "",
  /**
   * Along the stroke, 0..1, oriented by the mod: 1 is the top for [LoaderCylinderRole.LIFT], curled back
   * for [LoaderCylinderRole.TILT], fully out for [LoaderCylinderRole.TELESCOPE]. [LoaderCylinderRole.AUX]
   * is the engine's own direction, which means whatever the tool makes of it.

   */
  val travel: Float = 0f,
  /**
   * The engine icon the author gave the cylinder's control (`GRABBER_OPEN_CLOSE`, `TOOL_OPEN_CLOSE`,
   * `WORKING_WIDTH_TRANSLATE_X`, ...): the one place a tool's own cylinder says what it does, since its
   * axis does not. Null on an icon the mod drew itself, and before export version 32.
   */
  val icon: String? = null,
  /**
   * [LoaderCylinderRole.TIP] only: how far the cylinder has turned the bucket from its travel-0 end, in
   * degrees, positive nose up — the turn the tool's root-node pitch cannot see, since the root is the
   * frame the bucket turns on. Null where the tip is not a plain rotation.
   */
  val angle: Float? = null,
  /**
   * [LoaderCylinderRole.TILT] only: the tilt carries the lift, so the whole mast leans with the forks
   * (a Jungheinrich EFG S50) rather than the forks alone (a Hubtex MAXX 45).
   */
  val carriesLift: Boolean = false,
)

/**
 * What a cylinder does, read off the input axis that drives it. [AUX] is `TOOL2`..`TOOL5`, whose
 * meaning is the tool's own — a clamp on one, a top-hold on another. [TIP] is one of those that turns
 * the bucket itself on the tool's frame (a high-tip bucket), told apart by what it carries. [SHIFT] is a
 * forklift's sideshift: its second arm axis where that moves only the carriage, not the whole mast,
 * which stays [TELESCOPE] (the reach). Both new in export 32; an older terminal reads them as [AUX].
 */
@Serializable
enum class LoaderCylinderRole { LIFT, TELESCOPE, SHIFT, TILT, TIP, AUX }
