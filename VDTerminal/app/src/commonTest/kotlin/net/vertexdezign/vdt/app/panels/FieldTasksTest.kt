package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.app.i18n.de.German
import net.vertexdezign.vdt.app.i18n.en.English
import net.vertexdezign.vdt.model.Task
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldTasksTest {
  @Test
  fun aTaskIsWrittenInTheReadersLanguage() {
    assertEquals("F12 - Sow - Weizen", composeTaskDetail(English, 12, FieldTaskType.SOW, "Weizen"))
    assertEquals("F12 - Säen - Weizen", composeTaskDetail(German, 12, FieldTaskType.SOW, "Weizen"))
  }

  @Test
  fun aTaskWrittenInOneLanguageAttachesInAnother() {
    // The detail lives in the game's task list, which every player shares: one terminal's German has
    // to read back on another's English, or the link between task and field is lost to whoever wrote it.
    val written = composeTaskDetail(German, 7, FieldTaskType.PLOW)
    val ref = parseFieldTask("g", Task(detail = written))

    assertEquals(7, ref?.fieldId)
    assertEquals(FieldTaskType.PLOW, ref?.type)
    assertEquals("Plow", ref?.let { fieldTaskLabel(English, it) })
    assertEquals(FieldTaskType.HARVEST, FieldTaskType.of("harvest"))
    assertEquals(FieldTaskType.OTHER, FieldTaskType.of("Ballen wickeln"))
  }
}
