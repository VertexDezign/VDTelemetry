package net.vertexdezign.vdt.app.widgets

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.app.i18n.LocalStrings
import net.vertexdezign.vdt.app.i18n.Strings
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.theme.VdtColors

/**
 * One switch on the vehicle as a tile of its own: Speed Sync, a light, the engine, or fold/power/lower
 * for one position on the rig (issue #194). It works like [ShortcutWidget], except that a tap acts on
 * the machine instead of opening an app, and the tile shows the state of what it switches.
 *
 * It is one configurable type with the action as per-instance config, not one type per action. The
 * reasons are the shortcut's: one row in the picker however many actions there are, and as many
 * buttons on a page as anyone wants. The actions are [TileActions].
 *
 * The tile has three looks. **On** is a filled green square with [VdtColors.OnFill] ink. **Off** is the
 * plain panel square. **Unavailable** (nothing to switch: no vehicle, no beacon, nothing hitched at the
 * rear) keeps the action's icon greyed out and says so, so a tile placed for a machine that is not
 * hitched right now still shows what it is for. On and off differ in brightness and in the state word
 * under the label, never in hue alone. A button the game would refuse right now (Speed Sync with
 * nothing in range) shows its state but ignores taps.
 *
 * Every action is offered all the time, unlike the shortcut's apps: whether a switch exists depends on
 * the vehicle being driven, not on the session, and a picker that changed every time you changed
 * tractors would be hard to use.
 */
object ActionWidget : Widget {
  override val id = "action"
  override fun title(strings: Strings) = strings.widgets.actionButton
  override val icon: ImageVector = Icons.Filled.TouchApp

  // Thumb-sized, like the shortcut: a row of these along an edge is a switch panel.
  override val minWidth = 56.dp
  override val minHeight = 56.dp

  /** The config key naming the [TileAction] this button performs. */
  const val ACTION_KEY = "action"

  @Composable
  private fun actionOption() = ConfigOption(
    key = ACTION_KEY,
    label = LocalStrings.current.widgets.action,
    choices = TileActions.all.map { ConfigOption.Choice(it.id, it.title(LocalStrings.current), it.icon) },
  )

  @Composable
  override fun configOptions(): List<ConfigOption> = listOf(actionOption())

  @Composable
  override fun Content(modifier: Modifier, config: WidgetConfig) {
    val strings = LocalStrings.current
    val store = LocalVdtStore.current
    val telemetry by store.telemetry.collectAsState()
    val network by store.autoDrive.collectAsState()

    // An id no longer in the catalog (an action renamed away) says so instead of quietly becoming the
    // first action. Switching the engine off is not a good thing to do by default.
    val action = actionOption().resolve(config)?.let(TileActions::byId)
    if (action == null) {
      IconTile(
        Icons.AutoMirrored.Filled.HelpOutline,
        strings.widgets.unavailable,
        modifier,
        tint = VdtColors.TextDisabled,
      )
      return
    }

    val title = action.title(strings)
    val state = action.stateOf(telemetry?.vehicle, network)
    if (state == null) {
      IconTile(
        action.icon,
        title,
        modifier,
        interactionModifier = Modifier.semantics { contentDescription = "$title, ${strings.widgets.unavailable}" },
        tint = VdtColors.TextDisabled,
        labelColor = VdtColors.TextDisabled,
        caption = strings.widgets.unavailable,
      )
      return
    }

    val command = state.command
    val word = state.word.label(strings)
    val ink =
      when {
        // On wins over inert: grey ink on the green fill would be unreadable, and the state is the
        // part the driver needs to read.
        state.on -> VdtColors.OnFill

        command == null -> VdtColors.TextDisabled

        else -> VdtColors.DarkGray
      }
    IconTile(
      state.icon,
      title,
      modifier,
      interactionModifier =
      Modifier
        .semantics {
          contentDescription = title
          stateDescription = word
        }
        .toggleable(value = state.on, enabled = command != null, role = Role.Switch) {
          command?.let(store.onCommand)
        },
      tint = ink,
      labelColor = ink,
      background = if (state.on) VdtColors.Green else VdtColors.Panel,
      border = if (state.on) VdtColors.Green else VdtColors.PanelBorder,
      caption = word,
    )
  }
}
