package org.pathoplexus.backend.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.module.kotlin.readValue
import io.swagger.v3.oas.annotations.media.Schema
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mu.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.QueryParameter
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.booleanParam
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.dateTimeParam
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.stringParam
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.wrapAsExpression
import org.pathoplexus.backend.controller.BadRequestException
import org.pathoplexus.backend.controller.ForbiddenException
import org.pathoplexus.backend.controller.NotFoundException
import org.pathoplexus.backend.controller.UnprocessableEntityException
import org.pathoplexus.backend.model.HeaderId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import javax.sql.DataSource

private val log = KotlinLogging.logger { }

@Service
@Transactional
class DatabaseService(
    private val sequenceValidatorService: SequenceValidatorService,
    private val objectMapper: ObjectMapper,
    pool: DataSource,
) {
    init {
        Database.connect(pool)
    }

    fun insertSubmissions(submitter: String, submittedData: List<SubmittedData>): List<HeaderId> {
        log.info { "submitting ${submittedData.size} new sequences by $submitter" }

        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        return submittedData.map { data ->
            val insert = SequencesTable.insert {
                it[SequencesTable.submitter] = submitter
                it[submittedAt] = now
                it[version] = 1
                it[status] = Status.RECEIVED.name
                it[customId] = data.customId
                it[originalData] = data.originalData
            }
            HeaderId(insert[SequencesTable.sequenceId], 1, data.customId)
        }
    }

    fun streamUnprocessedSubmissions(numberOfSequences: Int, outputStream: OutputStream) {
        val maxVersionQuery = maxVersionQuery()

        val sequencesData = SequencesTable
            .slice(SequencesTable.sequenceId, SequencesTable.version, SequencesTable.originalData)
            .select(
                where = {
                    (SequencesTable.status eq Status.RECEIVED.name)
                        .and((SequencesTable.version eq maxVersionQuery))
                },
            )
            .limit(numberOfSequences)
            .map {
                UnprocessedData(
                    it[SequencesTable.sequenceId],
                    it[SequencesTable.version],
                    it[SequencesTable.originalData]!!,
                )
            }

        log.info { "streaming ${sequencesData.size} of $numberOfSequences requested unprocessed submissions" }

        updateStatusToProcessing(sequencesData)

        stream(sequencesData, outputStream)
    }

    private fun maxVersionQuery(): Expression<Long?> {
        val subQueryTable = SequencesTable.alias("subQueryTable")
        return wrapAsExpression(
            subQueryTable
                .slice(subQueryTable[SequencesTable.version].max())
                .select { subQueryTable[SequencesTable.sequenceId] eq SequencesTable.sequenceId },
        )
    }

    private fun updateStatusToProcessing(sequences: List<UnprocessedData>) {
        val sequenceVersions = sequences.map { it.sequenceId to it.version }
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        SequencesTable
            .update(
                where = { Pair(SequencesTable.sequenceId, SequencesTable.version) inList sequenceVersions },
            ) {
                it[status] = Status.PROCESSING.name
                it[startedProcessingAt] = now
            }
    }

    private fun <T> stream(
        sequencesData: List<T>,
        outputStream: OutputStream,
    ) {
        sequencesData
            .forEach { sequence ->
                val json = objectMapper.writeValueAsString(sequence)
                outputStream.write(json.toByteArray())
                outputStream.write('\n'.code)
                outputStream.flush()
            }
    }

    fun updateProcessedData(inputStream: InputStream): List<SequenceValidation> {
        log.info { "updating processed data" }
        val reader = BufferedReader(InputStreamReader(inputStream))

        return reader.lineSequence().map { line ->
            val submittedProcessedData = try {
                objectMapper.readValue<SubmittedProcessedData>(line)
            } catch (e: JacksonException) {
                throw BadRequestException("Failed to deserialize NDJSON line: ${e.message}", e)
            }
            val validationResult = sequenceValidatorService.validateSequence(submittedProcessedData)

            val numInserted = insertProcessedDataWithStatus(submittedProcessedData, validationResult)
            if (numInserted != 1) {
                throwInsertFailedException(submittedProcessedData)
            }

            SequenceValidation(submittedProcessedData.sequenceId, submittedProcessedData.version, validationResult)
        }.toList()
    }

    private fun insertProcessedDataWithStatus(
        submittedProcessedData: SubmittedProcessedData,
        validationResult: ValidationResult,
    ): Int {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        val validationErrors = when (validationResult) {
            is ValidationResult.Error -> validationResult.validationErrors
            is ValidationResult.Ok -> emptyList()
        }.map {
            PreprocessingAnnotation(
                listOf(
                    PreprocessingAnnotationSource(
                        PreprocessingAnnotationSourceType.Metadata,
                        it.fieldName,
                    ),
                ),
                "${it.type}: ${it.message}",
            )
        }
        val computedErrors = validationErrors + submittedProcessedData.errors.orEmpty()

        val newStatus = when {
            computedErrors.isEmpty() -> Status.PROCESSED
            else -> Status.NEEDS_REVIEW
        }

        return SequencesTable.update(
            where = {
                (SequencesTable.sequenceId eq submittedProcessedData.sequenceId) and
                    (SequencesTable.version eq submittedProcessedData.version) and
                    (SequencesTable.status eq Status.PROCESSING.name)
            },
        ) {
            it[status] = newStatus.name
            it[processedData] = submittedProcessedData.data
            it[errors] = computedErrors
            it[warnings] = submittedProcessedData.warnings
            it[finishedProcessingAt] = now
        }
    }

    private fun throwInsertFailedException(submittedProcessedData: SubmittedProcessedData): String {
        val selectedSequences = SequencesTable
            .slice(
                SequencesTable.sequenceId,
                SequencesTable.version,
                SequencesTable.status,
            )
            .select(
                where = {
                    (SequencesTable.sequenceId eq submittedProcessedData.sequenceId) and
                        (SequencesTable.version eq submittedProcessedData.version)
                },
            )

        val sequenceVersion = submittedProcessedData.displaySequenceVersion()
        if (selectedSequences.count() == 0L) {
            throw UnprocessableEntityException("Sequence version $sequenceVersion does not exist")
        }

        val selectedSequence = selectedSequences.first()
        if (selectedSequence[SequencesTable.status] != Status.PROCESSING.name) {
            throw UnprocessableEntityException(
                "Sequence version $sequenceVersion is in not in state ${Status.PROCESSING} " +
                    "(was ${selectedSequence[SequencesTable.status]})",
            )
        }
        throw RuntimeException("Update processed data: Unexpected error for sequence version $sequenceVersion")
    }

    fun approveProcessedData(submitter: String, sequenceVersions: List<SequenceVersion>) {
        log.info { "approving ${sequenceVersions.size} sequences by $submitter" }

        validateApprovalPreconditions(submitter, sequenceVersions)

        SequencesTable.update(
            where = {
                (Pair(SequencesTable.sequenceId, SequencesTable.version) inList sequenceVersions.toPairs()) and
                    (SequencesTable.status eq Status.PROCESSED.name)
            },
        ) {
            it[status] = Status.SILO_READY.name
        }
    }

    private fun validateApprovalPreconditions(submitter: String, sequenceVersions: List<SequenceVersion>) {
        val sequences = SequencesTable
            .slice(SequencesTable.sequenceId, SequencesTable.version, SequencesTable.submitter, SequencesTable.status)
            .select(
                where = {
                    Pair(SequencesTable.sequenceId, SequencesTable.version) inList sequenceVersions.toPairs()
                },
            )

        validateSequenceVersionsExist(sequences, sequenceVersions)
        validateSequencesAreInState(sequences, Status.PROCESSED)
        validateUserIsAllowedToEditSequences(sequences, submitter)
    }

    private fun validateSequenceVersionsExist(sequences: Query, sequenceVersions: List<SequenceVersion>) {
        if (sequences.count() == sequenceVersions.size.toLong()) {
            return
        }

        val sequenceVersionsNotFound = sequenceVersions
            .filter { sequenceVersion ->
                sequences.none {
                    it[SequencesTable.sequenceId] == sequenceVersion.sequenceId &&
                        it[SequencesTable.version] == sequenceVersion.version
                }
            }.joinToString(", ") { it.displaySequenceVersion() }

        throw UnprocessableEntityException("Sequence versions $sequenceVersionsNotFound do not exist")
    }

    private fun validateSequencesAreInState(sequences: Query, status: Status) {
        val sequencesNotProcessed = sequences
            .filter { it[SequencesTable.status] != status.name }
            .map { "${it[SequencesTable.sequenceId]}.${it[SequencesTable.version]} - ${it[SequencesTable.status]}" }

        if (sequencesNotProcessed.isNotEmpty()) {
            throw UnprocessableEntityException(
                "Sequence versions are in not in state $status: " +
                    sequencesNotProcessed.joinToString(", "),
            )
        }
    }

    private fun validateUserIsAllowedToEditSequences(sequences: Query, submitter: String) {
        val sequencesNotSubmittedByUser = sequences.filter { it[SequencesTable.submitter] != submitter }
            .map { SequenceVersion(it[SequencesTable.sequenceId], it[SequencesTable.version]) }

        if (sequencesNotSubmittedByUser.isNotEmpty()) {
            throw ForbiddenException(
                "User '$submitter' does not have right to change the sequence versions " +
                    sequencesNotSubmittedByUser.joinToString(", ") { it.displaySequenceVersion() },
            )
        }
    }

    fun streamProcessedSubmissions(numberOfSequences: Int, outputStream: OutputStream) {
        log.info { "streaming $numberOfSequences processed submissions" }
        val maxVersionQuery = maxVersionQuery()

        val sequencesData = SequencesTable
            .slice(
                SequencesTable.sequenceId,
                SequencesTable.version,
                SequencesTable.processedData,
                SequencesTable.errors,
                SequencesTable.warnings,
            )
            .select(
                where = {
                    (SequencesTable.status eq Status.PROCESSED.name) and
                        (SequencesTable.version eq maxVersionQuery)
                },
            ).limit(numberOfSequences).map { row ->
                SubmittedProcessedData(
                    row[SequencesTable.sequenceId],
                    row[SequencesTable.version],
                    row[SequencesTable.processedData]!!,
                    row[SequencesTable.errors],
                    row[SequencesTable.warnings],
                )
            }

        stream(sequencesData, outputStream)
    }

    fun streamReviewNeededSubmissions(submitter: String, numberOfSequences: Int, outputStream: OutputStream) {
        log.info { "streaming $numberOfSequences submissions that need review by $submitter" }
        val maxVersionQuery = maxVersionQuery()

        val sequencesData = SequencesTable
            .slice(
                SequencesTable.sequenceId,
                SequencesTable.version,
                SequencesTable.status,
                SequencesTable.processedData,
                SequencesTable.originalData,
                SequencesTable.errors,
                SequencesTable.warnings,
            )
            .select(
                where = {
                    (SequencesTable.status eq Status.NEEDS_REVIEW.name) and
                        (SequencesTable.version eq maxVersionQuery) and
                        (SequencesTable.submitter eq submitter)
                },
            ).limit(numberOfSequences).map { row ->
                SequenceReview(
                    row[SequencesTable.sequenceId],
                    row[SequencesTable.version],
                    Status.fromString(row[SequencesTable.status]),
                    row[SequencesTable.processedData]!!,
                    row[SequencesTable.originalData]!!,
                    row[SequencesTable.errors],
                    row[SequencesTable.warnings],
                )
            }

        stream(sequencesData, outputStream)
    }

    fun getActiveSequencesSubmittedBy(username: String): List<SequenceVersionStatus> {
        log.info { "getting active sequences submitted by $username" }

        val subTableSequenceStatus = SequencesTable
            .slice(
                SequencesTable.sequenceId,
                SequencesTable.version,
                SequencesTable.status,
                SequencesTable.isRevocation,
            )

        val maxVersionWithSiloReadyQuery = maxVersionWithSiloReadyQuery()
        val sequencesStatusSiloReady = subTableSequenceStatus
            .select(
                where = {
                    (SequencesTable.status eq Status.SILO_READY.name) and
                        (SequencesTable.submitter eq username) and
                        (SequencesTable.version eq maxVersionWithSiloReadyQuery)
                },
            ).map { row ->
                SequenceVersionStatus(
                    row[SequencesTable.sequenceId],
                    row[SequencesTable.version],
                    Status.SILO_READY,
                    row[SequencesTable.isRevocation],
                )
            }

        val maxVersionQuery = maxVersionQuery()
        val sequencesStatusNotSiloReady = subTableSequenceStatus.select(
            where = {
                (SequencesTable.status neq Status.SILO_READY.name) and
                    (SequencesTable.submitter eq username) and
                    (SequencesTable.version eq maxVersionQuery)
            },
        ).map { row ->
            SequenceVersionStatus(
                row[SequencesTable.sequenceId],
                row[SequencesTable.version],
                Status.fromString(row[SequencesTable.status]),
                row[SequencesTable.isRevocation],
            )
        }

        return sequencesStatusSiloReady + sequencesStatusNotSiloReady
    }

    private fun maxVersionWithSiloReadyQuery(): Expression<Long?> {
        val subQueryTable = SequencesTable.alias("subQueryTable")
        return wrapAsExpression(
            subQueryTable
                .slice(subQueryTable[SequencesTable.version].max())
                .select {
                    (subQueryTable[SequencesTable.sequenceId] eq SequencesTable.sequenceId) and
                        (subQueryTable[SequencesTable.status] eq Status.SILO_READY.name)
                },
        )
    }

    fun deleteUserSequences(username: String) {
        SequencesTable.deleteWhere { submitter eq username }
    }

    fun deleteSequences(sequenceIds: List<Long>) {
        SequencesTable.deleteWhere { sequenceId inList sequenceIds }
    }

    fun reviseData(submitter: String, dataSequence: Sequence<FileData>): List<HeaderId> {
        log.info { "revising sequences" }

        val maxVersionQuery = maxVersionQuery()
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        return dataSequence.map {
            SequencesTable.insert(
                SequencesTable.slice(
                    SequencesTable.sequenceId,
                    SequencesTable.version.plus(1),
                    SequencesTable.customId,
                    SequencesTable.submitter,
                    dateTimeParam(now),
                    stringParam(Status.RECEIVED.name),
                    booleanParam(false),
                    QueryParameter(it.originalData, SequencesTable.originalData.columnType),
                ).select(
                    where = {
                        (SequencesTable.sequenceId eq it.sequenceId) and
                            (SequencesTable.version eq maxVersionQuery) and
                            (SequencesTable.status eq Status.SILO_READY.name) and
                            (SequencesTable.submitter eq submitter)
                    },
                ),
                columns = listOf(
                    SequencesTable.sequenceId,
                    SequencesTable.version,
                    SequencesTable.customId,
                    SequencesTable.submitter,
                    SequencesTable.submittedAt,
                    SequencesTable.status,
                    SequencesTable.isRevocation,
                    SequencesTable.originalData,
                ),
            )

            HeaderId(it.sequenceId, it.sequenceId.toInt(), it.customId)
        }.toList()
    }

    fun revoke(sequenceIds: List<Long>, username: String): List<SequenceVersionStatus> {
        log.info { "revoking ${sequenceIds.size} sequences" }

        validateRevokePreconditions(username, sequenceIds)

        val maxVersionQuery = maxVersionQuery()
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        SequencesTable.insert(
            SequencesTable.slice(
                SequencesTable.sequenceId,
                SequencesTable.version.plus(1),
                SequencesTable.customId,
                SequencesTable.submitter,
                dateTimeParam(now),
                stringParam(Status.REVOKED_STAGING.name),
                booleanParam(true),
            ).select(
                where = {
                    (SequencesTable.sequenceId inList sequenceIds) and
                        (SequencesTable.version eq maxVersionQuery) and
                        (SequencesTable.status eq Status.SILO_READY.name)
                },
            ),
            columns = listOf(
                SequencesTable.sequenceId,
                SequencesTable.version,
                SequencesTable.customId,
                SequencesTable.submitter,
                SequencesTable.submittedAt,
                SequencesTable.status,
                SequencesTable.isRevocation,
            ),
        )

        return SequencesTable
            .slice(
                SequencesTable.sequenceId,
                SequencesTable.version,
                SequencesTable.status,
                SequencesTable.isRevocation,
            )
            .select(
                where = {
                    (SequencesTable.sequenceId inList sequenceIds) and
                        (SequencesTable.version eq maxVersionQuery) and
                        (SequencesTable.status eq Status.REVOKED_STAGING.name)
                },
            ).map {
                SequenceVersionStatus(
                    it[SequencesTable.sequenceId],
                    it[SequencesTable.version],
                    Status.REVOKED_STAGING,
                    it[SequencesTable.isRevocation],
                )
            }
    }

    private fun validateRevokePreconditions(submitter: String, sequenceIds: List<Long>) {
        val sequences = SequencesTable
            .slice(SequencesTable.sequenceId, SequencesTable.version, SequencesTable.submitter, SequencesTable.status)
            .select(
                where = {
                    SequencesTable.sequenceId inList sequenceIds
                },
            )

        validateSequenceIdExist(sequences, sequenceIds)
        validateSequencesAreInState(sequences, Status.SILO_READY)
        validateUserIsAllowedToEditSequences(sequences, submitter)
    }

    private fun validateSequenceIdExist(sequences: Query, sequenceIds: List<Long>) {
        if (sequences.count() == sequenceIds.size.toLong()) {
            return
        }

        val sequenceVersionsNotFound = sequenceIds
            .filter { sequenceId ->
                sequences.none {
                    it[SequencesTable.sequenceId] == sequenceId
                }
            }.joinToString(", ") { it.toString() }

        throw UnprocessableEntityException("SequenceIds $sequenceVersionsNotFound do not exist")
    }

    fun confirmRevocation(sequenceIds: List<Long>): Int {
        val maxVersionQuery = maxVersionQuery()

        return SequencesTable.update(
            where = {
                (SequencesTable.sequenceId inList sequenceIds) and
                    (SequencesTable.version eq maxVersionQuery) and
                    (SequencesTable.status eq Status.REVOKED_STAGING.name)
            },
        ) {
            it[status] = Status.SILO_READY.name
        }
    }

    fun submitReviewedSequence(submitter: String, reviewedSequenceVersion: UnprocessedData) {
        log.info { "reviewed sequence submitted $reviewedSequenceVersion" }

        val sequencesReviewed = SequencesTable.update(
            where = {
                (SequencesTable.sequenceId eq reviewedSequenceVersion.sequenceId) and
                    (SequencesTable.version eq reviewedSequenceVersion.version) and
                    (SequencesTable.submitter eq submitter) and
                    (
                        (SequencesTable.status eq Status.PROCESSED.name) or
                            (SequencesTable.status eq Status.NEEDS_REVIEW.name)
                        )
            },
        ) {
            it[status] = Status.REVIEWED.name
            it[originalData] = reviewedSequenceVersion.data
            it[errors] = null
            it[warnings] = null
            it[startedProcessingAt] = null
            it[finishedProcessingAt] = null
            it[processedData] = null
        }

        if (sequencesReviewed != 1) {
            handleReviewedSubmissionError(reviewedSequenceVersion, submitter)
        }
    }

    private fun handleReviewedSubmissionError(reviewedSequenceVersion: UnprocessedData, submitter: String) {
        val selectedSequences = SequencesTable
            .slice(
                SequencesTable.sequenceId,
                SequencesTable.version,
                SequencesTable.status,
                SequencesTable.submitter,
            )
            .select(
                where = {
                    (SequencesTable.sequenceId eq reviewedSequenceVersion.sequenceId) and
                        (SequencesTable.version eq reviewedSequenceVersion.version)
                },
            )

        val sequenceVersionString = reviewedSequenceVersion.displaySequenceVersion()

        if (selectedSequences.count().toInt() == 0) {
            throw UnprocessableEntityException("Sequence $sequenceVersionString does not exist")
        }

        val hasCorrectStatus = selectedSequences.all {
            (it[SequencesTable.status] == Status.PROCESSED.name) ||
                (it[SequencesTable.status] == Status.NEEDS_REVIEW.name)
        }
        if (!hasCorrectStatus) {
            throw UnprocessableEntityException(
                "Sequence $sequenceVersionString is in status ${selectedSequences.first()[SequencesTable.status]} " +
                    "not in ${Status.PROCESSED} or ${Status.NEEDS_REVIEW}",
            )
        }

        if (selectedSequences.any { it[SequencesTable.submitter] != submitter }) {
            throw ForbiddenException(
                "Sequence $sequenceVersionString is not owned by user $submitter",
            )
        }
        throw Exception("SequenceReview: Unknown error")
    }

    fun getReviewData(submitter: String, sequenceVersion: SequenceVersion): SequenceReview {
        log.info { "Getting Sequence ${sequenceVersion.displaySequenceVersion()} that needs review by $submitter" }

        val selectedSequences = SequencesTable
            .slice(
                SequencesTable.sequenceId,
                SequencesTable.version,
                SequencesTable.processedData,
                SequencesTable.originalData,
                SequencesTable.errors,
                SequencesTable.warnings,
                SequencesTable.status,
            )
            .select(
                where = {
                    (
                        (SequencesTable.status eq Status.NEEDS_REVIEW.name)
                            or (SequencesTable.status eq Status.PROCESSED.name)
                        ) and
                        (SequencesTable.sequenceId eq sequenceVersion.sequenceId) and
                        (SequencesTable.version eq sequenceVersion.version) and
                        (SequencesTable.submitter eq submitter)
                },
            )

        if (selectedSequences.count().toInt() != 1) {
            handleGetReviewDataError(submitter, sequenceVersion)
        }

        return selectedSequences.first().let {
            SequenceReview(
                it[SequencesTable.sequenceId],
                it[SequencesTable.version],
                Status.fromString(it[SequencesTable.status]),
                it[SequencesTable.processedData]!!,
                it[SequencesTable.originalData]!!,
                it[SequencesTable.errors],
                it[SequencesTable.warnings],
            )
        }
    }

    private fun handleGetReviewDataError(submitter: String, sequenceVersion: SequenceVersion): Nothing {
        val selectedSequences = SequencesTable
            .slice(
                SequencesTable.sequenceId,
                SequencesTable.version,
                SequencesTable.status,
            )
            .select(
                where = {
                    (SequencesTable.sequenceId eq sequenceVersion.sequenceId) and
                        (SequencesTable.version eq sequenceVersion.version)
                },
            )

        if (selectedSequences.count().toInt() == 0) {
            throw NotFoundException("Sequence version ${sequenceVersion.displaySequenceVersion()} does not exist")
        }

        val selectedSequence = selectedSequences.first()

        val hasCorrectStatus =
            (selectedSequence[SequencesTable.status] == Status.PROCESSED.name) ||
                (selectedSequence[SequencesTable.status] == Status.NEEDS_REVIEW.name)

        if (!hasCorrectStatus) {
            throw UnprocessableEntityException(
                "Sequence version ${sequenceVersion.displaySequenceVersion()} is in not in state " +
                    "${Status.NEEDS_REVIEW.name} or ${Status.PROCESSED.name} " +
                    "(was ${selectedSequence[SequencesTable.status]})",
            )
        }

        if (!hasPermissionToChange(submitter, sequenceVersion)) {
            throw ForbiddenException(
                "Sequence ${sequenceVersion.displaySequenceVersion()} is not owned by user $submitter",
            )
        }

        throw RuntimeException(
            "Get review data: Unexpected error for sequence version ${sequenceVersion.displaySequenceVersion()}",
        )
    }

    private fun hasPermissionToChange(user: String, sequenceVersion: SequenceVersion): Boolean {
        val sequencesOwnedByUser = SequencesTable
            .slice(SequencesTable.sequenceId, SequencesTable.version, SequencesTable.submitter)
            .select(
                where = {
                    (SequencesTable.sequenceId eq sequenceVersion.sequenceId) and
                        (SequencesTable.version eq sequenceVersion.version) and
                        (SequencesTable.submitter eq user)
                },
            )

        return sequencesOwnedByUser.count() == 1L
    }
}

