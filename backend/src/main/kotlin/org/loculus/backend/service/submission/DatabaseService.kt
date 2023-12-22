package org.loculus.backend.service.submission

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mu.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.booleanParam
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.dateTimeParam
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.stringParam
import org.jetbrains.exposed.sql.update
import org.loculus.backend.api.AccessionVersion
import org.loculus.backend.api.AccessionVersionInterface
import org.loculus.backend.api.Organism
import org.loculus.backend.api.ProcessedData
import org.loculus.backend.api.SequenceEntryStatus
import org.loculus.backend.api.SequenceEntryVersionToEdit
import org.loculus.backend.api.Status
import org.loculus.backend.api.Status.APPROVED_FOR_RELEASE
import org.loculus.backend.api.Status.AWAITING_APPROVAL
import org.loculus.backend.api.Status.AWAITING_APPROVAL_FOR_REVOCATION
import org.loculus.backend.api.Status.HAS_ERRORS
import org.loculus.backend.api.Status.IN_PROCESSING
import org.loculus.backend.api.Status.RECEIVED
import org.loculus.backend.api.SubmittedProcessedData
import org.loculus.backend.api.UnprocessedData
import org.loculus.backend.config.ReferenceGenome
import org.loculus.backend.controller.BadRequestException
import org.loculus.backend.controller.ProcessingValidationException
import org.loculus.backend.controller.UnprocessableEntityException
import org.loculus.backend.service.groupmanagement.GroupManagementPreconditionValidator
import org.loculus.backend.utils.Accession
import org.loculus.backend.utils.Version
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import javax.sql.DataSource

private val log = KotlinLogging.logger { }

