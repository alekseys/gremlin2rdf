/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { afterEach, beforeEach, expect, it } from 'vitest';
import { graphProjectSchema, inputHash } from './contracts';
import { GraphProjectService } from './GraphProjectService';
import { applySetup, discoverSetup } from './setup';

let root: string;
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
