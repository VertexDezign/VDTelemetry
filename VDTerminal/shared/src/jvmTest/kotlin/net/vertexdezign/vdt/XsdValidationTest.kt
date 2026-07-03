package net.vertexdezign.vdt

import java.io.File
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import kotlin.test.Test
import kotlin.test.fail

/** Verifies the example XMLs validate against `vdTelemetrySchema.xsd` (Phase 10 acceptance). */
class XsdValidationTest {

    private fun repoFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        fail("Could not locate $relative from ${File(".").absolutePath}")
    }

    private fun validate(exampleName: String) {
        val schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
            .newSchema(repoFile("vdTelemetrySchema.xsd"))
        val validator = schema.newValidator()
        try {
            validator.validate(StreamSource(repoFile("examples/xml/$exampleName")))
        } catch (e: Exception) {
            fail("$exampleName failed schema validation: ${e.message}")
        }
    }

    @Test fun tractorValidates() = validate("tractor_with_cultivator.xml")

    @Test fun combineValidates() = validate("combine.xml")

    @Test fun multipleImplementsValidates() = validate("mutliple_implements.xml")
}
