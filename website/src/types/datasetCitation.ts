import z from 'zod';

export enum DatasetRecordType {
    loculus = 'Loculus',
}

export const datasetRecord = z.object({
    accession: z.string(),
    type: z.nativeEnum(DatasetRecordType),
});
export type DatasetRecord = z.infer<typeof datasetRecord>;
export const datasetRecords = z.array(datasetRecord);

export const dataset = z.object({
    datasetId: z.string(),
    datasetDOI: z.string().nullish(),
    datasetVersion: z.number(),
    name: z.string(),
    description: z.string().optional(),
    createdAt: z.string(),
    createdBy: z.string(),
});
export const datasets = z.array(dataset);
export type Dataset = z.infer<typeof dataset>;

export const citedByResult = z.object({
    years: z.array(z.number()),
    citations: z.array(z.number()),
});
export type CitedByResult = z.infer<typeof citedByResult>;

export const authorProfile = z.object({
    username: z.string(),
    firstName: z.string(),
    lastName: z.string(),
    emailDomain: z.string(),
    university: z.string().nullish(),
});
export type AuthorProfile = z.infer<typeof authorProfile>;
