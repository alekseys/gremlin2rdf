import { _electron as electron } from '@playwright/test';
import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cp, mkdir, mkdtemp, readFile, writeFile, unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { graphProjectSchema, inputHash } from '../src/services/graphMigration/contracts.ts';
const documents = process.env.GRAPH_E2E_SOURCE_MODE === 'cosmos-documents';

const root = await mkdtemp(path.join(tmpdir(), 'gremlin2rdf-vsix-'));
console.log(JSON.stringify({ root, sourceMode: documents ? 'cosmos-documents' : 'gremlin', fixtureStage: 'created' }));
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
const sourceFixture = spawn(
    'python',
    [documents ? 'test\\graph-vsix\\document_fixture.py' : 'test\\graph-vsix\\source_fixture.py', root],
    {
        stdio: ['pipe', 'pipe', 'pipe'],
    },
);
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
        ...(documents
            ? {
                  sourceMode: 'cosmos-documents',
                  documentEndpoint: `https://localhost:${sourcePort}/`,
                  documentProfile: 'property-bags-v1',
              }
            : {}),
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
if (documents) {
    project.source.sourceMode = 'gremlin';
    project.source.identityManifest = 'artifacts/unavailable-old-inventory.ndjson';
    project.consistency = 'best-effort';
}
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
if (!documents) {
    execFileSync(
        path.join(jdk, 'bin', 'javac.exe'),
        ['-cp', sourceJar, '-d', root, 'test\\graph-vsix\\DriverProbe.java'],
        {
            timeout: 30000,
        },
    );
    console.log(
        execFileSync(
            path.join(jdk, 'bin', 'java.exe'),
            [
                '-cp',
                [sourceJar, root].join(path.delimiter),
                'io.github.gremlin2rdf.source.DriverProbe',
                path.join(runRoot, 'project.json'),
            ],
            {
                encoding: 'utf8',
                timeout: 30000,
                env: { ...process.env, GRAPH_E2E_CA: path.join(root, 'fixture-ca.pem') },
            },
        ),
    );
}
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
        GRAPH_E2E_KEY: documents
            ? Buffer.from('private-generated-fixture-token').toString('base64')
            : 'private-generated-fixture-token',
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
    const trust = page.getByRole('button', { name: /Yes, I trust/i });
    const manage = page.getByText('Manage', { exact: true });
    for (let attempt = 0; attempt < 60 && !(await trust.isVisible()) && !(await manage.isVisible()); attempt++)
        await page.waitForTimeout(500);
    if (await trust.isVisible()) await trust.click();
    else {
        await manage.waitFor();
        await page.getByText('Manage', { exact: true }).click();
        await page.getByRole('button', { name: 'Trust', exact: true }).click();
        const confirmTrust = page.getByRole('button', { name: /Trust Folder & Enable Features/ });
        const trustedFolder = page.getByText('You trust this folder', { exact: true });
        for (
            let attempt = 0;
            attempt < 60 && !(await confirmTrust.isVisible()) && !(await trustedFolder.isVisible());
            attempt++
        )
            await page.waitForTimeout(500);
        if (await confirmTrust.isVisible()) await confirmTrust.click();
        else await trustedFolder.waitFor();
    }
    await page
        .getByText('Restricted Mode is intended for safe code browsing.', { exact: false })
        .waitFor({ state: 'hidden' });
    console.log(JSON.stringify({ fixtureStage: 'isolated-workspace-trusted-through-ui' }));
    await page.keyboard.press('Control+Shift+P');
    await page.locator('.quick-input-widget input').fill('>Open Gremlin to RDF Migration');
    await page
        .locator('.quick-input-list .monaco-list-row')
        .filter({ hasText: 'Open Gremlin to RDF Migration' })
        .first()
        .waitFor();
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
    if (documents) {
        await webview
            .locator('summary')
            .filter({ hasText: /^Configuration and mapping$/ })
            .click();
        await webview.getByRole('combobox', { name: 'Source read mode' }).selectOption('cosmos-documents');
        await webview.getByRole('combobox', { name: 'JSON property layout' }).selectOption('auto-v1');
        const documentEndpoint = webview.getByRole('textbox', { name: 'Cosmos .NET SDK document endpoint' });
        await documentEndpoint.fill(`localhost:${sourcePort}`);
        await documentEndpoint.blur();
        assert.equal(await documentEndpoint.inputValue(), `https://localhost:${sourcePort}/`);
        assert.equal(
            await documentEndpoint.evaluate((input) => {
                const hint = input.ownerDocument.getElementById(input.getAttribute('aria-describedby'));
                return hint && hint.getBoundingClientRect().bottom <= input.getBoundingClientRect().top;
            }),
            true,
            'Accepted endpoint examples must appear above the control',
        );
        assert.equal(
            await webview.getByRole('button', { name: 'Go to next unfinished step', exact: true }).isEnabled(),
            false,
            'Unsaved source edits must not continue the prior run',
        );
        const restart = webview.getByRole('button', { name: 'Save settings and restart migration', exact: true });
        assert.equal(await restart.textContent(), 'Save settings and restart migration');
        assert.equal(await webview.getByRole('combobox', { name: 'Source consistency' }).inputValue(), 'best-effort');
        const fixturePending = path.join(runRoot, 'project.pending');
        await writeFile(fixturePending, 'Owned offline fixture pending-state sentinel');
        await restart.click();
        const inlineFailure = webview.locator('div[role="alert"]').filter({ hasText: 'PROJECT_PENDING_EXISTS' });
        await inlineFailure.waitFor({ state: 'visible' });
        assert.equal(await inlineFailure.evaluate((element) => element === element.ownerDocument.activeElement), true);
        assert.equal((await state()).runId, project.runId, 'Rejected restart must not replace the old run');
        assert.equal(await readFile(fixturePending, 'utf8'), 'Owned offline fixture pending-state sentinel');
        assert.equal(
            await webview.getByRole('combobox', { name: 'Source read mode' }).inputValue(),
            'cosmos-documents',
        );
        console.log(JSON.stringify({ fixtureStage: 'rejected-restart-explained-inline-old-run-preserved' }));
        await unlink(fixturePending);
        await webview.getByRole('combobox', { name: 'Source consistency' }).selectOption('immutable');
        await restart.click();
        await changedRun(project.runId);
        await settled('preflight');
        const restarted = await state();
        assert.equal(restarted.source.sourceMode, 'cosmos-documents');
        assert.equal(restarted.source.documentProfile, 'auto-v1');
        assert.equal(restarted.source.documentEndpoint, `https://localhost:${sourcePort}/`);
        assert.notEqual(restarted.source.identityManifest, project.source.identityManifest);
        assert.equal(restarted.phases['source-enumerate'], undefined);
        await webview.getByText('Restart saved. New run:', { exact: false }).waitFor({ state: 'visible' });
        assert.equal(await readFile(path.join(runRoot, 'identities.ndjson'), 'utf8'), '');
        assert.equal(
            JSON.parse(await readFile(path.join(runRoot, 'artifacts', 'stopped.json'), 'utf8')).offlineFixture,
            true,
        );
        console.log(JSON.stringify({ fixtureStage: 'legacy-restarted-with-document-draft' }));
        const activeStep = webview.locator('details[data-active="true"]');
        await activeStep
            .locator('summary')
            .filter({ hasText: /^Step 2:/ })
            .waitFor({ state: 'visible' });
        assert.equal(
            await activeStep.evaluate((step) => {
                const style = getComputedStyle(step);
                const box = step.getBoundingClientRect();
                return (
                    step.open &&
                    parseFloat(style.borderLeftWidth) >= 5 &&
                    box.top >= 0 &&
                    box.top < step.ownerDocument.defaultView.innerHeight &&
                    step === step.ownerDocument.activeElement
                );
            }),
            true,
            'The next step must be highlighted, focused and scrolled into view',
        );
        assert.equal(await webview.locator('details[data-completed="true"][open]').count(), 0);
        assert.equal(await webview.getByRole('heading', { name: 'Next unfinished step', exact: true }).count(), 0);
        await page.screenshot({ path: path.join(root, 'endpoint-normalized-next-step.png') });
        await webview
            .locator('summary')
            .filter({ hasText: /^Configuration and mapping$/ })
            .click();
    }
    const initialInventory = (await state()).source.identityManifest;
    const review = webview.getByRole('button', {
        name: documents ? 'Run for Enumerate read-only source identities' : 'Review run settings',
        exact: true,
    });
    await review.waitFor({ timeout: 30000 });
    await review.click();
    const requests = webview.getByRole('spinbutton', { name: 'Maximum reserved request attempts' });
    await requests.waitFor({ timeout: 15000 });
    await requests.fill(documents ? '4' : '16');
    await review.click();
    assert.equal(await requests.inputValue(), documents ? '4' : '16', 'Repeated Review must preserve edited settings');
    assert.equal(await requests.evaluate((input) => input === input.ownerDocument.activeElement), true);
    await webview
        .getByRole('button', { name: documents ? 'Review document-feed preparation' : 'Find partition candidates' })
        .click();
    if (!documents) await webview.getByText('2 candidates found.', { exact: false }).waitFor({ timeout: 10000 });
    if (!documents) {
        await webview.getByRole('button', { name: 'Check read-only connections', exact: true }).click();
        await settled('preflight');
    }
    await webview.getByRole('checkbox', { name: /I confirm source writes are paused/ }).check();
    const start = webview.getByRole('button', { name: 'Start bounded read-only preparation' });
    assert.equal(await start.isEnabled(), true);
    await start.click();
    await webview.getByText(/Waiting for confirmation in the VS Code dialog outside this panel/).waitFor();
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    assert.equal(await requests.inputValue(), documents ? '4' : '16', 'Dismissed confirmation must preserve settings');
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
    async function settled(operation, expectedStatus = 'succeeded', previousEvidence) {
        for (let i = 0; i < 240; i++) {
            const current = await state();
            const phase = current.phases[operation];
            if (previousEvidence && phase?.evidence[0] === previousEvidence) {
                await page.waitForTimeout(500);
                continue;
            }
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
    console.log(JSON.stringify({ fixtureStage: 'reviewed-budget-stop-observed' }));
    assert.ok(
        stopped.phases['source-enumerate'].blockers.some(
            (value) =>
                value.includes('SOURCE_ENUMERATION_REQUEST_LIMIT') ||
                (documents && value.includes('SOURCE_SPEND_UNKNOWN')),
        ),
    );
    await writeFile(path.join(root, 'request-limit-project.json'), JSON.stringify(stopped, null, 2));
    const stoppedEvidence = stopped.phases['source-enumerate'].evidence[0];
    const stoppedBytes = await readFile(path.join(runRoot, stoppedEvidence));
    await webview.getByRole('button', { name: 'Review run settings', exact: true }).click();
    await requests.fill('200');
    const recheck = webview.getByRole('button', { name: 'Check read-only connections', exact: true });
    await recheck.click();
    await settled('preflight');
    console.log(JSON.stringify({ fixtureStage: 'retry-preflight-observed' }));
    await webview
        .getByRole('button', { name: documents ? 'Review document-feed preparation' : 'Find partition candidates' })
        .click();
    if (!documents) await webview.getByText('2 candidates found.', { exact: false }).waitFor();
    await webview.getByRole('checkbox', { name: /I confirm source writes are paused/ }).check();
    await start.click();
    console.log(JSON.stringify({ fixtureStage: 'retry-start-clicked' }));
    await page.getByRole('button', { name: 'Start bounded read-only preparation', exact: true }).click();
    await changedRun(stopped.runId);
    for (let i = 0; i < 240; i++) {
        const current = await state();
        if (current.runId !== project.runId && current.source.identityManifest !== initialInventory) break;
        if (current.runId !== project.runId && current.phases['source-enumerate']?.status === 'blocked')
            throw new Error(`enumerate: ${current.phases['source-enumerate'].blockers.join(' ')}`);
        await page.waitForTimeout(500);
    }
    const enumerated = await state();
    console.log(JSON.stringify({ fixtureStage: 'enumeration-adoption-observed' }));
    assert.notEqual(enumerated.source.identityManifest, initialInventory, 'Inventory was not adopted');
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
        .locator('details[data-completed="true"] > summary')
        .filter({ hasText: /Enumerate read-only source identities/ })
        .click();
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
        if (operation === 'source-compatibility' && !documents) {
            const beforeFault = await state();
            await writeFile(path.join(root, 'source-fault.json'), JSON.stringify({ missingSystemKey: '_attachments' }));
            await webview.getByRole('button', { name: `Run for ${label}`, exact: true }).click();
            await settled(operation, 'blocked');
            const failed = await state();
            assert.equal(failed.phases[operation].status, 'blocked');
            assert.ok(
                failed.phases[operation].blockers.some(
                    (value) =>
                        value.includes('SOURCE_SYSTEM_METADATA_UNOBSERVABLE') &&
                        value.includes('source-record-system-attachments'),
                ),
            );
            assert.equal(failed.runId, beforeFault.runId);
            assert.equal(failed.source.identityManifestHash, beforeFault.source.identityManifestHash);
            const faultEvidence = failed.phases[operation].evidence[0];
            const savedFault = await readFile(path.join(runRoot, faultEvidence));
            await writeFile(path.join(root, 'source-record-failure.json'), savedFault);
            await writeFile(path.join(root, 'source-fault.json'), JSON.stringify({}));
            await page.screenshot({ path: path.join(root, 'source-record-blocked.png') });
        }
        if (operation === 'source-compatibility' && documents) {
            const strictBefore = await state();
            await webview.getByRole('button', { name: `Run for ${label}`, exact: true }).click();
            const strict = await settled(operation, 'blocked');
            assert.ok(
                strict.phases[operation].blockers.some((value) => value.includes('DOCUMENT_FIDELITY_UNOBSERVABLE')),
            );
            assert.equal(strict.source.identityManifestHash, strictBefore.source.identityManifestHash);
            const strictEvidence = JSON.parse(
                await readFile(path.join(runRoot, strict.phases[operation].evidence[0]), 'utf8'),
            );
            assert.equal(strictEvidence.result.sourceCountsReconciled, true);
            assert.equal(strictEvidence.result.observedVertices, 3);
            assert.equal(strictEvidence.result.observedEdges, 3);
            assert.ok(strictEvidence.result.unobservable.includes('property-occurrence-id-unobservable'));
            assert.equal(strictEvidence.status, 'blocked');
            await webview.getByRole('button', { name: 'Review source-detail policy', exact: true }).click();
            assert.equal(
                await webview
                    .getByRole('combobox', { name: 'When source details cannot be verified' })
                    .evaluate((select) => select === select.ownerDocument.activeElement),
                true,
            );
            assert.equal((await state()).mapping.fidelity, 'strict');
            assert.equal((await state()).approvals.length, 0);
            await webview
                .getByRole('combobox', { name: 'When source details cannot be verified' })
                .selectOption('reduced');
            await webview.getByRole('button', { name: 'Save connection settings', exact: true }).click();
            await page.waitForTimeout(1000);
            await webview.getByRole('button', { name: 'Run for Check read-only connections', exact: true }).click();
            await settled('preflight', 'succeeded', strict.phases.preflight?.evidence[0]);
            await webview.getByRole('button', { name: 'Run for Assess application', exact: true }).click();
            await settled('assess', 'succeeded', strict.phases.assess?.evidence[0]);
            await webview.getByRole('button', { name: 'Authorize reduced document fidelity', exact: true }).click();
            await page.getByRole('button', { name: 'Authorize', exact: true }).click();
        }
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
        await settled(
            operation,
            operation === 'report' ? 'blocked' : 'succeeded',
            current.phases[operation]?.evidence[0],
        );
    }
    const current = await state();
    const report = JSON.parse(await readFile(path.join(runRoot, current.phases.report.evidence[0]), 'utf8'));
    for (const name of ['structural', 'queries', documents ? 'documentFidelity' : 'sourceCompatibility'])
        assert.equal(report.result.gates[name].status, 'succeeded', name);
    if (documents) assert.equal(report.result.gates.sourceCompatibility.status, 'blocked');
    if (documents) {
        assert.equal(report.result.gates.strictEquivalence.status, 'blocked');
        const validation = JSON.parse(await readFile(path.join(runRoot, current.phases.validate.evidence[0]), 'utf8'));
        assert.equal(validation.result.validationScope, 'observed-document-canonical-only');
        assert.equal(validation.result.strictInputFidelity, false);
    }
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
    if (!documents) assert.ok(source.compressedConnections > 0, 'Negotiated compressed transport must be exercised');
    else assert.equal(source.mutations, 0, 'SDK source must remain read-only');
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
    console.error(JSON.stringify({ root, status: 'failed', message: error.message, errors }));
    await writeFile(
        path.join(root, 'failure.json'),
        JSON.stringify({ status: 'failed', message: error.message, errors }),
    );
    if (page) {
        try {
            await page.screenshot({ path: path.join(root, 'failure.png'), timeout: 5000 });
        } catch (captureError) {
            console.error(
                JSON.stringify({ fixtureStage: 'diagnostic-screenshot-failed', message: captureError.message }),
            );
        }
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
    await writeFile(path.join(root, 'source-fixture.log'), sourceOutput);
    throw error;
} finally {
    await app.close();
    for (const child of helpers) child.kill();
}
