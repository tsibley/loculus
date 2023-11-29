import { sentenceCase, snakeCase } from 'change-case';
import { type Dispatch, type FC, Fragment, type SetStateAction, useMemo, useRef, useState } from 'react';

import { EditableDataRow, ProcessedDataRow } from './DataRow.tsx';
import type { Row } from './InputField.tsx';
import { getClientLogger } from '../../clientLogger.ts';
import { routes } from '../../routes.ts';
import { backendClientHooks } from '../../services/serviceHooks.ts';
import type { MetadataRecord, ProcessingAnnotationSourceType, SequenceEntryReview } from '../../types/backend.ts';
import type { ClientConfig } from '../../types/runtimeConfig.ts';
import { createAuthorizationHeader } from '../../utils/createAuthorizationHeader.ts';
import { getAccessionVersionString } from '../../utils/extractAccessionVersion.ts';
import { ConfirmationDialog } from '../ConfirmationDialog.tsx';
import { ManagedErrorFeedback, useErrorFeedbackState } from '../Submission/ManagedErrorFeedback.tsx';
import { withQueryProvider } from '../common/withQueryProvider.tsx';

type ReviewPageProps = {
    organism: string;
    clientConfig: ClientConfig;
    reviewData: SequenceEntryReview;
    accessToken: string;
};

const logger = getClientLogger('ReviewPage');

const InnerReviewPage: FC<ReviewPageProps> = ({ organism, reviewData, clientConfig, accessToken }: ReviewPageProps) => {
    const [editedMetadata, setEditedMetadata] = useState(mapMetadataToRow(reviewData));
    const [editedSequences, setEditedSequences] = useState(mapSequencesToRow(reviewData));

    const { errorMessage, isErrorOpen, openErrorFeedback, closeErrorFeedback } = useErrorFeedbackState();

    const dialogRef = useRef<HTMLDialogElement>(null);

    const { mutate: submitReviewedSequence } = useSubmitReviewedSequence(
        organism,
        clientConfig,
        accessToken,
        reviewData,
        openErrorFeedback,
    );

    const handleOpenConfirmationDialog = () => {
        if (dialogRef.current) {
            dialogRef.current.showModal();
        }
    };

    const submitReviewForAccessionVersion = async () => {
        const data = {
            accession: reviewData.accession,
            version: reviewData.version,
            data: {
                metadata: editedMetadata.reduce((prev, row) => ({ ...prev, [row.key]: row.value }), {}),
                unalignedNucleotideSequences: editedSequences.reduce(
                    (prev, row) => ({ ...prev, [row.key]: row.value }),
                    {},
                ),
            },
        };
        submitReviewedSequence(data);
    };

    const processedSequenceRows = useMemo(() => extractProcessedSequences(reviewData), [reviewData]);
    const processedInsertions = useMemo(() => extractInsertions(reviewData), [reviewData]);

    return (
        <>
            <ManagedErrorFeedback message={errorMessage} open={isErrorOpen} onClose={closeErrorFeedback} />

            <div className='flex items-center gap-4'>
                <button className='btn normal-case' onClick={handleOpenConfirmationDialog}>
                    Submit Review
                </button>

                <button
                    className='btn normal-case'
                    onClick={() => generateAndDownloadFastaFile(editedSequences, reviewData)}
                >
                    Download Sequences as fasta file
                </button>
            </div>

            <dialog ref={dialogRef} className='modal'>
                <ConfirmationDialog
                    dialogText='Do you really want to submit your review?'
                    onConfirmation={submitReviewForAccessionVersion}
                />
            </dialog>

            <table className='customTable'>
                <tbody className='w-full'>
                    <Subtitle title='Original Data' bold />
                    <EditableOriginalData
                        editedMetadata={editedMetadata.filter(({ key }) => key !== 'accession')}
                        setEditedMetadata={setEditedMetadata}
                    />
                    <EditableOriginalSequences
                        editedSequences={editedSequences}
                        setEditedSequences={setEditedSequences}
                    />

                    <Subtitle title='Processed Data' bold />
                    <ProcessedMetadata processedMetadata={reviewData.processedData.metadata} />
                    <ProcessedSequences
                        processedSequenceRows={processedSequenceRows}
                        sequenceType='unalignedNucleotideSequences'
                    />
                    <ProcessedSequences
                        processedSequenceRows={processedSequenceRows}
                        sequenceType='alignedNucleotideSequences'
                    />
                    <ProcessedSequences
                        processedSequenceRows={processedSequenceRows}
                        sequenceType='alignedAminoAcidSequences'
                    />
                    <ProcessedInsertions
                        processedInsertions={processedInsertions}
                        insertionType='nucleotideInsertions'
                    />
                    <ProcessedInsertions
                        processedInsertions={processedInsertions}
                        insertionType='aminoAcidInsertions'
                    />
                </tbody>
            </table>
        </>
    );
};

