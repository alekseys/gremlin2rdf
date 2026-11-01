/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { type IActionContext } from '@microsoft/vscode-azext-utils';
import * as l10n from '@vscode/l10n';
import * as vscode from 'vscode';
import { AgeMigrationTab } from '../../panels/ageMigration/AgeMigrationTab';

export async function openAgeMigration(_context: IActionContext): Promise<void> {
    if (!vscode.workspace.getConfiguration('cosmosDB').get<boolean>('experimental.graphMigration.enabled', false))
        throw new Error(l10n.t('Enable the experimental graph migration setting first.'));
    if (!vscode.workspace.isTrusted) throw new Error(l10n.t('Workspace trust is required.'));
    const folders = vscode.workspace.workspaceFolders;
    if (!folders?.length) throw new Error(l10n.t('Open a workspace folder for the graph migration project.'));
    const folder = folders.length === 1 ? folders[0] : await vscode.window.showWorkspaceFolderPick();
    if (folder) AgeMigrationTab.render(folder.uri.fsPath);
}
