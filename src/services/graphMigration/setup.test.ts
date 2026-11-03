/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { afterEach, beforeEach, expect, it } from 'vitest';
import { canonicalJson, graphProjectSchema, inputHash, type GraphProject } from './contracts';
import { hasCurrentSourceCompatibility as hasCompatibilityForHash, hasFidelityInventory } from './diagnostics';
import { GraphProjectService } from './GraphProjectService';
import { adoptEnumeration, freshPreparation, discoverPreparation, prepareSourceSchema } from './preparation';
import { applySetup, discoverSetup } from './setup';

let root: string;
function hasCurrentSourceCompatibility(value: GraphProject): boolean {
    return hasCompatibilityForHash(value, inputHash(value));
}
beforeEach(async () => {
    root = await mkdtemp(path.join(tmpdir(), 'graph-setup-'));
});
afterEach(async () => {
    await rm(root, { recursive: true, force: true });
});
function project() {
    return graphProjectSchema.parse({
        schemaVersion: 1,
        migrationKind: 'cosmos-gremlin-to-jena',
        baselineRepositorySha: '0'.repeat(40),
        runId: 'setup-test',
        protocolVersion: 1,
        workerVersion: '0.1.0',
        source: null,
        target: null,
        mapping: {
            schemaVersion: 1,
            baseIri: 'urn:entity:',
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
}
it('switches modes only explicitly and archives the prior run without touching its complete inventory or journals', async () => {
    const run = await new GraphProjectService(root).root();
    const form = {
        host: 'account.gremlin.cosmos.azure.com',
        database: 'db',
        container: 'graph',
        partitionKeyPath: '/pk',
        datasetUrl: 'http://localhost:3030/graph',
        consistency: 'immutable' as const,
    };
    const legacy = await applySetup(project(), form, run);
    const inventory =
        Array.from({ length: 3917 }, (_, index) =>
            JSON.stringify({
                schemaVersion: 1,
                kind: 'vertex',
                partition: { type: 'string', lexical: 'p' },
                id: { type: 'string', lexical: String(index) },
                ownerId: null,
                edgeScope: null,
            }),
        ).join('\n') + '\n';
    await writeFile(path.join(run, legacy.source!.identityManifest), inventory);
    legacy.source!.identityManifestHash = createHash('sha256').update(inventory).digest('hex');
    await writeFile(path.join(run, `source-requests-${legacy.runId}.json`), 'preserved-accounting');
    const before = JSON.stringify(legacy);
    const changed = await applySetup(
        legacy,
        {
            ...form,
            sourceMode: 'cosmos-documents',
            documentEndpoint: 'https://account.documents.azure.com:443/',
            documentProfile: 'property-bags-v1',
        },
        run,
    );
    expect(changed.runId).not.toBe(legacy.runId);
    expect(changed.source?.identityManifest).toBe(legacy.source?.identityManifest);
    expect(changed.source?.identityManifestHash).toBe(legacy.source?.identityManifestHash);
    expect(changed.source?.sourceMode).toBe('cosmos-documents');
    expect(changed.source?.host).toBe(legacy.source?.host);
    expect(changed.approvals).toEqual([]);
    expect(changed.phases['source-compatibility']?.status).not.toBe('succeeded');
    expect(inputHash(changed)).not.toBe(inputHash(legacy));
    expect(JSON.stringify(legacy)).toBe(before);
    expect(await readFile(path.join(run, legacy.source!.identityManifest), 'utf8')).toBe(inventory);
    expect(await readFile(path.join(run, `source-requests-${legacy.runId}.json`), 'utf8')).toBe('preserved-accounting');
    const archive = (await readdir(path.join(run, 'artifacts'))).find((file) =>
        file.startsWith(`source-mode-run-${legacy.runId}-`),
    );
    expect(JSON.parse(await readFile(path.join(run, 'artifacts', archive!), 'utf8'))).toEqual(legacy);
    const prepared = await freshPreparation(changed, run, {
        partitions: [],
        maximumRequests: 20,
        maximumDurationSeconds: 120,
        ruBudget: '1000',
        confirmStableSource: true,
    });
    expect(JSON.parse(await readFile(path.join(run, prepared.plan), 'utf8'))).toMatchObject({
        partitions: [],
    });
    await expect(
        freshPreparation(legacy, run, {
            partitions: [],
            maximumRequests: 20,
            maximumDurationSeconds: 120,
            ruBudget: '1000',
            confirmStableSource: true,
        }),
    ).rejects.toThrow('Explicit candidate partitions');
});
it('requires an explicit credential-free document endpoint and profile instead of deriving or falling back to Gremlin', async () => {
    const run = await new GraphProjectService(root).root();
    const form = {
        host: '',
        database: 'db',
        container: 'graph',
        partitionKeyPath: '/pk',
        datasetUrl: '',
        consistency: 'immutable' as const,
        sourceMode: 'cosmos-documents' as const,
        documentProfile: 'flat-v1' as const,
    };
    await expect(applySetup(project(), form, run)).rejects.toThrow('explicit Cosmos DB');
    for (const endpoint of [
        'https://account.documents.azure.com/?key=private',
        'https://user:private@account.documents.azure.com/',
        'https://account.documents.azure.com/dbs/db',
        'https://account.documents.azure.com/#private',
    ])
        await expect(applySetup(project(), { ...form, documentEndpoint: endpoint }, run)).rejects.toThrow();
    const configured = await applySetup(
        project(),
        { ...form, documentEndpoint: 'https://account.documents.azure.com/' },
        run,
    );
    expect(configured.source?.sourceMode).toBe('cosmos-documents');
    expect(configured.source?.host).toBe('account.documents.azure.com');
    expect(configured.source?.documentProfile).toBe('flat-v1');
});
it('restarts an unchanged unfinished run with a fresh inventory while preserving its original state and journals', async () => {
    const run = await new GraphProjectService(root).root();
    const form = {
        host: '',
        database: 'db',
        container: 'graph',
        partitionKeyPath: '/pk',
        datasetUrl: 'http://localhost:3030/graph',
        consistency: 'immutable' as const,
        sourceMode: 'cosmos-documents' as const,
        documentEndpoint: 'https://account.documents.azure.com/',
        documentProfile: 'auto-v1' as const,
    };
    const prior = await applySetup(project(), form, run);
    await writeFile(path.join(run, prior.source!.identityManifest), 'owned old inventory\n');
    prior.source!.identityManifestHash = createHash('sha256').update('owned old inventory\n').digest('hex');
    prior.phases.load = {
        status: 'blocked',
        blockers: ['prior failure'],
        evidence: [],
        inputHash: inputHash(prior),
    };
    await writeFile(path.join(run, `source-requests-${prior.runId}.json`), 'unknown spend preserved');
    const snapshot = structuredClone(prior);
    const next = await applySetup(prior, { ...form, restart: true }, run);
    expect(next.runId).not.toBe(prior.runId);
    expect(next.phases).toEqual({});
    expect(next.approvals).toEqual([]);
    expect(hasFidelityInventory(next)).toBe(false);
    expect(next.source?.identityManifest).not.toBe(prior.source?.identityManifest);
    expect(await readFile(path.join(run, next.source!.identityManifest), 'utf8')).toBe('');
    expect(await readFile(path.join(run, prior.source!.identityManifest), 'utf8')).toBe('owned old inventory\n');
    expect(await readFile(path.join(run, `source-requests-${prior.runId}.json`), 'utf8')).toBe(
        'unknown spend preserved',
    );
    expect(prior).toEqual(snapshot);
    const archive = (await readdir(path.join(run, 'artifacts'))).find((file) =>
        file.startsWith(`restarted-run-${prior.runId}-`),
    );
    expect(JSON.parse(await readFile(path.join(run, 'artifacts', archive!), 'utf8'))).toEqual(snapshot);
});
it('infers recognized nonsecret settings and keeps conflicting endpoints unresolved', async () => {
    await writeFile(
        path.join(root, '.env'),
        'GREMLIN_HOST=one.gremlin.cosmos.azure.com\nGREMLIN_PASSWORD=private-key\nFUSEKI_DATASET_URL=http://localhost:3030/graph',
    );
    await writeFile(
        path.join(root, 'appsettings.json'),
        JSON.stringify({
            CosmosDb: { Gremlin: { Database: 'db', Container: 'graph', PartitionKeyPath: '/tenant' } },
            NoSql: { Database: 'unrelated' },
        }),
    );
    const detected = await discoverSetup(root, { GREMLIN_HOST: 'two.gremlin.cosmos.azure.com' });
    expect(detected.values.host).toBe('');
    expect(detected.notices.join(' ')).toContain('Conflicting');
    expect(detected.values.database).toBe('db');
    expect(detected.values.container).toBe('graph');
    expect(detected.values.partitionKeyPath).toBe('/tenant');
    expect(detected.values.datasetUrl).toBe('http://localhost:3030/graph');
    expect(detected.values.consistency).toBe('');
    expect(JSON.stringify(detected)).not.toContain('private-key');
});
it('does not offer Resume over an unacknowledged enumeration checkpoint and preserves both files and journal', async () => {
    const run = await new GraphProjectService(root).root();
    const configured = await applySetup(
        project(),
        {
            host: 'localhost',
            database: 'fixture',
            container: 'graph',
            partitionKeyPath: '/pk',
            datasetUrl: 'http://localhost:3030/graph',
            consistency: 'immutable',
        },
        run,
    );
    const prepared = await freshPreparation(configured, run, {
        partitions: ['fixture'],
        maximumRequests: 20,
        maximumDurationSeconds: 120,
        ruBudget: '1000',
        confirmStableSource: true,
    });
    await mkdir(path.join(run, prepared.output));
    const checkpoint = JSON.stringify({
        runId: prepared.project.runId,
        inputHash: inputHash(prepared.project),
        requests: 3,
        elapsedMillis: 20,
    });
    const output = path.join(run, prepared.output);
    await writeFile(path.join(output, 'checkpoint.json'), checkpoint);
    const journal = `source-requests-${prepared.project.runId}.json`;
    await writeFile(path.join(run, journal), 'preserved-journal');
    expect((await discoverPreparation(root, prepared.project)).resume).toEqual({
        plan: prepared.plan,
        output: prepared.output,
    });
    await writeFile(
        path.join(output, 'checkpoint.json'),
        JSON.stringify({ ...JSON.parse(checkpoint), inFlight: true }),
    );
    expect((await discoverPreparation(root, prepared.project)).resume).toBeUndefined();
    await writeFile(path.join(output, 'checkpoint.json'), checkpoint);
    prepared.project.phases['source-enumerate'] = {
        status: 'blocked',
        inputHash: inputHash(prepared.project),
        evidence: [],
        blockers: ['source-enumeration (SOURCE_ENUMERATION_REQUEST_LIMIT): Reviewed request ceiling reached.'],
    };
    const exhausted = await discoverPreparation(root, prepared.project);
    expect(exhausted.resume).toBeUndefined();
    expect(exhausted.notices.join(' ')).toContain('reserved request attempts');
    delete prepared.project.phases['source-enumerate'];
    await writeFile(path.join(output, 'checkpoint.json.pending'), 'preserved-pending');
    const candidates = await discoverPreparation(root, prepared.project);
    expect(candidates.resume).toBeUndefined();
    expect(candidates.notices.join(' ')).toContain('unacknowledged enumeration checkpoint');
    expect(await readFile(path.join(output, 'checkpoint.json'), 'utf8')).toBe(checkpoint);
    expect(await readFile(path.join(output, 'checkpoint.json.pending'), 'utf8')).toBe('preserved-pending');
    expect(await readFile(path.join(run, journal), 'utf8')).toBe('preserved-journal');
});
it.each([
    'SOURCE_RU_BUDGET_EXHAUSTED',
    'SOURCE_RU_UNOBSERVABLE',
    'SOURCE_RU_ACCOUNTING_REVIEW',
    'OPERATION_DEADLINE_PARTIAL_STATE_PRESERVED',
])('keeps RU allowance/checkpoints intact and requires reviewed fresh preparation for %s', async (code) => {
    const run = await new GraphProjectService(root).root();
    const configured = await applySetup(
        project(),
        {
            host: 'localhost',
            database: 'fixture',
            container: 'graph',
            partitionKeyPath: '/pk',
            datasetUrl: 'http://localhost:3030/graph',
            consistency: 'immutable',
        },
        run,
    );
    const legacy = graphProjectSchema.parse({ ...configured, source: { ...configured.source!, ruBudget: '1000' } });
    expect(legacy.source).not.toHaveProperty('ruPerSecond');
    const journal = `source-requests-${legacy.runId}.json`;
    await writeFile(path.join(run, journal), 'preserved-journal');
    const output = `artifacts/source-enumeration-${legacy.runId}`;
    await mkdir(path.join(run, output), { recursive: true });
    const checkpoint = { runId: legacy.runId, inputHash: inputHash(legacy), requests: 3, elapsedMillis: 20 };
    await writeFile(path.join(run, output, 'checkpoint.json'), JSON.stringify(checkpoint));
    const plan = `artifacts/enumeration-plan-${legacy.runId}.json`;
    await writeFile(path.join(run, plan), JSON.stringify({ maximumRequests: 20, maximumDurationMillis: 10000 }));
    const spent = graphProjectSchema.parse({
        ...legacy,
        phases: {
            'source-enumerate': {
                status: 'blocked',
                inputHash: inputHash(legacy),
                evidence: [],
                blockers: [`source-enumeration-vertex-page (${code}): Review accounting.`],
            },
        },
    });
    const candidates = await discoverPreparation(root, spent);
    expect(candidates.resume).toBeUndefined();
    expect(candidates.notices.join(' ')).toContain(
        code.startsWith('SOURCE_RU_')
            ? 'Waiting or changing RU/s cannot repair it'
            : 'This run reached its reviewed operation/enumeration duration',
    );
    const prepared = await freshPreparation(spent, run, {
        partitions: ['fixture'],
        maximumRequests: 20,
        maximumDurationSeconds: 1800,
        ruBudget: '120000',
        ruPerSecond: '1000',
        confirmStableSource: true,
    });
    expect(prepared.project.runId).not.toBe(legacy.runId);
    expect(prepared.project.source).toMatchObject({ ruBudget: '120000', ruPerSecond: '1000' });
    expect(prepared.project.limits.maximumOperationMillis).toBe(1800000);
    expect(inputHash(prepared.project)).not.toBe(inputHash(spent));
    expect(JSON.parse(await readFile(path.join(run, prepared.plan), 'utf8')).maximumDurationMillis).toBe(1800000);
    expect(prepared.project.phases['source-enumerate'].status).toBe('stale');
    expect(prepared.project.approvals).toEqual([]);
    expect(spent.source?.ruBudget).toBe('1000');
    expect(await readFile(path.join(run, journal), 'utf8')).toBe('preserved-journal');
    expect(JSON.parse(await readFile(path.join(run, output, 'checkpoint.json'), 'utf8'))).toEqual(checkpoint);
    const saved = await applySetup(
        prepared.project,
        {
            host: 'localhost',
            database: 'fixture',
            container: 'graph',
            partitionKeyPath: '/pk',
            datasetUrl: 'http://localhost:3030/graph',
            consistency: 'immutable',
        },
        run,
    );
    expect(inputHash(saved)).toBe(inputHash(prepared.project));
    expect(saved.source?.ruPerSecond).toBe('1000');
    expect(inputHash({ ...legacy, source: { ...legacy.source!, ruPerSecond: '400' } })).not.toBe(inputHash(legacy));
});
it('validates explicit pacing separately without treating a total allowance as RU per second', () => {
    const valid = {
        partitions: ['fixture'],
        maximumRequests: 20,
        maximumDurationSeconds: 120,
        ruBudget: '120000',
        confirmStableSource: true,
    };
    expect(prepareSourceSchema.parse(valid)).not.toHaveProperty('ruPerSecond');
    expect(prepareSourceSchema.parse({ ...valid, ruPerSecond: '400' })).toMatchObject({
        ruBudget: '120000',
        ruPerSecond: '400',
    });
    for (const ruPerSecond of ['0', '-1', 'NaN', '1e3', '1000000001', '', '0.5']) {
        expect(prepareSourceSchema.safeParse({ ...valid, ruPerSecond }).success).toBe(false);
    }
});
it('allows an explicitly reviewed longer duration within the existing worker bound, never an unbounded run', () => {
    const input = {
        partitions: ['fixture'],
        maximumRequests: 20,
        maximumDurationSeconds: 86400,
        ruBudget: '1000',
        confirmStableSource: true,
    };
    expect(prepareSourceSchema.parse(input).maximumDurationSeconds).toBe(86400);
    for (const seconds of [9, 86401, Infinity, 600.5])
        expect(prepareSourceSchema.safeParse({ ...input, maximumDurationSeconds: seconds }).success).toBe(false);
});
it('reads driver YAML routing without exposing passwords or guessing an immutable source', async () => {
    await writeFile(
        path.join(root, 'gremlin.yaml'),
        'hosts: [account.gremlin.cosmos.azure.com]\nusername: /dbs/db/colls/graph\npassword: private-key\n',
    );
    const detected = await discoverSetup(root, {});
    expect(detected.values.host).toBe('account.gremlin.cosmos.azure.com');
    expect(detected.values.database).toBe('db');
    expect(detected.values.container).toBe('graph');
    expect(JSON.stringify(detected)).not.toContain('private-key');
    expect(detected.values.consistency).toBe('');
});
it('rejects unsafe/dynamic inferred values and reports malformed known configuration', async () => {
    await writeFile(path.join(root, 'config.json'), '{');
    const detected = await discoverSetup(root, {
        GREMLIN_HOST: '${GREMLIN_ENDPOINT}',
        FUSEKI_URL: 'https://user:secret@example.com/ds',
    });
    expect(detected.values.host).toBe('');
    expect(detected.values.datasetUrl).toBe('');
    expect(detected.notices.length).toBe(3);
    expect(JSON.stringify(detected)).not.toContain('secret');
});
it('fills real inventory checksum and conventional endpoints without manufacturing evidence', async () => {
    const service = new GraphProjectService(root),
        run = await service.root();
    const original = project();
    const result = await applySetup(
        original,
        {
            host: 'wss://account.gremlin.cosmos.azure.com:443',
            database: 'db',
            container: 'graph',
            partitionKeyPath: '/tenant',
            datasetUrl: 'http://localhost:3030/graph/',
            consistency: 'quiesced',
        },
        run,
    );
    expect(result.source?.host).toBe('account.gremlin.cosmos.azure.com');
    expect(result.source?.identityManifestHash).toBe(
        'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    );
    expect(await readFile(path.join(run, 'identities.ndjson'), 'utf8')).toBe('');
    expect(result.target?.queryEndpoint).toBe('http://localhost:3030/graph/query');
    expect(result.target?.updateEndpoint).toBe('http://localhost:3030/graph/update');
    expect(result.target?.graphStoreEndpoint).toBe('http://localhost:3030/graph/data');
    expect(result.mapping).toEqual(original.mapping);
    expect(result.phases).toEqual({});
    expect(result.approvals).toEqual([]);
    expect(inputHash(result)).not.toBe(inputHash(original));
    const altered = { ...result, target: { ...result.target!, queryEndpoint: 'http://localhost:3030/custom-query' } };
    expect(
        (
            await applySetup(
                altered,
                {
                    host: result.source!.host,
                    database: 'db',
                    container: 'graph',
                    partitionKeyPath: '/tenant',
                    datasetUrl: altered.target.datasetUrl,
                    consistency: 'quiesced',
                },
                run,
            )
        ).target?.queryEndpoint,
    ).toBe(altered.target.queryEndpoint);
});
it.skipIf(!process.env.GRAPH_WORKER_SMOKE)(
    'parses a form-generated project and constructs the packaged driver offline',
    async () => {
        const run = await new GraphProjectService(root).root();
        const result = await applySetup(
            project(),
            {
                host: '127.0.0.1',
                database: 'fixture',
                container: 'graph',
                partitionKeyPath: '/pk',
                datasetUrl: 'http://localhost:3030',
                consistency: 'quiesced',
            },
            run,
        );
        const configured = graphProjectSchema.parse({
            ...result,
            limits: { ...result.limits, maximumOperationMillis: 120000 },
            source: { ...result.source, ruBudget: '1000', ruPerSecond: '400' },
        });
        const file = path.join(run, 'form.json');
        await writeFile(file, JSON.stringify(configured));
        const jdk = process.env.JAVA_HOME;
        if (!jdk) throw new Error('This opt-in smoke needs the existing JDK and built graph workers');
        const jar = path.resolve('resources', 'graph-migration', 'cosmos-gremlin-source-0.1.0.jar');
        const executableSuffix = process.platform === 'win32' ? '.exe' : '';
        const compile = spawnSync(
            path.join(jdk, 'bin', `javac${executableSuffix}`),
            [
                '-cp',
                jar,
                '-d',
                root,
                path.resolve(
                    'java',
                    'cosmos-gremlin-source',
                    'src',
                    'test',
                    'java',
                    'io',
                    'github',
                    'gremlin2rdf',
                    'source',
                    'FormClusterProbe.java',
                ),
            ],
            { encoding: 'utf8', timeout: 15000, windowsHide: true },
        );
        expect(compile.status).toBe(0);
        const probe = spawnSync(
            path.join(jdk, 'bin', `java${executableSuffix}`),
            ['-cp', [jar, root].join(path.delimiter), 'io.github.gremlin2rdf.source.FormClusterProbe', file],
            { encoding: 'utf8', timeout: 10000, windowsHide: true },
        );
        expect(probe.status).toBe(0);
        const missingPlan = spawnSync(
            path.join(jdk, 'bin', `java${executableSuffix}`),
            ['-jar', jar, 'enumerate', file, 'enumeration-plan.json', 'artifacts/missing-plan-output'],
            { encoding: 'utf8', timeout: 10000, windowsHide: true },
        );
        expect(missingPlan.status).toBe(2);
        expect(JSON.parse(missingPlan.stdout)).toMatchObject({
            status: 'blocked',
            code: 'SOURCE_ENUMERATION_PLAN_MISSING',
            stage: 'source-enumeration-plan',
        });
        const prepared = await freshPreparation(configured, run, {
            partitions: ['fixture'],
            maximumRequests: 20,
            maximumDurationSeconds: 10,
            ruBudget: '1000',
            ruPerSecond: '400',
            confirmStableSource: true,
        });
        await mkdir(path.join(run, prepared.output), { recursive: true });
        const identities =
            canonicalJson({
                schemaVersion: 1,
                kind: 'vertex',
                partition: { type: 'string', lexical: 'fixture' },
                id: { type: 'string', lexical: 'one' },
                ownerId: null,
                edgeScope: null,
            }) + '\n';
        await writeFile(path.join(run, prepared.output, 'identities.ndjson'), identities);
        const plan: unknown = JSON.parse(await readFile(path.join(run, prepared.plan), 'utf8'));
        await writeFile(
            path.join(run, prepared.output, 'manifest.json'),
            JSON.stringify({
                schemaVersion: 1,
                status: 'succeeded',
                runId: prepared.project.runId,
                inputHash: inputHash(prepared.project),
                sha256: createHash('sha256').update(identities).digest('hex'),
                planHash: createHash('sha256').update(canonicalJson(plan)).digest('hex'),
                vertices: 1,
                edges: 0,
                identityTypes: 'string-only',
                edgeScope: 'sourceVertex',
                sourceCountsReconciled: true,
            }),
        );
        const adopted = await adoptEnumeration(prepared.project, run, prepared.output);
        expect(adopted.source?.ruPerSecond).toBe('400');
        expect(adopted.source?.ruBudget).toBe('1000');
        expect(adopted.runId).not.toBe(prepared.project.runId);
        expect(hasCurrentSourceCompatibility(adopted)).toBe(false);
        const evidencePath = adopted.source!.compatibilityEvidence!;
        // Mock observations exist only in this isolated offline fixture, never a production capability claim.
        const evidence = JSON.stringify({
            schemaVersion: 1,
            runId: adopted.runId,
            inputHash: inputHash(adopted),
            operation: 'source-compatibility',
            status: 'succeeded',
            result: {
                status: 'succeeded',
                sourceManifestHash: adopted.source!.identityManifestHash,
                driverVersion: '3.4.13',
                serializer: 'GraphSONMessageSerializerV2d0',
                liveEndpointObserved: true,
                tlsHostnameVerified: true,
                partitionQualifiedLookup: true,
                typedValuesPreserved: true,
                edgeFidelity: true,
                propertyFidelity: true,
                edgeScopes: ['sourceVertex'],
                sourceCountsReconciled: true,
                stableRepeatedReads: true,
                systemMetadataFidelity: true,
            },
        });
        await mkdir(path.dirname(path.join(run, evidencePath)), { recursive: true });
        await writeFile(path.join(run, evidencePath), evidence);
        const current = graphProjectSchema.parse({
            ...adopted,
            phases: {
                ...adopted.phases,
                'source-compatibility': {
                    status: 'succeeded',
                    inputHash: inputHash(adopted),
                    evidence: [evidencePath],
                    blockers: [],
                },
            },
            artifacts: [
                ...adopted.artifacts,
                {
                    relativePath: evidencePath,
                    sha256: createHash('sha256').update(evidence).digest('hex'),
                    ownerRunId: adopted.runId,
                    sensitive: true,
                },
            ],
        });
        expect(hasCurrentSourceCompatibility(current)).toBe(true);
        await writeFile(file, JSON.stringify(current));
        const extraction = spawnSync(
            path.join(jdk, 'bin', `java${executableSuffix}`),
            [
                '-cp',
                [jar, root].join(path.delimiter),
                'io.github.gremlin2rdf.source.FormClusterProbe',
                file,
                '--extract-fixture',
            ],
            { encoding: 'utf8', timeout: 10000, windowsHide: true },
        );
        expect(extraction.status).toBe(0);
        expect(JSON.parse(extraction.stdout)).toMatchObject({
            status: 'succeeded',
            requestsSubmitted: 0,
            connectionsOpened: 0,
            fixtureRecords: 1,
        });
        const response: unknown = JSON.parse(probe.stdout);
        expect(response).toEqual({
            status: 'succeeded',
            fixtureRecords: 0,
            inputHash: inputHash(configured),
            requestsSubmitted: 0,
            connectionsOpened: 0,
            driver: '3.4.13',
        });
        const saved = {
            ...configured,
            phases: {
                preflight: {
                    status: 'blocked' as const,
                    inputHash: inputHash(configured),
                    evidence: [],
                    blockers: ['previous'],
                },
            },
        };
        await new GraphProjectService(root).save(saved);
        const unchanged = await applySetup(
            saved,
            {
                host: configured.source!.host,
                database: 'fixture',
                container: 'graph',
                partitionKeyPath: '/pk',
                datasetUrl: 'http://localhost:3030/',
                consistency: 'quiesced',
            },
            run,
        );
        expect(inputHash(unchanged)).toBe(inputHash(saved));
        expect(unchanged.phases.preflight.status).toBe('blocked');
    },
);
it('requires explicit source consistency and never replaces an existing inventory', async () => {
    const run = await new GraphProjectService(root).root();
    await writeFile(path.join(run, 'identities.ndjson'), 'reviewed-inventory\n');
    const input = {
        host: 'account.gremlin.cosmos.azure.com',
        database: 'db',
        container: 'graph',
        partitionKeyPath: '/pk',
        datasetUrl: '',
        consistency: '' as const,
    };
    await expect(applySetup(project(), input, run)).rejects.toThrow('consistency');
    await applySetup(project(), { ...input, consistency: 'immutable' }, run);
    expect(await readFile(path.join(run, 'identities.ndjson'), 'utf8')).toBe('reviewed-inventory\n');
});
it('finds resource-specific VS Code settings without reading unrelated connection strings', async () => {
    await mkdir(path.join(root, '.vscode'));
    await writeFile(
        path.join(root, '.vscode', 'settings.json'),
        `{
        // Existing VS Code settings often contain comments and trailing commas.
        "cosmosDB.graphMigration.source.host": "account.gremlin.cosmos.azure.com",
        "cosmosDB.graphMigration.target.datasetUrl": "http://localhost:3030/graph",
        "connectionString": "secret",
    }`,
    );
    const result = await discoverSetup(root, {});
    expect(result.values.host).toBe('account.gremlin.cosmos.azure.com');
    expect(result.values.datasetUrl).toBe('http://localhost:3030/graph');
});
it('rejects failed enumeration adoption and accepts only reconciled owned empty inventory evidence', async () => {
    const run = await new GraphProjectService(root).root();
    const original = await applySetup(
        project(),
        {
            host: 'localhost',
            database: 'fixture',
            container: 'graph',
            partitionKeyPath: '/pk',
            datasetUrl: 'http://localhost:3030/graph',
            consistency: 'immutable',
        },
        run,
    );
    expect(hasFidelityInventory(original)).toBe(false);
    const prepared = await freshPreparation(original, run, {
        partitions: ['fixture'],
        maximumRequests: 20,
        maximumDurationSeconds: 10,
        ruBudget: '1000',
        confirmStableSource: true,
    });
    await mkdir(path.join(run, prepared.output));
    await writeFile(path.join(run, prepared.output, 'identities.ndjson'), '');
    const plan: unknown = JSON.parse(await readFile(path.join(run, prepared.plan), 'utf8'));
    const manifest = {
        schemaVersion: 1,
        status: 'blocked',
        runId: prepared.project.runId,
        inputHash: inputHash(prepared.project),
        sha256: original.source!.identityManifestHash,
        planHash: createHash('sha256').update(canonicalJson(plan)).digest('hex'),
        vertices: 0,
        edges: 0,
        identityTypes: 'string-only',
        edgeScope: 'sourceVertex',
        sourceCountsReconciled: true,
    };
    await writeFile(path.join(run, prepared.output, 'manifest.json'), JSON.stringify(manifest));
    await expect(adoptEnumeration(prepared.project, run, prepared.output)).rejects.toThrow();
    expect(prepared.project.source?.identityManifest).toBe(original.source?.identityManifest);
    expect(hasFidelityInventory(prepared.project)).toBe(false);
    expect(hasCurrentSourceCompatibility(prepared.project)).toBe(false);
    await writeFile(
        path.join(run, prepared.output, 'manifest.json'),
        JSON.stringify({ ...manifest, status: 'succeeded', sourceCountsReconciled: false }),
    );
    await expect(adoptEnumeration(prepared.project, run, prepared.output)).rejects.toThrow();
    await writeFile(
        path.join(run, prepared.output, 'manifest.json'),
        JSON.stringify({ ...manifest, status: 'succeeded' }),
    );
    const adopted = await adoptEnumeration(prepared.project, run, prepared.output);
    expect(hasFidelityInventory(adopted)).toBe(true);
    expect(hasCurrentSourceCompatibility(adopted)).toBe(false);
    expect(adopted.source?.identityManifest).toBe(`${prepared.output}/identities.ndjson`);
    expect(adopted.artifacts.some((a) => a.relativePath.startsWith('artifacts/adopted-inventory-'))).toBe(true);
    expect(
        hasFidelityInventory({ ...original, source: { ...original.source!, identityManifestHash: '1'.repeat(64) } }),
    ).toBe(true);
});
it('requires owned current fidelity after a supplied inventory without changing reviewed state', async () => {
    const run = await new GraphProjectService(root).root();
    const configured = await applySetup(
        project(),
        {
            host: 'localhost',
            database: 'fixture',
            container: 'graph',
            partitionKeyPath: '/pk',
            datasetUrl: 'http://localhost:3030/graph',
            consistency: 'immutable',
        },
        run,
    );
    const supplied = graphProjectSchema.parse({
        ...configured,
        source: {
            ...configured.source!,
            identityManifestHash: '1'.repeat(64),
            compatibilityEvidence: 'artifacts/fidelity.json',
        },
    });
    const current = graphProjectSchema.parse({
        ...supplied,
        phases: {
            'source-compatibility': {
                status: 'succeeded',
                inputHash: inputHash(supplied),
                evidence: ['artifacts/fidelity.json'],
                blockers: [],
            },
        },
        artifacts: [
            {
                relativePath: 'artifacts/fidelity.json',
                ownerRunId: supplied.runId,
                sha256: '2'.repeat(64),
                sensitive: true,
            },
        ],
    });
    const before = canonicalJson(current);
    expect(hasCurrentSourceCompatibility(current)).toBe(true);
    expect(
        hasCurrentSourceCompatibility({
            ...current,
            artifacts: [{ ...current.artifacts[0], ownerRunId: 'prior-run' }],
        }),
    ).toBe(false);
    expect(hasCurrentSourceCompatibility({ ...current, limits: { ...current.limits, batchRecords: 99 } })).toBe(false);
    expect(
        hasCurrentSourceCompatibility({
            ...current,
            phases: {
                ...current.phases,
                'source-compatibility': { ...current.phases['source-compatibility'], status: 'stale' },
            },
        }),
    ).toBe(false);
    expect(canonicalJson(current)).toBe(before);
});
