package net.vertexdezign.vdt.app.panels

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.ControlTarget
import net.vertexdezign.vdt.CruiseAction
import net.vertexdezign.vdt.app.components.FillUnitsDisplay
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.SimpleGauge
import net.vertexdezign.vdt.app.components.StatusColor
import net.vertexdezign.vdt.app.components.StatusIconButton
import net.vertexdezign.vdt.app.components.format2
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.CruiseControl
import net.vertexdezign.vdt.model.FoldableState
import net.vertexdezign.vdt.model.Motor
import net.vertexdezign.vdt.model.MotorState
import net.vertexdezign.vdt.model.Vehicle
import kotlin.math.roundToInt

/** Engine / transmission panel: speed gauge, RPM, temps, cruise, and vehicle fill units. */
@Composable
fun EngineTransmission(
  vehicle: Vehicle,
  sampleIntervalMs: Int,
  modifier: Modifier = Modifier,
  onCommand: (ClientMessage) -> Unit = {},
) {
  val motor = vehicle.motor
  Panel(
    title = "Engine and Transmission",
    icon = Icons.Filled.Agriculture,
    modifier = modifier,
    headerActions = {
      // Engine start/stop lives here (not in the control row below): it's a panel-level toggle for the
      // whole engine. Tap sends the absolute target (start when currently off), green when running.
      if (motor != null) {
        val running = motor.state != MotorState.OFF
        Icon(
          Icons.Filled.Key,
          contentDescription = "engine start/stop",
          tint = if (running) VdtColors.Green else VdtColors.DarkGray,
          modifier =
          Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { onCommand(ClientMessage.SetMotorState(on = motor.state == MotorState.OFF)) }
            .padding(2.dp),
        )
      }
    },
  ) {
    if (motor == null) {
      Text("No engine data", color = VdtColors.DarkGray)
      return@Panel
    }
    val cruise = vehicle.cruiseControl
    val maxSpeed =
      (motor.maxSpeed?.let { maxOf(it.forward ?: 0, it.backward ?: 0) } ?: 0).let { if (it <= 0) 50 else it }

    // Tween the fast-changing readouts over one sample interval so they read as continuous
    // rather than snapping at the telemetry rate.
    val spec = tween<Float>(durationMillis = sampleIntervalMs, easing = LinearEasing)
    val animSpeed by animateFloatAsState(vehicle.speed?.value ?: 0f, spec, label = "speed")
    val animRpm by animateFloatAsState((motor.rpm?.value ?: 0).toFloat(), spec, label = "rpm")

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Left: RPM + fuel/hr
        Column(
          Modifier.width(64.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Metric("${animRpm.roundToInt()}", "RPM")
          Metric(usage(motor.fuel()?.usage, motor.fuel()?.unit), "FUEL/HR")
        }
        // Center: speed gauge (tap toggles cruise) + cruise speed adjuster
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          SimpleGauge(
            value = animSpeed,
            min = 0f,
            max = maxSpeed.toFloat(),
            unit = vehicle.speed?.unit ?: "",
            size = 140.dp,
            isActive = cruise?.active ?: false,
            onClick =
            cruise?.let {
              {
                onCommand(
                  ClientMessage.SetCruiseControl(
                    if (it.active == true) CruiseAction.DISABLE else CruiseAction.ENABLE,
                  ),
                )
              }
            },
          )
          if (cruise != null) {
            CruiseAdjuster(cruise, onCommand)
          }
        }
        // Right: water temp + def/hr
        Column(
          Modifier.width(64.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Metric("${motor.temperatur?.value ?: 0}${motor.temperatur?.unit ?: ""}", "WATER")
          Metric(usage(motor.def()?.usage, null), "DEF/HR")
        }
      }

      // Vehicle self controls. Each button is only clickable when the vehicle actually has that
      // aspect (foldable/turnOn/lowered present); the tap sends the ABSOLUTE target state, computed
      // from what we render, so it stays idempotent over the lossy command channel (see ClientMessage).
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val foldable = vehicle.foldable
        StatusIconButton(
          icon = Icons.Filled.UnfoldMore,
          modifier = Modifier.weight(1f),
          active = foldable != null,
          color = if (foldable == FoldableState.EXTENDED) StatusColor.Green else StatusColor.White,
          onClick =
          foldable?.let {
            { onCommand(ClientMessage.SetFolded(ControlTarget.VEHICLE, on = it != FoldableState.EXTENDED)) }
          },
        )
        StatusIconButton(
          Icons.Filled.PowerSettingsNew,
          Modifier.weight(1f),
          active = vehicle.isTurnedOn != null,
          color = if (vehicle.isTurnedOn == true) StatusColor.Green else StatusColor.White,
          onClick =
          vehicle.isTurnedOn?.let {
            { onCommand(ClientMessage.SetActivated(ControlTarget.VEHICLE, on = !it)) }
          },
        )
        StatusIconButton(
          if (vehicle.lowered == true) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
          Modifier.weight(1f),
          active = vehicle.lowered != null,
          color = if (vehicle.lowered == true) StatusColor.Green else StatusColor.White,
          onClick =
          vehicle.lowered?.let {
            { onCommand(ClientMessage.SetLowered(ControlTarget.VEHICLE, on = !it)) }
          },
        )
      }

      FillUnitsDisplay(vehicle.fillUnits?.fillUnit ?: emptyList(), Modifier.fillMaxWidth(), spacing = 4)
    }
  }
}

