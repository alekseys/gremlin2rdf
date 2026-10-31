/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { describe, expect, it } from 'vitest';
import {
    canonicalJson,
    graphProjectSchema,
    identityManifestEntrySchema,
    inputHash,
    invalidateEvidence,
    typedValueSchema,
    workerMessageSchema,
} from './contracts';

function project() {
    return graphProjectSchema.parse({
        schemaVersion: 1,
        migrationKind: 'cosmos-gremlin-to-jena',
        baselineRepositorySha: '22e3f2cccbfcc6622cacbf1873b9c620acba085e',
        runId: 'fixture',
        protocolVersion: 1,
        workerVersion: '0.1.0',
        mapping: {
            schemaVersion: 1,
            baseIri: 'urn:test:',
            vocabulary: 'urn:domain:',
            labels: {},
            properties: {},
            edges: {},
            exclusions: [],
            derivedRelationships: true,
            provenance: true,
            fidelity: 'strict',
        },
        source: null,
        target: null,
        limits: {
            batchRecords: 100,
            batchBytes: 1048576,
            maxRecordBytes: 262144,
            requestTimeoutMillis: 1000,
            maxRetries: 0,
            queueCapacity: 100,
            writerConcurrency: 1,
            minimumFreeDiskBytes: 0,
        },
        consistency: 'immutable',
        phases: {},
        approvals: [],
        applicationHashes: {},
        artifacts: [],
    });
}
describe('separate graph migration contracts', () => {
    it('rejects queue and payload settings outside the fixed worker memory envelope', () => {
        const input = project();
        for (const limits of [
            { ...input.limits, batchRecords: 1001 },
            { ...input.limits, queueCapacity: 1001 },
            { ...input.limits, batchBytes: 8388609 },
            { ...input.limits, maxRecordBytes: 1048577, batchBytes: 8388608 },
        ])
            expect(graphProjectSchema.safeParse({ ...input, limits }).success).toBe(false);
    });
    it('rejects relational fields, embedded secrets and arbitrary executable hooks', () => {
        const input = project();
        for (const field of ['tables', 'containersCreated', 'password', 'executable', 'hooks']) {
            expect(graphProjectSchema.safeParse({ ...input, [field]: 'untrusted' }).success).toBe(false);
        }
        expect(graphProjectSchema.safeParse({ ...input, migrationKind: 'relational' }).success).toBe(false);
    });
    it('invalidates all dependent evidence when mapping or application hashes change', () => {
        const input = project();
        input.phases.validation = {
            status: 'succeeded',
            blockers: [],
            evidence: ['validation.json'],
            inputHash: inputHash(input),
        };
        expect(invalidateEvidence(input).phases.validation.status).toBe('succeeded');
        input.mapping.baseIri = 'urn:changed:';
        expect(invalidateEvidence(input).phases.validation.status).toBe('stale');
    });
    it('preserves typed large integer identity instead of using JavaScript numbers', () => {
        expect(typedValueSchema.parse({ type: 'integer', lexical: '9223372036854775808' }).lexical).toBe(
            '9223372036854775808',
        );
        expect(typedValueSchema.safeParse({ type: 'integer', lexical: '01' }).success).toBe(false);
        expect(typedValueSchema.safeParse({ type: 'doubleValue', lexical: 'NaN' }).success).toBe(false);
    });
    it('canonicalizes object keys but preserves occurrence arrays', () => {
        expect(canonicalJson({ b: 1, a: ['x', 'y'] })).toBe(canonicalJson({ a: ['x', 'y'], b: 1 }));
        expect(canonicalJson(['x', 'y'])).not.toBe(canonicalJson(['y', 'x']));
    });
    it('rejects incompatible protocol versions', () => {
        expect(
            workerMessageSchema.safeParse({
                protocolVersion: 2,
                type: 'hello',
                sequence: 0,
                runId: '',
                operationId: '',
                timestamp: '2026-10-31T00:00:00Z',
                phase: 'idle',
                payload: {},
            }).success,
        ).toBe(false);
    });
    it('requires explicit typed edge-owner identity and scope in source manifests', () => {
        const vertex = {
            schemaVersion: 1,
            kind: 'vertex',
            partition: { type: 'string', lexical: 'p' },
            id: { type: 'string', lexical: 'v' },
            ownerId: null,
            edgeScope: null,
        };
        expect(identityManifestEntrySchema.safeParse(vertex).success).toBe(true);
        expect(identityManifestEntrySchema.safeParse({ ...vertex, kind: 'edge' }).success).toBe(false);
        expect(
            identityManifestEntrySchema.safeParse({ ...vertex, id: { type: 'doubleValue', lexical: '1.0' } }).success,
        ).toBe(false);
        expect(
            identityManifestEntrySchema.safeParse({ ...vertex, partition: { type: 'doubleValue', lexical: '1.5' } })
                .success,
        ).toBe(true);
        expect(
            identityManifestEntrySchema.safeParse({
                ...vertex,
                kind: 'edge',
                ownerId: vertex.id,
                edgeScope: 'sourceVertex',
            }).success,
        ).toBe(true);
    });
});