interface SequenceVersionInterface {
    val sequenceId: Long
    val version: Long

    fun displaySequenceVersion() = "$sequenceId.$version"
}

data class SequenceVersion(
    override val sequenceId: Long,
    override val version: Long,
) : SequenceVersionInterface

fun List<SequenceVersion>.toPairs() = map { Pair(it.sequenceId, it.version) }

data class SubmittedProcessedData(
    override val sequenceId: Long,
    override val version: Long,
    val data: ProcessedData,
    @Schema(description = "The processing failed due to these errors.")
    val errors: List<PreprocessingAnnotation>? = null,
    @Schema(
        description =
        "Issues where data is not necessarily wrong, but the submitter might want to look into those warnings.",
    )
    val warnings: List<PreprocessingAnnotation>? = null,
) : SequenceVersionInterface

data class SequenceReview(
    val sequenceId: Long,
    val version: Long,
    val status: Status,
    val processedData: ProcessedData,
    val originalData: OriginalData,
    @Schema(description = "The preprocessing will be considered failed if this is not empty")
    val errors: List<PreprocessingAnnotation>? = null,
    @Schema(
        description =
        "Issues where data is not necessarily wrong, but the user might want to look into those warnings.",
    )
    val warnings: List<PreprocessingAnnotation>? = null,
)

