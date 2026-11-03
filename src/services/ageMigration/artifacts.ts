/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { lstat, realpath } from 'node:fs/promises';
import path from 'node:path';

export async function agePath(root: string, relative: string): Promise<string> {
    if (!relative || relative.length > 1024 || !/^[a-zA-Z0-9_. /-]+$/u.test(relative))
        throw new Error('AGE artifact must be a relative path.');
    const parts = relative.split('/');
    if (parts.some((p) => !p || p === '.' || p === '..')) throw new Error('AGE artifact traversal prohibited.');
    if ((await lstat(root)).isSymbolicLink()) throw new Error('AGE project root symlink prohibited.');
    const base = await realpath(root);
    let selected = base;
    for (const part of parts) {
        selected = path.join(selected, part);
        try {
            if ((await lstat(selected)).isSymbolicLink()) throw new Error('AGE artifact symlink prohibited.');
        } catch (error) {
            if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error;
        }
    }
    return selected;
}
