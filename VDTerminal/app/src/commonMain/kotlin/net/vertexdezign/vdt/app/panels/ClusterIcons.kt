package net.vertexdezign.vdt.app.panels

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The cluster's telltale glyphs, drawn rather than borrowed from Material.
 *
 * A telltale is not an app icon. The shapes a driver reads without thinking are the ISO ones printed
 * on a real lens — the beam with its rays, the (P), the axle with its differential — and Material's
 * set has none of them; it has a lightbulb, a parking sign and a padlock, which are *about* those
 * things rather than being them. This is a A-pillar cluster after a John Deere 6R (issue #49), so it
 * uses the cluster's own vocabulary — and where the machine appears in a glyph it is drawn the way
 * that lamp's own subject is seen: from the side for the lights, from above for the drivetrain and
 * the reverser, where front and rear are what the lamp is distinguishing.
 *
 * Every glyph is a **fill** on a 24×24 viewport, no strokes. A stroke's width is in the vector's own
 * units and its caps and joins are another thing to get right per renderer; a filled outline is one
 * shape that scales exactly, and these are drawn once and then shown anywhere from 14dp to 48dp.
 * Colour is [Color.Black] throughout because `Icon` tints the whole thing — the lamp's colour lives
 * on [Telltale], not here.
 */
object ClusterIcons {
  /** Signal arrows. Chunky and near the full viewport: they are the two lamps read from furthest off-axis. */
  val TurnLeft = telltale("TurnLeft") { fill("M13.5 3.5 L2.5 12 L13.5 20.5 L13.5 15.6 L21 15.6 L21 8.4 L13.5 8.4 Z") }
  val TurnRight = telltale("TurnRight") { fill("M10.5 3.5 L21.5 12 L10.5 20.5 L10.5 15.6 L3 15.6 L3 8.4 L10.5 8.4 Z") }

  /** The hazard triangle, hollow — the outline is the symbol, a solid triangle is a different lamp. */
  val Hazard = telltale("Hazard") { fill("M12 2.6 L22.6 21 L1.4 21 Z M12 7.6 L19 19 L5 19 Z", PathFillType.EvenOdd) }

  /** Headlamps: the same lens, told apart by where the light goes — straight ahead, or dipped. */
  val HighBeam = telltale("HighBeam") { fill("$LAMP $HIGH_BEAM_RAYS") }
  val LowBeam = telltale("LowBeam") { fill("$LAMP $LOW_BEAM_RAYS") }

  /**
   * Work lights, as the machine itself shows them: the tractor with a beam off the cab roof, thrown
   * forward or thrown back. The tractor slides across the viewport to leave its beam the room.
   *
   * Facing left, like the schematic the Lighting panel lays its buttons over — these sit on that
   * panel too, and a tractor pointing one way on the art and the other way on the button over it is
   * a picture that fights itself.
   */
  val WorkFront = telltale("WorkFront") {
    mirrored {
      tractor(scale = 0.75f, dx = -1f, dy = 5f)
      fill("M11.5 7.5 L23 3 L23 8.8 Z")
    }
  }
  val WorkRear = telltale("WorkRear") {
    mirrored {
      tractor(scale = 0.75f, dx = 6f, dy = 5f)
      fill("M12.5 7.5 L1 3 L1 8.8 Z")
    }
  }

  /** A rotating beacon: dome, base, and the light coming off it. */
  val Beacon = telltale("Beacon") {
    fill(
      "M7 17 C7 11.5 9 9 12 9 C15 9 17 11.5 17 17 Z M5.2 17 H18.8 V20 H5.2 Z " +
        "M2.8 7.2 L5.2 9.6 L6.4 8.4 L4 6 Z M20 6 L17.6 8.4 L18.8 9.6 L21.2 7.2 Z M11.15 4 L11.15 6.6 L12.85 6.6 L12.85 4 Z",
    )
  }

  /** The parking brake's (P). */
  val ParkingBrake = telltale("ParkingBrake") {
    fill(
      "M1.6 12 A10.4 10.4 0 1 1 22.4 12 A10.4 10.4 0 1 1 1.6 12 Z " +
        "M3.4 12 A8.6 8.6 0 1 1 20.6 12 A8.6 8.6 0 1 1 3.4 12 Z " +
        "M9.4 7 H13 A2.9 2.9 0 0 1 13 12.8 H11.1 V17 H9.4 Z M11.1 8.7 V11.1 H13 A1.2 1.2 0 0 0 13 8.7 Z",
      PathFillType.EvenOdd,
    )
  }

  /**
   * The drivetrain, drawn from above, and the three lamps that are all the same picture of it:
   * all-wheel drive is both axles driven, and a diff lock is one axle with its differential shut.
   *
   * They share [DRIVETRAIN] so they read as one family, and the axle a lamp is *about* is the one
   * drawn solid — the other is left as an outline, which is as close as a single-tint icon gets to
   * "this end, not that one". **The front wheels are steered**; that is the only thing in the
   * picture that says which end is the front, and without it the two diff locks are one drawing
   * upside down.
   */
  val Awd = telltale("Awd") { fill("$DRIVETRAIN $FRONT_WHEELS $REAR_WHEELS") }

  val DiffLockFront = telltale("DiffLockFront") {
    fill("$DRIVETRAIN $FRONT_WHEELS $DIFF_FRONT")
    fill(REAR_WHEELS_OPEN, PathFillType.EvenOdd)
  }
  val DiffLockRear = telltale("DiffLockRear") {
    fill("$DRIVETRAIN $REAR_WHEELS $DIFF_REAR")
    fill(FRONT_WHEELS_OPEN, PathFillType.EvenOdd)
  }

  /** The engine block, finned down its left side — the fins are what stop it reading as a blob. */
  val EngineWarning = telltale("EngineWarning") {
    fill(
      "M5.5 8 H18.5 V18 H5.5 Z M7.5 4.5 H13.5 V8 H7.5 Z M15.5 6 H18.5 V8 H15.5 Z " +
        "M18.5 10.5 H21.5 V15 H18.5 Z M8 18 H11 V19.5 H8 Z " +
        "M2.5 9.5 H5.5 V11 H2.5 Z M2.5 12.5 H5.5 V14 H2.5 Z M2.5 15.5 H5.5 V17 H2.5 Z",
    )
  }

  /**
   * The reverser: the machine seen **from above**, with the arrow for the way it is travelling — the
   * symbol the 6R's own display carries beside its gear (see `references/img.png`).
   *
   * The tractor sits at the same place in both, so changing direction moves the arrow and nothing
   * else. The other arrow's space is simply left empty, which is what the panel it is copied from
   * does: the segment for the direction you are not going is not lit.
   */
  val DriveForward = telltale("DriveForward") {
    fill("$TRACTOR_TOP $ARROW_UP")
    fill(CAB, PathFillType.EvenOdd)
  }
  val DriveReverse = telltale("DriveReverse") {
    fill("$TRACTOR_TOP $ARROW_DOWN")
    fill(CAB, PathFillType.EvenOdd)
  }

  // ---------------------------------------------------------------------------------------------
  // Drawn ahead of the data. The maintenance mod these belong to isn't exported yet, so none of them
  // is a [Telltale]: a lamp with nothing behind it would be a choice in the band's config that can
  // never light. They live here so the vocabulary is complete when that channel does arrive.
  // ---------------------------------------------------------------------------------------------

  /** Charging system: the battery box with its terminals and its two poles. */
  val Battery = telltale("Battery") {
    fill("M2.5 6.5 H21.5 V18.5 H2.5 Z M4.3 8.3 H19.7 V16.7 H4.3 Z", PathFillType.EvenOdd)
    fill(
      "M6 4 H9.5 V6.5 H6 Z M14.5 4 H18 V6.5 H14.5 Z " +
        "M6.2 11.6 H11.2 V13.2 H6.2 Z M7.9 9.9 H9.5 V14.9 H7.9 Z M13.2 11.6 H18.2 V13.2 H13.2 Z",
    )
  }

  /** Coolant temperature: the thermometer standing in the water it is reading. */
  val Temperature = telltale("Temperature") {
    fill("M15.4 3 H18.6 V14.5 H15.4 Z M13.4 17.3 A3.6 3.6 0 1 1 20.6 17.3 A3.6 3.6 0 1 1 13.4 17.3 Z $WAVES")
  }

  /** The general warning triangle, as the reference cluster shows it. */
  val GeneralWarning = telltale("GeneralWarning") {
    fill("M12 2.6 L22.6 21 L1.4 21 Z M12 7.6 L19 19 L5 19 Z", PathFillType.EvenOdd)
    fill("M11.1 10 H12.9 V15.6 H11.1 Z M11.1 16.8 H12.9 V18.6 H11.1 Z")
  }

  /**
   * The brake system's bracketed (!). The brackets are what make it the brake lamp rather than a
   * general warning — which is why [ParkingBrake] has none.
   */
  val BrakeSystem = telltale("BrakeSystem") {
    fill(
      "M3.6 12 A8.4 8.4 0 1 1 20.4 12 A8.4 8.4 0 1 1 3.6 12 Z " +
        "M5.4 12 A6.6 6.6 0 1 1 18.6 12 A6.6 6.6 0 1 1 5.4 12 Z",
      PathFillType.EvenOdd,
    )
    fill("M11.1 7.6 H12.9 V12.6 H11.1 Z M11.1 13.8 H12.9 V15.6 H11.1 Z $BRAKE_ARCS")
  }

  /**
   * Service due. The mod this is waiting on puts the word SERVICE over its own speedometer; a band
   * of glyphs is the wrong place to carry a word that size, so it gets a lamp like everything else.
   */
  val Service = telltale("Service") {
    fill("M5.66 16.16 L13.16 8.66 L15.84 11.34 L8.34 18.84 Z $SPANNER_JAW")
  }
}

/**
 * The tractor, drawn once: cab with the window knocked out, hood, and two wheels with their hubs.
 * Four lamps are built on it, each placing it with [scale]/[dx]/[dy] to leave room for what it has
 * to say — so the machine is the same machine on all of them.
 */
private const val TRACTOR =
  "M4.5 4 H13 V11 H21.5 V15 H4.5 Z M6.2 5.6 H11.3 V9.4 H6.2 Z " +
    "M3.2 16.5 A4.3 4.3 0 1 1 11.8 16.5 A4.3 4.3 0 1 1 3.2 16.5 Z " +
    "M5.9 16.5 A1.6 1.6 0 1 1 9.1 16.5 A1.6 1.6 0 1 1 5.9 16.5 Z " +
    "M16 17.5 A3 3 0 1 1 22 17.5 A3 3 0 1 1 16 17.5 Z " +
    "M17.9 17.5 A1.1 1.1 0 1 1 20.1 17.5 A1.1 1.1 0 1 1 17.9 17.5 Z"

/**
 * The same machine from above, for the reverser: front wheels, chassis, rear wheels — and [CAB],
 * hollow so the block reads as a cab rather than as a bar, which is why it is a path of its own.
 */
private const val TRACTOR_TOP =
  "M6.4 6.4 H9.8 V9.4 H6.4 Z M14.2 6.4 H17.6 V9.4 H14.2 Z M11.2 7.4 H12.8 V12 H11.2 Z " +
    "M3.8 12.8 H8.6 V16.8 H3.8 Z M15.4 12.8 H20.2 V16.8 H15.4 Z"

private const val CAB = "M8.6 11.6 H15.4 V17.8 H8.6 Z M10.2 13.1 H13.8 V16.3 H10.2 Z"

private const val ARROW_UP = "M11 7.8 L11 5.8 L6.8 5.8 L12 0.6 L17.2 5.8 L13 5.8 L13 7.8 Z"
private const val ARROW_DOWN = "M11 17.6 L13 17.6 L13 18.8 L17.2 18.8 L12 23.6 L6.8 18.8 L11 18.8 Z"

/** Water under the thermometer. */
private const val WAVES =
  "M2 8 C4.62 6.4 7.25 6.4 7.25 8 C9.88 9.6 9.88 9.6 12.5 8 L12.5 9.7 " +
    "C9.88 11.3 9.88 11.3 7.25 9.7 C4.62 8.1 4.62 8.1 2 9.7 Z " +
    "M2 14 C4.62 12.4 7.25 12.4 7.25 14 C9.88 15.6 9.88 15.6 12.5 14 L12.5 15.7 " +
    "C9.88 17.3 9.88 17.3 7.25 15.7 C4.62 14.1 4.62 14.1 2 15.7 Z"

/** The two arcs flanking the brake lamp's circle, and the spanner's open jaw. */
private const val BRAKE_ARCS =
  "M2.5 5.35 A11.6 11.6 0 0 0 2.5 18.65 L3.81 17.74 A10 10 0 0 1 3.81 6.26 Z " +
    "M21.5 18.65 A11.6 11.6 0 0 0 21.5 5.35 L20.19 6.26 A10 10 0 0 1 20.19 17.74 Z"

private const val SPANNER_JAW =
  "M17.63 2.47 A4.8 4.8 0 1 0 21.53 6.37 L19.46 6.73 A2.7 2.7 0 1 1 17.27 4.54 Z"

/** The headlamp lens both beam lamps are built on, and the two ways light leaves it. */
private const val LAMP = "M13.5 4.8 C19.4 4.8 22 8 22 12 C22 16 19.4 19.2 13.5 19.2 Z"

private const val HIGH_BEAM_RAYS =
  "M2.2 6.6 L10.8 6.6 L10.8 5 L2.2 5 Z M2.2 9.8 L10.8 9.8 L10.8 8.2 L2.2 8.2 Z " +
    "M2.2 13 L10.8 13 L10.8 11.4 L2.2 11.4 Z M2.2 16.2 L10.8 16.2 L10.8 14.6 L2.2 14.6 Z " +
    "M2.2 19.4 L10.8 19.4 L10.8 17.8 L2.2 17.8 Z"

private const val LOW_BEAM_RAYS =
  "M10.56 4.84 L2.36 7.44 L2.84 8.96 L11.04 6.36 Z M10.56 8.44 L2.36 11.04 L2.84 12.56 L11.04 9.96 Z " +
    "M10.56 12.04 L2.36 14.64 L2.84 16.16 L11.04 13.56 Z M10.56 15.64 L2.36 18.24 L2.84 19.76 L11.04 17.16 Z"

/** Both axles and the shaft between them: what [ClusterIcons.Awd] and the two diff locks share. */
private const val DRIVETRAIN =
  "M5.8 5.7 H18.2 V7.1 H5.8 Z M11.2 6.4 H12.8 V17.8 H11.2 Z M5.8 17.1 H18.2 V18.5 H5.8 Z"

/** The wheels on each axle, driven (solid) or along for the ride (open). The front pair is steered. */
private const val FRONT_WHEELS =
  "M5.43 2.75 L8.43 3.85 L6.17 10.05 L3.17 8.95 Z M17.83 2.75 L20.83 3.85 L18.57 10.05 L15.57 8.95 Z"

private const val FRONT_WHEELS_OPEN =
  "M5.43 2.75 L8.43 3.85 L6.17 10.05 L3.17 8.95 Z M5.9 3.78 L7.41 4.32 L5.7 9.02 L4.19 8.48 Z " +
    "M17.83 2.75 L20.83 3.85 L18.57 10.05 L15.57 8.95 Z M18.3 3.78 L19.81 4.32 L18.1 9.02 L16.59 8.48 Z"

private const val REAR_WHEELS = "M4 14.2 H7.6 V21.4 H4 Z M16.4 14.2 H20 V21.4 H16.4 Z"

private const val REAR_WHEELS_OPEN =
  "M4 14.2 H7.6 V21.4 H4 Z M5 15.2 H6.6 V20.4 H5 Z M16.4 14.2 H20 V21.4 H16.4 Z M17.4 15.2 H19 V20.4 H17.4 Z"

/** The differential itself, shut, on one axle or the other. */
private const val DIFF_FRONT = "M9.1 6.4 A2.9 2.9 0 1 1 14.9 6.4 A2.9 2.9 0 1 1 9.1 6.4 Z"
private const val DIFF_REAR = "M9.1 17.8 A2.9 2.9 0 1 1 14.9 17.8 A2.9 2.9 0 1 1 9.1 17.8 Z"

private fun telltale(name: String, block: ImageVector.Builder.() -> Unit): ImageVector = ImageVector.Builder(
  name = "cluster.$name",
  defaultWidth = 24.dp,
  defaultHeight = 24.dp,
  viewportWidth = 24f,
  viewportHeight = 24f,
).apply(block).build()

/** One filled subpath set. [Color.Black] is a placeholder — `Icon` tints over it. */
private fun ImageVector.Builder.fill(pathData: String, fillType: PathFillType = PathFillType.NonZero) {
  addPath(addPathNodes(pathData), pathFillType = fillType, fill = SolidColor(Color.Black))
}

/** The shared tractor, scaled by [scale] and moved to ([dx], [dy]) in viewport units. */
private fun ImageVector.Builder.tractor(scale: Float, dx: Float, dy: Float) {
  addGroup(scaleX = scale, scaleY = scale, translationX = dx, translationY = dy)
  fill(TRACTOR, PathFillType.EvenOdd)
  clearGroup()
}

/** [block] flipped about the middle of the viewport, for the glyphs that face the other way. */
private fun ImageVector.Builder.mirrored(block: ImageVector.Builder.() -> Unit) {
  addGroup(scaleX = -1f, translationX = 24f)
  block()
  clearGroup()
}
