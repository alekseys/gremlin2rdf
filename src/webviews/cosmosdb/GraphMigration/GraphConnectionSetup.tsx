/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { Button, Field, Input, Select, makeStyles, mergeClasses, tokens } from '@fluentui/react-components';
import { CheckmarkCircleRegular, DatabaseRegular } from '@fluentui/react-icons';
import * as l10n from '@vscode/l10n';
import { useEffect, useState } from 'react';
import { type GraphProject } from '../../../services/graphMigration/contracts';
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
    saveRow: { display: 'flex', justifyContent: 'flex-start' },
});

export function GraphConnectionSetup({
    project,
    suggestions,
    disabled,
    save,
}: {
    project: GraphProject;
    suggestions?: SetupSuggestions;
    disabled: boolean;
    save(value: ConnectionSetup): Promise<void>;
}) {
    const styles = useStyles();
    const [values, setValues] = useState<ConnectionSetup>({
        host: '',
        database: '',
        container: '',
        partitionKeyPath: '/pk',
        datasetUrl: '',
        consistency: '',
    });
    useEffect(() => {
        setValues({
            host: project.source?.host ?? suggestions?.values.host ?? '',
            database: project.source?.database ?? suggestions?.values.database ?? '',
            container: project.source?.container ?? suggestions?.values.container ?? '',
            partitionKeyPath: project.source?.partitionKeyPath ?? suggestions?.values.partitionKeyPath ?? '/pk',
            datasetUrl: project.target?.datasetUrl ?? suggestions?.values.datasetUrl ?? '',
            consistency: project.source && project.consistency !== 'best-effort' ? project.consistency : '',
        });
    }, [project, suggestions]);
    const fields: [keyof Omit<ConnectionSetup, 'consistency'>, string, string][] = [
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
                        {l10n.t('Gremlin source')}
                    </h3>
                    <span className={styles.required}>
                        <CheckmarkCircleRegular aria-hidden={true} />
                        {l10n.t('Required')}
                    </span>
                    {fields
                        .filter(([key]) => key !== 'datasetUrl')
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
                <Button disabled={disabled} onClick={() => void save(values)}>
                    {l10n.t('Save connection settings')}
                </Button>
            </div>
        </div>
    );
}
