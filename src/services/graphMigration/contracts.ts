/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { createHash } from 'node:crypto';
import { z } from 'zod';

export const graphMigrationKind = 'cosmos-gremlin-to-jena';
export const graphMigrationFolder = '.cosmosdb-graph-migration';
export const protocolVersion = 1;
export const workerVersion = '0.1.0';
export const phaseStatusSchema = z.enum([
    'not-started',
    'running',
    'paused',
    'blocked',
    'failed',
    'succeeded',
    'cancelled',
    'stale',
]);
const hash = z.string().regex(/^[a-f0-9]{64}$/);
const reference = z
    .string()
    .regex(/^env:GRAPH_[A-Z0-9_]{1,100}$/)
    .nullable();
const iri = z.string().refine((value) => {
    try {
        return Boolean(new URL(value).protocol) && !/[<>\s]/u.test(value);
    } catch {
        return false;
    }
}, 'An absolute IRI is required');
const endpoint = z.string().refine((value) => {
    try {
        const url = new URL(value);
        return (
            !url.username &&
            !url.password &&
            !url.hash &&
            (url.protocol === 'https:' ||
                (url.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)))
        );
    } catch {
        return false;
    }
}, 'HTTPS is required except for loopback; embedded credentials are prohibited');
export const relativePathSchema = z
    .string()
    .min(1)
    .refine(
        (value) =>
            !value.startsWith('/') && !value.includes('\\') && !value.includes(':') && !value.split('/').includes('..'),
        'Artifacts must remain inside the migration directory',
    );
const relativePath = relativePathSchema;
export const typedValueSchema = z
    .object({
        type: z.enum(['string', 'booleanValue', 'integer', 'floatValue', 'doubleValue']),
        lexical: z.string(),
    })
    .strict()
    .superRefine((value, context) => {
        const valid =
            value.type === 'string' ||
            (value.type === 'booleanValue' && ['true', 'false'].includes(value.lexical)) ||
            (value.type === 'integer' && /^(?:0|-?[1-9][0-9]*)$/u.test(value.lexical)) ||
            (['floatValue', 'doubleValue'].includes(value.type) &&
                /^-?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$/u.test(value.lexical) &&
                Number.isFinite(Number(value.lexical)) &&
                (value.type !== 'floatValue' || Number.isFinite(Math.fround(Number(value.lexical)))));
        if (!valid) context.addIssue({ code: 'custom', message: 'Invalid typed source lexical value' });
    });
export const identitySchema = z
    .object({
        scope: z.string().min(1),
        partition: typedValueSchema,
        id: typedValueSchema,
    })
    .strict();
export const identityManifestEntrySchema = z
    .object({
        schemaVersion: z.literal(1),
        kind: z.enum(['vertex', 'edge']),
        partition: typedValueSchema,
        id: typedValueSchema,
        ownerId: typedValueSchema.nullable(),
        edgeScope: z.enum(['sourcePartition', 'sourceVertex']).nullable(),
    })
    .strict()
    .superRefine((entry, context) => {
        const identities = [entry.id, ...(entry.ownerId ? [entry.ownerId] : [])];
        if (
            identities.some((value) => !['string', 'integer', 'booleanValue'].includes(value.type)) ||
            (entry.kind === 'edge' ? !entry.ownerId || !entry.edgeScope : Boolean(entry.ownerId || entry.edgeScope))
        ) {
            context.addIssue({
                code: 'custom',
                message: 'Supported typed identities and verified edge owner/scope are required',
            });
        }
    });
export const propertySchema = z
    .object({
        key: z.string().min(1),
        value: typedValueSchema,
        id: typedValueSchema.nullable(),
        metaProperties: z.record(z.string(), typedValueSchema),
        fidelity: z.enum(['complete', 'reduced']),
    })
    .strict();
export const canonicalRecordSchema = z
    .object({
        kind: z.enum(['vertex', 'edge']),
        identity: identitySchema,
        label: z.string().min(1),
        source: identitySchema.nullable(),
        destination: identitySchema.nullable(),
        edgeScope: z.enum(['sourcePartition', 'sourceVertex']).nullable(),
        properties: z.array(propertySchema),
        metadata: z.record(z.string(), typedValueSchema),
        provenance: z
            .object({
                shard: z.string(),
                sourceManifestHash: hash,
                consistency: z.enum(['quiesced', 'immutable', 'best-effort']),
                fidelity: z.enum(['complete', 'reduced']),
            })
            .strict(),
    })
    .strict()
    .superRefine((record, context) => {
        const valid =
            record.kind === 'vertex'
                ? !record.source && !record.destination && !record.edgeScope
                : Boolean(
                      record.source &&
                      record.destination &&
                      record.edgeScope &&
                      record.identity.scope === record.source.scope &&
                      record.identity.scope === record.destination.scope &&
                      canonicalJson(record.identity.partition) === canonicalJson(record.source.partition),
                  );
        if (!valid)
            context.addIssue({ code: 'custom', message: 'Complete, consistent endpoint identities are required' });
        if (
            record.kind === 'vertex' &&
            record.properties.some((property) => property.fidelity === 'complete' && !property.id)
        ) {
            context.addIssue({
                code: 'custom',
                message: 'Complete vertex property occurrences require typed identifiers',
            });
        }
    });
