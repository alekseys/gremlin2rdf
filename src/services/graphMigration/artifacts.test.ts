/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, rm, symlink, writeFile } from 'node:fs/promises';
import * as os from 'node:os';
import * as path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { fileHash, ownedPath, readBounded } from './artifacts';

let root: string;
beforeEach(async () => {
    root = await mkdtemp(path.join(os.tmpdir(), 'g2r-artifact-test-'));
});
afterEach(async () => {
    await rm(root, { recursive: true, force: true });
});
describe('bounded owned artifacts', () => {
    it('hashes a large fixture with bounded chunks and rejects oversized buffered reads', async () => {
        const bytes = Buffer.alloc(16777217, 120),
            selected = path.join(root, 'large.nt');
        await writeFile(selected, bytes);
        expect(await fileHash(await ownedPath(root, 'large.nt'))).toBe(
            createHash('sha256').update(bytes).digest('hex'),
        );
        await expect(readBounded(selected, 1048576)).rejects.toThrow('bounded read');
    });
    it('rejects traversal and junction/symlink artifacts even when their target exists', async () => {
        await mkdir(path.join(root, 'actual'));
        await writeFile(path.join(root, 'actual', 'data.json'), '{}');
        await symlink(
            path.join(root, 'actual'),
            path.join(root, 'link'),
            process.platform === 'win32' ? 'junction' : 'dir',
        );
        await expect(ownedPath(root, '../outside')).rejects.toThrow();
        await expect(ownedPath(root, 'link/data.json')).rejects.toThrow('symlinks');
    });
    it('reads only regular size-bounded files and detects checksum changes', async () => {
        const selected = path.join(root, 'evidence.json');
        await writeFile(selected, '{"status":"succeeded"}');
        const before = await fileHash(selected);
        expect((await readBounded(selected, 128)).toString('utf8')).toContain('succeeded');
        await writeFile(selected, '{"status":"tampered"}');
        expect(await fileHash(selected)).not.toBe(before);
        await expect(readBounded(root, 128)).rejects.toThrow();
    });
});
