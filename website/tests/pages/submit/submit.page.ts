import { readFileSync } from 'fs';

import type { Locator, Page } from '@playwright/test';

import { BackendClient } from '../../../src/services/backendClient.ts';
import type { Sequence, SequenceVersion } from '../../../src/types.ts';
import {
    backendUrl,
    baseUrl,
    e2eLogger,
    expect,
    metadataTestFile,
    sequencesTestFile,
    testuser,
} from '../../e2e.fixture';
import { fakeProcessingPipeline, queryUnprocessedData } from '../../util/preprocessingPipeline.ts';

export class SubmitPage {
    public readonly userField: Locator;
    public readonly submitButton: Locator;
    private readonly testSequenceCount: number = readFileSync(metadataTestFile, 'utf-8').split('\n').length - 2;
    private readonly backendClient = new BackendClient(backendUrl, e2eLogger);

    constructor(public readonly page: Page) {
        this.submitButton = page.getByRole('button', { name: 'Submit' });
        this.userField = page.getByPlaceholder('Username');
    }

    public async goto() {
        await this.page.goto(`${baseUrl}/submit`);
    }

    public async submit() {
        await Promise.all([this.uploadSequenceData(), this.setUsername(testuser), this.uploadMetadata()]);
        await this.submitButton.click();
        await this.page.waitForSelector('text=Response Sequence Headers');
    }

    public async uploadMetadata(file: string = metadataTestFile) {
        await this.page.getByPlaceholder('Metadata File:').setInputFiles(file);
    }

    public async uploadSequenceData(file: string = sequencesTestFile) {
        await this.page.getByPlaceholder('Sequences File:').setInputFiles(file);
    }

    public async prepareDataToBeReleasable(): Promise<Sequence[]> {
        await this.goto();
        await this.submit();

        const sequences = await queryUnprocessedData(this.getTestSequenceCount());
        expect(sequences.length).toBe(this.getTestSequenceCount());

        for (const sequence of sequences) {
            await fakeProcessingPipeline({ sequenceId: sequence.sequenceId, version: sequence.version, error: false });
        }
        await this.approveProcessedData(testuser, sequences);

        return sequences;
    }

    public getTestSequenceCount() {
        return this.testSequenceCount;
    }

    public async setUsername(username: string) {
        await this.userField.fill(username);
    }

    public async approveProcessedData(username: string, sequenceVersions: SequenceVersion[]): Promise<void> {
        const body = {
            sequenceVersions,
        };

        const response = await this.backendClient.call('approveProcessedData', body, {
            queries: { username },
            headers: { 'Content-Type': 'application/json' },
        });

        return response.match(
            () => {
                return;
            },
            (error) => {
                throw new Error(`Unexpected error while approving: ${JSON.stringify(error)}`);
            },
        );
    }
}
