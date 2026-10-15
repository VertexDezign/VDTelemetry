package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.VehicleStrings

internal object EnVehicle : VehicleStrings {
  override val engineTitle = "Engine and Transmission"
  override val engineRunning = "engine running"
  override val speedSync = "speed sync"
  override val noEngine = "No engine data"
  override val rpm = "RPM"
  override val load = "LOAD"
  override val fuelPerHour = "FUEL/HR"
  override fun speedLimit(limit: String): String = "working speed limit $limit"
  override val water = "WATER"
  override val trans = "TRANS"
  override val defPerHour = "DEF/HR"
  override val steering = "STEERING"
  override val cruise = "CRUISE"
  override val cruiseFaster = "cruise faster"
  override val cruiseSlower = "cruise slower"

  override val navigation = "Navigation"
  override fun compass(index: Int): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[index]
  override val steeringAssist = "Steering assist"
  override val aiHelper = "AI helper"
  override val guideLines = "Guide lines"
  override val lampOn = "on"
  override val lampOff = "off"
  override val lampUnavailable = "not available"
  override val right = "R"
  override val left = "L"
  override fun linesDone(done: Int): String = " · $done done"

  override val lighting = "Lighting"
  override val beacon = "beacon"
  override val workFront = "front work lights"
  override val workRear = "rear work lights"
  override val highBeam = "high beam"
  override val lowBeam = "low beam"
  override val turnLeft = "left indicator"
  override val hazard = "hazard lights"
  override val turnRight = "right indicator"
  override val schematic = "Tractor Schematic"

  override val slotVehicle = "Vehicle"
  override val slotFront = "Front"
  override val slotRear = "Rear"
  override val mergeByFillType = "merge by fill type"
  override val noVehicle = "No Vehicle"
  override val noImplement = "No Implement"
  override val needsReset = "Needs reset"
  override val unknown = "Unknown"

  override val lowFuel = "LOW FUEL"
  override fun fuelAt(percent: Int): String = "Fuel at $percent%"
}
