package net.vertexdezign.vdt.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CadenceTrackerTest {
  @Test
  fun `no writes yet has zero count and null intervals`() {
    val snap = CadenceTracker("x.json").snapshot()
    assertEquals("x.json", snap.name)
    assertEquals(0L, snap.writes)
    assertNull(snap.lastWriteEpochMs)
    assertNull(snap.lastIntervalMs)
    assertNull(snap.meanIntervalMs)
    assertNull(snap.minIntervalMs)
    assertNull(snap.maxIntervalMs)
  }

  @Test
  fun `first write is a baseline with no interval`() {
    val t = CadenceTracker("x.json")
    t.recordWrite(1_000)
    val snap = t.snapshot()
    assertEquals(1L, snap.writes)
    assertEquals(1_000, snap.lastWriteEpochMs)
    assertNull(snap.lastIntervalMs) // needs two writes
    assertNull(snap.meanIntervalMs)
  }

  @Test
  fun `tracks interval, min, max and last-write across writes`() {
    val t = CadenceTracker("x.json")
    t.recordWrite(0)
    t.recordWrite(2_000) // +2000
    t.recordWrite(2_500) // +500
    t.recordWrite(4_500) // +2000
    val snap = t.snapshot()
    assertEquals(4L, snap.writes)
    assertEquals(4_500, snap.lastWriteEpochMs)
    assertEquals(2_000, snap.lastIntervalMs)
    assertEquals(500, snap.minIntervalMs)
    assertEquals(2_000, snap.maxIntervalMs)
  }

  @Test
  fun `mean interval is EMA-smoothed toward recent intervals`() {
    val t = CadenceTracker("x.json")
    t.recordWrite(0)
    t.recordWrite(1_000) // first interval seeds EMA = 1000
    assertEquals(1_000.0, t.snapshot().meanIntervalMs)
    t.recordWrite(3_000) // +2000: EMA = 0.3*2000 + 0.7*1000 = 1300
    assertEquals(1_300.0, t.snapshot().meanIntervalMs!!, 0.001)
  }

  @Test
  fun `a repeated mtime (duplicate filesystem event) is not counted as a new write`() {
    val t = CadenceTracker("x.json")
    t.recordWrite(1_000)
    t.recordWrite(3_000) // real write, +2000
    t.recordWrite(3_000) // duplicate FS event for the same write -> ignored
    val snap = t.snapshot()
    assertEquals(2L, snap.writes)
    assertEquals(2_000, snap.lastIntervalMs)
    assertEquals(2_000, snap.minIntervalMs)
    assertEquals(2_000, snap.maxIntervalMs)
  }

  @Test
  fun `a backwards clock step does not produce a negative interval`() {
    val t = CadenceTracker("x.json")
    t.recordWrite(5_000)
    t.recordWrite(4_000) // NTP step back
    val snap = t.snapshot()
    assertEquals(2L, snap.writes) // still counted as a write
    assertNull(snap.lastIntervalMs) // but no (negative) interval recorded
    assertEquals(4_000, snap.lastWriteEpochMs) // baseline advances to the new time
    assertTrue(snap.minIntervalMs == null)
  }
}
