package net.vertexdezign.vdt.app.net

import net.vertexdezign.vdt.ModFormat
import net.vertexdezign.vdt.ServerMessage

/**
 * The channel name and format version [msg] carries, for [ModFormat]'s comparison: null for a message
 * that is not a mod channel (the server's own diagnostics, its derived field status and course
 * progress), and a null version for a channel that has just gone away -- which drops it from the
 * comparison rather than leaving the last file's version standing.
 */
internal fun formatVersionOf(msg: ServerMessage): Pair<String, String?>? = when (msg) {
  is ServerMessage.Telemetry -> "telemetry" to msg.data.version

  is ServerMessage.TaskList -> "taskList" to msg.data?.version

  is ServerMessage.CropRotation -> "cropRotation" to msg.data?.version

  is ServerMessage.AutoDrive -> "autoDrive" to msg.data?.version

  is ServerMessage.MapUpdate -> "map" to msg.data?.version

  is ServerMessage.MapVehicles -> "mapVehicles" to msg.data?.version

  is ServerMessage.MapLayers -> "mapLayers" to msg.data?.formatVersion

  is ServerMessage.GpsCourse -> "gpsCourse" to msg.data?.version

  is ServerMessage.FieldInfo -> "fieldInfo" to msg.data?.version

  is ServerMessage.Production -> "production" to msg.data?.version

  is ServerMessage.Storage -> "storage" to msg.data?.version

  is ServerMessage.Husbandry -> "husbandry" to msg.data?.version

  is ServerMessage.Fleet -> "fleet" to msg.data?.version

  is ServerMessage.Prices -> "prices" to msg.data?.version

  is ServerMessage.Missions -> "missions" to msg.data?.version

  is ServerMessage.Finance -> "finance" to msg.data?.version

  is ServerMessage.Invoices -> "invoices" to msg.data?.version

  is ServerMessage.CropCalendar -> "cropCalendar" to msg.data?.version

  is ServerMessage.Weather -> "weather" to msg.data?.version

  is ServerMessage.GpsCourseProgress, is ServerMessage.FieldStatus, is ServerMessage.ChannelStats,
  is ServerMessage.Error,
  -> null
}
