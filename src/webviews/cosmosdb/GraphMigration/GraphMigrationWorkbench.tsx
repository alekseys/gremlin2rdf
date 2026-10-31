/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { Button, Field, Input, Select, Textarea, useAnnounce } from '@fluentui/react-components';
import { useTrpcClient } from '@microsoft/vscode-ext-webview/react';
import * as l10n from '@vscode/l10n';
import { useCallback, useEffect, useState } from 'react';
import {
    type GraphAppRouter,
    type GraphEvidencePhase,
    type GraphOperation,
} from '../../../panels/graphMigration/graphRouter';
import { type GraphProject } from '../../../services/graphMigration/contracts';
import { graphEvidencePhases } from '../../../services/graphMigration/evidence';

const operations = (): [GraphOperation, string][] => [
    ['preflight', l10n.t('Check prerequisites')],
    ['assess', l10n.t('Assess application')],
    ['source-compatibility', l10n.t('Probe read-only source compatibility')],
    ['mapping', l10n.t('Validate mapping')],
    ['synthetic', l10n.t('Generate local semantic fixture')],
    ['source-generate', l10n.t('Generate native source fixture locally')],
    ['source-plan', l10n.t('Review bounded source population plan')],
    ['source-recipes', l10n.t('Export source population recipes')],
    ['source-rdf', l10n.t('Export expected fixture RDF locally')],
    ['mapping-export', l10n.t('Export canonical input RDF locally')],
    ['fixture-generate', l10n.t('Derive query and mutation fixtures')],
    ['fixture-test', l10n.t('Execute approved query and CRUD fixtures')],
    ['benchmark-load', l10n.t('Run private reference load benchmark')],
    ['benchmark-soak', l10n.t('Run private two-hour mixed soak')],
    ['source-populate', l10n.t('Populate approved test container')],
    ['source-cleanup', l10n.t('Clean up recorded source fixture')],
    ['extract', l10n.t('Extract read-only source')],
    ['load', l10n.t('Load approved target graphs')],
    ['resume', l10n.t('Resume from confirmed checkpoint')],
    ['validate', l10n.t('Validate structural fidelity')],
    ['translate', l10n.t('Translate restricted Gremlin')],
    ['rewrite-plan', l10n.t('Review Java rewrite plan')],
    ['rewrite-apply', l10n.t('Apply approved Java rewrite to new output')],
    ['functional', l10n.t('Compare golden queries')],
    ['report', l10n.t('Evaluate readiness')],
    ['publish', l10n.t('Publish approved version')],
    ['cleanup', l10n.t('Clean up owned run graphs')],
];
const phaseLabels = (): Record<string, string> => ({
    'not-started': l10n.t('Not started'),
    running: l10n.t('Running'),
    paused: l10n.t('Paused'),
    blocked: l10n.t('Blocked'),
    failed: l10n.t('Failed'),
    succeeded: l10n.t('Succeeded'),
    cancelled: l10n.t('Cancelled'),
    stale: l10n.t('Stale: inputs changed'),
});

