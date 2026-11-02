/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import * as l10n from '@vscode/l10n';
import { createHash } from 'node:crypto';
import * as path from 'node:path';
import { z } from 'zod';
import { ownedPath, readBounded, fileHash, writeNewArtifact } from './artifacts';
import {
    canonicalJson,
    graphMigrationFolder,
    graphProjectSchema,
    inputHash,
    invalidateEvidence,
    type GraphProject,
} from './contracts';
import { hasCurrentEnumerationDeadline, hasCurrentEnumerationRequestLimit } from './diagnostics';
const partition = z
    .string()
    .min(1)
    .max(4096)
    .refine((v) => Array.from(v).every((c) => c.charCodeAt(0) >= 32));
export const prepareSourceSchema = z
    .object({
        partitions: z
            .array(partition)
            .min(1)
            .max(1000)
            .refine(
                (v) =>
                    new Set(v).size === v.length &&
                    v.reduce((size, value) => size + Buffer.byteLength(value, 'utf8'), 0) <= 524288,
            ),
        maximumRequests: z.number().int().min(4).max(100000),
        maximumDurationSeconds: z.number().int().min(10).max(86400),
        ruBudget: z
            .string()
            .regex(/^[0-9]+(?:\.[0-9]+)?$/u)
            .refine((v) => Number(v) > 0 && Number(v) <= 1e9),
        confirmStableSource: z.literal(true),
        ruPerSecond: z
            .string()
            .regex(/^[0-9]{1,10}(?:\.[0-9]{1,9})?$/u)
            .refine((value) => Number(value) >= 1 && Number(value) <= 1e9)
            .optional(),
    })
    .strict();
