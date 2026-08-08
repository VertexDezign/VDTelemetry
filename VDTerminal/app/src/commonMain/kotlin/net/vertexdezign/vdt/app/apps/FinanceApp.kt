package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.panels.FinancePanel
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.widgets.FinanceWidget
import net.vertexdezign.vdt.app.widgets.Widget

/**
 * The Finance app: the local farm's books — the balance and loan, the month-by-month finances table,
 * and the money notifications as a running log — plus borrow/repay against the base-game loan.
 * Base-game data, so it is always available (the panel renders its own waiting/empty states).
 */
object FinanceApp : VdtApp {
  override val id = "finance"
  override val title = "Finance"
  override val icon: ImageVector = Icons.Filled.AccountBalance
  override val widgets: List<Widget> = listOf(FinanceWidget)

  @Composable
  override fun FullPage(modifier: Modifier) {
    val store = LocalVdtStore.current
    val finance by store.finance.collectAsState()
    FinancePanel(finance, modifier, onCommand = store.onCommand)
  }
}
