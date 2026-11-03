/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { createHash } from 'node:crypto';
import { open } from 'node:fs/promises';

export async function fileHash(selected: string): Promise<string> {
    const file = await open(selected, 'r');
    try {
        const before = await file.stat();
        if (!before.isFile()) throw new Error('Artifact must be a regular file');
        const hash = createHash('sha256'),
            bytes = Buffer.alloc(65536);
        let count: number,
            total = 0;
        while ((count = (await file.read(bytes, 0, bytes.length, null)).bytesRead) !== 0) {
            hash.update(bytes.subarray(0, count));
            total += count;
        }
        const after = await file.stat();
        if (before.size !== after.size || before.mtimeMs !== after.mtimeMs || total !== before.size)
            throw new Error('Artifact changed during checksum verification');
        return hash.digest('hex');
    } finally {
        await file.close();
    }
}

export async function readBounded(selected: string, limit: number): Promise<Buffer> {
    const file = await open(selected, 'r');
    try {
        const before = await file.stat();
        if (!before.isFile() || before.size > limit) throw new Error('Artifact exceeds bounded read size');
        const bytes = Buffer.alloc(before.size + 1);
        let offset = 0,
            count: number;
        while (
            offset < bytes.length &&
            (count = (await file.read(bytes, offset, bytes.length - offset, null)).bytesRead) !== 0
        )
            offset += count;
        const after = await file.stat();
        if (offset > limit || before.size !== after.size || before.mtimeMs !== after.mtimeMs || offset !== before.size)
            throw new Error('Artifact changed during bounded read');
        return bytes.subarray(0, offset);
    } finally {
        await file.close();
    }
}
