package org.pathoplexus.backend.config

import org.pathoplexus.backend.api.NucleotideSequence

data class ReferenceGenome(
    val nucleotideSequences: List<ReferenceSequence>,
    val genes: List<ReferenceSequence>,
)

data class ReferenceSequence(
    val name: String,
    val sequence: NucleotideSequence,
)
