/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { TypedEventSink } from '@microsoft/vscode-ext-webview';
import { attachTrpc } from '@microsoft/vscode-ext-webview/host';
import * as l10n from '@vscode/l10n';
import { createHash } from 'node:crypto';
import { mkdir, rename, writeFile, stat } from 'node:fs/promises';
import path from 'node:path';
import * as vscode from 'vscode';
import { ext } from '../../extensionVariables';
import { AgeBridge } from '../../services/ageMigration/AgeBridge';
import { agePath } from '../../services/ageMigration/artifacts';
import {
    ageProjectSchema,
    ageInputHash,
    type AgeMessage,
    type AgeProject,
    type AgeOperation,
} from '../../services/ageMigration/contracts';
import { readBounded, fileHash } from '../../services/graphMigration/boundedIO';
import { BaseTab } from '../BaseTab';
import { ageCaller, ageRouter, type AgeContext } from './ageRouter';

export class AgeMigrationTab extends BaseTab {
    private static readonly open = new Map<string, { panel: vscode.WebviewPanel; tab: AgeMigrationTab }>();
    private readonly root: string;
    private readonly events = new TypedEventSink<AgeMessage | { type: 'diagnostic'; code: string }>();
    private readonly bridge: AgeBridge;
    constructor(panel: vscode.WebviewPanel, workspace: string) {
        super(panel, 'cosmosDbAgeMigration');
        this.root = path.join(workspace, '.cosmosdb-age-migration');
        this.bridge = new AgeBridge(
            path.join(ext.context.extensionPath, 'resources', 'age-migration'),
            ext.context.secrets,
            (event) => {
                if (event.type !== 'progress') {
                    void this.receipt(event)
                        .then(() => this.events.emit(event))
                        .catch(() => {
                            this.events.emit({ type: 'diagnostic', code: 'AGE_RESULT_SAVE_FAILED' });
                            void vscode.window.showErrorMessage(
                                l10n.t('AGE result could not be saved. Review preserved artifacts.'),
                            );
                        });
                } else this.events.emit(event);
            },
            (code) => {
                this.events.emit({ type: 'diagnostic', code });
                void vscode.window.showErrorMessage(
                    l10n.t('AGE worker stopped: {code}. Review the confirmed checkpoint.', { code }),
                );
            },
            () => vscode.workspace.isTrusted,
        );
        const context: AgeContext = {
            eventSink: this.events,
            load: async () => {
                try {
                    return (await readBounded(await agePath(this.root, 'project.json'), 1048576)).toString('utf8');
                } catch (error) {
                    if (error instanceof Error && 'code' in error && error.code === 'ENOENT') return null;
                    throw error;
                }
            },
            save: async (json) => {
                this.guard();
                const p = ageProjectSchema.parse(JSON.parse(json));
                await this.persist({
                    ...p,
                    approvals: p.approvals.filter((a) => a.runId === p.runId && a.inputHash === ageInputHash(p)),
                });
            },
            run: (operation, input, output) => this.run(operation, input, output),
            authorize: (input) => this.authorize(input),
            authorizeRewrite: (input, output) => this.authorizeRewrite(input, output),
            credentials: () => this.credentials(),
            importArtifact: async (relative) => {
                this.guard();
                const files = await vscode.window.showOpenDialog({
                    canSelectMany: false,
                    openLabel: l10n.t('Import reviewed AGE artifact'),
                });
                if (!files?.length) return;
                const contents = await readBounded(files[0].fsPath, 16_777_216);
                await writeFile(await agePath(this.root, relative), contents, { flag: 'wx' });
            },
            artifact: async (relative) => {
                await vscode.window.showTextDocument(
                    await vscode.workspace.openTextDocument(vscode.Uri.file(await agePath(this.root, relative))),
                );
            },
            stop: () => this.bridge.stop(),
        };
        this.disposables.push(attachTrpc(panel, context, ageRouter, ageCaller).disposable, this.bridge);
    }
    private guard(): void {
        if (!vscode.workspace.isTrusted) throw new Error(l10n.t('Workspace trust is required.'));
        if (this.bridge.active) throw new Error(l10n.t('Pause the AGE worker before changing project inputs.'));
    }
    private async persist(p: AgeProject): Promise<void> {
        await mkdir(this.root, { recursive: true });
        const target = await agePath(this.root, 'project.json');
        const temporary = await agePath(this.root, 'project.json.pending');
        await writeFile(temporary, JSON.stringify(p, null, 2), { flag: 'wx' });
        await rename(temporary, target);
    }
    private async project(): Promise<AgeProject> {
        return ageProjectSchema.parse(
            JSON.parse((await readBounded(await agePath(this.root, 'project.json'), 1048576)).toString('utf8')),
        );
    }
    private async run(operation: AgeOperation, input: string, output: string): Promise<void> {
        this.guard();
        await this.bridge.start(await this.project(), operation, input, output, this.root);
    }
    private async authorize(input: string): Promise<void> {
        this.guard();
        const p = await this.project();
        const selected = await agePath(this.root, input);
        const info = await stat(selected);
        if (!info.isFile() || info.size === 0)
            throw new Error(l10n.t('Select nonempty canonical input before authorization.'));
        const checksum = await fileHash(selected);
        const answer = await vscode.window.showWarningMessage(
            l10n.t(
                'Authorize AGE load for run {run}, graph {graph}, ledger {ledger}, and canonical SHA256 {checksum}? This can create owned ledger tables and write the dedicated graph.',
                { run: p.runId, graph: p.target?.graph ?? '', ledger: p.target?.ledgerSchema ?? '', checksum },
            ),
            { modal: true },
            l10n.t('Authorize'),
        );
        if (!answer) return;
        this.guard();
        if (ageInputHash(await this.project()) !== ageInputHash(p))
            throw new Error(l10n.t('AGE project inputs changed during authorization review.'));
        let key = await ext.context.secrets.get('gremlin2age:AGE_APPROVAL_KEY');
        if (!key) {
            key = Buffer.from(globalThis.crypto.getRandomValues(new Uint8Array(32))).toString('hex');
            await ext.context.secrets.store('gremlin2age:AGE_APPROVAL_KEY', key);
        }
        await this.bridge.start(p, 'authorize', 'load', input, this.root, checksum);
    }
    private async receipt(event: AgeMessage): Promise<void> {
        const file = await agePath(this.root, 'last-result.json');
        await writeFile(file, JSON.stringify(event, null, 2));
        if (event.type === 'result' && ['authorize', 'authorize-rewrite'].includes(event.phase)) {
            const p = await this.project();
            const approval = ageProjectSchema.shape.approvals.element.parse(event.payload);
            if (approval.runId !== p.runId || approval.inputHash !== ageInputHash(p))
                throw new Error(
                    l10n.t('AGE approval belongs to changed project inputs; review the current project again.'),
                );
            await this.persist({
                ...p,
                approvals: [...p.approvals.filter((a) => a.operation !== approval.operation), approval],
            });
        }
    }
    private async authorizeRewrite(input: string, output: string): Promise<void> {
        this.guard();
        const original = (await readBounded(await agePath(this.root, input), 1048576))
            .toString('utf8')
            .replace(/^\uFEFF/u, '');
        const sourceHash = createHash('sha256').update(original).digest('hex');
        const resourceHash = createHash('sha256').update(JSON.stringify({ sourceHash, output })).digest('hex');
        await agePath(this.root, output);
        const answer = await vscode.window.showWarningMessage(
            l10n.t(
                'Authorize the reviewed C# literal-query rewrite from {input} to a new file {output}? Original files are never overwritten. Result signatures and application equivalence need manual review.',
                { input, output },
            ),
            { modal: true },
            l10n.t('Authorize'),
        );
        if (!answer) return;
        this.guard();
        if (!(await ext.context.secrets.get('gremlin2age:AGE_APPROVAL_KEY')))
            await ext.context.secrets.store(
                'gremlin2age:AGE_APPROVAL_KEY',
                Buffer.from(globalThis.crypto.getRandomValues(new Uint8Array(32))).toString('hex'),
            );
        await this.bridge.start(await this.project(), 'authorize-rewrite', input, output, this.root, resourceHash);
    }
    private async credentials(): Promise<void> {
        this.guard();
        const p = await this.project();
        for (const [key, label] of [
            [p.source?.authenticationRef.slice(4) ?? 'AGE_SOURCE_KEY', l10n.t('Cosmos Gremlin source key')],
            [p.target?.authenticationRef.slice(4) ?? 'AGE_TARGET_PASSWORD', l10n.t('PostgreSQL AGE password')],
        ]) {
            const value = await vscode.window.showInputBox({ title: label, password: true, ignoreFocusOut: true });
            if (value) await ext.context.secrets.store(`gremlin2age:${key}`, value);
        }
    }
    static render(workspace: string): AgeMigrationTab {
        const key = path.resolve(workspace).toLowerCase();
        const existing = AgeMigrationTab.open.get(key);
        if (existing) {
            existing.panel.reveal();
            return existing.tab;
        }
        const panel = vscode.window.createWebviewPanel(
            'cosmosDbAgeMigration',
            l10n.t('Gremlin to PostgreSQL AGE (Experimental)'),
            vscode.ViewColumn.Active,
            { enableScripts: true, retainContextWhenHidden: true },
        );
        const tab = new AgeMigrationTab(panel, workspace);
        AgeMigrationTab.open.set(key, { panel, tab });
        tab.disposables.push(panel.onDidDispose(() => AgeMigrationTab.open.delete(key)));
        return tab;
    }
}
