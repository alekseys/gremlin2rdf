/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { mkdtemp, readFile, rm, rename, writeFile } from 'node:fs/promises';
import type * as FileSystem from 'node:fs/promises';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { afterEach, expect, it, vi } from 'vitest';
import { graphProjectSchema } from './contracts';
import { GraphProjectService } from './GraphProjectService';

vi.mock('node:fs/promises', async (original) => {
    const fs = await original<typeof FileSystem>();
    return { ...fs, rename: vi.fn(fs.rename) };
});
const roots: string[] = [];
afterEach(async () => {
    vi.mocked(rename).mockReset();
    const fs = await vi.importActual<typeof FileSystem>('node:fs/promises');
    vi.mocked(rename).mockImplementation(fs.rename);
    await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});
async function fixture() {
    const root = await mkdtemp(path.join(tmpdir(), 'graph-project-save-'));
    roots.push(root);
    const service = new GraphProjectService(root);
    const project = graphProjectSchema.parse({
        schemaVersion: 1,
        migrationKind: 'cosmos-gremlin-to-jena',
        baselineRepositorySha: 'a'.repeat(40),
        runId: 'fixture',
        protocolVersion: 1,
        workerVersion: '0.1.0',
        mapping: {
            schemaVersion: 1,
            baseIri: 'urn:test:',
            vocabulary: 'urn:vocab:',
            labels: {},
            properties: {},
            edges: {},
            exclusions: [],
            derivedRelationships: false,
            provenance: true,
            fidelity: 'strict',
        },
        source: null,
        target: null,
        limits: {
            batchRecords: 1,
            batchBytes: 1024,
            maxRecordBytes: 1024,
            requestTimeoutMillis: 1000,
            maxRetries: 0,
            queueCapacity: 1,
            writerConcurrency: 1,
            minimumFreeDiskBytes: 0,
        },
        consistency: 'immutable',
        phases: {},
        approvals: [],
        applicationHashes: {},
        artifacts: [],
    });
    await service.save(project);
    vi.mocked(rename).mockClear();
    return { service, project, root: await service.root() };
}
it('retries transient Windows replacement contention without deleting either file', async () => {
    const { service, project, root } = await fixture();
    vi.mocked(rename).mockRejectedValueOnce(Object.assign(new Error('busy'), { code: 'EPERM' }));
    await service.save({ ...project, runId: 'updated' });
    expect(vi.mocked(rename)).toHaveBeenCalledTimes(2);
    expect(JSON.parse(await readFile(path.join(root, 'project.json'), 'utf8')).runId).toBe('updated');
});
it('preserves the live and flushed pending state after five busy replacements', async () => {
    const { service, project, root } = await fixture();
    vi.mocked(rename).mockRejectedValue(Object.assign(new Error('busy'), { code: 'EBUSY' }));
    await expect(service.save({ ...project, runId: 'pending' })).rejects.toMatchObject({
        code: 'PROJECT_STATE_REPLACE_BUSY',
        stage: 'project-atomic-replace',
    });
    expect(vi.mocked(rename)).toHaveBeenCalledTimes(5);
    expect(JSON.parse(await readFile(path.join(root, 'project.json'), 'utf8')).runId).toBe('fixture');
    expect(JSON.parse(await readFile(path.join(root, 'project.pending'), 'utf8')).runId).toBe('pending');
});
it('does not retry other errors or overwrite an earlier pending write', async () => {
    const { service, project, root } = await fixture();
    await writeFile(path.join(root, 'project.pending'), 'owned prior draft');
    await expect(service.save(project)).rejects.toMatchObject({ code: 'EEXIST' });
    expect(vi.mocked(rename)).not.toHaveBeenCalled();
    expect(await readFile(path.join(root, 'project.pending'), 'utf8')).toBe('owned prior draft');
});
