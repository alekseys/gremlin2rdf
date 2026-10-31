/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { open, rename, mkdir, lstat, realpath, unlink } from 'node:fs/promises';
import * as path from 'node:path';
import { readBounded } from './artifacts';
import { graphMigrationFolder, graphProjectSchema, invalidateEvidence, type GraphProject } from './contracts';

export class GraphProjectService {
    constructor(private readonly workspacePath: string) {}
    async root(): Promise<string> {
        const workspace = await realpath(this.workspacePath);
        const folder = path.join(workspace, graphMigrationFolder);
        await mkdir(folder, { recursive: true });
        if ((await lstat(folder)).isSymbolicLink()) throw new Error('Graph project symlinks are prohibited');
        return folder;
    }
    async load(): Promise<GraphProject> {
        const root = await this.root();
        const file = path.join(root, 'project.json');
        if ((await lstat(file)).isSymbolicLink()) throw new Error('Graph project symlinks are prohibited');
        const bytes = await readBounded(file, 1048576);
        return invalidateEvidence(graphProjectSchema.parse(JSON.parse(bytes.toString('utf8'))));
    }
    async save(value: GraphProject): Promise<void> {
        const project = graphProjectSchema.parse(value);
        const root = await this.root();
        const lockPath = path.join(root, 'project.lock');
        const lock = await open(lockPath, 'wx');
        try {
            const pending = path.join(root, 'project.pending');
            const file = await open(pending, 'wx');
            try {
                await file.writeFile(JSON.stringify(project, null, 2));
                await file.sync();
            } finally {
                await file.close();
            }
            await rename(pending, path.join(root, 'project.json'));
        } finally {
            await lock.close();
            await unlink(lockPath);
        }
    }
}
