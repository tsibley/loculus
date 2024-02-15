package org.loculus.backend.service.submission

import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.json.exists
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.wrapAsExpression
import org.loculus.backend.api.AccessionVersionInterface
import org.loculus.backend.api.Group
import org.loculus.backend.api.Organism
import org.loculus.backend.api.OriginalData
import org.loculus.backend.api.PreprocessingAnnotation
import org.loculus.backend.api.ProcessedData
import org.loculus.backend.api.Status
import org.loculus.backend.api.toPairs
import org.loculus.backend.service.jacksonObjectMapper
import org.loculus.backend.service.jacksonSerializableJsonb
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger { }

@Service
class SequenceEntriesTableProvider(private val compressionService: CompressionService) {

    private val cachedTables: MutableMap<Organism?, SequenceEntriesDataTable> = mutableMapOf()

    fun get(organism: Organism?): SequenceEntriesDataTable {
        return cachedTables.getOrPut(organism) {
            SequenceEntriesDataTable(compressionService, organism)
        }
    }
}

const val SEQUENCE_ENTRIES_TABLE_NAME = "sequence_entries"

class SequenceEntriesDataTable(
    compressionService: CompressionService,
    organism: Organism? = null,
) : Table(
    SEQUENCE_ENTRIES_TABLE_NAME,
) {
    val originalDataColumn = serializeOriginalData(compressionService, organism).nullable()
    val processedDataColumn = serializeProcessedData(compressionService, organism).nullable()

    val accessionColumn = varchar("accession", 255)
    val versionColumn = long("version")
    val organismColumn = varchar("organism", 255)
    val submissionIdColumn = varchar("submission_id", 255)
    val submitterColumn = varchar("submitter", 255)
    val groupNameColumn = varchar("group_name", 255)
    val submittedAtColumn = datetime("submitted_at")
    val startedProcessingAtColumn = datetime("started_processing_at").nullable()
    val finishedProcessingAtColumn = datetime("finished_processing_at").nullable()
    val releasedAtColumn = datetime("released_at").nullable()
    val statusColumn = varchar("status", 255)
    val isRevocationColumn = bool("is_revocation").default(false)
    val errorsColumn = jacksonSerializableJsonb<List<PreprocessingAnnotation>>("errors").nullable()
    val warningsColumn = jacksonSerializableJsonb<List<PreprocessingAnnotation>>("warnings").nullable()

    override val primaryKey = PrimaryKey(accessionColumn, versionColumn)

    val isMaxVersion = versionColumn eq maxVersionQuery()

    private fun maxVersionQuery(): Expression<Long?> {
        val subQueryTable = alias("subQueryTable")
        return wrapAsExpression(
            subQueryTable
                .slice(subQueryTable[versionColumn].max())
                .select {
                    subQueryTable[accessionColumn] eq accessionColumn
                },
        )
    }

    private fun maxReleasedVersionQuery(): Expression<Long?> {
        val subQueryTable = alias("subQueryTable")
        return wrapAsExpression(
            subQueryTable
                .slice(subQueryTable[versionColumn].max())
                .select {
                    (subQueryTable[accessionColumn] eq accessionColumn) and
                        (subQueryTable[statusColumn] eq Status.APPROVED_FOR_RELEASE.name)
                },
        )
    }

    fun accessionVersionIsIn(accessionVersions: List<AccessionVersionInterface>) =
        Pair(accessionColumn, versionColumn) inList accessionVersions.toPairs()

    fun organismIs(organism: Organism) = organismColumn eq organism.name

    val entriesWithWarnings = warningsColumn.exists("[0]")

    fun statusIs(status: Status) = statusColumn eq status.name

    fun statusIsOneOf(statuses: List<Status>) = statusColumn inList statuses.map { it.name }

    fun accessionVersionEquals(accessionVersion: AccessionVersionInterface) =
        (accessionColumn eq accessionVersion.accession) and
            (versionColumn eq accessionVersion.version)

    fun groupIs(group: String) = groupNameColumn eq group

    fun groupIsOneOf(groups: List<Group>) = groupNameColumn inList groups.map { it.groupName }

    private val warningWhenNoOrganismWhenSerializing = "Organism is null when de-serializing data. " +
        "This should not happen. " +
        "Please check your code. " +
        "Data will be written without compression. " +
        "If this is unintentional data can become corrupted. "

    private fun serializeOriginalData(
        compressionService: CompressionService,
        organism: Organism?,
    ): Column<OriginalData> {
        return jsonb(
            "original_data",
            { originalData ->
                jacksonObjectMapper.writeValueAsString(
                    if (organism == null) {
                        logger.warn { warningWhenNoOrganismWhenSerializing }
                        originalData
                    } else {
                        compressionService.compressSequencesInOriginalData(
                            originalData,
                            organism,
                        )
                    },
                )
            },
            { string ->
                val originalData = jacksonObjectMapper.readValue(string) as OriginalData
                if (organism == null) {
                    logger.warn { warningWhenNoOrganismWhenSerializing }
                    originalData
                } else {
                    compressionService.decompressSequencesInOriginalData(
                        originalData,
                        organism,
                    )
                }
            },
        )
    }

    private fun serializeProcessedData(
        compressionService: CompressionService,
        organism: Organism?,
    ): Column<ProcessedData> {
        return jsonb(
            "processed_data",
            { processedData ->
                jacksonObjectMapper.writeValueAsString(
                    if (organism == null) {
                        logger.warn { warningWhenNoOrganismWhenSerializing }
                        processedData
                    } else {
                        compressionService.compressSequencesInProcessedData(
                            processedData,
                            organism,
                        )
                    },
                )
            },
            { string ->
                val processedData = jacksonObjectMapper.readValue(string) as ProcessedData
                if (organism == null) {
                    logger.warn { warningWhenNoOrganismWhenSerializing }
                    processedData
                } else {
                    compressionService.decompressSequencesInProcessedData(
                        jacksonObjectMapper.readValue(string) as ProcessedData,
                        organism,
                    )
                }
            },
        )
    }
}
