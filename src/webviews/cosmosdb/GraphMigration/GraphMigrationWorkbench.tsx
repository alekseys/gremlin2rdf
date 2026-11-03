/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import {
    Button,
    Field,
    Input,
    Select,
    Textarea,
    makeStyles,
    mergeClasses,
    tokens,
    useAnnounce,
} from '@fluentui/react-components';
import {
    ArrowRightRegular,
    ArrowUploadRegular,
    CheckmarkCircleRegular,
    CircleRegular,
    DatabaseRegular,
    DocumentSearchRegular,
    PlayRegular,
    WarningRegular,
    WrenchRegular,
} from '@fluentui/react-icons';
import { useTrpcClient } from '@microsoft/vscode-ext-webview/react';
import * as l10n from '@vscode/l10n';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
    type GraphAppRouter,
    type GraphEvidencePhase,
    type GraphOperation,
} from '../../../panels/graphMigration/graphRouter';
import { activityText } from '../../../services/graphMigration/activity';
import { type GraphProject } from '../../../services/graphMigration/contracts';
import {
    formatDiagnostic,
    hasCurrentSourceCompatibility,
    hasCurrentAdoptedInventory,
    hasCurrentEnumerationRequestLimit,
    hasFidelityInventory,
    safeDiagnostics,
    hostDiagnostic,
    GraphDiagnosticError,
} from '../../../services/graphMigration/diagnostics';
import { graphEvidencePhases } from '../../../services/graphMigration/evidence';
import { type SetupSuggestions } from '../../../services/graphMigration/setup';
import { GraphConnectionSetup } from './GraphConnectionSetup';
import { GraphPreparation } from './GraphPreparation';

