/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { createHash } from 'node:crypto';
import { z } from 'zod';

const identifier = z.string().regex(/^[a-z][a-z0-9_]{0,47}$/u);
const ledgerIdentifier = identifier.refine(
    (value) => !['public', 'ag_catalog', 'information_schema'].includes(value) && !value.startsWith('pg_'),
);
const secret = z.string().regex(/^env:AGE_[A-Z0-9_]+$/u);
export const ageProjectSchema = z
    .object({
        schemaVersion: z.literal(1),
        migrationKind: z.literal('cosmos-gremlin-to-age'),
        runId: z.string().regex(/^[a-zA-Z0-9-]{1,80}$/u),
        source: z
            .object({
                host: z.string().regex(/^[a-zA-Z0-9.-]+$/u),
                database: z.string().min(1).max(255),
                container: z.string().min(1).max(255),
                partitionKeyPath: z.string().regex(/^\/[^/]{1,255}$/u),
                authenticationRef: secret,
                scope: z.string().min(1).max(1024),
            })
            .strict()
            .nullable(),
        target: z
            .object({
                host: z.string().min(1).max(255),
                port: z.number().int().min(1).max(65535),
                database: z.string().min(1).max(255),
                username: z.string().min(1).max(255),
                authenticationRef: secret,
                graph: identifier,
                ledgerSchema: ledgerIdentifier,
                sslMode: z.literal('VerifyFull'),
            })
            .strict()
            .nullable(),
        consistency: z.enum(['quiesced', 'immutable']),
        limits: z
            .object({
                batchRecords: z.number().int().min(1).max(1000),
                batchBytes: z.number().int().min(262144).max(16777216),
                timeoutSeconds: z.number().int().min(1).max(120),
                maximumOperationSeconds: z.number().int().min(1).max(3600),
            })
            .strict(),
        approvals: z
            .array(
                z
                    .object({
                        operation: z.enum(['load', 'crud', 'rewrite']),
                        inputHash: z.string().regex(/^[a-f0-9]{64}$/u),
                        runId: z.string(),
                        target: z.string(),
                        resourceHash: z.string().regex(/^[a-f0-9]{64}$/u),
                        expiresAt: z.string(),
                        signature: z.string().regex(/^[a-f0-9]{64}$/u),
                    })
                    .strict(),
            )
            .max(20),
    })
    .strict();
export type AgeProject = z.infer<typeof ageProjectSchema>;
export const ageOperationSchema = z.enum([
    'fixture',
    'translate',
    'extract',
    'load',
    'resume',
    'query',
    'validate',
    'assess',
    'rewrite-plan',
    'rewrite-apply',
]);
export type AgeOperation = z.infer<typeof ageOperationSchema>;
export const ageMessageSchema = z
    .object({
        protocolVersion: z.literal(1),
        sequence: z.number().int().positive(),
        type: z.enum(['progress', 'result', 'error']),
        runId: z.string(),
        operationId: z.string(),
        phase: z.string(),
        payload: z.unknown(),
    })
    .strict();
export type AgeMessage = z.infer<typeof ageMessageSchema>;
const frameScope = {
    protocolVersion: z.literal(1),
    sequence: z.number().int().positive(),
    runId: z.string().min(1).max(80),
    operationId: z.string().min(1).max(80),
    inputHash: z.string().regex(/^[a-f0-9]{64}$/u),
    operation: z.enum([...ageOperationSchema.options, 'authorize', 'authorize-rewrite']),
    expectedResourceHash: z.union([z.literal(''), z.string().regex(/^[a-f0-9]{64}$/u)]).default(''),
};
export const ageRequestSchema = z.discriminatedUnion('type', [
    z
        .object({
            ...frameScope,
            type: z.literal('start'),
            project: ageProjectSchema,
            input: z.string().max(16384),
            output: z.string().max(1024),
        })
        .strict(),
    z
        .object({
            ...frameScope,
            type: z.enum(['pause', 'cancel']),
            project: z.null(),
            input: z.literal(''),
            output: z.literal(''),
        })
        .strict(),
]);
export function ageInputHash(p: AgeProject): string {
    return createHash('sha256')
        .update(
            JSON.stringify({
                schemaVersion: p.schemaVersion,
                migrationKind: p.migrationKind,
                runId: p.runId,
                source: p.source,
                target: p.target,
                consistency: p.consistency,
                limits: p.limits,
            }),
        )
        .digest('hex');
}