export type PrepareSource = z.infer<typeof prepareSourceSchema>;
export interface PreparationCandidates {
    partitions: string[];
    origins: string[];
    notices: string[];
    coverage: 'unverified-candidates';
    resume?: { plan: string; output: string };
}
type Token = { kind: 'word' | 'string' | 'punctuation'; value: string };
/** A bounded lexical reader, never a Groovy evaluator. Strings/comments cannot become executable tokens. */
export function seedPartitions(text: string, key: string): string[] {
    const values = new Set<string>();
    for (const line of text.split(/\r?\n/u)) {
        if (line.length > 262144) throw new Error('Seed line exceeds bounded input');
        const tokens: Token[] = [];
        let i = 0;
        while (i < line.length) {
            const c = line[i];
            if (/\s/u.test(c)) {
                i++;
                continue;
            }
            if (c === '/' && line[i + 1] === '/') break;
            if (c === "'" || c === '"') {
                const quote = c;
                let value = '',
                    closed = false;
                i++;
                while (i < line.length) {
                    let next = line[i++];
                    if (next === quote) {
                        closed = true;
                        break;
                    }
                    if (next === '\\') {
                        next = line[i++];
                        if (next !== "'" && next !== '"' && next !== '\\')
                            throw new Error('Unsupported seed string escape');
                    }
                    value += next;
                }
                if (!closed) throw new Error('Unterminated seed string');
                tokens.push({ kind: 'string', value });
            } else if (/[A-Za-z0-9_]/u.test(c)) {
                const start = i++;
                while (i < line.length && /[A-Za-z0-9_]/u.test(line[i])) i++;
                tokens.push({ kind: 'word', value: line.slice(start, i) });
            } else {
                tokens.push({ kind: 'punctuation', value: c });
                i++;
            }
        }
        if (
            tokens[0]?.kind !== 'word' ||
            tokens[0]?.value !== 'g' ||
            tokens[1]?.value !== '.' ||
            tokens[2]?.kind !== 'word' ||
            tokens[2]?.value !== 'addV'
        )
            continue;
        for (let n = 3; n + 6 < tokens.length; n++) {
            if (
                tokens[n].value === '.' &&
                tokens[n + 1].value === 'property' &&
                tokens[n + 2].value === '(' &&
                tokens[n + 3].kind === 'string' &&
                tokens[n + 3].value === key &&
                tokens[n + 4].value === ',' &&
                tokens[n + 5].kind === 'string' &&
                tokens[n + 6].value === ')'
            )
                values.add(partition.parse(tokens[n + 5].value));
        }
        if (values.size > 1000) throw new Error('More than 1000 candidate partitions; explicit bounded plans required');
    }
    return [...values].sort();
}
export async function discoverPreparation(workspace: string, p: GraphProject): Promise<PreparationCandidates> {
    const partitions = new Set<string>(),
        origins: string[] = [],
        notices: string[] = [];
    for (const relative of ['gremlin-partitions.json', 'data/gremlin/gremlin_load_file.txt']) {
        try {
            const text = (await readBounded(await ownedPath(workspace, relative), 16777216)).toString('utf8');
            const found = relative.endsWith('.json')
                ? z
                      .object({ schemaVersion: z.literal(1), partitions: z.array(partition).max(1000) })
                      .strict()
                      .parse(JSON.parse(text)).partitions
                : seedPartitions(text, p.source?.partitionKeyPath.slice(1) ?? 'pk');
            for (const value of found) partitions.add(value);
            if (partitions.size > 1000) throw new Error('Candidate partition bound exceeded');
            origins.push(relative);
        } catch (error) {
            if (error instanceof Error && 'code' in error && error.code === 'ENOENT') continue;
            notices.push(
                l10n.t(
                    'Could not safely inspect {file}. Supply explicit partition values in Prepare; no values were executed.',
                    { file: relative },
                ),
            );
        }
    }
    if (partitions.size > 1000)
        return {
            partitions: [],
            origins,
            notices: [l10n.t('Candidate partition count exceeds the supported 1000-partition bound.')],
            coverage: 'unverified-candidates',
        };
    let resume: PreparationCandidates['resume'];
    if (p.source) {
        const plan = `artifacts/enumeration-plan-${p.runId}.json`,
            output = `artifacts/source-enumeration-${p.runId}`;
        try {
            let pendingCheckpoint = false;
            try {
                await ownedPath(path.join(workspace, graphMigrationFolder), `${output}/checkpoint.json.pending`);
                pendingCheckpoint = true;
            } catch (error) {
                if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error;
            }
            if (pendingCheckpoint)
                notices.push(
                    l10n.t(
                        'An unacknowledged enumeration checkpoint is present. Preserve the old and pending files; do not delete or rename either. Resume is unavailable until verified recovery. You can explicitly review a new bounded preparation below without altering the old state.',
                    ),
                );
            const state = z
                .object({
                    runId: z.string(),
                    inputHash: z.string(),
                    requests: z.number().nonnegative(),
                    elapsedMillis: z.number().nonnegative(),
                })
                .passthrough()
                .parse(
                    JSON.parse(
                        (
                            await readBounded(
                                await ownedPath(
                                    path.join(workspace, graphMigrationFolder),
                                    `${output}/checkpoint.json`,
                                ),
                                1048576,
                            )
                        ).toString('utf8'),
                    ),
                );
            const limits = z
                .object({ maximumRequests: z.number(), maximumDurationMillis: z.number() })
                .passthrough()
                .parse(
                    JSON.parse(
                        (
                            await readBounded(
                                await ownedPath(path.join(workspace, graphMigrationFolder), plan),
                                1048576,
                            )
                        ).toString('utf8'),
                    ),
                );
            const phase = p.phases['source-enumerate'];
            const accountingBlocked =
                phase?.inputHash === inputHash(p) &&
                phase.blockers.some((blocker) =>
                    /\bSOURCE_RU_(?:BUDGET_EXHAUSTED|UNOBSERVABLE|ACCOUNTING_REVIEW)\b/u.test(blocker),
                );
            const durationBlocked = hasCurrentEnumerationDeadline(p, inputHash(p));
            const requestsBlocked = hasCurrentEnumerationRequestLimit(p, inputHash(p));
            if (requestsBlocked)
                notices.push(
                    l10n.t(
                        'This run exhausted its reviewed reserved request attempts, including count reconciliation. Preserve its checkpoint and journal. Review a new request ceiling below; Resume cannot renew the old allowance.',
                    ),
                );
            if (durationBlocked)
                notices.push(
                    l10n.t(
                        'This run reached its reviewed operation/enumeration duration, which includes count reconciliation. Preserve its checkpoint and RU journal. Review a new bounded run with sufficient duration below; Resume does not renew the original time allowance or verify interrupted request charges.',
                    ),
                );
            if (accountingBlocked)
                notices.push(
                    l10n.t(
                        'The preserved run has exhausted, unobservable, or older partial-only RU accounting. Waiting or changing RU/s cannot repair it. Preserve its journal and checkpoints and review a new bounded run below; Resume does not grant additional RU.',
                    ),
                );
            if (
                !accountingBlocked &&
                !durationBlocked &&
                !requestsBlocked &&
                !pendingCheckpoint &&
                state.runId === p.runId &&
                state.inputHash === inputHash(p) &&
                state.requests < limits.maximumRequests &&
                state.elapsedMillis < limits.maximumDurationMillis
            )
                resume = { plan, output };
        } catch (error) {
            if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT'))
                notices.push(
                    l10n.t(
                        'Preserved preparation could not be verified for resume. Start a newly reviewed bounded run; old files are retained.',
                    ),
                );
        }
    }
    return { partitions: [...partitions].sort(), origins, notices, coverage: 'unverified-candidates', resume };
}
export async function freshPreparation(
    p: GraphProject,
    root: string,
    value: PrepareSource,
): Promise<{ project: GraphProject; plan: string; output: string }> {
    const input = prepareSourceSchema.parse(value);
    if (!p.source || !['quiesced', 'immutable'].includes(p.consistency))
        throw new Error('Stable configured source required');
    const archived = `artifacts/previous-run-${p.runId}-${globalThis.crypto.randomUUID()}.json`;
    await writeNewArtifact(root, archived, JSON.stringify(p, null, 2));
    const runId = globalThis.crypto.randomUUID();
    const project = graphProjectSchema.parse({
        ...p,
        runId,
        approvals: [],
        phases: Object.fromEntries(
            Object.entries(p.phases).map(([name, phase]) => [name, { ...phase, status: 'stale' }]),
        ),
        source: {
            ...p.source,
            ruBudget: input.ruBudget,
            ...(input.ruPerSecond === undefined ? {} : { ruPerSecond: input.ruPerSecond }),
            compatibilityEvidence: `artifacts/source-compatibility-${runId}.json`,
        },
        limits: { ...p.limits, maximumOperationMillis: input.maximumDurationSeconds * 1000 },
    });
    const plan = `artifacts/enumeration-plan-${runId}.json`,
        output = `artifacts/source-enumeration-${runId}`;
    await writeNewArtifact(
        root,
        plan,
        JSON.stringify(
            {
                schemaVersion: 1,
                partitions: input.partitions.map((lexical) => ({ type: 'string', lexical })),
                pageSize: 100,
                maximumRequests: input.maximumRequests,
                maximumDurationMillis: input.maximumDurationSeconds * 1000,
            },
            null,
            2,
        ),
    );
    return { project, plan, output };
}
export async function adoptEnumeration(p: GraphProject, root: string, directory: string): Promise<GraphProject> {
    const file = `${directory}/identities.ndjson`,
        manifestPath = `${directory}/manifest.json`;
    const manifest = z
        .object({
            schemaVersion: z.literal(1),
            status: z.literal('succeeded'),
            runId: z.string(),
            inputHash: z.string(),
            sha256: z.string().regex(/^[a-f0-9]{64}$/u),
            planHash: z.string(),
            vertices: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
            edges: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
            identityTypes: z.literal('string-only'),
            edgeScope: z.literal('sourceVertex'),
        })
        .passthrough()
        .parse(JSON.parse((await readBounded(await ownedPath(root, manifestPath), 1048576)).toString('utf8')));
    const oldHash = inputHash(p),
        checksum = await fileHash(await ownedPath(root, file));
    const plan: unknown = JSON.parse(
        (await readBounded(await ownedPath(root, `artifacts/enumeration-plan-${p.runId}.json`), 1048576)).toString(
            'utf8',
        ),
    );
    const planHash = createHash('sha256').update(canonicalJson(plan)).digest('hex');
    if (
        !p.source ||
        manifest.runId !== p.runId ||
        manifest.inputHash !== oldHash ||
        manifest.sha256 !== checksum ||
        manifest.planHash !== planHash ||
        (manifest.vertices + manifest.edges === 0 && manifest.sourceCountsReconciled !== true)
    )
        throw new Error('Unconfirmed or wrong-run enumeration cannot be adopted');
    const archive = `artifacts/enumerated-run-${p.runId}.json`;
    await writeNewArtifact(root, archive, JSON.stringify(p, null, 2));
    const runId = globalThis.crypto.randomUUID();
    const next = invalidateEvidence(
        graphProjectSchema.parse({
            ...p,
            runId,
            approvals: [],
            source: {
                ...p.source,
                identityManifest: file,
                identityManifestHash: checksum,
                compatibilityEvidence: `artifacts/source-compatibility-${runId}.json`,
            },
        }),
    );
    const receipt = `artifacts/adopted-inventory-${runId}.json`;
    await writeNewArtifact(
        root,
        receipt,
        JSON.stringify(
            {
                schemaVersion: 1,
                runId,
                inputHash: inputHash(next),
                producerRunId: p.runId,
                producerInputHash: oldHash,
                identityManifest: file,
                sha256: checksum,
                vertices: manifest.vertices,
                edges: manifest.edges,
                coverage: 'live-global-and-partition-counts-reconciled',
                manifestSha256: await fileHash(await ownedPath(root, manifestPath)),
            },
            null,
            2,
        ),
    );
    next.artifacts.push({
        relativePath: receipt,
        sha256: await fileHash(await ownedPath(root, receipt)),
        ownerRunId: runId,
        sensitive: true,
    });
    return next;
}
