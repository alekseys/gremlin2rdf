/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
    fixtureCatalogSchema,
    graphProjectSchema,
    inputHash,
    javaRewritePlanSchema,
    queryFixtureSchema,
    mutationFixtureSchema,
} from '../src/services/graphMigration/contracts.ts';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const parent = path.join(repository, 'java', 'migration-engine', 'target');
await mkdir(parent, { recursive: true });
const root = await mkdtemp(path.join(parent, 'worker-smoke-'));
const project = graphProjectSchema.parse({
    schemaVersion: 1,
    migrationKind: 'cosmos-gremlin-to-jena',
    baselineRepositorySha: '22e3f2cccbfcc6622cacbf1873b9c620acba085e',
    runId: 'worker-smoke',
    protocolVersion: 1,
    workerVersion: '0.1.0',
    source: null,
    target: null,
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
await writeFile(path.join(root, 'project.json'), JSON.stringify(project));
async function run(operation, arguments_) {
    return await new Promise((resolve, reject) => {
        const child = spawn(
            'java',
            ['-Xmx256m', '-jar', path.join(repository, 'resources', 'graph-migration', 'worker.jar'), 'worker'],
            { cwd: root, shell: false, windowsHide: true },
        );
        let buffer = '',
            sequence = -1,
            outcome;
        const timeout = setTimeout(() => {
            child.kill();
            reject(new Error('Worker smoke deadline exceeded'));
        }, 30000);
        child.stderr.on('data', () => {});
        child.on('error', reject);
        child.stdout.on('data', (chunk) => {
            try {
                buffer += chunk.toString('utf8');
                let end;
                while ((end = buffer.indexOf('\n')) >= 0) {
                    const frame = buffer.slice(0, end);
                    buffer = buffer.slice(end + 1);
                    assert.ok(Buffer.byteLength(frame) <= 65536);
                    const message = JSON.parse(frame);
                    assert.equal(message.protocolVersion, 1);
                    assert.ok(message.sequence > sequence);
                    sequence = message.sequence;
                    if (message.type === 'hello')
                        child.stdin.write(
                            JSON.stringify({
                                protocolVersion: 1,
                                type: 'start',
                                runId: project.runId,
                                operationId: 'smoke',
                                sequence: 0,
                                timestamp: new Date().toISOString(),
                                phase: 'control',
                                payload: {
                                    operation,
                                    arguments: arguments_,
                                    runId: project.runId,
                                    inputHash: inputHash(project),
                                },
                            }) + '\n',
                        );
                    else if (message.type === 'result') {
                        outcome = message.payload;
                        child.stdin.end();
                    } else if (message.type === 'error')
                        throw new Error(`${operation}: ${message.payload?.code ?? 'structured-worker-error'}`);
                }
            } catch (error) {
                child.kill();
                reject(error);
            }
        });
        child.on('close', (code) => {
            clearTimeout(timeout);
            if (code === 0 && outcome && !buffer) resolve(outcome);
            else reject(new Error('Worker did not terminate cleanly with bounded evidence'));
        });
    });
}
const result = await run('synthetic', ['generate', 'semantic', '42', path.join(root, 'records.ndjson')]);
assert.equal(result.status, 'succeeded');
assert.equal(result.inputHash, inputHash(project));
const report = await readFile(path.join(root, result.artifact.relativePath));
assert.equal(createHash('sha256').update(report).digest('hex'), result.artifact.sha256);
assert.equal(JSON.parse(report).result.records, 1000);
assert.equal(result.artifacts.length, 2);
async function verifyArtifacts(outcome) {
    for (const artifact of [outcome.artifact, ...outcome.artifacts]) {
        assert.equal(artifact.ownerRunId, project.runId);
        assert.equal(
            createHash('sha256')
                .update(await readFile(path.join(root, artifact.relativePath)))
                .digest('hex'),
            artifact.sha256,
        );
    }
}
await verifyArtifacts(result);
const derived = await run('synthetic', [
    'derive',
    path.join(root, 'project.json'),
    'records.ndjson',
    'fixtures',
    'urn:fixture:canonical',
]);
assert.equal(derived.status, 'succeeded');
await verifyArtifacts(derived);
const catalog = fixtureCatalogSchema.parse(
    JSON.parse(await readFile(path.join(root, 'fixtures', 'catalog.json'), 'utf8')),
);
assert.equal(catalog.artifacts.length, 28);
for (const artifact of catalog.artifacts) {
    const value = JSON.parse(await readFile(path.join(root, 'fixtures', artifact.path), 'utf8'));
    if (artifact.kind === 'query') queryFixtureSchema.parse(value);
    if (artifact.kind === 'mutation') value.forEach((entry) => mutationFixtureSchema.parse(entry));
}
await writeFile(
    path.join(root, 'Fixture.java'),
    'import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;\nclass Fixture { long count(GraphTraversalSource g) { return g.V().count().next(); } }\n',
);
const rewritten = await run('rewrite', ['plan', root, 'Fixture.java', 'urn:fixture:canonical']);
assert.equal(rewritten.status, 'succeeded');
await verifyArtifacts(rewritten);
const plan = javaRewritePlanSchema.parse(
    JSON.parse(await readFile(path.join(root, rewritten.artifact.relativePath), 'utf8')).result,
);
assert.equal(plan.findings.length, 1);
assert.equal(plan.blockers.length, 0);
await writeFile(
    path.join(root, 'blocked-benchmark.json'),
    JSON.stringify({
        schemaVersion: 1,
        mode: 'load',
        profile: 'semantic',
        seed: 42,
        warmups: 1,
        repetitions: 3,
        soakDurationMillis: 0,
        batchRecords: 100,
        batchBytes: 1048576,
        queueCapacity: 100,
        minimumFreeDiskBytes: Number.MAX_SAFE_INTEGER,
    }),
);
const blockedBenchmark = await run('test', [
    'benchmark',
    path.join(root, 'project.json'),
    path.join(repository, 'resources', 'graph-migration', 'graph-testkit-0.1.0.jar'),
    'blocked-benchmark.json',
    'blocked-private-benchmark',
    '--private-fixture-confirmed',
]);
assert.equal(blockedBenchmark.status, 'blocked');
await verifyArtifacts(blockedBenchmark);
assert.equal(blockedBenchmark.artifacts.length, 1);
assert.equal(
    JSON.parse(await readFile(path.join(root, blockedBenchmark.artifact.relativePath), 'utf8')).result.result.code,
    'INSUFFICIENT_BENCHMARK_DISK',
);
const evidence = path.join(repository, 'docs', 'graph-migration', 'evidence');
await mkdir(evidence, { recursive: true });
await writeFile(
    path.join(evidence, 'worker-smoke.json'),
    JSON.stringify(
        {
            schemaVersion: 1,
            status: 'succeeded',
            command: 'node scripts/smoke-graph-worker.mjs',
            inputHash: inputHash(project),
            checks: [
                'Java/TypeScript canonical input hash parity',
                'packaged worker stdio lifecycle',
                'bounded monotonic frames',
                'large result persisted instead of sent over stdio',
                'producer-checked concrete artifact checksums and run ownership',
                '1000 deterministic records',
                'real Java catalog/query/mutation JSON accepted by strict editor schemas',
                'actual Java rewrite plan accepted by strict editor schema',
                'owned isolated private-testkit launch and checksummed resource-blocked acknowledgement (not a load/soak pass)',
            ],
            liveCosmosCompatibility: 'blocked-not-supplied',
            timestamp: new Date().toISOString(),
        },
        null,
        2,
    ) + '\n',
);
