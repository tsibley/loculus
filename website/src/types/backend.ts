import z from 'zod';

export const receivedStatus = 'RECEIVED';
export const inProcessingStatus = 'IN_PROCESSING';
export const hasErrorsStatus = 'HAS_ERRORS';
export const awaitingApprovalStatus = 'AWAITING_APPROVAL';
export const approvedForReleaseStatus = 'APPROVED_FOR_RELEASE';
export const awaitingApprovalForRevocationStatus = 'AWAITING_APPROVAL_FOR_REVOCATION';

export const sequenceEntryStatusNames = z.union([
    z.literal(receivedStatus),
    z.literal(inProcessingStatus),
    z.literal(hasErrorsStatus),
    z.literal(awaitingApprovalStatus),
    z.literal(approvedForReleaseStatus),
    z.literal(awaitingApprovalForRevocationStatus),
]);
export type SequenceEntryStatusNames = z.infer<typeof sequenceEntryStatusNames>;
const statusThatAllowsEditing = z.union([z.literal(hasErrorsStatus), z.literal(awaitingApprovalStatus)]);

const processingAnnotationSourceType = z.union([z.literal('Metadata'), z.literal('NucleotideSequence')]);
export type ProcessingAnnotationSourceType = z.infer<typeof processingAnnotationSourceType>;

const processingAnnotation = z.object({
    source: z.array(
        z.object({
            name: z.string(),
            type: processingAnnotationSourceType,
        }),
    ),
    message: z.string(),
});
export type ProcessingAnnotation = z.infer<typeof processingAnnotation>;

const unprocessedMetadataRecord = z.record(z.string());
export type UnprocessedMetadataRecord = z.infer<typeof unprocessedMetadataRecord>;

export const metadataField = z.union([z.string(), z.number(), z.date(), z.null()]);
export type MetadataField = z.infer<typeof metadataField>;

const metadataRecord = z.record(metadataField);
export type MetadataRecord = z.infer<typeof metadataRecord>;

export const accession = z.string();
export type Accession = z.infer<typeof accession>;

export const accessions = z.object({
    accessions: z.array(accession),
});

export const accessionVersion = z.object({
    accession,
    version: z.number(),
});
export type AccessionVersion = z.infer<typeof accessionVersion>;

export const accessionVersionsObject = z.object({
    accessionVersions: z.array(accessionVersion),
});

export const openDataUseTermsType = 'OPEN';
export const restrictedDataUseTermsType = 'RESTRICTED';

export const dataUseTermsTypes = [restrictedDataUseTermsType, openDataUseTermsType] as const;

export type DataUseTermsType = typeof openDataUseTermsType | typeof restrictedDataUseTermsType;

export const dataUseTerms = z.union([
    z.object({
        type: z.literal(restrictedDataUseTermsType),
        restrictedUntil: z.string(),
    }),
    z.object({
        type: z.literal(openDataUseTermsType),
    }),
]);

export type DataUseTerms = z.infer<typeof dataUseTerms>;

export const dataUseTermsHistoryEntry = z.object({
    accession,
    changeDate: z.string(),
    dataUseTerms,
    userName: z.string(),
});

export type DataUseTermsHistoryEntry = z.infer<typeof dataUseTermsHistoryEntry>;

export const sequenceEntryStatus = accessionVersion.merge(
    z.object({
        status: sequenceEntryStatusNames,
        submissionId: z.string(),
        isRevocation: z.boolean(),
        dataUseTerms,
    }),
);

export type SequenceEntryStatus = z.infer<typeof sequenceEntryStatus>;

export const statusCounts = z.record(z.number()).refine(
    (entry) => {
        return Object.keys(entry).every((key) => sequenceEntryStatusNames.safeParse(key).success);
    },
    { message: 'Invalid status name in statusCounts' },
);

export const getSequencesResponse = z.object({
    sequenceEntries: z.array(sequenceEntryStatus),
    statusCounts,
});
export type GetSequencesResponse = z.infer<typeof getSequencesResponse>;

export const submissionIdMapping = accessionVersion.merge(
    z.object({
        submissionId: z.string(),
    }),
);
export type SubmissionIdMapping = z.infer<typeof submissionIdMapping>;

export const unprocessedData = accessionVersion.merge(
    z.object({
        data: z.object({
            metadata: unprocessedMetadataRecord,
            unalignedNucleotideSequences: z.record(z.string()),
        }),
    }),
);
export type UnprocessedData = z.infer<typeof unprocessedData>;

export const sequenceEntryToEdit = accessionVersion.merge(
    z.object({
        status: statusThatAllowsEditing,
        errors: z.array(processingAnnotation).nullable(),
        warnings: z.array(processingAnnotation).nullable(),
        originalData: z.object({
            metadata: unprocessedMetadataRecord,
            unalignedNucleotideSequences: z.record(z.string()),
        }),
        processedData: z.object({
            metadata: metadataRecord,
            unalignedNucleotideSequences: z.record(z.string()),
            alignedNucleotideSequences: z.record(z.string()),
            nucleotideInsertions: z.record(z.array(z.string())),
            alignedAminoAcidSequences: z.record(z.string()),
            aminoAcidInsertions: z.record(z.array(z.string())),
        }),
    }),
);
export type SequenceEntryToEdit = z.infer<typeof sequenceEntryToEdit>;

export const uploadFiles = z.object({
    metadataFile: z.instanceof(File),
    sequenceFile: z.instanceof(File),
});

export const submitFiles = uploadFiles.merge(
    z.object({
        groupName: z.string(),
        dataUseTermsType: z.enum(dataUseTermsTypes),
        restrictedUntil: z.string().nullable(),
    }),
);

export const problemDetail = z.object({
    type: z.string(),
    title: z.string(),
    status: z.number(),
    detail: z.string(),
    instance: z.string().optional(),
});
export type ProblemDetail = z.infer<typeof problemDetail>;

export const group = z.object({
    groupName: z.string(),
});
export type Group = z.infer<typeof group>;

export const groupDetails = z.object({
    groupName: z.string(),
    users: z.array(
        z.object({
            name: z.string(),
        }),
    ),
});

export const pageQuery = z.object({
    page: z.number(),
    size: z.number(),
});

export type PageQuery = z.infer<typeof pageQuery>;
