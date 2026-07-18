package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.CropRotationData
import net.vertexdezign.vdt.model.FieldInfoData
import net.vertexdezign.vdt.model.MapData
import net.vertexdezign.vdt.model.MapVehiclesData
import net.vertexdezign.vdt.model.TaskListData
import net.vertexdezign.vdt.model.VdtData

/**
 * Parses the mod's `vdTelemetry.json` into the typed [VdtData] model.
 *
 * Configured to tolerate the mod running ahead of the client: unknown keys are ignored and omitted
 * fields fall back to the model's data-class defaults (the mod writes only what's present).
 *
 * `coerceInputValues` extends that tolerance to explicit `null`s on non-nullable fields: they fall
 * back to the default instead of failing the whole parse. A `null` slips in when the mod emits a
 * value JSON can't represent — e.g. a pass-through fill unit whose capacity is +inf encodes as
 * `null` — and one such field must not freeze the entire telemetry feed at last-good-state.
 */
object VdtParser {
  private val json =
    Json {
      ignoreUnknownKeys = true
      coerceInputValues = true
    }

  fun parseJson(text: String): VdtData = json.decodeFromString(VdtData.serializer(), text)

  /** Parse the optional `taskList.json` channel (FS25_TaskList) into [TaskListData]. */
  fun parseTaskList(text: String): TaskListData = json.decodeFromString(TaskListData.serializer(), text)

  /** Parse the optional `cropRotation.json` channel (FS25_CropRotation) into [CropRotationData]. */
  fun parseCropRotation(text: String): CropRotationData = json.decodeFromString(CropRotationData.serializer(), text)

  /** Parse the `map.json` channel (map overlay: POIs + fields) into [MapData]. */
  fun parseMap(text: String): MapData = json.decodeFromString(MapData.serializer(), text)

  /** Parse the `fieldInfo.json` channel (per-field agronomy state) into [FieldInfoData]. */
  fun parseFieldInfo(text: String): FieldInfoData = json.decodeFromString(FieldInfoData.serializer(), text)

  /** Parse the `mapVehicles.json` channel (vehicle markers) into [MapVehiclesData]. */
  fun parseMapVehicles(text: String): MapVehiclesData = json.decodeFromString(MapVehiclesData.serializer(), text)
}
