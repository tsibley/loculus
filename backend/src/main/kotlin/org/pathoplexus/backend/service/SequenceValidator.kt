package org.pathoplexus.backend.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import org.pathoplexus.backend.config.ReferenceGenome
import org.pathoplexus.backend.config.ReferenceSequence
import org.pathoplexus.backend.controller.ProcessingValidationException
import org.pathoplexus.backend.model.Metadata
import org.pathoplexus.backend.model.SchemaConfig
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private const val DATE_FORMAT = "yyyy-MM-dd"
private const val PANGO_LINEAGE_REGEX_PATTERN = "[a-zA-Z]{1,3}(\\.\\d{1,3}){0,3}"
private val pangoLineageRegex = Regex(PANGO_LINEAGE_REGEX_PATTERN)

enum class AminoAcidSymbols {
    A, C, D, E, F, G, H, I, K, L, M, N, P, Q, R, S, T, V, W, Y, B, Z, X,

    @JsonProperty("-")
    GAP,

    @JsonProperty("*")
    STOP,
}

enum class NucleotideSymbols {
    A, C, G, T, M, R, W, S, Y, K, V, H, D, B, N,

    @JsonProperty("-")
    GAP,
}

@Component
class SequenceValidator(
    private val schemaConfig: SchemaConfig,
    private val referenceGenome: ReferenceGenome,
) {
    fun validateSequence(submittedProcessedData: SubmittedProcessedData) {
        validateMetadata(submittedProcessedData)
        validateNucleotideSequences(submittedProcessedData)
        validateAminoAcidSequences(submittedProcessedData)
    }

    private fun validateMetadata(
        submittedProcessedData: SubmittedProcessedData,
    ) {
        val metadataFields = schemaConfig.schema.metadata
        validateNoUnknownInMetaData(submittedProcessedData.data.metadata, metadataFields.map { it.name })

        for (metadata in metadataFields) {
            validateKnownMetadataField(metadata, submittedProcessedData)
        }
    }

    private fun <T> validateNoUnknownInMetaData(
        data: Map<String, T>,
        known: List<String>,
    ) {
        val unknowns = data.keys.subtract(known.toSet())
        if (unknowns.isNotEmpty()) {
            throw ProcessingValidationException("Unknown fields in processed data: ${unknowns.joinToString(", ")}.")
        }
    }

    private fun validateKnownMetadataField(
        metadata: Metadata,
        submittedProcessedData: SubmittedProcessedData,
    ) {
        val fieldName = metadata.name
        val fieldValue = submittedProcessedData.data.metadata[fieldName]

        if (metadata.required) {
            if (fieldValue == null) {
                throw ProcessingValidationException("Missing the required field '$fieldName'.")
            }

            if (fieldValue is NullNode) {
                throw ProcessingValidationException("Field '$fieldName' is null, but a value is required.")
            }
        }

        if (fieldValue != null) {
            validateType(fieldValue, metadata)
        }
    }

    fun validateType(fieldValue: JsonNode, metadata: Metadata) {
        if (fieldValue.isNull) {
            return
        }

        when (metadata.type) {
            "date" -> {
                if (!isValidDate(fieldValue.asText())) {
                    throw ProcessingValidationException(
                        "Expected type 'date' in format '$DATE_FORMAT' for field '${metadata.name}', " +
                            "found value '$fieldValue'.",
                    )
                }
                return
            }

            "pango_lineage" -> {
                if (!isValidPangoLineage(fieldValue.asText())) {
                    throw ProcessingValidationException(
                        "Expected type 'pango_lineage' for field '${metadata.name}', " +
                            "found value '$fieldValue'. " +
                            "A pango lineage must be of the form $PANGO_LINEAGE_REGEX_PATTERN, e.g. 'XBB' or 'BA.1.5'.",
                    )
                }
                return
            }
        }

        val isOfCorrectPrimitiveType = when (metadata.type) {
            "string" -> fieldValue.isTextual
            "integer" -> fieldValue.isInt
            "float" -> fieldValue.isFloat
            "double" -> fieldValue.isDouble
            "number" -> fieldValue.isNumber
            else -> false
        }

        if (!isOfCorrectPrimitiveType) {
            throw ProcessingValidationException(
                "Expected type '${metadata.type}' for field '${metadata.name}', " +
                    "found value '$fieldValue'.",
            )
        }
    }

    fun isValidDate(dateStringCandidate: String): Boolean {
        val formatter = DateTimeFormatter.ofPattern(DATE_FORMAT)
        return try {
            LocalDate.parse(dateStringCandidate, formatter)
            true
        } catch (e: DateTimeParseException) {
            false
        }
    }

    fun isValidPangoLineage(pangoLineageCandidate: String): Boolean {
        return pangoLineageCandidate.matches(pangoLineageRegex)
    }

    private fun validateNucleotideSequences(
        submittedProcessedData: SubmittedProcessedData,
    ) {
        for (segment in referenceGenome.nucleotideSequences) {
            validateNoMissingSegment(
                segment,
                submittedProcessedData.data.alignedNucleotideSequences,
                "alignedNucleotideSequences",
            )
            validateLengthOfSequence(
                segment,
                submittedProcessedData.data.alignedNucleotideSequences,
                "alignedNucleotideSequences",
            )

            validateNoMissingSegment(
                segment,
                submittedProcessedData.data.unalignedNucleotideSequences,
                "unalignedNucleotideSequences",
            )
        }

        validateNoUnknownSegment(
            submittedProcessedData.data.alignedNucleotideSequences,
            "alignedNucleotideSequences",
        )

        validateNoUnknownSegment(
            submittedProcessedData.data.unalignedNucleotideSequences,
            "unalignedNucleotideSequences",
        )

        validateNoUnknownSegment(
            submittedProcessedData.data.nucleotideInsertions,
            "nucleotideInsertions",
        )

        validateNoUnknownNucleotideSymbol(
            submittedProcessedData.data.alignedNucleotideSequences,
            "alignedNucleotideSequences",
        )

        validateNoUnknownNucleotideSymbol(
            submittedProcessedData.data.unalignedNucleotideSequences,
            "unalignedNucleotideSequences",
        )

        validateNoUnknownNucleotideSymbolInInsertion(
            submittedProcessedData.data.nucleotideInsertions,
        )
    }

    private fun <T> validateNoMissingSegment(
        segment: ReferenceSequence,
        sequenceData: Map<String, T>,
        sequence: String,
    ) {
        if (!sequenceData.containsKey(segment.name)) {
            throw ProcessingValidationException("Missing the required segment '${segment.name}' in '$sequence'.")
        }
    }

    private fun validateLengthOfSequence(
        referenceSequence: ReferenceSequence,
        sequenceData: Map<String, String>,
        sequenceGrouping: String,
    ) {
        val sequence = sequenceData[referenceSequence.name]!!
        if (sequence.length != referenceSequence.sequence.length) {
            throw ProcessingValidationException(
                "The length of '${referenceSequence.name}' in '$sequenceGrouping' is ${sequence.length}, " +
                    "but it should be ${referenceSequence.sequence.length}.",
            )
        }
    }

    private fun <T> validateNoUnknownSegment(
        dataToValidate: Map<String, T>,
        sequenceGrouping: String,
    ) {
        val unknowns = dataToValidate.keys.subtract(referenceGenome.nucleotideSequences.map { it.name }.toSet())
        if (unknowns.isNotEmpty()) {
            throw ProcessingValidationException(
                "Unknown segments in '$sequenceGrouping': ${unknowns.joinToString(", ")}.",
            )
        }
    }

    private fun validateNoUnknownNucleotideSymbol(
        dataToValidate: Map<String, String>,
        sequenceGrouping: String,
    ) {
        for (sequence in dataToValidate) {
            val invalidSymbols = sequence.value.getInvalidSymbols<NucleotideSymbols>()
            if (invalidSymbols.isNotEmpty()) {
                throw ProcessingValidationException(
                    "The sequence of segment '${sequence.key}' in '$sequenceGrouping' " +
                        "contains invalid symbols: $invalidSymbols.",
                )
            }
        }
    }

    private fun validateNoUnknownNucleotideSymbolInInsertion(
        dataToValidate: Map<String, List<Insertion>>,
    ) {
        for (sequence in dataToValidate) {
            for (insertion in sequence.value) {
                val invalidSymbols = insertion.sequence.getInvalidSymbols<NucleotideSymbols>()
                if (invalidSymbols.isNotEmpty()) {
                    throw ProcessingValidationException(
                        "The insertion $insertion of segment '${sequence.key}' in 'nucleotideInsertions' " +
                            "contains invalid symbols: $invalidSymbols.",
                    )
                }
            }
        }
    }

    private inline fun <reified ValidSymbols : Enum<ValidSymbols>> String.getInvalidSymbols() =
        this.filter { !it.isValidSymbol<ValidSymbols>() }.toList()

    private inline fun <reified ValidSymbols : Enum<ValidSymbols>> Char.isValidSymbol() =
        enumValues<ValidSymbols>().any { it.name == this.toString() }

    private fun validateAminoAcidSequences(
        submittedProcessedData: SubmittedProcessedData,
    ) {
        for (gene in referenceGenome.genes) {
            validateNoMissingGene(gene, submittedProcessedData)
            validateLengthOfSequence(
                gene,
                submittedProcessedData.data.alignedAminoAcidSequences,
                "alignedAminoAcidSequences",
            )
        }

        validateNoUnknownGeneInData(
            submittedProcessedData.data.alignedAminoAcidSequences,
            "alignedAminoAcidSequences",
        )

        validateNoUnknownGeneInData(
            submittedProcessedData.data.aminoAcidInsertions,
            "aminoAcidInsertions",
        )

        validateNoUnknownAminoAcidSymbol(submittedProcessedData.data.alignedAminoAcidSequences)
        validateNoUnknownAminoAcidSymbolInInsertion(submittedProcessedData.data.aminoAcidInsertions)
    }

    private fun validateNoMissingGene(
        gene: ReferenceSequence,
        submittedProcessedData: SubmittedProcessedData,
    ) {
        if (!submittedProcessedData.data.alignedAminoAcidSequences.containsKey(gene.name)) {
            throw ProcessingValidationException("Missing the required gene '${gene.name}'.")
        }
    }

    private fun <T> validateNoUnknownGeneInData(
        data: Map<String, T>,
        geneGrouping: String,
    ) {
        val unknowns = data.keys.subtract(referenceGenome.genes.map { it.name }.toSet())
        if (unknowns.isNotEmpty()) {
            throw ProcessingValidationException("Unknown genes in '$geneGrouping': ${unknowns.joinToString(", ")}.")
        }
    }

    private fun validateNoUnknownAminoAcidSymbol(
        dataToValidate: Map<String, String>,
    ) {
        for (sequence in dataToValidate) {
            val invalidSymbols = sequence.value.getInvalidSymbols<AminoAcidSymbols>()
            if (invalidSymbols.isNotEmpty()) {
                throw ProcessingValidationException(
                    "The gene '${sequence.key}' in 'alignedAminoAcidSequences' " +
                        "contains invalid symbols: $invalidSymbols.",
                )
            }
        }
    }

    private fun validateNoUnknownAminoAcidSymbolInInsertion(
        dataToValidate: Map<String, List<Insertion>>,
    ) {
        for (sequence in dataToValidate) {
            for (insertion in sequence.value) {
                val invalidSymbols = insertion.sequence.getInvalidSymbols<AminoAcidSymbols>()
                if (invalidSymbols.isNotEmpty()) {
                    throw ProcessingValidationException(
                        "An insertion of gene '${sequence.key}' in 'aminoAcidInsertions' " +
                            "contains invalid symbols: $invalidSymbols.",
                    )
                }
            }
        }
    }
}
