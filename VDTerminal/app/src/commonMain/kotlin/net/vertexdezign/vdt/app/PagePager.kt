package net.vertexdezign.vdt.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import net.vertexdezign.vdt.app.layout.WidgetDashboard
import net.vertexdezign.vdt.app.pages.Page

/**
 * The swipeable stack of the user's [Page]s: a [HorizontalPager] over [pages]. Order is the list
 * order (so a reorder in the launcher changes both the swipe order and which page auto-shows).
 * [currentPageId] is the shell's open page; the pager syncs to it in both directions — an external
 * pick (auto-switch, launcher, dot tap) scrolls the pager, and a swipe reports the newly-settled page
 * back through [onPageChange].
 *
 * The position dots used to render here as a strip below the pager. They now live in the bottom bar
 * (see [net.vertexdezign.vdt.app.panels.Footer]) and read the shell's open page rather than the
 * pager's live scroll position, so they update when a swipe *settles* instead of when it crosses the
 * halfway point.
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
  // The settle effect below is keyed only on the (stable) pager, so it reads the live pages/id/callback
  // through these snapshots rather than capturing stale values from the composition that started it.
  val pagesState by rememberUpdatedState(pages)
  val currentPageIdState by rememberUpdatedState(currentPageId)
  val onPageChangeState by rememberUpdatedState(onPageChange)

  // External selection -> scroll to match. Keyed on the id so it only fires when the shell's open
  // page actually changes, not on every settle (which the other effect already handles).
  LaunchedEffect(currentPageId, pages) {
    val target = pages.indexOfFirst { it.id == currentPageId }
    if (target in pages.indices && target != pagerState.currentPage) {
      pagerState.animateScrollToPage(target)
    }
  }
  // A swipe settling on a new page -> tell the shell, so the header (edit target) and dots follow. We
  // watch settledPage, not currentPage: currentPage ticks through every index during an animated jump
  // (e.g. a dot tap across several pages), which would publish those fly-over pages as selections.
  LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.settledPage }.collect { idx ->
      pagesState.getOrNull(idx)?.let { if (it.id != currentPageIdState) onPageChangeState(it.id) }
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
}
