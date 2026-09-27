package net.vertexdezign.vdt.server

/**
 * BC7 (BPTC unorm) block decoding — a port of `woozymasta/bcn`'s `decodeBlockBC7`, itself a port of
 * `bcdec_bc7` from bcdec.h (MIT/Unlicense, (c) 2022 Sergii Kudlai), like the rest of [Dds]. The
 * partition tables were converted from bcn's `bptc_tables.go` by script, not retyped.
 *
 * FS25's base-game brand logos are BC7 behind a DX10 header, which is why this exists; the map
 * overviews seen so far are DXT.
 */
internal object Bc7 {
  /** One 16-byte block at [o] in [d] → 16 RGBA pixels, row-major. An unknown mode is transparent black. */
  fun decodeBlock(d: ByteArray, o: Int): ByteArray {
    val out = ByteArray(64)
    val r = BitReader(d, o)

    // The mode is unary: the count of zero bits before the first set one.
    var mode = 0
    while (mode < 8 && r.read(1) == 0) mode++
    if (mode >= 8) return out

    var partition = 0
    var numPartitions = 1
    var rotation = 0
    var indexSelectionBit = 0
    if (mode == 0 || mode == 1 || mode == 2 || mode == 3 || mode == 7) {
      numPartitions = if (mode == 0 || mode == 2) 3 else 2
      partition = r.read(if (mode == 0) 4 else 6)
    }
    val numEndpoints = numPartitions * 2
    if (mode == 4 || mode == 5) {
      rotation = r.read(2)
      if (mode == 4) indexSelectionBit = r.read(1)
    }

    val endpoints = Array(6) { IntArray(4) }
    val colorBits = COLOR_BITS[mode]
    for (c in 0 until 3) for (e in 0 until numEndpoints) endpoints[e][c] = r.read(colorBits)
    val alphaBits = ALPHA_BITS[mode]
    if (alphaBits > 0) for (e in 0 until numEndpoints) endpoints[e][3] = r.read(alphaBits)

    val hasPBits = MODE_HAS_P_BITS and (1 shl mode) != 0
    if (mode == 0 || mode == 1 || mode == 3 || mode == 6 || mode == 7) {
      for (e in 0 until numEndpoints) for (c in 0 until 4) endpoints[e][c] = endpoints[e][c] shl 1
      if (mode == 1) {
        // Shared P-bit: one for endpoints 0/1, one for 2/3, RGB only.
        val pi = r.read(1)
        val pj = r.read(1)
        for (c in 0 until 3) {
          endpoints[0][c] = endpoints[0][c] or pi
          endpoints[1][c] = endpoints[1][c] or pi
          endpoints[2][c] = endpoints[2][c] or pj
          endpoints[3][c] = endpoints[3][c] or pj
        }
      } else if (hasPBits) {
        for (e in 0 until numEndpoints) {
          val p = r.read(1)
          for (c in 0 until 4) endpoints[e][c] = endpoints[e][c] or p
        }
      }
    }

    // Expand to 8 bits: left-align, then replicate the high bits into the freed low ones.
    val pbit = if (hasPBits) 1 else 0
    val colorPrec = colorBits + pbit
    val alphaPrec = alphaBits + pbit
    for (e in 0 until numEndpoints) {
      for (c in 0 until 3) {
        val v = endpoints[e][c] shl (8 - colorPrec)
        endpoints[e][c] = v or (v ushr colorPrec)
      }
      val a = endpoints[e][3] shl (8 - alphaPrec)
      endpoints[e][3] = a or (a ushr alphaPrec)
    }
    if (alphaBits == 0) for (e in 0 until numEndpoints) endpoints[e][3] = 0xFF

    val indexBits =
      when (mode) {
        0, 1 -> 3
        6 -> 4
        else -> 2
      }
    val indexBits2 =
      when (mode) {
        4 -> 3
        5 -> 2
        else -> 0
      }
    val weights =
      when (indexBits) {
        2 -> WEIGHT2
        3 -> WEIGHT3
        else -> WEIGHT4
      }
    val weights2 = if (indexBits2 == 2) WEIGHT2 else WEIGHT3

    // All primary indices come first, then the secondary set: two passes over the texels.
    val indices = IntArray(16)
    for (t in 0 until 16) {
      val anchor = partitionAt(numPartitions, partition, t) and 0x80 != 0
      indices[t] = r.read(if (anchor) indexBits - 1 else indexBits)
    }

    for (t in 0 until 16) {
      val sub = partitionAt(numPartitions, partition, t) and 0x03
      val idx = indices[t]
      val e0 = endpoints[sub * 2]
      val e1 = endpoints[sub * 2 + 1]
      var cr: Int
      var cg: Int
      var cb: Int
      var ca: Int
      if (indexBits2 == 0) {
        cr = interpolate(e0[0], e1[0], weights, idx)
        cg = interpolate(e0[1], e1[1], weights, idx)
        cb = interpolate(e0[2], e1[2], weights, idx)
        ca = interpolate(e0[3], e1[3], weights, idx)
      } else {
        val idx2 = r.read(if (t == 0) indexBits2 - 1 else indexBits2)
        if (indexSelectionBit == 0) {
          cr = interpolate(e0[0], e1[0], weights, idx)
          cg = interpolate(e0[1], e1[1], weights, idx)
          cb = interpolate(e0[2], e1[2], weights, idx)
          ca = interpolate(e0[3], e1[3], weights2, idx2)
        } else {
          cr = interpolate(e0[0], e1[0], weights2, idx2)
          cg = interpolate(e0[1], e1[1], weights2, idx2)
          cb = interpolate(e0[2], e1[2], weights2, idx2)
          ca = interpolate(e0[3], e1[3], weights, idx)
        }
      }
      when (rotation) {
        1 -> cr = ca.also { ca = cr }
        2 -> cg = ca.also { ca = cg }
        3 -> cb = ca.also { ca = cb }
      }
      out[t * 4] = cr.toByte()
      out[t * 4 + 1] = cg.toByte()
      out[t * 4 + 2] = cb.toByte()
      out[t * 4 + 3] = ca.toByte()
    }
    return out
  }

