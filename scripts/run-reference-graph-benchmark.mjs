/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { copyFile, mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const mode = process.argv[2];
assert.ok(['load', 'soak', 'smoke'].includes(mode), 'Select load, soak or smoke');
const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const resources = path.join(repository, 'resources', 'graph-migration');
const manifest = JSON.parse(await readFile(path.join(resources, 'worker-manifest.json'), 'utf8'));
const jarBytes = await readFile(path.join(resources, 'graph-testkit-0.1.0.jar'));
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
assert.equal(hash(jarBytes), manifest.testkitSha256, 'Testkit package integrity failed');
const parent = path.join(repository, 'java', 'graph-testkit', 'target');
await mkdir(parent, { recursive: true });
const snapshot = await mkdtemp(path.join(parent, `reference-${mode}-`));
const jar = path.join(snapshot, 'immutable-testkit.jar');
await copyFile(path.join(resources, 'graph-testkit-0.1.0.jar'), jar);
assert.equal(hash(await readFile(jar)), manifest.testkitSha256);
const configuration =
    mode === 'smoke'
        ? {
              schemaVersion: 1,
              mode: 'soak',
              profile: 'semantic',
              seed: 42,
              warmups: 1,
              repetitions: 3,
              soakDurationMillis: 100,
              batchRecords: 100,
              batchBytes: 1048576,
              queueCapacity: 100,
              minimumFreeDiskBytes: 1073741824,
          }
        : JSON.parse(
              await readFile(
                  path.join(repository, 'docs', 'graph-migration', 'benchmarks', `reference-${mode}.json`),
                  'utf8',
              ),
          );
const selected = path.join(snapshot, 'configuration.json'),
    output = path.join(snapshot, 'output');
await writeFile(selected, JSON.stringify(configuration));
const environment = {};
for (const name of ['PATH', 'Path', 'SystemRoot', 'JAVA_HOME', 'HOME', 'USERPROFILE', 'TEMP', 'TMP'])
    if (process.env[name]) environment[name] = process.env[name];
environment.GRAPH_TEST_APPROVAL_KEY = randomBytes(32).toString('hex');
const executionPath = path.join(snapshot, 'execution.json');
const started = {
    schemaVersion: 1,
    status: 'running',
    mode,
    testkitSha256: manifest.testkitSha256,
    configurationSha256: hash(await readFile(selected)),
    startedAt: new Date().toISOString(),
    immutableSnapshot: true,
    liveEndpointsUsed: false,
};
await writeFile(executionPath, JSON.stringify(started, null, 2));
let acknowledgement = Buffer.alloc(0);
let stage = 'private-worker',
    processExitConfirmed = false;
const child = spawn(
    'java',
    ['-Xmx1g', '-jar', jar, 'run', '--private-fixture-confirmed', selected, output, '--parent-owned'],
    { env: environment, shell: false, windowsHide: true, stdio: ['pipe', 'pipe', 'inherit'] },
);
const terminate = () => child.kill();
process.once('SIGINT', terminate);
process.once('SIGTERM', terminate);
try {
    await new Promise((resolve, reject) => {
        let failure = null,
            escalation = null;
        child.once('error', () => {
            failure = 'PRIVATE_WORKER_START_FAILED';
        });
        child.stdout.on('data', (bytes) => {
            if (failure) return;
            if (bytes.length > 8192 - acknowledgement.length) {
                failure = 'ACKNOWLEDGEMENT_BOUND';
                child.kill();
                escalation = setTimeout(() => child.kill('SIGKILL'), 5000);
                return;
            }
            acknowledgement = Buffer.concat([acknowledgement, bytes]);
        });
        child.once('close', (code) => {
            processExitConfirmed = true;
            if (escalation) clearTimeout(escalation);
            if (failure || code !== 0) reject(new Error(failure ?? 'PRIVATE_WORKER_EXIT_FAILED'));
            else resolve();
        });
    });
    process.removeListener('SIGINT', terminate);
    process.removeListener('SIGTERM', terminate);
    stage = 'acknowledgement';
    const reply = JSON.parse(acknowledgement.toString('utf8'));
    stage = 'report-integrity';
    const bytes = await readFile(path.join(output, 'report.json'));
    assert.equal(hash(bytes), reply.reportSha256);
    const report = JSON.parse(bytes);
    assert.equal(report.status, reply.status);
    if (mode !== 'smoke' && report.status === 'succeeded') {
        stage = 'acceptance-fields';
        assert.equal(mode === 'load' ? report.loadAcceptance : report.soakAcceptance, 'succeeded');
    }
    const execution = {
        schemaVersion: 1,
        status: report.status,
        mode,
        testkitSha256: manifest.testkitSha256,
        configurationSha256: hash(await readFile(selected)),
        reportSha256: hash(bytes),
        finishedAt: new Date().toISOString(),
        reportPath: path.relative(repository, path.join(output, 'report.json')).replaceAll('\\', '/'),
        immutableSnapshot: true,
        liveEndpointsUsed: false,
        loadAcceptance: report.loadAcceptance ?? 'blocked',
        soakAcceptance: report.soakAcceptance ?? 'blocked',
    };
    await writeFile(executionPath, JSON.stringify(execution, null, 2));
    console.log(JSON.stringify(execution));
    if (report.status !== 'succeeded') process.exitCode = 2;
} catch {
    await writeFile(
        executionPath,
        JSON.stringify(
            {
                ...started,
                status: 'blocked',
                code: 'PRIVATE_BENCHMARK_UNCONFIRMED',
                finishedAt: new Date().toISOString(),
                partialEvidencePreserved: true,
                failedStage: stage,
                processExitConfirmed,
                loadAcceptance: 'blocked',
                soakAcceptance: 'blocked',
            },
            null,
            2,
        ),
    );
    console.error(`Private benchmark did not produce confirmed acceptance; inspect ${executionPath}`);
    process.exitCode = 2;
} finally {
    process.removeListener('SIGINT', terminate);
    process.removeListener('SIGTERM', terminate);
}
