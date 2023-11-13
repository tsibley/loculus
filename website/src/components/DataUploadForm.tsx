import { CircularProgress, TextField } from '@mui/material';
import { isErrorFromAlias } from '@zodios/core';
import type { AxiosError } from 'axios';
import { type ChangeEvent, type FormEvent, useState } from 'react';

import { withQueryProvider } from './common/withQueryProvider.tsx';
import { backendApi } from '../services/backendApi.ts';
import { backendClientHooks } from '../services/serviceHooks.ts';
import type { SubmissionIdMapping } from '../types/backend.ts';
import type { ClientConfig } from '../types/runtimeConfig.ts';

type Action = 'submit' | 'revise';

type DataUploadFormProps = {
    clientConfig: ClientConfig;
    action: Action;
    onSuccess: (value: SubmissionIdMapping[]) => void;
    onError: (message: string) => void;
};

const InnerDataUploadForm = ({ clientConfig, action, onSuccess, onError }: DataUploadFormProps) => {
    const [username, setUsername] = useState('');
    const [metadataFile, setMetadataFile] = useState<File | null>(null);
    const [sequenceFile, setSequenceFile] = useState<File | null>(null);

    const { submit, revise, isLoading } = useSubmitFiles(clientConfig, onSuccess, onError);

    const handleLoadExampleData = async () => {
        const { metadataFileContent, revisedMetadataFileContent, sequenceFileContent } = getExampleData();

        const exampleMetadataContent = action === `submit` ? metadataFileContent : revisedMetadataFileContent;

        const metadataFile = createTempFile(exampleMetadataContent, 'text/tab-separated-values', 'metadata.tsv');
        const sequenceFile = createTempFile(sequenceFileContent, 'application/octet-stream', 'sequences.fasta');

        setUsername('testuser');
        setMetadataFile(metadataFile);
        setSequenceFile(sequenceFile);
    };

    const handleSubmit = async (event: FormEvent) => {
        event.preventDefault();

        if (!metadataFile || !sequenceFile) {
            onError('Please select both a metadata and sequences file');
            return;
        }

        switch (action) {
            case 'submit':
                submit({ username, metadataFile, sequenceFile });
                break;
            case 'revise':
                revise({ username, metadataFile, sequenceFile });
                break;
        }
    };

    return (
        <form onSubmit={handleSubmit} className='p-6 space-y-6 max-w-md w-full'>
            <TextField
                variant='outlined'
                margin='dense'
                size='small'
                required
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                disabled={false}
                InputLabelProps={{
                    shrink: true,
                }}
                label={username === '' ? undefined : 'Username:'}
                placeholder={username !== '' ? undefined : 'Username:'}
            />

            <TextField
                variant='outlined'
                margin='dense'
                label='Metadata File:'
                placeholder='Metadata File:'
                size='small'
                type='file'
                onChange={(event: ChangeEvent<HTMLInputElement>) => setMetadataFile(event.target.files?.[0] || null)}
                disabled={false}
                InputLabelProps={{
                    shrink: true,
                }}
            />

            <TextField
                variant='outlined'
                margin='dense'
                label='Sequences File:'
                placeholder='Sequences File:'
                size='small'
                type='file'
                onChange={(event: ChangeEvent<HTMLInputElement>) => setSequenceFile(event.target.files?.[0] || null)}
                disabled={false}
                InputLabelProps={{
                    shrink: true,
                }}
            />
            <div className='flex gap-4'>
                <button type='button' className='px-4 py-2 btn normal-case ' onClick={handleLoadExampleData}>
                    Load Example Data
                </button>

                <button className='px-4 py-2 btn normal-case w-1/5' disabled={isLoading} type='submit'>
                    {isLoading ? <CircularProgress size={20} color='primary' /> : 'Submit'}
                </button>
            </div>
        </form>
    );
};

export const DataUploadForm = withQueryProvider(InnerDataUploadForm);

function useSubmitFiles(
    clientConfig: ClientConfig,
    onSuccess: (value: SubmissionIdMapping[]) => void,
    onError: (message: string) => void,
) {
    const hooks = backendClientHooks(clientConfig);
    const submit = hooks.useSubmit({}, { onSuccess, onError: handleError(onError, 'submit') });
    const revise = hooks.useRevise({}, { onSuccess, onError: handleError(onError, 'revise') });

    return {
        submit: submit.mutate,
        revise: revise.mutate,
        isLoading: submit.isLoading || revise.isLoading,
    };
}

function handleError(onError: (message: string) => void, action: Action) {
    return (error: unknown | AxiosError) => {
        if (isErrorFromAlias(backendApi, action, error)) {
            switch (error.response.status) {
                case 400:
                    onError('The submitted files were invalid: ' + error.response.data.detail);
                    return;
                case 422:
                    onError('The submitted file content was invalid: ' + error.response.data.detail);
                    return;
                default:
                    onError(error.response.data.title + ': ' + error.response.data.detail);
                    return;
            }
        }
        onError('Received unexpected message from backend: ' + (error?.toString() ?? JSON.stringify(error)));
    };
}

function getExampleData() {
    return {
        metadataFileContent: `
submissionId	date	region	country	division	host
custom0	2020-12-26	Europe	Switzerland	Bern	Homo sapiens
custom1	2020-12-15	Europe	Switzerland	Schaffhausen	Homo sapiens
custom2	2020-12-02	Europe	Switzerland	Bern	Homo sapiens
custom3	2020-12-02	Europe	Switzerland	Bern	Homo sapiens`,
        revisedMetadataFileContent: `
accession	submissionId	date	region	country	division	host
1	custom0	2020-12-26	Europe	Switzerland	Bern	Homo sapiens
2	custom1	2020-12-15	Europe	Switzerland	Schaffhausen	Homo sapiens
3	custom2	2020-12-02	Europe	Switzerland	Bern	Homo sapiens
4	custom3	2020-12-02	Europe	Switzerland	Bern	Homo sapiens`,
        sequenceFileContent: `
>custom0
ACTG
>custom1
ACTG
>custom2
ACTG
>custom3
ACTG`,
    };
}

function createTempFile(content: BlobPart, mimeType: any, fileName: string) {
    const blob = new Blob([content], { type: mimeType });
    return new File([blob], fileName, { type: mimeType });
}
