/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type * as vscode from 'vscode';
import { writeNewArtifact } from '../../services/graphMigration/artifacts';
import { canonicalJson, inputHash, type WorkerMessage } from '../../services/graphMigration/contracts';
import { GraphProjectService } from '../../services/graphMigration/GraphProjectService';
import { adoptEnumeration, freshPreparation, seedPartitions } from '../../services/graphMigration/preparation';
import { GraphMigrationTab } from './GraphMigrationTab';
import { type GraphRouterContext } from './graphRouter';

const mocks = vi.hoisted(() => ({
    context: undefined as GraphRouterContext | undefined,
    message: undefined as ((message: WorkerMessage) => void) | undefined,
    start: vi.fn(),
    events: vi.fn(),
    confirm: vi.fn(),
    active: false,
    trusted: true,
}));
vi.mock('../BaseTab', () => {
    class BaseTab {
        disposables: vscode.Disposable[] = [];
        dispose() {}
    }
    return { BaseTab, default: { BaseTab } };
});
vi.mock('@microsoft/vscode-ext-webview', () => {
    class TypedEventSink {
        emit = mocks.events;
        close() {}
    }
    return { TypedEventSink, default: { TypedEventSink } };
});
vi.mock('@microsoft/vscode-ext-webview/host', () => ({
    attachTrpc: (_: unknown, context: GraphRouterContext) => {
        mocks.context = context;
        return { disposable: { dispose() {} } };
    },
    default: {
        attachTrpc: (_: unknown, context: GraphRouterContext) => {
            mocks.context = context;
            return { disposable: { dispose() {} } };
        },
    },
}));
vi.mock('./graphRouter', () => ({ graphAppRouter: {}, graphCallerFactory: {} }));
vi.mock('../../extensionVariables', () => {
    const ext = { context: { extensionPath: 'test-extension', secrets: { get: vi.fn(), store: vi.fn() } } };
    return { ext, default: { ext } };
});
vi.mock('vscode', () => ({
    extensions: { getExtension: vi.fn() },
    workspace: {
        get isTrusted() {
            return mocks.trusted;
        },
    },
    window: { showWarningMessage: mocks.confirm },
}));
vi.mock('../../services/graphMigration/WorkerBridge', () => ({
    WorkerBridge: class {
        constructor(_: unknown, _secrets: unknown, message: (message: WorkerMessage) => void) {
            mocks.message = message;
        }
        get active() {
            return mocks.active;
        }
        start = mocks.start;
        cancel() {}
        pause() {}
        dispose() {}
    },
}));

let workspace: string, context: GraphRouterContext, service: GraphProjectService;
beforeEach(async () => {
    vi.clearAllMocks();
    mocks.active = false;
    mocks.trusted = true;
    mocks.start.mockResolvedValue(undefined);
    mocks.confirm.mockResolvedValue('Import reviewed evidence');
    workspace = await mkdtemp(path.join(tmpdir(), 'graph-editor-owned-'));
    new GraphMigrationTab({} as vscode.WebviewPanel, workspace);
    if (!mocks.context) throw new Error('Context was not attached');
    context = mocks.context;
    service = new GraphProjectService(workspace);
});
afterEach(async () => {
    await rm(workspace, { recursive: true, force: true });
});

async function complete(payload: WorkerMessage['payload']) {
    const before = mocks.events.mock.calls.length;
    mocks.message?.({
        protocolVersion: 1,
        type: 'result',
        runId: 'fixture',
        operationId: 'owned',
        sequence: 1,
        timestamp: new Date().toISOString(),
        phase: 'complete',
        payload,
    });
    await vi.waitFor(() =>
        expect(mocks.events.mock.calls.slice(before).some(([event]) => event.type === 'project')).toBe(true),
    );
}

