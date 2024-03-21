package org.loculus.backend.controller.submission

import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.empty
import org.junit.jupiter.api.Test
import org.loculus.backend.api.SequenceEntryVersionToEdit
import org.loculus.backend.api.Status
import org.loculus.backend.controller.DEFAULT_ORGANISM
import org.loculus.backend.controller.EndpointTest
import org.loculus.backend.controller.OTHER_ORGANISM
import org.loculus.backend.controller.assertStatusIs
import org.loculus.backend.controller.expectNdjsonAndGetContent
import org.loculus.backend.controller.expectUnauthorizedResponse
import org.loculus.backend.controller.generateJwtFor
import org.loculus.backend.controller.getAccessionVersions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@EndpointTest
class GetDataToEditEndpointTest(
    @Autowired val client: SubmissionControllerClient,
    @Autowired val convenienceClient: SubmissionConvenienceClient,
) {

    @Test
    fun `GIVEN invalid authorization token THEN returns 401 Unauthorized`() {
        expectUnauthorizedResponse {
            client.getSequenceEntryThatHasErrors(
                "ShouldNotMatterAtAll",
                1,
                jwt = it,
            )
        }
        expectUnauthorizedResponse {
            client.getNumberOfSequenceEntriesThatHaveErrors(
                1,
                jwt = it,
            )
        }
    }

    @Test
    fun `GIVEN an entry has errors WHEN I extract the sequence data THEN I get all data to edit the entry`() {
        val firstAccession = convenienceClient.prepareDefaultSequenceEntriesToInProcessing().first().accession

        convenienceClient.submitProcessedData(PreparedProcessedData.withErrors(firstAccession))

        convenienceClient.getSequenceEntryOfUser(accession = firstAccession, version = 1)
            .assertStatusIs(Status.HAS_ERRORS)

        val editedData = convenienceClient.getSequenceEntryThatHasErrors(
            accession = firstAccession,
            version = 1,
        )

        assertThat(editedData.accession, `is`(firstAccession))
        assertThat(editedData.version, `is`(1))
        assertThat(editedData.processedData, `is`(PreparedProcessedData.withErrors(firstAccession).data))
    }

    @Test
    fun `WHEN I query data for a non-existent accession THEN refuses request with not found`() {
        val nonExistentAccession = "DefinitelyNotExisting"

        client.getSequenceEntryThatHasErrors(nonExistentAccession, 1)
            .andExpect(status().isUnprocessableEntity)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(
                jsonPath("\$.detail").value(
                    "Accession versions $nonExistentAccession.1 do not exist",
                ),
            )
    }

    @Test
    fun `WHEN I query data for wrong organism THEN refuses request with unprocessable entity`() {
        val firstAccession = convenienceClient.prepareDataTo(
            Status.HAS_ERRORS,
            organism = DEFAULT_ORGANISM,
        ).first().accession

        client.getSequenceEntryThatHasErrors(firstAccession, 1, organism = DEFAULT_ORGANISM)
            .andExpect(status().isOk)
        client.getSequenceEntryThatHasErrors(firstAccession, 1, organism = OTHER_ORGANISM)
            .andExpect(status().isUnprocessableEntity)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(
                jsonPath("\$.detail").value(
                    containsString(
                        "The following accession versions are not of organism $OTHER_ORGANISM:",
                    ),
                ),
            )
    }

    @Test
    fun `WHEN I query data for a non-existent accession version THEN refuses request with not found`() {
        val nonExistentAccessionVersion = 999L

        convenienceClient.prepareDataTo(Status.HAS_ERRORS)

        client.getSequenceEntryThatHasErrors("1", nonExistentAccessionVersion)
            .andExpect(status().isUnprocessableEntity)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(
                jsonPath("\$.detail").value(
                    "Accession versions 1.$nonExistentAccessionVersion do not exist",
                ),
            )
    }

    @Test
    fun `WHEN I query a sequence entry that has a wrong state THEN refuses request with unprocessable entity`() {
        val firstAccession = convenienceClient.prepareDataTo(Status.IN_PROCESSING).first().accession

        client.getSequenceEntryThatHasErrors(
            accession = firstAccession,
            version = 1,
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(
                jsonPath("\$.detail").value(
                    "Accession versions are in not in one of the states " +
                        "[HAS_ERRORS, AWAITING_APPROVAL]: $firstAccession.1 - IN_PROCESSING",
                ),
            )
    }

    @Test
    fun `WHEN I try to get data for a sequence entry that I do not own THEN refuses request with forbidden entity`() {
        val firstAccession = convenienceClient.prepareDataTo(Status.HAS_ERRORS).first().accession

        val userNameThatDoesNotHavePermissionToQuery = "theOneWhoMustNotBeNamed"
        client.getSequenceEntryThatHasErrors(
            accession = firstAccession,
            version = 1,
            jwt = generateJwtFor(userNameThatDoesNotHavePermissionToQuery),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(
                jsonPath("\$.detail", containsString("is not a member of group")),
            )
    }

    @Test
    fun `WHEN I try to get batch data for sequence entries to edit THEN I get the expected count back`() {
        convenienceClient.prepareDataTo(Status.HAS_ERRORS)

        val numberOfEditedSequenceEntries = client.getNumberOfSequenceEntriesThatHaveErrors(
            SubmitFiles.DefaultFiles.NUMBER_OF_SEQUENCES,
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_NDJSON_VALUE))
            .expectNdjsonAndGetContent<SequenceEntryVersionToEdit>().size
        assertThat(numberOfEditedSequenceEntries, `is`(SubmitFiles.DefaultFiles.NUMBER_OF_SEQUENCES))

        val userNameThatDoesNotHavePermissionToQuery = "theOneWhoMustNotBeNamed"
        client.getNumberOfSequenceEntriesThatHaveErrors(
            SubmitFiles.DefaultFiles.NUMBER_OF_SEQUENCES,
            jwt = generateJwtFor(userNameThatDoesNotHavePermissionToQuery),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(
                jsonPath("\$.detail", containsString("is not a member of group")),
            )
    }

    @Test
    fun `GIVEN sequence entries for different organisms THEN only returns data for requested organism`() {
        val defaultOrganismData = convenienceClient.prepareDataTo(Status.HAS_ERRORS, organism = DEFAULT_ORGANISM)
        val otherOrganismData = convenienceClient.prepareDataTo(Status.HAS_ERRORS, organism = OTHER_ORGANISM)

        val sequencesToEdit = client.getNumberOfSequenceEntriesThatHaveErrors(
            defaultOrganismData.size + otherOrganismData.size,
            organism = OTHER_ORGANISM,
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_NDJSON_VALUE))
            .expectNdjsonAndGetContent<SequenceEntryVersionToEdit>()

        assertThat(
            sequencesToEdit.getAccessionVersions(),
            containsInAnyOrder(*otherOrganismData.getAccessionVersions().toTypedArray()),
        )

        val accessionVersionSet = defaultOrganismData.getAccessionVersions().toSet()

        assertThat(
            sequencesToEdit.getAccessionVersions().intersect(accessionVersionSet),
            `is`(empty()),
        )
    }

    @Test
    fun `WHEN I want to get more than allowed number of edited entries at once THEN returns Bad Request`() {
        client.getNumberOfSequenceEntriesThatHaveErrors(100_001)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("\$.detail", containsString("You can extract at max 100000 sequence entries at once.")))
    }
}
