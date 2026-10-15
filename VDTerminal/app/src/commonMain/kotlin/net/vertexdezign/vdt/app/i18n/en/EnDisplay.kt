package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.DisplayStrings

internal object EnDisplay : DisplayStrings {
  override val title = "DISPLAY MODE"
  override val wakeOn = "AWAKE"
  override val wakeOff = "SCREEN MAY SLEEP"
  override val wakeUnsupported = "NO WAKE LOCK"

  override fun wakeLockDescription(state: String) = "screen wake lock: $state"

  override val editLayout = "EDIT LAYOUT"
  override val exit = "EXIT DISPLAY"
  override val editing = "EDITING LAYOUT"
  override val move = "MOVE"
  override val done = "DONE"
  override val noSuchScreen = "NO SUCH SCREEN"

  override fun noSuchScreenDetail(target: String) = "This device is pinned to “$target”, which no longer exists.\n" +
    "Exit display mode, or open ?display=<page> for one that does."
}
