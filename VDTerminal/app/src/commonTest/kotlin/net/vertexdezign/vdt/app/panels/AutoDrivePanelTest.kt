package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.model.AutoDrive
import net.vertexdezign.vdt.model.AutoDriveDestination
import net.vertexdezign.vdt.model.AutoDriveFillType
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoDrivePanelTest {
  private val network =
    listOf(
      AutoDriveDestination(1, "farm"),
      AutoDriveDestination(2, "Silo B", folder = "Grain"),
      AutoDriveDestination(3, "Silo A", folder = "Grain"),
      AutoDriveDestination(4, "Bale yard", folder = "Bales"),
      AutoDriveDestination(5, "Barn"),
    )

  @Test
  fun groupsUnfiledFirstThenFoldersAlphabeticallyEachSortedByName() {
    val groups = destinationGroups(network, "")
    assertEquals(listOf(null, "Bales", "Grain"), groups.map { it.first })
    assertEquals(listOf("Barn", "farm"), groups[0].second.map { it.name })
    assertEquals(listOf("Silo A", "Silo B"), groups[2].second.map { it.name })
  }

  @Test
  fun searchMatchesANameOrAFolder() {
    assertEquals(listOf(2, 3), destinationGroups(network, "grain").flatMap { it.second }.map { it.id }.sorted())
    assertEquals(listOf(3), destinationGroups(network, " silo a ").flatMap { it.second }.map { it.id })
    assertEquals(emptyList(), destinationGroups(network, "nothing"))
  }

  @Test
  fun theLoopCounterReadsAsAutoDrivesHudCountsIt() {
    assertEquals("Until stopped", loopsLabel(AutoDrive(loops = 0, active = true)))
    assertEquals("5", loopsLabel(AutoDrive(loops = 5)))
    assertEquals("3 of 5 left", loopsLabel(AutoDrive(loops = 5, loopsDone = 2, active = true)))
    assertEquals("0 of 5 left", loopsLabel(AutoDrive(loops = 5, loopsDone = 7, active = true)))
  }

  @Test
  fun driveTimeIsMinutesAndSecondsOrSecondsAlone() {
    assertEquals("1:35", formatDriveTime(95))
    assertEquals("10:05", formatDriveTime(605))
    assertEquals("42s", formatDriveTime(42))
  }

  @Test
  fun fillTypesMatchOnTheTitleOrTheEngineName() {
    val fillTypes = listOf(AutoDriveFillType("WHEAT", "Sommerweizen"), AutoDriveFillType("WINTERWHEAT", "Winterweizen"))
    assertEquals(fillTypes, fillTypeMatches(fillTypes, ""))
    assertEquals(listOf("WINTERWHEAT"), fillTypeMatches(fillTypes, "winter").map { it.name })
    assertEquals(2, fillTypeMatches(fillTypes, "wheat").size)
  }
}
