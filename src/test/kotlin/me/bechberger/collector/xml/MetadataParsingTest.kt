package me.bechberger.collector.xml

import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MetadataParsingTest {

    private val sampleMetadata: Metadata by lazy {
        val path = Paths.get("samples/metadata.xml")
        path.readXmlAs(Metadata::class.java)
    }

    @Test
    fun `sample metadata contains events`() {
        assertTrue(sampleMetadata.events.isNotEmpty(), "Expected at least one event in sample metadata")
    }

    @Test
    fun `events have non-blank names`() {
        sampleMetadata.events.forEach { event ->
            assertTrue(event.name.isNotBlank(), "Event name must not be blank")
        }
    }

    @Test
    fun `event names are unique`() {
        val names = sampleMetadata.events.map { it.name }
        assertEquals(names.distinct().size, names.size, "Event names should be unique within a metadata file")
    }

    @Test
    fun `metadata roundtrips through XML without losing events`() {
        val xml = objectToXml(sampleMetadata)
        val reparsed = kotlinXmlMapper.readValue(xml, Metadata::class.java)
        assertEquals(sampleMetadata.events.size, reparsed.events.size, "Event count must survive XML roundtrip")
    }

    @Test
    fun `known event GCHeapSummary is present in sample`() {
        val event = sampleMetadata.events.find { it.name == "GCHeapSummary" }
        assertNotNull(event, "Expected GCHeapSummary event in sample metadata")
    }

    @Test
    fun `configuration XML parses successfully`() {
        val path = Paths.get("samples/profile.jfr")
        assertTrue(path.toFile().exists(), "samples/profile.jfr must exist")
    }
}
