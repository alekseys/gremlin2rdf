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
        await expect(bridge().start(project(), 'run', 'extract', [])).rejects.toThrow('credential reference');
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