export const ReviewPage = withQueryProvider(InnerReviewPage);

function useSubmitReviewedSequence(
    organism: string,
    clientConfig: ClientConfig,
    accessToken: string,
    reviewData: SequenceEntryReview,
    openErrorFeedback: (message: string) => void,
) {
    return backendClientHooks(clientConfig).useSubmitReviewedSequence(
        { headers: createAuthorizationHeader(accessToken), params: { organism } },
        {
            onSuccess: async () => {
                await logger.info('Successfully submitted review ' + getAccessionVersionString(reviewData));
                location.href = routes.userSequencesPage(organism);
            },
            onError: async (error) => {
                const message = `Failed to submit review for ${getAccessionVersionString(
                    reviewData,
                )} with error '${JSON.stringify(error)})}'`;
                await logger.info(message);
                openErrorFeedback(message);
            },
        },
    );
}

function generateAndDownloadFastaFile(editedSequences: Row[], reviewData: SequenceEntryReview) {
    const accessionVersion = getAccessionVersionString(reviewData);
    const fileContent = editedSequences
        .map((sequence) => `>${accessionVersion}.${sequence.key}\n${sequence.value}\n\n`)
        .join();

    const blob = new Blob([fileContent], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);

    const a = document.createElement('a');
    a.href = url;
    a.download = `accessionVersion${accessionVersion}.fasta`;
    a.click();

    URL.revokeObjectURL(url);
}

type SubtitleProps = {
    title: string;
    bold?: boolean;
    customKey?: string;
};
const Subtitle: FC<SubtitleProps> = ({ title, bold, customKey }) => (
    <Fragment key={snakeCase(customKey ?? title) + '_fragment'}>
        <tr key={snakeCase(customKey ?? title) + '_spacing'} className='h-4' />
        <tr key={snakeCase(customKey ?? title)} className='subtitle'>
            <td className={bold ?? false ? 'font-semibold' : 'font-normal'} colSpan={3}>
                {title}
            </td>
        </tr>
    </Fragment>
);

type EditableOriginalDataProps = {
    editedMetadata: Row[];
    setEditedMetadata: Dispatch<SetStateAction<Row[]>>;
};
const EditableOriginalData: FC<EditableOriginalDataProps> = ({ editedMetadata, setEditedMetadata }) => (
    <>
        <Subtitle title='Metadata' />
        {editedMetadata.map((field) => (
            <EditableDataRow
                key={'raw_metadata' + field.key}
                row={field}
                onChange={(editedRow: Row) =>
                    setEditedMetadata((prevRows: Row[]) =>
                        prevRows.map((prevRow) =>
                            prevRow.key === editedRow.key ? { ...prevRow, value: editedRow.value } : prevRow,
                        ),
                    )
                }
            />
        ))}
    </>
);

