/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { createHash } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type * as vscode from 'vscode';
import { graphProjectSchema } from './contracts';
import { formatDiagnostic, safeDiagnosticSchema } from './diagnostics';
import { WorkerBridge } from './WorkerBridge';

const mocks = vi.hoisted(() => ({ spawn: vi.fn(), readFile: vi.fn(), get: vi.fn(), trusted: true }));
vi.mock('node:child_process', () => ({ spawn: mocks.spawn }));
vi.mock('node:fs/promises', () => ({ readFile: mocks.readFile }));
vi.mock('./artifacts', () => ({
    readBounded: async (name: string) => Buffer.from(await mocks.readFile(name)),
    fileHash: async (name: string) =>
        createHash('sha256')
            .update(await mocks.readFile(name))
            .digest('hex'),
}));
vi.mock('vscode', () => ({
    workspace: {
        get isTrusted() {
            return mocks.trusted;
        },
    },
}));
const bytes = Buffer.from('owned-test-package');
const hash = createHash('sha256').update(bytes).digest('hex');
function project() {
    return graphProjectSchema.parse({
        schemaVersion: 1,
        migrationKind: 'cosmos-gremlin-to-jena',
        baselineRepositorySha: '0'.repeat(40),
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
        source: {
            scope: 'fixture',
            host: 'localhost',
            port: 443,
            database: 'db',
            container: 'test',
            partitionKeyPath: '/pk',
            authenticationRef: 'env:GRAPH_SOURCE_TEST',
            customCaRef: 'env:GRAPH_SOURCE_CA_TEST',
            extractionStrategy: 'verified-identity-manifest',
            identityManifest: 'identities.ndjson',
            identityManifestHash: hash,
            compatibilityEvidence: 'probe.json',
        },
        target: {
            datasetUrl: 'http://localhost/ds',
            queryEndpoint: 'http://localhost/ds/query',
            updateEndpoint: 'http://localhost/ds/update',
            graphStoreEndpoint: 'http://localhost/ds/data',
            authenticationRef: 'env:GRAPH_TARGET_TEST',
            customCaRef: 'env:GRAPH_TARGET_CA_TEST',
            graphNamespace: 'urn:test:',
            publicationMode: 'manual',
        },
        limits: {
            batchRecords: 1,
            batchBytes: 1024,
            maxRecordBytes: 1024,
            requestTimeoutMillis: 1000,
            maxRetries: 0,
            queueCapacity: 1,
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
beforeEach(() => {
    vi.clearAllMocks();
    mocks.trusted = true;
    mocks.readFile.mockImplementation((name: string) =>
        Promise.resolve(
            name.endsWith('worker-manifest.json')
                ? JSON.stringify({ workerSha256: hash, sourceSha256: hash, testkitSha256: hash })
                : bytes,
        ),
    );
    mocks.get.mockResolvedValue(undefined);
    mocks.spawn.mockImplementation(() =>
        Object.assign(new EventEmitter(), {
            stdout: new PassThrough(),
            stderr: new PassThrough(),
            stdin: new PassThrough(),
            kill: vi.fn().mockReturnValue(true),
        }),
    );
});
afterEach(() => {
    vi.useRealTimers();
});
function storage(): vscode.SecretStorage {
    return { get: mocks.get, keys: vi.fn(async () => []), store: vi.fn(), delete: vi.fn(), onDidChange: vi.fn() };
}
function bridge() {
    return new WorkerBridge('package', storage(), vi.fn(), vi.fn());
}
describe('worker credential isolation', () => {
    it.runIf(process.platform === 'win32' && process.arch === 'x64')(
        'verifies every SDK runtime file and never falls back to the Gremlin adapter',
        async () => {
            const selected = project();
            selected.source = {
                ...selected.source!,
                sourceMode: 'cosmos-documents',
                documentEndpoint: 'https://fixture.documents.azure.com/',
                documentProfile: 'property-bags-v1',
            };
            let corrupt = true;
            const reads: string[] = [];
            mocks.readFile.mockImplementation(async (name: string) => {
                reads.push(name);
                if (name.includes('document-source') && name.endsWith('worker-manifest.json'))
                    return JSON.stringify({
                        schemaVersion: 1,
                        rid: 'win-x64',
                        executable: 'DocumentSource.exe',
                        sdkVersion: '3.58.0',
                        files: {
                            'DocumentSource.exe': hash,
                            'Microsoft.Azure.Cosmos.Client.dll': corrupt ? '0'.repeat(64) : hash,
                        },
                    });
                if (name.endsWith('cosmos-gremlin-source-0.1.0.jar'))
                    throw new Error('Gremlin must not be a document-mode prerequisite');
                return name.endsWith('worker-manifest.json') ? JSON.stringify({ workerSha256: hash }) : bytes;
            });
            mocks.get.mockResolvedValue('private-source-key');
            const worker = bridge();
            await expect(worker.start(selected, 'run', 'source-compatibility', [])).rejects.toMatchObject({
                code: 'WORKER_PACKAGE_INVALID',
                stage: 'document-source-integrity',
            });
            expect(mocks.spawn).not.toHaveBeenCalled();
            corrupt = false;
            await worker.start(selected, 'run', 'source-compatibility', []);
            expect(mocks.spawn).toHaveBeenCalledTimes(1);
            expect(reads.some((name) => name.endsWith('cosmos-gremlin-source-0.1.0.jar'))).toBe(false);
            const child = mocks.spawn.mock.results[0].value;
            child.emit('close', 2);
            expect(mocks.spawn).toHaveBeenCalledTimes(1);
            expect(selected.source.sourceMode).toBe('cosmos-documents');
        },
    );
    it('waits for the owned process close before allowing completion to expose another Run', async () => {
        const message = vi.fn(),
            failure = vi.fn();
        const worker = new WorkerBridge('package', storage(), message, failure);
        await worker.start(project(), 'run', 'synthetic', []);
        const child = mocks.spawn.mock.results[0].value;
        const frame = {
            protocolVersion: 1,
            timestamp: new Date().toISOString(),
            phase: 'complete',
        };
        child.stdout.emit(
            'data',
            Buffer.from(
                JSON.stringify({
                    ...frame,
                    type: 'hello',
                    runId: '',
                    operationId: '',
                    sequence: 0,
                    payload: { workerVersion: '0.1.0' },
                }) + '\n',
            ),
        );
        const start = JSON.parse(child.stdin.read().toString().trim());
        child.stdout.emit(
            'data',
            Buffer.from(
                JSON.stringify({
                    ...frame,
                    type: 'result',
                    runId: start.runId,
                    operationId: start.operationId,
                    sequence: 1,
                    payload: { status: 'succeeded' },
                }) + '\n',
            ),
        );
        expect(message).toHaveBeenLastCalledWith(expect.objectContaining({ type: 'result' }));
        let resolved = false;
        const closed = worker.waitForExit().then(() => {
            resolved = true;
        });
        await Promise.resolve();
        expect(resolved).toBe(false);
        expect(worker.active).toBe(true);
        child.emit('close', 0);
        await closed;
        expect(worker.active).toBe(false);
        expect(failure).not.toHaveBeenCalled();
        await worker.waitForExit();
    });
    it('retains bounded artifact action/attempts through safe diagnostics and rendered remediation', () => {
        const diagnostic = {
            status: 'blocked',
            code: 'ARTIFACT_ATOMIC_REPLACE_ACCESS',
            stage: 'source-enumeration-checkpoint-write',
            remediation: 'Atomic replacement remained denied. Preserve both files.',
            artifactAction: 'atomic-replace',
            artifactAttempts: 5,
        };
        const parsed = safeDiagnosticSchema.parse(diagnostic);
        expect(formatDiagnostic(parsed)).toContain('atomic-replace');
        expect(formatDiagnostic(parsed)).toContain('local storage attempts 5');
        for (const invalid of [
            { ...diagnostic, artifactAction: 'private-file' },
            { ...diagnostic, artifactAttempts: 6 },
            { ...diagnostic, artifactAttempts: 0 },
            { ...diagnostic, path: 'private-path' },
        ])
            expect(safeDiagnosticSchema.safeParse(invalid).success).toBe(false);
    });
    it('formats only bounded numeric effective-deadline observations, never arbitrary context fields', () => {
        const diagnostic = {
            code: 'OPERATION_DEADLINE',
            stage: 'worker-control',
            status: 'blocked',
            remediation: 'The configured overall operation time limit was reached.',
            operationLimitMillis: 120000,
            elapsedMillis: 120001,
        };
        const parsed = safeDiagnosticSchema.parse(diagnostic);
        expect(formatDiagnostic(parsed)).toContain('operation limit 120 seconds');
        expect(formatDiagnostic(parsed)).toContain('observed host elapsed 120 seconds');
        for (const payload of [
            { ...diagnostic, operationLimitMillis: 86400001 },
            { ...diagnostic, elapsedMillis: -1 },
            { ...diagnostic, elapsedMillis: Infinity },
            { ...diagnostic, operationLimitMillis: 'private' },
            { ...diagnostic, providerMessage: 'private' },
        ])
            expect(safeDiagnosticSchema.safeParse(payload).success).toBe(false);
    });
    it('does not relabel an actual operator pause as a deadline while waiting for the owned worker to stop', async () => {
        vi.useFakeTimers();
        const failure = vi.fn();
        const worker = new WorkerBridge('package', storage(), vi.fn(), failure);
        const input = project();
        input.limits.maximumOperationMillis = 1000;
        await worker.start(input, 'run', 'synthetic', []);
        const child = mocks.spawn.mock.results[0].value;
        worker.pause();
        await vi.advanceTimersByTimeAsync(1000);
        expect(failure).not.toHaveBeenCalled();
        expect(child.kill).not.toHaveBeenCalled();
        child.emit('close', 0);
        expect(failure).toHaveBeenCalledWith('WORKER_DISCONNECTED', 'paused');
    });
    it('enforces the overall deadline and terminates only the owned worker after pause grace', async () => {
        vi.useFakeTimers();
        const failure = vi.fn();
        const worker = new WorkerBridge('package', storage(), vi.fn(), failure);
        const input = project();
        input.limits.maximumOperationMillis = 1000;
        await worker.start(input, 'run', 'synthetic', []);
        const child = mocks.spawn.mock.results[0].value;
        await vi.advanceTimersByTimeAsync(999);
        expect(failure).not.toHaveBeenCalled();
        await vi.advanceTimersByTimeAsync(1);
        expect(failure).toHaveBeenCalledWith(
            'OPERATION_DEADLINE_PARTIAL_STATE_PRESERVED',
            'paused',
            true,
            expect.objectContaining({ operationLimitMillis: 1000, elapsedMillis: 1000 }),
        );
        expect(child.kill).not.toHaveBeenCalled();
        await vi.advanceTimersByTimeAsync(5000);
        expect(child.kill).toHaveBeenCalledOnce();
        child.emit('close', null);
        await vi.advanceTimersByTimeAsync(10000);
        expect(child.kill).toHaveBeenCalledOnce();
    });
    it('keeps accepting progress and the real evidence acknowledgement during deadline pause grace, never late success', async () => {
        vi.useFakeTimers();
        const failure = vi.fn(),
            message = vi.fn();
        const worker = new WorkerBridge('package', storage(), message, failure);
        const input = project();
        input.limits.maximumOperationMillis = 1000;
        await worker.start(input, 'run', 'synthetic', []);
        const child = mocks.spawn.mock.results[0].value;
        child.stdout.emit(
            'data',
            Buffer.from(
                JSON.stringify({
                    protocolVersion: 1,
                    type: 'hello',
                    runId: '',
                    operationId: '',
                    sequence: 0,
                    timestamp: new Date().toISOString(),
                    phase: 'idle',
                    payload: { workerVersion: '0.1.0' },
                }) + '\n',
            ),
        );
        const start = JSON.parse(child.stdin.read().toString().trim());
        await vi.advanceTimersByTimeAsync(1000);
        expect(JSON.parse(child.stdin.read().toString().trim())).toMatchObject({
            type: 'pause',
            payload: { reason: 'operation-deadline' },
            runId: input.runId,
            operationId: start.operationId,
        });
        const frame = {
            protocolVersion: 1,
            runId: input.runId,
            operationId: start.operationId,
            timestamp: new Date().toISOString(),
            phase: 'synthetic',
        };
        child.stdout.emit(
            'data',
            Buffer.from(
                JSON.stringify({
                    ...frame,
                    type: 'progress',
                    sequence: 1,
                    payload: { activity: 'worker-active', elapsedMillis: 1100 },
                }) + '\n',
            ),
        );
        child.stdout.emit(
            'data',
            Buffer.from(
                JSON.stringify({
                    ...frame,
                    type: 'result',
                    sequence: 2,
                    payload: {
                        status: 'succeeded',
                        artifact: { relativePath: 'artifacts/report.json' },
                        diagnostics: [
                            {
                                code: 'OPERATOR_PAUSED',
                                stage: 'worker-control',
                                status: 'blocked',
                                remediation: 'The operator requested Pause.',
                            },
                            {
                                code: 'OPERATION_DEADLINE',
                                stage: 'worker-control',
                                status: 'blocked',
                                remediation: 'The configured overall operation time limit was reached.',
                            },
                        ],
                    },
                }) + '\n',
            ),
        );
        expect(message).toHaveBeenCalledWith(expect.objectContaining({ type: 'progress', sequence: 1 }));
        expect(message).toHaveBeenCalledWith(
            expect.objectContaining({
                type: 'result',
                payload: expect.objectContaining({
                    status: 'paused',
                    artifact: { relativePath: 'artifacts/report.json' },
                    diagnostics: [
                        expect.objectContaining({
                            code: 'OPERATION_DEADLINE_PARTIAL_STATE_PRESERVED',
                            operationLimitMillis: 1000,
                            elapsedMillis: 1000,
                        }),
                    ],
                }),
            }),
        );
        child.emit('close', 0);
        await vi.advanceTimersByTimeAsync(5000);
        expect(child.kill).not.toHaveBeenCalled();
        expect(failure).toHaveBeenCalledTimes(1);
    });
    it('injects only source enumeration credentials and exact target probe authorization', async () => {
        mocks.get.mockResolvedValue('test-only-value');
        await bridge().start(project(), 'run', 'source-enumerate', []);
        expect(mocks.get.mock.calls.map(([name]) => name)).toEqual([
            'graph-migration:env:GRAPH_SOURCE_TEST',
            'graph-migration:env:GRAPH_SOURCE_CA_TEST',
        ]);
        mocks.get.mockClear();
        const input = project();
        input.approvals.push({
            schemaVersion: 1,
            runId: input.runId,
            operation: 'target-probe',
            resources: ['urn:probe'],
            inputHash: hash,
            expiresAt: '2030-01-01T00:00:00Z',
            keyRef: 'env:GRAPH_PROBE_TEST',
            signature: hash,
        });
        await bridge().start(input, 'run', 'preflight', ['probe']);
        expect(mocks.get.mock.calls.map(([name]) => name)).toEqual([
            'graph-migration:env:GRAPH_TARGET_TEST',
            'graph-migration:env:GRAPH_TARGET_CA_TEST',
            'graph-migration:env:GRAPH_PROBE_TEST',
        ]);
    });
    it('runs offline operations without requiring configured live secrets', async () => {
        await bridge().start(project(), 'run', 'synthetic', ['generate', 'semantic', '1', 'records.ndjson']);
        expect(mocks.get).not.toHaveBeenCalled();
        expect(mocks.spawn).toHaveBeenCalledOnce();
        expect(mocks.spawn.mock.calls[0][2].env.GRAPH_SOURCE_TEST).toBeUndefined();
    });
    it('collects only source credentials for a read-only probe', async () => {
        mocks.get.mockResolvedValue('test-only-value');
        await bridge().start(project(), 'run', 'source-compatibility', []);
        expect(mocks.get.mock.calls.map(([name]) => name)).toEqual([
            'graph-migration:env:GRAPH_SOURCE_TEST',
            'graph-migration:env:GRAPH_SOURCE_CA_TEST',
        ]);
        expect(mocks.spawn.mock.calls[0][2].env.GRAPH_TARGET_TEST).toBeUndefined();
    });
    it('requires only applicable target and authorization references for load', async () => {
        const input = project();
        for (const operation of ['reduced-fidelity', 'best-effort-source', 'target-write', 'cleanup']) {
            input.approvals.push({
                schemaVersion: 1,
                runId: input.runId,
                operation,
                resources: ['scope'],
                inputHash: hash,
                expiresAt: '2030-01-01T00:00:00Z',
                keyRef: `env:GRAPH_${operation.replaceAll('-', '_').toUpperCase()}`,
                signature: hash,
            });
        }
        mocks.get.mockResolvedValue('test-only-value');
        await bridge().start(input, 'run', 'load', []);
        expect(mocks.get.mock.calls.map(([name]) => name)).toEqual([
            'graph-migration:env:GRAPH_TARGET_TEST',
            'graph-migration:env:GRAPH_TARGET_CA_TEST',
            'graph-migration:env:GRAPH_TARGET_WRITE',
        ]);
    });
    it('fails before spawning when trust or a relevant credential is missing', async () => {
        mocks.trusted = false;
        await expect(bridge().start(project(), 'run', 'synthetic', [])).rejects.toThrow('Workspace trust');
        mocks.trusted = true;
        await expect(bridge().start(project(), 'run', 'extract', [])).rejects.toThrow(
            'CREDENTIAL_REFERENCE_UNAVAILABLE',
        );
        expect(mocks.spawn).not.toHaveBeenCalled();
    });
    it('collects exact fixture and rewrite keys without unrelated source or target references', async () => {
        const input = project();
        for (const operation of ['fixture-test', 'application-write', 'application-delete', 'rewrite', 'cleanup']) {
            input.approvals.push({
                schemaVersion: 1,
                runId: input.runId,
                operation,
                resources: ['scope'],
                inputHash: hash,
                expiresAt: '2030-01-01T00:00:00Z',
                keyRef: `env:GRAPH_${operation.replaceAll('-', '_').toUpperCase()}`,
                signature: hash,
            });
        }
        mocks.get.mockResolvedValue('test-only-value');
        await bridge().start(input, 'run', 'test', ['fixtures']);
        expect(mocks.get.mock.calls.map(([name]) => name)).toEqual([
            'graph-migration:env:GRAPH_TARGET_TEST',
            'graph-migration:env:GRAPH_TARGET_CA_TEST',
            'graph-migration:env:GRAPH_FIXTURE_TEST',
            'graph-migration:env:GRAPH_APPLICATION_WRITE',
            'graph-migration:env:GRAPH_APPLICATION_DELETE',
        ]);
        mocks.get.mockClear();
        await bridge().start(input, 'run', 'rewrite', ['apply']);
        expect(mocks.get.mock.calls.map(([name]) => name)).toEqual(['graph-migration:env:GRAPH_REWRITE']);
    });
    it('stops only the owned unresponsive worker after five seconds and preserves paused state', async () => {
        vi.useFakeTimers();
        const failure = vi.fn();
        const secrets = storage();
        const worker = new WorkerBridge('package', secrets, vi.fn(), failure);
        await worker.start(project(), 'run', 'synthetic', []);
        const child = mocks.spawn.mock.results[0].value;
        worker.pause();
        await vi.advanceTimersByTimeAsync(4999);
        expect(child.kill).not.toHaveBeenCalled();
        await vi.advanceTimersByTimeAsync(1);
        expect(child.kill).toHaveBeenCalledOnce();
        expect(failure).toHaveBeenCalledWith('WORKER_TERMINATION_REQUESTED_PARTIAL_STATE_PRESERVED', 'paused');
        child.emit('close', null);
        await vi.advanceTimersByTimeAsync(5000);
        expect(child.kill).toHaveBeenCalledOnce();
        expect(failure).toHaveBeenCalledOnce();
    });
    it('rejects oversized frames once and clears shutdown timers after owned exit', async () => {
        vi.useFakeTimers();
        const failure = vi.fn();
        const secrets = storage();
        const worker = new WorkerBridge('package', secrets, vi.fn(), failure);
        await worker.start(project(), 'run', 'synthetic', []);
        const child = mocks.spawn.mock.results[0].value;
        child.stdout.emit('data', Buffer.alloc(65537, 120));
        child.stdout.emit('data', Buffer.from('more-untrusted-output'));
        expect(failure).toHaveBeenCalledOnce();
        expect(failure).toHaveBeenCalledWith('INVALID_WORKER_PROTOCOL', undefined);
        child.emit('close', 2);
        await vi.advanceTimersByTimeAsync(5000);
        expect(child.kill).not.toHaveBeenCalled();
        expect(failure).toHaveBeenCalledOnce();
    });
    it('does not accept a clean process exit without a terminal worker acknowledgement', async () => {
        const failure = vi.fn();
        const worker = new WorkerBridge('package', storage(), vi.fn(), failure);
        await worker.start(project(), 'run', 'synthetic', []);
        mocks.spawn.mock.results[0].value.emit('close', 0);
        expect(failure).toHaveBeenCalledWith('WORKER_DISCONNECTED', undefined);
        expect(worker.active).toBe(false);
    });
    it('runs private benchmarks without forwarding live or approval credentials', async () => {
        await bridge().start(project(), 'run', 'test', ['benchmark']);
        expect(mocks.get).not.toHaveBeenCalled();
        const environment = mocks.spawn.mock.calls[0][2].env;
        expect(Object.keys(environment).some((name) => name.startsWith('GRAPH_'))).toBe(false);
    });
});
