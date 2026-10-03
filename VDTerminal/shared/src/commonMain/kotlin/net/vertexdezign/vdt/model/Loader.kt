package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * A tool on a loader's tool joint — the shovel, fork or grab on a front loader, wheel loader,
 * telehandler or skid steer (mod version 30, issue #169).
 *
 * Both readings are **raw**. [pitch] is the angle of the tool's root node, and no node on a tool is
 * guaranteed to lie parallel to the shovel floor or the fork tines, so 0° here is *not* level: level
 * is a reference the player sets per tool model. Nothing may present [pitch] as "level" on its own.
 */
@Serializable
data class LoaderTool(
  /** The joint the tool hangs on; null for one this app does not know yet. */
  val joint: LoaderJoint? = null,
  /** Degrees against the horizon, positive nose up. */
  val pitch: Float = 0f,
  /**
   * Metres to the nearest thing under the tool that is not part of the rig — ground, bale, pallet,
   * trailer. Negative when the only hit was above it (buried in a heap); null when nothing was within
   * the mod's 10 m reach.
   */
  val distance: Float? = null,
)

@Serializable
enum class LoaderJoint { FRONTLOADER, TELEHANDLER, WHEEL_LOADER, SKID_STEER, LOADER_FORK }

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
)

/**
 * What a cylinder does, read off the input axis that drives it. [AUX] is `TOOL2`..`TOOL5`, whose
 * meaning is the tool's own — a clamp on one, a top-hold on another.
 */
@Serializable
enum class LoaderCylinderRole { LIFT, TELESCOPE, TILT, AUX }
