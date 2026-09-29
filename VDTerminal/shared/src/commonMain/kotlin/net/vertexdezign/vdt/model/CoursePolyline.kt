package net.vertexdezign.vdt.model

import kotlin.math.hypot
import kotlin.math.sqrt

/*
 * Arithmetic on the flat `[x1, z1, x2, z2, …]` polylines a course line is sent as, shared so the
 * server that records [CourseProgress] and the app that draws it measure a line the same way.
 * Lengths are in the polyline's own units — normalized map coordinates for a course line.
 */

/** Where a point lies against a polyline: [along] it from its first point, and [off] it sideways. */
data class PolylineHit(val along: Float, val off: Float)

/** Total length of a flat polyline; 0 for anything shorter than two points. */
fun polylineLength(p: List<Float>): Float {
  var length = 0f
  for (k in 0 until p.size / 2 - 1) length += hypot(p[2 * k + 2] - p[2 * k], p[2 * k + 3] - p[2 * k + 1])
  return length
}

/**
 * The point of the polyline nearest to ([x], [z]), as a distance along it and the distance to it; null
 * when the polyline has no length to project onto.
 */
fun projectOnPolyline(p: List<Float>, x: Float, z: Float): PolylineHit? {
  var best: PolylineHit? = null
  var start = 0f
  for (k in 0 until p.size / 2 - 1) {
    val ax = p[2 * k]
    val az = p[2 * k + 1]
    val dx = p[2 * k + 2] - ax
    val dz = p[2 * k + 3] - az
    val lengthSq = dx * dx + dz * dz
    if (lengthSq <= 0f) continue
    val t = (((x - ax) * dx + (z - az) * dz) / lengthSq).coerceIn(0f, 1f)
    val off = hypot(x - (ax + dx * t), z - (az + dz * t))
    val length = sqrt(lengthSq)
    if (best == null || off < best.off) best = PolylineHit(start + t * length, off)
    start += length
  }
  return best
}

/**
 * The part of the polyline between the fractions [from] and [to] of its length, as a flat polyline of
 * its own — the stretch of a course line that has been driven. Empty when there is nothing between.
 */
fun slicePolyline(p: List<Float>, from: Float, to: Float): List<Float> {
  val total = polylineLength(p)
  if (total <= 0f || to <= from) return emptyList()
  val lo = from.coerceIn(0f, 1f) * total
  val hi = to.coerceIn(0f, 1f) * total
  val out = ArrayList<Float>()
  var start = 0f
  for (k in 0 until p.size / 2 - 1) {
    val ax = p[2 * k]
    val az = p[2 * k + 1]
    val dx = p[2 * k + 2] - ax
    val dz = p[2 * k + 3] - az
    val length = hypot(dx, dz)
    val end = start + length
    if (length > 0f && end > lo && start < hi) {
      val t0 = ((lo - start) / length).coerceIn(0f, 1f)
      val t1 = ((hi - start) / length).coerceIn(0f, 1f)
      // The entry point only when the slice starts on this edge; otherwise it is the previous exit.
      if (out.isEmpty()) {
        out += ax + dx * t0
        out += az + dz * t0
      }
      out += ax + dx * t1
      out += az + dz * t1
    }
    start = end
  }
  return if (out.size >= 4) out else emptyList()
}
