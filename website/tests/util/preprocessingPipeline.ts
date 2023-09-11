export const fakeProcessingPipeline = async ({ sequenceId, error }: { sequenceId: number; error: boolean }) => {
    const body = {
        sequenceId,
        version: 1,
        errors: error ? [{ source: { fieldName: 'host', type: 'metadata' }, message: 'Not this kind of host' }] : [],
        warnings: [{ source: { fieldName: 'all', type: 'all' }, message: '"There is no warning"-warning' }],
        data: {
            metadata: {
                date: '2002-12-15',
                host: 'google.com',
                region: 'Europe',
                country: 'Spain',
                division: 'Schaffhausen',
            },
            unalignedNucleotideSequences: {
                main: 'AATTCC...',
            },
            alignedNucleotideSequences: {
                main: 'NNNNNAATTCC...',
            },
            nucleotideInsertions: {
                insertions: [],
            },
            alignedAminoAcidSequences: {
                S: 'XXMSR...',
                ORF1a: '...',
            },
            aminoAcidInsertions: {
                S: [],
            },
        },
    };

    const response = await fetch('http://localhost:8079/submit-processed-data', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-ndjson',
        },
        body: JSON.stringify(body),
    });
    if (!response.ok) {
        throw new Error(`Unexpected response: ${response.statusText}`);
    }
};