describe('persisted graph editor workflow', () => {
    it('starts guided preparation with bounded settings and exposes running state to Pause and Cancel', async () => {
        await context.create();
        await context.saveSetup({
            host: 'account.gremlin.cosmos.azure.com',
            database: 'db',
            container: 'graph',
            partitionKeyPath: '/pk',
            datasetUrl: 'http://localhost:3030/test',
            consistency: 'quiesced',
        });
        const prior = await service.load();
        await context.prepareSource({
            partitions: ['p'],
            maximumRequests: 100,
            maximumDurationSeconds: 30,
            ruBudget: '100',
            confirmStableSource: true,
        });
        const current = await service.load();
        expect(current.runId).not.toBe(prior.runId);
        expect(current.phases['source-enumerate'].status).toBe('running');
        expect(mocks.start).toHaveBeenCalledWith(
            expect.objectContaining({ runId: current.runId }),
            expect.any(String),
            'source-enumerate',
            expect.arrayContaining([`artifacts/enumeration-plan-${current.runId}.json`]),
        );
        expect(mocks.events).toHaveBeenCalledWith({
            type: 'project',
            project: expect.objectContaining({
                phases: expect.objectContaining({ 'source-enumerate': expect.objectContaining({ status: 'running' }) }),
            }),
        });
    });
    it('parses static seed candidates without evaluating code or matching comments/quoted fake properties', () => {
        expect(
            seedPartitions(
                "g.addV('person').property('pk','a')\ng.addV('person').property('pk',variable)\n// g.addV('x').property('pk','fake')\ng.addV('person').property('text',\".property('pk','fake')\")",
                'pk',
            ),
        ).toEqual(['a']);
    });
    it('adopts only reconciled owned inventory into a fresh extraction run and preserves the prior configuration', async () => {
        await context.create();
        await context.saveSetup({
            host: 'account.gremlin.cosmos.azure.com',
            database: 'db',
            container: 'graph',
            partitionKeyPath: '/pk',
            datasetUrl: 'http://localhost:3030/test',
            consistency: 'quiesced',
        });
        const prior = await service.load(),
            root = await service.root();
        const prepared = await freshPreparation(prior, root, {
            partitions: ['p'],
            maximumRequests: 100,
            maximumDurationSeconds: 30,
            ruBudget: '100',
            confirmStableSource: true,
        });
        const bytes =
                '{"kind":"vertex","partition":{"type":"string","lexical":"p"},"id":{"type":"string","lexical":"v"}}\n',
            sha256 = createHash('sha256').update(bytes).digest('hex');
        await writeNewArtifact(root, `${prepared.output}/identities.ndjson`, bytes);
        const plan = JSON.parse(await readFile(path.join(root, prepared.plan), 'utf8'));
        await writeNewArtifact(
            root,
            `${prepared.output}/manifest.json`,
            JSON.stringify({
                schemaVersion: 1,
                status: 'succeeded',
                runId: prepared.project.runId,
                inputHash: inputHash(prepared.project),
                sha256,
                planHash: createHash('sha256').update(canonicalJson(plan)).digest('hex'),
                vertices: 1,
                edges: 0,
                identityTypes: 'string-only',
                edgeScope: 'sourceVertex',
            }),
        );
        const adopted = await adoptEnumeration(prepared.project, root, prepared.output);
        expect(adopted.runId).not.toBe(prepared.project.runId);
        expect(adopted.source?.identityManifestHash).toBe(sha256);
        expect(adopted.source?.identityManifest).toBe(`${prepared.output}/identities.ndjson`);
        expect(adopted.approvals).toEqual([]);
        expect(adopted.source?.compatibilityEvidence).not.toBe(prepared.project.source?.compatibilityEvidence);
        await writeFile(path.join(root, prepared.output, 'identities.ndjson'), 'tampered');
        await expect(adoptEnumeration(prepared.project, root, prepared.output)).rejects.toThrow();
    });
    it('keeps specific blocked diagnostics in the persisted phase instead of a generic failure', async () => {
        await context.create();
        await context.run('preflight', '', '', '');
        await complete({
            status: 'blocked',
            diagnostics: [
                {
                    status: 'blocked',
                    code: 'ENDPOINT_AUTHENTICATION',
                    stage: 'target-connectivity',
                    remediation: 'Renew the configured credential securely.',
                },
            ],
        });
        await vi.waitFor(async () =>
            expect((await service.load()).phases.preflight.blockers.join(' ')).toContain('ENDPOINT_AUTHENTICATION'),
        );
    });
    it('saves simple connection settings with derived endpoints and a real bootstrap inventory', async () => {
        await context.create();
        await context.saveSetup({
            host: 'account.gremlin.cosmos.azure.com',
            database: 'db',
            container: 'graph',
            partitionKeyPath: '/tenant',
            datasetUrl: 'http://localhost:3030/graph',
            consistency: 'quiesced',
        });
        const loaded = await service.load();
        expect(loaded.source?.authenticationRef).toBe('env:GRAPH_SOURCE_KEY');
        expect(loaded.target?.queryEndpoint).toBe('http://localhost:3030/graph/query');
        expect(loaded.consistency).toBe('quiesced');
        expect(loaded.source?.identityManifestHash).toBe(createHash('sha256').update('').digest('hex'));
        expect(mocks.start).not.toHaveBeenCalled();
    });
    it('creates separate graph state and pauses orphaned work after restart without changing relational files', async () => {
        const relational = path.join(workspace, 'relational-project.json');
        await writeFile(relational, '{"untouched":true}');
        const project = await context.create();
        expect(project.migrationKind).toBe('cosmos-gremlin-to-jena');
        project.phases.load = { status: 'running', inputHash: inputHash(project), evidence: [], blockers: [] };
        await service.save(project);
        const loaded = await context.load();
        expect(loaded.project?.phases.load.status).toBe('paused');
        expect((await service.load()).phases.load.status).toBe('paused');
        expect(await readFile(relational, 'utf8')).toBe('{"untouched":true}');
    });
    it('registers actual checksummed outputs and makes superseded evidence stale', async () => {
        const project = await context.create(),
            root = await service.root();
        const old = Buffer.from('old'),
            output = Buffer.from('new');
        const checksum = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex');
        project.artifacts = [
            { relativePath: 'records.ndjson', sha256: checksum(old), ownerRunId: project.runId, sensitive: true },
        ];
        project.phases.extract = {
            status: 'succeeded',
            inputHash: inputHash(project),
            evidence: ['records.ndjson'],
            blockers: [],
        };
        await service.save(project);
        await context.run('synthetic', '', '', '');
        await writeFile(path.join(root, 'records.ndjson'), output);
        await complete({
            status: 'succeeded',
            inputHash: inputHash(project),
            artifacts: [
                {
                    relativePath: 'records.ndjson',
                    sha256: checksum(output),
                    ownerRunId: project.runId,
                    sensitive: true,
                },
            ],
        });
        const saved = await service.load();
        expect(saved.phases.synthetic.status).toBe('succeeded');
        expect(saved.phases.extract.status).toBe('stale');
        expect(saved.artifacts).toHaveLength(1);
        expect(saved.artifacts[0].sha256).toBe(checksum(output));
    });
    it('refuses tampered completion evidence and retains running ownership rather than accepting success', async () => {
        const project = await context.create(),
            root = await service.root();
        await context.run('synthetic', '', '', '');
        await writeFile(path.join(root, 'records.ndjson'), 'tampered');
        mocks.message?.({
            protocolVersion: 1,
            type: 'result',
            runId: project.runId,
            operationId: 'owned',
            sequence: 1,
            timestamp: new Date().toISOString(),
            phase: 'complete',
            payload: {
                status: 'succeeded',
                inputHash: inputHash(project),
                artifacts: [
                    {
                        relativePath: 'records.ndjson',
                        sha256: '0'.repeat(64),
                        ownerRunId: project.runId,
                        sensitive: true,
                    },
                ],
            },
        });
        await vi.waitFor(() =>
            expect(mocks.events).toHaveBeenCalledWith({ type: 'diagnostic', code: 'STATE_SAVE_FAILED' }),
        );
        expect((await service.load()).phases.synthetic.status).toBe('running');
        await expect(context.run('mapping', '', '', '')).rejects.toThrow('already active');
    });
    it('blocks admission while a completed worker still owns its process', async () => {
        await context.create();
        mocks.active = true;
        await expect(context.run('mapping', '', '', '')).rejects.toThrow('already active');
        expect(mocks.start).not.toHaveBeenCalled();
    });
    it('acknowledges a preparation pause without launching a worker', async () => {
        await context.create();
        const operation = context.run('mapping', '', '', '');
        context.pause();
        await operation;
        expect(mocks.start).not.toHaveBeenCalled();
        expect((await service.load()).phases.mapping.status).toBe('paused');
    });
    it('fails closed on existing crash artifacts instead of adopting or deleting them', async () => {
        await context.create();
        const root = await service.root();
        await writeFile(path.join(root, 'project.lock'), 'foreign-owner');
        await writeFile(path.join(root, 'project.pending'), '{"foreignIntent":true}');
        await expect(service.save(await service.load())).rejects.toThrow();
        expect(await readFile(path.join(root, 'project.lock'), 'utf8')).toBe('foreign-owner');
        expect(await readFile(path.join(root, 'project.pending'), 'utf8')).toBe('{"foreignIntent":true}');
    });
    it('imports only an explicitly reviewed current run envelope, not stale evidence or credentials', async () => {
        const project = await context.create(),
            root = await service.root();
        const envelope = {
            schemaVersion: 1,
            runId: project.runId,
            inputHash: inputHash(project),
            operation: 'application-build',
            status: 'succeeded',
            sensitiveArtifact: true,
            result: { beforeExitCode: 0, afterExitCode: 0, equivalentCases: 10, remainingBlockers: [] },
        };
        await writeFile(path.join(root, 'build.json'), JSON.stringify({ ...envelope, inputHash: '0'.repeat(64) }));
        await expect(context.importEvidence('build.json', 'application-build')).rejects.toThrow('current inputs');
        await writeFile(
            path.join(root, 'build.json'),
            JSON.stringify({ ...envelope, result: { password: 'fixture-rejected-value' } }),
        );
        await expect(context.importEvidence('build.json', 'application-build')).rejects.toThrow('Credential fields');
        expect(mocks.confirm).not.toHaveBeenCalled();
        await writeFile(path.join(root, 'build.json'), JSON.stringify(envelope));
        await context.importEvidence('build.json', 'application-build');
        expect(mocks.confirm).toHaveBeenCalledOnce();
        const saved = await service.load();
        expect(saved.phases['application-build'].evidence).toEqual(['build.json']);
        expect(saved.artifacts[0].sensitive).toBe(true);
        expect(mocks.start).not.toHaveBeenCalled();
    });
    it('refuses imported evidence changed during the operator review', async () => {
        const project = await context.create(),
            root = await service.root();
        await writeFile(
            path.join(root, 'build.json'),
            JSON.stringify({
                schemaVersion: 1,
                runId: project.runId,
                inputHash: inputHash(project),
                operation: 'application-build',
                status: 'blocked',
                sensitiveArtifact: true,
                result: {},
            }),
        );
        mocks.confirm.mockImplementation(async () => {
            await writeFile(path.join(root, 'build.json'), '{}');
            return 'Import reviewed evidence';
        });
        await expect(context.importEvidence('build.json', 'application-build')).rejects.toThrow(
            'changed during import',
        );
        expect((await service.load()).phases['application-build']).toBeUndefined();
    });
    it('imports checksum-verified linked reports only after the shared operator review', async () => {
        const project = await context.create(),
            root = await service.root();
        const report = JSON.stringify({ status: 'succeeded', profile: 'load' });
        await writeFile(path.join(root, 'measured.json'), report);
        const sha256 = createHash('sha256').update(report).digest('hex');
        const envelope = {
            schemaVersion: 1,
            runId: project.runId,
            inputHash: inputHash(project),
            operation: 'test-load',
            status: 'succeeded',
            sensitiveArtifact: true,
            result: {
                status: 'succeeded',
                reportPath: 'measured.json',
                reportSha256: sha256,
                result: JSON.parse(report),
            },
        };
        await writeFile(path.join(root, 'load.json'), JSON.stringify(envelope));
        await context.importEvidence('load.json', 'test-load');
        const saved = await service.load();
        expect(saved.artifacts.map((artifact) => artifact.relativePath).sort()).toEqual(['load.json', 'measured.json']);
        expect(saved.phases['test-load'].evidence).toEqual(['load.json']);
        expect(mocks.confirm).toHaveBeenCalledOnce();
        expect(mocks.start).not.toHaveBeenCalled();
    });
    it('rejects linked reports changed during review without registering partial evidence', async () => {
        const project = await context.create(),
            root = await service.root();
        await writeFile(path.join(root, 'measured.json'), '{}');
        await writeFile(
            path.join(root, 'load.json'),
            JSON.stringify({
                schemaVersion: 1,
                runId: project.runId,
                inputHash: inputHash(project),
                operation: 'test-load',
                status: 'blocked',
                sensitiveArtifact: true,
                result: { reportPath: 'measured.json', reportSha256: createHash('sha256').update('{}').digest('hex') },
            }),
        );
        mocks.confirm.mockImplementation(async () => {
            await writeFile(path.join(root, 'measured.json'), '{"changed":true}');
            return 'Import reviewed evidence';
        });
        await expect(context.importEvidence('load.json', 'test-load')).rejects.toThrow('Linked evidence changed');
        expect((await service.load()).artifacts).toEqual([]);
    });
});
