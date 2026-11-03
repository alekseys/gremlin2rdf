/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { describe, expect, it } from 'vitest';
import { evaluatePerformance } from './performance';

const policy = {
    schemaVersion: 1,
    minimumRecordsPerSecond: 80,
    maximumP95BatchMillis: 120,
    maximumErrorRate: null,
    maximumRequestCharge: null,
};
function report(rate = 100, latency = 100) {
    return {
        schemaVersion: 1,
        status: 'succeeded',
        configurationHash: 'a'.repeat(64),
        sourceSha256: 'b'.repeat(64),
        warmups: 1,
        measuredRepetitions: 3,
        environment: { workerHeap: '-Xmx1g', writerConcurrency: 1 },
        repetitions: Array.from({ length: 3 }, () => ({
            status: 'succeeded',
            migration: { recordsPerSecond: rate, p95BatchNanos: latency * 1000000 },
        })),
    };
}
describe('observed performance policy and stable baseline', () => {
    it('checks exact supplied thresholds without claiming unobserved RU or error rates', () => {
        const result = evaluatePerformance(report(80, 120), policy);
        expect(result.checks.throughput.status).toBe('succeeded');
        expect(result.checks.latency.status).toBe('succeeded');
        expect(result.checks.errorRate.status).toBe('unestablished');
        expect(result.checks.requestCharge.status).toBe('unestablished');
        expect(result.operationalSlo).toBe('unestablished');
        expect(result.baseline.status).toBe('unestablished');
        expect(evaluatePerformance(report(79, 121), policy).status).toBe('failed');
    });
    it('flags only regressions exceeding 15 percent throughput or 20 percent p95', () => {
        expect(evaluatePerformance(report(85, 120), policy, report()).baseline.status).toBe('succeeded');
        expect(evaluatePerformance(report(84.9, 100), policy, report()).baseline.status).toBe('investigate');
        expect(evaluatePerformance(report(100, 120.1), policy, report()).baseline.status).toBe('investigate');
    });
    it('refuses to compare different environments or datasets and rejects incomplete evidence', () => {
        expect(
            evaluatePerformance({ ...report(), sourceSha256: 'c'.repeat(64) }, policy, report()).baseline.status,
        ).toBe('not-comparable');
        expect(
            evaluatePerformance({ ...report(), environment: { workerHeap: '-Xmx2g' } }, policy, report()).baseline
                .status,
        ).toBe('not-comparable');
        expect(() => evaluatePerformance({ ...report(), measuredRepetitions: 4 }, policy)).toThrow();
        expect(() => evaluatePerformance({ ...report(), status: 'blocked' }, policy)).toThrow();
        expect(() => evaluatePerformance(report(), { ...policy, maximumErrorRate: 1.1 })).toThrow();
    });
});
