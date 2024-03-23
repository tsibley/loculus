package org.loculus.backend.service.submission

import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.wrapAsExpression
import org.loculus.backend.api.AccessionVersionInterface
import org.loculus.backend.api.Group
import org.loculus.backend.api.Organism
import org.loculus.backend.api.toPairs
import org.springframework.stereotype.Service

@Service
class SequenceEntriesTableProvider(private val compressionService: CompressionService) {

    private val cachedTables: MutableMap<Organism?, SequenceEntriesTable> = mutableMapOf()

    fun get(organism: Organism?): SequenceEntriesTable {
        return cachedTables.getOrPut(organism) {
            SequenceEntriesTable(compressionService, organism)
        }
    }
}

const val SEQUENCE_ENTRIES_TABLE_NAME = "sequence_entries"

class SequenceEntriesTable(
    compressionService: CompressionService,
    organism: Organism? = null,
) : Table(
    SEQUENCE_ENTRIES_TABLE_NAME,
) {
    val accessionColumn = varchar("accession", 255)
    val versionColumn = long("version")
    val organismColumn = varchar("organism", 255)
    val submissionIdColumn = varchar("submission_id", 255)
    val submitterColumn = varchar("submitter", 255)
    val groupNameColumn = varchar("group_name", 255)
    val submittedAtColumn = datetime("submitted_at")
    val releasedAtColumn = datetime("released_at").nullable()
    val isRevocationColumn = bool("is_revocation").default(false)

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

    fun accessionVersionIsIn(accessionVersions: List<AccessionVersionInterface>) =
        Pair(accessionColumn, versionColumn) inList accessionVersions.toPairs()

    fun groupIsOneOf(groups: List<Group>) = groupNameColumn inList groups.map { it.groupName }
}
