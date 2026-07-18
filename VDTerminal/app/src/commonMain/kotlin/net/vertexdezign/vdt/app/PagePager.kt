package net.vertexdezign.vdt.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.app.layout.WidgetDashboard
import net.vertexdezign.vdt.app.pages.Page
import net.vertexdezign.vdt.app.theme.VdtColors

/**
 * The swipeable stack of the user's [Page]s: a [HorizontalPager] over [pages] with a dot strip below
 * it. Order is the list order (so a reorder in the launcher changes both the swipe order and which
 * page auto-shows). [currentPageId] is the shell's open page; the pager syncs to it in both
 * directions — an external pick (auto-switch, launcher, dot tap) scrolls the pager, and a swipe
 * reports the newly-settled page back through [onPageChange].
 */
@Composable
fun ColumnScope.PagePager(
  pages: List<Page>,
  currentPageId: String,
  editing: Boolean,
  onPageChange: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val currentIndex = pages.indexOfFirst { it.id == currentPageId }.coerceAtLeast(0)
  val pagerState = rememberPagerState(initialPage = currentIndex) { pages.size }
  // The effect below restarts on pages/state changes; read the latest callback through this so it
  // never captures a stale lambda from an earlier composition.
  val onPageChangeState by rememberUpdatedState(onPageChange)

  // External selection -> scroll to match. Keyed on the id so it only fires when the shell's open
  // page actually changes, not on every settle (which the other effect already handles).
  LaunchedEffect(currentPageId, pages) {
    val target = pages.indexOfFirst { it.id == currentPageId }
    if (target in pages.indices && target != pagerState.currentPage) {
      pagerState.animateScrollToPage(target)
    }
  }
  // A swipe settling on a new page -> tell the shell, so the header (edit target) and dots follow.
  LaunchedEffect(pagerState, pages) {
    snapshotFlow { pagerState.currentPage }.collect { idx ->
      pages.getOrNull(idx)?.let { if (it.id != currentPageId) onPageChangeState(it.id) }
    }
  }

  HorizontalPager(
    state = pagerState,
    // Editing has its own widget-drag/resize affordances; swiping would fight them. The map consumes
    // its own transform gestures, so when swipe *is* enabled it only fires over non-map areas.
    userScrollEnabled = !editing,
    modifier = modifier.fillMaxWidth().weight(1f),
  ) { index ->
    val page = pages[index]
    Column(Modifier.fillMaxSize()) {
      // Only the settled page is editable; neighbours stay read-only so a stray toolbar can't flash by.
      WidgetDashboard(page, editing = editing && index == pagerState.currentPage)
    }
  }

  if (pages.size > 1) {
    PageDots(count = pages.size, current = pagerState.currentPage) { idx ->
      pages.getOrNull(idx)?.let { onPageChange(it.id) }
    }
  }
}

/** The position indicator: one dot per page, the current one filled and larger. Tapping a dot jumps. */
@Composable
private fun PageDots(count: Int, current: Int, onSelect: (Int) -> Unit) {
  Row(
    Modifier.fillMaxWidth().background(VdtColors.Panel).padding(vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (i in 0 until count) {
      val active = i == current
      Box(
        Modifier
          .size(if (active) 10.dp else 7.dp)
          .clip(CircleShape)
          .background(if (active) VdtColors.Green else VdtColors.DarkGray.copy(alpha = 0.4f))
          .clickable(interactionSource = null, indication = null) { onSelect(i) },
      )
    }
  }
}
