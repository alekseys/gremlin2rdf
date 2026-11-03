/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import assert from 'node:assert/strict';
import { spawnSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, writeFileSync, unlinkSync, rmdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

const executable = path.resolve('resources', 'age-migration', 'win-x64', 'AgeWorker.exe');
const temporary = mkdtempSync(path.join(tmpdir(), 'gremlin2age-smoke-'));
const project = {
    schemaVersion: 1,
    migrationKind: 'cosmos-gremlin-to-age',
    runId: 'offline-smoke',
    source: {
        host: 'example.gremlin.cosmos.azure.com',
        database: 'unicode-é<&',
        container: 'graph',
        partitionKeyPath: '/pk',
        authenticationRef: 'env:AGE_SOURCE_KEY',
        scope: 'fixture-é<&',
    },
    target: null,
    consistency: 'immutable',
    limits: { batchRecords: 2, batchBytes: 1048576, timeoutSeconds: 1, maximumOperationSeconds: 5 },
    approvals: [],
};
let checks = 0;
function run(args, env = {}) {
    const result = spawnSync(executable, args, {
        encoding: 'utf8',
        timeout: 10000,
        windowsHide: true,
        env: { ...process.env, ...env },
    });
    assert.equal(result.error, undefined);
    return result;
}
try {
    const smoke = run(['smoke']);
    assert.equal(smoke.status, 0);
    const report = JSON.parse(smoke.stdout);
    checks += report.checks;
    assert.equal(report.postgresAge, 'not-executed');
    for (const query of ['g.V().both()', 'g.V().', "g.V().values('score').hasLabel('x')"]) {
        const rejected = run(['translate', query]);
        assert.equal(rejected.status, 2);
        assert.equal(JSON.parse(rejected.stdout).status, 'blocked');
        checks++;
    }
    const { approvals, ...hashed } = project;
    void approvals;
    const inputHash = createHash('sha256').update(JSON.stringify(hashed)).digest('hex');
    const frame = {
        protocolVersion: 1,
        sequence: 1,
        type: 'start',
        runId: project.runId,
        operationId: 'smoke-frame',
        inputHash,
        operation: 'fixture',
        project,
        input: '',
        output: 'fixture.jsonl',
    };
    const message = await new Promise((resolve, reject) => {
        const child = spawn(executable, ['worker'], { cwd: temporary, windowsHide: true });
        let output = '';
        const timer = setTimeout(() => {
            child.kill();
            reject(new Error('Offline worker deadline exceeded'));
        }, 10000);
        child.stdout.on('data', (data) => {
            output += data.toString();
            if (output.includes('\n')) child.stdin.end();
        });
        child.stderr.resume();
        child.on('error', reject);
        child.on('close', (code) => {
            clearTimeout(timer);
            try {
                assert.equal(code, 0);
                resolve(JSON.parse(output));
            } catch (error) {
                reject(error);
            }
        });
        child.stdin.write(JSON.stringify(frame) + '\n');
    });
    assert.equal(message.type, 'result');
    assert.equal(message.sequence, 1);
    assert.equal(message.runId, project.runId);
    const records = readFileSync(path.join(temporary, 'fixture.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
    assert.equal(records.length, 5);
    assert.equal(records[0].properties.length, 2);
    assert.equal(records[0].properties[0].value.lexical, '1.00');
    checks += 4;
    const invalid = spawnSync(executable, ['worker'], {
        cwd: temporary,
        input: JSON.stringify({ ...frame, inputHash: '0'.repeat(64) }) + '\n',
        encoding: 'utf8',
        timeout: 10000,
        windowsHide: true,
    });
    assert.equal(invalid.status, 2);
    assert.equal(JSON.parse(invalid.stdout).code, 'PROTOCOL_INPUT');
    checks++;
    writeFileSync(path.join(temporary, 'project.json'), JSON.stringify({ ...project, source: null }));
    const blocked = run(['load', path.join(temporary, 'project.json'), 'fixture.jsonl', 'checkpoint.json']);
    assert.equal(blocked.status, 2);
    assert.equal(JSON.parse(blocked.stdout).code, 'APPROVAL_REQUIRED');
    checks++;
    const app = readFileSync(path.resolve('dotnet', 'AgeWorker', 'Fixtures', 'Repository.cs.txt'), 'utf8');
    writeFileSync(path.join(temporary, 'app.cs'), app);
    const approvalEnv = { AGE_APPROVAL_KEY: createHash('sha256').update('offline-test-key').digest('hex') };
    const approval = run(
        ['authorize-rewrite', path.join(temporary, 'project.json'), 'app.cs', 'rewritten.cs'],
        approvalEnv,
    );
    assert.equal(approval.status, 0);
    const rewriteProject = { ...project, source: null, approvals: [JSON.parse(approval.stdout)] };
    writeFileSync(path.join(temporary, 'project.json'), JSON.stringify(rewriteProject));
    const rewrite = run(['rewrite-apply', path.join(temporary, 'project.json'), 'app.cs', 'rewritten.cs'], approvalEnv);
    assert.equal(rewrite.status, 0);
    assert.equal(JSON.parse(rewrite.stdout).status, 'review-required');
    assert.match(readFileSync(path.join(temporary, 'rewritten.cs'), 'utf8'), /ageAccess.QueryAsync/);
    if (process.env.AGE_DOTNET_SDK) {
        for (const file of ['app.cs', 'rewritten.cs']) {
            const compile = spawnSync(
                process.env.AGE_DOTNET_SDK,
                [
                    'build',
                    path.resolve('dotnet', 'FixtureSmoke', 'FixtureSmoke.csproj'),
                    '-c',
                    'Release',
                    '--nologo',
                    '-v',
                    'quiet',
                    `-p:FixtureFile=${path.join(temporary, file)}`,
                ],
                {
                    cwd: path.resolve('dotnet'),
                    encoding: 'utf8',
                    timeout: 30000,
                    windowsHide: true,
                    env: {
                        ...process.env,
                        DOTNET_CLI_TELEMETRY_OPTOUT: '1',
                        DOTNET_GENERATE_ASPNET_CERTIFICATE: 'false',
                    },
                },
            );
            assert.equal(compile.status, 0, compile.stdout + compile.stderr);
            checks++;
        }
    }
    const overwritten = run(
        ['rewrite-apply', path.join(temporary, 'project.json'), 'app.cs', 'rewritten.cs'],
        approvalEnv,
    );
    assert.equal(overwritten.status, 2);
    checks++;
    writeFileSync(path.join(temporary, 'app.cs'), app + '\n// changed source\n');
    const stale = run(['rewrite-apply', path.join(temporary, 'project.json'), 'app.cs', 'other.cs'], approvalEnv);
    assert.equal(stale.status, 2);
    assert.equal(JSON.parse(stale.stdout).code, 'APPROVAL_REQUIRED');
    checks += 3;
    console.log(
        JSON.stringify({
            status: 'succeeded',
            checks,
            selfContained: true,
            protocol: 'verified-offline',
            liveSource: 'unverified',
            postgresAge: 'not-executed',
        }),
    );
} finally {
    for (const name of ['fixture.jsonl', 'project.json', 'app.cs', 'rewritten.cs']) {
        try {
            unlinkSync(path.join(temporary, name));
        } catch (error) {
            if (error.code !== 'ENOENT') throw error;
        }
    }
    rmdirSync(temporary);
}