const useStyles = makeStyles({
    root: {
        boxSizing: 'border-box',
        maxWidth: '1120px',
        marginInline: 'auto',
        padding: `${tokens.spacingVerticalL} ${tokens.spacingHorizontalL}`,
        color: tokens.colorNeutralForeground1,
        '& *': { boxSizing: 'border-box' },
        '& h1': { margin: 0, fontSize: tokens.fontSizeBase600, lineHeight: tokens.lineHeightBase600 },
        '& h2': { margin: 0, fontSize: tokens.fontSizeBase500, lineHeight: tokens.lineHeightBase500 },
        '& p': { marginBlock: tokens.spacingVerticalS },
        '& .fui-Field': { display: 'flex', flexDirection: 'column' },
        '& .fui-Field__label': { order: -2 },
        '& .fui-Field__hint': { order: -1 },
        '@media (max-width: 640px)': {
            padding: `${tokens.spacingVerticalM} ${tokens.spacingHorizontalM}`,
            '& h1': { fontSize: tokens.fontSizeBase500, lineHeight: tokens.lineHeightBase500 },
        },
    },
    hero: {
        padding: `${tokens.spacingVerticalL} ${tokens.spacingHorizontalL}`,
        border: `1px solid ${tokens.colorNeutralStroke2}`,
        borderLeftWidth: '4px',
        borderLeftStyle: 'solid',
        borderLeftColor: tokens.colorBrandStroke1,
        borderRadius: tokens.borderRadiusMedium,
        backgroundColor: tokens.colorNeutralBackground2,
    },
    eyebrow: {
        display: 'flex',
        alignItems: 'center',
        gap: tokens.spacingHorizontalS,
        marginBottom: tokens.spacingVerticalS,
        color: tokens.colorBrandForeground1,
        fontSize: tokens.fontSizeBase200,
        fontWeight: tokens.fontWeightSemibold,
        letterSpacing: '0.04em',
        textTransform: 'uppercase',
    },
    purpose: {
        maxWidth: '68ch',
        marginBlock: `${tokens.spacingVerticalS} ${tokens.spacingVerticalM}`,
        color: `${tokens.colorNeutralForeground1} !important`,
    },
    bridge: {
        display: 'flex',
        alignItems: 'center',
        gap: tokens.spacingHorizontalM,
        flexWrap: 'wrap',
        marginTop: tokens.spacingVerticalM,
        '@media (max-width: 640px)': { flexDirection: 'column', alignItems: 'stretch' },
    },
    bridgeArrow: {
        flexShrink: 0,
        '@media (max-width: 640px)': { alignSelf: 'center', transform: 'rotate(90deg)' },
    },
    bridgeNode: {
        display: 'flex',
        alignItems: 'center',
        gap: tokens.spacingHorizontalS,
        padding: `${tokens.spacingVerticalS} ${tokens.spacingHorizontalM}`,
        border: `1px solid ${tokens.colorNeutralStroke2}`,
        borderRadius: tokens.borderRadiusMedium,
        backgroundColor: tokens.colorNeutralBackground1,
        fontWeight: tokens.fontWeightSemibold,
    },
    sourceIcon: { color: tokens.colorPaletteBlueForeground2 },
    targetIcon: { color: tokens.colorPaletteGreenForeground2 },
    stepRail: {
        display: 'grid',
        gridTemplateColumns: 'repeat(4, minmax(0, 1fr))',
        gap: tokens.spacingHorizontalS,
        marginBlock: tokens.spacingVerticalM,
        '@media (max-width: 640px)': { gridTemplateColumns: 'repeat(2, minmax(0, 1fr))' },
    },
    step: {
        display: 'flex',
        alignItems: 'center',
        gap: tokens.spacingHorizontalS,
        minWidth: 0,
        padding: `${tokens.spacingVerticalS} ${tokens.spacingHorizontalM}`,
        borderLeftWidth: '2px',
        borderLeftStyle: 'solid',
        borderLeftColor: tokens.colorNeutralStroke1,
        color: tokens.colorNeutralForeground2,
        fontSize: tokens.fontSizeBase200,
    },
    stepNumber: {
        display: 'inline-grid',
        placeItems: 'center',
        width: '22px',
        height: '22px',
        flex: '0 0 22px',
        borderRadius: tokens.borderRadiusCircular,
        backgroundColor: tokens.colorNeutralBackground3,
        color: tokens.colorNeutralForeground1,
        fontWeight: tokens.fontWeightSemibold,
    },
    section: {
        display: 'grid',
        gap: tokens.spacingVerticalM,
        marginBlock: tokens.spacingVerticalL,
        padding: tokens.spacingVerticalL,
        border: `1px solid ${tokens.colorNeutralStroke2}`,
        borderRadius: tokens.borderRadiusMedium,
        backgroundColor: tokens.colorNeutralBackground1,
    },
    sectionHeader: {
        display: 'flex',
        alignItems: 'center',
        flexWrap: 'wrap',
        gap: tokens.spacingHorizontalS,
        color: tokens.colorNeutralForeground1,
    },
    requiredTag: {
        display: 'inline-flex',
        alignItems: 'center',
        gap: tokens.spacingHorizontalXXS,
        width: 'fit-content',
        borderRadius: tokens.borderRadiusSmall,
        padding: `2px ${tokens.spacingHorizontalS}`,
        backgroundColor: tokens.colorBrandBackground2,
        color: tokens.colorBrandForeground2,
        fontSize: tokens.fontSizeBase200,
        fontWeight: tokens.fontWeightSemibold,
    },
    optionalDetails: {
        border: `1px solid ${tokens.colorNeutralStroke2}`,
        borderRadius: tokens.borderRadiusMedium,
        backgroundColor: tokens.colorNeutralBackground2,
        '& > summary': {
            padding: `${tokens.spacingVerticalM} ${tokens.spacingHorizontalM}`,
            color: tokens.colorNeutralForeground2,
            cursor: 'pointer',
            fontWeight: tokens.fontWeightSemibold,
        },
        '& > summary:focus-visible': {
            outline: `2px solid ${tokens.colorStrokeFocus2}`,
            outlineOffset: '-2px',
        },
        '&[open] > summary': {
            borderBottom: `1px solid ${tokens.colorNeutralStroke2}`,
            color: tokens.colorNeutralForeground1,
        },
    },
    detailsBody: {
        display: 'grid',
        gap: tokens.spacingVerticalM,
        padding: tokens.spacingVerticalM,
        color: tokens.colorNeutralForeground2,
    },
    activeStep: {
        border: `2px solid ${tokens.colorBrandStroke1}`,
        borderLeftWidth: '5px',
        backgroundColor: tokens.colorBrandBackground2,
        '& > summary': { color: tokens.colorBrandForeground2 },
        '@media (forced-colors: active)': {
            borderTopColor: 'Highlight',
            borderRightColor: 'Highlight',
            borderBottomColor: 'Highlight',
            borderLeftColor: 'Highlight',
        },
    },
    notice: {
        minWidth: 0,
        overflowWrap: 'anywhere',
        padding: `${tokens.spacingVerticalS} ${tokens.spacingHorizontalM}`,
        borderLeftWidth: '3px',
        borderLeftStyle: 'solid',
        borderLeftColor: tokens.colorPaletteYellowBorder2,
        backgroundColor: tokens.colorPaletteYellowBackground1,
        color: tokens.colorNeutralForeground1,
    },
    alert: {
        padding: `${tokens.spacingVerticalS} ${tokens.spacingHorizontalM}`,
        borderLeftWidth: '3px',
        borderLeftStyle: 'solid',
        borderLeftColor: tokens.colorPaletteRedBorder2,
        backgroundColor: tokens.colorPaletteRedBackground1,
        color: tokens.colorNeutralForeground1,
    },
    workflowGrid: {
        display: 'grid',
        gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
        gap: tokens.spacingHorizontalM,
        '@media (max-width: 640px)': { gridTemplateColumns: 'minmax(0, 1fr)' },
    },
    workflowGroup: {
        minWidth: 0,
        borderLeftWidth: '3px',
        borderLeftStyle: 'solid',
        borderLeftColor: tokens.colorBrandStroke1,
        paddingInlineStart: tokens.spacingHorizontalM,
        '& h3': { marginBlock: tokens.spacingVerticalS, fontSize: tokens.fontSizeBase400 },
    },
    operationCard: {
        display: 'grid',
        gridTemplateColumns: 'minmax(0, 1fr) auto',
        alignItems: 'center',
        gap: tokens.spacingHorizontalS,
        minWidth: 0,
        paddingBlock: tokens.spacingVerticalS,
        borderBottom: `1px solid ${tokens.colorNeutralStroke2}`,
        '& h4': {
            margin: 0,
            fontSize: tokens.fontSizeBase200,
            fontWeight: tokens.fontWeightRegular,
            overflowWrap: 'anywhere',
        },
        '@media (max-width: 640px)': { gridTemplateColumns: 'minmax(0, 1fr)' },
    },
    operationActions: {
        gridColumn: '1 / -1',
        minWidth: 0,
    },
    operationDiagnostics: {
        gridColumn: '1 / -1',
        minWidth: 0,
    },
    activityLog: {
        maxHeight: '200px',
        overflowY: 'auto',
        overflowWrap: 'anywhere',
        paddingInline: tokens.spacingHorizontalS,
        border: `1px solid ${tokens.colorNeutralStroke2}`,
        '&:focus-visible': { outline: `2px solid ${tokens.colorStrokeFocus2}`, outlineOffset: '2px' },
    },
    status: {
        display: 'inline-flex',
        alignItems: 'center',
        gap: tokens.spacingHorizontalXS,
        width: 'fit-content',
        padding: `2px ${tokens.spacingHorizontalS}`,
        borderRadius: tokens.borderRadiusSmall,
        backgroundColor: tokens.colorNeutralBackground3,
        color: tokens.colorNeutralForeground2,
        fontSize: tokens.fontSizeBase200,
        maxWidth: '100%',
    },
    statusSucceeded: {
        backgroundColor: tokens.colorPaletteGreenBackground1,
        color: tokens.colorPaletteGreenForeground1,
    },
    statusBlocked: {
        backgroundColor: tokens.colorPaletteRedBackground1,
        color: tokens.colorPaletteRedForeground1,
    },
    statusWarning: {
        backgroundColor: tokens.colorPaletteYellowBackground1,
        color: tokens.colorPaletteYellowForeground1,
    },
    statusFailed: { backgroundColor: tokens.colorPaletteRedBackground1, color: tokens.colorPaletteRedForeground1 },
    actionButtons: { display: 'flex', gap: tokens.spacingHorizontalS, flexWrap: 'wrap' },
});

const operations = (): [GraphOperation, string][] => [
    ['preflight', l10n.t('Check read-only connections')],
    ['target-probe', l10n.t('Probe approved disposable target graph')],
    ['assess', l10n.t('Assess application')],
    ['source-compatibility', l10n.t('Probe read-only source compatibility')],
    ['source-enumerate', l10n.t('Enumerate read-only source identities')],
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
    warning: l10n.t('Warning'),
    failed: l10n.t('Failed'),
    succeeded: l10n.t('Succeeded'),
    cancelled: l10n.t('Cancelled'),
    stale: l10n.t('Stale: inputs changed'),
});

function isReadinessWarning(project: GraphProject | null, hash?: string): boolean {
    const phase = project?.phases.report;
    return Boolean(
        phase &&
        phase.status === 'blocked' &&
        hash &&
        phase.inputHash === hash &&
        phase.evidence.length > 0 &&
        phase.blockers.length > 0 &&
        phase.blockers.every((blocker) => /^readiness-[a-z]+ \(READINESS_EVIDENCE_REQUIRED\):/u.test(blocker)),
    );
}

