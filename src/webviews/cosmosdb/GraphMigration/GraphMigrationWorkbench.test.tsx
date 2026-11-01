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
import { graphProjectSchema } from '../../../services/graphMigration/contracts';
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
