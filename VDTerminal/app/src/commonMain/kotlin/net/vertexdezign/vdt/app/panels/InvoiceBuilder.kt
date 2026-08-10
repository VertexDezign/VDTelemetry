package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.InvoiceLineInput
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.InvoicesData
import net.vertexdezign.vdt.model.WorkType

/**
 * One line being built. Held as text rather than numbers because that is what the fields contain while
 * someone is mid-edit — `"3."` is a legitimate thing to have typed and is not a Double yet.
 */
private class DraftLine(val workType: WorkType) {
  var quantity by mutableStateOf("1")
  var price by mutableStateOf(trimTrailing(workType.price))
  var discount by mutableStateOf("")
  var note by mutableStateOf("")

  val quantityValue: Double get() = quantity.toDoubleOrNull() ?: 0.0
  val priceValue: Double get() = price.toDoubleOrNull() ?: 0.0

  /** Percent as typed, as the fraction the mod wants. Out-of-range is clamped, as the mod clamps it. */
  val discountFraction: Double get() = ((discount.toDoubleOrNull() ?: 0.0) / 100).coerceIn(0.0, 1.0)

  /**
   * The line total, computed exactly as the mod computes it (`Invoice.computeLineGross` then the
   * discount): a litre line is priced per 1000 l, and the rounding is to the nearest whole unit. This
   * is a preview of a number the mod will recompute — it must agree, or the invoice that arrives will
   * not be the one that was previewed.
   */
  val amount: Long
    get() {
      val gross = if (workType.unit == "liter") priceValue * quantityValue / 1000 else priceValue * quantityValue
      return kotlin.math.round(kotlin.math.round(gross) * (1 - discountFraction)).toLong()
    }

  /** What this line would have cost undiscounted, the mod's `computeLineGross`. */
  val grossAmount: Long
    get() {
      val gross = if (workType.unit == "liter") priceValue * quantityValue / 1000 else priceValue * quantityValue
      return kotlin.math.round(gross).toLong()
    }

  /** What the discount takes off, as money -- the figure the mod prints beside the total. */
  val discountAmount: Long get() = (grossAmount - amount).coerceAtLeast(0)

  val isValid: Boolean get() = quantityValue > 0 && priceValue >= 0

  fun toInput(): InvoiceLineInput = InvoiceLineInput(
    workTypeId = workType.id,
    quantity = quantityValue,
    // Only sent when it differs from the catalogue, so an untouched line follows whatever the
    // server's difficulty says at the moment the mod builds it rather than what we cached.
    price = priceValue.takeIf { it != workType.price },
    discount = discountFraction.takeIf { it > 0 },
    note = note.takeIf { it.isNotBlank() },
  )
}

private fun trimTrailing(value: Double): String = formatQuantity(value).replace(",", "")

/**
 * Raise a new invoice, or a proposal, without opening the in-game wizard.
 *
 * Only the work types that carry no in-game picker are offered — vehicle, consumable and product sales
 * transfer ownership of real objects on payment, which a command cannot assemble, so the mod refuses
 * them and they are shown here as unavailable rather than silently missing.
 */
