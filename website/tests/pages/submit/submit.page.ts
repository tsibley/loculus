import { readFileSync } from 'fs';

import type { Locator, Page } from '@playwright/test';

import { approveProcessedData } from '../../../src/components/UserSequenceList/approveProcessedData.ts';
import { baseUrl, metadataTestFile, sequencesTestFile, testuser, expect } from '../../e2e.fixture';
import { fakeProcessingPipeline, queryUnprocessedData, type Sequence } from '../../util/preprocessingPipeline.ts';

export type SubmitResponse = { sequenceId: number; customId: string };
export class SubmitPage {
    public readonly userField: Locator;
    public readonly submitButton: Locator;
    private readonly testSequenceCount: number = readFileSync(metadataTestFile, 'utf-8').split('\n').length - 2;

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
        await approveProcessedData(
            testuser,
            sequences.map((entry) => entry.sequenceId),
        );

        return sequences;
    }

    public getTestSequenceCount() {
        return this.testSequenceCount;
    }

    public async setUsername(username: string) {
        await this.userField.fill(username);
    }
}
