/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import {
    Badge,
    Button,
    Field,
    Input,
    Select,
    Textarea,
    makeStyles,
    tokens,
    useAnnounce,
} from '@fluentui/react-components';
import {
    ArrowUploadRegular,
    DatabaseRegular,
    PlayRegular,
    DocumentSearchRegular,
    CheckmarkCircleRegular,
} from '@fluentui/react-icons';
import { useTrpcClient } from '@microsoft/vscode-ext-webview/react';
import * as l10n from '@vscode/l10n';
import { useEffect, useState } from 'react';
import { type AgeRouter } from '../../../panels/ageMigration/ageRouter';
import { type AgeOperation, type AgeProject } from '../../../services/ageMigration/contracts';

const useStyles = makeStyles({
    root: {
        maxWidth: '1050px',
        marginInline: 'auto',
        padding: tokens.spacingHorizontalL,
        color: tokens.colorNeutralForeground1,
    },
    cards: {
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit,minmax(300px,1fr))',
        gap: tokens.spacingHorizontalL,
    },
    card: {
        padding: tokens.spacingHorizontalL,
        backgroundColor: tokens.colorNeutralBackground2,
        borderRadius: tokens.borderRadiusLarge,
        border: `1px solid ${tokens.colorNeutralStroke2}`,
        borderTopWidth: '4px',
        borderTopStyle: 'solid',
        borderTopColor: tokens.colorBrandStroke1,
        '& h2': { display: 'flex', alignItems: 'center', gap: tokens.spacingHorizontalS },
    },
    actions: {
        display: 'flex',
        gap: tokens.spacingHorizontalS,
        flexWrap: 'wrap',
        marginBlock: tokens.spacingVerticalM,
    },
    advanced: { marginBlock: tokens.spacingVerticalL, color: tokens.colorNeutralForeground2 },
    result: { whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: '350px', overflowY: 'auto' },
});
function initialProject(): AgeProject {
    return {
        schemaVersion: 1,
        migrationKind: 'cosmos-gremlin-to-age',
        runId: crypto.randomUUID(),
        source: null,
        target: null,
        consistency: 'quiesced',
        limits: { batchRecords: 50, batchBytes: 1048576, timeoutSeconds: 30, maximumOperationSeconds: 300 },
        approvals: [],
    };
}

