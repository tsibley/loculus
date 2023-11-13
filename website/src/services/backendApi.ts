import { makeApi, makeEndpoint, makeParameters } from '@zodios/core';
import z from 'zod';

import {
    submissionIdMapping,
    problemDetail,
    accessions,
    accessionReview,
    sequenceEntryStatus,
    accessionVersionsObject,
    submitFiles,
    unprocessedData,
} from '../types/backend.ts';

const usernameParameters = makeParameters([
    {
        name: 'username',
        type: 'Query',
        schema: z.string(),
    },
]);

const submitEndpoint = makeEndpoint({
    method: 'post',
    path: '/submit',
    alias: 'submit',
    requestFormat: 'form-data',
    parameters: [
        {
            name: 'data',
            type: 'Body',
            schema: submitFiles,
        },
    ],
    response: z.array(submissionIdMapping),
    errors: [
        { status: 'default', schema: problemDetail },
        { status: 400, schema: problemDetail },
        { status: 422, schema: problemDetail },
    ],
});

const reviseEndpoint = makeEndpoint({
    method: 'post',
    path: '/revise',
    alias: 'revise',
    requestFormat: 'form-data',
    parameters: [
        {
            name: 'data',
            type: 'Body',
            schema: submitFiles,
        },
    ],
    response: z.array(submissionIdMapping),
    errors: [
        { status: 'default', schema: problemDetail },
        { status: 400, schema: problemDetail },
        { status: 422, schema: problemDetail },
    ],
});

const getDataToReviewEndpoint = makeEndpoint({
    method: 'get',
    path: '/get-data-to-review/:accession/:version',
    alias: 'getDataToReview',
    parameters: usernameParameters,
    response: accessionReview,
});

const revokeSequencesEndpoint = makeEndpoint({
    method: 'post',
    path: '/revoke',
    alias: 'revokeSequences',
    parameters: [
        ...usernameParameters,
        {
            name: 'accessions',
            type: 'Body',
            schema: accessions,
        },
    ],
    response: z.array(sequenceEntryStatus),
    errors: [
        { status: 'default', schema: problemDetail },
        { status: 422, schema: problemDetail },
    ],
});

const submitReviewedSequenceEndpoint = makeEndpoint({
    method: 'post',
    path: '/submit-reviewed-sequence',
    alias: 'submitReviewedSequence',
    parameters: [
        ...usernameParameters,
        {
            name: 'data',
            type: 'Body',
            schema: unprocessedData,
        },
    ],
    response: z.never(),
});

const getSequencesOfUserEndpoint = makeEndpoint({
    method: 'get',
    path: '/get-sequences-of-user',
    alias: 'getSequencesOfUser',
    parameters: usernameParameters,
    response: z.array(sequenceEntryStatus),
});

const approveProcessedDataEndpoint = makeEndpoint({
    method: 'post',
    path: '/approve-processed-data',
    alias: 'approveProcessedData',
    parameters: [
        ...usernameParameters,
        {
            name: 'data',
            type: 'Body',
            schema: accessionVersionsObject,
        },
    ],
    response: z.never(),
    errors: [{ status: 'default', schema: problemDetail }],
});

const deleteSequencesEndpoint = makeEndpoint({
    method: 'delete',
    path: '/delete-sequences',
    alias: 'deleteSequences',
    parameters: [
        ...usernameParameters,
        {
            name: 'accessionVersions',
            type: 'Body',
            schema: accessionVersionsObject,
        },
    ],
    response: z.never(),
    errors: [{ status: 'default', schema: problemDetail }],
});

const confirmRevocationEndpoint = makeEndpoint({
    method: 'post',
    path: '/confirm-revocation',
    alias: 'confirmRevocation',
    parameters: [
        ...usernameParameters,
        {
            name: 'accessionVersions',
            type: 'Body',
            schema: accessionVersionsObject,
        },
    ],
    response: z.never(),
    errors: [
        { status: 'default', schema: problemDetail },
        { status: 422, schema: problemDetail },
    ],
});

const extractUnprocessedDataEndpoint = makeEndpoint({
    method: 'post',
    path: '/extract-unprocessed-data',
    alias: 'extractUnprocessedData',
    parameters: [
        {
            name: 'numberOfSequenceEntries',
            type: 'Query',
            schema: z.number(),
        },
    ],
    response: z.union([z.string(), unprocessedData]),
});

const submitProcessedDataEndpoint = makeEndpoint({
    method: 'post',
    path: '/submit-processed-data',
    alias: 'submitProcessedData',
    parameters: [
        {
            name: 'data',
            type: 'Body',
            schema: z.string(),
        },
    ],
    response: z.never(),
    errors: [
        { status: 'default', schema: problemDetail },
        { status: 422, schema: problemDetail },
    ],
});

export const backendApi = makeApi([
    submitEndpoint,
    reviseEndpoint,
    getDataToReviewEndpoint,
    revokeSequencesEndpoint,
    submitReviewedSequenceEndpoint,
    getSequencesOfUserEndpoint,
    approveProcessedDataEndpoint,
    deleteSequencesEndpoint,
    confirmRevocationEndpoint,
    extractUnprocessedDataEndpoint,
    submitProcessedDataEndpoint,
]);
