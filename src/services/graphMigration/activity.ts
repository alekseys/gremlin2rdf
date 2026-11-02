/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import * as l10n from '@vscode/l10n';
import { z } from 'zod';

const counter = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);

/** Present the first update, at most one per ten seconds, and the final two; never retain an unbounded stream. */
export class ActivitySampler<T> {
    private next = 0;
    private lastSampleAt = Number.NEGATIVE_INFINITY;
    private samples: { index: number; value: T }[] = [];
    private tail: { index: number; value: T }[] = [];
    offer(value: T, now = Date.now(), eligible = true): boolean {
        const entry = { index: this.next++, value };
        this.tail = [...this.tail, entry].slice(-2);
        if (!eligible || now - this.lastSampleAt < 10000) return false;
        this.lastSampleAt = now;
        this.samples = [...this.samples, entry];
        if (this.samples.length > 18) this.samples.splice(1, this.samples.length - 18);
        return true;
    }
    snapshot(): T[] {
        const values = new Map([...this.samples, ...this.tail].map((entry) => [entry.index, entry]));
        return [...values.values()].sort((a, b) => a.index - b.index).map((entry) => entry.value);
    }
}
const sourceStages = z.enum([
    'source-enumeration-vertex-page',
    'source-enumeration-edge-page',
    'source-enumeration-global-vertex-count',
    'source-enumeration-global-edge-count',
    'source-enumeration-partition-vertex-count',
    'source-enumeration-partition-edge-count',
    'source-enumeration-checkpoint',
    'source-enumeration-reconciliation',
    'source-enumeration-merge',
]);
export const sourceActivitySchema = z
    .object({
        type: z.literal('source-progress'),
        stage: sourceStages,
        vertices: counter,
        edges: counter,
        reservedRequests: counter,
        elapsedMillis: counter,
    })
    .strict();
export function activityText(payload: unknown): string | undefined {
    if (
        z
            .object({ activity: z.literal('operation-started') })
            .strict()
            .safeParse(payload).success
    )
        return l10n.t('Worker started this operation. Waiting for observed stage or batch activity.');
    const request = z
        .object({
            type: z.literal('source-request-progress'),
            stage: z.enum([
                'source-request-awaiting-response',
                'source-rate-wait',
                'source-retry-wait',
                'source-request-completed',
            ]),
            waitMillis: counter,
            retries: counter,
        })
        .strict()
        .safeParse(payload);
    if (request.success) {
        switch (request.data.stage) {
            case 'source-request-awaiting-response':
                return l10n.t(
                    'Source request submitted; awaiting its response. No query or resource values are logged.',
                );
            case 'source-rate-wait':
                return l10n.t('Pacing source reads: waiting {milliseconds} milliseconds before the next request.', {
                    milliseconds: request.data.waitMillis,
                });
            case 'source-retry-wait':
                return l10n.t('Waiting {milliseconds} milliseconds before bounded source retry {retries}.', {
                    milliseconds: request.data.waitMillis,
                    retries: request.data.retries,
                });
            case 'source-request-completed':
                return l10n.t('Source response received and request charge accounted.');
        }
    }
    const source = sourceActivitySchema.safeParse(payload);
    if (source.success) {
        const labels: Record<z.infer<typeof sourceStages>, string> = {
            'source-enumeration-vertex-page': l10n.t('Reading a vertex identity page'),
            'source-enumeration-edge-page': l10n.t('Reading an outgoing-edge identity page'),
            'source-enumeration-global-vertex-count': l10n.t('Counting source vertices'),
            'source-enumeration-global-edge-count': l10n.t('Counting source edges'),
            'source-enumeration-partition-vertex-count': l10n.t('Reconciling partition vertex counts'),
            'source-enumeration-partition-edge-count': l10n.t('Reconciling partition edge counts'),
            'source-enumeration-checkpoint': l10n.t('Saved local enumeration checkpoint (coverage not yet verified)'),
            'source-enumeration-reconciliation': l10n.t('Reconciling source counts and coverage'),
            'source-enumeration-merge': l10n.t('Merging verified identity pages'),
        };
        return l10n.t(
            '{stage}. Enumerated vertices: {vertices}; edges: {edges}; reserved request attempts: {requests}; elapsed: {seconds} seconds.',
            {
                stage: labels[source.data.stage],
                vertices: source.data.vertices,
                edges: source.data.edges,
                requests: source.data.reservedRequests,
                seconds: Math.floor(source.data.elapsedMillis / 1000),
            },
        );
    }
    const metrics = z
        .object({
            recordsConfirmed: counter,
            requestBodyBytes: counter,
            elapsedMillis: counter,
            queueDepth: counter.optional(),
            retries: counter.optional(),
        })
        .strict()
        .safeParse(payload);
    if (metrics.success)
        return l10n.t('Confirmed records: {records}; request bytes: {bytes}; elapsed: {seconds} seconds.', {
            records: metrics.data.recordsConfirmed,
            bytes: metrics.data.requestBodyBytes,
            seconds: Math.floor(metrics.data.elapsedMillis / 1000),
        });
    return undefined;
}
