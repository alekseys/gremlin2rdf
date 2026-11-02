/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { fileHash, readBounded } from './artifacts';
import { inputHash, workerMessageSchema, type GraphProject, type WorkerMessage } from './contracts';
import { GraphDiagnosticError, hostDiagnostic, safeDiagnostics, type SafeDiagnostic } from './diagnostics';

export class WorkerBridge implements vscode.Disposable {
    private process: ChildProcessWithoutNullStreams | undefined;
    private incoming = -1;
    private outgoing = 0;
    private buffer = Buffer.alloc(0);
    private runId = '';
    private operationId = '';
    private inputHash = '';
    private stopping: 'paused' | 'cancelled' | undefined;
    private stopTimer: ReturnType<typeof setTimeout> | undefined;
    private deadlineTimer: ReturnType<typeof setTimeout> | undefined;
    private failed = false;
    private terminal = false;
    private started = false;
    private deadlineReached = false;
    private deadlineObservation: Pick<SafeDiagnostic, 'operationLimitMillis' | 'elapsedMillis'> = {};
    get active(): boolean {
        return this.process !== undefined;
    }
    constructor(
        private readonly resourceDirectory: string,
        private readonly secrets: vscode.SecretStorage,
        private readonly onMessage: (message: WorkerMessage) => void,
        private readonly onFailure: (
            code: string,
            status?: 'paused' | 'cancelled',
            pending?: boolean,
            diagnostic?: SafeDiagnostic,
        ) => void,
    ) {}
    async start(project: GraphProject, cwd: string, operation: string, arguments_: string[]): Promise<void> {
        if (!vscode.workspace.isTrusted) throw new Error('Workspace trust is required for worker execution');
        if (this.process) throw new Error('A graph worker is already active');
        const jar = path.join(this.resourceDirectory, 'worker.jar');
        const manifest: unknown = JSON.parse(
            (await readBounded(path.join(this.resourceDirectory, 'worker-manifest.json'), 65536)).toString('utf8'),
        );
        if (
            !manifest ||
            typeof manifest !== 'object' ||
            !('workerSha256' in manifest) ||
            (await fileHash(jar)) !== manifest.workerSha256
        ) {
            throw new GraphDiagnosticError('WORKER_PACKAGE_INVALID', 'worker-integrity');
        }
        if (
            !('sourceSha256' in manifest) ||
            (await fileHash(path.join(this.resourceDirectory, 'cosmos-gremlin-source-0.1.0.jar'))) !==
                manifest.sourceSha256
        )
            throw new GraphDiagnosticError('WORKER_PACKAGE_INVALID', 'source-adapter-integrity');
        if (
            operation === 'test' &&
            arguments_[0] === 'benchmark' &&
            (!('testkitSha256' in manifest) ||
                (await fileHash(path.join(this.resourceDirectory, 'graph-testkit-0.1.0.jar'))) !==
                    manifest.testkitSha256)
        )
            throw new Error('Packaged private testkit integrity check failed');
        const env: NodeJS.ProcessEnv = {};
        for (const name of ['PATH', 'Path', 'SystemRoot', 'JAVA_HOME', 'HOME', 'USERPROFILE', 'TEMP', 'TMP']) {
            if (process.env[name]) env[name] = process.env[name];
        }
        const sourceOperation = [
            'source-enumerate',
            'extract',
            'source-compatibility',
            'source-populate',
            'source-cleanup',
        ].includes(operation);
        const privateBenchmark = operation === 'test' && arguments_[0] === 'benchmark';
        const targetOperation =
            !privateBenchmark &&
            ['preflight', 'load', 'resume', 'migrate', 'validate', 'test', 'cleanup', 'publish'].includes(operation);
        const scopes = new Set<string>();
        if ((sourceOperation || targetOperation) && project.mapping.fidelity === 'reduced')
            scopes.add('reduced-fidelity');
        if (
            (sourceOperation || operation === 'load' || operation === 'resume' || operation === 'migrate') &&
            project.consistency === 'best-effort'
        )
            scopes.add('best-effort-source');
        if (operation === 'load' || operation === 'resume' || operation === 'migrate') scopes.add('target-write');
        if (operation === 'preflight' && arguments_[0] === 'probe') scopes.add('target-probe');
        if (operation === 'cleanup' || operation === 'publish') scopes.add(operation);
        if (operation === 'cleanup') scopes.add('target-write');
        if (operation === 'source-populate' || operation === 'source-cleanup') scopes.add(operation);
        if (operation === 'rewrite' && arguments_[0] === 'apply') scopes.add('rewrite');
        if (operation === 'test' && arguments_[0] === 'fixtures')
            for (const scope of ['fixture-test', 'application-write', 'application-delete']) scopes.add(scope);
        const references = [
            ...(sourceOperation || (operation === 'preflight' && arguments_[0] !== 'probe')
                ? [project.source?.authenticationRef, project.source?.customCaRef]
                : []),
            ...(targetOperation ? [project.target?.authenticationRef, project.target?.customCaRef] : []),
            ...project.approvals
                .filter((approval) => scopes.has(approval.operation))
                .map((approval) => approval.keyRef),
        ];
        for (const reference of references) {
            if (!reference) continue;
            const value = (await this.secrets.get(`graph-migration:${reference}`)) ?? process.env[reference.slice(4)];
            if (!value) {
                if (operation === 'preflight' && arguments_[0] !== 'probe') continue;
                throw new GraphDiagnosticError(
                    'CREDENTIAL_REFERENCE_UNAVAILABLE',
                    sourceOperation ? 'source-credential' : 'target-credential',
                );
            }
            env[reference.slice(4)] = value;
        }
        this.runId = project.runId;
        this.inputHash = inputHash(project);
        this.operationId = globalThis.crypto.randomUUID();
        this.incoming = -1;
        this.outgoing = 0;
        this.buffer = Buffer.alloc(0);
        this.stopping = undefined;
        this.failed = false;
        this.terminal = false;
        this.started = false;
        this.deadlineReached = false;
        this.deadlineObservation = {};
        this.process = spawn('java', ['-Xmx1g', '-jar', jar, 'worker'], { cwd, env, shell: false, windowsHide: true });
        if (project.limits.maximumOperationMillis !== undefined) {
            const owned = this.process;
            const startedAt = Date.now(),
                limit = project.limits.maximumOperationMillis;
            this.deadlineTimer = setTimeout(() => {
                if (this.process !== owned || this.terminal) return;
                this.deadlineReached = true;
                this.deadlineObservation = {
                    operationLimitMillis: limit,
                    elapsedMillis: Math.max(0, Date.now() - startedAt),
                };
                this.pause('operation-deadline');
                this.onFailure('OPERATION_DEADLINE_PARTIAL_STATE_PRESERVED', 'paused', true, this.deadlineDiagnostic());
            }, project.limits.maximumOperationMillis);
            this.deadlineTimer.unref();
        }
        this.process.stdout.on('data', (chunk: Buffer) => this.accept(chunk, operation, arguments_));
        this.process.stderr.on('data', () => {
            /* Worker logs are not forwarded to telemetry or the webview. */
        });
        this.process.on('error', () => this.failure('WORKER_START_FAILED'));
        this.process.on('close', (code) => {
            this.clearStopTimer();
            this.clearDeadlineTimer();
            this.process = undefined;
            if (!this.terminal || code !== 0 || this.buffer.length) this.failure('WORKER_DISCONNECTED');
        });
    }
    private accept(chunk: Buffer, operation: string, arguments_: string[]): void {
        if (this.failed) return;
        this.buffer = Buffer.concat([this.buffer, chunk]);
        let end: number;
        while ((end = this.buffer.indexOf(10)) !== -1) {
            if (end > 65536) {
                this.failProtocol();
                return;
            }
            const frame = this.buffer.subarray(0, end);
            this.buffer = this.buffer.subarray(end + 1);
            let value: unknown;
            try {
                value = JSON.parse(frame.toString('utf8'));
            } catch (error) {
                if (!(error instanceof SyntaxError)) throw error;
                this.failProtocol();
                return;
            }
            const parsed = workerMessageSchema.safeParse(value);
            if (!parsed.success || parsed.data.sequence <= this.incoming) {
                this.failProtocol();
                return;
            }
            this.incoming = parsed.data.sequence;
            if (parsed.data.type === 'hello') {
                if (
                    parsed.data.sequence !== 0 ||
                    parsed.data.runId ||
                    parsed.data.operationId ||
                    !parsed.data.payload ||
                    typeof parsed.data.payload !== 'object' ||
                    Array.isArray(parsed.data.payload) ||
                    parsed.data.payload.workerVersion !== '0.1.0'
                ) {
                    this.failProtocol();
                    return;
                }
                this.send('start', { operation, arguments: arguments_, runId: this.runId, inputHash: this.inputHash });
                this.started = true;
                if (this.stopping)
                    this.send(
                        this.stopping === 'paused' ? 'pause' : 'cancel',
                        this.deadlineReached ? { reason: 'operation-deadline' } : {},
                    );
            } else if (parsed.data.runId !== this.runId || parsed.data.operationId !== this.operationId) {
                this.failProtocol();
                return;
            }
            if (this.stopping && (parsed.data.type === 'result' || parsed.data.type === 'error')) {
                const payload = parsed.data.payload;
                const code =
                    this.deadlineReached && this.stopping === 'paused'
                        ? 'OPERATION_DEADLINE_PARTIAL_STATE_PRESERVED'
                        : this.stopping === 'paused'
                          ? 'OPERATOR_PAUSED'
                          : 'OPERATOR_CANCELLED';
                this.onMessage({
                    ...parsed.data,
                    payload: {
                        ...(payload && typeof payload === 'object' && !Array.isArray(payload) ? payload : {}),
                        status: this.stopping,
                        diagnostics: [
                            ...safeDiagnostics(payload).filter(
                                (diagnostic) =>
                                    diagnostic.code !== code &&
                                    (!this.deadlineReached ||
                                        ![
                                            'OPERATOR_PAUSED',
                                            'OPERATION_DEADLINE',
                                            'OPERATION_DEADLINE_PARTIAL_STATE_PRESERVED',
                                        ].includes(diagnostic.code)),
                            ),
                            code === 'OPERATION_DEADLINE_PARTIAL_STATE_PRESERVED'
                                ? this.deadlineDiagnostic()
                                : hostDiagnostic(new GraphDiagnosticError(code, 'worker-control')),
                        ],
                    },
                });
            } else this.onMessage(parsed.data);
            if (parsed.data.type === 'result' || parsed.data.type === 'error') {
                this.terminal = true;
                this.clearDeadlineTimer();
                this.clearStopTimer();
                this.process?.stdin.end();
                this.armStopTimer();
            }
        }
        if (this.buffer.length > 65536) this.failProtocol();
    }
    private deadlineDiagnostic(): SafeDiagnostic {
        return {
            ...hostDiagnostic(new GraphDiagnosticError('OPERATION_DEADLINE_PARTIAL_STATE_PRESERVED', 'worker-control')),
            ...this.deadlineObservation,
        };
    }
    private failProtocol(): void {
        this.failure('INVALID_WORKER_PROTOCOL');
        this.process?.stdin.end();
        this.armStopTimer();
    }
    private failure(code: string): void {
        if (this.failed) return;
        this.failed = true;
        this.onFailure(code, this.stopping);
    }
    private clearStopTimer(): void {
        if (this.stopTimer) clearTimeout(this.stopTimer);
        this.stopTimer = undefined;
    }
    private clearDeadlineTimer(): void {
        if (this.deadlineTimer) clearTimeout(this.deadlineTimer);
        this.deadlineTimer = undefined;
    }
    private armStopTimer(): void {
        this.clearStopTimer();
        const owned = this.process;
        if (!owned) return;
        this.stopTimer = setTimeout(() => {
            if (this.process !== owned) return;
            try {
                if (!owned.kill()) {
                    this.onFailure('WORKER_TERMINATION_FAILED');
                    return;
                }
                this.failure('WORKER_TERMINATION_REQUESTED_PARTIAL_STATE_PRESERVED');
            } catch {
                this.onFailure('WORKER_TERMINATION_FAILED');
            }
        }, 5000);
        this.stopTimer.unref();
    }
    private send(type: 'start' | 'pause' | 'cancel' | 'status', payload: Record<string, unknown>): void {
        const frame = {
            protocolVersion: 1,
            type,
            runId: this.runId,
            operationId: this.operationId,
            sequence: this.outgoing++,
            timestamp: new Date().toISOString(),
            phase: 'control',
            payload,
        };
        const text = JSON.stringify(frame);
        if (Buffer.byteLength(text) > 65536) throw new Error('Worker input exceeds protocol frame bound');
        this.process?.stdin.write(text + '\n');
    }
    cancel(): void {
        if (!this.process) return;
        this.clearDeadlineTimer();
        this.stopping = 'cancelled';
        if (this.started) this.send('cancel', {});
        this.armStopTimer();
    }
    pause(reason: 'operator' | 'operation-deadline' = 'operator'): void {
        if (!this.process) return;
        if (reason === 'operator') this.clearDeadlineTimer();
        this.stopping = 'paused';
        if (this.started) this.send('pause', reason === 'operation-deadline' ? { reason } : {});
        this.armStopTimer();
    }
    dispose(): void {
        this.clearDeadlineTimer();
        this.cancel();
        this.process?.stdin.end();
    }
}
