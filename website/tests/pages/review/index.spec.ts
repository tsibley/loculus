import { type ReviewPage } from './review.page.ts';
import type { AccessionVersion } from '../../../src/types/backend.ts';
import { baseUrl, dummyOrganism, expect, test } from '../../e2e.fixture';
import { prepareDataToBe } from '../../util/prepareDataToBe.ts';
import type { UserPage } from '../user/user.page.ts';
import { routes } from '../../../src/routes.ts';

test.describe('The review page', () => {
    test(
        'should show the review page for a sequence entry that needs review, ' +
            'download the sequence and submit the review',
        async ({ userPage, reviewPage, loginAsTestUser }) => {
            const { token } = await loginAsTestUser();

            const [erroneousTestSequenceEntry] = await prepareDataToBe('erroneous', token, 1);
            const [stagedTestSequenceEntry] = await prepareDataToBe('awaitingApproval', token, 1);

            expect(erroneousTestSequenceEntry).toBeDefined();
            expect(stagedTestSequenceEntry).toBeDefined();

            await userPage.gotoUserSequencePage();

            await testReviewFlow(reviewPage, userPage, erroneousTestSequenceEntry);
            await testReviewFlow(reviewPage, userPage, stagedTestSequenceEntry);
        },
    );

    const testReviewFlow = async (reviewPage: ReviewPage, userPage: UserPage, testSequence: AccessionVersion) => {
        await userPage.clickOnReviewForSequenceEntry(testSequence);

        expect(await reviewPage.page.isVisible(`text=Review for Id: ${testSequence.accession}`)).toBe(true);
        expect(await reviewPage.page.isVisible(`text=Original Data`)).toBe(true);
        expect(await reviewPage.page.isVisible(`text=Processed Data`)).toBe(true);

        await reviewPage.downloadAndVerify(testSequence);

        await reviewPage.submit();

        await reviewPage.page.waitForURL(`${baseUrl}${routes.userSequencesPage(dummyOrganism.key)}`);
    };
});
