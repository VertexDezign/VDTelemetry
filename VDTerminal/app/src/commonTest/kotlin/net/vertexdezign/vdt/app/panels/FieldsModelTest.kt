package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.model.FieldInfoEntry
import net.vertexdezign.vdt.model.FieldStatus
import net.vertexdezign.vdt.model.FieldStatusSlice
import net.vertexdezign.vdt.model.MapField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the Fields list says a field is growing, and where that answer comes from.
 *
 * The rule under test is the one that fixes a real miss: `fieldInfo.crop` is a single cell at the
 * field's centre, and that cell is empty for every ordinary reason a spot can be — a track through the
 * middle, ground a machine drove over, the un-drilled half of a field being sown. A field in wheat
 * then read as growing nothing, and the app went on to offer to sow it.
 */
class FieldsModelTest {
  private fun row(
    crops: FieldStatus? = null,
    crop: String = "",
    owned: Boolean = true,
    growth: FieldStatus? = null,
    soil: FieldStatus? = null,
    info: FieldInfoEntry? = FieldInfoEntry(id = 1, crop = crop),
    haPerCell: Float = HA_PER_CELL_2KM,
  ) = FieldRow(
    mapField = MapField(id = 1, name = "12", areaHa = 4.8f, ownerFarmId = if (owned) 1 else null),
    info = info,
    growth = growth,
    soil = soil,
    crops = crops,
    mission = null,
    owned = owned,
    haPerCell = haPerCell,
  )

  /** A crops breakdown, in the shape the histogram produces: descending, labelled, `cells` sampled. */
  private fun cropStatus(vararg slices: Pair<String, Int>, blank: Int = 0) = FieldStatus(
    id = 1,
    cells = slices.sumOf { it.second },
    blank = blank,
    slices = slices.map { (label, cells) -> FieldStatusSlice("crop", cells, label) },
  )

  @Test
  fun namesTheCropTheRasterSeesEvenWhenTheCentreCellIsEmpty() {
    val row = row(crops = cropStatus("Wheat" to 400, blank = 30), crop = "")

    assertEquals("Wheat", fieldCrop(row), "the field is in wheat; only its middle is bare")
  }

  @Test
  fun namesTheFruitCoveringMostOfTheField() {
    // The centre happens to sit in the barley strip. The field is mostly wheat, and mostly is what a
    // list of fields is for.
    val row = row(crops = cropStatus("Wheat" to 300, "Barley" to 120), crop = "Barley")

    assertEquals("Wheat", fieldCrop(row))
    assertEquals(listOf("Wheat" to 300f / 420f, "Barley" to 120f / 420f), fieldCropMix(row))
  }

  @Test
  fun fallsBackToTheCentreSampleWhenTheRasterCannotResolveTheField() {
    // A sliver of polygon: under MIN_READING_HA there is not enough ground for the raster's answer to
    // be about a field, so the point sample — at least a real reading of a real cell — answers instead.
    val thin = row(crops = cropStatus("Wheat" to SLIVER_CELLS_2KM), crop = "Barley")
    assertEquals("Barley", fieldCrop(thin))
    assertTrue(fieldCropMix(thin).isEmpty(), "too thin to break down is too thin to quote shares off")

    // No raster at all: the plane has not been swept yet, which is the state the app opens in.
    assertEquals("Barley", fieldCrop(row(crops = null, crop = "Barley")))
    assertTrue(fieldCropMix(row(crops = null, crop = "Barley")).isEmpty())
  }

  @Test
  fun namesTheCropOnAFieldTooCoarseToQuoteSharesOff() {
    // The split the two thresholds exist for. Sixty cells is a tenth of a hectare — real field, and
    // the fruit covering most of it is a better answer than the one cell at its centre. It is still
    // not enough cells to print "83 %" off, so the mix stays quiet.
    val coarse = row(crops = cropStatus("Wheat" to 50, "Barley" to 10), crop = "Barley")
    assertEquals("Wheat", fieldCrop(coarse))
    assertTrue(fieldCropMix(coarse).isEmpty(), "sixty cells cannot carry two significant figures")

    val growth = row(growth = growthStatus("harvest" to 50, "cut" to 10))
    assertEquals(FieldHeadline("Ready", fromRaster = true), fieldHeadline(growth))
    assertTrue(!hasBreakdown(growth), "the bar quotes exact shares; this field has none to quote")
  }

