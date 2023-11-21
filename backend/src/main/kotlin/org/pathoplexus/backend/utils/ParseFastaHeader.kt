package org.pathoplexus.backend.utils

import org.pathoplexus.backend.config.ReferenceGenome
import org.pathoplexus.backend.controller.BadRequestException
import org.pathoplexus.backend.model.SegmentName
import org.pathoplexus.backend.model.SubmissionId
import org.springframework.stereotype.Service

@Service
class ParseFastaHeader(private val referenceGenome: ReferenceGenome) {
    fun parse(submissionId: String): Pair<SubmissionId, SegmentName> {
        if (referenceGenome.nucleotideSequences.size == 1) {
            return Pair(submissionId, "main")
        }

        val lastDelimiter = submissionId.lastIndexOf("_")
        if (lastDelimiter == -1) {
            throw BadRequestException(
                "The FASTA header $submissionId does not contain the segment name. Please provide the" +
                    " segment name in the format <submissionId>_<segment name>",
            )
        }
        return Pair(submissionId.substring(0, lastDelimiter), submissionId.substring(lastDelimiter + 1))
    }
}
