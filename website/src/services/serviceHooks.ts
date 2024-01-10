import { Zodios } from '@zodios/core';
import { ZodiosHooks, type ZodiosHooksInstance } from '@zodios/react';

import { backendApi } from './backendApi.ts';
import { lapisApi } from './lapisApi.ts';
import type { LapisBaseRequest } from '../types/lapis.ts';
import type { ClientConfig } from '../types/runtimeConfig.ts';
import { fastaEntries } from '../utils/parseFasta.ts';
import { isAlignedSequence, isUnalignedSequence, type SequenceType } from '../utils/sequenceTypeHelpers.ts';

export function backendClientHooks(clientConfig: ClientConfig) {
    return new ZodiosHooks('loculus', new Zodios(clientConfig.backendUrl, backendApi));
}

export function lapisClientHooks(lapisUrl: string) {
    const zodiosHooks = new ZodiosHooks('lapis', new Zodios(lapisUrl, lapisApi, { transform: false }));
    return {
        zodiosHooks,
        utilityHooks: {
            useGetSequence(accessionVersion: string, sequenceType: SequenceType, isMultiSegmented: boolean) {
                const { data, error, isLoading } = getSequenceHook(
                    zodiosHooks,
                    { accessionVersion },
                    sequenceType,
                    isMultiSegmented,
                );

                if (data === undefined) {
                    return { data, error, isLoading };
                }

                const parseResult = fastaEntries.safeParse(data);

                if (parseResult.success) {
                    return {
                        data: parseResult.data[0],
                        error,
                        isLoading,
                    };
                }
                return {
                    data: undefined,
                    error: parseResult.error,
                    isLoading,
                };
            },
        },
    };
}

function getSequenceHook(
    hooks: ZodiosHooksInstance<typeof lapisApi>,
    request: LapisBaseRequest,
    sequenceType: SequenceType,
    isMultiSegmented: boolean,
) {
    if (isUnalignedSequence(sequenceType)) {
        return isMultiSegmented
            ? hooks.useUnalignedNucleotideSequencesMultiSegment(request, { params: { segment: sequenceType.name } })
            : hooks.useUnalignedNucleotideSequences(request);
    }

    if (isAlignedSequence(sequenceType)) {
        return isMultiSegmented
            ? hooks.useAlignedNucleotideSequencesMultiSegment(request, { params: { segment: sequenceType.name } })
            : hooks.useAlignedNucleotideSequences(request);
    }

    return hooks.useAlignedAminoAcidSequences(request, { params: { gene: sequenceType.name } });
}
