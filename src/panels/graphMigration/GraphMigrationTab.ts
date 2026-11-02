/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { TypedEventSink } from '@microsoft/vscode-ext-webview';
import { attachTrpc } from '@microsoft/vscode-ext-webview/host';
import * as l10n from '@vscode/l10n';
import { createHash, createHmac, randomBytes } from 'node:crypto';
import { realpath, lstat } from 'node:fs/promises';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { z } from 'zod';
import { ext } from '../../extensionVariables';
import { ActivitySampler, activityText } from '../../services/graphMigration/activity';
import {
    createOwnedDirectory,
    fileHash,
    ownedPath,
    readBounded,
    writeNewArtifact,
} from '../../services/graphMigration/artifacts';
import {
    artifactEnvelopeSchema,
    canonicalJson,
    fixtureCatalogSchema,
    graphMigrationFolder,
    graphProjectSchema,
    inputHash,
    javaRewritePlanSchema,
    populationPlanSchema,
    relativePathSchema,
    type GraphProject,
    type WorkerMessage,
} from '../../services/graphMigration/contracts';
import {
    formatDiagnostic,
    GraphDiagnosticError,
    hasCurrentSourceCompatibility,
    hostDiagnostic,
    safeDiagnostics,
} from '../../services/graphMigration/diagnostics';
import { GraphProjectService } from '../../services/graphMigration/GraphProjectService';
import {
    adoptEnumeration,
    discoverPreparation,
    freshPreparation,
    type PrepareSource,
} from '../../services/graphMigration/preparation';
import { applySetup, discoverSetup, type SetupSuggestions } from '../../services/graphMigration/setup';
import { WorkerBridge } from '../../services/graphMigration/WorkerBridge';
import { BaseTab } from '../BaseTab';
import {
    graphAppRouter,
    graphCallerFactory,
    type GraphApprovalOperation,
    type GraphEvidencePhase,
    type GraphEvent,
    type GraphOperation,
    type GraphRouterContext,
} from './graphRouter';