/**
 * Cruise target-speed control: `−  <speed>  +`, with the speed tappable to type an exact value. The
 * ± buttons and the input all send an absolute `setSpeed` (km/h); the mod clamps to the vehicle's own
 * min/max, so we only guard against going below 0.
 */
@Composable
private fun CruiseAdjuster(cruise: CruiseControl, onCommand: (ClientMessage) -> Unit) {
  var editing by remember { mutableStateOf(false) }
  val current = cruise.targetSpeed ?: 0f
  val valueColor = if (cruise.active == true) VdtColors.Green else VdtColors.DarkGray

  // km/h as a float — mods allow sub-1 cruise steps. The ± buttons move by 1; the input takes any value.
  fun setSpeed(v: Float) =
    onCommand(ClientMessage.SetCruiseControl(CruiseAction.SET_SPEED, speed = v.coerceAtLeast(0f)))

  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      AdjustButton(Icons.Filled.Remove) { setSpeed(current - 1f) }
      if (editing) {
        CruiseInput(
          initial = current,
          color = valueColor,
          onCommit = {
            setSpeed(it)
            editing = false
          },
          onCancel = { editing = false },
        )
      } else {
        Text(
          format2(cruise.targetSpeed ?: 0f),
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold,
          color = valueColor,
          modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { editing = true }.padding(horizontal = 4.dp),
        )
      }
      AdjustButton(Icons.Filled.Add) { setSpeed(current + 1f) }
    }
    Text("CRUISE", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
  }
}

/** Small round ± icon button for the cruise adjuster. */
@Composable
private fun AdjustButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
  Icon(
    icon,
    contentDescription = null,
    tint = VdtColors.DarkGray,
    modifier = Modifier.size(22.dp).clip(CircleShape).clickable(onClick = onClick).padding(2.dp),
  )
}

/**
 * Inline numeric editor for the cruise speed. Auto-focuses; commits on Enter or on losing focus
 * (once it has actually been focused, so the initial unfocused state doesn't cancel immediately).
 */
@Composable
private fun CruiseInput(
  initial: Float,
  color: androidx.compose.ui.graphics.Color,
  onCommit: (Float) -> Unit,
  onCancel: () -> Unit,
) {
  // Prefill without a trailing ".0" so a whole speed reads as "15", not "15.0".
  val initialText = if (initial == initial.toLong().toFloat()) initial.toLong().toString() else initial.toString()
  var text by remember { mutableStateOf(initialText) }
  var hadFocus by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }

  fun commit() = text.toFloatOrNull()?.let(onCommit) ?: onCancel()

  BasicTextField(
    value = text,
    onValueChange = { new ->
      // digits plus a single decimal point
      val filtered = new.filter { it.isDigit() || it == '.' }
      val dot = filtered.indexOf('.')
      text =
        (
          if (dot <
            0
          ) {
            filtered
          } else {
            filtered.substring(0, dot + 1) + filtered.substring(dot + 1).filter { it.isDigit() }
          }
          )
          .take(5)
    },
    singleLine = true,
    textStyle =
    TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color, textAlign = TextAlign.Center),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { commit() }),
    modifier =
    Modifier
      .width(44.dp)
      .focusRequester(focusRequester)
      .onFocusChanged { state ->
        if (state.isFocused) {
          hadFocus = true
        } else if (hadFocus) {
          commit()
        }
      },
  )

  LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

private fun Motor.fuel() = fillUnits?.fuel

private fun Motor.def() = fillUnits?.def

private fun usage(value: Float?, unit: String?): String = if (value == null) "--" else "$value${unit ?: ""}"

@Composable
private fun Metric(value: String, label: String) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
    Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = VdtColors.Gray)
  }
}