  /** The block as a little-endian 128-bit window, read LSB-first. */
  private class BitReader(d: ByteArray, o: Int) {
    private var lo = le64(d, o)
    private var hi = le64(d, o + 8)

    /** Pops the low [n] bits (1..8). */
    fun read(n: Int): Int {
      val mask = (1L shl n) - 1
      val bits = (lo and mask).toInt()
      lo = (lo ushr n) or ((hi and mask) shl (64 - n))
      hi = hi ushr n
      return bits
    }

    private fun le64(d: ByteArray, o: Int): Long {
      var v = 0L
      for (k in 0 until 8) v = v or ((d[o + k].toLong() and 0xFF) shl (8 * k))
      return v
    }
  }

  private fun interpolate(a: Int, b: Int, weights: IntArray, index: Int): Int {
    val w = weights[index]
    return (a * (64 - w) + b * w + 32) shr 6
  }

  /** Subset (low 2 bits) and anchor flag (0x80) of texel [t]; one subset has texel 0 as its only anchor. */
  private fun partitionAt(numPartitions: Int, partition: Int, t: Int): Int = if (numPartitions == 1) {
    if (t == 0) 0x80 else 0
  } else {
    PARTITION_SETS[numPartitions - 2][partition * 16 + t]
  }

  private val WEIGHT2 = intArrayOf(0, 21, 43, 64)
  private val WEIGHT3 = intArrayOf(0, 9, 18, 27, 37, 46, 55, 64)
  private val WEIGHT4 = intArrayOf(0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64)
  private val COLOR_BITS = intArrayOf(4, 6, 5, 7, 5, 7, 7, 5)
  private val ALPHA_BITS = intArrayOf(0, 0, 0, 0, 6, 8, 7, 5)

