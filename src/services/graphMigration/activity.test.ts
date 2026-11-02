/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { describe, expect, it } from 'vitest';
import { activityText } from './activity';

describe('safe per-step activity', () => {
    const progress = {
        type: 'source-progress',
        stage: 'source-enumeration-edge-page',
        vertices: 10,
        edges: 21,
        reservedRequests: 30,
        elapsedMillis: 4500,
    };
    it('formats observed counts without pretending coverage or load confirmation', () => {
        expect(activityText(progress)).toContain('Reading an outgoing-edge identity page');
        expect(activityText(progress)).toContain('Enumerated vertices: 10; edges: 21');
        expect(activityText(progress)).toContain('reserved request attempts: 30');
        expect(activityText(progress)).not.toContain('Confirmed records');
    });
    it.each([
        { ...progress, stage: 'private' },
        { ...progress, query: 'private' },
        { ...progress, vertices: -1 },
        { ...progress, edges: Number.POSITIVE_INFINITY },
        { ...progress, elapsedMillis: 1.2 },
    ])('rejects unknown/raw/unbounded payloads', (payload) => {
        expect(activityText(payload)).toBeUndefined();
    });
    it('distinguishes pacing from active requests and rejected unstructured logs', () => {
        expect(
            activityText({ type: 'source-request-progress', stage: 'source-rate-wait', waitMillis: 3000, retries: 1 }),
        ).toContain('waiting 3000 milliseconds');
        expect(
            activityText({
                type: 'source-request-progress',
                stage: 'source-request-awaiting-response',
                waitMillis: 0,
                retries: 0,
            }),
        ).toContain('awaiting its response');
        expect(
            activityText({
                type: 'source-request-progress',
                stage: 'source-rate-wait',
                waitMillis: 3000,
                retries: 1,
                providerMessage: 'private',
            }),
        ).toBeUndefined();
    });
});
