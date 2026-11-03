/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { z } from 'zod';

const finite = z.number().finite().nonnegative();
export const performancePolicySchema = z
    .object({
        schemaVersion: z.literal(1),
        minimumRecordsPerSecond: finite.nullable(),
        maximumP95BatchMillis: finite.nullable(),
        maximumErrorRate: finite.max(1).nullable(),
        maximumRequestCharge: finite.nullable(),
    })
    .strict();
const reportSchema = z
    .object({
        schemaVersion: z.literal(1),
        status: z.literal('succeeded'),
        configurationHash: z.string().regex(/^[a-f0-9]{64}$/),
        sourceSha256: z.string().regex(/^[a-f0-9]{64}$/),
        warmups: z.number().int().positive(),
        measuredRepetitions: z.number().int().min(3).max(10),
        environment: z.record(z.string(), z.union([z.string(), z.number().finite(), z.boolean(), z.null()])),
        repetitions: z
            .array(
                z
                    .object({
                        status: z.literal('succeeded'),
                        migration: z
                            .object({
                                recordsPerSecond: finite,
                                p95BatchNanos: finite,
                            })
                            .passthrough(),
                    })
                    .passthrough(),
            )
            .min(3)
            .max(10),
    })
    .passthrough()
    .refine((report) => report.repetitions.length === report.measuredRepetitions, 'Repetition count mismatch');
type Check = {
    status: 'succeeded' | 'failed' | 'unestablished';
    observed: number | null;
    threshold: number | null;
    basis: string;
};

export function evaluatePerformance(input: unknown, selectedPolicy: unknown, previous?: unknown) {
    const report = reportSchema.parse(input),
        policy = performancePolicySchema.parse(selectedPolicy);
    const rates = report.repetitions.map((entry) => entry.migration.recordsPerSecond);
    const latencies = report.repetitions.map((entry) => entry.migration.p95BatchNanos / 1000000);
    const check = (threshold: number | null, observed: number, minimum: boolean, basis: string): Check => ({
        status:
            threshold === null
                ? 'unestablished'
                : (minimum ? observed >= threshold : observed <= threshold)
                  ? 'succeeded'
                  : 'failed',
        observed,
        threshold,
        basis,
    });
    const checks = {
        throughput: check(
            policy.minimumRecordsPerSecond,
            Math.min(...rates),
            true,
            'minimum measured source records/second across repetitions',
        ),
        latency: check(
            policy.maximumP95BatchMillis,
            Math.max(...latencies),
            false,
            'maximum reported p95 batch confirmation/checkpoint latency; not per-request latency',
        ),
        errorRate: {
            status: 'unestablished',
            observed: null,
            threshold: policy.maximumErrorRate,
            basis: 'HTTP request failure denominator is not observed; retries are not an error-rate measurement',
        } as Check,
        requestCharge: {
            status: 'unestablished',
            observed: null,
            threshold: policy.maximumRequestCharge,
            basis: 'Local canonical input has no live Cosmos request-charge observation',
        } as Check,
    };
    const median = (values: number[]) => {
        const sorted = [...values].sort((a, b) => a - b),
            middle = Math.floor(sorted.length / 2);
        return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    };
    let baseline: {
        status: 'unestablished' | 'not-comparable' | 'succeeded' | 'investigate';
        throughputChange: number | null;
        p95Change: number | null;
        remediation: string;
    } = {
        status: 'unestablished',
        throughputChange: null,
        p95Change: null,
        remediation: 'Establish and retain a stable baseline under identical conditions.',
    };
    if (previous !== undefined) {
        const before = reportSchema.parse(previous);
        const environment = (value: typeof report) =>
            JSON.stringify(Object.entries(value.environment).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
        if (
            before.configurationHash !== report.configurationHash ||
            before.sourceSha256 !== report.sourceSha256 ||
            environment(before) !== environment(report)
        ) {
            baseline = {
                status: 'not-comparable',
                throughputChange: null,
                p95Change: null,
                remediation:
                    'Repeat with identical configuration, dataset, JDK, target, writer count and host conditions.',
            };
        } else {
            const oldRate = median(before.repetitions.map((entry) => entry.migration.recordsPerSecond));
            const oldLatency = median(before.repetitions.map((entry) => entry.migration.p95BatchNanos));
            if (oldRate <= 0 || oldLatency <= 0) throw new Error('A positive measured baseline is required');
            const throughputChange = (median(rates) - oldRate) / oldRate;
            const p95Change = (median(latencies) * 1000000 - oldLatency) / oldLatency;
            baseline = {
                status: throughputChange < -0.15 || p95Change > 0.2 ? 'investigate' : 'succeeded',
                throughputChange,
                p95Change,
                remediation: 'Repeat and investigate a flagged regression before accepting or rejecting the change.',
            };
        }
    }
    return {
        schemaVersion: 1,
        status: Object.values(checks).some((value) => value.status === 'failed') ? 'failed' : 'evaluated',
        operationalSlo: Object.values(checks).some((value) => value.status === 'failed')
            ? 'failed'
            : Object.values(checks).every((value) => value.status === 'succeeded')
              ? 'succeeded'
              : 'unestablished',
        checks,
        baseline,
        sourceSha256: report.sourceSha256,
        configurationHash: report.configurationHash,
        measurementLimitations: [
            'No universal throughput guarantee',
            'No per-request latency, worker RSS, target CPU/disk latency or HTTP/TLS network-byte observation',
        ],
    };
}
