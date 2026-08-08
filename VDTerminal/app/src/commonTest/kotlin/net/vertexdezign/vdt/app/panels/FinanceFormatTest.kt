package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.app.theme.VdtColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The finance panel's money formatting. Worth pinning down because the grouping is hand-rolled (no
 * `String.format` on wasm) and every branch of it is a number a user reads off a dashboard: the
 * remainder group at the front, the sign living outside the digits, and zero staying unsigned.
 */
class FinanceFormatTest {
  @Test
  fun groupsThousandsFromTheRight() {
    assertEquals("0", formatMoney(0))
    assertEquals("7", formatMoney(7))
    assertEquals("999", formatMoney(999))
    assertEquals("1,000", formatMoney(1000))
    // The leading partial group is the case a naive chunk-from-the-left gets wrong.
    assertEquals("12,345", formatMoney(12345))
    assertEquals("123,456", formatMoney(123456))
    assertEquals("1,234,567", formatMoney(1234567))
    // Wider than Int: a long-running farm's balance is why the model carries Long.
    assertEquals("9,999,999,999", formatMoney(9999999999L))
  }

  @Test
  fun putsTheSignOutsideTheDigits() {
    assertEquals("-1,250", formatMoney(-1250))
    assertEquals("-1,250", formatMoney(-1250, withSign = true))
    assertEquals("+1,250", formatMoney(1250, withSign = true))
    assertEquals("1,250", formatMoney(1250))
  }

  @Test
  fun survivesTheWidestNegativeLong() {
    // Not a balance anyone will hold — it is the one value where taking the magnitude with abs()
    // would return itself, still negative, and print a doubled sign.
    assertEquals("-9,223,372,036,854,775,808", formatMoney(Long.MIN_VALUE))
    assertEquals("-9,223,372,036,854,775,808", formatMoney(Long.MIN_VALUE, withSign = true))
  }

  @Test
  fun zeroIsNeverSigned() {
    // A "+0" in a table of transactions reads as a credit that didn't happen.
    assertEquals("0", formatMoney(0, withSign = true))
  }

  @Test
  fun annuityMatchesTheModsOwnFormula() {
    // ELS_loan:calculateAnnuity — amount * ((1+r)^n * r) / ((1+r)^n - 1) / 12, with n in YEARS.
    // 200,000 over 20 years at 3.5%: (1.035)^20 = 1.98979, factor = 0.070361, /12 → 1172.68.
    // Duplicated in the app only so the "take a loan" panel can price the deal before the command
    // goes; the mod remains the authority, which is why this is pinned to its arithmetic.
    assertEquals(1173L, annuity(200_000, 3.5f, 20))

    // Shorter term, bigger instalment — the sanity check that `years` is not being read as months.
    assertTrue(annuity(200_000, 3.5f, 5) > annuity(200_000, 3.5f, 20))
  }

  @Test
  fun annuityDegradesRatherThanDividingByZero() {
    // A zero rate makes the mod's denominator ((1+r)^n - 1) zero, so this falls back to plain equal
    // instalments instead of producing NaN on screen.
    assertEquals(1000L, annuity(120_000, 0f, 10))

    // Nothing borrowed, or no term, is not a payment.
    assertEquals(0L, annuity(0, 3.5f, 20))
    assertEquals(0L, annuity(200_000, 3.5f, 0))
  }

  @Test
  fun zeroIsMutedRatherThanGreen() {
    // Most of the finances table is zeroes; colouring them as income would drown the rows that moved.
    assertEquals(VdtColors.DarkGray, moneyColor(0))
    assertEquals(VdtColors.AccentText, moneyColor(1))
    assertEquals(VdtColors.Red, moneyColor(-1))
  }
}
