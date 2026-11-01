/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { parse as parseJson } from '@prantlf/jsonlint';
import * as l10n from '@vscode/l10n';
import { lstat } from 'node:fs/promises';
import { z } from 'zod';
import { fileHash, ownedPath, readBounded, writeNewArtifact } from './artifacts';
import { graphProjectSchema, invalidateEvidence, type GraphProject } from './contracts';

export const connectionSetupSchema = z
    .object({
        host: z.string().max(500),
        database: z.string().max(255),
        container: z.string().max(255),
        partitionKeyPath: z.string().max(256),
        datasetUrl: z.string().max(2000),
        consistency: z.enum(['quiesced', 'immutable', '']).default(''),
    })
    .strict();
export type ConnectionSetup = z.infer<typeof connectionSetupSchema>;
export interface SetupSuggestions {
    values: ConnectionSetup;
    origins: Partial<Record<keyof ConnectionSetup, string>>;
    notices: string[];
}

type ConnectionField = Exclude<keyof ConnectionSetup, 'consistency'>;
const aliases: Record<string, ConnectionField> = {
    gremlinhost: 'host',
    cosmosgremlinhost: 'host',
    cosmosdbgremlinhost: 'host',
    gremlinendpoint: 'host',
    cosmosgremlinendpoint: 'host',
    cosmosdbgremlinendpoint: 'host',
    gremlindatabase: 'database',
    cosmosgremlindatabase: 'database',
    cosmosdbgremlindatabase: 'database',
    gremlindatabasename: 'database',
    cosmosgremlindatabasename: 'database',
    gremlincontainer: 'container',
    cosmosgremlincontainer: 'container',
    cosmosdbgremlincontainer: 'container',
    cosmosgremlingraph: 'container',
    cosmosgremlingraphname: 'container',
    cosmosdbgremlingraph: 'container',
    cosmosdbgremlincontainername: 'container',
    gremlingraph: 'container',
    gremlingraphname: 'container',
    gremlincontainername: 'container',
    gremlinpartitionkey: 'partitionKeyPath',
    gremlinpartitionkeypath: 'partitionKeyPath',
    cosmosgremlinpartitionkeypath: 'partitionKeyPath',
    cosmosdbgremlinpartitionkeypath: 'partitionKeyPath',
    fusekidataseturl: 'datasetUrl',
    fusekiendpoint: 'datasetUrl',
    fusekiurl: 'datasetUrl',
    jenaendpoint: 'datasetUrl',
    jenadataseturl: 'datasetUrl',
    graphsourcehost: 'host',
    graphsourcedatabase: 'database',
    graphsourcecontainer: 'container',
    graphsourcepartitionkeypath: 'partitionKeyPath',
    graphtargetdataseturl: 'datasetUrl',
};
const normalized = (key: string) => key.toLowerCase().replace(/[^a-z0-9]/gu, '');
const files = [
    '.env',
    '.env.local',
    'application.properties',
    'src/main/resources/application.properties',
    'appsettings.json',
    'appsettings.Development.json',
    'local.settings.json',
    'config.json',
    'config/gremlin.json',
    'gremlin.json',
    '.vscode/settings.json',
    'gremlin.yaml',
    'gremlin.yml',
    'conf/remote.yaml',
    'src/main/resources/gremlin.yaml',
    'application.yml',
    'application.yaml',
    'src/main/resources/application.yml',
];
const emptyValues = (): ConnectionSetup => ({
    host: '',
    database: '',
    container: '',
    partitionKeyPath: '/pk',
    datasetUrl: '',
    consistency: '',
});

function safeSuggestion(field: ConnectionField, value: string): string | undefined {
    if (!value || value.length > 2000 || /[\r\n${}]/u.test(value)) return undefined;
    if (field === 'host') {
        if (/^[a-z0-9.-]+$/iu.test(value)) return value;
        try {
            const url = new URL(value);
            if (
                !['wss:', 'https:'].includes(url.protocol) ||
                url.username ||
                url.password ||
                url.search ||
                url.hash ||
                url.pathname !== '/' ||
                (url.port && url.port !== '443')
            )
                return undefined;
            return url.hostname;
        } catch {
            return undefined;
        }
    }
    if (field === 'datasetUrl') {
        const target = graphProjectSchema.shape.target.unwrap().shape.datasetUrl.safeParse(value);
        if (!target.success) return undefined;
        const url = new URL(value);
        if (url.search) return undefined;
        return value.replace(/\/+$/u, '');
    }
    if (field === 'partitionKeyPath') {
        const candidate = value.startsWith('/') ? value : `/${value}`;
        return /^\/[^/]{1,255}$/u.test(candidate) ? candidate : undefined;
    }
    return value.length <= 255 && !/[;\\]/u.test(value) ? value : undefined;
}