type EditableOriginalSequencesProps = {
    editedSequences: Row[];
    setEditedSequences: Dispatch<SetStateAction<Row[]>>;
};
const EditableOriginalSequences: FC<EditableOriginalSequencesProps> = ({ editedSequences, setEditedSequences }) => (
    <>
        <Subtitle title='Unaligned nucleotide sequences' />
        {editedSequences.map((field) => (
            <EditableDataRow
                key={'raw_unaligned' + field.key}
                row={field}
                onChange={(editedRow: Row) =>
                    setEditedSequences((prevRows: Row[]) =>
                        prevRows.map((prevRow) =>
                            prevRow.key === editedRow.key ? { ...prevRow, value: editedRow.value } : prevRow,
                        ),
                    )
                }
            />
        ))}
    </>
);

type ProcessedMetadataProps = {
    processedMetadata: MetadataRecord;
};
const ProcessedMetadata: FC<ProcessedMetadataProps> = ({ processedMetadata }) => (
    <>
        <Subtitle title='Metadata' customKey='preprocessing_metadata' />
        {Object.entries(processedMetadata).map(([key, value]) => (
            <ProcessedDataRow key={'processed' + key} row={{ key, value: value.toString() }} />
        ))}
    </>
);

type ProcessedSequencesProps = {
    processedSequenceRows: ReturnType<typeof extractProcessedSequences>;
    sequenceType: keyof ReturnType<typeof extractProcessedSequences>;
};
const ProcessedSequences: FC<ProcessedSequencesProps> = ({ processedSequenceRows, sequenceType }) => (
    <>
        <Subtitle key={`preprocessing_sequences_${sequenceType}`} title={sentenceCase(sequenceType)} />
        {Object.entries(processedSequenceRows[sequenceType]).map(([key, value]) => (
            <ProcessedDataRow key={`processed_${sequenceType}_${key}`} row={{ key, value }} />
        ))}
    </>
);

type ProcessedInsertionsProps = {
    processedInsertions: ReturnType<typeof extractInsertions>;
    insertionType: keyof ReturnType<typeof extractInsertions>;
};
const ProcessedInsertions: FC<ProcessedInsertionsProps> = ({ processedInsertions, insertionType }) => (
    <>
        <Subtitle key={`processed_insertions_${insertionType}`} title={sentenceCase(insertionType)} />
        {Object.entries(processedInsertions[insertionType]).map(([key, value]) => (
            <ProcessedDataRow key={`processed_${insertionType}_${key}`} row={{ key, value: value.join(',') }} />
        ))}
    </>
);

const mapMetadataToRow = (reviewData: SequenceEntryReview): Row[] =>
    Object.entries(reviewData.originalData.metadata).map(([key, value]) => ({
        key,
        initialValue: value.toString(),
        value: value.toString(),
        ...mapErrorsAndWarnings(reviewData, key, 'Metadata'),
    }));

const mapSequencesToRow = (reviewData: SequenceEntryReview): Row[] =>
    Object.entries(reviewData.originalData.unalignedNucleotideSequences).map(([key, value]) => ({
        key,
        initialValue: value.toString(),
        value: value.toString(),
        ...mapErrorsAndWarnings(reviewData, key, 'NucleotideSequence'),
    }));

const extractProcessedSequences = (reviewData: SequenceEntryReview) => ({
    unalignedNucleotideSequences: reviewData.processedData.unalignedNucleotideSequences,
    alignedNucleotideSequences: reviewData.processedData.alignedNucleotideSequences,
    alignedAminoAcidSequences: reviewData.processedData.alignedAminoAcidSequences,
});

const extractInsertions = (reviewData: SequenceEntryReview) => ({
    nucleotideInsertions: reviewData.processedData.nucleotideInsertions,
    aminoAcidInsertions: reviewData.processedData.aminoAcidInsertions,
});

const mapErrorsAndWarnings = (
    reviewData: SequenceEntryReview,
    key: string,
    type: ProcessingAnnotationSourceType,
): { errors: string[]; warnings: string[] } => ({
    errors: (reviewData.errors ?? [])
        .filter((error) => error.source.find((source) => source.name === key && source.type === type) !== undefined)
        .map((error) => error.message),
    warnings: (reviewData.warnings ?? [])
        .filter((warning) => warning.source.find((source) => source.name === key && source.type === type) !== undefined)
        .map((warning) => warning.message),
});
