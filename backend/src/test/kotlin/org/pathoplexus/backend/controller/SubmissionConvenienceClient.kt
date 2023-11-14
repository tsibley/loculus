package org.pathoplexus.backend.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.pathoplexus.backend.api.HeaderId
import org.pathoplexus.backend.api.SequenceReview
import org.pathoplexus.backend.api.SequenceVersion
import org.pathoplexus.backend.api.SequenceVersionStatus
import org.pathoplexus.backend.api.Status
import org.pathoplexus.backend.api.SubmittedProcessedData
import org.pathoplexus.backend.api.UnprocessedData
import org.pathoplexus.backend.controller.SubmitFiles.DefaultFiles
import org.pathoplexus.backend.service.SequenceId
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class SubmissionConvenienceClient(
    private val client: SubmissionControllerClient,
    private val objectMapper: ObjectMapper,
) {
    fun submitDefaultFiles(username: String = USER_NAME): List<HeaderId> {
        val submit = client.submit(
            username,
            DefaultFiles.metadataFile,
            DefaultFiles.sequencesFile,
        )

        return deserializeJsonResponse(submit)
    }

    fun prepareDefaultSequencesToInProcessing() {
        submitDefaultFiles()
        extractUnprocessedData()
    }

    fun submitProcessedData(vararg submittedProcessedData: SubmittedProcessedData) {
        client.submitProcessedData(*submittedProcessedData)
            .andExpect(status().isNoContent)
    }

    fun prepareDefaultSequencesToHasErrors() {
        prepareDefaultSequencesToInProcessing()
        DefaultFiles.allSequenceIds.forEach { sequenceId ->
            client.submitProcessedData(PreparedProcessedData.withErrors(sequenceId = sequenceId))
        }
    }

    fun prepareDefaultSequencesToAwaitingApproval() {
        prepareDefaultSequencesToInProcessing()
        client.submitProcessedData(
            *DefaultFiles.allSequenceIds.map {
                PreparedProcessedData.successfullyProcessed(sequenceId = it)
            }.toTypedArray(),
        )
    }

    fun prepareDefaultSequencesToApprovedForRelease() {
        prepareDefaultSequencesToAwaitingApproval()

        approveProcessedSequences(
            DefaultFiles.allSequenceIds.map { SequenceVersion(it, 1L) },
        )
    }

    fun reviseAndProcessDefaultSequences() {
        reviseDefaultProcessedSequences()
        val extractedSequenceVersions = extractUnprocessedData().map { SequenceVersion(it.sequenceId, it.version) }
        submitProcessedData(
            *extractedSequenceVersions
                .map { PreparedProcessedData.successfullyProcessed(sequenceId = it.sequenceId, version = it.version) }
                .toTypedArray(),
        )
        approveProcessedSequences(extractedSequenceVersions)
    }

    fun prepareDefaultSequencesToAwaitingApprovalForRevocation() {
        prepareDefaultSequencesToApprovedForRelease()
        revokeSequences(DefaultFiles.allSequenceIds)
    }

    fun extractUnprocessedData(numberOfSequences: Int = DefaultFiles.NUMBER_OF_SEQUENCES) =
        client.extractUnprocessedData(numberOfSequences)
            .expectNdjsonAndGetContent<UnprocessedData>()

    fun prepareDatabaseWith(
        vararg processedData: SubmittedProcessedData,
    ) {
        submitDefaultFiles()
        extractUnprocessedData()
        client.submitProcessedData(*processedData)
    }

    fun getSequencesOfUser(userName: String = USER_NAME): List<SequenceVersionStatus> {
        return deserializeJsonResponse(client.getSequencesOfUser(userName))
    }

    fun getSequencesOfUserInState(
        userName: String = USER_NAME,
        status: Status,
    ): List<SequenceVersionStatus> = getSequencesOfUser(userName).filter { it.status == status }

    fun getSequenceVersionOfUser(
        sequenceVersion: SequenceVersion,
        userName: String = USER_NAME,
    ) = getSequenceVersionOfUser(sequenceVersion.sequenceId, sequenceVersion.version, userName)

    fun getSequenceVersionOfUser(
        sequenceId: SequenceId,
        version: Long,
        userName: String = USER_NAME,
    ): SequenceVersionStatus {
        val sequencesOfUser = getSequencesOfUser(userName)

        return sequencesOfUser.find { it.sequenceId == sequenceId && it.version == version }
            ?: error("Did not find $sequenceId.$version for $userName")
    }

    fun getSequenceThatNeedsReview(
        sequenceId: SequenceId,
        version: Long,
        userName: String = USER_NAME,
    ): SequenceReview =
        deserializeJsonResponse(client.getSequenceThatNeedsReview(sequenceId, version, userName))

    fun submitDefaultReviewedData(
        userName: String = USER_NAME,
    ) {
        DefaultFiles.allSequenceIds.forEach { sequenceId ->
            client.submitReviewedSequence(
                userName,
                UnprocessedData(sequenceId, 1L, defaultOriginalData),
            )
        }
    }

    fun approveProcessedSequences(listOfSequencesToApprove: List<SequenceVersion>) {
        client.approveProcessedSequences(listOfSequencesToApprove)
            .andExpect(status().isNoContent)
    }

    fun reviseDefaultProcessedSequences(): List<HeaderId> {
        val result = client.reviseSequences(
            DefaultFiles.revisedMetadataFile,
            DefaultFiles.sequencesFile,
        ).andExpect(status().isOk)

        return deserializeJsonResponse(result)
    }

    fun revokeSequences(listOfSequencesToRevoke: List<SequenceId>): List<SequenceVersionStatus> =
        deserializeJsonResponse(client.revokeSequences(listOfSequencesToRevoke))

    fun confirmRevocation(listOfSequencesToConfirm: List<SequenceVersion>) {
        client.confirmRevocation(listOfSequencesToConfirm)
            .andExpect(status().isNoContent)
    }

    fun prepareDataTo(status: Status) {
        when (status) {
            Status.RECEIVED -> submitDefaultFiles()
            Status.IN_PROCESSING -> prepareDefaultSequencesToInProcessing()
            Status.HAS_ERRORS -> prepareDefaultSequencesToHasErrors()
            Status.AWAITING_APPROVAL -> prepareDefaultSequencesToAwaitingApproval()
            Status.APPROVED_FOR_RELEASE -> prepareDefaultSequencesToApprovedForRelease()
            Status.AWAITING_APPROVAL_FOR_REVOCATION -> prepareDefaultSequencesToAwaitingApprovalForRevocation()
        }
    }

    private inline fun <reified T> deserializeJsonResponse(resultActions: ResultActions): T {
        val content =
            resultActions
                .andExpect(status().isOk)
                .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
                .andReturn()
                .response
                .contentAsString
        return objectMapper.readValue(content)
    }
}
