/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { Button, Field, Input, Select, makeStyles, mergeClasses, tokens } from '@fluentui/react-components';
import { CheckmarkCircleRegular, DatabaseRegular } from '@fluentui/react-icons';
import * as l10n from '@vscode/l10n';
import { useEffect, useMemo, useRef, useState } from 'react';
import { type GraphProject } from '../../../services/graphMigration/contracts';
import {
    formatDiagnostic,
    hostDiagnostic,
    GraphDiagnosticError,
    type SafeDiagnostic,
} from '../../../services/graphMigration/diagnostics';
import { type ConnectionSetup, type SetupSuggestions } from '../../../services/graphMigration/setup';

const useStyles = makeStyles({
    root: { display: 'grid', gap: tokens.spacingVerticalM },
    intro: { maxWidth: '72ch', margin: 0, color: tokens.colorNeutralForeground2 },
    connectionGrid: {
        display: 'grid',
        gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
        gap: tokens.spacingHorizontalM,
        '@media (max-width: 700px)': { gridTemplateColumns: 'minmax(0, 1fr)' },
    },
    connectionCard: {
        display: 'grid',
        alignContent: 'start',
        gap: tokens.spacingVerticalS,
        minWidth: 0,
        padding: tokens.spacingVerticalM,
        border: `1px solid ${tokens.colorNeutralStroke2}`,
        borderRadius: tokens.borderRadiusMedium,
        backgroundColor: tokens.colorNeutralBackground2,
    },
    sourceCard: { borderTopWidth: '3px', borderTopStyle: 'solid', borderTopColor: tokens.colorBrandStroke1 },
    targetCard: { borderTopWidth: '3px', borderTopStyle: 'solid', borderTopColor: tokens.colorPaletteGreenBorder2 },
    cardTitle: {
        display: 'flex',
        alignItems: 'center',
        gap: tokens.spacingHorizontalS,
        margin: 0,
        fontSize: tokens.fontSizeBase400,
        lineHeight: tokens.lineHeightBase400,
    },
    required: {
        display: 'inline-flex',
        alignItems: 'center',
        gap: tokens.spacingHorizontalXXS,
        width: 'fit-content',
        padding: `2px ${tokens.spacingHorizontalS}`,
        borderRadius: tokens.borderRadiusSmall,
        backgroundColor: tokens.colorBrandBackground2,
        color: tokens.colorBrandForeground2,
        fontSize: tokens.fontSizeBase200,
        fontWeight: tokens.fontWeightSemibold,
    },
    sourceIcon: { color: tokens.colorPaletteBlueForeground2 },
    targetIcon: { color: tokens.colorPaletteGreenForeground2 },
    notice: {
        padding: `${tokens.spacingVerticalS} ${tokens.spacingHorizontalM}`,
        borderLeftWidth: '3px',
        borderLeftStyle: 'solid',
        borderLeftColor: tokens.colorPaletteYellowBorder2,
        backgroundColor: tokens.colorPaletteYellowBackground1,
        color: tokens.colorNeutralForeground1,
    },
    saveRow: { display: 'flex', flexWrap: 'wrap', gap: tokens.spacingHorizontalS, justifyContent: 'flex-start' },
});

