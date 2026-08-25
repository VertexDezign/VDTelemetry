package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import net.vertexdezign.vdt.app.theme.VdtColors

/**
 * One thing a list can be filtered by. [kind] is what the option filters *on* — a commodity, a form,
 * a station — and it is also the grouping the matcher uses: options of one kind widen the result
 * (OR), options of different kinds narrow it (AND). That is the only reading under which picking two
 * commodities and one storage means what a reader expects.
 *
 * [value] is what the domain matches against; [label] is what the reader sees. They are usually the
 * same string, and differ where a token stands for a set (a category) rather than a name.
 */
data class FilterOption(val kind: String, val label: String, val value: String = label) {
  companion object {
    /**
     * The kind a token typed rather than picked gets. Anything the vocabulary does not name is still
     * worth being able to filter on — half a machine's name, a word out of a station's — so free text
     * is a token like the rest instead of a second search box beside this one.
     */
    const val TEXT: String = "Text"
  }
}

/**
 * A filter box that takes several tokens: type to narrow a defined list, Enter to take the highlighted
 * one, Backspace on an empty box to take the last one back, **Escape to close the list and leave the
 * box**.
 *
 * Escape matters more here than it looks: this app runs full-screen on a tablet in a cab, where there
 * is often nothing else on the panel to tab to, and a field you can only leave by finding another
 * focusable element is a field you are stuck in.
 *
 * A dropdown rather than a row of chips because what a table filters on is a *defined list* that grows
 * with the data — every commodity, every store, every station on the map — and a row of chips can only
 * carry the handful of them somebody thought of in advance. The vocabulary is built from what is
 * actually in the list, so an option is never offered that can only come back empty.
 *
 * Selection state is the caller's ([selected] / [onSelectedChange]); this composable owns only the
 * query and the highlight.
 */
@Composable
fun FilterSelect(
  options: List<FilterOption>,
  selected: List<FilterOption>,
  onSelectedChange: (List<FilterOption>) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "Filter…",
) {
  var query by remember { mutableStateOf("") }
  // Whether the list is showing, tracked apart from the focus rather than derived from it: Escape has
  // to be able to shut it whatever the focus does next, and on this target a blur is not something to
  // hang the only close path on.
  var open by remember { mutableStateOf(false) }
  var focused by remember { mutableStateOf(false) }
  var highlight by remember { mutableIntStateOf(0) }
  val focusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current

  val matches = remember(options, selected, query) {
    val needle = query.trim()
    options
      .filter { option -> selected.none { it.kind == option.kind && it.value == option.value } }
      .filter { needle.isEmpty() || it.label.contains(needle, ignoreCase = true) }
  }
  val visible = matches.take(MAX_SUGGESTIONS)
  val safeHighlight = highlight.coerceIn(0, (visible.size - 1).coerceAtLeast(0))

  fun add(option: FilterOption) {
    onSelectedChange(selected + option)
    query = ""
    highlight = 0
  }

  Box(modifier) {
    FlowRow(
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(4.dp))
        .background(VdtColors.White)
        .border(1.dp, if (focused) VdtColors.Green else VdtColors.PanelBorder, RoundedCornerShape(4.dp))
        .clickable(role = Role.Button) {
          focusRequester.requestFocus()
          open = true
        }
        .padding(horizontal = 6.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      selected.forEach { token ->
        Token(token) { onSelectedChange(selected - token) }
      }
      BasicTextField(
        value = query,
        onValueChange = {
          query = it
          highlight = 0
          open = true
        },
        singleLine = true,
        textStyle = TextStyle(fontSize = 12.sp, color = VdtColors.TextDark),
        modifier = Modifier
          .widthIn(min = 90.dp)
          .focusRequester(focusRequester)
          .onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) open = false
          }
          .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) {
              false
            } else {
              when (event.key) {
                Key.Enter, Key.NumPadEnter -> {
                  val pick = visible.getOrNull(safeHighlight)
                  val typed = query.trim()
                  when {
                    pick != null -> add(pick)

                    // Nothing in the vocabulary matches, so the words themselves become the filter.
                    typed.isNotEmpty() -> add(FilterOption(FilterOption.TEXT, typed))

                    else -> Unit
                  }
                  true
                }

                Key.DirectionDown -> {
                  highlight = (safeHighlight + 1).coerceAtMost(visible.size - 1)
                  true
                }

                Key.DirectionUp -> {
                  highlight = (safeHighlight - 1).coerceAtLeast(0)
                  true
                }

                // Backspace on an empty box takes the last token back, the way every token field does.
                Key.Backspace -> if (query.isEmpty() && selected.isNotEmpty()) {
                  onSelectedChange(selected.dropLast(1))
                  true
                } else {
                  false
                }

                // The way out. It drops what was half-typed, shuts the list and gives the focus
                // back, so the box can be left without there having to be somewhere else to go.
                Key.Escape -> {
                  query = ""
                  open = false
                  focusManager.clearFocus()
                  true
                }

                else -> false
              }
            }
          },
        decorationBox = { inner ->
          Box(Modifier.padding(vertical = 3.dp)) {
            if (query.isEmpty() && selected.isEmpty()) {
              Text(placeholder, fontSize = 12.sp, color = VdtColors.DarkGray)
            }
            inner()
          }
        },
      )
    }
    DropdownMenu(
      expanded = open && focused && visible.isNotEmpty(),
      onDismissRequest = { open = false },
      // Not focusable: the menu is a hint over a field the reader is still typing in, and a focusable
      // popup would take the caret away on its first frame.
      properties = PopupProperties(focusable = false),
    ) {
      visible.forEachIndexed { index, option ->
        DropdownMenuItem(
          text = { SuggestionRow(option, index == safeHighlight) },
          onClick = { add(option) },
        )
      }
      if (matches.size > visible.size) {
        DropdownMenuItem(
          text = {
            Text(
              "${matches.size - visible.size} more — keep typing",
              fontSize = 10.sp,
              color = VdtColors.DarkGray,
            )
          },
          enabled = false,
          onClick = { },
        )
      }
    }
  }
}

/** How many suggestions the menu shows before it asks the reader to type a little more. */
private const val MAX_SUGGESTIONS = 10

@Composable
private fun SuggestionRow(option: FilterOption, highlighted: Boolean) {
  Row(Modifier.width(240.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(
      option.label,
      // The highlighted row is the one Enter takes, so it says so in weight as well as in fill —
      // a Material menu's own hover tint is a colour and nothing else.
      fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
      fontSize = 12.sp,
      color = VdtColors.TextDark,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(8.dp))
    Text(option.kind.uppercase(), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
  }
}

@Composable
private fun Token(option: FilterOption, onRemove: () -> Unit) {
  Row(
    Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Green)
      .padding(start = 6.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      option.label,
      color = VdtColors.White,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.widthIn(max = 160.dp),
    )
    // An Icon, not an "x": the wasm build has no font fallback and would render the character as tofu.
    Icon(
      Icons.Filled.Close,
      contentDescription = "remove ${option.label}",
      tint = VdtColors.White,
      modifier = Modifier
        .padding(start = 2.dp)
        .size(14.dp)
        .clip(RoundedCornerShape(3.dp))
        .clickable(role = Role.Button, onClick = onRemove),
    )
  }
}