export const mappingSchema = z
    .object({
        schemaVersion: z.literal(1),
        baseIri: iri,
        vocabulary: iri,
        labels: z.record(z.string(), iri),
        properties: z.record(z.string(), iri),
        edges: z.record(z.string(), iri),
        exclusions: z.array(z.string()).refine((values) => new Set(values).size === values.length),
        derivedRelationships: z.boolean(),
        provenance: z.boolean(),
        fidelity: z.enum(['strict', 'reduced']),
        valueRules: z
            .record(
                z.string().min(1),
                z
                    .object({
                        sourceType: z.enum(['string', 'booleanValue', 'integer', 'floatValue', 'doubleValue']),
                        representation: z.enum(['literal', 'resource']),
                        datatype: iri.nullable(),
                    })
                    .strict()
                    .superRefine((rule, context) => {
                        if (
                            rule.representation === 'resource'
                                ? rule.sourceType !== 'string' || rule.datatype !== null
                                : !rule.datatype ||
                                  !/^http:\/\/www\.w3\.org\/2001\/XMLSchema#(?:string|boolean|integer|decimal|float|double|date|dateTime|anyURI)$/u.test(
                                      rule.datatype,
                                  )
                        )
                            context.addIssue({
                                code: 'custom',
                                message: 'Explicit supported domain representation required',
                            });
                    }),
            )
            .refine((rules) => Object.keys(rules).length > 0)
            .optional(),
        domainRules: z
            .array(
                z
                    .object({
                        label: z.string().min(1),
                        property: z.string().min(1),
                        minimumOccurrences: z.number().int().nonnegative(),
                        maximumOccurrences: z.number().int().nonnegative().nullable(),
                    })
                    .strict(),
            )
            .min(1)
            .max(1000)
            .optional(),
    })
    .strict()
    .superRefine((mapping, context) => {
        if (
            [
                ...Object.values(mapping.labels),
                ...Object.values(mapping.properties),
                ...Object.values(mapping.edges),
            ].some((value) => value.startsWith('urn:gremlin2rdf:canonical:v1:'))
        ) {
            context.addIssue({ code: 'custom', message: 'Reserved canonical vocabulary collision' });
        }
        if (mapping.fidelity === 'strict' && mapping.exclusions.length) {
            context.addIssue({ code: 'custom', message: 'Strict mapping cannot exclude required properties' });
        }
        if (
            Object.keys(mapping.valueRules ?? {}).length > 1000 ||
            Object.keys(mapping.valueRules ?? {}).some((key) => mapping.exclusions.includes(key)) ||
            (mapping.domainRules ?? []).some(
                (rule) =>
                    !mapping.valueRules?.[rule.property] ||
                    mapping.exclusions.includes(rule.property) ||
                    (rule.maximumOccurrences !== null && rule.maximumOccurrences < rule.minimumOccurrences),
            )
        )
            context.addIssue({ code: 'custom', message: 'Bounded compatible domain occurrence rules required' });
        const explicitPredicates = Object.keys(mapping.valueRules ?? {})
            .filter((key) => mapping.properties[key] !== undefined)
            .map((key) => mapping.properties[key]);
        if (new Set(explicitPredicates).size !== explicitPredicates.length)
            context.addIssue({ code: 'custom', message: 'Domain value rules require distinct predicates' });
    });
export const approvalSchema = z
    .object({
        schemaVersion: z.literal(1),
        runId: z.string().regex(/^[A-Za-z0-9_-]{1,80}$/),
        operation: z.string().min(1),
        resources: z.array(z.string()).min(1),
        inputHash: hash,
        expiresAt: z.iso.datetime(),
        keyRef: reference.refine((value) => value !== null),
        signature: hash,
    })
    .strict();
export const populationPlanSchema = z
    .object({
        schemaVersion: z.literal(1),
        runId: z.string().regex(/^[A-Za-z0-9_-]{1,80}$/),
        inputHash: hash,
        containerIdentity: z.string().min(1),
        runPrefix: z.string().min(1),
        recordsPath: relativePath,
        recordsSha256: hash,
        vertices: z.number().int().positive(),
        edges: z.number().int().nonnegative(),
        estimatedWrites: z.number().int().positive(),
        maximumWrites: z.number().int().positive(),
        ruBudget: z.string().min(1),
    })
    .strict();
