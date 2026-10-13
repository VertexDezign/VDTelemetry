package net.vertexdezign.vdt.app.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NumbersTest {
  private val en = Numbers.English
  private val de = Numbers.German

  @Test
  fun groupsWholeNumbersWithTheLanguagesSeparator() {
    assertEquals("1,234,567", en.integer(1_234_567))
    assertEquals("1.234.567", de.integer(1_234_567))
    assertEquals("-1.250", de.integer(-1250L))
    assertEquals("+1.250", de.integer(1250L, withSign = true))
    assertEquals("999", de.integer(999))
  }

  @Test
  fun writesDecimalsWithTheLanguagesMark() {
    assertEquals("1234.5", en.fixed(1234.5, 1))
    assertEquals("1234,5", de.fixed(1234.5, 1))
    assertEquals("1.234,50", de.fixed(1234.5, 2, grouped = true))
    assertEquals("12", de.fixed(12.4, 0))
  }

  @Test
  fun keepsTheSignOnlyWhereTheRoundedValueHasOne() {
    assertEquals("-0,5", de.fixed(-0.5, 1))
    assertEquals("0,0", de.fixed(-0.04, 1))
    // 1.7 is 1.7000000000000002 as a double; rounding the scaled value hides it.
    assertEquals("1,70", de.fixed(1.7, 2))
  }

  @Test
  fun roundsHalfUpAndInTheFloatItWasGiven() {
    assertEquals("3", en.fixed(2.5, 0))
    assertEquals("-0.01", en.fixed(-0.005f, 2))
    assertEquals("0.3", en.fixed(0.25f, 1))
  }

  @Test
  fun trimsTrailingZerosAndABareMark() {
    assertEquals("3,5", de.trimmed(3.50, 2))
    assertEquals("2", de.trimmed(2.0, 2))
    assertEquals("0.55", en.trimmed(0.55, 2))
    // The whole part's own zeros stay.
    assertEquals("1.000", de.trimmed(1000.0, 2, grouped = true))
  }

  @Test
  fun readsEitherMarkWhateverTheLanguage() {
    assertEquals(3.5, en.parse("3,5"))
    assertEquals(3.5, de.parse("3.5"))
    assertEquals(3.5, de.parse(" 3,5 "))
    assertNull(de.parse(""))
    assertNull(de.parse("3,5,1"))
  }

  @Test
  fun aDecimalFieldKeepsOneMarkInTheLanguagesForm() {
    assertEquals("3,5", de.decimalInput("3.5"))
    assertEquals("3.5", en.decimalInput("3,5"))
    assertEquals("3,51", de.decimalInput("3,5,1"))
    // A typed grouping separator is read as the decimal mark: the fields hold prices and quantities,
    // where "1.5" is far likelier than a thousands-grouped "1.000".
    assertEquals("1,000", de.decimalInput("1.000"))
    assertEquals("12", de.decimalInput("1a2"))
  }
}