export function AgeMigrationWorkbench() {
    const styles = useStyles(),
        client = useTrpcClient<AgeRouter>(),
        { announce } = useAnnounce();
    const [project, setProject] = useState<AgeProject>(initialProject);
    const [saved, setSaved] = useState(false),
        [busy, setBusy] = useState(false);
    const [status, setStatus] = useState(''),
        [error, setError] = useState(''),
        [result, setResult] = useState('');
    const [host, setHost] = useState(''),
        [database, setDatabase] = useState(''),
        [container, setContainer] = useState('');
    const [partition, setPartition] = useState('/partitionKey');
    const [pgHost, setPgHost] = useState('localhost'),
        [pgDatabase, setPgDatabase] = useState('postgres');
    const [pgUser, setPgUser] = useState(''),
        [graph, setGraph] = useState('gremlin_migration');
    const [canonical, setCanonical] = useState('canonical.jsonl'),
        [query, setQuery] = useState('g.V().count()');
    const [configuration, setConfiguration] = useState('');
    const apply = (p: AgeProject) => {
        setProject(p);
        setSaved(true);
        setConfiguration(JSON.stringify(p, null, 2));
        if (p.source) {
            setHost(p.source.host);
            setDatabase(p.source.database);
            setContainer(p.source.container);
            setPartition(p.source.partitionKeyPath);
        }
        if (p.target) {
            setPgHost(p.target.host);
            setPgDatabase(p.target.database);
            setPgUser(p.target.username);
            setGraph(p.target.graph);
        }
    };
    const action = async (work: () => Promise<unknown>) => {
        setError('');
        try {
            await work();
        } catch {
            const message = l10n.t(
                'The AGE action could not complete. Check configuration, artifact paths, scoped approval and the host diagnostic.',
            );
            setError(message);
            announce(message);
            setBusy(false);
        }
    };
    useEffect(() => {
        void client.load
            .query()
            .then((value) => {
                if (value) apply(JSON.parse(value) as AgeProject);
            })
            .catch(() => {
                setError(l10n.t('AGE project could not be opened. Check the owned project directory.'));
            });
        const subscription = client.events.subscribe(undefined, {
            onData: (event) => {
                if (event.type === 'diagnostic') {
                    setBusy(false);
                    setError(
                        l10n.t('AGE worker stopped: {code}. Review the confirmed checkpoint.', { code: event.code }),
                    );
                    return;
                }
                setStatus(event.type);
                setResult(JSON.stringify(event.payload, null, 2));
                if (event.type !== 'progress') {
                    setBusy(false);
                    announce(
                        event.type === 'result'
                            ? l10n.t('AGE operation finished. Review the result.')
                            : l10n.t('AGE operation blocked. Review the diagnostic.'),
                    );
                }
            },
            onError: () => {
                setBusy(false);
                setError(l10n.t('AGE event connection was interrupted. Review the confirmed checkpoint.'));
            },
        });
        return () => subscription.unsubscribe();
    }, [client, announce]);
    const save = async () => {
        const p: AgeProject = {
            ...project,
            source:
                host && database && container
                    ? {
                          host,
                          database,
                          container,
                          partitionKeyPath: partition,
                          authenticationRef: 'env:AGE_SOURCE_KEY',
                          scope: `cosmos:${host}/${database}/${container}`,
                      }
                    : null,
            target:
                pgHost && pgDatabase && pgUser
                    ? {
                          host: pgHost,
                          port: project.target?.port ?? 5432,
                          database: pgDatabase,
                          username: pgUser,
                          authenticationRef: 'env:AGE_TARGET_PASSWORD',
                          graph,
                          ledgerSchema:
                              project.target?.ledgerSchema ?? `g2a_${project.runId.replaceAll('-', '').slice(0, 12)}`,
                          sslMode: 'VerifyFull',
                      }
                    : null,
            approvals: [],
        };
        await client.save.mutate(JSON.stringify(p));
        apply(p);
        announce(l10n.t('AGE configuration saved.'));
    };
    const run = (operation: AgeOperation, input: string, output = '') =>
        action(async () => {
            setBusy(true);
            await client.run.mutate({ operation, input, output });
        });
    const field = (label: string, value: string, set: (value: string) => void, required = false) => (
        <Field label={label} required={required}>
            <Input value={value} aria-required={required} disabled={busy} onChange={(_, data) => set(data.value)} />
        </Field>
    );
    const buttonsDisabled = busy || !saved;
    return (
        <main className={styles.root}>
            <h1>{l10n.t('Move your Gremlin graph to PostgreSQL')}</h1>
            <Badge appearance="tint" color="brand">
                {l10n.t('Apache AGE • Experimental')}
            </Badge>
            <p>
                {l10n.t(
                    'Keep graph connections in AGE and complete identities, property occurrences and provenance in a companion ledger. Review supported Gremlin reads as Cypher before switching your application.',
                )}
            </p>
            <div className={styles.cards}>
                <section className={styles.card}>
                    <h2>
                        <DatabaseRegular aria-hidden />
                        {l10n.t('1. Connect existing databases')}
                    </h2>
                    {field(l10n.t('Cosmos Gremlin hostname'), host, setHost, true)}
                    {field(l10n.t('Source database'), database, setDatabase, true)}
                    {field(l10n.t('Source graph/container'), container, setContainer, true)}
                    {field(l10n.t('Partition key path'), partition, setPartition, true)}
                    {field(l10n.t('PostgreSQL hostname'), pgHost, setPgHost, true)}
                    {field(l10n.t('PostgreSQL database'), pgDatabase, setPgDatabase, true)}
                    {field(l10n.t('PostgreSQL username'), pgUser, setPgUser, true)}
                    {field(l10n.t('Existing dedicated AGE graph'), graph, setGraph, true)}
                    <Field label={l10n.t('Source consistency')} required>
                        <Select
                            value={project.consistency}
                            disabled={busy}
                            onChange={(_, d) =>
                                setProject({ ...project, consistency: d.value as AgeProject['consistency'] })
                            }
                        >
                            <option value="quiesced">{l10n.t('Writes paused')}</option>
                            <option value="immutable">{l10n.t('Immutable source')}</option>
                        </Select>
                    </Field>
                    <div className={styles.actions}>
                        <Button appearance="primary" disabled={busy} onClick={() => void action(save)}>
                            {l10n.t('Save configuration')}
                        </Button>
                        <Button
                            disabled={buttonsDisabled}
                            onClick={() => void action(() => client.credentials.mutate())}
                        >
                            {l10n.t('Set credentials securely')}
                        </Button>
                    </div>
                    <p>
                        {l10n.t(
                            'Your operator installs AGE and creates an empty dedicated graph. TLS certificate verification stays enabled; this extension provisions nothing.',
                        )}
                    </p>
                </section>
                <section className={styles.card}>
                    <h2>
                        <ArrowUploadRegular aria-hidden />
                        {l10n.t('2. Prepare and transfer')}
                    </h2>
                    {field(l10n.t('Canonical input file'), canonical, setCanonical, true)}
                    <p>
                        {l10n.t(
                            'Import reviewed inventory and fresh source observations before extraction. Unknown identity, meta-property or numeric fidelity blocks transfer instead of guessing.',
                        )}
                    </p>
                    <div className={styles.actions}>
                        <Button
                            disabled={buttonsDisabled}
                            onClick={() => void action(() => client.importArtifact.mutate('inventory.json'))}
                        >
                            {l10n.t('Import source inventory')}
                        </Button>
                        <Button
                            disabled={buttonsDisabled}
                            onClick={() => void action(() => client.importArtifact.mutate('source-compatibility.json'))}
                        >
                            {l10n.t('Import source observations')}
                        </Button>
                        <Button
                            disabled={buttonsDisabled}
                            onClick={() => void run('extract', 'inventory.json', canonical)}
                        >
                            {l10n.t('Extract read-only source')}
                        </Button>
                        <Button
                            disabled={buttonsDisabled}
                            onClick={() => void action(() => client.importArtifact.mutate('canonical.jsonl'))}
                        >
                            {l10n.t('Import canonical input')}
                        </Button>
                    </div>
                    <div className={styles.actions}>
                        <Button
                            disabled={buttonsDisabled || !project.target}
                            onClick={() =>
                                void action(async () => {
                                    setBusy(true);
                                    await client.authorize.mutate(canonical);
                                    setBusy(false);
                                })
                            }
                        >
                            {l10n.t('Review and authorize load')}
                        </Button>
                        <Button
                            appearance="primary"
                            icon={<PlayRegular />}
                            disabled={buttonsDisabled || !project.target}
                            onClick={() => void run('load', canonical, 'checkpoint.json')}
                        >
                            {l10n.t('Load approved graph')}
                        </Button>
                        <Button
                            disabled={buttonsDisabled || !project.target}
                            onClick={() => void run('resume', canonical, 'checkpoint.json')}
                        >
                            {l10n.t('Resume confirmed batches')}
                        </Button>
                        <Button disabled={!busy} onClick={() => void action(() => client.stop.mutate())}>
                            {l10n.t('Pause safely')}
                        </Button>
                    </div>
                </section>
                <section className={styles.card}>
                    <h2>
                        <CheckmarkCircleRegular aria-hidden />
                        {l10n.t('3. Review and validate')}
                    </h2>
                    <Field label={l10n.t('Restricted Gremlin read')}>
                        <Textarea value={query} onChange={(_, d) => setQuery(d.value)} resize="vertical" />
                    </Field>
                    <div className={styles.actions}>
                        <Button disabled={buttonsDisabled} onClick={() => void run('translate', query)}>
                            {l10n.t('Preview Cypher')}
                        </Button>
                        <Button disabled={buttonsDisabled || !project.target} onClick={() => void run('query', query)}>
                            {l10n.t('Read AGE results')}
                        </Button>
                        <Button
                            disabled={buttonsDisabled || !project.target}
                            onClick={() => void run('validate', canonical)}
                        >
                            {l10n.t('Validate graph and ledger')}
                        </Button>
                        <Button
                            disabled={buttonsDisabled}
                            onClick={() => void action(() => client.artifact.mutate('last-result.json'))}
                        >
                            {l10n.t('Open result artifact')}
                        </Button>
                    </div>
                    <output aria-live="polite">
                        {status === 'progress' ? l10n.t('AGE operation running within configured limits.') : ''}
                    </output>
                    {error && <p role="alert">{error}</p>}
                    <pre className={styles.result} aria-label={l10n.t('AGE operation result')}>
                        {result}
                    </pre>
                </section>
            </div>
            <details className={styles.advanced}>
                <summary>{l10n.t('Optional: offline fixtures, C# assessment and advanced settings')}</summary>
                <div className={styles.actions}>
                    <Button disabled={buttonsDisabled} onClick={() => void run('fixture', '', 'fixture.jsonl')}>
                        {l10n.t('Create small offline fixture')}
                    </Button>
                    <Button
                        icon={<DocumentSearchRegular />}
                        disabled={buttonsDisabled}
                        onClick={() => void action(() => client.importArtifact.mutate('app.cs'))}
                    >
                        {l10n.t('Import C# source for review')}
                    </Button>
                    <Button disabled={buttonsDisabled} onClick={() => void run('assess', 'app.cs')}>
                        {l10n.t('Assess literal C# queries')}
                    </Button>
                    <Button
                        disabled={buttonsDisabled}
                        onClick={() => void run('rewrite-plan', 'app.cs', 'rewrite-plan.json')}
                    >
                        {l10n.t('Create C# rewrite plan')}
                    </Button>
                    <Button
                        disabled={buttonsDisabled}
                        onClick={() => void action(() => client.artifact.mutate('rewrite-plan.json'))}
                    >
                        {l10n.t('Review C# rewrite plan')}
                    </Button>
                    <Button
                        disabled={buttonsDisabled}
                        onClick={() =>
                            void action(() =>
                                client.authorizeRewrite.mutate({ input: 'app.cs', output: 'rewritten.cs' }),
                            )
                        }
                    >
                        {l10n.t('Authorize new C# output')}
                    </Button>
                    <Button
                        disabled={buttonsDisabled}
                        onClick={() => void run('rewrite-apply', 'app.cs', 'rewritten.cs')}
                    >
                        {l10n.t('Apply reviewed C# rewrite')}
                    </Button>
                </div>
                <Field label={l10n.t('Advanced AGE project JSON')}>
                    <Textarea value={configuration} resize="vertical" onChange={(_, d) => setConfiguration(d.value)} />
                </Field>
                <Button
                    disabled={buttonsDisabled}
                    onClick={() =>
                        void action(async () => {
                            await client.save.mutate(configuration);
                            apply(JSON.parse(configuration) as AgeProject);
                        })
                    }
                >
                    {l10n.t('Save advanced configuration')}
                </Button>
            </details>
        </main>
    );
}
