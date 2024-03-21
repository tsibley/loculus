import { AxiosError } from 'axios';
import { type FC } from 'react';

import { CitationPlot } from './CitationPlot';
import { getClientLogger } from '../../clientLogger';
import { datasetCitationClientHooks } from '../../services/serviceHooks';
import { type DatasetRecord, type Dataset, type CitedByResult, DatasetRecordType } from '../../types/datasetCitation';
import type { ClientConfig } from '../../types/runtimeConfig';
import { createAuthorizationHeader } from '../../utils/createAuthorizationHeader';
import { displayConfirmationDialog } from '../ConfirmationDialog.tsx';
import { ManagedErrorFeedback, useErrorFeedbackState } from '../common/ManagedErrorFeedback';
import { withQueryProvider } from '../common/withQueryProvider.tsx';

const logger = getClientLogger('DatasetItem');

type DatasetRecordsTableProps = {
    datasetRecords: DatasetRecord[];
};

const DatasetRecordsTable: FC<DatasetRecordsTableProps> = ({ datasetRecords }) => {
    if (datasetRecords.length === 0) {
        return null;
    }

    const accessionOutlink = {
        [DatasetRecordType.loculus]: (acc: string) => `/seq/${acc}`,
    };

    return (
        <table className='table-auto w-full max-w-xl'>
            <thead>
                <tr>
                    <th className='w-1/2 text-left font-medium'>Accession</th>
                    <th className='w-1/2 text-left font-medium'>Source</th>
                </tr>
            </thead>
            <tbody>
                {datasetRecords.map((datasetRecord, index) => {
                    return (
                        <tr key={`accessionData-${index}`}>
                            <td className='text-left'>
                                <a href={accessionOutlink[datasetRecord.type](datasetRecord.accession)} target='_blank'>
                                    {datasetRecord.accession}
                                </a>
                            </td>
                            <td className='text-left'>{datasetRecord.type as string}</td>
                        </tr>
                    );
                })}
            </tbody>
        </table>
    );
};

type DatasetItemProps = {
    clientConfig: ClientConfig;
    accessToken: string;
    dataset: Dataset;
    datasetRecords: DatasetRecord[];
    citedByData: CitedByResult;
    isAdminView?: boolean;
};

const DatasetItemInner: FC<DatasetItemProps> = ({
    clientConfig,
    accessToken,
    dataset,
    datasetRecords,
    citedByData,
    isAdminView = false,
}) => {
    const { errorMessage, isErrorOpen, openErrorFeedback, closeErrorFeedback } = useErrorFeedbackState();

    const { mutate: createDatasetDOI } = useCreateDatasetDOIAction(
        clientConfig,
        accessToken,
        dataset.datasetId,
        dataset.datasetVersion,
        openErrorFeedback,
    );

    const handleCreateDOI = async () => {
        createDatasetDOI(undefined);
    };

    const getCrossRefUrl = () => {
        return `https://search.crossref.org/search/works?from_ui=yes&q=${dataset.datasetDOI}`;
    };

    const formatDate = (date?: string) => {
        if (date === undefined) {
            return 'N/A';
        }
        const dateObj = new Date(date);
        return dateObj.toLocaleDateString('en-US');
    };

    const renderDOI = () => {
        if (dataset.datasetDOI !== undefined && dataset.datasetDOI !== null) {
            return `https://doi.org/${dataset.datasetDOI}`;
        }

        if (!isAdminView) {
            return 'N/A';
        }

        return (
            <a
                className='mr-4 cursor-pointer font-medium text-blue-600 dark:text-blue-500 hover:underline'
                onClick={() =>
                    displayConfirmationDialog({
                        dialogText: `Are you sure you want to create a DOI for this version of your dataset?`,
                        onConfirmation: handleCreateDOI,
                    })
                }
            >
                Generate a DOI
            </a>
        );
    };

    return (
        <div className='flex flex-col items-left'>
            <ManagedErrorFeedback message={errorMessage} open={isErrorOpen} onClose={closeErrorFeedback} />
            <div>
                <h1 className='text-2xl font-semibold pb-4'>{dataset.name}</h1>
            </div>
            <div className='flex flex-col'>
                <div className='flex flex-row py-1.5'>
                    <p className='mr-8 w-[120px] text-gray-500 text-right'>Description</p>
                    <p className='text'>{dataset.description ?? 'N/A'}</p>
                </div>
                <div className='flex flex-row py-1.5'>
                    <p className='mr-8 w-[120px] text-gray-500 text-right'>Version</p>
                    <p className='text'>{dataset.datasetVersion}</p>
                </div>
                <div className='flex flex-row py-1.5'>
                    <p className='mr-8 w-[120px] text-gray-500 text-right'>Created date</p>
                    <p className='text'>{formatDate(dataset.createdAt)}</p>
                </div>
                <div className='flex flex-row py-1.5'>
                    <p className='mr-8 w-[120px] text-gray-500 text-right'>DOI</p>
                    {renderDOI()}
                </div>
                <div className='flex flex-row py-1.5'>
                    <p className='mr-8 w-[120px] text-gray-500 text-right'>Total citations</p>
                    {dataset.datasetDOI === undefined || dataset.datasetDOI === null ? (
                        <p className='text'>Cited By 0</p>
                    ) : (
                        <a
                            className='mr-4 cursor-pointer font-medium text-blue-600 dark:text-blue-500 hover:underline'
                            href={getCrossRefUrl()}
                            target='_blank'
                        >
                            Cited By 0
                        </a>
                    )}
                </div>
                <div className='flex flex-row'>
                    <p className='mr-0 w-[120px]'></p>
                    <div className='ml-4'>
                        <CitationPlot citedByData={citedByData} />
                        <p className='text-sm text-center text-gray-500 my-4 ml-8 max-w-64'>
                            Number of times this dataset has been cited by a publication
                        </p>
                    </div>
                </div>
            </div>
            <div className='flex flex-col my-4'>
                <p className='text-xl py-4 font-semibold'>Sequences</p>
                <DatasetRecordsTable datasetRecords={datasetRecords} />
            </div>
        </div>
    );
};

function useCreateDatasetDOIAction(
    clientConfig: ClientConfig,
    accessToken: string,
    datasetId: string,
    datasetVersion: number,
    onError: (message: string) => void,
) {
    return datasetCitationClientHooks(clientConfig).useCreateDatasetDOI(
        { headers: createAuthorizationHeader(accessToken), params: { datasetId, datasetVersion } },
        {
            onSuccess: async () => {
                await logger.info(
                    `Successfully created dataset DOI for datasetId: ${datasetId}, version ${datasetVersion}`,
                );
                location.reload();
            },
            onError: async (error) => {
                await logger.info(`Failed to create dataset DOI with error: '${JSON.stringify(error)})}'`);
                if (error instanceof AxiosError) {
                    if (error.response?.data !== undefined) {
                        onError(
                            `Failed to update dataset. ${error.response.data?.title}. ${error.response.data?.detail}`,
                        );
                    }
                }
            },
        },
    );
}

export const DatasetItem = withQueryProvider(DatasetItemInner);