  @Test
  fun theReadingLineMeansTheSameGroundOnEveryMapSize() {
    // The bug the hectare unit fixes. Half a hectare of wheat is half a hectare of wheat: 312 cells on
    // a 2 km map, 78 on a 4 km one, because the grid is 512² either way. A cell count would have named
    // the crop on one save and fallen back to the centre cell on the other.
    val small = row(crops = cropStatus("Wheat" to 312), crop = "Barley", haPerCell = HA_PER_CELL_2KM)
    val large = row(crops = cropStatus("Wheat" to 78), crop = "Barley", haPerCell = HA_PER_CELL_4KM)
    assertEquals("Wheat", fieldCrop(small))
    assertEquals("Wheat", fieldCrop(large))

    // And a sliver stays a sliver on both, which is the only thing this line is guarding against.
    assertEquals("Barley", fieldCrop(row(crops = cropStatus("Wheat" to 20), crop = "Barley")))
    assertEquals(
      "Barley",
      fieldCrop(row(crops = cropStatus("Wheat" to 5), crop = "Barley", haPerCell = HA_PER_CELL_4KM)),
    )
  }

  @Test
  fun trustsARasterThatNeverSaidHowBigItsCellsAre() {
    // haPerCell 0 is "nobody stated a terrain size", not "no ground": the raster resolved the field,
    // and refusing here would silence every field on the save rather than the slivers this guards.
    val unscaled = row(crops = cropStatus("Wheat" to 40), crop = "Barley", haPerCell = 0f)
    assertEquals("Wheat", fieldCrop(unscaled))
  }

  @Test
  fun saysNothingRatherThanGuessingOnABareField() {
    assertEquals("", fieldCrop(row(crops = null, crop = "")))
    assertEquals("", fieldCrop(row(crops = cropStatus(blank = 500), crop = "")), "sampled, and nothing on it")
    assertEquals("", fieldCrop(row(info = null)), "no fieldInfo channel and no raster")
  }

  /** A growth breakdown in the histogram's shape; no slices at all is the all-blank field. */
  private fun growthStatus(vararg slices: Pair<String, Int>, blank: Int = 0) = FieldStatus(
    id = 1,
    cells = slices.sumOf { it.second },
    blank = blank,
    slices = slices.map { (kind, cells) -> FieldStatusSlice(kind, cells) },
  )

  @Test
  fun callsAFieldWithNoGrowthOnItBareAcrossTheField() {
    // Mulch a field and its growth raster goes entirely blank: the game paints mulch on its soil
    // overlay and never on the growth one, whose only ground-type paints are cultivated, plowed,
    // stubble-tillage and seedbed. The raster has resolved every cell and every one of them says "no
    // growth" -- an answer about the whole field, not a hole in the data.
    val mulched = row(growth = growthStatus(blank = 500), crop = "")

    assertTrue(isBareByRaster(mulched))
    assertEquals(FieldHeadline("Bare", fromRaster = true), fieldHeadline(mulched))

    // The bug this fixes: it used to fall through to the centre sample and label itself with it.
    assertEquals("Bare", fieldHeadline(mulched).text)
    assertTrue(fieldHeadline(mulched).fromRaster, "the raster answered; saying otherwise sends the reader out there")
  }

  @Test
  fun doesNotCallAFieldBareWhenTheRasterSimplyCannotSeeIt() {
    // Too little ground in the polygon to trust either way -- that really is the centre sample's
    // question, and it is the sliver this guard exists for rather than any field on a real map.
    val thin = row(growth = growthStatus(blank = SLIVER_CELLS_2KM), crop = "")
    assertTrue(!isBareByRaster(thin))
    assertTrue(!fieldHeadline(thin).fromRaster)

    // No raster yet is not a bare field either, however the app opens.
    assertTrue(!isBareByRaster(row(growth = null)))

    // And a field that has growth on it is answered by the growth, not by this.
    val growing = row(growth = growthStatus("growing" to 400, blank = 30))
    assertTrue(!isBareByRaster(growing))
    assertEquals(FieldHeadline("Growing", fromRaster = true), fieldHeadline(growing))
  }

