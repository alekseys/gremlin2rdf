/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import path from 'node:path';
import { z } from 'zod';
import { fileHash, readBounded } from '../graphMigration/boundedIO';
import { ageInputHash, ageMessageSchema, ageRequestSchema, type AgeMessage, type AgeProject } from './contracts';

const manifestSchema = z
    .object({
        schemaVersion: z.literal(1),
        protocolVersion: z.literal(1),
        rid: z.literal('win-x64'),
        executable: z.literal('AgeWorker.exe'),
        files: z.record(z.string().regex(/^[a-zA-Z0-9_.-]+$/u), z.string().regex(/^[a-f0-9]{64}$/u)),
        sdk: z.string(),
        gremlin: z.literal('3.4.13'),
        sourceCompatibility: z.string(),
    })
    .strict();

export class AgeBridge {
    private process?: ChildProcessWithoutNullStreams;
    private preparing = false;
    private preparationStopped = false;
    private control?: { project: AgeProject; operationId: string; operation: string; sequence: number };
    private stopTimer?: ReturnType<typeof setTimeout>;
    constructor(
        private readonly resources: string,
        private readonly secrets: { get(key: string): Thenable<string | undefined> },
        private readonly message: (value: AgeMessage) => void,
        private readonly failure: (code: string) => void,
        private readonly canStart: () => boolean = () => true,
    ) {}
    get active(): boolean {
        return Boolean(this.process) || this.preparing;
    }
    async start(
        project: AgeProject,
        operation: string,
        input: string,
        output: string,
        cwd: string,
        expectedResourceHash = '',
    ): Promise<void> {
        if (this.active) throw new Error('AGE worker already active.');
        this.preparing = true;
        this.preparationStopped = false;
        try {
            await this.launch(project, operation, input, output, cwd, expectedResourceHash);
        } finally {
            this.preparing = false;
        }
    }
    private async launch(
        project: AgeProject,
        operation: string,
        input: string,
        output: string,
        cwd: string,
        expectedResourceHash: string,
    ): Promise<void> {
        const operationId = globalThis.crypto.randomUUID();
        const request = ageRequestSchema.parse({
            protocolVersion: 1,
            sequence: 1,
            type: 'start',
            runId: project.runId,
            operationId,
            inputHash: ageInputHash(project),
            operation,
            project,
            input,
            output,
            expectedResourceHash,
        });
        if (process.platform !== 'win32' || process.arch !== 'x64')
            throw new Error(
                'AGE worker currently supports Windows x64 extension hosts only. Use the Jena workflow or build a verified AGE RID.',
            );
        const manifest = manifestSchema.parse(
            JSON.parse((await readBounded(path.join(this.resources, 'worker-manifest.json'), 65536)).toString('utf8')),
        );
        for (const [file, expected] of Object.entries(manifest.files)) {
            if ((await fileHash(path.join(this.resources, manifest.rid, file))) !== expected)
                throw new Error('AGE worker integrity check failed.');
        }
        if (!manifest.files[manifest.executable]) throw new Error('AGE executable missing from integrity manifest.');
        const env: NodeJS.ProcessEnv = {};
        for (const key of ['PATH', 'Path', 'SystemRoot', 'TEMP', 'TMP', 'USERPROFILE'])
            if (process.env[key]) env[key] = process.env[key];
        const refs = new Set<string>(['env:AGE_APPROVAL_KEY']);
        if (operation === 'extract' && project.source) refs.add(project.source.authenticationRef);
        if (['load', 'resume', 'query', 'validate'].includes(operation) && project.target)
            refs.add(project.target.authenticationRef);
        for (const ref of refs) {
            const key = ref.slice(4);
            const value = (await this.secrets.get(`gremlin2age:${key}`)) ?? process.env[key];
            if (value) env[key] = value;
        }
        let buffer = Buffer.alloc(0),
            sequence = 0,
            terminal = false,
            invalid = false;
        if (this.preparationStopped || !this.canStart())
            throw new Error('AGE preparation cancelled or workspace trust revoked.');
        const child = spawn(path.join(this.resources, manifest.rid, manifest.executable), ['worker'], {
            cwd,
            env,
            shell: false,
            windowsHide: true,
        });
        this.process = child;
        this.control = { project, operationId, operation, sequence: 1 };
        const deadline = setTimeout(() => this.stop(), project.limits.maximumOperationSeconds * 1000);
        const rejectFrame = (code: string) => {
            if (invalid) return;
            invalid = true;
            buffer = Buffer.alloc(0);
            this.failure(code);
            this.stop();
        };
        child.stdout.on('data', (data: Buffer) => {
            if (invalid) return;
            buffer = Buffer.concat([buffer, data]);
            let end: number;
            while ((end = buffer.indexOf(10)) !== -1) {
                if (end > 1048576) {
                    rejectFrame('AGE_FRAME_BOUND');
                    return;
                }
                const line = buffer.subarray(0, end).toString('utf8');
                buffer = buffer.subarray(end + 1);
                try {
                    const event = ageMessageSchema.parse(JSON.parse(line));
                    if (
                        event.runId !== project.runId ||
                        event.operationId !== operationId ||
                        event.phase !== operation ||
                        event.sequence !== ++sequence ||
                        terminal
                    )
                        throw new Error('AGE protocol scope mismatch.');
                    terminal = event.type !== 'progress';
                    this.message(event);
                    if (terminal) {
                        clearTimeout(deadline);
                        child.stdin.end();
                    }
                } catch {
                    rejectFrame('AGE_PROTOCOL_INVALID');
                    return;
                }
                if (buffer.length > 1048576) {
                    rejectFrame('AGE_FRAME_BOUND');
                }
            }
        });
        child.stderr.on('data', () => {
            /* Raw worker/provider logs must not reach the webview. */
        });
        child.on('error', () => this.failure('AGE_WORKER_START_FAILED'));
        child.on('close', (code) => {
            clearTimeout(deadline);
            if (this.stopTimer) clearTimeout(this.stopTimer);
            this.process = undefined;
            this.control = undefined;
            if (!invalid && (!terminal || code !== 0 || buffer.length)) this.failure('AGE_WORKER_DISCONNECTED');
        });
        child.stdin.on('error', () => {
            this.failure('AGE_WORKER_INPUT_CLOSED');
            this.stop();
        });
        child.stdin.write(JSON.stringify(request) + '\n');
    }
    stop(): void {
        this.preparationStopped = true;
        if (!this.process || !this.control) return;
        const child = this.process,
            c = this.control;
        if (!child.stdin.destroyed && !child.stdin.writableEnded)
            child.stdin.write(
                JSON.stringify(
                    ageRequestSchema.parse({
                        protocolVersion: 1,
                        sequence: ++c.sequence,
                        type: 'pause',
                        runId: c.project.runId,
                        operationId: c.operationId,
                        inputHash: ageInputHash(c.project),
                        operation: c.operation,
                        project: null,
                        input: '',
                        output: '',
                    }),
                ) + '\n',
            );
        this.stopTimer = setTimeout(() => {
            if (this.process === child) child.kill();
        }, 5000);
    }
    dispose(): void {
        this.stop();
        this.process?.stdin.end();
    }
}
