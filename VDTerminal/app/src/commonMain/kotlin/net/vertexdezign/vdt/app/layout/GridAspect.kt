package net.vertexdezign.vdt.app.layout

import androidx.compose.ui.unit.Dp

/**
 * Which of the two grids a page is laid out on. A [net.vertexdezign.vdt.app.pages.Page] carries one
 * arrangement per aspect and the shell picks between them from the *body's* measured size — see
 * [of] for why that, and not a media query or the window.
 *
 * There are two, not four. A phone held landscape (844×390dp) is not a tablet either, but 12×7 gives
 * it 70×56dp cells, which is close enough to square to be fine; the aspect flip is where the grid
 * actually stops working, so that is where the second layout earns its keep.
 */
enum class GridAspect {
  Landscape,
  Portrait,
  ;

  val columns: Int
    get() = when (this) {
      Landscape -> GridLayout.COLUMNS
      Portrait -> GridLayout.PORTRAIT_COLUMNS
    }

  val rows: Int
    get() = when (this) {
      Landscape -> GridLayout.ROWS
      Portrait -> GridLayout.PORTRAIT_ROWS
    }

  companion object {
    /**
     * The aspect for a body of [width] × [height].
     *
     * Measured from the page body rather than the viewport on purpose: the body is what the grid
     * actually gets, and how much of the viewport reaches it depends on whether the shell's header
     * and bottom bar are there at all (they are not on a display — see
     * [net.vertexdezign.vdt.app.DisplayShell]). A media query would answer a different question from
     * the one the grid is asking.
     *
     * Square is the tie-break's business, not ours: at exactly 1:1 either grid is equally wrong, and
     * landscape is the one every existing page is already laid out on.
     */
    fun of(width: Dp, height: Dp): GridAspect = if (height > width) Portrait else Landscape
  }
}