export class GraphMigrationTab extends BaseTab {
    private readonly service: GraphProjectService;
    private readonly events = new TypedEventSink<GraphEvent>();
    private readonly bridge: WorkerBridge;
    private activeOperation: GraphOperation | undefined;
    private activeInputHash: string | undefined;
    private finishing: Promise<void> | undefined;
    private preparationStop: 'paused' | 'cancelled' | undefined;
    private setup: SetupSuggestions | undefined;
    private guidedEnumerationOutput: string | undefined;
    private reviewingPreparation = false;
    private recentActivity: string[] = [];
    private activitySampler = new ActivitySampler<WorkerMessage>();
    private lastHeartbeatAt = Number.NEGATIVE_INFINITY;
    private pendingStopDiagnostic: ReturnType<typeof hostDiagnostic> | undefined;
    constructor(
        panel: vscode.WebviewPanel,
        private readonly workspacePath: string,
    ) {
        super(panel, 'cosmosDbGraphMigration');
        this.service = new GraphProjectService(workspacePath);
        this.bridge = new WorkerBridge(
            path.join(ext.context.extensionPath, 'resources', 'graph-migration'),
            ext.context.secrets,
            (message) => {
                if (message.type === 'progress') {
                    const text = activityText(message.payload);
                    if (text) {
                        const payload = message.payload;
                        const eligible = !(
                            payload &&
                            typeof payload === 'object' &&
                            !Array.isArray(payload) &&
                            payload.type === 'source-request-progress' &&
                            ['source-request-awaiting-response', 'source-request-completed'].includes(
                                String(payload.stage),
                            )
                        );
                        const show = this.activitySampler.offer(message, Date.now(), eligible);
                        this.recentActivity = this.activitySampler.snapshot().flatMap((value) => {
                            const line = activityText(value.payload);
                            return line ? [line] : [];
                        });
                        if (!show) return;
                    } else {
                        if (Date.now() - this.lastHeartbeatAt < 10000) return;
                        this.lastHeartbeatAt = Date.now();
                    }
                }
                if (message.type === 'result' || message.type === 'error') this.flushActivity();
                this.emitWorker(message);
                if (message.type === 'result' || message.type === 'error')
                    void this.finish(message.payload).catch(() =>
                        this.events.emit({ type: 'diagnostic', code: 'STATE_SAVE_FAILED' }),
                    );
            },
            (code, status, pending, observedDiagnostic) => {
                if (!pending) this.flushActivity();
                this.events.emit({ type: 'diagnostic', code, pending });
                const diagnostic =
                    observedDiagnostic ?? hostDiagnostic(new GraphDiagnosticError(code, 'worker-control'));
                if (pending) {
                    this.pendingStopDiagnostic = diagnostic;
                    return;
                }
                void this.finish({
                    status: status ?? 'failed',
                    diagnostics: [...(this.pendingStopDiagnostic ? [this.pendingStopDiagnostic] : []), diagnostic],
                }).catch(() => this.events.emit({ type: 'diagnostic', code: 'STATE_SAVE_FAILED' }));
            },
        );
        const context: GraphRouterContext = {
            eventSink: this.events,
            load: () => this.load(),
            create: () => this.create(),
            save: async (json: string) => {
                if (!vscode.workspace.isTrusted) throw new Error(l10n.t('Workspace trust is required.'));
                if (this.activeOperation || this.bridge.active || this.reviewingPreparation)
                    throw new Error(l10n.t('Pause or cancel the worker before changing inputs.'));
                await this.service.save(graphProjectSchema.parse(JSON.parse(json)));
            },
            saveSetup: async (value) => {
                if (!vscode.workspace.isTrusted) throw new Error(l10n.t('Workspace trust is required.'));
                if (this.activeOperation || this.bridge.active || this.reviewingPreparation)
                    throw new Error(l10n.t('Pause or cancel the worker before changing inputs.'));
                const project = await applySetup(await this.service.load(), value, await this.service.root());
                await this.service.save(project);
            },
            run: (operation: GraphOperation, query: string, sourcePath: string, outputPath: string) =>
                this.run(operation, query, sourcePath, outputPath),
            approve: (operation: GraphApprovalOperation, outputPath: string) => this.approve(operation, outputPath),
            openArtifact: (relativePath: string) => this.openArtifact(relativePath),
            importEvidence: (relativePath: string, phase: GraphEvidencePhase) =>
                this.importEvidence(relativePath, phase),
            credentials: () => this.credentials(),
            cancel: () => this.stop('cancelled'),
            pause: () => this.stop('paused'),
            preparation: async () => {
                if (!vscode.workspace.isTrusted) throw new Error(l10n.t('Workspace trust is required.'));
                return discoverPreparation(this.workspacePath, await this.service.load());
            },
            prepareSource: (value) => this.prepareSource(value),
            resumePreparation: () => this.resumePreparation(),
        };
        const attached = attachTrpc(panel, context, graphAppRouter, graphCallerFactory);
        this.disposables.push(attached.disposable, this.bridge);
    }
    private emitWorker(message: WorkerMessage): void {
        this.events.emit({
            type: 'worker',
            message:
                this.activeOperation && message.type !== 'hello'
                    ? { ...message, phase: this.activeOperation }
                    : message,
        });
    }
    private flushActivity(): void {
        const messages = this.activitySampler.snapshot();
        if (this.activeOperation && messages.length)
            this.events.emit({ type: 'activity-snapshot', phase: this.activeOperation, messages });
    }
    private stop(status: 'paused' | 'cancelled'): void {
        if (!this.activeOperation && !this.bridge.active) return;
        this.preparationStop = status;
        if (status === 'paused') this.bridge.pause();
        else this.bridge.cancel();
    }
    private async prepareSource(value: PrepareSource): Promise<void> {
        if (!vscode.workspace.isTrusted || this.activeOperation || this.bridge.active || this.reviewingPreparation)
            throw new Error(l10n.t('Trust the workspace and pause any active operation before preparing a new run.'));
        this.reviewingPreparation = true;
        try {
            const project = await this.service.load(),
                root = await this.service.root(),
                hash = inputHash(project);
            const confirm = await vscode.window.showWarningMessage(
                l10n.t(
                    'Enumerate up to {partitions} candidate partitions using read-only requests, at most {requests} reserved attempts, {seconds} seconds and a total-run allowance of {ru} RU (not RU/s)? Coverage is unverified until live counts reconcile. This preserves the old run and creates fresh preparation/extraction runs; old approvals and evidence will not authorize new writes.',
                    {
                        partitions: value.partitions.length,
                        requests: value.maximumRequests,
                        seconds: value.maximumDurationSeconds,
                        ru: value.ruBudget,
                    },
                ) +
                    ' ' +
                    l10n.t(
                        'Migration pacing: {rate}. Charges pace subsequent requests; a single request can exceed one second of this rate.',
                        {
                            rate:
                                (value.ruPerSecond ?? project.source?.ruPerSecond)
                                    ? `${value.ruPerSecond ?? project.source?.ruPerSecond} RU/s`
                                    : l10n.t('not configured (legacy)'),
                        },
                    ),
                { modal: true },
                l10n.t('Start bounded read-only preparation'),
            );
            if (!confirm) return;
            if (
                !vscode.workspace.isTrusted ||
                inputHash(await this.service.load()) !== hash ||
                this.activeOperation ||
                this.bridge.active
            )
                throw new Error(l10n.t('Project inputs changed during preparation review.'));
            const prepared = await freshPreparation(project, root, value);
            if (!vscode.workspace.isTrusted || inputHash(await this.service.load()) !== hash)
                throw new Error(l10n.t('Project inputs changed while creating the local preparation plan.'));
            await this.service.save(prepared.project);
            this.emitProject(prepared.project);
            this.guidedEnumerationOutput = prepared.output;
            await this.run('source-enumerate', '', prepared.plan, prepared.output, true);
        } finally {
            this.reviewingPreparation = false;
        }
    }
    private async resumePreparation(): Promise<void> {
        if (!vscode.workspace.isTrusted || this.activeOperation || this.bridge.active || this.reviewingPreparation)
            throw new Error(l10n.t('Trust the workspace and pause owned work before resuming preparation.'));
        this.reviewingPreparation = true;
        try {
            const project = await this.service.load(),
                hash = inputHash(project);
            const resume = (await discoverPreparation(this.workspacePath, project)).resume;
            if (!resume)
                throw new Error(
                    l10n.t('No same-input preparation checkpoint with remaining reviewed capacity is available.'),
                );
            const confirmed = await vscode.window.showWarningMessage(
                l10n.t(
                    'Resume preserved enumeration within its original request, duration, total-run RU allowance and pacing settings? Waiting does not replenish total spend. This grants no additional budget and establishes no coverage.',
                ),
                { modal: true },
                l10n.t('Resume bounded preparation'),
            );
            if (!confirmed) return;
            if (!vscode.workspace.isTrusted || inputHash(await this.service.load()) !== hash)
                throw new Error(l10n.t('Project inputs changed during resume review.'));
            this.guidedEnumerationOutput = resume.output;
            await this.run('source-enumerate', '', resume.plan, resume.output, true);
        } finally {
            this.reviewingPreparation = false;
        }
    }
    static render(workspacePath: string): GraphMigrationTab {
        return new GraphMigrationTab(
            vscode.window.createWebviewPanel(
                'cosmosDbGraphMigration',
                l10n.t('Gremlin to RDF migration (Experimental)'),
                vscode.ViewColumn.Active,
                { enableScripts: true, retainContextWhenHidden: true },
            ),
            workspacePath,
        );
    }
    private emitProject(project: GraphProject): void {
        this.events.emit({ type: 'project', project, currentInputHash: inputHash(project) });
    }
    private async load(): Promise<{
        project: GraphProject | null;
        trusted: boolean;
        setup?: SetupSuggestions;
        currentInputHash?: string;
    }> {
        if (vscode.workspace.isTrusted && !this.setup)
            this.setup = await discoverSetup(this.workspacePath, process.env);
        try {
            const project = await this.service.load();
            if (!this.activeOperation && Object.values(project.phases).some((phase) => phase.status === 'running')) {
                for (const phase of Object.values(project.phases))
                    if (phase.status === 'running') {
                        phase.status = 'paused';
                        phase.blockers = [
                            l10n.t(
                                'No active worker owns this phase. Resume only after verifying its persisted checkpoint and request budget.',
                            ),
                        ];
                    }
                await this.service.save(project);
            }
            if (!this.activeOperation && !this.bridge.active) {
                let changed = false;
                for (const [operation, phase] of Object.entries(project.phases)) {
                    if (phase.status !== 'paused' || phase.evidence.length) continue;
                    const diagnostic = hostDiagnostic(
                        new GraphDiagnosticError('WORKER_PREVIOUS_STOP_REASON_UNKNOWN', 'worker-control'),
                    );
                    const relativePath = `artifacts/host-stop-${globalThis.crypto.randomUUID()}.json`;
                    const selected = await writeNewArtifact(
                        await this.service.root(),
                        relativePath,
                        canonicalJson({
                            schemaVersion: 1,
                            runId: project.runId,
                            inputHash: phase.inputHash,
                            operation,
                            status: 'paused',
                            producer: 'extension-host-recovery',
                            sensitiveArtifact: true,
                            diagnostics: [diagnostic],
                            previousBlockers: phase.blockers,
                        }),
                    );
                    project.artifacts.push({
                        relativePath,
                        ownerRunId: project.runId,
                        sensitive: true,
                        sha256: await fileHash(selected),
                    });
                    phase.evidence.push(relativePath);
                    phase.blockers = [formatDiagnostic(diagnostic), ...phase.blockers];
                    changed = true;
                }
                if (changed) await this.service.save(project);
            }
            return {
                project,
                trusted: vscode.workspace.isTrusted,
                setup: this.setup,
                currentInputHash: inputHash(project),
            };
        } catch (error) {
            if (error instanceof Error && 'code' in error && error.code === 'ENOENT')
                return { project: null, trusted: vscode.workspace.isTrusted, setup: this.setup };
            throw new Error(l10n.t('The graph project could not be read. Check its schema and artifact paths.'));
        }
    }
    private async create(): Promise<GraphProject> {
        if (!vscode.workspace.isTrusted) throw new Error(l10n.t('Workspace trust is required.'));
        if ((await this.load()).project) throw new Error(l10n.t('A graph project already exists.'));
        const git = vscode.extensions.getExtension<{
            getAPI(version: 1): { repositories: { rootUri: vscode.Uri; state: { HEAD?: { commit?: string } } }[] };
        }>('vscode.git');
        const baseline = git?.isActive
            ? git.exports.getAPI(1).repositories.find((repository) => {
                  const relative = path.relative(repository.rootUri.fsPath, this.workspacePath);
                  return !relative.startsWith('..') && !path.isAbsolute(relative);
              })?.state.HEAD?.commit
            : undefined;
        const project = graphProjectSchema.parse({
            schemaVersion: 1,
            migrationKind: 'cosmos-gremlin-to-jena',
            baselineRepositorySha: baseline ?? '22e3f2cccbfcc6622cacbf1873b9c620acba085e',
            runId: globalThis.crypto.randomUUID(),
            protocolVersion: 1,
            workerVersion: '0.1.0',
            source: null,
            target: null,
            mapping: {
                schemaVersion: 1,
                baseIri: 'urn:gremlin2rdf:entity:',
                vocabulary: 'urn:gremlin2rdf:domain:',
                labels: {},
                properties: {},
                edges: {},
                exclusions: [],
                derivedRelationships: true,
                provenance: true,
                fidelity: 'strict',
            },
            limits: {
                batchRecords: 100,
                batchBytes: 1048576,
                maxRecordBytes: 262144,
                requestTimeoutMillis: 30000,
                maxRetries: 3,
                queueCapacity: 100,
                writerConcurrency: 1,
                minimumFreeDiskBytes: 104857600,
            },
            consistency: 'immutable',
            phases: {},
            approvals: [],
            applicationHashes: {},
            artifacts: [],
        });
        await this.service.save(project);
        return project;
    }
    private graphs(project: GraphProject): string[] {
        if (!project.target) throw new Error(l10n.t('Configure explicit Fuseki endpoints first.'));
        return ['canonical', 'derived', 'metadata'].map(
            (role) => `${project.target?.graphNamespace}${project.runId}:${role}`,
        );
    }
    private async currentArtifact(project: GraphProject, phaseName: string, suffix: string) {
        const phase = project.phases[phaseName];
        if (!phase || phase.status !== 'succeeded' || phase.inputHash !== inputHash(project))
            throw new Error(l10n.t('Current passing phase evidence is required.'));
        const artifact = project.artifacts.findLast(
            (entry) =>
                entry.ownerRunId === project.runId &&
                phase.evidence.includes(entry.relativePath) &&
                entry.relativePath.endsWith(suffix),
        );
        if (!artifact) throw new Error(l10n.t('Required owned artifact is missing.'));
        const selected = await ownedPath(await this.service.root(), artifact.relativePath);
        if ((await fileHash(selected)) !== artifact.sha256)
            throw new Error(l10n.t('Artifact integrity verification failed.'));
        return { artifact, selected };
    }
    private async fixtureCatalog(project: GraphProject) {
        const { artifact, selected } = await this.currentArtifact(project, 'fixture-generate', '/catalog.json');
        const catalog = fixtureCatalogSchema.parse(JSON.parse((await readBounded(selected, 1048576)).toString('utf8')));
        if (
            catalog.runId !== project.runId ||
            catalog.inputHash !== inputHash(project) ||
            catalog.graph !== this.graphs(project)[0] ||
            (await fileHash(await ownedPath(await this.service.root(), catalog.sourcePath))) !== catalog.sourceHash
        )
            throw new Error(l10n.t('Fixture catalog inputs no longer match this project.'));
        return { artifact, selected, catalog };
    }
    private async rewritePlan(project: GraphProject) {
        const { selected } = await this.currentArtifact(project, 'rewrite-plan', '.json');
        const envelope = artifactEnvelopeSchema.parse(
            JSON.parse((await readBounded(selected, 16777216)).toString('utf8')),
        );
        const plan = javaRewritePlanSchema.parse(envelope.result);
        if (
            envelope.runId !== project.runId ||
            envelope.inputHash !== inputHash(project) ||
            envelope.operation !== 'rewrite' ||
            plan.blockers.length ||
            !plan.findings.length ||
            plan.graph !== this.graphs(project)[0] ||
            (await fileHash(await ownedPath(this.workspacePath, plan.sourcePath))) !== plan.sourceHash
        )
            throw new Error(l10n.t('Reviewed Java plan is blocked, stale or has changed source.'));
        return { plan };
    }
    private async rewriteOutput(outputPath: string): Promise<string> {
        const relative = relativePathSchema.parse(outputPath);
        if (!relative.startsWith('artifacts/rewritten/') || !relative.endsWith('.java'))
            throw new Error(l10n.t('Choose a new Java output under artifacts/rewritten/ in this run.'));
        const root = await this.service.root();
        let selected = root;
        for (const part of relative.split('/')) {
            selected = path.join(selected, part);
            try {
                const stat = await lstat(selected);
                if (stat.isSymbolicLink()) throw new Error(l10n.t('Rewrite output symlinks are prohibited.'));
                if (selected === path.join(root, relative))
                    throw new Error(l10n.t('Rewrite output must not already exist.'));
            } catch (error) {
                if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error;
            }
        }
        return `${graphMigrationFolder}/${relative}`;
    }
    private async approve(operation: GraphApprovalOperation, outputPath = ''): Promise<void> {
        if (!vscode.workspace.isTrusted) throw new Error(l10n.t('Workspace trust is required.'));
        const project = await this.service.load();
        const scope =
            operation === 'load' || operation === 'resume'
                ? 'target-write'
                : operation === 'cleanup'
                  ? 'cleanup'
                  : operation === 'publish'
                    ? 'publish'
                    : operation === 'reduced-fidelity' ||
                        operation === 'best-effort-source' ||
                        operation === 'source-populate' ||
                        operation === 'source-cleanup' ||
                        operation === 'target-probe' ||
                        operation === 'fixture-test'
                      ? operation
                      : operation === 'rewrite-apply'
                        ? 'rewrite'
                        : undefined;
        if (!scope) throw new Error(l10n.t('This operation does not have a graph-write approval scope.'));
        let resources: string[];
        if (scope === 'target-probe') {
            if (!project.target || !outputPath.startsWith(`${project.target.graphNamespace}${project.runId}:probe:`))
                throw new Error(l10n.t('Select a dedicated run-owned probe graph.'));
            resources = [outputPath];
        } else if (scope === 'rewrite') {
            const { plan } = await this.rewritePlan(project);
            const output = await this.rewriteOutput(outputPath);
            resources = [
                plan.sourcePath,
                plan.sourceHash,
                createHash('sha256').update(canonicalJson(plan)).digest('hex'),
                output,
            ];
        } else if (scope === 'fixture-test') {
            const { artifact } = await this.fixtureCatalog(project);
            resources = [...this.graphs(project).slice(0, 2), artifact.sha256];
        } else if (scope === 'reduced-fidelity') {
            if (project.mapping.fidelity !== 'reduced')
                throw new Error(l10n.t('Select reduced mapping fidelity before authorizing its losses.'));
            resources = [
                createHash('sha256')
                    .update(canonicalJson({ ...project.mapping, exclusions: [...project.mapping.exclusions].sort() }))
                    .digest('hex'),
            ];
        } else if (scope === 'best-effort-source') {
            if (!project.source || project.consistency !== 'best-effort')
                throw new Error(l10n.t('Configure an explicit best-effort source before authorizing it.'));
            resources = [project.source.scope, project.source.identityManifestHash];
        } else if (scope === 'source-populate' || scope === 'source-cleanup') {
            if (
                !project.source?.syntheticTestContainer ||
                project.consistency !== 'quiesced' ||
                !project.source.ruBudget
            )
                throw new Error(
                    l10n.t(
                        'Source writes require a dedicated, pre-existing quiesced test container and explicit RU budget.',
                    ),
                );
            const root = await this.service.root();
            const artifact = project.artifacts.findLast(
                (entry) =>
                    entry.ownerRunId === project.runId &&
                    project.phases['source-plan']?.evidence.includes(entry.relativePath) &&
                    entry.relativePath.startsWith('artifacts/synthetic-'),
            );
            if (
                !artifact ||
                project.phases['source-plan']?.status !== 'succeeded' ||
                project.phases['source-plan'].inputHash !== inputHash(project)
            )
                throw new Error(l10n.t('A current passing source population plan is required.'));
            const evidence = await ownedPath(root, artifact.relativePath);
            if ((await fileHash(evidence)) !== artifact.sha256)
                throw new Error(l10n.t('Source plan evidence integrity verification failed.'));
            const selected = await ownedPath(root, 'population-plan.json');
            const plan = populationPlanSchema.parse(JSON.parse((await readBounded(selected, 65536)).toString('utf8')));
            const planHash = createHash('sha256').update(canonicalJson(plan)).digest('hex');
            const envelope: unknown = JSON.parse((await readBounded(evidence, 1048576)).toString('utf8'));
            if (
                !envelope ||
                typeof envelope !== 'object' ||
                !('result' in envelope) ||
                !envelope.result ||
                typeof envelope.result !== 'object' ||
                !('planHash' in envelope.result) ||
                envelope.result.planHash !== planHash ||
                plan.runId !== project.runId ||
                plan.inputHash !== inputHash(project)
            )
                throw new Error(l10n.t('Source population plan no longer matches its current evidence.'));
            resources = [
                plan.containerIdentity,
                plan.runPrefix,
                plan.recordsSha256,
                planHash,
                'exclusive-pre-existing-test-container',
            ];
        } else resources = this.graphs(project);
        if (scope === 'cleanup') resources.push(`${project.target?.graphNamespace}${project.runId}:validation`);
        if (scope === 'publish') {
            const phase = project.phases.report;
            if (
                !phase ||
                phase.status !== 'succeeded' ||
                phase.inputHash !== inputHash(project) ||
                phase.evidence.length !== 1
            )
                throw new Error(l10n.t('A current passing readiness report is required before publication approval.'));
            const artifact = project.artifacts.find(
                (a) => a.relativePath === phase.evidence[0] && a.ownerRunId === project.runId,
            );
            if (!artifact) throw new Error(l10n.t('Readiness artifact ownership is missing.'));
            const root = await this.service.root(),
                selected = await ownedPath(root, artifact.relativePath);
            if ((await fileHash(selected)) !== artifact.sha256)
                throw new Error(l10n.t('Readiness artifact integrity verification failed.'));
            resources.push(artifact.sha256);
        }
        const confirmed = await vscode.window.showWarningMessage(
            l10n.t('Authorize {operation} only for these exact run-scoped resources?\n{resources}', {
                operation:
                    scope === 'fixture-test'
                        ? 'fixture-test, application-write, application-delete'
                        : scope === 'cleanup'
                          ? 'cleanup, target-write (ownership lock)'
                          : scope,
                resources: resources.join('\n'),
            }),
            { modal: true },
            l10n.t('Authorize'),
        );
        if (!confirmed) return;
        const keyRef = 'env:GRAPH_APPROVAL_KEY';
        let key = await ext.context.secrets.get(`graph-migration:${keyRef}`);
        if (!key) {
            key = randomBytes(32).toString('hex');
            await ext.context.secrets.store(`graph-migration:${keyRef}`, key);
        }
        const expiresAt = new Date(Date.now() + 3600000).toISOString();
        const grants =
            scope === 'fixture-test'
                ? ['fixture-test', 'application-write', 'application-delete']
                : scope === 'cleanup'
                  ? ['cleanup', 'target-write']
                  : [scope];
        for (const grant of grants) {
            const sorted = [
                ...(grant === 'application-write' || grant === 'application-delete'
                    ? resources.slice(0, 2)
                    : grant === 'target-write'
                      ? resources.slice(0, 3)
                      : resources),
            ].sort();
            const approval = {
                schemaVersion: 1 as const,
                runId: project.runId,
                operation: grant,
                resources: sorted,
                inputHash: inputHash(project),
                expiresAt,
                keyRef,
                signature: createHmac('sha256', key)
                    .update(canonicalJson([1, project.runId, grant, sorted, inputHash(project), expiresAt, keyRef]))
                    .digest('hex'),
            };
            project.approvals = [...project.approvals.filter((a) => a.operation !== grant), approval];
        }
        await this.service.save(project);
        this.emitProject(project);
    }
    private async credentials(): Promise<void> {
        if (!vscode.workspace.isTrusted) throw new Error(l10n.t('Workspace trust is required.'));
        const project = await this.service.load();
        for (const reference of [project.source?.authenticationRef, project.target?.authenticationRef]) {
            if (!reference) continue;
            const value = await vscode.window.showInputBox({
                password: true,
                ignoreFocusOut: true,
                prompt: l10n.t('Enter the credential for {reference}. It is stored only in VS Code SecretStorage.', {
                    reference,
                }),
            });
            if (value) await ext.context.secrets.store(`graph-migration:${reference}`, value);
        }
        for (const reference of [project.source?.customCaRef, project.target?.customCaRef]) {
            if (!reference) continue;
            const selected = await vscode.window.showOpenDialog({
                canSelectMany: false,
                openLabel: l10n.t('Select trusted CA certificate'),
                filters: { [l10n.t('Certificates')]: ['pem', 'crt', 'cer'] },
            });
            if (selected?.[0]) await ext.context.secrets.store(`graph-migration:${reference}`, selected[0].fsPath);
        }
    }
    private async run(
        operation: GraphOperation,
        query: string,
        sourcePath: string,
        outputPath = '',
        reviewedPreparation = false,
    ): Promise<void> {
        if (this.activeOperation || this.bridge.active || (this.reviewingPreparation && !reviewedPreparation))
            throw new Error(l10n.t('A graph migration operation is already active.'));
        if (!vscode.workspace.isTrusted) throw new Error(l10n.t('Workspace trust is required.'));
        this.activeOperation = operation;
        this.recentActivity = [];
        this.activitySampler = new ActivitySampler<WorkerMessage>();
        this.lastHeartbeatAt = Number.NEGATIVE_INFINITY;
        this.pendingStopDiagnostic = undefined;
        this.preparationStop = undefined;
        try {
            const project = await this.service.load(),
                root = await this.service.root();
            const projectFile = path.join(root, 'project.json'),
                source = path.join(
                    root,
                    project.phases.extract?.status === 'succeeded'
                        ? (project.artifacts
                              .slice()
                              .reverse()
                              .find(
                                  (a) =>
                                      a.ownerRunId === project.runId &&
                                      project.phases.extract.evidence.includes(a.relativePath) &&
                                      /(?:^|\/)records(?:-[a-f0-9-]+)?\.ndjson$/u.test(a.relativePath),
                              )?.relativePath ?? 'records.ndjson')
                        : 'records.ndjson',
                );
            await createOwnedDirectory(root, 'artifacts');
            let command: string = operation,
                args: string[] = [];
            switch (operation) {
                case 'benchmark-load':
                case 'benchmark-soak': {
                    const selected = await vscode.window.showWarningMessage(
                        l10n.t(
                            'Run the private {mode} benchmark with a 1 GiB worker and separate 2 GiB loopback Fuseki? Load needs 50 GiB free disk; soak runs for at least two hours. No live endpoints or credentials are used.',
                            {
                                mode: operation === 'benchmark-load' ? l10n.t('load') : l10n.t('soak'),
                            },
                        ),
                        { modal: true },
                        l10n.t('Run private benchmark'),
                    );
                    if (!selected) {
                        this.preparationStop = 'cancelled';
                        break;
                    }
                    const mode = operation === 'benchmark-load' ? 'load' : 'soak';
                    const config = await readBounded(
                        path.join(ext.context.extensionPath, 'resources', 'graph-migration', `reference-${mode}.json`),
                        65536,
                    );
                    const configPath = `artifacts/benchmark-config-${globalThis.crypto.randomUUID()}.json`;
                    await writeNewArtifact(root, configPath, config.toString('utf8'));
                    command = 'test';
                    args = [
                        'benchmark',
                        projectFile,
                        path.join(ext.context.extensionPath, 'resources', 'graph-migration', 'graph-testkit-0.1.0.jar'),
                        configPath,
                        `artifacts/benchmark-${mode}-${globalThis.crypto.randomUUID()}`,
                        '--private-fixture-confirmed',
                    ];
                    break;
                }
                case 'preflight':
                    args = [
                        projectFile,
                        path.join(
                            ext.context.extensionPath,
                            'resources',
                            'graph-migration',
                            'cosmos-gremlin-source-0.1.0.jar',
                        ),
                    ];
                    break;
                case 'target-probe':
                    command = 'preflight';
                    args = ['probe', projectFile, outputPath];
                    break;
                case 'mapping': {
                    const file = await writeNewArtifact(
                        root,
                        `artifacts/mapping-input-${globalThis.crypto.randomUUID()}.json`,
                        JSON.stringify(project.mapping),
                    );
                    args = ['validate', file];
                    break;
                }
                case 'synthetic':
                    args = ['generate', 'semantic', '42', source];
                    break;
                case 'fixture-generate':
                    command = 'synthetic';
                    args = [
                        'derive',
                        projectFile,
                        path.relative(root, source).replaceAll('\\', '/'),
                        `artifacts/fixtures-${globalThis.crypto.randomUUID()}`,
                        this.graphs(project)[0],
                    ];
                    break;
                case 'fixture-test': {
                    const { artifact } = await this.fixtureCatalog(project);
                    command = 'test';
                    args = ['fixtures', projectFile, path.posix.dirname(artifact.relativePath)];
                    break;
                }
                case 'source-generate':
                    command = 'synthetic';
                    args = ['source-generate', 'semantic', '42', projectFile, path.join(root, 'source-fixture.ndjson')];
                    break;
                case 'source-enumerate':
                    command = 'source-enumerate';
                    args = [
                        projectFile,
                        path.join(
                            ext.context.extensionPath,
                            'resources',
                            'graph-migration',
                            'cosmos-gremlin-source-0.1.0.jar',
                        ),
                        sourcePath.replaceAll('\\', '/'),
                        outputPath.replaceAll('\\', '/'),
                    ];
                    break;
                case 'source-plan':
                    command = 'synthetic';
                    args = ['plan', projectFile, 'source-fixture.ndjson', 'population-plan.json', '100000'];
                    break;
                case 'source-populate':
                case 'source-cleanup':
                case 'source-recipes':
                    args = [
                        projectFile,
                        path.join(
                            ext.context.extensionPath,
                            'resources',
                            'graph-migration',
                            'cosmos-gremlin-source-0.1.0.jar',
                        ),
                        'population-plan.json',
                        operation === 'source-recipes' ? 'source-recipes.ndjson' : 'source-ownership.json',
                    ];
                    break;
                case 'source-rdf':
                case 'mapping-export':
                    command = 'mapping';
                    args = [
                        'export',
                        projectFile,
                        operation === 'source-rdf' ? path.join(root, 'source-fixture.ndjson') : source,
                        path.join(root, 'artifacts', operation === 'source-rdf' ? 'source-rdf' : 'canonical-rdf'),
                    ];
                    break;
                case 'load':
                case 'resume':
                    {
                        let state = path.join(root, 'artifacts', `state-${project.runId}`);
                        try {
                            const legacy = z
                                .object({ runId: z.string() })
                                .passthrough()
                                .parse(
                                    JSON.parse(
                                        (
                                            await readBounded(
                                                await ownedPath(root, 'artifacts/checkpoint.json'),
                                                1048576,
                                            )
                                        ).toString('utf8'),
                                    ),
                                );
                            if (legacy.runId === project.runId) state = path.join(root, 'artifacts');
                        } catch (error) {
                            if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error;
                        }
                        args = [projectFile, source, state];
                    }
                    break;
                case 'validate':
                    args = [projectFile, source];
                    break;
                case 'translate': {
                    const graph = this.graphs(project)[0];
                    args = [graph, query];
                    break;
                }
                case 'report':
                    args = [projectFile, path.join(root, 'artifacts')];
                    break;
                case 'cleanup':
                case 'publish':
                    args = [projectFile];
                    break;
                case 'functional':
                    command = 'test';
                    args = ['functional', projectFile, source];
                    break;
                case 'assess':
                    command = 'assess';
                    args = [this.workspacePath];
                    break;
                case 'rewrite-plan': {
                    if (!sourcePath || path.isAbsolute(sourcePath) || sourcePath.split(/[\\/]/u).includes('..'))
                        throw new Error(l10n.t('Select a Java file within this workspace.'));
                    const selected = await realpath(path.join(this.workspacePath, sourcePath));
                    if (!selected.startsWith((await realpath(this.workspacePath)) + path.sep))
                        throw new Error(l10n.t('Source path is outside the workspace.'));
                    command = 'rewrite';
                    args = ['plan', this.workspacePath, sourcePath.replaceAll('\\', '/'), this.graphs(project)[0]];
                    break;
                }
                case 'rewrite-apply': {
                    const { plan } = await this.rewritePlan(project);
                    const output = await this.rewriteOutput(outputPath);
                    const file = await writeNewArtifact(
                        root,
                        `artifacts/reviewed-plan-${globalThis.crypto.randomUUID()}.json`,
                        JSON.stringify(plan),
                    );
                    command = 'rewrite';
                    args = ['apply', projectFile, this.workspacePath, file, output];
                    break;
                }
                case 'extract':
                case 'source-compatibility': {
                    if (operation === 'extract' && !hasCurrentSourceCompatibility(project, inputHash(project)))
                        throw new GraphDiagnosticError(
                            'SOURCE_COMPATIBILITY_NOT_CURRENT',
                            'source-compatibility-prerequisite',
                        );
                    if (!project.source)
                        throw new Error(l10n.t('Configure the source and verified identity manifest first.'));
                    const output = operation === 'source-compatibility' ? project.source.compatibilityEvidence : null;
                    if (operation === 'source-compatibility' && !output)
                        throw new Error(l10n.t('Configure a new relative compatibility evidence path first.'));
                    if (
                        operation === 'source-compatibility' &&
                        (await lstat(await ownedPath(root, project.source.identityManifest))).size === 0 &&
                        !project.artifacts.some(
                            (a) =>
                                a.ownerRunId === project.runId &&
                                a.relativePath.startsWith('artifacts/adopted-inventory-'),
                        )
                    )
                        throw new GraphDiagnosticError('SOURCE_INVENTORY_REQUIRED', 'source-fidelity-inventory');
                    command = operation;
                    args = [
                        projectFile,
                        path.join(
                            ext.context.extensionPath,
                            'resources',
                            'graph-migration',
                            'cosmos-gremlin-source-0.1.0.jar',
                        ),
                        path.join(root, project.source.identityManifest),
                        output ? path.join(root, output) : path.join(root, `records-${project.runId}.ndjson`),
                    ];
                    break;
                }
                default:
                    throw new Error(
                        l10n.t('Review and explicitly authorize the rewrite plan before applying it with the CLI.'),
                    );
            }
            this.activeInputHash = inputHash(project);
            project.phases[operation] = {
                status: 'running',
                blockers: [],
                evidence: [],
                inputHash: inputHash(project),
            };
            await this.service.save(project);
            this.emitProject(project);
            if (this.preparationStop) {
                await this.finish(this.preparationStop);
                return;
            }
            await this.bridge.start(project, root, command, args);
            if (this.preparationStop) this.stop(this.preparationStop);
        } catch (error) {
            try {
                const project = await this.service.load(),
                    root = await this.service.root(),
                    diagnostic = hostDiagnostic(error, operation);
                this.activeInputHash ??= inputHash(project);
                const relativePath = `artifacts/host-${operation}-${globalThis.crypto.randomUUID()}.json`;
                await writeNewArtifact(
                    root,
                    relativePath,
                    JSON.stringify(
                        {
                            schemaVersion: 1,
                            runId: project.runId,
                            inputHash: this.activeInputHash,
                            operation,
                            status: 'blocked',
                            sensitiveArtifact: true,
                            result: diagnostic,
                        },
                        null,
                        2,
                    ),
                );
                await this.finish({
                    status: 'blocked',
                    inputHash: this.activeInputHash,
                    diagnostics: [diagnostic],
                    artifact: {
                        relativePath,
                        sha256: await fileHash(await ownedPath(root, relativePath)),
                        ownerRunId: project.runId,
                        sensitive: true,
                    },
                });
            } catch {
                this.events.emit({ type: 'diagnostic', code: 'STATE_SAVE_FAILED' });
            }
            this.activeOperation = undefined;
            this.activeInputHash = undefined;
            throw error;
        }
    }
    private async openArtifact(relativePath: string): Promise<void> {
        const project = await this.service.load();
        const artifact = project.artifacts.find(
            (a) =>
                a.relativePath === relativePath &&
                (a.ownerRunId === project.runId ||
                    Object.values(project.phases).some((phase) => phase.evidence.includes(relativePath))),
        );
        if (!artifact) throw new Error(l10n.t('The artifact is not owned by this run.'));
        const root = await this.service.root(),
            selected = await ownedPath(root, artifact.relativePath);
        if ((await fileHash(selected)) !== artifact.sha256)
            throw new Error(l10n.t('Artifact integrity verification failed.'));
        await vscode.window.showTextDocument(vscode.Uri.file(selected), { preview: true });
    }
    private async importEvidence(relativePath: string, phase: GraphEvidencePhase): Promise<void> {
        if (!vscode.workspace.isTrusted || this.activeOperation || this.bridge.active)
            throw new Error(l10n.t('Trust the workspace and stop owned work before importing evidence.'));
        const project = await this.service.load(),
            root = await this.service.root();
        const selected = await ownedPath(root, relativePath);
        const bytes = await readBounded(selected, 16777216);
        const envelope = artifactEnvelopeSchema.parse(JSON.parse(bytes.toString('utf8')));
        if (
            envelope.runId !== project.runId ||
            envelope.inputHash !== inputHash(project) ||
            envelope.operation !== phase ||
            !['succeeded', 'blocked', 'failed'].includes(envelope.status)
        )
            throw new Error(l10n.t('Imported evidence must name this run, current inputs and selected phase.'));
        const rejectCredentials = (value: unknown): void => {
            if (Array.isArray(value)) for (const item of value) rejectCredentials(item);
            else if (value !== null && typeof value === 'object')
                for (const [key, child] of Object.entries(value)) {
                    if (
                        [
                            'password',
                            'secret',
                            'token',
                            'accountkey',
                            'masterkey',
                            'connectionstring',
                            'authorization',
                        ].includes(key.toLowerCase())
                    )
                        throw new Error(l10n.t('Credential fields are prohibited in imported evidence.'));
                    rejectCredentials(child);
                }
        };
        rejectCredentials(envelope.result);
        const fields = z.record(z.string(), z.unknown()).parse(envelope.result);
        const linked: GraphProject['artifacts'] = [];
        for (const [pathField, hashField] of [
            ['reportPath', 'reportSha256'],
            ['sbomPath', 'sbomSha256'],
            ['licenseReportPath', 'licenseReportSha256'],
            ['javaVulnerabilityReportPath', 'javaVulnerabilityReportSha256'],
            ['extensionVulnerabilityReportPath', 'extensionVulnerabilityReportSha256'],
        ]) {
            if (fields[pathField] === undefined) continue;
            const artifact = graphProjectSchema.shape.artifacts.element.parse({
                relativePath: fields[pathField],
                sha256: fields[hashField],
                ownerRunId: project.runId,
                sensitive: true,
            });
            const content = await readBounded(await ownedPath(root, artifact.relativePath), 16777216);
            if (createHash('sha256').update(content).digest('hex') !== artifact.sha256)
                throw new Error(l10n.t('Linked evidence integrity verification failed.'));
            if (artifact.relativePath.endsWith('.json')) rejectCredentials(JSON.parse(content.toString('utf8')));
            linked.push(artifact);
        }
        const sha256 = createHash('sha256').update(bytes).digest('hex');
        const confirmed = await vscode.window.showWarningMessage(
            l10n.t(
                'Import reported {phase} evidence with checksum {checksum}? Review its producer, execution proof and {artifactCount} linked artifacts first. Import does not approve database mutations; readiness independently checks required measurements.',
                {
                    phase,
                    checksum: sha256,
                    artifactCount: linked.length,
                },
            ),
            { modal: true },
            l10n.t('Import reviewed evidence'),
        );
        if (!confirmed) return;
        if ((await fileHash(selected)) !== sha256 || inputHash(await this.service.load()) !== inputHash(project))
            throw new Error(l10n.t('Evidence or project inputs changed during import review.'));
        for (const artifact of linked) {
            if ((await fileHash(await ownedPath(root, artifact.relativePath))) !== artifact.sha256)
                throw new Error(l10n.t('Linked evidence changed during import review.'));
            const old = project.artifacts.find((entry) => entry.relativePath === artifact.relativePath);
            if (old && old.sha256 !== artifact.sha256)
                for (const [name, state] of Object.entries(project.phases))
                    if (name !== phase && state.status === 'succeeded') state.status = 'stale';
            project.artifacts = [
                ...project.artifacts.filter((entry) => entry.relativePath !== artifact.relativePath),
                artifact,
            ];
        }
        project.artifacts = [
            ...project.artifacts.filter((entry) => entry.relativePath !== relativePath),
            { relativePath, sha256, ownerRunId: project.runId, sensitive: true },
        ];
        project.phases[phase] = {
            status: envelope.status,
            inputHash: inputHash(project),
            evidence: [relativePath],
            blockers: [
                l10n.t('Imported producer report; readiness must verify its required measurements and linked inputs.'),
            ],
        };
        await this.service.save(project);
        this.emitProject(project);
    }
    private async finish(result: unknown): Promise<void> {
        if (this.finishing) return this.finishing;
        this.finishing = this.finishOnce(result);
        try {
            await this.finishing;
        } finally {
            this.finishing = undefined;
        }
    }
    private async finishOnce(result: unknown): Promise<void> {
        const operation = this.activeOperation;
        if (!operation) return;
        const project = await this.service.load();
        const previousHash = this.activeInputHash;
        let status: 'succeeded' | 'failed' | 'blocked' | 'paused' | 'cancelled' = 'failed';
        if (result === 'paused' || result === 'cancelled') status = result;
        const evidence: string[] = [];
        if (result === 'paused' || result === 'cancelled')
            result = {
                status,
                diagnostics: [
                    hostDiagnostic(
                        new GraphDiagnosticError(
                            result === 'paused' ? 'OPERATOR_PAUSED' : 'OPERATOR_CANCELLED',
                            'worker-control',
                        ),
                    ),
                ],
            };
        if (result !== null && typeof result === 'object' && 'status' in result) {
            const candidate = result.status;
            if (
                candidate === 'succeeded' ||
                candidate === 'blocked' ||
                candidate === 'paused' ||
                candidate === 'cancelled'
            )
                status = candidate;
            if ('artifact' in result) {
                const artifact = graphProjectSchema.shape.artifacts.element.parse(result.artifact);
                if (
                    artifact.ownerRunId !== project.runId ||
                    !('inputHash' in result) ||
                    result.inputHash !== previousHash
                )
                    throw new Error(l10n.t('Worker evidence does not match this operation.'));
                const root = await this.service.root(),
                    selected = await ownedPath(root, artifact.relativePath);
                if ((await fileHash(selected)) !== artifact.sha256)
                    throw new Error(l10n.t('Artifact integrity verification failed.'));
                project.artifacts.push(artifact);
                evidence.push(artifact.relativePath);
            }
            if ('artifacts' in result) {
                const artifacts = graphProjectSchema.shape.artifacts.parse(result.artifacts);
                for (const artifact of artifacts) {
                    if (
                        artifact.ownerRunId !== project.runId ||
                        !('inputHash' in result) ||
                        result.inputHash !== previousHash ||
                        (await fileHash(await ownedPath(await this.service.root(), artifact.relativePath))) !==
                            artifact.sha256
                    )
                        throw new Error(l10n.t('Produced artifact does not match its worker acknowledgement.'));
                    const old = project.artifacts.find((entry) => entry.relativePath === artifact.relativePath);
                    if (old && old.sha256 !== artifact.sha256) {
                        for (const [name, phase] of Object.entries(project.phases))
                            if (
                                name !== operation &&
                                phase.status === 'succeeded' &&
                                phase.evidence.includes(artifact.relativePath)
                            )
                                phase.status = 'stale';
                    }
                    project.artifacts = [
                        ...project.artifacts.filter((entry) => entry.relativePath !== artifact.relativePath),
                        artifact,
                    ];
                    evidence.push(artifact.relativePath);
                }
            }
        }
        if ((!evidence.length && status !== 'succeeded') || status === 'paused' || status === 'cancelled') {
            const relativePath = `artifacts/${operation}-host-stop-${globalThis.crypto.randomUUID()}.json`;
            const selected = await writeNewArtifact(
                await this.service.root(),
                relativePath,
                canonicalJson({
                    schemaVersion: 1,
                    runId: project.runId,
                    inputHash: previousHash,
                    operation,
                    status,
                    sensitiveArtifact: true,
                    producer: 'extension-host',
                    result,
                    lastObservedActivity: this.recentActivity,
                }),
            );
            project.artifacts.push({
                relativePath,
                ownerRunId: project.runId,
                sensitive: true,
                sha256: await fileHash(selected),
            });
            evidence.push(relativePath);
        }
        project.phases[operation] = {
            status: previousHash !== inputHash(project) ? 'stale' : status,
            inputHash: previousHash ?? null,
            evidence,
            blockers:
                status === 'succeeded'
                    ? []
                    : safeDiagnostics(result).length
                      ? safeDiagnostics(result).map(formatDiagnostic)
                      : [l10n.t('Review the worker diagnostics and current evidence before continuing.')],
        };
        await this.service.save(project);
        if (
            operation === 'source-enumerate' &&
            this.guidedEnumerationOutput &&
            status === 'succeeded' &&
            previousHash === inputHash(project)
        ) {
            try {
                const adopted = await adoptEnumeration(
                    project,
                    await this.service.root(),
                    this.guidedEnumerationOutput,
                );
                await this.service.save(adopted);
                this.emitProject(adopted);
            } catch {
                project.phases[operation].status = 'blocked';
                project.phases[operation].blockers = [
                    l10n.t(
                        'Inventory adoption could not verify ownership, plan/hash or persistence. Preserve the enumeration artifacts and restart guided Prepare; extraction is not authorized.',
                    ),
                ];
                await this.service.save(project);
                this.emitProject(project);
            }
        } else this.emitProject(project);
        this.guidedEnumerationOutput = undefined;
        this.activeOperation = undefined;
        this.activeInputHash = undefined;
    }
    override dispose(): void {
        this.events.close();
        super.dispose();
    }
}
