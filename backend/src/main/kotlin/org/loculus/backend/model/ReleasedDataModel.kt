package org.loculus.backend.model

import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.LongNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TextNode
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mu.KotlinLogging
import org.loculus.backend.api.DataUseTerms
import org.loculus.backend.api.GeneticSequence
import org.loculus.backend.api.Organism
import org.loculus.backend.api.ProcessedData
import org.loculus.backend.api.VersionStatus
import org.loculus.backend.config.BackendConfig
import org.loculus.backend.service.submission.RawProcessedData
import org.loculus.backend.service.submission.SubmissionDatabaseService
import org.loculus.backend.service.submission.UpdateTrackerTable
import org.loculus.backend.utils.Accession
import org.loculus.backend.utils.Version
import org.loculus.backend.utils.toTimestamp
import org.loculus.backend.utils.toUtcDateString
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger { }

val RELEASED_DATA_RELATED_TABLES: List<String> =
    listOf(
        "sequence_entries",
        "sequence_entries_preprocessed_data",
        "external_metadata",
        "current_processing_pipeline",
        "metadata_upload_aux_table",
        "sequence_upload_aux_table",
    )

@Service
open class ReleasedDataModel(
    private val submissionDatabaseService: SubmissionDatabaseService,
    private val backendConfig: BackendConfig,
) {
    @Transactional(readOnly = true)
    open fun getReleasedData(organism: Organism): Sequence<ProcessedData<GeneticSequence>> {
        log.info { "Fetching released submissions from database for organism $organism" }

        val latestVersions = submissionDatabaseService.getLatestVersions(organism)
        val latestRevocationVersions = submissionDatabaseService.getLatestRevocationVersions(organism)

        return submissionDatabaseService.streamReleasedSubmissions(organism)
            .map { computeAdditionalMetadataFields(it, latestVersions, latestRevocationVersions) }
    }

    @Transactional(readOnly = true)
    open fun getLastDatabaseWriteETag(tableNames: List<String>? = null): String {
        val query = UpdateTrackerTable.select(UpdateTrackerTable.lastTimeUpdatedDbColumn).apply {
            tableNames?.let {
                where { UpdateTrackerTable.tableNameColumn inList it }
            }
        }

        val lastUpdateTime = query
            .mapNotNull { it[UpdateTrackerTable.lastTimeUpdatedDbColumn] }
            .maxOrNull()
            // Replace not strictly necessary but does no harm and a) shows UTC, b) simplifies silo import script logic
            ?.replace(" ", "Z")
            ?: ""
        return "\"$lastUpdateTime\"" // ETag must be enclosed in double quotes
    }

    private fun computeAdditionalMetadataFields(
        rawProcessedData: RawProcessedData,
        latestVersions: Map<Accession, Version>,
        latestRevocationVersions: Map<Accession, Version>,
    ): ProcessedData<GeneticSequence> {
        val versionStatus = computeVersionStatus(rawProcessedData, latestVersions, latestRevocationVersions)

        val currentDataUseTerms = computeDataUseTerm(rawProcessedData)
        val restrictedDataUseTermsUntil = if (currentDataUseTerms is DataUseTerms.Restricted) {
            TextNode(currentDataUseTerms.restrictedUntil.toString())
        } else {
            NullNode.getInstance()
        }

        var metadata = rawProcessedData.processedData.metadata +
            ("accession" to TextNode(rawProcessedData.accession)) +
            ("version" to LongNode(rawProcessedData.version)) +
            (HEADER_TO_CONNECT_METADATA_AND_SEQUENCES to TextNode(rawProcessedData.submissionId)) +
            ("accessionVersion" to TextNode(rawProcessedData.displayAccessionVersion())) +
            ("isRevocation" to BooleanNode.valueOf(rawProcessedData.isRevocation)) +
            ("submitter" to TextNode(rawProcessedData.submitter)) +
            ("groupId" to IntNode(rawProcessedData.groupId)) +
            ("groupName" to TextNode(rawProcessedData.groupName)) +
            ("submittedDate" to TextNode(rawProcessedData.submittedAtTimestamp.toUtcDateString())) +
            ("submittedAtTimestamp" to LongNode(rawProcessedData.submittedAtTimestamp.toTimestamp())) +
            ("releasedAtTimestamp" to LongNode(rawProcessedData.releasedAtTimestamp.toTimestamp())) +
            ("releasedDate" to TextNode(rawProcessedData.releasedAtTimestamp.toUtcDateString())) +
            ("versionStatus" to TextNode(versionStatus.name)) +
            ("dataUseTerms" to TextNode(currentDataUseTerms.type.name)) +
            ("dataUseTermsRestrictedUntil" to restrictedDataUseTermsUntil) +
            ("versionComment" to TextNode(rawProcessedData.versionComment))

        if (backendConfig.dataUseTermsUrls != null) {
            val url = if (rawProcessedData.dataUseTerms == DataUseTerms.Open) {
                backendConfig.dataUseTermsUrls.open
            } else {
                backendConfig.dataUseTermsUrls.restricted
            }
            metadata += ("dataUseTermsUrl" to TextNode(url))
        }

        return ProcessedData(
            metadata = metadata,
            unalignedNucleotideSequences = rawProcessedData.processedData.unalignedNucleotideSequences,
            alignedNucleotideSequences = rawProcessedData.processedData.alignedNucleotideSequences,
            nucleotideInsertions = rawProcessedData.processedData.nucleotideInsertions,
            aminoAcidInsertions = rawProcessedData.processedData.aminoAcidInsertions,
            alignedAminoAcidSequences = rawProcessedData.processedData.alignedAminoAcidSequences,
        )
    }

    private fun computeDataUseTerm(rawProcessedData: RawProcessedData): DataUseTerms = if (
        rawProcessedData.dataUseTerms is DataUseTerms.Restricted &&
        rawProcessedData.dataUseTerms.restrictedUntil > Clock.System.now().toLocalDateTime(TimeZone.UTC).date
    ) {
        DataUseTerms.Restricted(rawProcessedData.dataUseTerms.restrictedUntil)
    } else {
        DataUseTerms.Open
    }

    // LATEST_VERSION: This is the highest version of the sequence entry
    // REVOKED: This is not the highest version of the sequence entry, and a higher version is a revocation
    // REVISED: This is not the highest version of the sequence entry, and no higher version is a revocation
    // Note: a revocation entry is only REVOKED when there's a higher version that is a revocation
    private fun computeVersionStatus(
        rawProcessedData: RawProcessedData,
        latestVersions: Map<Accession, Version>,
        latestRevocationVersions: Map<Accession, Version>,
    ): VersionStatus {
        val isLatestVersion = (latestVersions[rawProcessedData.accession] == rawProcessedData.version)
        if (isLatestVersion) {
            return VersionStatus.LATEST_VERSION
        }

        latestRevocationVersions[rawProcessedData.accession]?.let {
            if (it > rawProcessedData.version) {
                return VersionStatus.REVOKED
            }
        }

        return VersionStatus.REVISED
    }
}
