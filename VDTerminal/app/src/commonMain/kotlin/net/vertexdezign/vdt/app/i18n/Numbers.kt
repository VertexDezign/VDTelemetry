package net.vertexdezign.vdt.app.i18n

import kotlin.math.abs
import kotlin.math.round

/**
 * How a language writes a number: its decimal mark and its thousands separator — `1,234.5` in English,
 * `1.234,5` in German. Every number the terminal prints goes through one of these, because Kotlin/wasm
 * has no `String.format` or `NumberFormat` to fall back on, and a hand-built `"$whole.$tenths"` is an
 * English number in a German screen.
 *
 * Deliberately two characters and not CLDR: the readouts are fixed-width instruments, where a stable
 * format beats a perfectly regional one, and both are testable from `commonTest`.
 */
class Numbers(val decimalMark: Char, val groupMark: Char) {
  /** Thousands-group an unsigned digit string from the right — also what an amount field shows as it is typed. */
  fun group(digits: String): String {
    val grouped = StringBuilder()
    val firstGroup = digits.length % 3
    if (firstGroup > 0) grouped.append(digits, 0, firstGroup)
    var i = firstGroup
    while (i < digits.length) {
      if (grouped.isNotEmpty()) grouped.append(groupMark)
      grouped.append(digits, i, i + 3)
      i += 3
    }
    return grouped.toString()
  }

  /** A whole number, grouped, with a leading `+` on a positive one when [withSign]. */
  fun integer(value: Long, withSign: Boolean = false): String {
    // The magnitude comes off the string, not off abs(): abs(Long.MIN_VALUE) is still negative, and
    // grouping a "-" as a digit would print a doubled sign and a ragged first group.
    val sign = when {
      value < 0 -> "-"
      withSign && value > 0 -> "+"
      else -> ""
    }
    return sign + group(value.toString().removePrefix("-"))
  }

  /** A whole number, grouped. */
  fun integer(value: Int): String = integer(value.toLong())

  /**
   * [value] to exactly [places] decimals. Rounded from the scaled value rather than by printing the
   * double, which would give `1.7000000000000002`; signed off the rounded whole, so `-0.04` reads `0.0`
   * and `-0.5` keeps its sign. [grouped] separates thousands in the whole part.
   */
  fun fixed(value: Double, places: Int, grouped: Boolean = false): String {
    if (value.isNaN() || value.isInfinite()) return "—"
    var scale = 1L
    repeat(places) { scale *= 10 }
    val scaled = round(abs(value) * scale).toLong()
    val whole = (scaled / scale).toString()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    val wholePart = if (grouped) group(whole) else whole
    if (places <= 0) return sign + wholePart
    return "$sign$wholePart$decimalMark${(scaled % scale).toString().padStart(places, '0')}"
  }

  fun fixed(value: Float, places: Int, grouped: Boolean = false): String = fixed(value.toDouble(), places, grouped)

  /** Like [fixed], but at most [places] decimals: trailing zeros go, and a bare mark with them — `3.5`, `2`. */
  fun trimmed(value: Double, places: Int, grouped: Boolean = false): String {
    val full = fixed(value, places, grouped)
    if (places <= 0 || decimalMark !in full) return full
    return full.trimEnd('0').trimEnd(decimalMark)
  }

  fun trimmed(value: Float, places: Int, grouped: Boolean = false): String = trimmed(value.toDouble(), places, grouped)

  /**
   * A decimal as someone typed it, or null when it is not one. Either mark reads as the decimal mark,
   * whatever the language — a German keyboard on an English terminal still types a comma — so a typed
   * value carries no grouping; the fields that take one are whole-number fields.
   */
  fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

  /**
   * What a decimal field keeps of an edit: digits and one decimal mark, typed as either mark and shown
   * as this language's. A second mark is dropped — "3..5" and "3.5.1" are both unparseable — and so is
   * anything else, a grouping separator included.
   */
  fun decimalInput(typed: String): String {
    val cleaned = typed.filter { it.isDigit() || it == '.' || it == ',' }
    val mark = cleaned.indexOfFirst { it == '.' || it == ',' }
    if (mark < 0) return cleaned
    return cleaned.substring(0, mark) + decimalMark + cleaned.substring(mark + 1).filter { it.isDigit() }
  }

  companion object {
    /** `1,234.5` */
    val English = Numbers(decimalMark = '.', groupMark = ',')

    /** `1.234,5` */
    val German = Numbers(decimalMark = ',', groupMark = '.')
  }
}
