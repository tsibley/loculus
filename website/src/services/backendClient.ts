import { backendApi } from './backendApi.ts';
import { ZodiosWrapperClient } from './zodiosWrapperClient.ts';
import { getRuntimeConfig } from '../config.ts';
import { getInstanceLogger } from '../logger.ts';
import { createAuthorizationHeader } from '../utils/createAuthorizationHeader.ts';

export class BackendClient extends ZodiosWrapperClient<typeof backendApi> {
    /** Somehow Typescript's type inference currently doesn't work properly in Astro files */
    public readonly astroFileTypeHelpers = {
        getSequenceEntriesOfUser: (organism: string, token: string) =>
            this.call('getSequencesOfUser', {
                params: { organism },
                headers: createAuthorizationHeader(token),
            }),

        getDataToReview: (organism: string, token: string, accession: string, version: string | number) =>
            this.call('getDataToReview', {
                params: { accession, version, organism },
                headers: createAuthorizationHeader(token),
            }),
    };

    public static create(
        backendUrl: string = getRuntimeConfig().serverSide.backendUrl,
        logger = getInstanceLogger('serverSideBackendClient'),
    ) {
        return new BackendClient(backendUrl, backendApi, (axiosError) => axiosError.data, logger, 'backend');
    }
}
