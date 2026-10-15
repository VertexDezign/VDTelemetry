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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
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
import net.vertexdezign.vdt.app.i18n.LocalStrings
import net.vertexdezign.vdt.app.i18n.Numbers
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.InvoicesData
import net.vertexdezign.vdt.model.WorkType

/**
 * One line being built. Held as text rather than numbers because that is what the fields contain while
 * someone is mid-edit — `"3."` is a legitimate thing to have typed and is not a Double yet.
 */
private class DraftLine(val workType: WorkType, private val numbers: Numbers) {
  var quantity by mutableStateOf("1")

  // Ungrouped, unlike formatQuantity: this is the text the field starts out holding, and a group
  // separator in it would not parse back.
  var price by mutableStateOf(numbers.trimmed(workType.price, 2))
  var discount by mutableStateOf("")
  var note by mutableStateOf("")

  val quantityValue: Double get() = numbers.parse(quantity) ?: 0.0
  val priceValue: Double get() = numbers.parse(price) ?: 0.0

  /** Percent as typed, as the fraction the mod wants. Out-of-range is clamped, as the mod clamps it. */
  val discountFraction: Double get() = ((numbers.parse(discount) ?: 0.0) / 100).coerceIn(0.0, 1.0)

  /**
   * The line total, computed exactly as the mod computes it: the discount comes off the already
   * rounded [grossAmount], and the result is rounded again to a whole unit. This is a preview of a
   * number the mod will recompute — it must agree, or the invoice that arrives will not be the one
   * that was previewed, so the gross is taken from the one place that knows how to price a litre.
   */
  val amount: Long
    get() = kotlin.math.round(grossAmount * (1 - discountFraction)).toLong()

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
  val strings = LocalStrings.current
  val numbers = strings.numbers
  val s = strings.invoices
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
        if (proposal) s.askToBeBilled else s.newInvoice,
        color = VdtColors.TextDark,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
      )

      // Which way round it is. A proposal is raised by the payer and validated by the issuer, so this
      // choice decides who ends up owing whom -- worth stating in words rather than a toggle label.
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ModeChip(s.iDidTheWork, !proposal) { proposal = false }
        ModeChip(s.theyDidTheWork, proposal) { proposal = true }
      }
      Text(
        if (proposal) s.proposalNote else s.invoiceNote,
        color = VdtColors.DarkGray,
        fontSize = 10.sp,
      )

      FieldLabel(if (proposal) s.whoDidTheWork else s.whoToBill)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        data.farms.forEach { farm ->
          ModeChip(farm.label, farmId == farm.id) { farmId = farm.id }
        }
      }

      FieldLabel(s.linesLabel)
      if (lines.isEmpty()) {
        Text(s.noLinesYet, color = VdtColors.DarkGray, fontSize = 11.sp)
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
      FinanceButton(s.addLine, VdtColors.ProgressBlue, { picking = true }, enabled = !atCap)
      if (atCap) {
        Text(
          s.maxLines(ClientMessage.CreateInvoice.MAX_LINES),
          color = VdtColors.DarkGray,
          fontSize = 9.sp,
        )
      }

      if (discount > 0) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text(s.discountCaps, color = VdtColors.DarkGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
          Text(
            "-${numbers.formatMoney(discount)}",
            color = VdtColors.AccentText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
          )
        }
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(s.totalCaps, color = VdtColors.DarkGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(
          numbers.formatMoney(total),
          color = VdtColors.TextDark,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
        )
      }
      if (data.vatEnabled) {
        // The preview is tax-inclusive, like every figure the mod prints, and the issuer never sees
        // the VAT part of it. Better said here than discovered after the money lands.
        Text(
          s.vatNote,
          color = VdtColors.DarkGray,
          fontSize = 9.sp,
        )
      }

      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FinanceButton(
          label = if (proposal) s.sendProposal else s.sendInvoice,
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
        FinanceButton(strings.common.cancel, VdtColors.DarkGray, onDismiss)
      }
    }

    if (picking) {
      WorkTypePicker(
        workTypes = data.workTypes,
        onPick = {
          lines.add(DraftLine(it, numbers))
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
    color = if (active) VdtColors.OnFill else VdtColors.DarkGray,
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
  val strings = LocalStrings.current
  val numbers = strings.numbers
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Surface.copy(alpha = 0.6f))
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
        numbers.formatMoney(line.amount),
        color = if (line.isValid) VdtColors.TextDark else VdtColors.Red,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
      )
      // An Icon rather than a "✕": the wasm build has no font fallback, so a dingbat outside the
      // bundled font's coverage renders as tofu.
      Icon(
        Icons.Filled.Close,
        contentDescription = strings.invoices.remove(line.workType.name),
        tint = VdtColors.DarkGray,
        modifier = Modifier
          .clip(RoundedCornerShape(4.dp))
          .clickable(role = Role.Button, onClick = onRemove)
          .padding(horizontal = 6.dp, vertical = 4.dp)
          .size(18.dp),
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      NumberField(line.quantity, { line.quantity = it }, unitLabel(strings, line.workType.unit), width = 84)
      NumberField(line.price, { line.price = it }, "@ ${priceUnitLabel(strings, line.workType.unit)}", width = 104)
      NumberField(line.discount, { line.discount = it }, strings.invoices.percentOff, width = 74)
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
  val numbers = LocalStrings.current.numbers
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    BasicTextField(
      value = value,
      onValueChange = { new -> onChange(numbers.decimalInput(new)) },
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
        .background(VdtColors.Surface)
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
          Text(LocalStrings.current.invoices.noteHint, color = VdtColors.TextDisabled, fontSize = 10.sp)
        }
        inner()
      }
    },
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Surface)
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 5.dp),
  )
}

/** Pick a work type. The picker-only rows are listed but not selectable, with the reason. */
@Composable
private fun WorkTypePicker(workTypes: List<WorkType>, onPick: (WorkType) -> Unit, onDismiss: () -> Unit) {
  val s = LocalStrings.current.invoices
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
      Text(s.addALine, color = VdtColors.TextDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
      Column(
        Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        workTypes.forEach { workType ->
          WorkTypeRow(workType, onPick)
        }
      }
      FinanceButton(s.close, VdtColors.DarkGray, onDismiss)
    }
  }
}

@Composable
private fun WorkTypeRow(workType: WorkType, onPick: (WorkType) -> Unit) {
  val strings = LocalStrings.current
  val numbers = strings.numbers
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
        Text(strings.invoices.inGameOnly, color = VdtColors.DarkGray, fontSize = 9.sp)
      }
    }
    if (usable) {
      Text(
        "${numbers.formatQuantity(workType.price)} / ${priceUnitLabel(strings, workType.unit)}",
        color = VdtColors.DarkGray,
        fontSize = 10.sp,
        maxLines = 1,
      )
    }
  }
}
