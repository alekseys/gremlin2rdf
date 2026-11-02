/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { describe, expect, it } from 'vitest';
import { ActivitySampler, activityText } from './activity';

describe('bounded activity sampling', () => {
    it('shows the first, no more than one per ten seconds, and retains the final two for a terminal snapshot', () => {
        const sampler = new ActivitySampler<number>();
        expect(sampler.offer(0, 0)).toBe(true);
        for (let i = 1; i < 100; i++) expect(sampler.offer(i, i * 100)).toBe(false);
        expect(sampler.offer(100, 10000)).toBe(true);
        expect(sampler.offer(101, 10001)).toBe(false);
        expect(sampler.offer(102, 10002)).toBe(false);
        expect(sampler.snapshot()).toEqual([0, 100, 101, 102]);
    });
    it('prefers meaningful summaries to per-request chatter and preserves a bounded first/periodic/tail history', () => {
        const sampler = new ActivitySampler<number>();
        sampler.offer(0, 0);
        expect(sampler.offer(1, 10000, false)).toBe(false);
        expect(sampler.offer(2, 10001)).toBe(true);
        for (let i = 3; i < 200; i++) sampler.offer(i, i * 10000);
        sampler.offer(200, 1990001, false);
        sampler.offer(201, 1990002, false);
        expect(sampler.snapshot()).toHaveLength(20);
        expect(sampler.snapshot()[0]).toBe(0);
        expect(sampler.snapshot().slice(-2)).toEqual([200, 201]);
        expect(new ActivitySampler<number>().offer(0, 0)).toBe(true);
    });
    it('includes the penultimate observation even when the last was already sampled, without duplication or reordering', () => {
        const sampler = new ActivitySampler<number>();
        sampler.offer(0, 0);
        sampler.offer(1, 9999);
        sampler.offer(2, 10000);
        expect(sampler.snapshot()).toEqual([0, 1, 2]);
        expect(sampler.snapshot()).toEqual([0, 1, 2]);
    });
});

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
