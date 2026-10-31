/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { spawnSync } from 'node:child_process';
import { mkdir, readFile, writeFile, unlink } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { graphProjectSchema } from '../src/services/graphMigration/contracts.ts';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const jar = path.join(root, 'resources', 'graph-migration', 'worker.jar');
const work = path.join(root, 'java', 'migration-engine', 'target', 'example-rewrite');
await mkdir(work, { recursive: true });
const source = 'java/examples-before/src/main/java/example/GraphRepository.java';
const output = 'java/examples-after/src/main/java/example/GraphRepository.java';
const project = graphProjectSchema.parse({
    schemaVersion: 1,
    migrationKind: 'cosmos-gremlin-to-jena',
    baselineRepositorySha: '22e3f2cccbfcc6622cacbf1873b9c620acba085e',
    runId: 'fixture',
    protocolVersion: 1,
    workerVersion: '0.1.0',
    source: null,
    target: {
        datasetUrl: 'http://localhost:1/fixture',
        queryEndpoint: 'http://localhost:1/fixture/query',
        updateEndpoint: 'http://localhost:1/fixture/update',
        graphStoreEndpoint: 'http://localhost:1/fixture/data',
        authenticationRef: null,
        graphNamespace: 'urn:example:',
        publicationMode: 'manual',
    },
    mapping: {
        schemaVersion: 1,
        baseIri: 'urn:fixture:',
        vocabulary: 'urn:domain:',
        labels: {},
        properties: {},
        edges: {},
        exclusions: [],
        derivedRelationships: true,
        provenance: true,
        fidelity: 'strict',
    },
    limits: {
        batchRecords: 100,
        batchBytes: 1048576,
        maxRecordBytes: 262144,
        requestTimeoutMillis: 1000,
        maxRetries: 0,
        queueCapacity: 100,
        writerConcurrency: 1,
        minimumFreeDiskBytes: 0,
    },
    consistency: 'immutable',
    phases: {},
    approvals: [],
    applicationHashes: {},
    artifacts: [],
});
const invoke = (args) => {
    const result = spawnSync('java', ['-jar', jar, ...args], {
        cwd: root,
        encoding: 'utf8',
        env: { ...process.env, GRAPH_TEST_APPROVAL_KEY: 'synthetic-test-only-key-not-a-live-credential' },
    });
    if (result.error || result.status !== 0) throw new Error('Fixture rewrite command failed');
    return JSON.parse(result.stdout);
};
const plan = invoke(['rewrite', 'plan', root, source, 'urn:example:fixture:canonical']);
if (plan.blockers.length || plan.findings.length !== 2)
    throw new Error('Fixture does not match the supported AST rewrite contract');
const projectFile = path.join(work, 'project.json'),
    planFile = path.join(work, 'plan.json');
await writeFile(projectFile, JSON.stringify(project));
await writeFile(planFile, JSON.stringify(plan));
const { createHash } = await import('node:crypto');
const { canonicalJson } = await import('../src/services/graphMigration/contracts.ts');
const planHash = createHash('sha256').update(canonicalJson(plan)).digest('hex');
project.approvals = [
    invoke([
        'authorize',
        projectFile,
        '--operator-confirmed',
        'rewrite',
        JSON.stringify([source, plan.sourceHash, planHash, output]),
        new Date(Date.now() + 300000).toISOString(),
        'env:GRAPH_TEST_APPROVAL_KEY',
    ]),
];
await writeFile(projectFile, JSON.stringify(project));
let existing;
try {
    existing = await readFile(path.join(root, output), 'utf8');
} catch (error) {
    if (error.code !== 'ENOENT') throw error;
}
if (existing === undefined) invoke(['rewrite', 'apply', projectFile, root, planFile, output]);
else if (existing !== plan.output) throw new Error('Existing generated fixture diverged; refusing to overwrite it');
await unlink(projectFile);
await unlink(planFile);
