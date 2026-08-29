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
    info: FieldInfoEntry? = FieldInfoEntry(id = 1, crop = crop),
  ) = FieldRow(
    mapField = MapField(id = 1, name = "12", areaHa = 4.8f, ownerFarmId = if (owned) 1 else null),
    info = info,
    growth = growth,
    soil = null,
    crops = crops,
    mission = null,
    owned = owned,
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
    // Below MIN_STATUS_CELLS the percentages are not worth quoting and neither is the dominant fruit,
    // so the point sample — which is at least a real reading of a real cell — answers instead.
    val thin = row(crops = cropStatus("Wheat" to MIN_STATUS_CELLS - 1), crop = "Barley")
    assertEquals("Barley", fieldCrop(thin))
    assertTrue(fieldCropMix(thin).isEmpty(), "too thin to break down is too thin to quote shares off")

    // No raster at all: the plane has not been swept yet, which is the state the app opens in.
    assertEquals("Barley", fieldCrop(row(crops = null, crop = "Barley")))
    assertTrue(fieldCropMix(row(crops = null, crop = "Barley")).isEmpty())
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
    // Too few cells in the polygon to trust either way -- that really is the centre sample's question.
    val thin = row(growth = growthStatus(blank = MIN_STATUS_CELLS - 1), crop = "")
    assertTrue(!isBareByRaster(thin))
    assertTrue(!fieldHeadline(thin).fromRaster)

    // No raster yet is not a bare field either, however the app opens.
    assertTrue(!isBareByRaster(row(growth = null)))

    // And a field that has growth on it is answered by the growth, not by this.
    val growing = row(growth = growthStatus("growing" to 400, blank = 30))
    assertTrue(!isBareByRaster(growing))
    assertEquals(FieldHeadline("Growing", fromRaster = true), fieldHeadline(growing))
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
}