  /** Modes 0, 1, 3, 6 and 7 carry P-bits. */
  private const val MODE_HAS_P_BITS = 0b11001011

  /** [2-subset, 3-subset], each 64 partitions × 16 texels, row-major. */
  private val PARTITION_SETS =
    arrayOf(
      intArrayOf(
        128, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 129,
        128, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 129,
        128, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 129,
        128, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 129,
        128, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 129,
        128, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 129,
        128, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 129,
        128, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 129,
        128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 129,
        128, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129,
        128, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 129,
        128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 129,
        128, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129,
        128, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 129,
        128, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129,
        128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 129,
        128, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 1, 1, 1, 129,
        128, 1, 129, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0,
        128, 0, 0, 0, 0, 0, 0, 0, 129, 0, 0, 0, 1, 1, 1, 0,
        128, 1, 129, 1, 0, 0, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0,
        128, 0, 129, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0,
        128, 0, 0, 0, 1, 0, 0, 0, 129, 1, 0, 0, 1, 1, 1, 0,
        128, 0, 0, 0, 0, 0, 0, 0, 129, 0, 0, 0, 1, 1, 0, 0,
        128, 1, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 0, 129,
        128, 0, 129, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0,
        128, 0, 0, 0, 1, 0, 0, 0, 129, 0, 0, 0, 1, 1, 0, 0,
        128, 1, 129, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0,
        128, 0, 129, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 0, 0,
        128, 0, 0, 1, 0, 1, 1, 1, 129, 1, 1, 0, 1, 0, 0, 0,
        128, 0, 0, 0, 1, 1, 1, 1, 129, 1, 1, 1, 0, 0, 0, 0,
        128, 1, 129, 1, 0, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1, 0,
        128, 0, 129, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0,
        128, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 129,
        128, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 129,
        128, 1, 0, 1, 1, 0, 129, 0, 0, 1, 0, 1, 1, 0, 1, 0,
        128, 0, 1, 1, 0, 0, 1, 1, 129, 1, 0, 0, 1, 1, 0, 0,
        128, 0, 129, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0,
        128, 1, 0, 1, 0, 1, 0, 1, 129, 0, 1, 0, 1, 0, 1, 0,
        128, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 129,
        128, 1, 0, 1, 1, 0, 1, 0, 1, 0, 1, 0, 0, 1, 0, 129,
        128, 1, 129, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 1, 0,
        128, 0, 0, 1, 0, 0, 1, 1, 129, 1, 0, 0, 1, 0, 0, 0,
        128, 0, 129, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 1, 0, 0,
        128, 0, 129, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 1, 0, 0,
        128, 1, 129, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0,
        128, 0, 1, 1, 1, 1, 0, 0, 1, 1, 0, 0, 0, 0, 1, 129,
        128, 1, 1, 0, 0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 0, 129,
        128, 0, 0, 0, 0, 1, 129, 0, 0, 1, 1, 0, 0, 0, 0, 0,
        128, 1, 0, 0, 1, 1, 129, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        128, 0, 129, 0, 0, 1, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0,
        128, 0, 0, 0, 0, 0, 129, 0, 0, 1, 1, 1, 0, 0, 1, 0,
        128, 0, 0, 0, 0, 1, 0, 0, 129, 1, 1, 0, 0, 1, 0, 0,
        128, 1, 1, 0, 1, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 129,
        128, 0, 1, 1, 0, 1, 1, 0, 1, 1, 0, 0, 1, 0, 0, 129,
        128, 1, 129, 0, 0, 0, 1, 1, 1, 0, 0, 1, 1, 1, 0, 0,
        128, 0, 129, 1, 1, 0, 0, 1, 1, 1, 0, 0, 0, 1, 1, 0,
        128, 1, 1, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 129,
        128, 1, 1, 0, 0, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0, 129,
        128, 1, 1, 1, 1, 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 129,
        128, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1, 0, 0, 1, 1, 129,
        128, 0, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 0, 0, 1, 129,
        128, 0, 129, 1, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0,
        128, 0, 129, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0,
        128, 1, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 1, 1, 129,
      ),
      intArrayOf(
        128, 0, 1, 129, 0, 0, 1, 1, 0, 2, 2, 1, 2, 2, 2, 130,
        128, 0, 0, 129, 0, 0, 1, 1, 130, 2, 1, 1, 2, 2, 2, 1,
        128, 0, 0, 0, 2, 0, 0, 1, 130, 2, 1, 1, 2, 2, 1, 129,
        128, 2, 2, 130, 0, 0, 2, 2, 0, 0, 1, 1, 0, 1, 1, 129,
        128, 0, 0, 0, 0, 0, 0, 0, 129, 1, 2, 2, 1, 1, 2, 130,
        128, 0, 1, 129, 0, 0, 1, 1, 0, 0, 2, 2, 0, 0, 2, 130,
        128, 0, 2, 130, 0, 0, 2, 2, 1, 1, 1, 1, 1, 1, 1, 129,
        128, 0, 1, 1, 0, 0, 1, 1, 130, 2, 1, 1, 2, 2, 1, 129,
        128, 0, 0, 0, 0, 0, 0, 0, 129, 1, 1, 1, 2, 2, 2, 130,
        128, 0, 0, 0, 1, 1, 1, 1, 129, 1, 1, 1, 2, 2, 2, 130,
        128, 0, 0, 0, 1, 1, 129, 1, 2, 2, 2, 2, 2, 2, 2, 130,
        128, 0, 1, 2, 0, 0, 129, 2, 0, 0, 1, 2, 0, 0, 1, 130,
        128, 1, 1, 2, 0, 1, 129, 2, 0, 1, 1, 2, 0, 1, 1, 130,
        128, 1, 2, 2, 0, 129, 2, 2, 0, 1, 2, 2, 0, 1, 2, 130,
        128, 0, 1, 129, 0, 1, 1, 2, 1, 1, 2, 2, 1, 2, 2, 130,
        128, 0, 1, 129, 2, 0, 0, 1, 130, 2, 0, 0, 2, 2, 2, 0,
        128, 0, 0, 129, 0, 0, 1, 1, 0, 1, 1, 2, 1, 1, 2, 130,
        128, 1, 1, 129, 0, 0, 1, 1, 130, 0, 0, 1, 2, 2, 0, 0,
        128, 0, 0, 0, 1, 1, 2, 2, 129, 1, 2, 2, 1, 1, 2, 130,
        128, 0, 2, 130, 0, 0, 2, 2, 0, 0, 2, 2, 1, 1, 1, 129,
        128, 1, 1, 129, 0, 1, 1, 1, 0, 2, 2, 2, 0, 2, 2, 130,
        128, 0, 0, 129, 0, 0, 0, 1, 130, 2, 2, 1, 2, 2, 2, 1,
        128, 0, 0, 0, 0, 0, 129, 1, 0, 1, 2, 2, 0, 1, 2, 130,
        128, 0, 0, 0, 1, 1, 0, 0, 130, 2, 129, 0, 2, 2, 1, 0,
        128, 1, 2, 130, 0, 129, 2, 2, 0, 0, 1, 1, 0, 0, 0, 0,
        128, 0, 1, 2, 0, 0, 1, 2, 129, 1, 2, 2, 2, 2, 2, 130,
        128, 1, 1, 0, 1, 2, 130, 1, 129, 2, 2, 1, 0, 1, 1, 0,
        128, 0, 0, 0, 0, 1, 129, 0, 1, 2, 130, 1, 1, 2, 2, 1,
        128, 0, 2, 2, 1, 1, 0, 2, 129, 1, 0, 2, 0, 0, 2, 130,
        128, 1, 1, 0, 0, 129, 1, 0, 2, 0, 0, 2, 2, 2, 2, 130,
        128, 0, 1, 1, 0, 1, 2, 2, 0, 1, 130, 2, 0, 0, 1, 129,
        128, 0, 0, 0, 2, 0, 0, 0, 130, 2, 1, 1, 2, 2, 2, 129,
        128, 0, 0, 0, 0, 0, 0, 2, 129, 1, 2, 2, 1, 2, 2, 130,
        128, 2, 2, 130, 0, 0, 2, 2, 0, 0, 1, 2, 0, 0, 1, 129,
        128, 0, 1, 129, 0, 0, 1, 2, 0, 0, 2, 2, 0, 2, 2, 130,
        128, 1, 2, 0, 0, 129, 2, 0, 0, 1, 130, 0, 0, 1, 2, 0,
        128, 0, 0, 0, 1, 1, 129, 1, 2, 2, 130, 2, 0, 0, 0, 0,
        128, 1, 2, 0, 1, 2, 0, 1, 130, 0, 129, 2, 0, 1, 2, 0,
        128, 1, 2, 0, 2, 0, 1, 2, 129, 130, 0, 1, 0, 1, 2, 0,
        128, 0, 1, 1, 2, 2, 0, 0, 1, 1, 130, 2, 0, 0, 1, 129,
        128, 0, 1, 1, 1, 1, 130, 2, 2, 2, 0, 0, 0, 0, 1, 129,
        128, 1, 0, 129, 0, 1, 0, 1, 2, 2, 2, 2, 2, 2, 2, 130,
        128, 0, 0, 0, 0, 0, 0, 0, 130, 1, 2, 1, 2, 1, 2, 129,
        128, 0, 2, 2, 1, 129, 2, 2, 0, 0, 2, 2, 1, 1, 2, 130,
        128, 0, 2, 130, 0, 0, 1, 1, 0, 0, 2, 2, 0, 0, 1, 129,
        128, 2, 2, 0, 1, 2, 130, 1, 0, 2, 2, 0, 1, 2, 2, 129,
        128, 1, 0, 1, 2, 2, 130, 2, 2, 2, 2, 2, 0, 1, 0, 129,
        128, 0, 0, 0, 2, 1, 2, 1, 130, 1, 2, 1, 2, 1, 2, 129,
        128, 1, 0, 129, 0, 1, 0, 1, 0, 1, 0, 1, 2, 2, 2, 130,
        128, 2, 2, 130, 0, 1, 1, 1, 0, 2, 2, 2, 0, 1, 1, 129,
        128, 0, 0, 2, 1, 129, 1, 2, 0, 0, 0, 2, 1, 1, 1, 130,
        128, 0, 0, 0, 2, 129, 1, 2, 2, 1, 1, 2, 2, 1, 1, 130,
        128, 2, 2, 2, 0, 129, 1, 1, 0, 1, 1, 1, 0, 2, 2, 130,
        128, 0, 0, 2, 1, 1, 1, 2, 129, 1, 1, 2, 0, 0, 0, 130,
        128, 1, 1, 0, 0, 129, 1, 0, 0, 1, 1, 0, 2, 2, 2, 130,
        128, 0, 0, 0, 0, 0, 0, 0, 2, 1, 129, 2, 2, 1, 1, 130,
        128, 1, 1, 0, 0, 129, 1, 0, 2, 2, 2, 2, 2, 2, 2, 130,
        128, 0, 2, 2, 0, 0, 1, 1, 0, 0, 129, 1, 0, 0, 2, 130,
        128, 0, 2, 2, 1, 1, 2, 2, 129, 1, 2, 2, 0, 0, 2, 130,
        128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 129, 1, 130,
        128, 0, 0, 130, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 129,
        128, 2, 2, 2, 1, 2, 2, 2, 0, 2, 2, 2, 129, 2, 2, 130,
        128, 1, 0, 129, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 130,
        128, 1, 1, 129, 2, 0, 1, 1, 130, 2, 0, 1, 2, 2, 2, 0,
      ),
    )
}