export function GraphMigrationWorkbench() {
    const styles = useStyles();
    const client = useTrpcClient<GraphAppRouter>();
    const { announce } = useAnnounce();
    const [project, setProject] = useState<GraphProject | null>(null);
    const [currentInputHash, setCurrentInputHash] = useState<string>();
    const [enumerationReview, setEnumerationReview] = useState(0);
    const [restartFocusRunId, setRestartFocusRunId] = useState<string>();
    const [continuation, setContinuation] = useState<{ operation: GraphOperation; runId?: string }>();
    const dispatchedAutomatically = useRef(new Set<string>());
    const firstStepRef = useRef<HTMLDetailsElement>(null);
    const connectionSettingsRef = useRef<HTMLDetailsElement>(null);
    const [suggestions, setSuggestions] = useState<SetupSuggestions>();
    const [configuration, setConfiguration] = useState('');
    const [configurationDirty, setConfigurationDirty] = useState(false);
    const configurationDirtyRef = useRef(false);
    const [trusted, setTrusted] = useState(false);
    const [busy, setBusy] = useState(false);
    const [elapsedSeconds, setElapsedSeconds] = useState(0);
    const [diagnostic, setDiagnostic] = useState('');
    const [query, setQuery] = useState('g.V().count()');
    const [sourcePath, setSourcePath] = useState('');
    const [outputPath, setOutputPath] = useState('artifacts/rewritten/GraphRepository.java');
    const [probeGraph, setProbeGraph] = useState('');
    const [progress, setProgress] = useState('');
    const [activity, setActivity] = useState<Record<string, string[]>>({});
    const [workerElapsed, setWorkerElapsed] = useState<Record<string, number>>({});
    const activityRun = useRef<string | undefined>(undefined);
    const activityAttempt = useRef<Record<string, string>>({});
    const [evidencePath, setEvidencePath] = useState('');
    const [evidencePhase, setEvidencePhase] = useState<GraphEvidencePhase>('application-build');
    const applyProject = useCallback((value: GraphProject | null) => {
        if (activityRun.current !== value?.runId) {
            setActivity({});
            setWorkerElapsed({});
            activityRun.current = value?.runId;
            activityAttempt.current = {};
        }
        setProject(value);
        if (!configurationDirtyRef.current) setConfiguration(value ? JSON.stringify(value, null, 2) : '');
        setBusy(Boolean(value && Object.values(value.phases).some((phase) => phase.status === 'running')));
    }, []);

    const workflowGroups: { title: string; phases: GraphOperation[] }[] = [
        { title: l10n.t('Prepare'), phases: ['preflight', 'source-enumerate', 'assess'] },
        { title: l10n.t('Check live source and mapping'), phases: ['source-compatibility', 'mapping'] },
        { title: l10n.t('Transfer graph'), phases: ['extract', 'load'] },
        { title: l10n.t('Verify migration'), phases: ['validate', 'functional'] },
    ];
    const coreOperations = new Set(workflowGroups.flatMap((group) => group.phases));
    const action = useCallback(async (work: () => Promise<unknown>) => {
        setDiagnostic('');
        try {
            await work();
        } catch {
            setDiagnostic(
                (current) =>
                    current ||
                    l10n.t(
                        'The operation could not complete. Review the specific saved phase diagnostic; connection and inventory failures are separate.',
                    ),
            );
            setBusy(false);
        }
    }, []);
    const reload = useCallback(async () => {
        const value = await client.load.query();
        setTrusted(value.trusted);
        setSuggestions(value.setup);
        setCurrentInputHash(value.currentInputHash);
        applyProject(value.project);
    }, [client, applyProject]);
    useEffect(() => {
        if (!busy) return;
        const started = Date.now();
        setElapsedSeconds(0);
        const timer = setInterval(() => setElapsedSeconds(Math.floor((Date.now() - started) / 1000)), 10000);
        return () => clearInterval(timer);
    }, [busy]);
    useEffect(() => {
        void action(reload);
        const subscription = client.events.subscribe(undefined, {
            onData: (event) => {
                if (event.type === 'project') {
                    applyProject(event.project);
                    setCurrentInputHash(event.currentInputHash);
                    setDiagnostic('');
                    announce(l10n.t('Graph project state updated.'));
                } else if (event.type === 'activity-snapshot') {
                    const lines = event.messages.slice(0, 20).flatMap((message) => {
                        const text = activityText(message.payload);
                        return text ? [text] : [];
                    });
                    setActivity((current) => ({ ...current, [event.phase]: lines }));
                } else if (event.type === 'diagnostic') {
                    setDiagnostic(
                        formatDiagnostic(hostDiagnostic(new GraphDiagnosticError(event.code, 'worker-control'))),
                    );
                    if (!event.pending) setBusy(false);
                } else if (event.message.type === 'checkpoint') {
                    const text = l10n.t('A target-confirmed checkpoint was saved.');
                    setProgress(text);
                } else if (event.message.type === 'progress') {
                    const payload = event.message.payload;
                    const phase = event.message.phase;
                    const text = activityText(payload);
                    if (text) {
                        const sameAttempt = activityAttempt.current[phase] === event.message.operationId;
                        activityAttempt.current[phase] = event.message.operationId;
                        setActivity((current) => {
                            const lines = [...(sameAttempt ? (current[phase] ?? []) : []), text];
                            if (lines.length > 20) lines.splice(1, lines.length - 20);
                            return { ...current, [phase]: lines };
                        });
                        setProgress(text);
                    }
                    if (
                        payload &&
                        typeof payload === 'object' &&
                        !Array.isArray(payload) &&
                        payload.activity === 'worker-active' &&
                        typeof payload.elapsedMillis === 'number' &&
                        Number.isSafeInteger(payload.elapsedMillis) &&
                        payload.elapsedMillis >= 0
                    ) {
                        const seconds = Math.floor(payload.elapsedMillis / 1000);
                        setWorkerElapsed((current) => ({ ...current, [phase]: seconds }));
                    }
                } else if (event.message.type === 'warning') {
                    setProgress(
                        l10n.t('The worker reported an execution warning. Review the persisted phase and diagnostics.'),
                    );
                } else if (event.message.type === 'result' || event.message.type === 'error') {
                    setProgress(l10n.t('Worker operation finished. Review the phase status and evidence.'));
                    const details = safeDiagnostics(event.message.payload);
                    if (
                        details.length &&
                        !(
                            event.message.phase === 'report' &&
                            details.every(
                                (detail) =>
                                    detail.code === 'READINESS_EVIDENCE_REQUIRED' &&
                                    detail.stage.startsWith('readiness-'),
                            )
                        )
                    )
                        setDiagnostic(details.map(formatDiagnostic).join('\n'));
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
    const [setupDirty, setSetupDirty] = useState(false);
    const disabled = !trusted || !project || busy;
    const runLabel = l10n.t('Run'),
        authorizationLabel = l10n.t('Authorize');
    const run = async (operation: GraphOperation) => {
        if (operation === 'source-enumerate') {
            setEnumerationReview((version) => version + 1);
            announce(
                l10n.t('Run settings are open. Review the limits and stable-source confirmation before starting.'),
            );
            return;
        }
        setContinuation({ operation, runId: project?.runId });
        setBusy(true);
        try {
            await client.run.mutate({
                operation,
                query,
                sourcePath,
                outputPath: operation === 'target-probe' ? probeGraph : outputPath,
            });
            await reload();
        } catch (error) {
            setContinuation(undefined);
            throw error;
        }
    };
    const operationLabels = new Map(operations());
    const corePhases = workflowGroups
        .flatMap((group) => group.phases)
        .map((operation): [GraphOperation, string] => [operation, operationLabels.get(operation)!])
        .filter(
            ([operation]) =>
                coreOperations.has(operation) &&
                (operation !== 'source-enumerate' ||
                    !project ||
                    !hasFidelityInventory(project) ||
                    Boolean(project.phases['source-enumerate'])),
        );
    const phaseComplete = useCallback(
        (operation: GraphOperation) =>
            Boolean(
                project &&
                (operation === 'source-enumerate'
                    ? hasFidelityInventory(project)
                    : operation === 'source-compatibility'
                      ? hasCurrentSourceCompatibility(project, currentInputHash)
                      : project.phases[operation]?.status === 'succeeded' &&
                        currentInputHash !== undefined &&
                        project.phases[operation].inputHash === currentInputHash),
            ),
        [project, currentInputHash],
    );
    const nextPhase =
        corePhases.find(([operation]) => project?.phases[operation]?.status === 'running') ??
        corePhases.find(([operation]) => !phaseComplete(operation));
    const nextOperation = nextPhase?.[0];
    const nextLabel = nextPhase?.[1];
    const requiredStepsRef = useRef<HTMLDivElement>(null);
    const previousStep = useRef<string | undefined>(undefined);
    const stepKey = project ? `${project.runId}:${nextOperation ?? 'complete'}` : undefined;
    useEffect(() => {
        const previous = previousStep.current;
        previousStep.current = stepKey;
        if (!previous || previous === stepKey) return;
        const steps = requiredStepsRef.current;
        for (const card of steps?.querySelectorAll<HTMLDetailsElement>('details[data-completed="true"]') ?? []) {
            card.open = false;
        }
        const next = steps?.querySelector<HTMLDetailsElement>('details[data-active="true"]');
        if (next) {
            next.open = true;
            next.scrollIntoView?.({ block: 'start', behavior: 'instant' });
            next.focus({ preventScroll: true });
        }
    }, [stepKey]);
    useEffect(() => {
        if (restartFocusRunId && project?.runId === restartFocusRunId) {
            firstStepRef.current?.scrollIntoView?.({ block: 'center' });
            firstStepRef.current?.focus();
            setRestartFocusRunId(undefined);
        }
    }, [restartFocusRunId, project?.runId]);
    const nextRunRef = useRef<HTMLButtonElement>(null);
    const inventoryAdopted = Boolean(project && hasCurrentAdoptedInventory(project, currentInputHash));
    const enumerationStopped = Boolean(
        project && ['blocked', 'failed', 'paused', 'cancelled'].includes(project.phases['source-enumerate']?.status),
    );
    const enumerationRequestLimited = Boolean(project && hasCurrentEnumerationRequestLimit(project, currentInputHash));
    const phaseStatus = (operation: GraphOperation) => {
        const phase = project?.phases[operation];
        if (operation === 'report' && isReadinessWarning(project, currentInputHash)) return 'warning';
        return phase && phase.status !== 'running' && currentInputHash && phase.inputHash !== currentInputHash
            ? 'stale'
            : (phase?.status ?? 'not-started');
    };
    useEffect(() => {
        if (!continuation || !project || !trusted || busy || setupDirty || configurationDirty || !currentInputHash)
            return;
        if (
            continuation.runId &&
            continuation.runId !== project.runId &&
            !(continuation.operation === 'source-enumerate' && hasCurrentAdoptedInventory(project, currentInputHash))
        ) {
            setContinuation(undefined);
            return;
        }
        const previous = project.phases[continuation.operation];
        if (previous && ['blocked', 'failed', 'paused', 'cancelled'].includes(previous.status)) {
            setContinuation(undefined);
            return;
        }
        if (!phaseComplete(continuation.operation)) return;
        const automatic = new Set<GraphOperation>([
            'preflight',
            'assess',
            'source-compatibility',
            'mapping',
            'extract',
            'validate',
            'functional',
        ]);
        if (!nextOperation || !automatic.has(nextOperation)) {
            setContinuation(undefined);
            return;
        }
        const next = project.phases[nextOperation];
        if (next && ['blocked', 'failed', 'paused', 'cancelled'].includes(next.status)) {
            setContinuation(undefined);
            return;
        }
        const approved = (operation: string) =>
            project.approvals.some(
                (approval) =>
                    approval.operation === operation &&
                    approval.runId === project.runId &&
                    approval.inputHash === currentInputHash &&
                    Date.parse(approval.expiresAt) > Date.now(),
            );
        if (
            ['source-compatibility', 'extract'].includes(nextOperation) &&
            ((project.mapping.fidelity === 'reduced' && !approved('reduced-fidelity')) ||
                (project.consistency === 'best-effort' && !approved('best-effort-source')))
        ) {
            return;
        }
        const key = `${project.runId}:${currentInputHash}:${nextOperation}`;
        if (dispatchedAutomatically.current.has(key)) return;
        dispatchedAutomatically.current.add(key);
        setContinuation({ operation: nextOperation, runId: project.runId });
        setBusy(true);
        void action(async () => {
            try {
                await client.run.mutate({ operation: nextOperation, query: '', sourcePath: '', outputPath: '' });
                await reload();
            } catch (error) {
                setContinuation(undefined);
                throw error;
            }
        });
    }, [
        continuation,
        project,
        trusted,
        busy,
        setupDirty,
        configurationDirty,
        currentInputHash,
        nextOperation,
        phaseComplete,
        action,
        client,
        reload,
    ]);
    useEffect(() => {
        if (nextOperation !== 'preflight' && nextOperation !== 'source-enumerate') setEnumerationReview(0);
        if (nextLabel) announce(l10n.t('Current step: {phase}', { phase: nextLabel }));
    }, [nextOperation, nextLabel, announce]);
    const statusStyles: Partial<Record<string, string>> = {
        succeeded: styles.statusSucceeded,
        blocked: styles.statusBlocked,
        warning: styles.statusWarning,
        failed: styles.statusFailed,
    };
    const renderOperation = ([operation, label]: [GraphOperation, string], showActions = true, showTitle = true) => {
        const operationActionLabel = runLabel;
        const phaseDisabled =
            disabled ||
            setupDirty ||
            configurationDirty ||
            (coreOperations.has(operation) && operation !== nextPhase?.[0]);
        const extractionPrerequisite =
            operation === 'extract' && project && !hasCurrentSourceCompatibility(project, currentInputHash)
                ? l10n.t(
                      'Extraction needs current, succeeded source fidelity evidence. Resolve the Prepare enumeration blocker first, then run fidelity. Mapping and connectivity success alone do not permit extraction.',
                  )
                : undefined;
        const status = phaseStatus(operation);
        const lines = activity[operation] ?? [];
        const StatusIcon =
            status === 'succeeded'
                ? CheckmarkCircleRegular
                : status === 'blocked' || status === 'failed' || status === 'warning'
                  ? WarningRegular
                  : CircleRegular;
        return (
            <article className={styles.operationCard} key={operation} aria-label={label}>
                {showTitle && <h4>{label}</h4>}
                {showTitle && (
                    <span className={mergeClasses(styles.status, statusStyles[status])}>
                        <StatusIcon aria-hidden={true} />
                        {phaseLabels()[status]}
                    </span>
                )}
                {(status === 'running' || lines.length > 0 || workerElapsed[operation] !== undefined) && (
                    <div className={styles.operationDiagnostics}>
                        {status === 'running' && (
                            <output>{l10n.t('Operation running. Recent activity is shown in this step.')}</output>
                        )}
                        {status === 'running' && (
                            <p>
                                {l10n.t(
                                    'Worker active; elapsed: {seconds} seconds. Activity below reports observed work, not a completion estimate.',
                                    { seconds: workerElapsed[operation] ?? elapsedSeconds },
                                )}
                            </p>
                        )}
                        {project?.limits.maximumOperationMillis !== undefined && (
                            <p>
                                {l10n.t(
                                    'Configured overall operation limit: {seconds} seconds; this is separate from enumeration duration and RU limits.',
                                    { seconds: Math.floor(project.limits.maximumOperationMillis / 1000) },
                                )}
                            </p>
                        )}
                        {status === 'running' && lines.length === 0 && (
                            <p>{l10n.t('Starting this operation; waiting for its first bounded progress update.')}</p>
                        )}
                        {lines.length > 0 && (
                            <>
                                <p>{lines[lines.length - 1]}</p>
                                <div
                                    className={styles.activityLog}
                                    role="log"
                                    aria-live="off"
                                    aria-label={l10n.t('Recent activity for {phase}', { phase: label })}
                                >
                                    <Field label={l10n.t('Recent activity for {phase}', { phase: label })}>
                                        <Textarea readOnly rows={6} value={lines.join('\n')} />
                                    </Field>
                                </div>
                            </>
                        )}
                    </div>
                )}
                {operation === 'source-enumerate' && inventoryAdopted && (
                    <p>
                        {l10n.t(
                            'Verified inventory adopted for this run. The original enumeration and its evidence are preserved; you do not need to enumerate again. Selecting the inventory created a new input-bound run, so recheck read-only connections before continuing.',
                        )}
                    </p>
                )}
                {Boolean(project?.phases[operation]?.blockers.length) && (
                    <div className={styles.operationDiagnostics}>
                        {status === 'stale' ? (
                            <details>
                                <summary>{l10n.t('Previous result — not a current connection check')}</summary>
                                <p>{l10n.t('Inputs changed after this result. Save settings and rerun this check.')}</p>
                                {project?.phases[operation]?.blockers.map((blocker, index) => (
                                    <p key={index} className={styles.notice}>
                                        {blocker}
                                    </p>
                                ))}
                            </details>
                        ) : (
                            project?.phases[operation]?.blockers.map((blocker, index) => (
                                <p key={index} className={styles.notice}>
                                    {blocker}
                                </p>
                            ))
                        )}
                    </div>
                )}
                {operation === 'source-compatibility' &&
                    status === 'blocked' &&
                    project?.source?.sourceMode === 'cosmos-documents' &&
                    project.phases[operation]?.blockers.some((blocker) =>
                        blocker.includes('(DOCUMENT_FIDELITY_UNOBSERVABLE)'),
                    ) && (
                        <div className={styles.notice}>
                            <p>
                                {l10n.t(
                                    'The document read reached the source-detail policy check; this is not an endpoint or RU-limit error. Strict mode cannot verify all required source details. Review the saved evidence for the observed missing details. Raising budgets or retrying unchanged settings will not resolve this block.',
                                )}
                            </p>
                            <p>
                                {l10n.t(
                                    'Keep strict mode to require exact preservation, or explicitly choose observed-values conversion under "When source details cannot be verified" and save connection settings. Your inventory is retained; no restart is needed. Changed evidence must be rechecked, and reduced conversion requires a separate scoped approval. Nothing is approved or switched automatically.',
                                )}
                            </p>
                            <Button
                                disabled={disabled || configurationDirty}
                                onClick={() => {
                                    const settings = connectionSettingsRef.current;
                                    if (settings) {
                                        settings.open = true;
                                        const policy =
                                            settings.querySelector<HTMLSelectElement>('#graph-document-fidelity');
                                        policy?.scrollIntoView?.({ block: 'center' });
                                        policy?.focus({ preventScroll: true });
                                    }
                                }}
                            >
                                {l10n.t('Review source-detail policy')}
                            </Button>
                        </div>
                    )}
                {showActions && (
                    <div className={styles.operationActions}>
                        <div className={styles.actionButtons}>
                            {operation === 'source-enumerate' && enumerationStopped && (
                                <Button
                                    disabled={disabled}
                                    onClick={() => void action(() => run('source-enumerate'))}
                                    aria-description={l10n.t(
                                        'Review request, duration and RU limits for a new bounded preparation. The stopped run and its evidence remain preserved.',
                                    )}
                                >
                                    {l10n.t('Review run settings')}
                                </Button>
                            )}
                            <Button
                                ref={operation === nextOperation ? nextRunRef : undefined}
                                disabled={
                                    phaseDisabled ||
                                    (operation === 'extract' &&
                                        Boolean(
                                            project && !hasCurrentSourceCompatibility(project, currentInputHash),
                                        )) ||
                                    (operation === 'source-compatibility' &&
                                        Boolean(project && !hasFidelityInventory(project)))
                                }
                                aria-label={l10n.t('{action} for {phase}', {
                                    action: operationActionLabel,
                                    phase: label,
                                })}
                                aria-description={extractionPrerequisite}
                                icon={<PlayRegular aria-hidden={true} />}
                                onClick={() => void action(() => run(operation))}
                            >
                                {operationActionLabel}
                            </Button>
                            {(operation === 'load' ||
                                operation === 'resume' ||
                                operation === 'publish' ||
                                operation === 'cleanup' ||
                                operation === 'source-populate' ||
                                operation === 'source-cleanup') && (
                                <Button
                                    disabled={phaseDisabled}
                                    aria-label={l10n.t('{action} for {phase}', {
                                        action: authorizationLabel,
                                        phase: label,
                                    })}
                                    onClick={() => void action(() => client.approve.mutate(operation))}
                                >
                                    {authorizationLabel}
                                </Button>
                            )}
                            {operation === 'target-probe' && (
                                <Button
                                    disabled={phaseDisabled}
                                    aria-label={l10n.t('{action} for {phase}', {
                                        action: authorizationLabel,
                                        phase: label,
                                    })}
                                    onClick={() =>
                                        void action(() =>
                                            client.approve.mutate({
                                                operation,
                                                outputPath: probeGraph,
                                            }),
                                        )
                                    }
                                >
                                    {authorizationLabel}
                                </Button>
                            )}
                            {(operation === 'source-compatibility' || operation === 'extract') &&
                                project?.source?.sourceMode === 'cosmos-documents' &&
                                project.mapping.fidelity === 'reduced' && (
                                    <Button
                                        disabled={phaseDisabled}
                                        onClick={() => void action(() => client.approve.mutate('reduced-fidelity'))}
                                    >
                                        {l10n.t('Authorize reduced document fidelity')}
                                    </Button>
                                )}
                            {(operation === 'rewrite-apply' || operation === 'fixture-test') && (
                                <Button
                                    disabled={phaseDisabled}
                                    aria-label={l10n.t('{action} for {phase}', {
                                        action: authorizationLabel,
                                        phase: label,
                                    })}
                                    onClick={() => void action(() => client.approve.mutate({ operation, outputPath }))}
                                >
                                    {authorizationLabel}
                                </Button>
                            )}
                            {project?.phases[operation]?.evidence.map((file, index) => {
                                const evidenceLabel = l10n.t('Evidence {number}', { number: index + 1 });
                                return (
                                    <Button
                                        key={file}
                                        onClick={() => void action(() => client.openArtifact.mutate(file))}
                                        aria-description={
                                            file.includes('-host-stop-')
                                                ? l10n.t(
                                                      'Extension-host stop report, including sampled activity and the observed effective deadline when available.',
                                                  )
                                                : l10n.t('Worker-produced report for this step.')
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
                        </div>
                        {extractionPrerequisite && <p>{extractionPrerequisite}</p>}
                        {operation === 'validate' && project?.source?.sourceMode === 'cosmos-documents' && (
                            <p>
                                {l10n.t(
                                    'Document-source validation verifies that the target matches the owned canonical conversion and SHACL. It does not restore unobservable source metadata or numeric runtime types, prove Gremlin semantics, or establish strict cutover equivalence. Those readiness gates remain separate.',
                                )}
                            </p>
                        )}
                        {operation === 'source-enumerate' &&
                            (operation === nextPhase?.[0] || enumerationStopped) &&
                            (enumerationReview > 0 || enumerationRequestLimited) &&
                            project && (
                                <GraphPreparation
                                    key={`${project.runId}-${currentInputHash}`}
                                    project={project}
                                    currentInputHash={currentInputHash}
                                    disabled={disabled}
                                    executionDisabled={phaseDisabled}
                                    reviewVersion={enumerationReview}
                                    checkConnections={
                                        nextOperation === 'preflight' ? () => run('preflight') : undefined
                                    }
                                    action={action}
                                    run={run}
                                    onStarted={() => {
                                        void action(async () => {
                                            const current = await client.load.query();
                                            applyProject(current.project);
                                            setCurrentInputHash(current.currentInputHash);
                                            if (
                                                current.project &&
                                                (current.project.phases['source-enumerate']?.status === 'running' ||
                                                    (current.project.runId !== project.runId &&
                                                        hasFidelityInventory(current.project)))
                                            )
                                                setContinuation({
                                                    operation: 'source-enumerate',
                                                    runId: current.project.runId,
                                                });
                                        });
                                    }}
                                    enumerationOnly
                                />
                            )}
                    </div>
                )}
            </article>
        );
    };
    return (
        <main className={styles.root}>
            <header className={styles.hero}>
                <div className={styles.eyebrow}>
                    <span>{l10n.t('Experimental migration workbench')}</span>
                </div>
                <h1>{l10n.t('Gremlin to RDF migration')}</h1>
                <p className={styles.purpose}>
                    {l10n.t(
                        'Move your Cosmos DB Gremlin graph to Apache Jena while preserving graph identity and repeated property values.',
                    )}
                </p>
                <p className={styles.notice}>
                    {l10n.t(
                        'Bring existing databases; this tool does not create them. Verify live endpoint compatibility before migration. Local samples do not verify your live source.',
                    )}
                </p>
                <div className={styles.bridge} aria-label={l10n.t('Migration path: Gremlin source to RDF target')}>
                    <span className={styles.bridgeNode}>
                        <DatabaseRegular aria-hidden={true} className={styles.sourceIcon} />
                        {l10n.t('Gremlin source')}
                    </span>
                    <ArrowRightRegular aria-hidden={true} className={styles.bridgeArrow} />
                    <span className={styles.bridgeNode}>
                        <DatabaseRegular aria-hidden={true} className={styles.targetIcon} />
                        {l10n.t('Jena / Fuseki RDF target')}
                    </span>
                </div>
            </header>
            <nav aria-label={l10n.t('Migration sequence overview')}>
                <p>{l10n.t('Migration sequence overview')}</p>
                <div className={styles.stepRail}>
                    {[l10n.t('Connect'), l10n.t('Review'), l10n.t('Transfer'), l10n.t('Verify')].map((step, index) => (
                        <div className={styles.step} key={step}>
                            <span className={styles.stepNumber} aria-hidden={true}>
                                {index + 1}
                            </span>
                            <span>{step}</span>
                        </div>
                    ))}
                </div>
            </nav>
            {!trusted && (
                <p className={styles.alert} role="alert">
                    {l10n.t(
                        'Trust this workspace before starting workers, storing credentials or authorizing operations.',
                    )}
                </p>
            )}
            {diagnostic && (
                <p className={styles.alert} role="alert">
                    {diagnostic}
                </p>
            )}
            <p>
                <output aria-live="off">{progress}</output>
            </p>
            {busy && (
                <p className={styles.notice} aria-live="off">
                    {l10n.t('Current operation: {phase}. Observed elapsed time in this panel: {seconds} seconds.', {
                        phase:
                            Object.entries(project?.phases ?? {})
                                .filter(([, phase]) => phase.status === 'running')
                                .map(([phase]) => operations().find(([operation]) => operation === phase)?.[1] ?? phase)
                                .join(', ') || l10n.t('Preparing worker'),
                        seconds: elapsedSeconds,
                    })}
                </p>
            )}
            {!project ? (
                <Button
                    disabled={!trusted}
                    onClick={() => void action(async () => applyProject(await client.create.mutate()))}
                >
                    {l10n.t('Create graph migration project')}
                </Button>
            ) : (
                <>
                    {nextLabel && (
                        <section
                            aria-label={l10n.t('Continue migration')}
                            className={mergeClasses(styles.section, styles.activeStep)}
                        >
                            <h2>{nextLabel}</h2>
                            {inventoryAdopted && nextOperation === 'preflight' && (
                                <p>
                                    {l10n.t(
                                        'Your verified inventory is saved. Recheck connections for the new run; do not repeat enumeration.',
                                    )}
                                </p>
                            )}
                            <Button
                                disabled={disabled || setupDirty || configurationDirty}
                                onClick={() => {
                                    nextRunRef.current?.scrollIntoView?.({ block: 'center' });
                                    nextRunRef.current?.focus();
                                }}
                            >
                                {l10n.t('Go to next unfinished step')}
                            </Button>
                            {setupDirty && (
                                <p>
                                    {l10n.t(
                                        'Configuration has unsaved edits. Use Save settings and restart migration to begin with those settings instead of continuing the old run.',
                                    )}
                                </p>
                            )}
                            {configurationDirty && (
                                <p>
                                    {l10n.t('Save your advanced configuration edits before continuing or restarting.')}
                                </p>
                            )}
                        </section>
                    )}
                    <details
                        ref={connectionSettingsRef}
                        className={styles.section}
                        open={!project.source || !project.target}
                    >
                        <summary>{l10n.t('Configuration and mapping')}</summary>
                        <section aria-labelledby="graph-configuration">
                            <div className={styles.sectionHeader}>
                                <DatabaseRegular aria-hidden={true} className={styles.sourceIcon} />
                                <h2 id="graph-configuration">{l10n.t('Configuration and mapping')}</h2>
                            </div>
                            <GraphConnectionSetup
                                project={project}
                                suggestions={suggestions}
                                disabled={disabled || configurationDirty}
                                onDirtyChange={setSetupDirty}
                                save={async (value) => {
                                    setDiagnostic('');
                                    let saved = false;
                                    try {
                                        setBusy(true);
                                        const result = await client.saveSetup.mutate(value);
                                        if (result.status === 'blocked') {
                                            setBusy(false);
                                            setDiagnostic(formatDiagnostic(result.diagnostic));
                                            return result.diagnostic;
                                        }
                                        saved = true;
                                        await reload();
                                        setSetupDirty(false);
                                        if (value.restart) {
                                            setContinuation({ operation: 'preflight', runId: result.runId });
                                            setEnumerationReview(0);
                                            setRestartFocusRunId(result.runId);
                                            setBusy(true);
                                            await client.run.mutate({
                                                operation: 'preflight',
                                                query: '',
                                                sourcePath: '',
                                                outputPath: '',
                                            });
                                            await reload();
                                        }
                                        return value.restart
                                            ? l10n.t(
                                                  'Restart saved. New run: {runId}. Step 1 connection checks have started; the previous run is archived.',
                                                  { runId: result.runId },
                                              )
                                            : l10n.t('Connection settings saved.');
                                    } catch {
                                        setBusy(false);
                                        const diagnostic = hostDiagnostic(
                                            new GraphDiagnosticError(
                                                saved ? 'SETUP_RESTART_START_FAILED' : 'SETUP_SAVE_FAILED',
                                                'connection-settings',
                                            ),
                                        );
                                        setDiagnostic(formatDiagnostic(diagnostic));
                                        return diagnostic;
                                    }
                                }}
                            />
                            <div className={styles.actionButtons}>
                                <Button
                                    disabled={disabled}
                                    onClick={() => void action(() => client.credentials.mutate())}
                                >
                                    {l10n.t('Store credentials securely')}
                                </Button>
                            </div>
                            <p className={styles.notice}>
                                {l10n.t(
                                    'Credentials use secure references, never connection fields. Source uses env:GRAPH_SOURCE_KEY by default; configure a target reference in advanced settings for protected Fuseki.',
                                )}
                            </p>
                            <details className={styles.optionalDetails}>
                                <summary>{l10n.t('Advanced configuration and mapping')}</summary>
                                <div className={styles.detailsBody}>
                                    <p>
                                        {l10n.t(
                                            'Edit endpoint, typed identity-manifest and mapping fields in this strict project document. Use env:GRAPH_* references only; never enter credentials here. Input changes invalidate previous evidence and approvals.',
                                        )}
                                    </p>
                                    <p>
                                        {l10n.t(
                                            'Set limits.maximumOperationMillis to bound the entire editor worker operation. At the deadline, work is paused and an unresponsive worker is requested to terminate after five seconds. Checkpoints remain subject to reconciliation.',
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
                                            disabled={busy || setupDirty}
                                            onChange={(_, data) => {
                                                configurationDirtyRef.current = true;
                                                setConfigurationDirty(true);
                                                setConfiguration(data.value);
                                            }}
                                        />
                                    </Field>
                                    <Button
                                        disabled={disabled || setupDirty}
                                        onClick={() =>
                                            void action(async () => {
                                                await client.save.mutate(configuration);
                                                configurationDirtyRef.current = false;
                                                setConfigurationDirty(false);
                                                await reload();
                                            })
                                        }
                                    >
                                        {l10n.t('Save configuration')}
                                    </Button>
                                    <Field label={l10n.t('Dedicated target probe graph IRI')}>
                                        <Input
                                            value={probeGraph}
                                            disabled={busy}
                                            onChange={(_, data) => setProbeGraph(data.value)}
                                        />
                                    </Field>
                                    <p>
                                        {project.source?.sourceMode === 'cosmos-documents'
                                            ? l10n.t(
                                                  'The default .NET Cosmos SDK document feed is read-only and mode-bound. It preserves raw documents and detects supported JSON layouts unless an explicit layout override is saved. Missing source details are reported separately from Gremlin semantic equivalence. There is no automatic Gremlin fallback or live benchmark.',
                                              )
                                            : l10n.t(
                                                  'Enumeration is read-only and requires explicit partition values, string IDs, a quiesced or immutable source, and request/duration budgets. Review the generated identity manifest before configuring a new extraction run.',
                                              )}
                                    </p>
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
                                            onClick={() =>
                                                void action(() => client.approve.mutate('best-effort-source'))
                                            }
                                        >
                                            {l10n.t('Authorize best-effort source')}
                                        </Button>
                                    )}
                                    {(project.mapping.fidelity === 'reduced' ||
                                        project.consistency === 'best-effort') && (
                                        <p>
                                            {l10n.t(
                                                'These separately approved modes cannot establish strict cutover equivalence. Reduced source projection omits occurrence IDs/meta-properties and explicitly excluded properties.',
                                            )}
                                        </p>
                                    )}
                                </div>
                            </details>
                        </section>
                    </details>
                    <details
                        className={styles.section}
                        open={nextPhase?.[0] === 'assess' || nextPhase?.[0] === 'functional'}
                    >
                        <summary>{l10n.t('Query and application review')}</summary>
                        <section aria-labelledby="graph-review">
                            <div className={styles.sectionHeader}>
                                <DocumentSearchRegular aria-hidden={true} />
                                <h2 id="graph-review">{l10n.t('Query and application review')}</h2>
                            </div>
                            <Field
                                required
                                label={l10n.t('Restricted Gremlin read query')}
                                hint={l10n.t('Required for query comparison.')}
                            >
                                <Textarea
                                    value={query}
                                    rows={3}
                                    disabled={busy}
                                    onChange={(_, data) => setQuery(data.value)}
                                />
                            </Field>
                            <details className={styles.optionalDetails}>
                                <summary>{l10n.t('Optional application adaptation')}</summary>
                                <div className={styles.detailsBody}>
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
                                </div>
                            </details>
                        </section>
                    </details>
                    <section className={styles.section} aria-labelledby="graph-phases">
                        <div className={styles.sectionHeader}>
                            <ArrowUploadRegular aria-hidden={true} />
                            <h2 id="graph-phases">{l10n.t('Phases, approvals and evidence')}</h2>
                        </div>
                        <div className={styles.actionButtons}>
                            {continuation && (
                                <Button onClick={() => setContinuation(undefined)}>
                                    {l10n.t('Stop automatic progression')}
                                </Button>
                            )}
                            <Button
                                disabled={!trusted || !busy}
                                onClick={() => {
                                    setContinuation(undefined);
                                    void action(() => client.pause.mutate());
                                }}
                            >
                                {l10n.t('Pause after current batch')}
                            </Button>
                            <Button
                                disabled={!trusted || !busy}
                                onClick={() => {
                                    setContinuation(undefined);
                                    void action(() => client.cancel.mutate());
                                }}
                            >
                                {l10n.t('Cancel safely')}
                            </Button>
                            <Button onClick={() => void action(reload)}>{l10n.t('Reload persisted state')}</Button>
                        </div>
                        <p>
                            {l10n.t(
                                'After you start a step, input-free checks continue automatically. Preparation budgets, scoped approvals and target writes still require your review. Failures, pause and cancellation stop automatic progression; reopening the panel does not start operations.',
                            )}
                        </p>
                        <div ref={requiredStepsRef} aria-label={l10n.t('Required migration steps')}>
                            {corePhases.map((phase, index) => {
                                const active = phase[0] === nextPhase?.[0];
                                const completed = phaseComplete(phase[0]);
                                return (
                                    <details
                                        key={`${project.runId}:${phase[0]}`}
                                        ref={index === 0 ? firstStepRef : undefined}
                                        tabIndex={-1}
                                        open={
                                            active ||
                                            (!completed && phase[0] === 'source-enumerate' && enumerationStopped)
                                        }
                                        data-active={active}
                                        data-completed={completed}
                                        className={mergeClasses(styles.optionalDetails, active && styles.activeStep)}
                                    >
                                        <summary aria-current={active ? 'step' : undefined}>
                                            {l10n.t('Step {number}: {phase}', { number: index + 1, phase: phase[1] })}
                                            {' — '}
                                            <span
                                                className={mergeClasses(
                                                    styles.status,
                                                    statusStyles[phaseStatus(phase[0])],
                                                )}
                                            >
                                                {phaseLabels()[phaseStatus(phase[0])]}
                                            </span>
                                        </summary>
                                        {renderOperation(phase, true, false)}
                                    </details>
                                );
                            })}
                        </div>
                        {!nextPhase && (
                            <p>
                                <output>
                                    {l10n.t(
                                        'Data migration completed and validated. Production cutover is not certified by data transfer alone.',
                                    )}
                                </output>
                            </p>
                        )}
                        <details
                            className={styles.optionalDetails}
                            open={phaseStatus('report') === 'warning' || phaseStatus('report') === 'blocked'}
                        >
                            <summary>
                                {l10n.t('Step 10 (optional): Evaluate readiness')}
                                {' — '}
                                <span className={mergeClasses(styles.status, statusStyles[phaseStatus('report')])}>
                                    {phaseLabels()[phaseStatus('report')]}
                                </span>
                            </summary>
                            <p>
                                {l10n.t(
                                    'Optional production-cutover evaluation. Missing evaluation evidence is a warning, not a failed data migration. Publication remains prohibited without a current passing readiness report; execution and safety errors remain blocked.',
                                )}
                            </p>
                            {renderOperation(['report', operationLabels.get('report')!], true, false)}
                        </details>
                        <details className={styles.optionalDetails}>
                            <summary>{l10n.t('Optional reviewed evidence import')}</summary>
                            <div className={styles.detailsBody}>
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
                                <div className={styles.actionButtons}>
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
                                </div>
                            </div>
                        </details>
                        <details className={styles.optionalDetails}>
                            <summary>
                                <WrenchRegular aria-hidden={true} /> {l10n.t('All phases and tools')}
                            </summary>
                            <div className={styles.detailsBody}>
                                <p>
                                    {l10n.t('Optional tools are manual and do not replace live endpoint verification.')}
                                </p>
                                <h3>{l10n.t('Optional tools and advanced operations')}</h3>
                                {operations()
                                    .filter(([operation]) => !coreOperations.has(operation) && operation !== 'report')
                                    .map((phase) => renderOperation(phase))}
                            </div>
                        </details>
                    </section>
                </>
            )}
        </main>
    );
}
