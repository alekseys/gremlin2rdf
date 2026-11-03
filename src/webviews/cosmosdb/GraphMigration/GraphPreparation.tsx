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
import {
    hasCurrentEnumerationDeadline,
    hasCurrentEnumerationRequestLimit,
    hasFidelityInventory,
} from '../../../services/graphMigration/diagnostics';
import { type PreparationCandidates } from '../../../services/graphMigration/preparation';
export function GraphPreparation({
    project,
    currentInputHash,
    disabled,
    action,
    run,
    enumerationOnly = false,
    executionDisabled = false,
    reviewVersion = 0,
    checkConnections,
}: {
    project: GraphProject;
    currentInputHash?: string;
    disabled: boolean;
    action: (work: () => Promise<unknown>) => Promise<void>;
    run: (operation: GraphOperation) => Promise<void>;
    enumerationOnly?: boolean;
    executionDisabled?: boolean;
    reviewVersion?: number;
    checkConnections?: () => Promise<void>;
}) {
    const client = useTrpcClient<GraphAppRouter>();
    const documents = project.source?.sourceMode === 'cosmos-documents';
    const reviewButtonRef = useRef<HTMLButtonElement>(null);
    const requestsRef = useRef<HTMLInputElement>(null);
    const [candidates, setCandidates] = useState<PreparationCandidates>();
    const expired = hasCurrentEnumerationDeadline(project, currentInputHash);
    const requestLimited = hasCurrentEnumerationRequestLimit(project, currentInputHash);
    const enumerationStatus = project.phases['source-enumerate']?.status;
    const [text, setText] = useState(''),
        [requests, setRequests] = useState(requestLimited ? '' : '20000'),
        [seconds, setSeconds] = useState(
            expired ? '' : String((project.limits.maximumOperationMillis ?? 120000) / 1000),
        );
    const [ru, setRu] = useState(project.source?.ruBudget ?? ''),
        [rate, setRate] = useState(project.source?.ruPerSecond ?? ''),
        [stable, setStable] = useState(false),
        [starting, setStarting] = useState(false);
    useEffect(() => {
        if (reviewVersion > 0) requestsRef.current?.focus();
    }, [reviewVersion]);
    useEffect(() => {
        if (expired) {
            setSeconds('');
            setStable(false);
        }
    }, [expired]);
    useEffect(() => {
        if (requestLimited) {
            setRequests('');
            setStable(false);
        }
    }, [requestLimited]);
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
    }, [enumerationOnly, client, action, enumerationStatus]);
    const valid =
        (documents || partitions.length > 0) &&
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
            {enumerationOnly && reviewVersion > 0 && (
                <p>
                    {l10n.t('Run settings are open. Review the limits and stable-source confirmation before starting.')}
                </p>
            )}
            {starting && enumerationStatus !== 'running' && (
                <output>
                    {l10n.t(
                        'Waiting for confirmation in the VS Code dialog outside this panel. Confirm the displayed budgets to start, or dismiss the dialog to keep the existing run unchanged.',
                    )}
                </output>
            )}
            {executionDisabled && !disabled && (
                <>
                    <p>
                        {l10n.t(
                            'Settings can be edited, but starting this run requires the earliest unfinished migration step to pass. If connection evidence became stale after creating the new run, recheck it here; no budgets or checkpoints are reset.',
                        )}
                    </p>
                    {checkConnections && (
                        <Button onClick={() => void action(checkConnections)}>
                            {l10n.t('Check read-only connections')}
                        </Button>
                    )}
                </>
            )}
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
                {documents ? l10n.t('Review document-feed preparation') : l10n.t('Find partition candidates')}
            </Button>
            {(documents || candidates || enumerationOnly) && (
                <>
                    {documents && (
                        <p>
                            {l10n.t(
                                'The .NET SDK scans all container feed partitions with bounded pages and continuation tokens. No Gremlin queries or partition candidate list are required. Completion requires feed and count reconciliation; failures stop without switching source modes.',
                            )}
                        </p>
                    )}
                    {candidates && !documents && (
                        <p>
                            <output aria-live="polite">
                                {l10n.t(
                                    '{count} candidates found. These are not verified live coverage; enumeration must reconcile global and partition counts.',
                                    { count: candidates.partitions.length },
                                )}
                            </output>
                        </p>
                    )}
                    {candidates?.notices.map((notice, index) => (
                        <p key={index}>{notice}</p>
                    ))}
                    {candidates?.resume && !requestLimited && !expired && (
                        <>
                            <Button
                                disabled={disabled || executionDisabled || starting}
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
                    {!documents && (
                        <details>
                            <summary>{l10n.t('Review or add partition values')}</summary>
                            <Field
                                label={l10n.t('String partition values, one per line')}
                                hint={l10n.t(
                                    'Example: tenant-a on one line, tenant-b on the next. Enter 1-1000 distinct exact string partition values.',
                                )}
                                required
                            >
                                <Textarea
                                    aria-required={true}
                                    value={text}
                                    onChange={(_, data) => setText(data.value)}
                                    disabled={disabled}
                                />
                            </Field>
                        </details>
                    )}
                    <Field
                        label={l10n.t('Maximum reserved request attempts')}
                        required
                        validationState={requestLimited && !requests ? 'error' : 'none'}
                        validationMessage={
                            requestLimited && !requests
                                ? l10n.t(
                                      'The stopped run exhausted its reviewed request allowance. Enter and explicitly review a request ceiling for a new run; Resume cannot renew this allowance.',
                                  )
                                : undefined
                        }
                        hint={l10n.t(
                            'Example: 20000. Whole number from 4 to 100000. Reserved attempts include retry capacity, not just completed requests. Identity pages and count reconciliation share this ceiling. Applies only to newly reviewed preparation; old checkpoints and journals are retained.',
                        )}
                    >
                        <Input
                            ref={requestsRef}
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
                        validationMessage={
                            expired && !seconds
                                ? l10n.t(
                                      'The previous run reached its time limit. Enter the duration you intend to review for a new run; installing an update does not extend an existing limit.',
                                  )
                                : undefined
                        }
                        validationState={expired && !seconds ? 'error' : 'none'}
                        hint={l10n.t(
                            'Example: 1800. Whole number from 10 to 86400 seconds, including identity pages and all count reconciliation. The worker stops at this duration even if identities have been read. Existing runs keep their deadline; increasing duration does not increase RU or request allowances.',
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
                    {expired && (
                        <p>
                            {project.limits.maximumOperationMillis !== undefined
                                ? l10n.t(
                                      'Previous configured overall limit: {seconds} seconds. This is not an RU/s limit. The duration field is deliberately empty so the old limit is not silently reused. Review enough time for identity reads and reconciliation; no completion duration is guaranteed.',
                                      { seconds: project.limits.maximumOperationMillis / 1000 },
                                  )
                                : l10n.t(
                                      'The previous enumeration reached a duration limit. Its exact configured duration is not available here. Enter and review a new bounded duration without deleting prior artifacts.',
                                  )}
                        </p>
                    )}
                    <Field
                        label={l10n.t('Total source RU allowance per run')}
                        required
                        hint={l10n.t(
                            'Example: 120000. Positive decimal up to 1000000000 RU; no commas or exponent notation. Cumulative RU, not RU/s: 1000 RU/s for 120 seconds can consume 120000 RU. Preparation and extraction have separate totals. Waiting does not replenish this allowance.',
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
                            'Example: 1000. Decimal from 1 to 1000000000 RU/s, up to 9 decimal places; no commas or exponent notation. Separate from provisioned throughput. Observed charges pace subsequent requests; one request can exceed one second of this rate. Missing charge observations block execution.',
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
                            disabled ||
                            executionDisabled ||
                            starting ||
                            !valid ||
                            !['quiesced', 'immutable'].includes(project.consistency)
                        }
                        onClick={() =>
                            void action(async () => {
                                setStarting(true);
                                try {
                                    await client.prepareSource.mutate({
                                        partitions: documents ? [] : partitions,
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