@Service
@Transactional
class DatabaseService(
    private val sequenceValidatorFactory: SequenceValidatorFactory,
    private val submissionPreconditionValidator: SubmissionPreconditionValidator,
    private val groupManagementPreconditionValidator: GroupManagementPreconditionValidator,
    private val objectMapper: ObjectMapper,
    pool: DataSource,
    private val referenceGenome: ReferenceGenome,
    private val sequenceEntriesTableProvider: SequenceEntriesTableProvider,
) {

    init {
        Database.connect(pool)
    }

    fun streamUnprocessedSubmissions(numberOfSequenceEntries: Int, organism: Organism): Sequence<UnprocessedData> {
        log.info { "streaming unprocessed submissions. Requested $numberOfSequenceEntries sequence entries." }

        sequenceEntriesTableProvider.get(organism).let { table ->
            val sequenceEntryData = table
                .slice(table.accessionColumn, table.versionColumn, table.originalDataColumn)
                .select(
                    where = { table.statusIs(RECEIVED) and table.isMaxVersion and table.organismIs(organism) },
                )
                .limit(numberOfSequenceEntries)
                .map {
                    UnprocessedData(
                        it[table.accessionColumn],
                        it[table.versionColumn],
                        it[table.originalDataColumn]!!,
                    )
                }

            log.info {
                "streaming ${sequenceEntryData.size} of $numberOfSequenceEntries requested unprocessed submissions"
            }

            updateStatusToProcessing(sequenceEntryData, table)

            return sequenceEntryData.asSequence()
        }
    }

    private fun updateStatusToProcessing(sequenceEntries: List<UnprocessedData>, table: SequenceEntriesDataTable) {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        table.update(
            where = { table.accessionVersionIsIn(sequenceEntries) },
        ) {
            it[statusColumn] = IN_PROCESSING.name
            it[startedProcessingAtColumn] = now
        }
    }

    fun updateProcessedData(inputStream: InputStream, organism: Organism) {
        log.info { "updating processed data" }
        val reader = BufferedReader(InputStreamReader(inputStream))

        reader.lineSequence().forEach { line ->
            val submittedProcessedData = try {
                objectMapper.readValue<SubmittedProcessedData>(line)
            } catch (e: JacksonException) {
                throw BadRequestException("Failed to deserialize NDJSON line: ${e.message}", e)
            }

            val numInserted = insertProcessedDataWithStatus(submittedProcessedData, organism)
            if (numInserted != 1) {
                throwInsertFailedException(submittedProcessedData, organism)
            }
        }
    }

    private fun insertProcessedDataWithStatus(
        submittedProcessedData: SubmittedProcessedData,
        organism: Organism,
    ): Int {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        val submittedErrors = submittedProcessedData.errors.orEmpty()

        if (submittedErrors.isEmpty()) {
            try {
                sequenceValidatorFactory.create(organism).validateSequence(submittedProcessedData)
            } catch (validationException: ProcessingValidationException) {
                throwIfIsSubmissionForWrongOrganism(submittedProcessedData, organism)
                throw validationException
            }
        }

        val submittedWarnings = submittedProcessedData.warnings.orEmpty()
        val submittedProcessedDataWithAllKeysForInsertions = addMissingKeysForInsertions(submittedProcessedData)

        val newStatus = when {
            submittedErrors.isEmpty() -> AWAITING_APPROVAL
            else -> HAS_ERRORS
        }

        return sequenceEntriesTableProvider.get(organism).let { table ->
            table.update(
                where = {
                    table.accessionVersionEquals(submittedProcessedDataWithAllKeysForInsertions) and
                        table.statusIs(IN_PROCESSING) and
                        table.organismIs(organism)
                },
            ) {
                it[statusColumn] = newStatus.name
                it[processedDataColumn] = submittedProcessedDataWithAllKeysForInsertions.data
                it[errorsColumn] = submittedErrors
                it[warningsColumn] = submittedWarnings
                it[finishedProcessingAtColumn] = now
            }
        }
    }

    private fun throwIfIsSubmissionForWrongOrganism(
        submittedProcessedData: SubmittedProcessedData,
        organism: Organism,
    ) {
        sequenceEntriesTableProvider.get(organism).let { table ->
            val resultRow = table.slice(table.organismColumn)
                .select(where = { table.accessionVersionEquals(submittedProcessedData) })
                .firstOrNull() ?: return

            if (resultRow[table.organismColumn] != organism.name) {
                throw UnprocessableEntityException(
                    "Accession version ${submittedProcessedData.displayAccessionVersion()} is for organism " +
                        "${resultRow[table.organismColumn]}, but submitted data is for organism ${organism.name}",
                )
            }
        }
    }

    private fun addMissingKeysForInsertions(submittedProcessedData: SubmittedProcessedData): SubmittedProcessedData {
        val nucleotideInsertions = referenceGenome.nucleotideSequences.associate {
            if (it.name in submittedProcessedData.data.nucleotideInsertions.keys) {
                it.name to submittedProcessedData.data.nucleotideInsertions[it.name]!!
            } else {
                (it.name to emptyList())
            }
        }

        val aminoAcidInsertions = referenceGenome.genes.associate {
            if (it.name in submittedProcessedData.data.aminoAcidInsertions.keys) {
                it.name to submittedProcessedData.data.aminoAcidInsertions[it.name]!!
            } else {
                (it.name to emptyList())
            }
        }

        return submittedProcessedData.copy(
            data = submittedProcessedData.data.copy(
                nucleotideInsertions = nucleotideInsertions,
                aminoAcidInsertions = aminoAcidInsertions,
            ),
        )
    }

    private fun throwInsertFailedException(submittedProcessedData: SubmittedProcessedData, organism: Organism): String {
        sequenceEntriesTableProvider.get(organism).let { table ->
            val selectedSequenceEntries = table
                .slice(
                    table.accessionColumn,
                    table.versionColumn,
                    table.statusColumn,
                )
                .select(where = { table.accessionVersionEquals(submittedProcessedData) })

            val accessionVersion = submittedProcessedData.displayAccessionVersion()
            if (selectedSequenceEntries.count() == 0L) {
                throw UnprocessableEntityException("Accession version $accessionVersion does not exist")
            }

            val selectedSequence = selectedSequenceEntries.first()
            if (selectedSequence[table.statusColumn] != IN_PROCESSING.name) {
                throw UnprocessableEntityException(
                    "Accession version $accessionVersion is in not in state $IN_PROCESSING " +
                        "(was ${selectedSequence[table.statusColumn]})",
                )
            }

            throw RuntimeException("Update processed data: Unexpected error for accession versions $accessionVersion")
        }
    }

    fun approveProcessedData(submitter: String, accessionVersions: List<AccessionVersion>, organism: Organism) {
        log.info { "approving ${accessionVersions.size} sequences by $submitter" }

        submissionPreconditionValidator.validateAccessionVersions(
            submitter,
            accessionVersions,
            listOf(AWAITING_APPROVAL),
            organism,
        )

        sequenceEntriesTableProvider.get(organism).let { table ->
            table.update(
                where = {
                    table.accessionVersionIsIn(accessionVersions) and table.statusIs(AWAITING_APPROVAL)
                },
            ) {
                it[statusColumn] = APPROVED_FOR_RELEASE.name
            }
        }
    }

    fun getLatestVersions(organism: Organism): Map<Accession, Version> {
        sequenceEntriesTableProvider.get(organism).let { table ->
            val maxVersionExpression = table.versionColumn.max()
            return table
                .slice(table.accessionColumn, maxVersionExpression)
                .select(
                    where = { table.statusIs(APPROVED_FOR_RELEASE) and table.organismIs(organism) },
                )
                .groupBy(table.accessionColumn)
                .associate { it[table.accessionColumn] to it[maxVersionExpression]!! }
        }
    }

    fun getLatestRevocationVersions(organism: Organism): Map<Accession, Version> {
        sequenceEntriesTableProvider.get(organism).let { table ->
            val maxVersionExpression = table.versionColumn.max()
            return table
                .slice(table.accessionColumn, maxVersionExpression)
                .select(
                    where = {
                        table.statusIs(APPROVED_FOR_RELEASE) and
                            (table.isRevocationColumn eq true) and
                            table.organismIs(organism)
                    },
                )
                .groupBy(table.accessionColumn)
                .associate { it[table.accessionColumn] to it[maxVersionExpression]!! }
        }
    }

    fun streamReleasedSubmissions(organism: Organism): Sequence<RawProcessedData> {
        return sequenceEntriesTableProvider.get(organism).let { table ->
            table.slice(
                table.accessionColumn,
                table.versionColumn,
                table.isRevocationColumn,
                table.processedDataColumn,
                table.submitterColumn,
                table.submittedAtColumn,
                table.submissionIdColumn,
            )
                .select(
                    where = { table.statusIs(APPROVED_FOR_RELEASE) and table.organismIs(organism) },
                )
                // TODO(#429): This needs clarification of how to handle revocations. Until then, revocations are filtered out.
                .filter { !it[table.isRevocationColumn] }
                .map {
                    RawProcessedData(
                        accession = it[table.accessionColumn],
                        version = it[table.versionColumn],
                        isRevocation = it[table.isRevocationColumn],
                        submitter = it[table.submitterColumn],
                        submissionId = it[table.submissionIdColumn],
                        processedData = it[table.processedDataColumn]!!,
                        submittedAt = it[table.submittedAtColumn],
                    )
                }
                .asSequence()
        }
    }

    fun streamDataToEdit(
        submitter: String,
        groupName: String,
        numberOfSequenceEntries: Int,
        organism: Organism,
    ): Sequence<SequenceEntryVersionToEdit> {
        log.info { "streaming $numberOfSequenceEntries submissions that need edit by $submitter" }

        groupManagementPreconditionValidator.validateUserInExistingGroupAndReturnUserList(groupName, submitter)

        sequenceEntriesTableProvider.get(organism).let { table ->
            return table.slice(
                table.accessionColumn,
                table.versionColumn,
                table.statusColumn,
                table.processedDataColumn,
                table.originalDataColumn,
                table.errorsColumn,
                table.warningsColumn,
            )
                .select(
                    where = {
                        table.statusIs(HAS_ERRORS) and
                            table.isMaxVersion and
                            table.groupIs(groupName) and
                            table.organismIs(organism)
                    },
                )
                .limit(numberOfSequenceEntries)
                .map { row ->
                    SequenceEntryVersionToEdit(
                        row[table.accessionColumn],
                        row[table.versionColumn],
                        Status.fromString(row[table.statusColumn]),
                        row[table.processedDataColumn]!!,
                        row[table.originalDataColumn]!!,
                        row[table.errorsColumn],
                        row[table.warningsColumn],
                    )
                }
                .asSequence()
        }
    }

    fun getActiveSequencesSubmittedBy(username: String, organism: Organism): List<SequenceEntryStatus> {
        log.info { "getting active sequence entries submitted by $username" }

        sequenceEntriesTableProvider.get(organism).let { table ->
            val subTableSequenceStatus = table
                .slice(
                    table.accessionColumn,
                    table.versionColumn,
                    table.statusColumn,
                    table.isRevocationColumn,
                    table.organismColumn,
                )

            val releasedSequenceEntries = subTableSequenceStatus
                .select(
                    where = {
                        table.statusIs(APPROVED_FOR_RELEASE) and
                            (table.submitterColumn eq username) and
                            table.isMaxReleasedVersion and
                            table.organismIs(organism)
                    },
                ).map { row ->
                    SequenceEntryStatus(
                        row[table.accessionColumn],
                        row[table.versionColumn],
                        APPROVED_FOR_RELEASE,
                        row[table.isRevocationColumn],
                    )
                }

            val unreleasedSequenceEntries = subTableSequenceStatus.select(
                where = {
                    (table.statusColumn neq APPROVED_FOR_RELEASE.name) and
                        (table.submitterColumn eq username) and
                        table.isMaxVersion and
                        table.organismIs(organism)
                },
            ).map { row ->
                SequenceEntryStatus(
                    row[table.accessionColumn],
                    row[table.versionColumn],
                    Status.fromString(row[table.statusColumn]),
                    row[table.isRevocationColumn],
                )
            }

            return releasedSequenceEntries + unreleasedSequenceEntries
        }
    }

    fun revoke(accessions: List<Accession>, username: String, organism: Organism): List<SequenceEntryStatus> {
        log.info { "revoking ${accessions.size} sequences" }

        submissionPreconditionValidator.validateAccessions(
            username,
            accessions,
            listOf(APPROVED_FOR_RELEASE),
            organism,
        )

        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        sequenceEntriesTableProvider.get(organism).let { table ->
            table.insert(
                table.slice(
                    table.accessionColumn,
                    table.versionColumn.plus(1),
                    table.submissionIdColumn,
                    table.submitterColumn,
                    table.groupNameColumn,
                    dateTimeParam(now),
                    stringParam(AWAITING_APPROVAL_FOR_REVOCATION.name),
                    booleanParam(true),
                    table.organismColumn,
                ).select(
                    where = {
                        (table.accessionColumn inList accessions) and
                            table.isMaxVersion
                    },
                ),
                columns = listOf(
                    table.accessionColumn,
                    table.versionColumn,
                    table.submissionIdColumn,
                    table.submitterColumn,
                    table.groupNameColumn,
                    table.submittedAtColumn,
                    table.statusColumn,
                    table.isRevocationColumn,
                    table.organismColumn,
                ),
            )

            return table
                .slice(
                    table.accessionColumn,
                    table.versionColumn,
                    table.isRevocationColumn,
                )
                .select(
                    where = {
                        (table.accessionColumn inList accessions) and
                            table.isMaxVersion and
                            table.statusIs(AWAITING_APPROVAL_FOR_REVOCATION)
                    },
                ).map {
                    SequenceEntryStatus(
                        it[table.accessionColumn],
                        it[table.versionColumn],
                        AWAITING_APPROVAL_FOR_REVOCATION,
                        it[table.isRevocationColumn],
                    )
                }.sortedBy { it.accession }
        }
    }

    fun confirmRevocation(accessionVersions: List<AccessionVersion>, username: String, organism: Organism) {
        log.info { "Confirming revocation for ${accessionVersions.size} sequence entries" }

        submissionPreconditionValidator.validateAccessionVersions(
            username,
            accessionVersions,
            listOf(AWAITING_APPROVAL_FOR_REVOCATION),
            organism,
        )

        sequenceEntriesTableProvider.get(organism).let { table ->
            table.update(
                where = {
                    table.accessionVersionIsIn(accessionVersions) and table.statusIs(
                        AWAITING_APPROVAL_FOR_REVOCATION,
                    )
                },
            ) {
                it[statusColumn] = APPROVED_FOR_RELEASE.name
            }
        }
    }

    fun deleteSequenceEntryVersions(accessionVersions: List<AccessionVersion>, submitter: String, organism: Organism) {
        log.info { "Deleting accession versions: $accessionVersions" }

        submissionPreconditionValidator.validateAccessionVersions(
            submitter,
            accessionVersions,
            listOf(RECEIVED, AWAITING_APPROVAL, HAS_ERRORS, AWAITING_APPROVAL_FOR_REVOCATION),
            organism,
        )

        sequenceEntriesTableProvider.get(organism).deleteWhere {
            accessionVersionIsIn(accessionVersions)
        }
    }

    fun submitEditedData(submitter: String, editedAccessionVersion: UnprocessedData, organism: Organism) {
        log.info { "edited sequence entry submitted $editedAccessionVersion" }

        submissionPreconditionValidator.validateAccessionVersions(
            submitter,
            listOf(editedAccessionVersion),
            listOf(AWAITING_APPROVAL, HAS_ERRORS),
            organism,
        )

        sequenceEntriesTableProvider.get(organism).let { table ->
            table.update(
                where = {
                    table.accessionVersionEquals(editedAccessionVersion)
                },
            ) {
                it[statusColumn] = RECEIVED.name
                it[originalDataColumn] = editedAccessionVersion.data
                it[errorsColumn] = null
                it[warningsColumn] = null
                it[startedProcessingAtColumn] = null
                it[finishedProcessingAtColumn] = null
                it[processedDataColumn] = null
            }
        }
    }

    fun getSequenceEntryVersionToEdit(
        submitter: String,
        accessionVersion: AccessionVersion,
        organism: Organism,
    ): SequenceEntryVersionToEdit {
        log.info {
            "Getting sequence entry ${accessionVersion.displayAccessionVersion()} by $submitter to edit"
        }

        submissionPreconditionValidator.validateAccessionVersions(
            submitter,
            listOf(accessionVersion),
            listOf(HAS_ERRORS, AWAITING_APPROVAL),
            organism,
        )

        sequenceEntriesTableProvider.get(organism).let { table ->
            val selectedSequenceEntries = table.slice(
                table.accessionColumn,
                table.versionColumn,
                table.statusColumn,
                table.processedDataColumn,
                table.originalDataColumn,
                table.errorsColumn,
                table.warningsColumn,
            )
                .select(
                    where = {
                        table.accessionVersionEquals(accessionVersion)
                    },
                )

            return selectedSequenceEntries.first().let {
                SequenceEntryVersionToEdit(
                    it[table.accessionColumn],
                    it[table.versionColumn],
                    Status.fromString(it[table.statusColumn]),
                    it[table.processedDataColumn]!!,
                    it[table.originalDataColumn]!!,
                    it[table.errorsColumn],
                    it[table.warningsColumn],
                )
            }
        }
    }
}

data class RawProcessedData(
    override val accession: Accession,
    override val version: Version,
    val isRevocation: Boolean,
    val submitter: String,
    val submittedAt: LocalDateTime,
    val submissionId: String,
    val processedData: ProcessedData,
) : AccessionVersionInterface