export function GraphMigrationWorkbench() {
    const client = useTrpcClient<GraphAppRouter>();
    const { announce } = useAnnounce();
    const [project, setProject] = useState<GraphProject | null>(null);
    const [configuration, setConfiguration] = useState('');
    const [trusted, setTrusted] = useState(false);
    const [busy, setBusy] = useState(false);
    const [diagnostic, setDiagnostic] = useState('');
    const [query, setQuery] = useState('g.V().count()');
    const [sourcePath, setSourcePath] = useState('');
    const [outputPath, setOutputPath] = useState('artifacts/rewritten/GraphRepository.java');
    const [progress, setProgress] = useState('');
    const [evidencePath, setEvidencePath] = useState('');
    const [evidencePhase, setEvidencePhase] = useState<GraphEvidencePhase>('application-build');
    const applyProject = useCallback((value: GraphProject | null) => {
        setProject(value);
        setConfiguration(value ? JSON.stringify(value, null, 2) : '');
        setBusy(Boolean(value && Object.values(value.phases).some((phase) => phase.status === 'running')));
    }, []);
    const action = useCallback(async (work: () => Promise<unknown>) => {
        setDiagnostic('');
        try {
            await work();
        } catch {
            const message = l10n.t(
                'The operation could not complete. Check the project schema, configured secret references, scoped approvals and worker artifacts.',
            );
            setDiagnostic(message);
            setBusy(false);
        }
    }, []);
    const reload = useCallback(async () => {
        const value = await client.load.query();
        setTrusted(value.trusted);
        applyProject(value.project);
    }, [client, applyProject]);
    useEffect(() => {
        void action(reload);
        const subscription = client.events.subscribe(undefined, {
            onData: (event) => {
                if (event.type === 'project') {
                    applyProject(event.project);
                    announce(l10n.t('Graph project state updated.'));
                } else if (event.type === 'diagnostic') {
                    setDiagnostic(
                        l10n.t(
                            'Worker diagnostic: {code}. Reload the project and review persisted evidence before retrying.',
                            { code: event.code },
                        ),
                    );
                    setBusy(false);
                } else if (event.message.type === 'checkpoint') {
                    const text = l10n.t('A target-confirmed checkpoint was saved.');
                    setProgress(text);
                } else if (event.message.type === 'result' || event.message.type === 'error') {
                    setProgress(l10n.t('Worker operation finished. Review the phase status and evidence.'));
                }
            },
            onError: () => {
                setDiagnostic(
                    l10n.t('The workbench connection failed. Reopen the panel and reconcile persisted project state.'),
                );
                setBusy(false);
            },
        });
        return () => subscription.unsubscribe();
    }, [client, action, reload, applyProject, announce]);
    const disabled = !trusted || !project || busy;
    const runLabel = l10n.t('Run'),
        authorizationLabel = l10n.t('Authorize');
    const run = async (operation: GraphOperation) => {
        setBusy(true);
        await client.run.mutate({ operation, query, sourcePath, outputPath });
        await reload();
    };
    return (
        <main style={{ padding: '24px', maxWidth: '1000px', margin: 'auto' }}>
            <h1>{l10n.t('Gremlin to RDF migration (Experimental)')}</h1>
            <p>
                {l10n.t(
                    'This is a separate graph migration project. Canonical RDF preserves graph identities and property occurrences; derived relationships are supplementary.',
                )}
            </p>
            <p>
                {l10n.t(
                    'No resources are provisioned. Live compatibility remains blocked without verified endpoint evidence. Synthetic reference results are not Cosmos DB compatibility evidence.',
                )}
            </p>
            {!trusted && (
                <p role="alert">
                    {l10n.t(
                        'Trust this workspace before starting workers, storing credentials or authorizing operations.',
                    )}
                </p>
            )}
            {diagnostic && <p role="alert">{diagnostic}</p>}
            <p>
                <output aria-live="polite">{progress}</output>
            </p>
            {!project ? (
                <Button
                    disabled={!trusted}
                    onClick={() => void action(async () => applyProject(await client.create.mutate()))}
                >
                    {l10n.t('Create graph migration project')}
                </Button>
            ) : (
                <>
                    <section aria-labelledby="graph-configuration">
                        <h2 id="graph-configuration">{l10n.t('Configuration and mapping')}</h2>
                        <p>
                            {l10n.t(
                                'Edit endpoint, typed identity-manifest and mapping fields in this strict project document. Use env:GRAPH_* references only; never enter credentials here. Input changes invalidate previous evidence and approvals.',
                            )}
                        </p>
                        <p>
                            {l10n.t(
                                'Source population is separate from read-only extraction. Use only a pre-existing empty dedicated test container with no concurrent writers. Review the generated identity-manifest checksum, set an RU budget and approve the exact plan before any source mutation.',
                            )}
                        </p>
                        <Field label={l10n.t('Graph project JSON')}>
                            <Textarea
                                value={configuration}
                                rows={18}
                                resize="vertical"
                                disabled={busy}
                                onChange={(_, data) => setConfiguration(data.value)}
                            />
                        </Field>
                        <Button
                            disabled={disabled}
                            onClick={() =>
                                void action(async () => {
                                    await client.save.mutate(configuration);
                                    await reload();
                                })
                            }
                        >
                            {l10n.t('Save configuration')}
                        </Button>
                        <Button disabled={disabled} onClick={() => void action(() => client.credentials.mutate())}>
                            {l10n.t('Store credentials securely')}
                        </Button>
                        <Button onClick={() => void action(reload)}>{l10n.t('Reload persisted state')}</Button>
                        {project.mapping.fidelity === 'reduced' && (
                            <Button
                                disabled={disabled}
                                onClick={() => void action(() => client.approve.mutate('reduced-fidelity'))}
                            >
                                {l10n.t('Authorize reduced fidelity')}
                            </Button>
                        )}
                        {project.consistency === 'best-effort' && (
                            <Button
                                disabled={disabled}
                                onClick={() => void action(() => client.approve.mutate('best-effort-source'))}
                            >
                                {l10n.t('Authorize best-effort source')}
                            </Button>
                        )}
                        {(project.mapping.fidelity === 'reduced' || project.consistency === 'best-effort') && (
                            <p>
                                {l10n.t(
                                    'These separately approved modes cannot establish strict cutover equivalence. Reduced source projection omits occurrence IDs/meta-properties and explicitly excluded properties.',
                                )}
                            </p>
                        )}
                    </section>
                    <section aria-labelledby="graph-review">
                        <h2 id="graph-review">{l10n.t('Query and application review')}</h2>
                        <Field label={l10n.t('Restricted Gremlin read query')}>
                            <Textarea
                                value={query}
                                rows={3}
                                disabled={busy}
                                onChange={(_, data) => setQuery(data.value)}
                            />
                        </Field>
                        <Field label={l10n.t('Workspace-relative Java source path')}>
                            <Input
                                value={sourcePath}
                                disabled={busy}
                                onChange={(_, data) => setSourcePath(data.value)}
                            />
                        </Field>
                        <Field label={l10n.t('Run-relative new Java output path')}>
                            <Input
                                value={outputPath}
                                disabled={busy}
                                onChange={(_, data) => setOutputPath(data.value)}
                            />
                        </Field>
                        <p>
                            {l10n.t(
                                'Rewrite plans never overwrite source files. Review and authorize the exact plan and a new Java path under artifacts/rewritten/, then apply it to that separate output. Build and compare both applications before cutover.',
                            )}
                        </p>
                        <p>
                            {l10n.t(
                                'Fixture execution requires a current canonical-derived catalog and explicit approval for the exact catalog checksum and application write/delete graph scopes. It never populates Cosmos DB.',
                            )}
                        </p>
                    </section>
                    <section aria-labelledby="graph-phases">
                        <h2 id="graph-phases">{l10n.t('Phases, approvals and evidence')}</h2>
                        <Field label={l10n.t('Run-relative reviewed report path')}>
                            <Input
                                value={evidencePath}
                                disabled={busy}
                                onChange={(_, data) => setEvidencePath(data.value)}
                            />
                        </Field>
                        <Field label={l10n.t('Imported evidence phase')}>
                            <Select
                                value={evidencePhase}
                                disabled={busy}
                                onChange={(_, data) => {
                                    const phase = graphEvidencePhases.find((value) => value === data.value);
                                    if (phase) setEvidencePhase(phase);
                                    else setDiagnostic(l10n.t('Unsupported evidence phase.'));
                                }}
                            >
                                <option value="application-build">
                                    {l10n.t('Before/after application build and equivalence')}
                                </option>
                                <option value="crud">{l10n.t('CRUD and execution-failure atomicity')}</option>
                                <option value="test-load">{l10n.t('Measured streaming load')}</option>
                                <option value="test-soak">{l10n.t('Measured mixed soak')}</option>
                                <option value="dependency-audit">{l10n.t('Dependency audit')}</option>
                            </Select>
                        </Field>
                        <Button
                            disabled={disabled || !evidencePath}
                            onClick={() =>
                                void action(async () => {
                                    await client.importEvidence.mutate({
                                        relativePath: evidencePath,
                                        phase: evidencePhase,
                                    });
                                    await reload();
                                })
                            }
                        >
                            {l10n.t('Import reviewed evidence')}
                        </Button>
                        <Button disabled={!trusted || !busy} onClick={() => void action(() => client.pause.mutate())}>
                            {l10n.t('Pause after current batch')}
                        </Button>
                        <Button disabled={!trusted || !busy} onClick={() => void action(() => client.cancel.mutate())}>
                            {l10n.t('Cancel safely')}
                        </Button>
                        <table style={{ width: '100%', textAlign: 'left' }}>
                            <caption>{l10n.t('Persistent graph migration phases')}</caption>
                            <thead>
                                <tr>
                                    <th scope="col">{l10n.t('Phase')}</th>
                                    <th scope="col">{l10n.t('Status')}</th>
                                    <th scope="col">{l10n.t('Actions and evidence')}</th>
                                </tr>
                            </thead>
                            <tbody>
                                {operations().map(([operation, label]) => (
                                    <tr key={operation}>
                                        <th scope="row">{label}</th>
                                        <td>{phaseLabels()[project.phases[operation]?.status ?? 'not-started']}</td>
                                        <td>
                                            <Button
                                                disabled={disabled}
                                                aria-label={l10n.t('{action} for {phase}', {
                                                    action: runLabel,
                                                    phase: label,
                                                })}
                                                onClick={() => void action(() => run(operation))}
                                            >
                                                {runLabel}
                                            </Button>
                                            {(operation === 'load' ||
                                                operation === 'resume' ||
                                                operation === 'publish' ||
                                                operation === 'cleanup' ||
                                                operation === 'source-populate' ||
                                                operation === 'source-cleanup') && (
                                                <Button
                                                    disabled={disabled}
                                                    aria-label={l10n.t('{action} for {phase}', {
                                                        action: authorizationLabel,
                                                        phase: label,
                                                    })}
                                                    onClick={() => void action(() => client.approve.mutate(operation))}
                                                >
                                                    {authorizationLabel}
                                                </Button>
                                            )}
                                            {(operation === 'rewrite-apply' || operation === 'fixture-test') && (
                                                <Button
                                                    disabled={disabled}
                                                    aria-label={l10n.t('{action} for {phase}', {
                                                        action: authorizationLabel,
                                                        phase: label,
                                                    })}
                                                    onClick={() =>
                                                        void action(() =>
                                                            client.approve.mutate({ operation, outputPath }),
                                                        )
                                                    }
                                                >
                                                    {authorizationLabel}
                                                </Button>
                                            )}
                                            {project.phases[operation]?.evidence.map((file, index) => {
                                                const evidenceLabel = l10n.t('Evidence {number}', {
                                                    number: index + 1,
                                                });
                                                return (
                                                    <Button
                                                        key={file}
                                                        onClick={() =>
                                                            void action(() => client.openArtifact.mutate(file))
                                                        }
                                                        aria-label={l10n.t('{action} for {phase}', {
                                                            action: evidenceLabel,
                                                            phase: label,
                                                        })}
                                                    >
                                                        {evidenceLabel}
                                                    </Button>
                                                );
                                            })}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </section>
                </>
            )}
        </main>
    );
}
