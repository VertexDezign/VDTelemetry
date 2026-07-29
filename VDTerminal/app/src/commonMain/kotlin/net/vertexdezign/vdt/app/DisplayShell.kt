package net.vertexdezign.vdt.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import net.vertexdezign.vdt.app.apps.AppRegistry
import net.vertexdezign.vdt.app.layout.WidgetDashboard
import net.vertexdezign.vdt.app.pages.Page
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.theme.VdtColors

/** How long a pointer must be held still before the display's controls appear. */
private const val HOLD_MS = 2_000L

/** How long they stay up afterwards with nothing touched. */
private const val REVEAL_MS = 8_000L

/**
 * The shell for a device in **display mode**: one screen, filling the viewport, with none of the
 * chrome a hand-driven tablet needs. No header, no bottom bar, no launcher, no notification centre, no
 * edit toggle, no page swipe — and, importantly, no auto-switch when you step out of the tractor, which
 * is right for the tablet and wrong for something clamped to the pillar.
 *
 * What stays is what a fixed screen still owes the driver: the loading and connection states, and a way
 * back out. Alert banners are deliberately *not* here — the tablet owns those, so one alert doesn't
 * announce itself twice in one cab and cover the readout this device exists for.
 *
 * The pinned screen is [screen], already resolved from the stored id ([net.vertexdezign.vdt.app.state.resolveDisplay]);
 * null means the id names nothing that exists any more, which is a state you must be able to escape
 * rather than a black screen.
 */
@Composable
fun DisplayShell(
  screen: Screen?,
  target: String,
  pages: List<Page>,
  ready: Boolean,
  onExit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var revealed by remember { mutableStateOf(false) }

  // A reveal that was really a fat-fingered lean on the screen shouldn't leave a live EXIT sitting
  // there for the rest of the session, so the controls time out on their own.
  LaunchedEffect(revealed) {
    if (revealed) {
      delay(REVEAL_MS)
      revealed = false
    }
  }

  Box(modifier.fillMaxSize().holdToReveal { revealed = true }) {
    Column(Modifier.fillMaxSize()) {
      when {
        // Held until the first frame arrives, exactly as the tablet does — but inside the hold
        // detector, so a display that never connects can still be got out of.
        !ready -> Box(Modifier.fillMaxWidth().weight(1f)) { LoadingScreen() }

        screen is Screen.OpenPage ->
          pages.firstOrNull { it.id == screen.pageId }?.let { WidgetDashboard(it, editing = false) }

        screen is Screen.OpenApp ->
          AppRegistry.byId(screen.appId)?.let { app ->
            Box(Modifier.fillMaxWidth().weight(1f).padding(8.dp)) { app.FullPage(Modifier.fillMaxSize()) }
          }

        else -> Box(Modifier.fillMaxWidth().weight(1f)) { MissingTarget(target) }
      }
    }

    // Pinned at nothing means the hold gesture is the only thing standing between the user and a dead
    // device, so don't make them discover it — show the way out outright.
    if (revealed || screen == null) {
      DisplayControls(onExit, Modifier.align(Alignment.TopCenter).padding(top = 16.dp))
    }
  }
}

/**
 * The transient controls a held press reveals: what this device is, whether the screen is actually
 * being kept awake, and the way back to the full shell.
 *
 * The wake-lock state is here because it has nowhere else to go — display mode asks for the lock at
 * startup, but the browser can refuse (or not support it at all), and the header that used to report
 * that is gone.
 */
@Composable
private fun DisplayControls(onExit: () -> Unit, modifier: Modifier = Modifier) {
  val store = LocalVdtStore.current
  val wakeLock by store.wakeLock.collectAsState()
  val (wakeIcon, wakeLabel) =
    when (wakeLock) {
      WakeLockStatus.On -> Icons.Filled.Coffee to "AWAKE"
      WakeLockStatus.Off -> Icons.Filled.Bedtime to "SCREEN MAY SLEEP"
      WakeLockStatus.Unsupported -> Icons.Filled.Bedtime to "NO WAKE LOCK"
    }

  Row(
    modifier
      .clip(RoundedCornerShape(10.dp))
      .background(VdtColors.Black.copy(alpha = 0.85f))
      .padding(horizontal = 14.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text("DISPLAY MODE", color = VdtColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)

    Row(
      // Tappable whenever the API exists, so a lock the browser refused without a gesture can be
      // granted by hand from here.
      Modifier
        .clickable(
          enabled = wakeLock != WakeLockStatus.Unsupported,
          onClick = store.onToggleWakeLock,
        )
        .padding(horizontal = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      val tint = VdtColors.White.copy(alpha = if (wakeLock == WakeLockStatus.On) 1f else 0.55f)
      Icon(wakeIcon, "screen wake lock: $wakeLabel", tint = tint, modifier = Modifier.size(16.dp))
      Text(wakeLabel, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }

    Row(
      Modifier
        .clip(RoundedCornerShape(6.dp))
        .background(VdtColors.White.copy(alpha = 0.14f))
        .clickable(onClick = onExit)
        .padding(horizontal = 10.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(Icons.Filled.Close, null, tint = VdtColors.White, modifier = Modifier.size(14.dp))
      Text("EXIT DISPLAY", color = VdtColors.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
  }
}

/** A pinned id that resolves to nothing — the page was deleted, or the URL had a typo in it. */
@Composable
private fun MissingTarget(target: String) {
  Column(
    Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("NO SUCH SCREEN", color = VdtColors.Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text(
      "This device is pinned to “$target”, which no longer exists.\n" +
        "Exit display mode, or open ?display=<page> for one that does.",
      color = VdtColors.DarkGray,
      fontSize = 13.sp,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 10.dp, start = 24.dp, end = 24.dp),
    )
  }
}

/**
 * Calls [onHold] when a pointer is held still for [HOLD_MS] — the display's escape hatch.
 *
 * Watched on [PointerEventPass.Initial] and never consumed, so the hold is seen *before* the widgets
 * underneath and works anywhere on the screen, including over a button, while leaving every ordinary
 * tap and drag to behave normally. That is what lets the display stay interactive (lights, cruise) and
 * still have a way out: nothing here is visibly pressable, and a press long enough to count is far more
 * deliberate than anything a bump in the cab produces.
 */
private fun Modifier.holdToReveal(onHold: () -> Unit): Modifier = pointerInput(Unit) {
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    // Returns null only if the loop never finished, i.e. the finger was still down and still still.
    val lifted =
      withTimeoutOrNull(HOLD_MS) {
        while (true) {
          val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
          if (!change.pressed) break
          if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
        }
      }
    if (lifted == null) onHold()
  }
}