  /**
   * A soil breakdown, whose blank cells are the ordinary case: the plane's 0 means "nothing to report
   * here", so a clean field is all blank and every share is of the polygon rather than of the sample.
   */
  private fun soilStatus(vararg slices: Pair<String, Int>, blank: Int = 0) = FieldStatus(
    id = 1,
    cells = slices.sumOf { it.second },
    blank = blank,
    slices = slices.map { (kind, cells) -> FieldStatusSlice(kind, cells) },
  )

  @Test
  fun namesTheMulchBehindAFieldTheGrowthPlaneCallsBlank() {
    // The pair the whole reading exists for: the growth raster is blank either way, and only the soil
    // plane can say which of the two blank fields this is. Both halves are real -- field 13 of the
    // committed capture is 87 % mulched and fields 24, 44 and 90 are blank with no mulch at all; see
    // FieldStatusTest.countsMulchPerFieldOffTheRealSoilRaster, which is where the raster can be read.
    val mulched = row(growth = growthStatus(blank = 500), soil = soilStatus("mulched" to 300, blank = 200))
    assertEquals(0.6f, mulchShare(mulched))
    assertEquals("Nothing growing on any of it — the ground is mulched.", bareGroundNote(mulched))

    val bare = row(growth = growthStatus(blank = 500), soil = soilStatus(blank = 500))
    assertEquals(0f, mulchShare(bare))
    assertEquals("Nothing growing on any of it.", bareGroundNote(bare))

    // Both are still "Bare" in one word: the headline is the growth plane's answer, and it has not
    // changed. The mulch is the explanation underneath it.
    assertEquals(FieldHeadline("Bare", fromRaster = true), fieldHeadline(mulched))
  }

  @Test
  fun saysNothingAboutMulchWithoutASoilRasterToSayItFrom() {
    // No soil plane subscribed: null, not zero -- and the sentence falls back to what the growth plane
    // alone can support rather than claiming the field is unmulched.
    val unswept = row(growth = growthStatus(blank = 500))
    assertEquals(null, mulchShare(unswept))
    assertEquals("Nothing growing on any of it.", bareGroundNote(unswept))

    // Too little of the polygon resolved to read a share off, exactly as for the plough and the weeds.
    val thin = row(soil = soilStatus("mulched" to SLIVER_CELLS_2KM))
    assertEquals(null, mulchShare(thin))

    // A half-hectare field on a 4 km map is 78 cells -- under the quoting line and well over the
    // reading one, and the soil shares are floors, so it keeps its reading rather than its centre cell.
    val large = row(soil = soilStatus("mulched" to 40, blank = 38), haPerCell = HA_PER_CELL_4KM)
    assertEquals(40f / 78f, mulchShare(large))
  }

  @Test
  fun onlyReportsAMixWhenThereIsOne() {
    assertEquals(listOf("Wheat" to 1f), fieldCropMix(row(crops = cropStatus("Wheat" to 400))))
  }

  @Test
  fun doesNotOfferToSowAFieldThatIsAlreadyPlanted() {
    // The consequence of the miss, and the reason it was worth fixing rather than noting: a field whose
    // centre cell is bare used to be offered a sow task while standing in wheat.
    val planted = row(crops = cropStatus("Wheat" to 400, blank = 30), crop = "")
    assertTrue(FieldTaskType.SOW !in fieldWork(planted))

    val bare = row(crops = cropStatus(blank = 500), crop = "")
    assertTrue(FieldTaskType.SOW in fieldWork(bare), "a genuinely bare owned field is still work")
  }

  private companion object {
    /** A 512² grid over a 2 km map: cells are 4 m square. The scale every unqualified row here is on. */
    const val HA_PER_CELL_2KM = 0.0016f

    /** The same grid over a 4 km map — 8 m cells, so a quarter of the samples for the same ground. */
    const val HA_PER_CELL_4KM = 0.0064f

    /** Under [MIN_READING_HA] on a 2 km map (0.032 ha): a strip of polygon rather than a field. */
    const val SLIVER_CELLS_2KM = 20
  }
}