typealias SegmentName = String
typealias GeneName = String
typealias NucleotideSequence = String
typealias AminoAcidSequence = String

data class ProcessedData(
    @Schema(
        example = """{"date": "2020-01-01", "country": "Germany", "age": 42, "qc": 0.95}""",
        description = "Key value pairs of metadata, correctly typed",
    )
    val metadata: Map<String, JsonNode>,
    @Schema(
        example = """{"segment1": "ACTG", "segment2": "GTCA"}""",
        description = "The key is the segment name, the value is the nucleotide sequence",
    )
    val unalignedNucleotideSequences: Map<SegmentName, NucleotideSequence>,
    @Schema(
        example = """{"segment1": "ACTG", "segment2": "GTCA"}""",
        description = "The key is the segment name, the value is the aligned nucleotide sequence",
    )
    val alignedNucleotideSequences: Map<SegmentName, NucleotideSequence>,
    @Schema(
        example = """{"segment1": ["123:GTCA", "345:AAAA"], "segment2": ["123:GTCA", "345:AAAA"]}""",
        description = "The key is the segment name, the value is a list of nucleotide insertions",
    )
    val nucleotideInsertions: Map<SegmentName, List<Insertion>>,
    @Schema(
        example = """{"gene1": "NRNR", "gene2": "NRNR"}""",
        description = "The key is the gene name, the value is the amino acid sequence",
    )
    val aminoAcidSequences: Map<GeneName, AminoAcidSequence>,
    @Schema(
        example = """{"gene1": ["123:RRN", "345:NNN"], "gene2": ["123:NNR", "345:RN"]}""",
        description = "The key is the gene name, the value is a list of amino acid insertions",
    )
    val aminoAcidInsertions: Map<GeneName, List<Insertion>>,
)

