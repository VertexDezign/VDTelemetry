package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The look the pillar-cluster tiles share: a black panel carrying bright, chunky readouts, after the
 * A-pillar display a modern tractor puts between the windscreen and the right-hand window.
 *
 * Its own palette rather than [net.vertexdezign.vdt.app.theme.VdtColors], which is tuned for dark
 * marks on the terminal's light panels. Those greens and ambers are legible on white and muddy on
 * black, and this is a different instrument: something read off at a glance, off-axis, in a cab
 * that's moving — so the colours are the saturated ones a real cluster uses, and they carry meaning
 * rather than decoration. Amber is a value *you* set, green is go, red is wrong, blue is high beam.
 */
object ClusterColors {
  /** Not pure black: a hair of lift keeps the tile visible as a panel on a dark page. */
  val Surface = Color(0xFF0A0D0B)
  val Digits = Color(0xFFF2F5F2)

  /** A target the driver asked for — cruise, and the lamps for a lock you engaged. */
  val Set = Color(0xFFFF9F0A)
  val Go = Color(0xFF32D74B)
  val Warn = Color(0xFFFF453A)
  val Beam = Color(0xFF409CFF)

  /**
   * A lamp that is off but present — barely there.
   *
   * A real cluster shows an unlit lamp as nothing at all: the symbol is printed on the lens and only
   * exists when it is backlit. Going that far would make the band reflow every time a lamp changed,
   * which is worse than the problem, so an off lamp holds its place at just enough contrast to be
   * found if you look for it and not enough to compete with a lamp that is actually saying something.
   */
  val Dim = Color(0xFF1F2420)

  /** The empty part of a level bar. Light, as on the reference cluster, so the level reads as a line. */
  val Track = Color(0xFFCFD4D0)
  val Label = Color(0xFF8B948D)
}

/**
 * The digit face. A heavy monospace at size rather than a true seven-segment font: a segment face
 * means either licensing a bundled font or drawing the segments by hand, and this gets most of the
 * effect for neither. Monospace matters more than the exact shapes — it stops the whole readout
 * shuffling sideways every time a digit changes.
 */
val ClusterDigitFont = FontFamily.Monospace

/** Panel chrome for a cluster tile: the black surface, and nothing else. */
@Composable
fun ClusterSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
  Box(
    modifier.clip(RoundedCornerShape(6.dp)).background(ClusterColors.Surface).padding(8.dp),
    content = content,
  )
}

/**
 * What a cluster tile shows with no vehicle to report on.
 *
 * A dark panel with a dash, not the light "No vehicle connected" card the other widgets use. A
 * cluster page is mostly these tiles, so borrowing that card would turn stepping out of the tractor
 * into a screen of white boxes announcing an absence — where a real cluster simply goes dark.
 */
@Composable
fun ClusterEmpty(modifier: Modifier = Modifier) {
  ClusterSurface(modifier) {
    Text(
      "—",
      color = ClusterColors.Dim,
      fontSize = 28.sp,
      fontFamily = ClusterDigitFont,
      fontWeight = FontWeight.Black,
      modifier = Modifier.align(Alignment.Center),
    )
  }
}

/** A caption beside a readout or under a bar. */
@Composable
internal fun ClusterLabel(
  text: String,
  modifier: Modifier = Modifier,
  color: Color = ClusterColors.Label,
  align: TextAlign = TextAlign.Start,
) {
  Text(text, modifier, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = align)
}
