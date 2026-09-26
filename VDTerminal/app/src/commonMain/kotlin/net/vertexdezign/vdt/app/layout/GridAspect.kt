package net.vertexdezign.vdt.app.layout

import androidx.compose.ui.unit.Dp

/**
 * Which of a page's two arrangements to show. A [net.vertexdezign.vdt.app.pages.Page] carries one
 * split tree per aspect and the shell picks between them from the *body's* measured size — see [of]
 * for why that, and not a media query or the window.
 *
 * Two, not four, and not one. A tree is ratios, so either arrangement would *draw* at any size — but
 * a landscape page squeezed onto a phone standing up is a page of tall stripes, and the aspect flip
 * is where that stops being cramped and starts being wrong. A phone held landscape is not a tablet
 * either, but it is the same shape of page, just smaller, and the widgets' compact forms cope.
 *
 * The name is from when each aspect was a cell grid; what is left of that is only the choice.
 */
enum class GridAspect {
  Landscape,
  Portrait,
  ;

  companion object {
    /**
     * The aspect for a body of [width] × [height].
     *
     * Measured from the page body rather than the viewport on purpose: the body is what the page
     * actually gets, and how much of the viewport reaches it depends on whether the shell's header
     * and bottom bar are there at all (they are not on a display — see
     * [net.vertexdezign.vdt.app.DisplayShell]). A media query would answer a different question from
     * the one the layout is asking.
     *
     * At exactly 1:1 either arrangement is equally wrong, and landscape is the one every page has.
     */
    fun of(width: Dp, height: Dp): GridAspect = if (height > width) Portrait else Landscape
  }
}
