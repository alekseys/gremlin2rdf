/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { describe, expect, it } from 'vitest';
import { graphProjectSchema } from '../graphMigration/contracts';
import { ageInputHash, ageMessageSchema, ageProjectSchema } from './contracts';
const p = {
    schemaVersion: 1,
    migrationKind: 'cosmos-gremlin-to-age',
    runId: 'test',
    source: null,
    target: null,
    consistency: 'immutable',
    limits: { batchRecords: 50, batchBytes: 1048576, timeoutSeconds: 30, maximumOperationSeconds: 300 },
    approvals: [],
};
describe('isolated AGE contracts', () => {
    it('does not accept Jena state or convert AGE state into Jena', () => {
        expect(ageProjectSchema.safeParse({ ...p, migrationKind: 'cosmos-gremlin-to-jena' }).success).toBe(false);
        expect(graphProjectSchema.safeParse(p).success).toBe(false);
    });
    it('hashes inputs independently of approval changes', () => {
        const project = ageProjectSchema.parse(p);
        expect(ageInputHash({ ...project, consistency: 'quiesced' })).not.toBe(ageInputHash(project));
        expect(ageInputHash({ ...project, approvals: [] })).toBe(ageInputHash(project));
    });
    it('rejects credential values and insecure TLS', () => {
        const target = {
            host: 'example',
            port: 5432,
            database: 'db',
            username: 'user',
            authenticationRef: 'env:AGE_TARGET_PASSWORD',
            graph: 'graph',
            ledgerSchema: 'ledger',
            sslMode: 'VerifyFull',
        };
        expect(ageProjectSchema.safeParse({ ...p, target }).success).toBe(true);
        expect(ageProjectSchema.safeParse({ ...p, target: { ...target, sslMode: 'Disable' } }).success).toBe(false);
        expect(ageProjectSchema.safeParse({ ...p, target: { ...target, password: 'secret' } }).success).toBe(false);
    });
    it('rejects malformed versioned messages', () => {
        expect(
            ageMessageSchema.safeParse({
                protocolVersion: 1,
                sequence: 1,
                type: 'result',
                runId: 'test',
                operationId: 'id',
                phase: 'fixture',
                payload: { records: 5 },
            }).success,
        ).toBe(true);
        expect(ageMessageSchema.safeParse({ protocolVersion: 2, sequence: 0, type: 'success' }).success).toBe(false);
    });
});