@Composable
fun InvoiceBuilder(
  data: InvoicesData,
  onDismiss: () -> Unit,
  onCommand: (ClientMessage) -> Unit,
  modifier: Modifier = Modifier,
) {
  var farmId by remember { mutableStateOf(data.farms.firstOrNull()?.id) }
  var proposal by remember { mutableStateOf(false) }
  val lines = remember { mutableStateListOf<DraftLine>() }
  var picking by remember { mutableStateOf(false) }

  val valid = lines.isNotEmpty() && lines.all { it.isValid }
  val total = lines.sumOf { it.amount }
  val discount = lines.sumOf { it.discountAmount }

  Box(
    modifier
      .fillMaxSize()
      .background(VdtColors.Black.copy(alpha = 0.55f))
      .clickable(interactionSource = null, indication = null, onClick = onDismiss),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      Modifier
        .widthIn(max = 560.dp)
        .padding(16.dp)
        .clip(RoundedCornerShape(6.dp))
        .background(VdtColors.Panel)
        .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(6.dp))
        .padding(14.dp)
        // Swallow the click so tapping inside the sheet does not dismiss it.
        .clickable(interactionSource = null, indication = null, onClick = {}),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        if (proposal) "Ask to be billed" else "New invoice",
        color = VdtColors.TextDark,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
      )

      // Which way round it is. A proposal is raised by the payer and validated by the issuer, so this
      // choice decides who ends up owing whom -- worth stating in words rather than a toggle label.
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ModeChip("I did the work", !proposal) { proposal = false }
        ModeChip("They did the work", proposal) { proposal = true }
      }
      Text(
        if (proposal) {
          "They will be asked to approve it before it becomes payable."
        } else {
          "Billed immediately — they can pay it as soon as it arrives."
        },
        color = VdtColors.DarkGray,
        fontSize = 10.sp,
      )

      FieldLabel(if (proposal) "Who did the work" else "Who to bill")
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        data.farms.forEach { farm ->
          ModeChip(farm.label, farmId == farm.id) { farmId = farm.id }
        }
      }

      FieldLabel("Lines")
      if (lines.isEmpty()) {
        Text("Nothing on it yet — add a work type below.", color = VdtColors.DarkGray, fontSize = 11.sp)
      } else {
        Column(
          Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          lines.forEachIndexed { index, line ->
            DraftLineRow(line, onRemove = { lines.removeAt(index) })
          }
        }
      }

      // The cap is the mod's own, and ClientMessage.CreateInvoice rejects a longer list in its
      // constructor -- so it has to be unreachable from the UI, not merely discouraged.
      val atCap = lines.size >= ClientMessage.CreateInvoice.MAX_LINES
      FinanceButton("Add line", VdtColors.ProgressBlue, { picking = true }, enabled = !atCap)
      if (atCap) {
        Text(
          "An invoice can carry at most ${ClientMessage.CreateInvoice.MAX_LINES} lines.",
          color = VdtColors.DarkGray,
          fontSize = 9.sp,
        )
      }

      if (discount > 0) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text("DISCOUNT", color = VdtColors.DarkGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
          Text(
            "−${formatMoney(discount)}",
            color = VdtColors.AccentText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
          )
        }
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("TOTAL", color = VdtColors.DarkGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(
          formatMoney(total),
          color = VdtColors.TextDark,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
        )
      }
      if (data.vatEnabled) {
        // The preview is tax-inclusive, like every figure the mod prints, and the issuer never sees
        // the VAT part of it. Better said here than discovered after the money lands.
        Text(
          "Includes VAT at the server's rates; the issuer receives the net amount.",
          color = VdtColors.DarkGray,
          fontSize = 9.sp,
        )
      }

      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FinanceButton(
          label = if (proposal) "Send proposal" else "Send invoice",
          color = VdtColors.Green,
          enabled = valid && farmId != null,
          onClick = {
            val target = farmId ?: return@FinanceButton
            onCommand(
              ClientMessage.CreateInvoice(
                farmId = target,
                proposal = proposal,
                lines = lines.map { it.toInput() },
              ),
            )
          },
        )
        FinanceButton("Cancel", VdtColors.DarkGray, onDismiss)
      }
    }

    if (picking) {
      WorkTypePicker(
        workTypes = data.workTypes,
        onPick = {
          lines.add(DraftLine(it))
          picking = false
        },
        onDismiss = { picking = false },
      )
    }
  }
}

@Composable
private fun FieldLabel(text: String) {
  Text(text.uppercase(), color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun ModeChip(label: String, active: Boolean, onClick: () -> Unit) {
  Text(
    label,
    color = if (active) VdtColors.White else VdtColors.DarkGray,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(if (active) VdtColors.Green else VdtColors.TrackGray)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 6.dp),
  )
}

@Composable
private fun DraftLineRow(line: DraftLine, onRemove: () -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.White.copy(alpha = 0.6f))
      .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        line.workType.name,
        color = VdtColors.TextDark,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        formatMoney(line.amount),
        color = if (line.isValid) VdtColors.TextDark else VdtColors.Red,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
      )
      Text(
        "✕",
        color = VdtColors.DarkGray,
        fontSize = 12.sp,
        modifier = Modifier
          .clip(RoundedCornerShape(4.dp))
          .clickable(role = Role.Button, onClick = onRemove)
          .padding(horizontal = 6.dp, vertical = 2.dp),
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      NumberField(line.quantity, { line.quantity = it }, unitLabel(line.workType.unit), width = 84)
      NumberField(line.price, { line.price = it }, "@ ${line.workType.priceUnitLabel}", width = 104)
      NumberField(line.discount, { line.discount = it }, "% off", width = 74)
    }
    NoteField(line.note) { line.note = it }
  }
}

