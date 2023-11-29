import type { Locator, Page } from '@playwright/test';

import { baseUrl, dummyOrganism, expect, metadataTestFile, sequencesTestFile } from '../../e2e.fixture';
import { routes } from '../../../src/routes.ts';

export class SubmitPage {
    public readonly submitButton: Locator;

    constructor(public readonly page: Page) {
        this.submitButton = page.getByRole('button', { name: 'Submit' });
    }

    public async goto() {
        await this.page.goto(`${baseUrl}${routes.submitPage(dummyOrganism.key)}`, { waitUntil: 'networkidle' });
    }

    public async uploadMetadata() {
        await this.page.getByPlaceholder('Metadata File:').setInputFiles(metadataTestFile);
        expect(this.page.getByText('metadata.tsv'));
    }

    public async uploadSequenceData() {
        await this.page.getByPlaceholder('Sequences File:').setInputFiles(sequencesTestFile);
    }
}
