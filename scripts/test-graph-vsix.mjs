import { _electron as electron } from '@playwright/test';
import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cp, mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { graphProjectSchema, inputHash } from '../src/services/graphMigration/contracts.ts';

const root = await mkdtemp(path.join(tmpdir(), 'gremlin2rdf-vsix-'));
const helpers = [];
process.on('exit', () => {
    for (const child of helpers) child.kill();
});
async function ready(file) {
    for (let i = 0; i < 120; i++) {
        try {
            return JSON.parse(await readFile(file, 'utf8'));
        } catch (error) {
            if (error.code !== 'ENOENT') throw error;
        }
        await new Promise((resolve) => setTimeout(resolve, 250));
    }
    throw new Error(`Local fixture failed to start: ${path.basename(file)}`);
}
const sourceFixture = spawn('python', ['test\\graph-vsix\\source_fixture.py', root], {
    stdio: ['pipe', 'pipe', 'pipe'],
});
helpers.push(sourceFixture);
let sourceOutput = '';
sourceFixture.stdout.on('data', (chunk) => {
    sourceOutput += chunk;
});
sourceFixture.stderr.on('data', (chunk) => {
    sourceOutput += chunk;
});
const sourcePort = (await ready(path.join(root, 'source-ready.json'))).port;
const jdk = process.env.JAVA_HOME ?? 'C:\\Program Files\\Java\\latest\\jdk-21';
const testkit = path.resolve('resources', 'graph-migration', 'graph-testkit-0.1.0.jar');
execFileSync(path.join(jdk, 'bin', 'javac.exe'), ['-cp', testkit, '-d', root, 'test\\graph-vsix\\LocalFuseki.java'], {
    timeout: 30000,
});
const fuseki = spawn(
    path.join(jdk, 'bin', 'java.exe'),
    ['-Xmx256m', '-cp', [testkit, root].join(path.delimiter), 'LocalFuseki', path.join(root, 'target-ready.json')],
    { stdio: ['pipe', 'pipe', 'pipe'] },
);
helpers.push(fuseki);
const targetPort = (await ready(path.join(root, 'target-ready.json'))).port;
const dataset = `http://localhost:${targetPort}/fixture`;
const workspace = path.join(root, 'workspace');
const extensions = path.join(root, 'extensions');
const userData = path.join(root, 'user-data');
await mkdir(path.join(workspace, '.vscode'), { recursive: true });
await mkdir(path.join(userData, 'User'), { recursive: true });
await mkdir(extensions);
await writeFile(
    path.join(userData, 'User', 'settings.json'),
    JSON.stringify({
        'telemetry.telemetryLevel': 'off',
        'workbench.startupEditor': 'none',
        'workbench.secondarySideBar.visible': false,
        'chat.commandCenter.enabled': false,
        'cosmosDB.experimental.graphMigration.enabled': true,
        'cosmosDB.quickStart.enabled': false,
        'window.dialogStyle': 'custom',
        'security.workspace.trust.enabled': true,
    }),
);
await writeFile(
    path.join(workspace, '.vscode', 'settings.json'),
    JSON.stringify({
        'cosmosDB.experimental.graphMigration.enabled': true,
    }),
);
await writeFile(
    path.join(workspace, 'gremlin-partitions.json'),
    JSON.stringify({
        schemaVersion: 1,
        partitions: ['p0', 'p1'],
    }),
);
const project = graphProjectSchema.parse({
    schemaVersion: 1,
    migrationKind: 'cosmos-gremlin-to-jena',
    baselineRepositorySha: '22e3f2cccbfcc6622cacbf1873b9c620acba085e',
    runId: 'offline-vsix',
    protocolVersion: 1,
    workerVersion: '0.1.0',
    source: {
        scope: 'offline',
        host: 'localhost',
        port: sourcePort,
        database: 'fixture',
        container: 'graph',
        partitionKeyPath: '/pk',
        authenticationRef: 'env:GRAPH_E2E_KEY',
        extractionStrategy: 'verified-identity-manifest',
        identityManifest: 'identities.ndjson',
        identityManifestHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
        compatibilityEvidence: 'artifacts/fidelity.json',
        ruBudget: '999999',
        ruPerSecond: '1000',
        customCaRef: 'env:GRAPH_E2E_CA',
        syntheticTestContainer: false,
    },
    target: {
        datasetUrl: dataset,
        queryEndpoint: `${dataset}/query`,
        updateEndpoint: `${dataset}/update`,
        graphStoreEndpoint: `${dataset}/data`,
        authenticationRef: null,
        graphNamespace: 'urn:private-e2e:',
        publicationMode: 'manual',
        customCaRef: null,
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
        requestTimeoutMillis: 30000,
        maximumOperationMillis: 1800000,
        maxRetries: 3,
        queueCapacity: 100,
        writerConcurrency: 1,
        minimumFreeDiskBytes: 104857600,
    },
    consistency: 'immutable',
    phases: {},
    approvals: [],
    applicationHashes: {},
    artifacts: [],
});
const hash = inputHash(project);
project.phases.preflight = { status: 'stale', inputHash: hash, evidence: [], blockers: [] };
project.phases['source-enumerate'] = {
    status: 'blocked',
    inputHash: hash,
    evidence: ['artifacts/stopped.json'],
    blockers: ['source-enumeration (SOURCE_ENUMERATION_REQUEST_LIMIT): Reviewed ceiling reached.'],
};
const runRoot = path.join(workspace, '.cosmosdb-graph-migration');
await mkdir(path.join(runRoot, 'artifacts'), { recursive: true });
await writeFile(path.join(runRoot, 'project.json'), JSON.stringify(project));
await writeFile(path.join(runRoot, 'identities.ndjson'), '');
await writeFile(
    path.join(runRoot, 'artifacts', 'stopped.json'),
    JSON.stringify({ offlineFixture: true, status: 'blocked' }),
);
const sourceJar = path.resolve('resources', 'graph-migration', 'cosmos-gremlin-source-0.1.0.jar');
execFileSync(path.join(jdk, 'bin', 'javac.exe'), ['-cp', sourceJar, '-d', root, 'test\\graph-vsix\\DriverProbe.java'], {
    timeout: 30000,
});
console.log(
    execFileSync(
        path.join(jdk, 'bin', 'java.exe'),
        [
            '-cp',
            [sourceJar, root].join(path.delimiter),
            'io.github.gremlin2rdf.source.DriverProbe',
            path.join(runRoot, 'project.json'),
        ],
        { encoding: 'utf8', timeout: 30000, env: { ...process.env, GRAPH_E2E_CA: path.join(root, 'fixture-ca.pem') } },
    ),
);
const code = 'C:\\Program Files\\Microsoft VS Code\\Code.exe';
const launcher = await readFile('C:\\Program Files\\Microsoft VS Code\\bin\\code.cmd', 'utf8');
const relativeCli = launcher.match(/"%~dp0([^"]*cli\.js)"/)?.[1];
assert.ok(relativeCli, 'Unsupported VS Code launcher layout');
const cli = path.resolve('C:\\Program Files\\Microsoft VS Code\\bin', relativeCli);
const cliOptions = { encoding: 'utf8', env: { ...process.env, ELECTRON_RUN_AS_NODE: '1' }, timeout: 120000 };
const dependency = execFileSync(
    code,
    [cli, '--locate-extension', 'ms-azuretools.vscode-azureresourcegroups'],
    cliOptions,
).trim();
assert.ok(dependency, 'Required installed Azure extension missing');
await cp(dependency, path.join(extensions, path.basename(dependency)), { recursive: true });
const version = JSON.parse(await readFile('package.json', 'utf8')).version;
const vsix = path.resolve(process.env.GRAPH_E2E_VSIX ?? `vscode-cosmosdb-${version}.vsix`);
execFileSync(
    code,
    [cli, '--user-data-dir', userData, '--extensions-dir', extensions, '--install-extension', vsix],
    cliOptions,
);
const installed = execFileSync(
    code,
    [
        cli,
        '--user-data-dir',
        userData,
        '--extensions-dir',
        extensions,
        '--locate-extension',
        'ms-azuretools.vscode-cosmosdb',
    ],
    cliOptions,
).trim();
for (const file of ['cosmos-gremlin-source-0.1.0.jar', 'graph-testkit-0.1.0.jar']) {
    assert.deepEqual(
        await readFile(path.join(installed, 'resources', 'graph-migration', file)),
        await readFile(path.join('resources', 'graph-migration', file)),
        'Fixture must use the actual packaged worker bytes',
    );
}
const app = await electron.launch({
    executablePath: 'C:\\Program Files\\Microsoft VS Code\\Code.exe',
    args: ['--new-window', '--user-data-dir', userData, '--extensions-dir', extensions, '--skip-welcome', workspace],
    env: {
        ...process.env,
        DEBUGTELEMETRY: 'v',
        GRAPH_E2E_KEY: 'private-generated-fixture-token',
        GRAPH_E2E_CA: path.join(root, 'fixture-ca.pem'),
    },
    timeout: 60000,
});
const errors = [];
let page;
try {
    page = await app.firstWindow();
    page.setDefaultTimeout(30000);
    page.on('pageerror', (error) => errors.push(error.message));
    await page.locator('.monaco-workbench').waitFor();
    await page.waitForTimeout(5000);
    const trust = page.getByRole('button', { name: /Yes, I trust/i });
    if (await trust.count()) await trust.click();
    if (await page.getByText('Manage', { exact: true }).count()) {
        await page.getByText('Manage', { exact: true }).click();
        await page.getByRole('button', { name: 'Trust', exact: true }).click();
        await page.waitForTimeout(1000);
        const confirmTrust = page.getByRole('button', { name: /Trust Folder & Enable Features/ });
        if (await confirmTrust.count()) await confirmTrust.click();
        await page.waitForTimeout(1000);
    }
    await page.keyboard.press('Control+Shift+P');
    await page.locator('.quick-input-widget input').fill('>Open Gremlin to RDF Migration');
    await page.keyboard.press('Enter');
    let webview;
    for (let i = 0; i < 60; i++) {
        for (const frame of page.frames()) {
            if (await frame.getByRole('heading', { name: 'Gremlin to RDF migration', exact: true }).count()) {
                webview = frame;
                break;
            }
        }
        if (webview) break;
        await page.waitForTimeout(500);
    }
    assert.ok(webview, 'Graph webview did not open');
    await page.screenshot({ path: path.join(root, 'opened.png') });
    const review = webview.getByRole('button', { name: 'Review run settings', exact: true });
    await review.waitFor({ timeout: 30000 });
    await review.click();
    const requests = webview.getByRole('spinbutton', { name: 'Maximum reserved request attempts' });
    await requests.waitFor({ timeout: 15000 });
    await requests.fill('16');
    await review.click();
    assert.equal(await requests.inputValue(), '16', 'Repeated Review must preserve edited settings');
    assert.equal(await requests.evaluate((input) => input === input.ownerDocument.activeElement), true);
    await webview.getByRole('button', { name: 'Find partition candidates' }).click();
    await webview.getByText('2 candidates found.', { exact: false }).waitFor({ timeout: 10000 });
    await webview.getByRole('button', { name: 'Check read-only connections', exact: true }).click();
    await settled('preflight');
    await webview.getByRole('checkbox', { name: /I confirm source writes are paused/ }).check();
    const start = webview.getByRole('button', { name: 'Start bounded read-only preparation' });
    assert.equal(await start.isEnabled(), true);
    await start.click();
    await webview.getByText(/Waiting for confirmation in the VS Code dialog outside this panel/).waitFor();
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    assert.equal(await requests.inputValue(), '16', 'Dismissed confirmation must preserve settings');
    await start.click();
    await page.getByRole('button', { name: 'Start bounded read-only preparation', exact: true }).click();
    async function state() {
        return JSON.parse(await readFile(path.join(runRoot, 'project.json'), 'utf8'));
    }
    async function changedRun(previousRunId) {
        for (let i = 0; i < 60; i++) {
            if ((await state()).runId !== previousRunId) return;
            await page.waitForTimeout(500);
        }
        throw new Error('Approved preparation did not create its reviewed run');
    }
    async function settled(operation, expectedStatus = 'succeeded') {
        for (let i = 0; i < 240; i++) {
            const current = await state();
            const phase = current.phases[operation];
            if (phase && phase.status !== 'running' && phase.status !== 'stale') {
                if (phase.status !== expectedStatus)
                    throw new Error(`${operation}: ${phase.status} ${phase.blockers.join(' ')}`);
                return current;
            }
            await page.waitForTimeout(500);
        }
        throw new Error(`${operation}: fixture operation did not settle`);
    }
    await changedRun(project.runId);
    const stopped = await settled('source-enumerate', 'blocked');
    assert.ok(
        stopped.phases['source-enumerate'].blockers.some((value) => value.includes('SOURCE_ENUMERATION_REQUEST_LIMIT')),
    );
    await writeFile(path.join(root, 'request-limit-project.json'), JSON.stringify(stopped, null, 2));
    const stoppedEvidence = stopped.phases['source-enumerate'].evidence[0];
    const stoppedBytes = await readFile(path.join(runRoot, stoppedEvidence));
    await review.click();
    await requests.fill('200');
    const recheck = webview.getByRole('button', { name: 'Check read-only connections', exact: true });
    await recheck.click();
    await settled('preflight');
    await webview.getByRole('button', { name: 'Find partition candidates' }).click();
    await webview.getByText('2 candidates found.', { exact: false }).waitFor();
    await webview.getByRole('checkbox', { name: /I confirm source writes are paused/ }).check();
    await start.click();
    await page.getByRole('button', { name: 'Start bounded read-only preparation', exact: true }).click();
    await changedRun(stopped.runId);
    for (let i = 0; i < 240; i++) {
        const current = await state();
        if (current.runId !== project.runId && current.source.identityManifest !== 'identities.ndjson') break;
        if (current.runId !== project.runId && current.phases['source-enumerate']?.status === 'blocked')
            throw new Error(`enumerate: ${current.phases['source-enumerate'].blockers.join(' ')}`);
        await page.waitForTimeout(500);
    }
    const enumerated = await state();
    assert.notEqual(enumerated.source.identityManifest, 'identities.ndjson', 'Inventory was not adopted');
    assert.equal(
        enumerated.phases['source-enumerate'].status,
        'succeeded',
        'Adoption must not look like a stale enumeration',
    );
    const receiptPath = enumerated.phases['source-enumerate'].evidence[0];
    const receipt = JSON.parse(await readFile(path.join(runRoot, receiptPath), 'utf8'));
    const sourceBeforeReload = JSON.parse(await readFile(path.join(root, 'source-summary.json'), 'utf8'));
    const legacy = structuredClone(enumerated);
    legacy.phases['source-enumerate'] = {
        ...legacy.phases['source-enumerate'],
        status: 'stale',
        inputHash: receipt.producerInputHash,
        evidence: legacy.phases['source-enumerate'].evidence.slice(1),
    };
    await writeFile(path.join(runRoot, 'project.json'), JSON.stringify(legacy, null, 2));
    await webview.getByRole('button', { name: 'Reload persisted state', exact: true }).click();
    for (let i = 0; i < 60; i++) {
        if ((await state()).phases['source-enumerate'].status === 'succeeded') break;
        await page.waitForTimeout(250);
    }
    assert.equal(
        (await state()).phases['source-enumerate'].inputHash,
        inputHash(enumerated),
        'Legacy persisted adoption must recover locally',
    );
    assert.equal(
        JSON.parse(await readFile(path.join(root, 'source-summary.json'), 'utf8')).requests,
        sourceBeforeReload.requests,
        'Adoption recovery must not submit source requests',
    );
    await webview
        .getByText(
            'Your verified inventory is saved. Recheck connections for the new run; do not repeat enumeration.',
            { exact: true },
        )
        .waitFor();
    await webview.getByRole('button', { name: 'Go to next unfinished step', exact: true }).click();
    const nextConnection = webview.getByRole('button', { name: 'Run for Check read-only connections', exact: true });
    assert.equal(await nextConnection.isEnabled(), true, 'Adoption must leave the required connection Run enabled');
    assert.equal(await nextConnection.evaluate((button) => button === button.ownerDocument.activeElement), true);
    await page.screenshot({ path: path.join(root, 'adopted-continue.png') });
    assert.deepEqual(
        await readFile(path.join(runRoot, stoppedEvidence)),
        stoppedBytes,
        'Retry must preserve stopped evidence',
    );
    const inventory = await readFile(path.join(runRoot, enumerated.source.identityManifest), 'utf8');
    const identities = inventory
        .trim()
        .split('\n')
        .map((row) => JSON.parse(row));
    assert.equal(identities.filter((row) => row.kind === 'vertex').length, 3);
    assert.equal(identities.filter((row) => row.kind === 'edge').length, 3);
    await webview
        .getByRole('button', { name: 'Evidence 1 for Enumerate read-only source identities', exact: true })
        .click();
    await page.getByRole('tab', { name: /adopted-inventory/ }).waitFor();
    await page.getByRole('tab', { name: /Gremlin to RDF/i }).click();
    const phases = [
        ['preflight', 'Check read-only connections'],
        ['assess', 'Assess application'],
        ['source-compatibility', 'Probe read-only source compatibility'],
        ['mapping', 'Validate mapping'],
        ['extract', 'Extract read-only source'],
        ['load', 'Load approved target graphs'],
        ['validate', 'Validate structural fidelity'],
        ['functional', 'Compare golden queries'],
        ['report', 'Evaluate readiness'],
    ];
    for (const [operation, label] of phases) {
        const current = await state();
        if (
            current.phases[operation]?.status === 'succeeded' &&
            current.phases[operation].inputHash === inputHash(current)
        )
            continue;
        if (operation === 'load') {
            await webview.getByRole('button', { name: `Authorize for ${label}`, exact: true }).click();
            await page.getByRole('button', { name: 'Authorize', exact: true }).click();
        }
        const button = webview.getByRole('button', { name: `Run for ${label}`, exact: true });
        await button.click();
        await settled(operation, operation === 'report' ? 'blocked' : 'succeeded');
    }
    const current = await state();
    const report = JSON.parse(await readFile(path.join(runRoot, current.phases.report.evidence[0]), 'utf8'));
    for (const name of ['structural', 'queries', 'sourceCompatibility'])
        assert.equal(report.result.gates[name].status, 'succeeded', name);
    for (const name of ['application', 'applicationInputs', 'crud', 'load', 'soak', 'dependencies'])
        assert.equal(report.result.gates[name].status, 'blocked', `Unexecuted ${name} proof must not pass`);
    assert.equal(
        current.approvals.some((approval) => approval.operation === 'publish'),
        false,
    );
    for (const artifact of current.artifacts) {
        const bytes = await readFile(path.join(runRoot, artifact.relativePath));
        assert.equal(createHash('sha256').update(bytes).digest('hex'), artifact.sha256, artifact.relativePath);
    }
    const source = JSON.parse(await readFile(path.join(root, 'source-summary.json'), 'utf8'));
    assert.ok(source.compressedConnections > 0, 'Negotiated compressed transport must be exercised');
    assert.equal(source.rejectedTemplates, 0);
    await page.screenshot({ path: path.join(root, 'completed-core-migration.png') });
    await writeFile(path.join(root, 'passed-project.json'), JSON.stringify(current, null, 2));
    await writeFile(
        path.join(root, 'verification.json'),
        JSON.stringify(
            {
                schemaVersion: 1,
                vsix: path.basename(vsix),
                vsixSha256: createHash('sha256')
                    .update(await readFile(vsix))
                    .digest('hex'),
                coreMigration: 'succeeded',
                productionReadiness: 'blocked',
                source,
                phases: current.phases,
                errors,
            },
            null,
            2,
        ),
    );
    console.log(JSON.stringify({ root, status: 'core-migration-succeeded-release-readiness-blocked', errors }));
} catch (error) {
    if (page) {
        await page.screenshot({ path: path.join(root, 'failure.png') });
        await writeFile(path.join(root, 'failure-dom.txt'), await page.locator('body').innerText());
        for (const [i, frame] of page.frames().entries())
            await writeFile(
                path.join(root, `frame-${i}.txt`),
                await frame
                    .locator('body')
                    .innerText()
                    .catch(() => 'unavailable'),
            );
    }
    console.error(JSON.stringify({ root, status: 'failed', message: error.message, errors }));
    await writeFile(path.join(root, 'source-fixture.log'), sourceOutput);
    throw error;
} finally {
    await app.close();
    for (const child of helpers) child.kill();
}