/**
 * A decimal entry field. Accepts one decimal point and nothing else — the mod parses these with the
 * engine's XML float reader, so anything it cannot read has to be impossible to type.
 */
@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, suffix: String, width: Int) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    BasicTextField(
      value = value,
      onValueChange = { new ->
        val cleaned = new.filter { it.isDigit() || it == '.' }
        // Keep the first decimal point, drop any others: "3..5" and "3.5.1" are both unparseable.
        val dot = cleaned.indexOf('.')
        onChange(
          if (dot < 0) cleaned else cleaned.substring(0, dot + 1) + cleaned.substring(dot + 1).filter { it.isDigit() },
        )
      },
      singleLine = true,
      textStyle = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = VdtColors.TextDark,
        textAlign = TextAlign.End,
      ),
      cursorBrush = SolidColor(VdtColors.TextDark),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
      modifier = Modifier
        .widthIn(min = width.dp, max = width.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(VdtColors.White)
        .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp))
        .padding(horizontal = 6.dp, vertical = 5.dp),
    )
    Text(suffix, color = VdtColors.DarkGray, fontSize = 9.sp, maxLines = 1)
  }
}

@Composable
private fun NoteField(value: String, onChange: (String) -> Unit) {
  BasicTextField(
    value = value,
    onValueChange = { onChange(it.take(120)) },
    singleLine = true,
    textStyle = TextStyle(fontSize = 10.sp, color = VdtColors.TextDark),
    cursorBrush = SolidColor(VdtColors.TextDark),
    decorationBox = { inner ->
      Box {
        if (value.isEmpty()) {
          Text("note (optional)", color = VdtColors.TextDisabled, fontSize = 10.sp)
        }
        inner()
      }
    },
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.White)
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 5.dp),
  )
}

/** Pick a work type. The picker-only rows are listed but not selectable, with the reason. */
@Composable
private fun WorkTypePicker(workTypes: List<WorkType>, onPick: (WorkType) -> Unit, onDismiss: () -> Unit) {
  Box(
    Modifier
      .fillMaxSize()
      .background(VdtColors.Black.copy(alpha = 0.55f))
      .clickable(interactionSource = null, indication = null, onClick = onDismiss),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      Modifier
        .widthIn(max = 420.dp)
        .padding(16.dp)
        .clip(RoundedCornerShape(6.dp))
        .background(VdtColors.Panel)
        .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(6.dp))
        .padding(12.dp)
        .clickable(interactionSource = null, indication = null, onClick = {}),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("Add a line", color = VdtColors.TextDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
      Column(
        Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        workTypes.forEach { workType ->
          WorkTypeRow(workType, onPick)
        }
      }
      FinanceButton("Close", VdtColors.DarkGray, onDismiss)
    }
  }
}

@Composable
private fun WorkTypeRow(workType: WorkType, onPick: (WorkType) -> Unit) {
  val usable = workType.isUsable
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .clickable(enabled = usable, role = Role.Button) { onPick(workType) }
      .padding(horizontal = 8.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
      Text(
        workType.name,
        color = if (usable) VdtColors.TextDark else VdtColors.TextDisabled,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!usable) {
        Text("built in-game only — it transfers goods", color = VdtColors.DarkGray, fontSize = 9.sp)
      }
    }
    if (usable) {
      Text(
        "${formatQuantity(workType.price)} / ${workType.priceUnitLabel}",
        color = VdtColors.DarkGray,
        fontSize = 10.sp,
        maxLines = 1,
      )
    }
  }
}