@JsonDeserialize(using = InsertionDeserializer::class)
data class Insertion(
    @Schema(example = "123", description = "Position in the sequence where the insertion starts")
    val position: Int,
    @Schema(example = "GTCA", description = "Inserted sequence")
    val sequence: String,
) {
    companion object {
        fun fromString(insertionString: String): Insertion {
            val parts = insertionString.split(":")
            if (parts.size != 2) {
                throw IllegalArgumentException("Invalid insertion string: $insertionString")
            }
            return Insertion(parts[0].toInt(), parts[1])
        }
    }

    @JsonValue
    override fun toString(): String {
        return "$position:$sequence"
    }
}

class InsertionDeserializer : JsonDeserializer<Insertion>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Insertion {
        return Insertion.fromString(p.valueAsString)
    }
}

data class PreprocessingAnnotation(
    val source: List<PreprocessingAnnotationSource>,
    @Schema(description = "A descriptive message that helps the submitter to fix the issue") val message: String,
)

data class PreprocessingAnnotationSource(
    val type: PreprocessingAnnotationSourceType,
    @Schema(description = "Field or sequence segment name") val name: String,
)

enum class PreprocessingAnnotationSourceType {
    Metadata,
    NucleotideSequence,
}

data class SequenceVersionStatus(
    val sequenceId: Long,
    val version: Long,
    val status: Status,
    val isRevocation: Boolean = false,
)

