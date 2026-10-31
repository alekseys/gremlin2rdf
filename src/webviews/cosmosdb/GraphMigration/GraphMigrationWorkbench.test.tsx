/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

// @vitest-environment jsdom

import { AriaLiveAnnouncer, FluentProvider, webLightTheme } from '@fluentui/react-components';
import { act, render, screen, waitFor } from '@testing-library/react';
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
describe('graph workbench boundaries and keyboard access', () => {
    it('labels configuration/review fields and dispatches only the selected operation by keyboard', async () => {
        render(<GraphMigrationWorkbench />, { wrapper: Wrapper });
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
        render(<GraphMigrationWorkbench />, { wrapper: Wrapper });
        const button = await screen.findByRole('button', { name: 'Authorize for Load approved target graphs' });
        expect(button).toHaveTextContent('Authorize');
        expect(button).toHaveAccessibleName('Authorize for Load approved target graphs');
        await userEvent.setup().click(button);
        expect(mocks.approve).toHaveBeenCalledWith('load');
        expect(mocks.run).not.toHaveBeenCalled();
    });
    it('blocks executable actions in an untrusted workspace', async () => {
        mocks.load.mockResolvedValue({ project, trusted: false });
        render(<GraphMigrationWorkbench />, { wrapper: Wrapper });
        expect(await screen.findByRole('button', { name: 'Run for Load approved target graphs' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Store credentials securely' })).toBeDisabled();
    });
    it('announces worker failure with actionable recovery instead of a success state', async () => {
        render(<GraphMigrationWorkbench />, { wrapper: Wrapper });
        await screen.findByRole('textbox', { name: 'Graph project JSON' });
        act(() => listener({ type: 'diagnostic', code: 'WORKER_DISCONNECTED' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('Reload the project');
    });
    it('dispatches read-only compatibility independently from target approval', async () => {
        render(<GraphMigrationWorkbench />, { wrapper: Wrapper });
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
        render(<GraphMigrationWorkbench />, { wrapper: Wrapper });
        await userEvent.setup().click(await screen.findByRole('button', { name: 'Authorize reduced fidelity' }));
        await userEvent.setup().click(screen.getByRole('button', { name: 'Authorize best-effort source' }));
        expect(mocks.approve).toHaveBeenCalledWith('reduced-fidelity');
        expect(mocks.approve).toHaveBeenCalledWith('best-effort-source');
        expect(mocks.run).not.toHaveBeenCalled();
    });
    it('keeps source fixture writes separate from target writes and explicit approval', async () => {
        render(<GraphMigrationWorkbench />, { wrapper: Wrapper });
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
        render(<GraphMigrationWorkbench />, { wrapper: Wrapper });
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
        render(<GraphMigrationWorkbench />, { wrapper: Wrapper });
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
