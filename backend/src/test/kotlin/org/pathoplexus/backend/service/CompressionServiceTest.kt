package org.pathoplexus.backend.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.pathoplexus.backend.SpringBootTestWithoutDatabase
import org.pathoplexus.backend.api.Organism
import org.pathoplexus.backend.api.OriginalData
import org.pathoplexus.backend.config.BackendConfig
import org.springframework.beans.factory.annotation.Autowired

@SpringBootTestWithoutDatabase
class CompressionServiceTest(
    @Autowired private val compressor: CompressionService,
    @Autowired private val backendConfig: BackendConfig,
) {

    @Test
    fun `Compress and decompress sequence`() {
        val input =
            "NNACTGACTGACTGACTGATCGATCGATCGATCGATCGATCGATC----NNNNATCGCGATCGATCGATCGATCGGGATCGTAGC--NNNNATGC"

        val segmentName = "main"
        val testData = OriginalData(
            mapOf("test" to "test"),
            mapOf(segmentName to input),
        )
        val organism = Organism(backendConfig.instances.keys.first())
        val compressed = compressor.compressSequencesInOriginalData(testData, organism)
        val decompressed = compressor.decompressSequencesInOriginalData(compressed, organism)

        assertEquals(testData, decompressed)
    }
}