data class FileData(
    val customId: String,
    val sequenceId: Long,
    val originalData: OriginalData,
)

data class SubmittedData(
    val customId: String,
    val originalData: OriginalData,
)

data class UnprocessedData(
    @Schema(example = "123") override val sequenceId: Long,
    @Schema(example = "1") override val version: Long,
    val data: OriginalData,
) : SequenceVersionInterface

data class OriginalData(
    @Schema(
        example = "{\"date\": \"2020-01-01\", \"country\": \"Germany\"}",
        description = "Key value pairs of metadata, as submitted in the metadata file",
    )
    val metadata: Map<String, String>,
    @Schema(
        example = "{\"segment1\": \"ACTG\", \"segment2\": \"GTCA\"}",
        description = "The key is the segment name, the value is the nucleotide sequence",
    )
    val unalignedNucleotideSequences: Map<String, String>,
)

data class SequenceValidation(
    val sequenceId: Long,
    val version: Long,
    val validation: ValidationResult,
)

enum class Status {
    @JsonProperty("RECEIVED")
    RECEIVED,

    @JsonProperty("PROCESSING")
    PROCESSING,

    @JsonProperty("NEEDS_REVIEW")
    NEEDS_REVIEW,

    @JsonProperty("REVIEWED")
    REVIEWED,

    @JsonProperty("PROCESSED")
    PROCESSED,

    @JsonProperty("SILO_READY")
    SILO_READY,

    @JsonProperty("REVOKED_STAGING")
    REVOKED_STAGING,

    ;

    companion object {
        private val stringToEnumMap: Map<String, Status> = entries.associateBy { it.name }

        fun fromString(statusString: String): Status {
            return stringToEnumMap[statusString]
                ?: throw IllegalArgumentException("Unknown status: $statusString")
        }
    }
}
