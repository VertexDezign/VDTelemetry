package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.DisplayStrings

internal object DeDisplay : DisplayStrings {
  override val title = "ANZEIGEMODUS"
  override val wakeOn = "WACH"
  override val wakeOff = "BILDSCHIRM DARF SCHLAFEN"
  override val wakeUnsupported = "KEIN WACHHALTEN"

  override fun wakeLockDescription(state: String) = "Bildschirm wach halten: $state"

  override val editLayout = "LAYOUT BEARBEITEN"
  override val exit = "ANZEIGE BEENDEN"
  override val editing = "LAYOUT WIRD BEARBEITET"
  override val move = "VERSCHIEBEN"
  override val done = "FERTIG"
  override val noSuchScreen = "SEITE NICHT GEFUNDEN"

  override fun noSuchScreenDetail(target: String) =
    "Dieses Gerät ist an „$target“ angeheftet, das es nicht mehr gibt.\n" +
      "Anzeigemodus beenden, oder ?display=<Seite> für eine bestehende öffnen."
}
