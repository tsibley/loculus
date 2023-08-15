import { render, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import React from 'react';
import { describe, expect, test } from 'vitest';

import { SubmissionForm } from './SubmissionForm';
import type { HeaderId } from '../../types';
import { mockRequest, testConfig, testuser } from '../vitest.setup';

function renderSubmissionForm() {
    return render(<SubmissionForm config={testConfig} />);
}

const metadataFile = new File(['content'], 'metadata.tsv', { type: 'text/plain' });
const sequencesFile = new File(['content'], 'sequences.fasta', { type: 'text/plain' });

const testResponse: HeaderId[] = [
    { id: 0, header: 'header0' },
    { id: 1, header: 'header1' },
];

describe('SubmitForm', () => {
    test('should handle file upload and server response', async () => {
        mockRequest.submit(200, testResponse);

        const { getByLabelText, getByText, getByPlaceholderText } = renderSubmissionForm();

        await userEvent.type(getByPlaceholderText('Username:'), testuser);
        await userEvent.upload(getByLabelText(/Metadata File:/i), metadataFile);
        await userEvent.upload(getByLabelText(/Sequences File:/i), sequencesFile);

        const submitButton = getByText('Submit');
        await userEvent.click(submitButton);

        await waitFor(() => {
            expect(getByText((text) => text.includes('header0'))).toBeInTheDocument();
            expect(getByText((text) => text.includes('header1'))).toBeInTheDocument();
        });
    });

    test('should answer with feedback that a file is missing', async () => {
        mockRequest.submit(200, testResponse);

        const { getByLabelText, getByText, getByPlaceholderText } = renderSubmissionForm();

        await userEvent.type(getByPlaceholderText('Username:'), testuser);
        await userEvent.upload(getByLabelText(/Metadata File:/i), metadataFile);

        const submitButton = getByText('Submit');
        await userEvent.click(submitButton);

        await waitFor(() => {
            expect(
                getByText((text) => text.includes('Please select both a metadata and sequences file')),
            ).toBeInTheDocument();
        });
    });

    test('should answer with feedback that the backend respond with an internal server error', async () => {
        mockRequest.submit(500);

        const { getByLabelText, getByText, getByPlaceholderText } = renderSubmissionForm();

        await userEvent.type(getByPlaceholderText('Username:'), testuser);
        await userEvent.upload(getByLabelText(/Metadata File:/i), metadataFile);
        await userEvent.upload(getByLabelText(/Sequences File:/i), sequencesFile);

        const submitButton = getByText('Submit');
        await userEvent.click(submitButton);
        await waitFor(() => {
            expect(getByText((text) => text.includes('Submission failed with status code 500'))).toBeInTheDocument();
        });
    });
});