export const translationSchema = z
    .object({
        schemaVersion: z.literal(1),
        fingerprint: hash,
        sparql: z.string().max(16777216),
        shape: z.enum(['vertex', 'edge', 'value', 'projection', 'count']),
        comparison: z.enum(['set', 'multiset', 'sequence']),
        resultVariables: z.array(z.string().max(128)).max(100),
    })
    .strict();
export const rdfTermSchema = z
    .object({
        kind: z.enum(['iri', 'literal']),
        lexical: z.string().max(1048576),
        datatype: z.string().nullable(),
        language: z.string().nullable(),
    })
    .strict();
export const queryFixtureSchema = z
    .object({
        gremlin: z.string().max(65536),
        translation: translationSchema,
        expected: z.array(z.record(z.string().max(128), rdfTermSchema)).max(100000),
    })
    .strict();
export const fixtureCatalogSchema = z
    .object({
        schemaVersion: z.literal(1),
        runId: z.string().regex(/^[A-Za-z0-9_-]{1,80}$/),
        inputHash: hash,
        sourcePath: relativePath,
        sourceHash: hash,
        graph: iri,
        artifacts: z
            .array(
                z
                    .object({
                        path: relativePath,
                        sha256: hash,
                        kind: z.enum(['query', 'mutation', 'record-manifest']),
                    })
                    .strict(),
            )
            .max(100),
        sensitiveArtifact: z.literal(true),
    })
    .strict();
export const mutationFixtureSchema = z
    .object({
        operation: z.enum([
            'create',
            'lookup',
            'insertProperty',
            'replaceProperties',
            'removeProperty',
            'deleteEdge',
            'deleteVertex',
        ]),
        entity: iri,
        record: canonicalRecordSchema.nullable(),
        property: propertySchema.nullable(),
        properties: z.array(propertySchema).nullable(),
        incidentPolicy: z.enum(['reject', 'cascade-edges']).nullable(),
        expectedStatus: z.enum(['succeeded', 'blocked']),
        expected: z.array(z.object({ entity: iri, record: canonicalRecordSchema.nullable() }).strict()).max(100),
    })
    .strict();
export const javaRewritePlanSchema = z
    .object({
        schemaVersion: z.literal(1),
        sourcePath: relativePath,
        sourceHash: hash,
        graph: iri,
        output: z.string().max(1048576),
        findings: z
            .array(
                z
                    .object({
                        symbol: z.string(),
                        startLine: z.number().int().positive(),
                        endLine: z.number().int().positive(),
                        fingerprint: hash,
                        status: z.string(),
                        remediation: z.string(),
                    })
                    .strict(),
            )
            .max(10000),
        blockers: z.array(z.string()),
        sensitiveArtifact: z.literal(true),
    })
    .strict();
export const artifactEnvelopeSchema = z
    .object({
        schemaVersion: z.literal(1),
        runId: z.string(),
        inputHash: hash,
        operation: z.string(),
        status: phaseStatusSchema,
        sensitiveArtifact: z.literal(true),
        result: z.unknown(),
    })
    .strict();
