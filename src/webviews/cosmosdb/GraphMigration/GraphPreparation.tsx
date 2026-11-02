/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { Button, Checkbox, Field, Input, Textarea } from '@fluentui/react-components';
import { useTrpcClient } from '@microsoft/vscode-ext-webview/react';
import * as l10n from '@vscode/l10n';
import { useEffect, useRef, useState } from 'react';
import { type GraphAppRouter, type GraphOperation } from '../../../panels/graphMigration/graphRouter';
import { type GraphProject } from '../../../services/graphMigration/contracts';
import { hasFidelityInventory } from '../../../services/graphMigration/diagnostics';
import { type PreparationCandidates } from '../../../services/graphMigration/preparation';
export function GraphPreparation({
    project,
    disabled,
    action,
    run,
    enumerationOnly = false,
}: {
    project: GraphProject;
    disabled: boolean;
    action: (work: () => Promise<unknown>) => Promise<void>;
    run: (operation: GraphOperation) => Promise<void>;
    enumerationOnly?: boolean;
}) {
    const client = useTrpcClient<GraphAppRouter>();
    const reviewButtonRef = useRef<HTMLButtonElement>(null);
    const [candidates, setCandidates] = useState<PreparationCandidates>();
    const [text, setText] = useState(''),
        [requests, setRequests] = useState('20000'),
        [seconds, setSeconds] = useState('120');
    const [ru, setRu] = useState(project.source?.ruBudget ?? ''),
        [rate, setRate] = useState(project.source?.ruPerSecond ?? ''),
        [stable, setStable] = useState(false),
        [starting, setStarting] = useState(false);
    const partitions = [...new Set(text.split(/\r?\n/u).filter((v) => v.length > 0))];
    useEffect(() => {
        if (!enumerationOnly) return;
        reviewButtonRef.current?.focus();
        let disposed = false;
        void action(async () => {
            const found = await client.preparation.query();
            if (!disposed) {
                setCandidates(found);
                setText(found.partitions.join('\n'));
            }
        });
        return () => {
            disposed = true;
        };
    }, [enumerationOnly, client, action]);
    const valid =
        partitions.length > 0 &&
        partitions.length <= 1000 &&
        /^\d+(?:\.\d+)?$/u.test(ru) &&
        Number(ru) > 0 &&
        Number(ru) <= 1e9 &&
        /^[0-9]{1,10}(?:\.[0-9]{1,9})?$/u.test(rate) &&
        Number(rate) >= 1 &&
        Number(rate) <= 1e9 &&
        Number.isInteger(Number(requests)) &&
        Number(requests) >= 4 &&
        Number(requests) <= 100000 &&
        Number.isInteger(Number(seconds)) &&
        Number(seconds) >= 10 &&
        Number(seconds) <= 86400 &&
        stable;
    return (
        <section aria-label={l10n.t('Guided read-only Prepare')}>
            {!enumerationOnly && <h2>{l10n.t('Prepare your source')}</h2>}
            <p>
                {l10n.t(
                    'Check connections independently, then build a verified identity inventory without editing project JSON.',
                )}
            </p>
            {!enumerationOnly && (
                <>
                    <Button disabled={disabled} onClick={() => void action(() => run('preflight'))}>
                        {l10n.t('Check read-only connections')}
                    </Button>
                    <Button disabled={disabled} onClick={() => void action(() => client.credentials.mutate())}>
                        {l10n.t('Store credentials securely')}
                    </Button>
                </>
            )}
            <Button
                ref={reviewButtonRef}
                disabled={disabled || !project.source}
                onClick={() =>
                    void action(async () => {
                        const found = await client.preparation.query();
                        setCandidates(found);
                        setText(found.partitions.join('\n'));
                    })
                }
            >
                {l10n.t('Find partition candidates')}
            </Button>
            {candidates && (
                <>
                    <p>
                        <output aria-live="polite">
                            {l10n.t(
                                '{count} candidates found. These are not verified live coverage; enumeration must reconcile global and partition counts.',
                                { count: candidates.partitions.length },
                            )}
                        </output>
                    </p>
                    {candidates.notices.map((notice, index) => (
                        <p key={index}>{notice}</p>
                    ))}
                    {candidates.resume && (
                        <>
                            <Button
                                disabled={disabled || starting}
                                onClick={() => void action(() => client.resumePreparation.mutate())}
                            >
                                {l10n.t('Resume preserved read-only preparation')}
                            </Button>
                            <p>
                                {l10n.t(
                                    'Resume keeps the original rate, total-run allowance and remaining duration. The fields below apply only to a newly reviewed run.',
                                )}
                            </p>
                        </>
                    )}
                    <details>
                        <summary>{l10n.t('Review or add partition values')}</summary>
                        <Field label={l10n.t('String partition values, one per line')} required>
                            <Textarea
                                aria-required={true}
                                value={text}
                                onChange={(_, data) => setText(data.value)}
                                disabled={disabled}
                            />
                        </Field>
                    </details>
                    <Field label={l10n.t('Maximum reserved request attempts')} required>
                        <Input
                            aria-required={true}
                            type="number"
                            min={4}
                            max={100000}
                            value={requests}
                            onChange={(_, d) => setRequests(d.value)}
                            disabled={disabled}
                        />
                    </Field>
                    <Field
                        label={l10n.t('Maximum duration (seconds)')}
                        required
                        hint={l10n.t(
                            'Review 10-86400 seconds for a new run, including identity pages and all global/partition count reconciliation. The worker stops at this duration, even if identities have already been read. Existing runs keep their original deadline; increasing duration does not increase total RU or request allowances.',
                        )}
                    >
                        <Input
                            aria-required={true}
                            type="number"
                            min={10}
                            max={86400}
                            value={seconds}
                            onChange={(_, d) => setSeconds(d.value)}
                            disabled={disabled}
                        />
                    </Field>
                    <Field
                        label={l10n.t('Total source RU allowance per run')}
                        required
                        hint={l10n.t(
                            'Cumulative RU, not RU/s. For example, 1000 RU/s for 120 seconds can consume 120000 RU. Preparation and extraction have separate totals. Waiting does not replenish this allowance; existing saved values keep their total-run meaning.',
                        )}
                    >
                        <Input
                            aria-required={true}
                            value={ru}
                            onChange={(_, d) => setRu(d.value)}
                            disabled={disabled}
                        />
                    </Field>
                    <Field
                        label={l10n.t('Migration source pacing (RU/s)')}
                        required
                        hint={l10n.t(
                            'Separate from provisioned database throughput. Observed request charges pace subsequent requests; a single request can exceed one second of this rate because its cost is known only after completion. Missing charge observations block execution.',
                        )}
                    >
                        <Input
                            aria-required={true}
                            value={rate}
                            onChange={(_, d) => setRate(d.value)}
                            disabled={disabled}
                        />
                    </Field>
                    <Checkbox
                        disabled={disabled}
                        checked={stable}
                        onChange={(_, d) => setStable(d.checked === true)}
                        label={l10n.t(
                            'I confirm source writes are paused, or this is an immutable source, as declared in the connection form.',
                        )}
                    />
                    <p>
                        {l10n.t(
                            'Starts new preparation/extraction runs and preserves old artifacts. Previous approvals become stale. No source writes or target writes are performed.',
                        )}
                    </p>
                    <Button
                        appearance="primary"
                        disabled={
                            disabled || starting || !valid || !['quiesced', 'immutable'].includes(project.consistency)
                        }
                        onClick={() =>
                            void action(async () => {
                                setStarting(true);
                                try {
                                    await client.prepareSource.mutate({
                                        partitions,
                                        maximumRequests: Number(requests),
                                        maximumDurationSeconds: Number(seconds),
                                        ruBudget: ru,
                                        ruPerSecond: rate,
                                        confirmStableSource: true,
                                    });
                                } finally {
                                    setStarting(false);
                                }
                            })
                        }
                    >
                        {l10n.t('Start bounded read-only preparation')}
                    </Button>
                </>
            )}
            <p>
                {l10n.t(
                    'After reconciled enumeration, the inventory and checksum are selected automatically. Then check actual source fidelity before extraction.',
                )}
            </p>
            {!enumerationOnly && !hasFidelityInventory(project) && (
                <p>
                    {l10n.t(
                        'Source fidelity needs a completed inventory. Review any enumeration failure first; no source capability conclusion can be drawn from an empty bootstrap file.',
                    )}
                </p>
            )}
            {!enumerationOnly && (
                <Button
                    disabled={disabled || !hasFidelityInventory(project)}
                    onClick={() => void action(() => run('source-compatibility'))}
                >
                    {l10n.t('Check read-only source fidelity')}
                </Button>
            )}
        </section>
    );
}
