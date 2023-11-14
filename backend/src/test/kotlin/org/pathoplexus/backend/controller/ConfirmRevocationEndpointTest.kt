package org.pathoplexus.backend.controller

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.pathoplexus.backend.api.SequenceVersion
import org.pathoplexus.backend.api.Status
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@EndpointTest
class ConfirmRevocationEndpointTest(
    @Autowired val client: SubmissionControllerClient,
    @Autowired val convenienceClient: SubmissionConvenienceClient,
) {
    @Test
    fun `GIVEN sequences with status 'FOR_REVOCATION' THEN the status changes to 'APPROVED_FOR_RELEASE'`() {
        convenienceClient.prepareDefaultSequencesToAwaitingApprovalForRevocation()

        client.confirmRevocation(
            listOf(
                SequenceVersion("1", 2),
                SequenceVersion("2", 2),
            ),
        )
            .andExpect(status().isNoContent)

        convenienceClient.getSequenceVersionOfUser(sequenceId = "1", version = 2).assertStatusIs(
            Status.APPROVED_FOR_RELEASE,
        )
        convenienceClient.getSequenceVersionOfUser(sequenceId = "2", version = 2).assertStatusIs(
            Status.APPROVED_FOR_RELEASE,
        )
    }

    @Test
    fun `WHEN confirming revocation of non-existing sequenceVersions THEN throws an unprocessableEntity error`() {
        convenienceClient.prepareDefaultSequencesToAwaitingApprovalForRevocation()

        val nonExistingSequenceId = SequenceVersion("123", 2)
        val nonExistingVersion = SequenceVersion("1", 123)

        client.confirmRevocation(listOf(nonExistingSequenceId, nonExistingVersion))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(
                jsonPath("\$.detail", containsString("Sequence versions 1.123, 123.2 do not exist")),
            )
    }

    @Test
    fun `WHEN confirming revocation for sequenceVersions not from the submitter THEN throws forbidden error`() {
        convenienceClient.prepareDefaultSequencesToAwaitingApprovalForRevocation()

        val notSubmitter = "notTheSubmitter"
        client.confirmRevocation(
            listOf(
                SequenceVersion("1", 2),
                SequenceVersion("2", 2),
            ),
            notSubmitter,
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(
                jsonPath("\$.detail").value(
                    "User '$notSubmitter' does not have right to change the sequence versions " +
                        "1.2, 2.2",
                ),
            )
    }

    @Test
    fun `WHEN confirming revocation with latest version not 'APPROVED_FOR_RELEASE' THEN throws an error`() {
        convenienceClient.prepareDefaultSequencesToApprovedForRelease()

        client.confirmRevocation(
            listOf(
                SequenceVersion("1", 1),
                SequenceVersion("2", 1),
            ),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(
                jsonPath("\$.detail").value(
                    "Sequence versions are in not in one of the states [" +
                        "${Status.AWAITING_APPROVAL_FOR_REVOCATION.name}]: " +
                        "1.1 - ${Status.APPROVED_FOR_RELEASE.name}, 2.1 - ${Status.APPROVED_FOR_RELEASE.name}",
                ),
            )
    }
}
