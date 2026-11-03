/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type * as vscode from 'vscode';
import { writeNewArtifact, fileHash } from '../../services/graphMigration/artifacts';
import {
    canonicalJson,
    graphProjectSchema,
    inputHash,
    type WorkerMessage,
} from '../../services/graphMigration/contracts';
import { type SafeDiagnostic } from '../../services/graphMigration/diagnostics';
import { GraphProjectService } from '../../services/graphMigration/GraphProjectService';
import { adoptEnumeration, freshPreparation, seedPartitions } from '../../services/graphMigration/preparation';
import { GraphMigrationTab } from './GraphMigrationTab';
import { type GraphRouterContext } from './graphRouter';

const mocks = vi.hoisted(() => ({
    context: undefined as GraphRouterContext | undefined,
    message: undefined as ((message: WorkerMessage) => void) | undefined,
    failure: undefined as
        | ((code: string, status?: 'paused' | 'cancelled', pending?: boolean, diagnostic?: SafeDiagnostic) => void)
        | undefined,
    start: vi.fn(),
    waitForExit: vi.fn(),
    events: vi.fn(),
    confirm: vi.fn(),
    showTextDocument: vi.fn(),
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
    window: { showWarningMessage: mocks.confirm, showTextDocument: mocks.showTextDocument },
    Uri: { file: (value: string) => value },
}));
vi.mock('../../services/graphMigration/WorkerBridge', () => ({
    WorkerBridge: class {
        constructor(
            _: unknown,
            _secrets: unknown,
            message: (message: WorkerMessage) => void,
            failure: (
                code: string,
                status?: 'paused' | 'cancelled',
                pending?: boolean,
                diagnostic?: SafeDiagnostic,
            ) => void,
        ) {
            mocks.message = message;
            mocks.failure = failure;
        }
        get active() {
            return mocks.active;
        }
        start = mocks.start;
        waitForExit = mocks.waitForExit;
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
    mocks.waitForExit.mockResolvedValue(undefined);
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
    it('dispatches an explicitly selected document source to its packaged SDK without a Gremlin fallback on failure', async () => {
        await context.create();
        await context.saveSetup({
            sourceMode: 'cosmos-documents',
            documentEndpoint: 'https://fixture.documents.azure.com/',
            documentProfile: 'property-bags-v1',
            documentFidelity: 'strict',
            host: '',
            database: 'db',
            container: 'graph',
            partitionKeyPath: '/pk',
            datasetUrl: 'http://localhost:3030/graph',
            consistency: 'immutable',
        });
        await context.run('preflight', '', '', '');
        expect(mocks.start).toHaveBeenCalledWith(
            expect.objectContaining({ source: expect.objectContaining({ sourceMode: 'cosmos-documents' }) }),
            expect.any(String),
            'preflight',
            expect.arrayContaining([
                path.join(
                    'test-extension',
                    'resources',
                    'graph-migration',
                    'document-source',
                    'win-x64',
                    'DocumentSource.exe',
                ),
            ]),
        );
        await complete({
            status: 'blocked',
            diagnostics: [
                {
                    status: 'blocked',
                    code: 'ENDPOINT_AUTHORIZATION',
                    stage: 'source-document-feed',
                    remediation: 'No access. Explicit mode switch is required to try Gremlin.',
                },
            ],
        });
        expect(mocks.start).toHaveBeenCalledTimes(1);
        const stopped = await service.load();
        expect(stopped.source?.sourceMode).toBe('cosmos-documents');
        expect(stopped.phases.preflight.status).toBe('blocked');
        expect(stopped.phases.preflight.blockers.join(' ')).toContain('ENDPOINT_AUTHORIZATION');
    });
    it('does not certify a terminal success if owned shutdown subsequently fails', async () => {
        await context.create();
        await context.run('mapping', '', '', '');
        let exited: () => void = () => {};
        mocks.waitForExit.mockImplementationOnce(
            () =>
                new Promise<void>((resolve) => {
                    exited = resolve;
                }),
        );
        const completion = complete({ status: 'succeeded' });
        await vi.waitFor(() => expect(mocks.waitForExit).toHaveBeenCalled());
        mocks.failure?.('WORKER_DISCONNECTED');
        exited();
        await completion;
        const phase = (await service.load()).phases.mapping;
        expect(phase.status).toBe('failed');
        expect(phase.blockers.join(' ')).toContain('WORKER_DISCONNECTED');
    });
    it('does not publish completed controls while the previous owned worker is still exiting', async () => {
        await context.create();
        await context.run('mapping', '', '', '');
        let exited: () => void = () => {};
        mocks.waitForExit.mockImplementationOnce(
            () =>
                new Promise<void>((resolve) => {
                    exited = resolve;
                }),
        );
        const before = mocks.events.mock.calls.length;
        const completion = complete({ status: 'succeeded' });
        await vi.waitFor(() => expect(mocks.waitForExit).toHaveBeenCalled());
        expect((await service.load()).phases.mapping.status).toBe('running');
        expect(mocks.events.mock.calls.slice(before).some(([event]) => event.type === 'project')).toBe(false);
        exited();
        await completion;
        expect((await service.load()).phases.mapping.status).toBe('succeeded');
    });
    it('opens preserved phase evidence from an earlier run only when referenced and checksum-valid', async () => {
        await context.create();
        const project = await service.load(),
            root = await service.root();
        const file = 'artifacts/preserved-enumeration.json';
        await mkdir(path.join(root, 'artifacts'), { recursive: true });
        await writeFile(path.join(root, file), '{"status":"blocked"}');
        project.artifacts.push({
            relativePath: file,
            ownerRunId: 'previous-run',
            sha256: await fileHash(path.join(root, file)),
            sensitive: true,
        });
        project.phases['source-enumerate'] = {
            status: 'stale',
            inputHash: '0'.repeat(64),
            evidence: [file],
            blockers: [],
        };
        await service.save(project);
        await context.openArtifact(file);
        expect(mocks.showTextDocument).toHaveBeenCalledWith(path.join(root, file), { preview: true });
        await writeFile(path.join(root, file), '{}');
        await expect(context.openArtifact(file)).rejects.toThrow('integrity');
        project.phases['source-enumerate'].evidence = [];
        await service.save(project);
        await expect(context.openArtifact(file)).rejects.toThrow('not owned');
    });
    it('persists storage action/attempts and precise worker stage without changing inputs or granting success', async () => {
        await context.create();
        const before = await service.load();
        await context.run('mapping', '', '', '');
        const diagnostic = {
            status: 'blocked',
            code: 'ARTIFACT_ATOMIC_REPLACE_ACCESS',
            stage: 'source-enumeration-checkpoint-write',
            remediation: 'Atomic replacement remained denied. Preserve the old and pending files.',
            artifactAction: 'atomic-replace',
            artifactAttempts: 5,
        };
        await complete({ status: 'blocked', diagnostics: [diagnostic] });
        const saved = await service.load();
        expect(inputHash(saved)).toBe(inputHash(before));
        expect(saved.approvals).toEqual(before.approvals);
        expect(saved.phases.mapping.status).toBe('blocked');
        expect(saved.phases.mapping.blockers[0]).toContain('source-enumeration-checkpoint-write');
        expect(saved.phases.mapping.blockers[0]).toContain('local storage attempts 5');
        const report = JSON.parse(
            await readFile(path.join(await service.root(), saved.phases.mapping.evidence[0]), 'utf8'),
        );
        expect(report.result.diagnostics[0]).toEqual(diagnostic);
    });
    it('keeps worker and host stop evidence distinct and opens each selected checksummed artifact', async () => {
        await context.create();
        await context.run('mapping', '', '', '');
        const project = await service.load(),
            root = await service.root();
        const report = {
            schemaVersion: 1,
            runId: project.runId,
            inputHash: inputHash(project),
            operation: 'mapping',
            status: 'paused',
            sensitiveArtifact: true,
            result: {
                status: 'paused',
                partialOutput: 'preserved-not-loadable',
                diagnostics: [
                    {
                        code: 'OPERATION_DEADLINE',
                        stage: 'worker-control',
                        status: 'blocked',
                        remediation: 'The configured overall operation time limit was reached.',
                    },
                ],
            },
        };
        const relativePath = 'artifacts/mapping-worker-fixture.json';
        await writeNewArtifact(root, relativePath, canonicalJson(report));
        await complete({
            status: 'paused',
            inputHash: inputHash(project),
            artifact: {
                relativePath,
                ownerRunId: project.runId,
                sensitive: true,
                sha256: createHash('sha256').update(canonicalJson(report)).digest('hex'),
            },
            artifacts: [],
            diagnostics: [{ ...report.result.diagnostics[0], operationLimitMillis: 120000, elapsedMillis: 120000 }],
        });
        const saved = await service.load(),
            evidence = saved.phases.mapping.evidence;
        expect(evidence).toHaveLength(2);
        expect(new Set(evidence).size).toBe(2);
        const worker = JSON.parse(await readFile(path.join(root, evidence[0]), 'utf8'));
        const host = JSON.parse(await readFile(path.join(root, evidence[1]), 'utf8'));
        expect(worker).toEqual(report);
        expect(host.producer).toBe('extension-host');
        expect(host.result.diagnostics[0]).toMatchObject({ operationLimitMillis: 120000, elapsedMillis: 120000 });
        expect(host).not.toEqual(worker);
        for (const file of evidence) await context.openArtifact(file);
        expect(mocks.showTextDocument.mock.calls.map(([file]) => file)).toEqual(
            evidence.map((file) => path.join(root, file)),
        );
    });
    it('forwards first/ten-second summaries and final two observations, not each request, with preserved safe stop evidence', async () => {
        await context.create();
        await context.run('mapping', '', '', '');
        const clock = vi.spyOn(Date, 'now').mockReturnValue(0);
        const progress = (sequence: number, request = false) => {
            mocks.message?.({
                protocolVersion: 1,
                type: 'progress',
                runId: 'fixture',
                operationId: 'owned',
                sequence,
                timestamp: '2026-10-31T00:00:00Z',
                phase: 'mapping',
                payload: request
                    ? { type: 'source-request-progress', stage: 'source-request-completed', waitMillis: 0, retries: 0 }
                    : {
                          type: 'source-progress',
                          stage: 'source-enumeration-checkpoint',
                          vertices: sequence,
                          edges: 0,
                          reservedRequests: sequence,
                          elapsedMillis: sequence * 100,
                      },
            });
        };
        try {
            progress(1);
            for (let i = 2; i < 100; i++) {
                clock.mockReturnValue(i * 100);
                progress(i, i % 2 === 0);
            }
            let updates = mocks.events.mock.calls.filter(
                ([event]) => event.type === 'worker' && event.message.type === 'progress',
            );
            expect(updates).toHaveLength(1);
            clock.mockReturnValue(10000);
            progress(100, true);
            expect(
                mocks.events.mock.calls.filter(
                    ([event]) => event.type === 'worker' && event.message.type === 'progress',
                ),
            ).toHaveLength(1);
            progress(101);
            clock.mockReturnValue(10001);
            progress(102);
            progress(103);
            expect(
                mocks.events.mock.calls.filter(
                    ([event]) => event.type === 'worker' && event.message.type === 'progress',
                ),
            ).toHaveLength(2);
            clock.mockRestore();
            await complete({
                status: 'paused',
                diagnostics: [
                    {
                        code: 'OPERATION_DEADLINE',
                        stage: 'worker-control',
                        status: 'blocked',
                        remediation: 'The configured overall operation time limit was reached.',
                    },
                ],
            });
            updates = mocks.events.mock.calls.filter(
                ([event]) => event.type === 'worker' && event.message.type === 'progress',
            );
            expect(updates.map(([event]) => event.message.sequence)).toEqual([1, 101]);
            const snapshot = mocks.events.mock.calls.find(([event]) => event.type === 'activity-snapshot')?.[0];
            expect(snapshot.messages.map((message: WorkerMessage) => message.sequence)).toEqual([1, 101, 102, 103]);
            const saved = await service.load();
            const evidence = JSON.parse(
                await readFile(path.join(await service.root(), saved.phases.mapping.evidence[0]), 'utf8'),
            );
            expect(evidence.lastObservedActivity).toHaveLength(4);
            expect(evidence.lastObservedActivity[0]).toContain('vertices: 1;');
            expect(evidence.lastObservedActivity.slice(-2).join(' ')).toContain('vertices: 103;');
        } finally {
            clock.mockRestore();
        }
    });
    it('adds honest recovery evidence for a legacy paused phase with no recorded stop cause, without inventing a timeout', async () => {
        await context.create();
        const original = await service.load();
        original.phases['source-enumerate'] = {
            status: 'paused',
            inputHash: inputHash(original),
            evidence: [],
            blockers: ['Review the worker diagnostics and current evidence before continuing.'],
        };
        await service.save(original);
        const value = await context.load(),
            phase = value.project!.phases['source-enumerate'];
        expect(phase.evidence).toHaveLength(1);
        expect(phase.blockers[0]).toContain('WORKER_PREVIOUS_STOP_REASON_UNKNOWN');
        expect(phase.blockers[0]).toContain('exact stop reason is unknown');
        const again = await context.load();
        expect(again.project!.phases['source-enumerate'].evidence).toEqual(phase.evidence);
        expect(inputHash(value.project!)).toBe(inputHash(original));
        expect(value.project!.approvals).toEqual(original.approvals);
    });
    it('does not finalize on pending deadline notice and persists concrete host stop evidence with safe recent activity', async () => {
        await context.create();
        await context.run('mapping', '', '', '');
        mocks.message?.({
            protocolVersion: 1,
            type: 'progress',
            runId: 'fixture',
            operationId: 'owned',
            sequence: 1,
            timestamp: new Date().toISOString(),
            phase: 'mapping',
            payload: { recordsConfirmed: 2, requestBodyBytes: 100, elapsedMillis: 1000 },
        });
        mocks.failure?.('OPERATION_DEADLINE_PARTIAL_STATE_PRESERVED', 'paused', true, {
            code: 'OPERATION_DEADLINE_PARTIAL_STATE_PRESERVED',
            stage: 'worker-control',
            status: 'blocked',
            remediation: 'The configured overall operation time limit was reached.',
            operationLimitMillis: 120000,
            elapsedMillis: 120001,
        });
        expect((await service.load()).phases.mapping.status).toBe('running');
        mocks.failure?.('WORKER_TERMINATION_REQUESTED_PARTIAL_STATE_PRESERVED', 'paused');
        await vi.waitFor(async () => expect((await service.load()).phases.mapping.status).toBe('paused'));
        const saved = await service.load(),
            phase = saved.phases.mapping;
        expect(phase.blockers.join(' ')).toContain('five-second stop grace');
        expect(phase.evidence).toHaveLength(1);
        const evidence = JSON.parse(await readFile(path.join(await service.root(), phase.evidence[0]), 'utf8'));
        expect(evidence).toMatchObject({
            operation: 'mapping',
            status: 'paused',
            producer: 'extension-host',
            inputHash: inputHash(saved),
        });
        expect(evidence.lastObservedActivity.join(' ')).toContain('Confirmed records: 2');
        expect(evidence.result.diagnostics.map((value: { code: string }) => value.code)).toEqual([
            'OPERATION_DEADLINE_PARTIAL_STATE_PRESERVED',
            'WORKER_TERMINATION_REQUESTED_PARTIAL_STATE_PRESERVED',
        ]);
        expect(evidence.result.diagnostics[0]).toMatchObject({ operationLimitMillis: 120000, elapsedMillis: 120001 });
        expect(phase.blockers.join(' ')).toContain('operation limit 120 seconds');
    });
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
            currentInputHash: inputHash(current),
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
                sourceCountsReconciled: true,
            }),
        );
        const adopted = await adoptEnumeration(prepared.project, root, prepared.output);
        expect(adopted.runId).not.toBe(prepared.project.runId);
        expect(adopted.source?.identityManifestHash).toBe(sha256);
        expect(adopted.source?.identityManifest).toBe(`${prepared.output}/identities.ndjson`);
        expect(adopted.approvals).toEqual([]);
        expect(adopted.source?.compatibilityEvidence).not.toBe(prepared.project.source?.compatibilityEvidence);
        const receipt = `artifacts/adopted-inventory-${adopted.runId}.json`;
        expect(adopted.phases['source-enumerate']).toMatchObject({
            status: 'succeeded',
            inputHash: inputHash(adopted),
            evidence: [receipt],
        });
        const savedReceipt = await readFile(path.join(root, receipt));
        const legacy = graphProjectSchema.parse({
            ...adopted,
            phases: {
                ...adopted.phases,
                'source-enumerate': {
                    status: 'stale',
                    inputHash: inputHash(prepared.project),
                    evidence: [],
                    blockers: [],
                },
            },
        });
        await service.save(legacy);
        const recovered = await context.load();
        expect(recovered.project?.phases['source-enumerate']).toEqual(adopted.phases['source-enumerate']);
        expect(recovered.project?.runId).toBe(adopted.runId);
        expect(await readFile(path.join(root, receipt))).toEqual(savedReceipt);
        expect(mocks.start).not.toHaveBeenCalled();
        expect((await service.load()).phases['source-enumerate'].status).toBe('succeeded');
        const changed = graphProjectSchema.parse({ ...legacy, limits: { ...legacy.limits, batchRecords: 99 } });
        await service.save(changed);
        expect((await context.load()).project?.phases['source-enumerate'].status).toBe('stale');
        await service.save(legacy);
        await writeFile(path.join(root, prepared.output, 'identities.ndjson'), 'tampered');
        const rejected = await context.load();
        expect(rejected.project?.phases['source-enumerate'].status).toBe('blocked');
        expect(rejected.project?.phases['source-enumerate'].blockers[0]).toContain('INVENTORY_ADOPTION_UNVERIFIED');
        expect(mocks.start).not.toHaveBeenCalled();
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