/** Reads only recognized non-secret connection keys; never evaluates application code or resolves secrets. */
export async function discoverSetup(workspace: string, environment: NodeJS.ProcessEnv): Promise<SetupSuggestions> {
    const values = emptyValues(),
        origins: SetupSuggestions['origins'] = {},
        notices: string[] = [];
    const candidates = new Map<ConnectionField, Map<string, string>>();
    const collect = (key: string, value: unknown, origin: string) => {
        const name = normalized(key);
        const field =
            aliases[name] ??
            (name.includes('graphmigration')
                ? (aliases[name.replace(/^.*graphmigration(?:source|target)?/u, 'gremlin')] ??
                  aliases[name.replace(/^.*graphmigrationtarget/u, 'fuseki')])
                : undefined);
        if (!field || typeof value !== 'string') return;
        const safe = safeSuggestion(field, value.trim());
        if (!safe) {
            notices.push(
                l10n.t('A connection value in {file} could not be safely inferred. Enter it manually.', {
                    file: origin,
                }),
            );
            return;
        }
        const entries = candidates.get(field) ?? new Map<string, string>();
        entries.set(safe, origin);
        candidates.set(field, entries);
    };
    for (const [key, value] of Object.entries(environment)) collect(key, value, l10n.t('Environment'));
    const flatten = (value: unknown, prefix: string, origin: string, depth = 0) => {
        if (depth > 8 || !value || typeof value !== 'object' || Array.isArray(value)) return;
        for (const [key, item] of Object.entries(value)) {
            const name = prefix + key;
            collect(name, item, origin);
            flatten(item, `${name}.`, origin, depth + 1);
        }
    };
    for (const relative of files) {
        try {
            const file = await ownedPath(workspace, relative);
            if (!(await lstat(file)).isFile()) throw new Error('Configuration is not a regular file');
            const text = (await readBounded(file, 262144)).toString('utf8');
            if (relative.endsWith('.json')) {
                const parsed: unknown = parseJson(text, {
                    ignoreComments: true,
                    ignoreTrailingCommas: true,
                    allowDuplicateObjectKeys: false,
                });
                flatten(parsed, '', relative);
                if (relative.endsWith('gremlin.json')) flatten(parsed, 'gremlin.', relative);
                // Azure Functions stores environment-style keys under Values.
                if (relative === 'local.settings.json' && parsed && typeof parsed === 'object' && 'Values' in parsed)
                    flatten(parsed.Values, '', relative);
            } else if (/\.(?:yaml|yml)$/u.test(relative)) {
                const parents: { indent: number; key: string }[] = [];
                for (const line of text.split(/\r?\n/u)) {
                    const match = /^(\s*)([A-Za-z_][A-Za-z0-9_-]*):\s*(.*?)\s*$/u.exec(line);
                    if (!match) continue;
                    const indent = match[1].length;
                    while (parents.length && parents[parents.length - 1].indent >= indent) parents.pop();
                    if (!match[3]) {
                        parents.push({ indent, key: match[2] });
                        continue;
                    }
                    const key = [...parents.map((parent) => parent.key), match[2]].join('.');
                    const raw = match[3];
                    const value = raw.replace(/^(["'])(.*)\1$/u, '$2');
                    if (match[2] === 'hosts' && (relative.includes('gremlin') || relative === 'conf/remote.yaml')) {
                        const host = /^\[\s*["']?([a-z0-9.-]+)["']?\s*\]$/iu.exec(raw);
                        if (host) collect('gremlin.host', host[1], relative);
                    }
                    if (/^[!&*[{>]|^.*\s#/u.test(raw)) continue;
                    collect(key, value, relative);
                    if (relative.includes('gremlin') || relative === 'conf/remote.yaml')
                        collect(`gremlin.${key}`, value, relative);
                    // The conventional Cosmos driver username contains routing IDs, not the password.
                    if (match[2] === 'username' && (relative.includes('gremlin') || relative === 'conf/remote.yaml')) {
                        const routing = /^\/dbs\/([^/]+)\/colls\/([^/]+)$/u.exec(value);
                        if (routing) {
                            collect('gremlin.database', routing[1], relative);
                            collect('gremlin.container', routing[2], relative);
                        }
                    }
                }
            } else {
                for (const line of text.split(/\r?\n/u)) {
                    const match = /^(?:export\s+)?([A-Za-z_][A-Za-z0-9_.:-]*)\s*=\s*(.*?)\s*$/u.exec(line.trim());
                    if (match) {
                        const value = match[2].replace(/^(["'])(.*)\1$/u, '$2');
                        collect(match[1], value, relative);
                    }
                }
            }
        } catch (error) {
            if (error instanceof Error && 'code' in error && error.code === 'ENOENT') continue;
            notices.push(
                l10n.t('Could not inspect {file}. Review its format, size and access permissions.', { file: relative }),
            );
        }
    }
    for (const [field, entries] of candidates) {
        if (entries.size === 1) {
            const [value, origin] = entries.entries().next().value!;
            values[field] = value;
            origins[field] = origin;
        } else {
            values[field] = '';
            notices.push(
                l10n.t('Conflicting {field} settings were found. Choose the intended value manually.', { field }),
            );
        }
    }
    return { values, origins, notices };
}

export async function applySetup(project: GraphProject, value: ConnectionSetup, root: string): Promise<GraphProject> {
    const input = connectionSetupSchema.parse(value);
    const sourceFields = [input.host, input.database, input.container].map((text) => text.trim());
    if (sourceFields.some(Boolean)) {
        if (!sourceFields.every(Boolean) || !input.consistency)
            throw new Error(l10n.t('Enter the Gremlin host, database, graph and source consistency before saving.'));
        const host = safeSuggestion('host', sourceFields[0]);
        if (!host)
            throw new Error(l10n.t('Enter a Gremlin hostname or a credential-free HTTPS/WSS endpoint on port 443.'));
        const identityManifest = project.source?.identityManifest ?? 'identities.ndjson';
        let manifest: string;
        try {
            manifest = await ownedPath(root, identityManifest);
        } catch (error) {
            if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT') || project.source) throw error;
            // A real empty bootstrap inventory permits discovery, not extraction/compatibility acceptance.
            manifest = await writeNewArtifact(root, identityManifest, '');
        }
        project = {
            ...project,
            consistency: input.consistency,
            source: {
                ...project.source,
                scope: project.source?.scope ?? `${host}/${sourceFields[1]}/${sourceFields[2]}`,
                host,
                port: project.source?.port ?? 443,
                database: sourceFields[1],
                container: sourceFields[2],
                partitionKeyPath: input.partitionKeyPath.trim(),
                authenticationRef: project.source?.authenticationRef ?? 'env:GRAPH_SOURCE_KEY',
                extractionStrategy: 'verified-identity-manifest',
                identityManifest,
                identityManifestHash: await fileHash(manifest),
                compatibilityEvidence: project.source?.compatibilityEvidence ?? 'artifacts/source-compatibility.json',
                ruBudget: project.source?.ruBudget ?? null,
                ...(project.source?.ruPerSecond === undefined ? {} : { ruPerSecond: project.source.ruPerSecond }),
                customCaRef: project.source?.customCaRef ?? null,
                syntheticTestContainer: project.source?.syntheticTestContainer ?? false,
            },
        };
    } else if (project.source)
        throw new Error(l10n.t('Use advanced settings to remove an existing source configuration.'));
    const datasetUrl = input.datasetUrl.trim().replace(/\/+$/u, '');
    if (datasetUrl) {
        if (!safeSuggestion('datasetUrl', datasetUrl))
            throw new Error(
                l10n.t(
                    'Enter an HTTPS Fuseki dataset URL without credentials or query parameters; HTTP is allowed for localhost.',
                ),
            );
        const sameDataset = project.target?.datasetUrl.replace(/\/+$/u, '') === datasetUrl;
        project = {
            ...project,
            target: {
                ...project.target,
                datasetUrl,
                queryEndpoint: sameDataset ? project.target!.queryEndpoint : `${datasetUrl}/query`,
                updateEndpoint: sameDataset ? project.target!.updateEndpoint : `${datasetUrl}/update`,
                graphStoreEndpoint: sameDataset ? project.target!.graphStoreEndpoint : `${datasetUrl}/data`,
                authenticationRef: project.target?.authenticationRef ?? null,
                graphNamespace: project.target?.graphNamespace ?? 'urn:gremlin2rdf:run:',
                publicationMode: project.target?.publicationMode ?? 'manual',
                customCaRef: project.target?.customCaRef ?? null,
            },
        };
    } else if (project.target)
        throw new Error(l10n.t('Use advanced settings to remove an existing target configuration.'));
    return invalidateEvidence(graphProjectSchema.parse(project));
}
