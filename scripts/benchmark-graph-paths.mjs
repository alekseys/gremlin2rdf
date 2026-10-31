/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash, createHmac, randomBytes, randomUUID } from 'node:crypto';
import { copyFile, mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { fileHash, readBounded } from '../src/services/graphMigration/boundedIO.ts';
import { canonicalJson, graphProjectSchema, inputHash } from '../src/services/graphMigration/contracts.ts';

assert.equal(
    process.argv[2],
    '--private-fixture-confirmed',
    'Explicit private-only execution confirmation is required',
);
const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const resources = path.join(repository, 'resources', 'graph-migration');
const packaged = JSON.parse((await readBounded(path.join(resources, 'worker-manifest.json'), 65536)).toString('utf8'));
const parent = path.join(repository, 'java', 'graph-testkit', 'target');
await mkdir(parent, { recursive: true });
const root = await mkdtemp(path.join(parent, 'paths-'));
for (const [name, expected] of [
    ['worker.jar', packaged.workerSha256],
    ['graph-testkit-0.1.0.jar', packaged.testkitSha256],
]) {
    assert.equal(await fileHash(path.join(resources, name)), expected);
    await copyFile(path.join(resources, name), path.join(root, name));
    assert.equal(await fileHash(path.join(root, name)), expected);
}
const key = randomBytes(32).toString('hex'),
    environment = {};
for (const name of ['PATH', 'Path', 'SystemRoot', 'JAVA_HOME', 'HOME', 'USERPROFILE', 'TEMP', 'TMP'])
    if (process.env[name]) environment[name] = process.env[name];
environment.GRAPH_TEST_APPROVAL_KEY = key;
async function cli(args) {
    return await new Promise((resolve, reject) => {
        const child = spawn('java', ['-Xmx1g', '-jar', path.join(root, 'worker.jar'), ...args], {
            env: environment,
            cwd: root,
            shell: false,
            windowsHide: true,
        });
        let output = Buffer.alloc(0);
        const deadline = setTimeout(() => {
            child.kill();
            reject(new Error('Private path operation deadline; partial artifacts preserved'));
        }, 300000);
        child.stderr.on('data', () => {});
        child.stdout.on('data', (bytes) => {
            output = Buffer.concat([output, bytes]);
            if (output.length > 16777216) {
                child.kill();
                reject(new Error('Private CLI reply bound'));
            }
        });
        child.once('error', (error) => {
            clearTimeout(deadline);
            reject(error);
        });
        child.once('close', (code) => {
            clearTimeout(deadline);
            try {
                const result = JSON.parse(output.toString('utf8'));
                if (code !== 0 || (result.status && result.status !== 'succeeded'))
                    throw new Error(`Private path ${args[0]} failed with code ${result.code ?? code}`);
                resolve(result);
            } catch (error) {
                reject(error);
            }
        });
    });
}
const records = path.join(root, 'records.ndjson');
await cli(['synthetic', 'generate', 'semantic', '42', records]);
const sourceHash = await fileHash(records);
const mapping = {
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
};
function project(run, url) {
    return graphProjectSchema.parse({
        schemaVersion: 1,
        migrationKind: 'cosmos-gremlin-to-jena',
        baselineRepositorySha: '22e3f2cccbfcc6622cacbf1873b9c620acba085e',
        runId: run,
        protocolVersion: 1,
        workerVersion: '0.1.0',
        mapping,
        source: null,
        target: {
            datasetUrl: url,
            queryEndpoint: `${url}/query`,
            updateEndpoint: `${url}/update`,
            graphStoreEndpoint: `${url}/data`,
            authenticationRef: null,
            graphNamespace: 'urn:gremlin2rdf:private-paths:',
            publicationMode: 'manual',
        },
        limits: {
            batchRecords: 100,
            batchBytes: 1048576,
            maxRecordBytes: 262144,
            requestTimeoutMillis: 30000,
            maxRetries: 0,
            queueCapacity: 100,
            writerConcurrency: 1,
            minimumFreeDiskBytes: 1073741824,
        },
        consistency: 'immutable',
        phases: {},
        approvals: [],
        applicationHashes: {},
        artifacts: [],
    });
}
function grant(input, operation, graphs) {
    const resources = [...graphs].sort(),
        expiresAt = new Date(Date.now() + 3600000).toISOString(),
        keyRef = 'env:GRAPH_TEST_APPROVAL_KEY';
    return {
        schemaVersion: 1,
        runId: input.runId,
        operation,
        resources,
        inputHash: inputHash(input),
        expiresAt,
        keyRef,
        signature: createHmac('sha256', key)
            .update(canonicalJson([1, input.runId, operation, resources, inputHash(input), expiresAt, keyRef]))
            .digest('hex'),
    };
}
function authorize(input, operation, resources) {
    const approval = input.approvals.find((entry) => entry.operation === operation);
    assert.ok(approval && approval.inputHash === inputHash(input) && Date.parse(approval.expiresAt) > Date.now());
    assert.deepEqual(approval.resources, [...resources].sort());
    assert.equal(approval.signature, grantSignature(approval));
}
function grantSignature(approval) {
    return createHmac('sha256', key)
        .update(
            canonicalJson([
                1,
                approval.runId,
                approval.operation,
                approval.resources,
                approval.inputHash,
                approval.expiresAt,
                approval.keyRef,
            ]),
        )
        .digest('hex');
}
const privateTarget = path.join(root, 'private-target');
await mkdir(privateTarget);
await writeFile(
    path.join(privateTarget, 'private-target.json'),
    JSON.stringify({ schemaVersion: 1, kind: 'private-testkit', newDirectoryOnly: true }),
);
const server = spawn(
    'java',
    ['-Xmx2g', '-jar', path.join(root, 'graph-testkit-0.1.0.jar'), 'serve-private', privateTarget],
    { env: environment, shell: false, windowsHide: true },
);
server.stderr.on('data', () => {});
try {
    const port = await new Promise((resolve, reject) => {
        let bytes = Buffer.alloc(0);
        const deadline = setTimeout(() => reject(new Error('Private Fuseki startup deadline')), 30000);
        server.once('error', (error) => {
            clearTimeout(deadline);
            reject(error);
        });
        server.stdout.on('data', (chunk) => {
            bytes = Buffer.concat([bytes, chunk]);
            if (bytes.length > 4096) {
                clearTimeout(deadline);
                reject(new Error('Private startup frame bound'));
                return;
            }
            if (bytes.includes(10)) {
                clearTimeout(deadline);
                try {
                    const value = JSON.parse(bytes.toString('utf8'));
                    assert.ok(Number.isInteger(value.port) && value.port > 0 && value.port < 65536);
                    resolve(value.port);
                } catch (error) {
                    reject(error);
                }
            }
        });
    });
    const url = `http://localhost:${port}/fixture`;
    const measurements = [];
    for (let repetition = -1; repetition < 3; repetition++) {
        const input = project(`paths-${randomUUID()}`, url),
            file = path.join(root, `project-${input.runId}.json`);
        await writeFile(file, JSON.stringify(input));
        let started = performance.now();
        const rdf = await cli(['mapping', 'export', file, records, path.join(root, `rdf-${input.runId}`)]);
        const mappingMillis = performance.now() - started;
        const graphs = ['canonical', 'derived'].map((role) => `${input.target.graphNamespace}${input.runId}:${role}`);
        const frames = [];
        for (const [index, role] of ['canonical', 'derived'].entries()) {
            const bytes = await readBounded(path.join(root, `rdf-${input.runId}`, `${role}.nt`), 16777216);
            assert.equal(createHash('sha256').update(bytes).digest('hex'), rdf[role].sha256);
            for (const line of bytes.toString('utf8').split('\n').filter(Boolean))
                frames.push({ graph: graphs[index], line });
        }
        input.approvals.push(grant(input, 'private-rdf-load', [...graphs, sourceHash]));
        const latencies = [];
        let requestBytes = 0;
        started = performance.now();
        for (let offset = 0; offset < frames.length; offset += 1000) {
            authorize(input, 'private-rdf-load', [...graphs, sourceHash]);
            const selected = frames.slice(offset, offset + 1000);
            const body = `INSERT DATA { ${graphs
                .map(
                    (graph) =>
                        `GRAPH <${graph}> { ${selected
                            .filter((frame) => frame.graph === graph)
                            .map((frame) => frame.line)
                            .join('\n')} }`,
                )
                .join('\n')} }`;
            assert.ok(Buffer.byteLength(body) <= input.limits.batchBytes);
            const requestStarted = performance.now();
            const response = await fetch(`${url}/update`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/sparql-update' },
                body,
                signal: AbortSignal.timeout(input.limits.requestTimeoutMillis),
            });
            await response.arrayBuffer();
            assert.ok(response.ok, 'Private paired-graph RDF insert denied or failed');
            latencies.push(performance.now() - requestStarted);
            requestBytes += Buffer.byteLength(body);
        }
        const loadMillis = performance.now() - started;
        const catalogDirectory = `catalog-${input.runId}`;
        await cli(['synthetic', 'derive', file, 'records.ndjson', catalogDirectory, graphs[0]]);
        const catalogHash = await fileHash(path.join(root, catalogDirectory, 'catalog.json'));
        for (const operation of ['application-write', 'application-delete', 'fixture-test'])
            input.approvals.push(
                grant(input, operation, operation === 'fixture-test' ? [...graphs, catalogHash] : graphs),
            );
        await writeFile(file, JSON.stringify(input));
        started = performance.now();
        const fixture = await cli(['test', 'fixtures', file, catalogDirectory]);
        assert.equal(fixture.status, 'succeeded');
        assert.equal(fixture.mutations.length, 15);
        assert.ok(fixture.queries.every((entry) => entry.mismatchCount === 0));
        const fixtureMillis = performance.now() - started;
        const sorted = [...latencies].sort((a, b) => a - b),
            percentile = (fraction) => sorted[Math.ceil(sorted.length * fraction) - 1];
        measurements.push({
            warmup: repetition === -1,
            mappingSerializationMillis: mappingMillis,
            mappingRecordsPerSecond: (rdf.records * 1000) / mappingMillis,
            canonicalTripleFrames: rdf.canonical.tripleFrames,
            derivedTripleFrames: rdf.derived.tripleFrames,
            loadingOnlyMillis: loadMillis,
            rdfFramesPerSecond: (frames.length * 1000) / loadMillis,
            requestBodyBytes: requestBytes,
            updateRequestP50Millis: percentile(0.5),
            updateRequestP95Millis: percentile(0.95),
            updateRequestP99Millis: percentile(0.99),
            queryCrudEquivalenceMillis: fixtureMillis,
            queryCases: fixture.queries.length,
            mutationSteps: fixture.mutations,
            queryCrudTimingScope:
                'Java CLI startup, oracle, HTTP reads/mutations, postconditions and structural validation included',
        });
    }
    assert.equal(await fileHash(records), sourceHash);
    const report = {
        schemaVersion: 1,
        status: 'succeeded',
        profile: 'semantic-not-streaming-load',
        warmups: 1,
        measuredRepetitions: 3,
        sourceSha256: sourceHash,
        workerSha256: packaged.workerSha256,
        testkitSha256: packaged.testkitSha256,
        measurements,
        extractionOnly: 'blocked-no-opted-in-live-source',
        extractionToLoad: 'blocked-no-opted-in-live-source',
        mixedRecoverySoak: 'run-reference-graph-benchmark.mjs supplies separate measured acceptance',
        limits: { singleWriter: true, boundedSemanticRdfBytes: 16777216, requestBytes: 1048576 },
        heapScope:
            'Stage Java processes use -Xmx1g; portable path runner does not claim their after-GC retained-heap measurement',
    };
    await writeFile(path.join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(
        JSON.stringify({
            status: report.status,
            reportPath: path.relative(repository, path.join(root, 'report.json')).replaceAll('\\', '/'),
        }),
    );
} finally {
    server.stdin.end();
    await new Promise((resolve, reject) => {
        let confirmation;
        const deadline = setTimeout(() => {
            if (!server.kill()) {
                reject(new Error('Private target termination failed'));
                return;
            }
            confirmation = setTimeout(() => reject(new Error('Private target termination was not confirmed')), 2000);
        }, 10000);
        server.once('close', () => {
            clearTimeout(deadline);
            clearTimeout(confirmation);
            resolve();
        });
        if (server.exitCode !== null || server.signalCode !== null) {
            clearTimeout(deadline);
            resolve();
        }
    });
}
