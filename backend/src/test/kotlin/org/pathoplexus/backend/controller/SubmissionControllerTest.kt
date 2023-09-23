package org.pathoplexus.backend.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.pathoplexus.backend.service.SequenceStatus
import org.pathoplexus.backend.service.Status
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.shaded.org.awaitility.Awaitility.await
import java.io.File

@SpringBootTest
@ActiveProfiles("test-with-database")
@AutoConfigureMockMvc
class SubmissionControllerTest(@Autowired val mockMvc: MockMvc, @Autowired val objectMapper: ObjectMapper) {

    private val testUsername = "testuser"

    // This is the number of sequences and a list of sequenceIds in the test data, i.e. metadata.tsv and sequences.fasta
    private val numberOfSequences = 10
    private val allSequenceIds = (1L..numberOfSequences).toList()
    private val firstSequence = allSequenceIds[0]

    @BeforeEach
    fun beforeEach() {
        postgres.execInContainer(
            "psql",
            "-U",
            postgres.username,
            "-d",
            postgres.databaseName,
            "-c",
            "truncate table sequences restart identity cascade;",
        )
    }

    @Test
    fun `submit sequences`() {
        submitInitialData()
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("\$[0].customId").value("custom0"))
            .andExpect(jsonPath("\$[0].sequenceId").isNumber())
    }

    @Test
    fun `extract unprocessed sequences`() {
        val emptyResponse = queryUnprocessedSequences(numberOfSequences)
        expectLinesInResponse(emptyResponse, 0)

        submitInitialData()

        val result7 = queryUnprocessedSequences(7)
        expectLinesInResponse(result7, 7)

        val result3 = queryUnprocessedSequences(5)
        expectLinesInResponse(result3, 3)

        val result0 = queryUnprocessedSequences(numberOfSequences)
        expectLinesInResponse(result0, 0)
    }

    @ParameterizedTest(name = "{arguments}")
    @MethodSource("provideValidationScenarios")
    fun `validation of processed data`(scenario: Scenario<ValidationError>) {
        submitInitialData()
        awaitResponse(queryUnprocessedSequences(numberOfSequences))

        val requestBuilder = submitProcessedData(scenario.inputData)
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("\$").isArray())

        if (scenario.expectedError == null) {
            requestBuilder.andExpect(jsonPath("\$").isEmpty())
        } else {
            val error = scenario.expectedError

            error.fieldsWithTypeMismatch.forEach { mismatch ->
                requestBuilder.andExpect(
                    jsonPath(
                        "\$[0].fieldsWithTypeMismatch",
                        Matchers.hasItem(
                            Matchers.allOf(
                                Matchers.hasEntry("field", mismatch.field),
                                Matchers.hasEntry("shouldBeType", mismatch.shouldBeType),
                                Matchers.hasEntry("fieldValue", mismatch.fieldValue),
                            ),
                        ),
                    ),
                )
            }
            requestBuilder.andExpect(
                jsonPath("\$[0].fieldsWithTypeMismatch", Matchers.hasSize<Any>(error.fieldsWithTypeMismatch.size)),
            )

            for (err in error.unknownFields) {
                requestBuilder.andExpect(jsonPath("\$[0].unknownFields", Matchers.hasItem(err)))
            }
            requestBuilder.andExpect(
                jsonPath("\$[0].unknownFields", Matchers.hasSize<String>(error.unknownFields.size)),
            )

            for (err in error.missingRequiredFields) {
                requestBuilder.andExpect(jsonPath("\$[0].missingRequiredFields", Matchers.hasItem(err)))
            }
            requestBuilder.andExpect(
                jsonPath("\$[0].missingRequiredFields", Matchers.hasSize<String>(error.missingRequiredFields.size)),
            )

            for (err in error.genericError) {
                requestBuilder.andExpect(jsonPath("\$[0].genericError", Matchers.hasItem(err)))
            }
            requestBuilder.andExpect(
                jsonPath("\$[0].genericError", Matchers.hasSize<String>(error.genericError.size)),
            )
        }
    }

    @Test
    fun `handling of errors in processed data`() {
        submitInitialData()
        awaitResponse(queryUnprocessedSequences(numberOfSequences))

        submitProcessedData(processedInputDataFromFile("error_feedback"))

        expectStatusInResponse(querySequenceList(), 1, Status.NEEDS_REVIEW.name)
    }

    @Test
    fun `approving of processed data`() {
        val sequencesThatAreProcessed = listOf(1)
        submitInitialData()
        awaitResponse(queryUnprocessedSequences(numberOfSequences))
        submitProcessedData(processedInputDataFromFile("no_validation_errors"))

        approveProcessedSequences(sequencesThatAreProcessed)

        expectStatusInResponse(querySequenceList(), sequencesThatAreProcessed.size, Status.SILO_READY.name)
    }

    @Test
    fun `workflow from initial submit to releasable data and creating a new version`() {
        submitInitialData()
        expectStatusInResponse(querySequenceList(), numberOfSequences, Status.RECEIVED.name)

        val testData = expectLinesInResponse(queryUnprocessedSequences(numberOfSequences), numberOfSequences)
        expectStatusInResponse(querySequenceList(), numberOfSequences, Status.PROCESSING.name)

        submitProcessedData(testData)
        expectStatusInResponse(querySequenceList(), numberOfSequences, Status.PROCESSED.name)

        approveProcessedSequences(allSequenceIds)
        expectStatusInResponse(querySequenceList(), numberOfSequences, Status.SILO_READY.name)

        allSequenceIds.forEach { sequenceId ->
            reviseSiloReadySequences(sequenceId)
        }
        expectStatusInResponse(querySequenceList(), numberOfSequences, Status.RECEIVED.name)
    }

    @Test
    fun `verify that versions only go up`() {
        prepareDataToSiloReady()

        assertThat(getSequenceList().filter { it.sequenceId == firstSequence })
            .hasSize(1)
            .contains(
                SequenceStatus(
                    sequenceId = firstSequence,
                    version = 1,
                    status = Status.SILO_READY,
                    revoked = false,
                ),
            )

        reviseSiloReadySequences(firstSequence)

        assertThat(getSequenceList().filter { it.sequenceId == firstSequence })
            .hasSize(2)
            .contains(
                SequenceStatus(
                    sequenceId = firstSequence,
                    version = 1,
                    status = Status.SILO_READY,
                    revoked = false,
                ),
            ).contains(
                SequenceStatus(
                    sequenceId = firstSequence,
                    version = 2,
                    status = Status.RECEIVED,
                    revoked = false,
                ),
            )
    }

    @Test
    fun `revoke sequences and check that the 'revoke' flag is set properly`() {
        prepareDataToSiloReady()

        assertThat(getSequenceList().filter { it.sequenceId == firstSequence })
            .hasSize(1)
            .contains(
                SequenceStatus(
                    sequenceId = firstSequence,
                    version = 1,
                    status = Status.SILO_READY,
                    revoked = false,
                ),
            )

        revokeSequences(allSequenceIds)

        assertThat(getSequenceList().filter { it.sequenceId == firstSequence })
            .hasSize(2)
            .contains(
                SequenceStatus(
                    sequenceId = firstSequence,
                    version = 1,
                    status = Status.SILO_READY,
                    revoked = false,
                ),
            ).contains(
                SequenceStatus(
                    sequenceId = firstSequence,
                    version = 2,
                    status = Status.REVOKED_STAGING,
                    revoked = true,
                ),
            )

        confirmRevocation(allSequenceIds)

        assertThat(getSequenceList().filter { it.sequenceId == firstSequence })
            .hasSize(1)
            .contains(
                SequenceStatus(
                    sequenceId = firstSequence,
                    version = 2,
                    status = Status.SILO_READY,
                    revoked = true,
                ),
            )
    }

    private fun getSequenceList(): List<SequenceStatus> = objectMapper.readValue<List<SequenceStatus>>(
        querySequenceList().response.contentAsString,
    )

    private fun submitProcessedData(testData: String): ResultActions {
        return mockMvc.perform(
            MockMvcRequestBuilders.post("/submit-processed-data")
                .contentType(MediaType.APPLICATION_NDJSON_VALUE)
                .content(testData),
        )
            .andExpect(status().isOk())
    }

    private fun querySequenceList(): MvcResult {
        return mockMvc.perform(
            MockMvcRequestBuilders.get("/get-sequences-of-user")
                .param("username", testUsername),
        )
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andReturn()
    }

    private fun submitInitialData(): ResultActions {
        val metadataFile = MockMultipartFile(
            "metadataFile",
            "metadata.tsv",
            MediaType.TEXT_PLAIN_VALUE,
            this.javaClass.classLoader.getResourceAsStream("metadata.tsv")?.readBytes() ?: error(
                "metadata.tsv not found",
            ),
        )

        val sequencesFile = MockMultipartFile(
            "sequenceFile",
            "sequences.fasta",
            MediaType.TEXT_PLAIN_VALUE,
            this.javaClass.classLoader.getResourceAsStream("sequences.fasta")?.readBytes() ?: error(
                "sequences.fasta not found",
            ),
        )

        return mockMvc.perform(
            MockMvcRequestBuilders.multipart("/submit")
                .file(metadataFile)
                .file(sequencesFile)
                .param("username", testUsername),
        )
    }

    private fun queryUnprocessedSequences(numberOfSequences: Int): MvcResult = mockMvc.perform(
        MockMvcRequestBuilders.post("/extract-unprocessed-data")
            .param("numberOfSequences", numberOfSequences.toString()),
    )
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/x-ndjson"))
        .andReturn()

    private fun approveProcessedSequences(listOfSequencesToApprove: List<Number>): ResultActions = mockMvc.perform(
        MockMvcRequestBuilders.post("/approve-processed-data")
            .param("username", testUsername)
            .contentType(MediaType.APPLICATION_JSON_VALUE)
            .content("""{"sequenceIds":$listOfSequencesToApprove}"""),
    )
        .andExpect(status().isOk())

    private fun reviseSiloReadySequences(sequencesToRevise: Long): ResultActions = mockMvc.perform(
        MockMvcRequestBuilders.post("/revise")
            .param("username", testUsername)
            .contentType(MediaType.APPLICATION_JSON_VALUE)
            .param("sequenceId", sequencesToRevise.toString()),
    )
        .andExpect(status().isOk())

    private fun revokeSequences(listOfSequencesToRevoke: List<Number>): ResultActions = mockMvc.perform(
        MockMvcRequestBuilders.post("/revoke")
            .contentType(MediaType.APPLICATION_JSON_VALUE)
            .content("""{"sequenceIds":$listOfSequencesToRevoke}"""),
    )
        .andExpect(status().isOk())

    private fun confirmRevocation(listOfSequencesToConfirm: List<Number>): ResultActions = mockMvc.perform(
        MockMvcRequestBuilders.post("/confirm-revocation")
            .contentType(MediaType.APPLICATION_JSON_VALUE)
            .content("""{"sequenceIds":$listOfSequencesToConfirm}"""),
    )
        .andExpect(status().isOk())

    private fun prepareDataToSiloReady() {
        submitInitialData()
        submitProcessedData(awaitResponse(queryUnprocessedSequences(numberOfSequences)))
        approveProcessedSequences(allSequenceIds)
    }

    private fun awaitResponse(result: MvcResult): String {
        await().until {
            result.response.isCommitted
        }
        return result.response.contentAsString
    }

    private fun expectLinesInResponse(result: MvcResult, numberOfSequences: Int): String {
        awaitResponse(result)

        val sequenceCount = result.response.contentAsString.count {
            it == '\n'
        }
        assertThat(sequenceCount).isEqualTo(numberOfSequences)

        return result.response.contentAsString
    }

    private fun expectStatusInResponse(result: MvcResult, numberOfSequences: Int, expectedStatus: String): String {
        awaitResponse(result)

        val responseContent = result.response.contentAsString
        val statusCount = responseContent.split(expectedStatus).size - 1

        assertThat(statusCount).isEqualTo(numberOfSequences)

        return responseContent
    }

    companion object {
        private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer<Nothing>("postgres:latest")
            .apply {
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun setDataSourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }

        @AfterAll
        @JvmStatic
        fun afterAll() {
            postgres.stop()
        }

        @JvmStatic
        fun provideValidationScenarios() = listOf(
            Scenario(
                name = "Happy Path",
                inputData = processedInputDataFromFile("no_validation_errors"),
                expectedError = null as ValidationError?,
            ),
            Scenario(
                name = "Unknown field",
                inputData = processedInputDataFromFile("unknown_field"),
                expectedError = ValidationError(
                    id = 1,
                    missingRequiredFields = emptyList(),
                    fieldsWithTypeMismatch = emptyList(),
                    unknownFields = listOf("not_a_meta_data_field"),
                    genericError = emptyList(),
                ),
            ),
            Scenario(
                name = "Missing required field",
                inputData = processedInputDataFromFile("missing_required_field"),
                expectedError = ValidationError(
                    id = 1,
                    missingRequiredFields = listOf("date", "country"),
                    fieldsWithTypeMismatch = emptyList(),
                    unknownFields = emptyList(),
                    genericError = emptyList(),
                ),
            ),
            Scenario(
                name = "Wrong type field",
                inputData = processedInputDataFromFile("wrong_type_field"),
                expectedError = ValidationError(
                    id = 1,
                    missingRequiredFields = emptyList(),
                    fieldsWithTypeMismatch = listOf(
                        TypeMismatch(field = "date", shouldBeType = "date", fieldValue = "\"15.12.2002\""),
                    ),
                    unknownFields = emptyList(),
                    genericError = emptyList(),
                ),
            ),
            Scenario(
                name = "Invalid ID / Non-existing ID",
                inputData = processedInputDataFromFile("invalid_id"),
                expectedError = ValidationError(
                    id = 12,
                    missingRequiredFields = emptyList(),
                    fieldsWithTypeMismatch = emptyList(),
                    unknownFields = emptyList(),
                    genericError = listOf("SequenceId does not exist"),
                ),
            ),
            Scenario(
                name = "null in nullable field",
                inputData = processedInputDataFromFile("null_value"),
                expectedError = null,
            ),
        )

        data class Scenario<ErrorType>(
            val name: String,
            val inputData: String,
            val expectedError: ErrorType?,
        ) {
            override fun toString() = name
        }

        data class ValidationError(
            val id: Int,
            val missingRequiredFields: List<String>,
            val fieldsWithTypeMismatch: List<TypeMismatch>,
            val unknownFields: List<String>,
            val genericError: List<String>,
        )

        data class TypeMismatch(
            val field: String,
            val shouldBeType: String,
            val fieldValue: String,
        )

        fun processedInputDataFromFile(fileName: String): String = inputData[fileName] ?: error(
            "$fileName.json not found",
        )

        private val inputData: Map<String, String> by lazy {
            val fileMap = mutableMapOf<String, String>()

            val jsonResourceDirectory = "src/test/resources/processedInputData"

            val directory = File(jsonResourceDirectory)

            directory.listFiles { _, name -> name.endsWith(".json") }?.forEach { file ->
                val fileName = file.nameWithoutExtension
                val formattedJson = file.readText().replace("\n", "").replace("\r", "").replace(" ", "")
                fileMap[fileName] = formattedJson
            }

            fileMap
        }
    }
}
