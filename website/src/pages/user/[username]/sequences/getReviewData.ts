import { getConfig } from '../../../../config';
import { logger } from '../../../../logger';

type PangoLineage = string;

export type SequenceReview = {
    sequenceId: number;
    processing_errors: ProcessingAnnotation[];
    processing_warnings: ProcessingAnnotation[];
    data: {
        metadata: {
            date: string;
            host: string;
            region: string;
            country: string;
            division: string;
            pangolinLineage: PangoLineage;
        };
        unalignedNucleotideSequences: {
            main: string;
        };
    };
};

export type ProcessingAnnotation = {
    source: {
        name: string;
        type: string;
    };
    message: string;
};

export const getReviewData = async (name: string): Promise<SequenceReview[]> => {
    try {
        const config = getConfig();
        const mySequencesQuery = `${config.backendUrl}/get-data-to-review?submitter=${name}&numberOfSequences=10`;

        const mySequencesResponse = await fetch(mySequencesQuery, {
            method: 'GET',
            headers: {
                accept: 'application/x-ndjson',
            },
        });

        if (!mySequencesResponse.ok) {
            logger.error(`Failed to fetch user sequences with status ${mySequencesResponse.status}`);
            return [];
        }

        const sequenceReviews: SequenceReview[] = [];

        const ndjsonText = await mySequencesResponse.text();
        const ndjsonLines = ndjsonText.split('\n');

        ndjsonLines.forEach((line) => {
            if (line.trim() === '') {
                return;
            }

            try {
                const sequenceReview = JSON.parse(line) as SequenceReview;
                sequenceReviews.push(sequenceReview);
            } catch (error) {
                logger.error(`Failed to parse JSON line: ${error}`);
            }
        });

        return sequenceReviews;
    } catch (error) {
        logger.error(`Failed to fetch user sequences with error '${(error as Error).message}'`);
        return [];
    }
};
