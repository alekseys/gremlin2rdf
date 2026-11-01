/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

// @vitest-environment jsdom

import { AriaLiveAnnouncer, FluentProvider, webLightTheme } from '@fluentui/react-components';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { type PropsWithChildren } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { type GraphEvent } from '../../../panels/graphMigration/graphRouter';
import { graphProjectSchema, inputHash } from '../../../services/graphMigration/contracts';
import { GraphMigrationWorkbench } from './GraphMigrationWorkbench';

const mocks = vi.hoisted(() => ({
    load: vi.fn(),
    create: vi.fn(),
    save: vi.fn(),
    saveSetup: vi.fn(),
    run: vi.fn(),
    approve: vi.fn(),
    credentials: vi.fn(),
    cancel: vi.fn(),
    pause: vi.fn(),
    openArtifact: vi.fn(),
    importEvidence: vi.fn(),
    subscribe: vi.fn(),
    preparation: vi.fn(),
    prepareSource: vi.fn(),
    resumePreparation: vi.fn(),
}));
vi.mock('@microsoft/vscode-ext-webview/react', () => ({
    useTrpcClient: () => client,
}));
const client = {
    load: { query: mocks.load },
    create: { mutate: mocks.create },
    save: { mutate: mocks.save },
    saveSetup: { mutate: mocks.saveSetup },
    run: { mutate: mocks.run },
    approve: { mutate: mocks.approve },
    credentials: { mutate: mocks.credentials },
    cancel: { mutate: mocks.cancel },
    pause: { mutate: mocks.pause },
    openArtifact: { mutate: mocks.openArtifact },
    importEvidence: { mutate: mocks.importEvidence },
    events: { subscribe: mocks.subscribe },
    preparation: { query: mocks.preparation },
    prepareSource: { mutate: mocks.prepareSource },
    resumePreparation: { mutate: mocks.resumePreparation },
};
const project = graphProjectSchema.parse({
    schemaVersion: 1,
    migrationKind: 'cosmos-gremlin-to-jena',
    baselineRepositorySha: '22e3f2cccbfcc6622cacbf1873b9c620acba085e',
    runId: 'fixture',
    protocolVersion: 1,
    workerVersion: '0.1.0',
    source: null,
    target: null,
    mapping: {
        schemaVersion: 1,
        baseIri: 'urn:fixture:',
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
        requestTimeoutMillis: 1000,
        maxRetries: 0,
        queueCapacity: 100,
        writerConcurrency: 1,
        minimumFreeDiskBytes: 0,
    },
    consistency: 'immutable',
    phases: {},
    approvals: [],
    applicationHashes: {},
    artifacts: [],
});
let listener: (event: GraphEvent) => void;
function bootstrapProject() {
    const source = {
        scope: 'fixture',
        host: 'localhost',
        port: 443,
        database: 'db',
        container: 'graph',
        partitionKeyPath: '/pk',
        authenticationRef: null,
        extractionStrategy: 'verified-identity-manifest',
        identityManifest: 'identities.ndjson',
        identityManifestHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
        compatibilityEvidence: 'artifacts/fidelity.json',
        ruBudget: '1000',
        customCaRef: null,
        syntheticTestContainer: false,
    };
    const configured = graphProjectSchema.parse({ ...project, source });
    const passed = { status: 'succeeded', inputHash: inputHash(configured), evidence: [], blockers: [] };
    return graphProjectSchema.parse({
        ...configured,
        phases: {
            preflight: passed,
            assess: passed,
            mapping: passed,
            'source-enumerate': {
                status: 'blocked',
                inputHash: inputHash(configured),
                evidence: [],
                blockers: ['source-operation (ARTIFACT_MISSING): Missing local artifact.'],
            },
            'source-compatibility': { status: 'stale', inputHash: '0'.repeat(64), evidence: [], blockers: [] },
        },
    });
}
beforeEach(() => {
    vi.clearAllMocks();
    mocks.load.mockResolvedValue({ project, trusted: true });
    mocks.subscribe.mockImplementation((_: unknown, options: { onData: typeof listener }) => {
        listener = options.onData;
        return { unsubscribe: vi.fn() };
    });
});
function Wrapper({ children }: PropsWithChildren) {
    return (
        <FluentProvider theme={webLightTheme}>
            <AriaLiveAnnouncer>{children}</AriaLiveAnnouncer>
        </FluentProvider>
    );
}
function renderWorkbench() {
    const rendered = render(<GraphMigrationWorkbench />, { wrapper: Wrapper });
    // Existing boundary tests exercise the advanced controls, not the simplified initial view.
    void screen.findByText('Advanced configuration and mapping').then((summary) => {
        act(() => {
            for (const details of summary.closest('main')?.querySelectorAll('details') ?? []) {
                details.setAttribute('open', '');
            }
        });
    });
    return rendered;
}
describe('graph workbench boundaries and keyboard access', () => {
    it('routes the next enumeration step to guided review and preserved resume, never the default raw plan', async () => {
        const current = bootstrapProject();
        mocks.load.mockResolvedValue({ project: current, trusted: true, currentInputHash: inputHash(current) });
        mocks.preparation.mockResolvedValue({
            partitions: ['fixture'],
            origins: [],
            notices: [],
            coverage: 'unverified-candidates',
            resume: { plan: 'artifacts/enumeration-plan-fixture.json', output: 'artifacts/source-enumeration-fixture' },
        });
        render(<GraphMigrationWorkbench />, { wrapper: Wrapper });
        const next = await screen.findByRole('region', { name: 'Next unfinished migration step' });
        expect(within(next).getByRole('heading', { name: 'Enumerate read-only source identities' })).toBeVisible();
        expect(
            within(next).queryByRole('button', { name: 'Run for Enumerate read-only source identities' }),
        ).toBeNull();
        const review = within(next).getByRole('button', { name: 'Review guided read-only preparation' });
        expect(review).toHaveTextContent('Review guided read-only preparation');
        await userEvent.setup().click(review);
        const find = screen.getByRole('button', { name: 'Find partition candidates' });
        expect(find).toHaveFocus();
        expect(mocks.run).not.toHaveBeenCalled();
        await userEvent.setup().click(find);
        await userEvent
            .setup()
            .click(await screen.findByRole('button', { name: 'Resume preserved read-only preparation' }));
        expect(mocks.resumePreparation).toHaveBeenCalledOnce();
        expect(mocks.prepareSource).not.toHaveBeenCalled();
        expect(mocks.run).not.toHaveBeenCalled();
    });
    it('keeps bounded preparation disabled until candidate, ceilings and stable-source review are supplied', async () => {
        mocks.load.mockResolvedValue({ project: bootstrapProject(), trusted: true });
        mocks.preparation.mockResolvedValue({
            partitions: ['fixture'],
            origins: [],
            notices: [],
            coverage: 'unverified-candidates',
        });
        render(<GraphMigrationWorkbench />, { wrapper: Wrapper });
        await userEvent.setup().click(await screen.findByRole('button', { name: 'Find partition candidates' }));
        const start = await screen.findByRole('button', { name: 'Start bounded read-only preparation' });
        expect(start).toBeDisabled();
        await userEvent.setup().clear(screen.getByRole('spinbutton', { name: 'Maximum reserved request attempts' }));
        await userEvent
            .setup()
            .type(screen.getByRole('spinbutton', { name: 'Maximum reserved request attempts' }), '20');
        await userEvent.setup().clear(screen.getByRole('spinbutton', { name: 'Maximum duration (seconds)' }));
        await userEvent.setup().type(screen.getByRole('spinbutton', { name: 'Maximum duration (seconds)' }), '10');
        await userEvent.setup().click(screen.getByRole('checkbox', { name: /I confirm source writes are paused/ }));
        expect(start).not.toBeDisabled();
        await userEvent.setup().click(start);
        expect(mocks.prepareSource).toHaveBeenCalledWith({
            partitions: ['fixture'],
            maximumRequests: 20,
            maximumDurationSeconds: 10,
            ruBudget: '1000',
            confirmStableSource: true,
        });
        expect(mocks.run).not.toHaveBeenCalled();
        expect(mocks.resumePreparation).not.toHaveBeenCalled();
    });
    it('uses the host input snapshot to enable extraction only for current owned fidelity', async () => {
        const supplied = graphProjectSchema.parse({
            ...project,
            source: {
                scope: 'fixture',
                host: 'localhost',
                port: 443,
                database: 'db',
                container: 'graph',
                partitionKeyPath: '/pk',
                authenticationRef: null,
                extractionStrategy: 'verified-identity-manifest',
                identityManifest: 'identities.ndjson',
                identityManifestHash: '1'.repeat(64),
                compatibilityEvidence: 'artifacts/fidelity.json',
                ruBudget: null,
                customCaRef: null,
                syntheticTestContainer: false,
            },
        });
        const current = graphProjectSchema.parse({
            ...supplied,
            phases: {
                'source-compatibility': {
                    status: 'succeeded',
                    inputHash: inputHash(supplied),
                    blockers: [],
                    evidence: ['artifacts/fidelity.json'],
                },
            },
            artifacts: [
                {
                    relativePath: 'artifacts/fidelity.json',
                    ownerRunId: supplied.runId,
                    sha256: '2'.repeat(64),
                    sensitive: true,
                },
            ],
        });
        mocks.load.mockResolvedValue({ project: current, trusted: true, currentInputHash: inputHash(current) });
        renderWorkbench();
        const button = await screen.findByRole('button', { name: 'Run for Extract read-only source' });
        expect(button).not.toBeDisabled();
        act(() => listener({ type: 'project', project: current, currentInputHash: '0'.repeat(64) }));
        expect(button).toBeDisabled();
        expect(button).toHaveAccessibleDescription(/current, succeeded source fidelity/);
        expect(mocks.run).not.toHaveBeenCalled();
    });
    it('shows a safe failed substep and concrete remedy from the worker acknowledgement', async () => {
        render(<GraphMigrationWorkbench />, { wrapper: Wrapper });
        await screen.findByRole('button', { name: 'Check read-only connections' });
        act(() =>
            listener({
                type: 'worker',
                message: {
                    protocolVersion: 1,
                    type: 'result',
                    runId: 'fixture',
                    operationId: 'owned',
                    sequence: 1,
                    timestamp: new Date().toISOString(),
                    phase: 'complete',
                    payload: {
                        status: 'blocked',
                        diagnostics: [
                            {
                                status: 'blocked',
                                code: 'ENDPOINT_AUTHENTICATION',
                                stage: 'target-connectivity',
                                remediation: 'Renew the configured credential securely.',
                            },
                        ],
                    },
                },
            }),
        );
        expect(await screen.findByRole('alert')).toHaveTextContent('target-connectivity (ENDPOINT_AUTHENTICATION)');
        expect(screen.getByRole('alert')).toHaveTextContent('Renew the configured credential securely.');
    });
    it('retains safe HTTP status in current diagnostics and presents stale results only as history', async () => {
        mocks.load.mockResolvedValue({
            project: {
                ...project,
                phases: {
                    preflight: {
                        status: 'stale',
                        inputHash: '0'.repeat(64),
                        evidence: [],
                        blockers: [
                            'target-query (TARGET_ENDPOINT_NOT_FOUND, HTTP 404): Verify the existing dataset query path.',
                        ],
                    },
                },
            },
            trusted: true,
        });
        render(<GraphMigrationWorkbench />, { wrapper: Wrapper });
        const summary = await screen.findAllByText('Previous result — not a current connection check');
        expect(summary[0].closest('details')).not.toHaveAttribute('open');
        expect(screen.getAllByText(/target-query \(TARGET_ENDPOINT_NOT_FOUND/)[0]).not.toBeVisible();
        expect(mocks.run).not.toHaveBeenCalled();
        act(() =>
            listener({
                type: 'worker',
                message: {
                    protocolVersion: 1,
                    type: 'result',
                    runId: 'fixture',
                    operationId: 'owned',
                    sequence: 1,
                    timestamp: new Date().toISOString(),
                    phase: 'complete',
                    payload: {
                        status: 'blocked',
                        diagnostics: [
                            {
                                status: 'blocked',
                                code: 'TARGET_ENDPOINT_NOT_FOUND',
                                stage: 'target-query',
                                httpStatus: 404,
                                remediation: 'Verify the existing dataset query path.',
                            },
                        ],
                    },
                },
            }),
        );
        expect(await screen.findByRole('alert')).toHaveTextContent('TARGET_ENDPOINT_NOT_FOUND, HTTP 404');
        act(() => listener({ type: 'project', project }));
        await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    });
    it('keeps full JSON collapsed by default and saves the visible connection fields independently', async () => {
        mocks.load.mockResolvedValue({
            project,
            trusted: true,
            setup: {
                values: {
                    host: 'account.gremlin.cosmos.azure.com',
                    database: 'db',
                    container: 'graph',
                    partitionKeyPath: '/tenant',
                    datasetUrl: 'http://localhost:3030/graph',
                    consistency: '',
                },
                origins: { host: '.env' },
                notices: [],
            },
        });
        render(<GraphMigrationWorkbench />, { wrapper: Wrapper });
        await waitFor(() =>
            expect(screen.getByRole('textbox', { name: 'Gremlin endpoint' })).toHaveValue(
                'account.gremlin.cosmos.azure.com',
            ),
        );
        expect(screen.getByText('Advanced configuration and mapping').closest('details')).not.toHaveAttribute('open');
        expect(screen.getByLabelText('Graph project JSON')).not.toBeVisible();
        const user = userEvent.setup();
        await user.selectOptions(screen.getByRole('combobox', { name: 'Source consistency' }), 'quiesced');
        await user.click(screen.getByRole('button', { name: 'Save connection settings' }));
        expect(mocks.saveSetup).toHaveBeenCalledWith(
            expect.objectContaining({
                host: 'account.gremlin.cosmos.azure.com',
                partitionKeyPath: '/tenant',
                consistency: 'quiesced',
            }),
        );
        expect(mocks.run).not.toHaveBeenCalled();
    });
    it('introduces the RDF migration workflow while keeping experimental and live-compatibility boundaries clear', async () => {
        renderWorkbench();
        expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Gremlin to RDF migration');
        expect(screen.getByText('Experimental migration workbench')).toBeVisible();
        expect(screen.getByText(/Move your Cosmos DB Gremlin graph/)).toHaveTextContent(
            'preserving graph identity and repeated property values',
        );
        expect(screen.getByText(/Bring existing databases/)).toHaveTextContent(
            'Local samples do not verify your live source',
        );
        await screen.findByRole('textbox', { name: 'Graph project JSON' });
        expect(mocks.run).not.toHaveBeenCalled();
        expect(mocks.approve).not.toHaveBeenCalled();
    });
    it('highlights core work while leaving optional tools collapsed and never starting a benchmark', async () => {
        render(<GraphMigrationWorkbench />, { wrapper: Wrapper });
        await screen.findByText('All phases and tools');
        expect(screen.getByText('All phases and tools').closest('details')).not.toHaveAttribute('open');
        expect(screen.getByText('Optional application adaptation').closest('details')).not.toHaveAttribute('open');
        expect(screen.getByText('Optional reviewed evidence import').closest('details')).not.toHaveAttribute('open');
        expect(screen.getByRole('navigation', { name: 'Migration sequence overview' })).toBeVisible();
        expect(
            within(screen.getByRole('region', { name: 'Next unfinished migration step' })).getByRole('button', {
                name: 'Run for Check prerequisites',
            }),
        ).toBeVisible();
        expect(screen.getByText('Run private two-hour mixed soak')).not.toBeVisible();
        expect(screen.getByRole('textbox', { name: 'Gremlin endpoint' })).toBeRequired();
        expect(screen.getByRole('textbox', { name: 'Fuseki dataset URL' })).toBeRequired();
        expect(screen.getByRole('combobox', { name: 'Source consistency' })).toBeRequired();
        expect(mocks.run).not.toHaveBeenCalled();
        expect(mocks.approve).not.toHaveBeenCalled();
    });
    it('labels configuration/review fields and dispatches only the selected operation by keyboard', async () => {
        renderWorkbench();
        expect(await screen.findByRole('textbox', { name: 'Graph project JSON' })).toBeVisible();
        expect(screen.getByRole('textbox', { name: 'Restricted Gremlin read query' })).toBeVisible();
        expect(screen.getByRole('textbox', { name: 'Workspace-relative Java source path' })).toBeVisible();
        const button = screen.getByRole('button', { name: 'Run for Validate mapping' });
        expect(button).toHaveTextContent('Run');
        expect(button).toHaveAccessibleName('Run for Validate mapping');
        button.focus();
        await userEvent.setup().keyboard('{Enter}');
        await waitFor(() =>
            expect(mocks.run).toHaveBeenCalledWith({
                operation: 'mapping',
                query: 'g.V().count()',
                sourcePath: '',
                outputPath: 'artifacts/rewritten/GraphRepository.java',
            }),
        );
        expect(mocks.approve).not.toHaveBeenCalled();
    });
    it('separates approval review from executing graph writes', async () => {
        renderWorkbench();
        const button = await screen.findByRole('button', { name: 'Authorize for Load approved target graphs' });
        expect(button).toHaveTextContent('Authorize');
        expect(button).toHaveAccessibleName('Authorize for Load approved target graphs');
        await userEvent.setup().click(button);
        expect(mocks.approve).toHaveBeenCalledWith('load');
        expect(mocks.run).not.toHaveBeenCalled();
    });
    it('blocks executable actions in an untrusted workspace', async () => {
        mocks.load.mockResolvedValue({ project, trusted: false });
        renderWorkbench();
        expect(await screen.findByRole('button', { name: 'Run for Load approved target graphs' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Store credentials securely' })).toBeDisabled();
    });
    it('announces worker failure with actionable recovery instead of a success state', async () => {
        renderWorkbench();
        await screen.findByRole('textbox', { name: 'Graph project JSON' });
        act(() => listener({ type: 'diagnostic', code: 'WORKER_DISCONNECTED' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('Reload the project');
    });
    it('dispatches read-only compatibility independently from target approval', async () => {
        mocks.load.mockResolvedValue({
            project: {
                ...project,
                source: {
                    scope: 'fixture',
                    host: 'localhost',
                    port: 443,
                    database: 'db',
                    container: 'graph',
                    partitionKeyPath: '/pk',
                    authenticationRef: null,
                    extractionStrategy: 'verified-identity-manifest',
                    identityManifest: 'identities.ndjson',
                    identityManifestHash: '1'.repeat(64),
                    compatibilityEvidence: 'artifacts/compatibility.json',
                    ruBudget: null,
                    customCaRef: null,
                    syntheticTestContainer: false,
                },
            },
            trusted: true,
        });
        renderWorkbench();
        const button = await screen.findByRole('button', { name: 'Run for Probe read-only source compatibility' });
        expect(button).toHaveTextContent('Run');
        button.focus();
        await userEvent.setup().keyboard('{Enter}');
        await waitFor(() =>
            expect(mocks.run).toHaveBeenCalledWith({
                operation: 'source-compatibility',
                query: 'g.V().count()',
                sourcePath: '',
                outputPath: 'artifacts/rewritten/GraphRepository.java',
            }),
        );
        expect(mocks.approve).not.toHaveBeenCalled();
    });
    it('blocks empty-bootstrap fidelity after failed enumeration while keeping connections independently runnable', async () => {
        mocks.load.mockResolvedValue({
            project: {
                ...project,
                source: {
                    scope: 'fixture',
                    host: 'localhost',
                    port: 443,
                    database: 'db',
                    container: 'graph',
                    partitionKeyPath: '/pk',
                    authenticationRef: null,
                    extractionStrategy: 'verified-identity-manifest',
                    identityManifest: 'identities.ndjson',
                    identityManifestHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
                    compatibilityEvidence: 'artifacts/compatibility.json',
                    ruBudget: null,
                    customCaRef: null,
                    syntheticTestContainer: false,
                },
                phases: {
                    'source-enumerate': {
                        status: 'blocked',
                        inputHash: '1'.repeat(64),
                        evidence: [],
                        blockers: [
                            'source-enumeration-vertex-page (SOURCE_SCRIPT_REJECTED, source-keyset-page-v1, Gremlin 597, source 400): Template rejection; exact cause unknown.',
                        ],
                    },
                },
            },
            trusted: true,
        });
        renderWorkbench();
        expect(await screen.findByRole('button', { name: 'Check read-only source fidelity' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Run for Probe read-only source compatibility' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Run for Extract read-only source' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Run for Extract read-only source' })).toHaveAccessibleDescription(
            /current, succeeded source fidelity/,
        );
        expect(screen.getByRole('button', { name: 'Check read-only connections' })).not.toBeDisabled();
        expect(screen.getByText(/no source capability conclusion/)).toBeVisible();
        expect(mocks.run).not.toHaveBeenCalled();
        act(() =>
            listener({
                type: 'worker',
                message: {
                    protocolVersion: 1,
                    type: 'result',
                    runId: 'fixture',
                    operationId: 'owned',
                    sequence: 1,
                    timestamp: new Date().toISOString(),
                    phase: 'complete',
                    payload: {
                        status: 'blocked',
                        diagnostics: [
                            {
                                status: 'blocked',
                                code: 'SOURCE_SCRIPT_REJECTED',
                                stage: 'source-enumeration-vertex-page',
                                gremlinStatus: 597,
                                sourceStatus: 400,
                                sourceSubstatus: 1004,
                                remediation: 'Share only safe status evidence.',
                            },
                        ],
                    },
                },
            }),
        );
        expect(await screen.findByRole('alert')).toHaveTextContent('Gremlin 597, source 400, substatus 1004');
    });
    it('keeps reduced-fidelity and best-effort approvals separate from worker execution', async () => {
        mocks.load.mockResolvedValue({
            project: { ...project, mapping: { ...project.mapping, fidelity: 'reduced' }, consistency: 'best-effort' },
            trusted: true,
        });
        renderWorkbench();
        await userEvent.setup().click(await screen.findByRole('button', { name: 'Authorize reduced fidelity' }));
        await userEvent.setup().click(screen.getByRole('button', { name: 'Authorize best-effort source' }));
        expect(mocks.approve).toHaveBeenCalledWith('reduced-fidelity');
        expect(mocks.approve).toHaveBeenCalledWith('best-effort-source');
        expect(mocks.run).not.toHaveBeenCalled();
    });
    it('keeps source fixture writes separate from target writes and explicit approval', async () => {
        renderWorkbench();
        const label = 'Populate approved test container';
        await userEvent.setup().click(await screen.findByRole('button', { name: `Authorize for ${label}` }));
        expect(mocks.approve).toHaveBeenCalledWith('source-populate');
        expect(mocks.run).not.toHaveBeenCalled();
        await userEvent
            .setup()
            .click(screen.getByRole('button', { name: 'Run for Review bounded source population plan' }));
        expect(mocks.run).toHaveBeenCalledWith({
            operation: 'source-plan',
            query: 'g.V().count()',
            sourcePath: '',
            outputPath: 'artifacts/rewritten/GraphRepository.java',
        });
    });
    it('reviews exact new rewrite output separately from applying it and from fixture authorization', async () => {
        renderWorkbench();
        const output = await screen.findByRole('textbox', { name: 'Run-relative new Java output path' });
        const user = userEvent.setup();
        await user.clear(output);
        await user.type(output, 'artifacts/rewritten/Reviewed.java');
        await user.click(
            screen.getByRole('button', { name: 'Authorize for Apply approved Java rewrite to new output' }),
        );
        expect(mocks.approve).toHaveBeenCalledWith({
            operation: 'rewrite-apply',
            outputPath: 'artifacts/rewritten/Reviewed.java',
        });
        expect(mocks.run).not.toHaveBeenCalled();
        await user.click(screen.getByRole('button', { name: 'Run for Apply approved Java rewrite to new output' }));
        expect(mocks.run).toHaveBeenCalledWith({
            operation: 'rewrite-apply',
            query: 'g.V().count()',
            sourcePath: '',
            outputPath: 'artifacts/rewritten/Reviewed.java',
        });
    });
    it('imports a selected reviewed proof without running or authorizing a database operation', async () => {
        renderWorkbench();
        const user = userEvent.setup();
        await user.type(
            await screen.findByRole('textbox', { name: 'Run-relative reviewed report path' }),
            'artifacts/build-report.json',
        );
        expect(screen.getByRole('combobox', { name: 'Imported evidence phase' })).toBeVisible();
        await user.click(screen.getByRole('button', { name: 'Import reviewed evidence' }));
        expect(mocks.importEvidence).toHaveBeenCalledWith({
            relativePath: 'artifacts/build-report.json',
            phase: 'application-build',
        });
        expect(mocks.run).not.toHaveBeenCalled();
        expect(mocks.approve).not.toHaveBeenCalled();
    });
});
