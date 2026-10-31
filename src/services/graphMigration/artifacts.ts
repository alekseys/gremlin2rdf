/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { lstat, mkdir, open, realpath } from 'node:fs/promises';
import * as path from 'node:path';
import { relativePathSchema } from './contracts';
export { fileHash, readBounded } from './boundedIO';

export async function ownedPath(root: string, relative: string): Promise<string> {
    const base = await realpath(root);
    let selected = base;
    for (const part of relativePathSchema.parse(relative).split('/')) {
        selected = path.join(selected, part);
        if ((await lstat(selected)).isSymbolicLink()) throw new Error('Artifact symlinks are prohibited');
    }
    const resolved = await realpath(selected);
    if (!resolved.startsWith(base + path.sep)) throw new Error('Artifact path is outside the run directory');
    return resolved;
}

export async function createOwnedDirectory(root: string, relative: string): Promise<string> {
    const base = await realpath(root);
    let selected = base;
    for (const part of relativePathSchema.parse(relative).split('/')) {
        selected = path.join(selected, part);
        try {
            await mkdir(selected);
        } catch (error) {
            if (!(error instanceof Error && 'code' in error && error.code === 'EEXIST')) throw error;
        }
        if (!(await lstat(selected)).isDirectory() || (await lstat(selected)).isSymbolicLink())
            throw new Error('Artifact directories must be owned non-symlink directories');
    }
    return ownedPath(base, relative);
}

export async function writeNewArtifact(root: string, relative: string, contents: string): Promise<string> {
    const safe = relativePathSchema.parse(relative);
    const parent = path.posix.dirname(safe);
    const directory = parent === '.' ? await realpath(root) : await createOwnedDirectory(root, parent);
    const selected = path.join(directory, path.posix.basename(safe));
    const file = await open(selected, 'wx');
    try {
        await file.writeFile(contents);
        await file.sync();
    } finally {
        await file.close();
    }
    return selected;
}
