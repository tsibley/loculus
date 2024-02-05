package org.loculus.backend.controller.submission

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.loculus.backend.api.AccessionVersion
import org.loculus.backend.api.Status
import org.loculus.backend.api.Status.AWAITING_APPROVAL
import org.loculus.backend.api.Status.IN_PROCESSING
import org.loculus.backend.controller.ALTERNATIVE_DEFAULT_GROUP_NAME
import org.loculus.backend.controller.ALTERNATIVE_DEFAULT_USER_NAME
import org.loculus.backend.controller.DEFAULT_GROUP_NAME
import org.loculus.backend.controller.DEFAULT_ORGANISM
import org.loculus.backend.controller.EndpointTest
import org.loculus.backend.controller.OTHER_ORGANISM
import org.loculus.backend.controller.expectUnauthorizedResponse
import org.loculus.backend.controller.generateJwtFor
import org.loculus.backend.controller.getAccessionVersions
import org.loculus.backend.controller.submission.SubmitFiles.DefaultFiles.NUMBER_OF_SEQUENCES
import org.loculus.backend.controller.submission.SubmitFiles.DefaultFiles.firstAccession
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@EndpointTest
class GetSequencesEndpointTest(
    @Autowired val client: SubmissionControllerClient,
    @Autowired val convenienceClient: SubmissionConvenienceClient,
) {

    @Test
    fun `GIVEN invalid authorization token THEN returns 401 Unauthorized`() {
        expectUnauthorizedResponse { client.getSequenceEntries(jwt = it) }
    }

    @Test
    fun `GIVEN data submitted from a group member THEN another group member sees the data`() {
        convenienceClient.submitDefaultFiles(DEFAULT_USER_NAME)

        val sequencesOfUser = convenienceClient.getSequenceEntries(
            username = DEFAULT_USER_NAME,
        )

        assertThat(sequencesOfUser, hasSize(NUMBER_OF_SEQUENCES))

        val sequencesOfAlternativeUser = convenienceClient.getSequenceEntries(
            username = ALTERNATIVE_DEFAULT_USER_NAME,
        )

        assertThat(sequencesOfAlternativeUser, hasSize(NUMBER_OF_SEQUENCES))
    }

    @Test
    fun `GIVEN data submitted to a group WHEN querying another group THEN only shows entries of the given group`() {
        convenienceClient.submitDefaultFiles(DEFAULT_USER_NAME, DEFAULT_GROUP_NAME)

        val sequencesOfUser = convenienceClient.getSequenceEntries(
            username = DEFAULT_USER_NAME,
            groupsFilter = listOf(ALTERNATIVE_DEFAULT_GROUP_NAME),
        )

        assertThat(sequencesOfUser, hasSize(0))
    }

    @Test
    fun `WHEN querying for a non-existing group THEN expect an error that the group is not found`() {
        val nonExistingGroup = "a-non-existing-group-that-does-not-exist-in-the-database-and-never-will"

        client.getSequenceEntries(
            groupsFilter = listOf(nonExistingGroup),
        )
            .andExpect(status().isNotFound)
            .andExpect(
                jsonPath("$.detail", containsString("Groups $nonExistingGroup do not exist.")),
            )
    }

    @Test
    fun `GIVEN some sequence entries in the database THEN only shows entries of the requested organism`() {
        val defaultOrganismData = convenienceClient.submitDefaultFiles(organism = DEFAULT_ORGANISM)
        val otherOrganismData = convenienceClient.submitDefaultFiles(organism = OTHER_ORGANISM)

        val sequencesOfUser = convenienceClient.getSequenceEntries(
            username = DEFAULT_USER_NAME,
            organism = OTHER_ORGANISM,
        )

        assertThat(
            sequencesOfUser.getAccessionVersions(),
            containsInAnyOrder(*otherOrganismData.getAccessionVersions().toTypedArray()),
        )
        assertThat(
            sequencesOfUser.getAccessionVersions().intersect(defaultOrganismData.getAccessionVersions().toSet()),
            `is`(empty()),
        )
    }

    @Test
    fun `WHEN querying sequences of an existing group that you're not a member of THEN this is forbidden`() {
        client.getSequenceEntries(
            groupsFilter = listOf(ALTERNATIVE_DEFAULT_GROUP_NAME),
            jwt = generateJwtFor(ALTERNATIVE_DEFAULT_USER_NAME),
        ).andExpect(status().isForbidden).andExpect {
            jsonPath(
                "$.detail",
                containsString(
                    "User $ALTERNATIVE_DEFAULT_USER_NAME is not " +
                        "a member of groups $ALTERNATIVE_DEFAULT_GROUP_NAME.",
                ),
            )
        }
    }

    @Test
    fun `GIVEN data in many statuses WHEN querying sequences for a certain one THEN return only those sequences`() {
        convenienceClient.prepareDataTo(AWAITING_APPROVAL)

        val sequencesInAwaitingApproval = convenienceClient.getSequenceEntries(
            username = ALTERNATIVE_DEFAULT_USER_NAME,
            statusesFilter = listOf(AWAITING_APPROVAL),
        )

        assertThat(sequencesInAwaitingApproval, hasSize(10))

        val sequencesInProcessing = convenienceClient.getSequenceEntries(
            username = ALTERNATIVE_DEFAULT_USER_NAME,
            statusesFilter = listOf(IN_PROCESSING),
        )

        assertThat(sequencesInProcessing, hasSize(0))
    }

    @ParameterizedTest(name = "{arguments}")
    @MethodSource("provideStatusScenarios")
    fun `GIVEN database in prepared state THEN returns sequence entries in expected status`(scenario: Scenario) {
        scenario.prepareDatabase(convenienceClient)

        val sequencesOfUser = convenienceClient.getSequenceEntries(statusesFilter = listOf(scenario.expectedStatus))

        val accessionVersionStatus =
            sequencesOfUser.find { it.accession == firstAccession && it.version == scenario.expectedVersion }
        assertThat(accessionVersionStatus?.status, `is`(scenario.expectedStatus))
        assertThat(accessionVersionStatus?.isRevocation, `is`(scenario.expectedIsRevocation))
    }

    companion object {
        @JvmStatic
        fun provideStatusScenarios() = listOf(
            Scenario(
                setupDescription = "I submitted sequence entries",
                prepareDatabase = { it.submitDefaultFiles() },
                expectedStatus = Status.RECEIVED,
                expectedIsRevocation = false,
            ),
            Scenario(
                setupDescription = "I started processing sequence entries",
                prepareDatabase = { it.prepareDefaultSequenceEntriesToInProcessing() },
                expectedStatus = IN_PROCESSING,
                expectedIsRevocation = false,
            ),
            Scenario(
                setupDescription = "I submitted sequence entries that have errors",
                prepareDatabase = { it.prepareDefaultSequenceEntriesToHasErrors() },
                expectedStatus = Status.HAS_ERRORS,
                expectedIsRevocation = false,
            ),
            Scenario(
                setupDescription = "I submitted sequence entries that have been successfully processed",
                prepareDatabase = {
                    it.prepareDatabaseWithProcessedData(
                        PreparedProcessedData.successfullyProcessed(),
                    )
                },
                expectedStatus = AWAITING_APPROVAL,
                expectedIsRevocation = false,
            ),
            Scenario(
                setupDescription = "I submitted, processed and approved sequence entries",
                prepareDatabase = {
                    it.prepareDatabaseWithProcessedData(PreparedProcessedData.successfullyProcessed())
                    it.approveProcessedSequenceEntries(listOf(AccessionVersion(firstAccession, 1)))
                },
                expectedStatus = Status.APPROVED_FOR_RELEASE,
                expectedIsRevocation = false,
            ),
            Scenario(
                setupDescription = "I submitted a revocation",
                prepareDatabase = {
                    it.prepareDatabaseWithProcessedData(PreparedProcessedData.successfullyProcessed())
                    it.approveProcessedSequenceEntries(listOf(AccessionVersion(firstAccession, 1)))
                    it.revokeSequenceEntries(listOf(firstAccession))
                },
                expectedStatus = Status.AWAITING_APPROVAL_FOR_REVOCATION,
                expectedIsRevocation = true,
                expectedVersion = 2,
            ),
            Scenario(
                setupDescription = "I approved a revocation",
                prepareDatabase = {
                    it.prepareDatabaseWithProcessedData(PreparedProcessedData.successfullyProcessed())
                    it.approveProcessedSequenceEntries(listOf(AccessionVersion(firstAccession, 1)))
                    it.revokeSequenceEntries(listOf(firstAccession))
                    it.confirmRevocation(listOf(AccessionVersion(firstAccession, 2)))
                },
                expectedStatus = Status.APPROVED_FOR_RELEASE,
                expectedIsRevocation = true,
                expectedVersion = 2,
            ),
        )
    }

    data class Scenario(
        val setupDescription: String,
        val expectedVersion: Long = 1,
        val prepareDatabase: (SubmissionConvenienceClient) -> Unit,
        val expectedStatus: Status,
        val expectedIsRevocation: Boolean,
    ) {
        override fun toString(): String {
            val maybeRevocationSequence = when {
                expectedIsRevocation -> "revocation sequence"
                else -> "sequence"
            }

            return "GIVEN $setupDescription THEN shows $maybeRevocationSequence in status ${expectedStatus.name}"
        }
    }
}