export function GraphConnectionSetup({
    project,
    suggestions,
    disabled,
    save,
    onDirtyChange,
}: {
    project: GraphProject;
    suggestions?: SetupSuggestions;
    disabled: boolean;
    save(value: ConnectionSetup): Promise<void | string | SafeDiagnostic>;
    onDirtyChange?(dirty: boolean): void;
}) {
    const styles = useStyles();
    const savedConnection = useMemo<ConnectionSetup>(
        () => ({
            host: project.source?.host ?? suggestions?.values.host ?? '',
            database: project.source?.database ?? suggestions?.values.database ?? '',
            container: project.source?.container ?? suggestions?.values.container ?? '',
            partitionKeyPath: project.source?.partitionKeyPath ?? suggestions?.values.partitionKeyPath ?? '/pk',
            datasetUrl: project.target?.datasetUrl ?? suggestions?.values.datasetUrl ?? '',
            consistency: project.source ? project.consistency : '',
            sourceMode: project.source ? (project.source.sourceMode ?? 'gremlin') : 'cosmos-documents',
            documentEndpoint: project.source?.documentEndpoint ?? suggestions?.values.documentEndpoint ?? '',
            documentProfile: project.source?.documentProfile ?? 'auto-v1',
            documentFidelity: project.mapping.fidelity,
            documentRuBudget: project.source?.ruBudget ?? '',
            documentRuPerSecond: project.source?.ruPerSecond ?? '',
        }),
        [project.source, project.target, project.consistency, project.mapping.fidelity, suggestions],
    );
    const [values, setValues] = useState<ConnectionSetup>(savedConnection);
    const [feedback, setFeedback] = useState<{ failed: boolean; text: string }>();
    const feedbackRef = useRef<HTMLDivElement>(null);
    useEffect(() => {
        if (feedback?.failed) {
            feedbackRef.current?.scrollIntoView?.({ block: 'center' });
            feedbackRef.current?.focus();
        }
    }, [feedback]);
    const submit = async (restart: boolean) => {
        setFeedback(undefined);
        try {
            const result = await save(restart ? { ...values, restart: true } : values);
            if (result && typeof result !== 'string') setFeedback({ failed: true, text: formatDiagnostic(result) });
            else if (typeof result === 'string') setFeedback({ failed: false, text: result });
        } catch {
            setFeedback({
                failed: true,
                text: formatDiagnostic(
                    hostDiagnostic(new GraphDiagnosticError('SETUP_SAVE_FAILED', 'connection-settings')),
                ),
            });
        }
    };
    const savedValues = JSON.stringify(savedConnection);
    const previousSavedValues = useRef(savedValues);
    useEffect(() => {
        if (previousSavedValues.current !== savedValues) {
            previousSavedValues.current = savedValues;
            setValues(savedConnection);
        }
    }, [savedConnection, savedValues]);
    const dirty = Object.entries(savedConnection).some(
        ([key, value]) => values[key as keyof ConnectionSetup] !== value,
    );
    useEffect(() => {
        onDirtyChange?.(dirty);
    }, [dirty, onDirtyChange]);
    const fields: ['host' | 'database' | 'container' | 'partitionKeyPath' | 'datasetUrl', string, string][] = [
        ['host', l10n.t('Gremlin endpoint'), l10n.t('For example, my-account.gremlin.cosmos.azure.com')],
        ['database', l10n.t('Cosmos DB database'), ''],
        ['container', l10n.t('Gremlin graph (container)'), ''],
        [
            'partitionKeyPath',
            l10n.t('Partition key path'),
            l10n.t('Default: /pk. Confirm the path used by your graph.'),
        ],
        [
            'datasetUrl',
            l10n.t('Fuseki dataset URL'),
            l10n.t('For example, http://localhost:3030/my-dataset. Query, update and data URLs are filled in for you.'),
        ],
    ];
    return (
        <div className={styles.root}>
            <p className={styles.intro}>
                {l10n.t(
                    'Confirm both existing endpoints. Suggested local values are not verified; connections happen only when you run an operation.',
                )}
            </p>
            {suggestions?.notices.map((notice, index) => (
                <p className={styles.notice} key={index}>
                    <output>{notice}</output>
                </p>
            ))}
            <div className={styles.connectionGrid}>
                <section
                    className={mergeClasses(styles.connectionCard, styles.sourceCard)}
                    aria-labelledby="graph-source-title"
                >
                    <h3 className={styles.cardTitle} id="graph-source-title">
                        <DatabaseRegular aria-hidden={true} className={styles.sourceIcon} />
                        {l10n.t('Cosmos DB graph source')}
                    </h3>
                    <span className={styles.required}>
                        <CheckmarkCircleRegular aria-hidden={true} />
                        {l10n.t('Required')}
                    </span>
                    <Field label={l10n.t('Source read mode')} required>
                        <Select
                            value={values.sourceMode}
                            disabled={disabled}
                            onChange={(_, data) =>
                                setValues((current) => ({
                                    ...current,
                                    sourceMode: data.value === 'cosmos-documents' ? 'cosmos-documents' : 'gremlin',
                                }))
                            }
                        >
                            <option value="cosmos-documents">
                                {l10n.t('Cosmos .NET SDK document feed (default)')}
                            </option>
                            <option value="gremlin">{l10n.t('Gremlin traversal (explicit secondary mode)')}</option>
                        </Select>
                    </Field>
                    <p className={styles.intro}>
                        {l10n.t(
                            'Modes never fall back automatically. Switching modes archives the prior run, preserves its inventory and journals, and requires fresh mode-bound evidence and approvals. Existing legacy runs remain Gremlin until you explicitly switch.',
                        )}
                    </p>
                    {values.sourceMode === 'cosmos-documents' && (
                        <>
                            <Field
                                required
                                label={l10n.t('Cosmos .NET SDK document endpoint')}
                                hint={l10n.t(
                                    'Copy the account .NET SDK URI, for example https://my-account.documents.azure.com:443/. The same stored source key is used; do not enter a key here.',
                                )}
                            >
                                <Input
                                    value={values.documentEndpoint ?? ''}
                                    disabled={disabled}
                                    onChange={(_, data) =>
                                        setValues((current) => ({ ...current, documentEndpoint: data.value }))
                                    }
                                />
                            </Field>
                            <Field
                                required
                                label={l10n.t('JSON property layout')}
                                hint={l10n.t(
                                    'This means how graph properties are stored inside each JSON document, not its indentation or file format. Automatic detection checks every vertex during the approved read: either arrays of property records with IDs, or direct scalar fields. Unsupported or mixed fields in a vertex stop conversion; no source calls happen while editing.',
                                )}
                            >
                                <Select
                                    value={values.documentProfile}
                                    disabled={disabled}
                                    onChange={(_, data) =>
                                        setValues((current) => ({
                                            ...current,
                                            documentProfile:
                                                data.value === 'flat-v1'
                                                    ? 'flat-v1'
                                                    : data.value === 'property-bags-v1'
                                                      ? 'property-bags-v1'
                                                      : 'auto-v1',
                                        }))
                                    }
                                >
                                    <option value="auto-v1">{l10n.t('Detect supported layouts automatically')}</option>
                                    <option value="property-bags-v1">
                                        {l10n.t('Property records: name: [{id, _value, _meta}]')}
                                    </option>
                                    <option value="flat-v1">
                                        {l10n.t('Direct values (FlatSchema): name: "Alice"')}
                                    </option>
                                </Select>
                            </Field>
                            <Field
                                required
                                label={l10n.t('When source details cannot be verified')}
                                hint={l10n.t(
                                    'Fidelity means how completely the target preserves the source graph. JSON can contain a value without revealing its original Gremlin numeric type, property ID or metadata. Detection can report missing details, but cannot recreate them or decide whether you accept that limitation. Observed-values mode preserves raw documents and available values; it requires a separate approval and does not prove exact Gremlin equivalence.',
                                )}
                            >
                                <Select
                                    value={values.documentFidelity}
                                    disabled={disabled}
                                    onChange={(_, data) =>
                                        setValues((current) => ({
                                            ...current,
                                            documentFidelity: data.value === 'strict' ? 'strict' : 'reduced',
                                        }))
                                    }
                                >
                                    <option value="strict">
                                        {l10n.t('Stop if required source details are missing')}
                                    </option>
                                    <option value="reduced">
                                        {l10n.t('Migrate observed values only (approval required)')}
                                    </option>
                                </Select>
                            </Field>
                            <Field
                                label={l10n.t('Document-source total-run RU allowance')}
                                hint={l10n.t(
                                    'Set an explicit positive total allowance before document-feed operations. This is not provisioned throughput; changing it invalidates evidence and does not reset an existing journal.',
                                )}
                            >
                                <Input
                                    value={values.documentRuBudget ?? ''}
                                    disabled={disabled}
                                    onChange={(_, data) =>
                                        setValues((current) => ({ ...current, documentRuBudget: data.value }))
                                    }
                                />
                            </Field>
                            <Field
                                label={l10n.t('Document-source pacing (RU/s)')}
                                hint={l10n.t(
                                    'Set a separate positive pacing rate. A single request may cost more than one second of this rate. No source throughput or resources are changed.',
                                )}
                            >
                                <Input
                                    value={values.documentRuPerSecond ?? ''}
                                    disabled={disabled}
                                    onChange={(_, data) =>
                                        setValues((current) => ({ ...current, documentRuPerSecond: data.value }))
                                    }
                                />
                            </Field>
                        </>
                    )}
                    {fields
                        .filter(
                            ([key]) =>
                                key !== 'datasetUrl' && (key !== 'host' || values.sourceMode !== 'cosmos-documents'),
                        )
                        .map(([key, label, hint]) => (
                            <Field
                                key={key}
                                required={key !== 'partitionKeyPath'}
                                label={label}
                                hint={[
                                    hint,
                                    suggestions?.origins[key] && !project.source && !project.target
                                        ? l10n.t('Suggested from {file}. Review before saving.', {
                                              file: suggestions.origins[key],
                                          })
                                        : '',
                                ]
                                    .filter(Boolean)
                                    .join(' ')}
                            >
                                <Input
                                    value={values[key]}
                                    disabled={disabled}
                                    onChange={(_, data) => setValues((current) => ({ ...current, [key]: data.value }))}
                                />
                            </Field>
                        ))}
                    <Field
                        required
                        label={l10n.t('Source consistency')}
                        hint={l10n.t(
                            'Choose how you will keep the graph stable during extraction. This cannot be detected automatically.',
                        )}
                    >
                        <Select
                            value={values.consistency}
                            disabled={disabled}
                            onChange={(_, data) =>
                                setValues((current) => ({
                                    ...current,
                                    consistency: data.value as ConnectionSetup['consistency'],
                                }))
                            }
                        >
                            <option value="">{l10n.t('Choose before using a live source')}</option>
                            <option value="quiesced">{l10n.t('I will pause source writes during extraction')}</option>
                            <option value="immutable">{l10n.t('This source is an immutable snapshot')}</option>
                            <option value="best-effort">
                                {l10n.t('Source may change (best-effort; separate approval required)')}
                            </option>
                        </Select>
                    </Field>
                </section>
                <section
                    className={mergeClasses(styles.connectionCard, styles.targetCard)}
                    aria-labelledby="graph-target-title"
                >
                    <h3 className={styles.cardTitle} id="graph-target-title">
                        <DatabaseRegular aria-hidden={true} className={styles.targetIcon} />
                        {l10n.t('Jena / Fuseki RDF target')}
                    </h3>
                    <span className={styles.required}>
                        <CheckmarkCircleRegular aria-hidden={true} />
                        {l10n.t('Required')}
                    </span>
                    {fields
                        .filter(([key]) => key === 'datasetUrl')
                        .map(([key, label, hint]) => (
                            <Field
                                key={key}
                                required
                                label={label}
                                hint={[
                                    hint,
                                    suggestions?.origins[key] && !project.source && !project.target
                                        ? l10n.t('Suggested from {file}. Review before saving.', {
                                              file: suggestions.origins[key],
                                          })
                                        : '',
                                ]
                                    .filter(Boolean)
                                    .join(' ')}
                            >
                                <Input
                                    value={values[key]}
                                    disabled={disabled}
                                    onChange={(_, data) => setValues((current) => ({ ...current, [key]: data.value }))}
                                />
                            </Field>
                        ))}
                    <p className={styles.intro}>
                        {l10n.t(
                            'Use the existing dataset path, for example http://localhost:3030/my-dataset, not just the server address. Query, update and data paths are derived; custom endpoints remain available in advanced settings. An operator must create or mount a missing dataset separately.',
                        )}
                    </p>
                </section>
            </div>
            <p className={styles.intro}>
                {l10n.t(
                    'Saving creates an empty identity inventory if needed. Enumerate and review source identities before extraction; an empty inventory is not ready.',
                )}
            </p>
            <div className={styles.saveRow}>
                <Button disabled={disabled} onClick={() => void submit(false)}>
                    {l10n.t('Save connection settings')}
                </Button>
                <Button disabled={disabled} onClick={() => void submit(true)}>
                    {l10n.t('Save settings and restart migration')}
                </Button>
            </div>
            {feedback && (
                <div ref={feedbackRef} tabIndex={-1} role={feedback.failed ? 'alert' : undefined}>
                    {feedback.failed ? feedback.text : <output>{feedback.text}</output>}
                </div>
            )}
            <p className={styles.intro}>
                {l10n.t(
                    'Restart uses the settings shown above, archives the previous run and begins read-only connection checks at Step 1. Its inventory, journals and target data are not deleted. The new run gets a fresh inventory and requires new approvals; no old continuation is resumed.',
                )}
            </p>
            {dirty && (
                <output>
                    {l10n.t('These edits are not saved. Save them or restart before continuing migration steps.')}
                </output>
            )}
        </div>
    );
}