export const graphProjectSchema = z
    .object({
        schemaVersion: z.literal(1),
        migrationKind: z.literal(graphMigrationKind),
        baselineRepositorySha: z.string().regex(/^[a-f0-9]{40}$/),
        runId: z.string().regex(/^[A-Za-z0-9_-]{1,80}$/),
        protocolVersion: z.literal(protocolVersion),
        workerVersion: z.literal(workerVersion),
        mapping: mappingSchema,
        source: z
            .object({
                scope: z.string().min(1),
                host: z
                    .string()
                    .min(1)
                    .refine((value) => !value.includes('/')),
                port: z.number().int().min(1).max(65535),
                database: z.string().min(1),
                container: z.string().min(1),
                partitionKeyPath: z.string().regex(/^\/[^/]{1,255}$/u),
                authenticationRef: reference,
                extractionStrategy: z.literal('verified-identity-manifest'),
                sourceMode: z.enum(['cosmos-documents', 'gremlin']).optional(),
                documentEndpoint: endpoint.optional(),
                documentProfile: z.enum(['property-bags-v1', 'flat-v1']).optional(),
                identityManifest: relativePath,
                identityManifestHash: hash,
                compatibilityEvidence: relativePath.nullable(),
                ruBudget: z
                    .string()
                    .regex(/^[0-9]+(?:\.[0-9]+)?$/u)
                    .refine((value) => Number(value) > 0)
                    .nullable()
                    .default(null),
                customCaRef: reference.default(null),
                syntheticTestContainer: z.boolean().default(false),
                ruPerSecond: z
                    .string()
                    .regex(/^[0-9]{1,10}(?:\.[0-9]{1,9})?$/u)
                    .refine((value) => Number(value) >= 1 && Number(value) <= 1e9)
                    .optional(),
            })
            .strict()
            .superRefine((source, context) => {
                if (source.sourceMode === 'cosmos-documents' && (!source.documentEndpoint || !source.documentProfile))
                    context.addIssue({
                        code: 'custom',
                        message: 'Document mode requires its explicit endpoint and storage profile',
                    });
                if (source.documentEndpoint) {
                    const url = new URL(source.documentEndpoint);
                    if (url.protocol !== 'https:' || url.pathname !== '/' || url.search)
                        context.addIssue({
                            code: 'custom',
                            message: 'Use the account document endpoint without resource paths or query parameters',
                        });
                }
            })
            .nullable(),
        target: z
            .object({
                datasetUrl: endpoint,
                queryEndpoint: endpoint,
                updateEndpoint: endpoint,
                graphStoreEndpoint: endpoint,
                authenticationRef: reference,
                graphNamespace: iri,
                publicationMode: z.enum(['manual', 'version-pointer']),
                customCaRef: reference.default(null),
            })
            .strict()
            .nullable(),
        limits: z
            .object({
                batchRecords: z.number().int().min(1).max(1000),
                batchBytes: z.number().int().min(1024).max(8388608),
                maxRecordBytes: z.number().int().min(128).max(1048576),
                requestTimeoutMillis: z.number().int().min(100).max(300000),
                maxRetries: z.number().int().min(0).max(10),
                queueCapacity: z.number().int().min(1).max(1000),
                writerConcurrency: z.literal(1),
                minimumFreeDiskBytes: z.number().int().nonnegative(),
                maximumOperationMillis: z.number().int().min(1000).max(86400000).optional(),
            })
            .strict(),
        consistency: z.enum(['quiesced', 'immutable', 'best-effort']),
        phases: z.record(
            z.string(),
            z
                .object({
                    status: phaseStatusSchema,
                    blockers: z.array(z.string()),
                    evidence: z.array(relativePath),
                    inputHash: hash.nullable(),
                })
                .strict(),
        ),
        approvals: z.array(approvalSchema),
        applicationHashes: z.record(relativePath, hash),
        artifacts: z.array(
            z
                .object({
                    relativePath,
                    sha256: hash,
                    ownerRunId: z.string(),
                    sensitive: z.boolean(),
                })
                .strict(),
        ),
    })
    .strict()
    .superRefine((project, context) => {
        if (project.limits.maxRecordBytes > project.limits.batchBytes) {
            context.addIssue({ code: 'custom', message: 'Record limit must not exceed batch limit' });
        }
    });
export type GraphProject = z.infer<typeof graphProjectSchema>;
export type WorkerMessage = z.infer<typeof workerMessageSchema>;
export const workerMessageSchema = z
    .object({
        protocolVersion: z.literal(1),
        type: z.enum([
            'hello',
            'start',
            'pause',
            'cancel',
            'status',
            'progress',
            'warning',
            'checkpoint',
            'result',
            'error',
        ]),
        runId: z.string().max(80),
        operationId: z.string().max(80),
        sequence: z.number().int().nonnegative(),
        timestamp: z.iso.datetime(),
        phase: z.string().max(80),
        payload: z.json(),
    })
    .strict();
export function canonicalJson(value: unknown): string {
    if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
    if (value !== null && typeof value === 'object') {
        return `{${Object.entries(value)
            .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
            .map(([key, child]) => `${JSON.stringify(key)}:${canonicalJson(child)}`)
            .join(',')}}`;
    }
    const result = JSON.stringify(value);
    if (result === undefined) throw new Error('Only canonical JSON values are accepted');
    return result;
}
export function inputHash(project: GraphProject): string {
    return createHash('sha256')
        .update(
            canonicalJson([
                project.migrationKind,
                project.schemaVersion,
                project.baselineRepositorySha,
                project.runId,
                project.protocolVersion,
                project.workerVersion,
                { ...project.mapping, exclusions: [...project.mapping.exclusions].sort() },
                project.source?.sourceMode === 'cosmos-documents'
                    ? { ...project.source, compatibilityEvidence: null }
                    : (project.source ?? ''),
                project.target ?? '',
                project.limits,
                project.consistency,
                project.applicationHashes,
            ]),
        )
        .digest('hex');
}
export function invalidateEvidence(project: GraphProject): GraphProject {
    const current = inputHash(project);
    return {
        ...project,
        phases: Object.fromEntries(
            Object.entries(project.phases).map(([name, phase]) => [
                name,
                phase.inputHash && phase.inputHash !== current ? { ...phase, status: 'stale' as const } : phase,
            ]),
        ),
    };
}
