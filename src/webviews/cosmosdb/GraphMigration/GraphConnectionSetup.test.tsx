/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

// @vitest-environment jsdom

import { FluentProvider, webLightTheme } from '@fluentui/react-components';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { type GraphProject } from '../../../services/graphMigration/contracts';
import { GraphConnectionSetup } from './GraphConnectionSetup';

const project = {
    source: null,
    target: null,
    consistency: 'immutable',
    mapping: { fidelity: 'strict' },
} as GraphProject;
it('shows inferred connections and explicit consistency while keeping technical defaults out of the form', async () => {
    const save = vi.fn(async () => {});
    render(
        <FluentProvider theme={webLightTheme}>
            <GraphConnectionSetup
                project={project}
                disabled={false}
                save={save}
                suggestions={{
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
                }}
            />
        </FluentProvider>,
    );
    expect(screen.getByRole('combobox', { name: 'Source read mode' })).toHaveValue('cosmos-documents');
    expect(screen.queryByRole('textbox', { name: 'Gremlin endpoint' })).not.toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Cosmos .NET SDK document endpoint' })).toHaveValue('');
    expect(screen.getByRole('textbox', { name: 'Partition key path' })).toHaveValue('/tenant');
    expect(screen.getByRole('combobox', { name: 'Source consistency' })).toHaveValue('');
    const user = userEvent.setup();
    await user.selectOptions(screen.getByRole('combobox', { name: 'Source consistency' }), 'quiesced');
    const button = screen.getByRole('button', { name: 'Save connection settings' });
    expect(button).toHaveAccessibleName('Save connection settings');
    await user.click(button);
    await waitFor(() =>
        expect(save).toHaveBeenCalledWith(
            expect.objectContaining({
                host: 'account.gremlin.cosmos.azure.com',
                consistency: 'quiesced',
                sourceMode: 'cosmos-documents',
            }),
        ),
    );
    expect(screen.queryByRole('textbox', { name: 'Graph project JSON' })).not.toBeInTheDocument();
});
it('retains a saved legacy Gremlin mode until an explicit selection, without network or automatic fallback', async () => {
    const save = vi.fn(async () => {});
    const legacy = {
        ...project,
        source: {
            host: 'account.gremlin.cosmos.azure.com',
            database: 'db',
            container: 'graph',
            partitionKeyPath: '/pk',
        },
    } as GraphProject;
    render(
        <FluentProvider theme={webLightTheme}>
            <GraphConnectionSetup project={legacy} disabled={false} save={save} />
        </FluentProvider>,
    );
    expect(screen.getByRole('combobox', { name: 'Source read mode' })).toHaveValue('gremlin');
    const user = userEvent.setup();
    await user.selectOptions(screen.getByRole('combobox', { name: 'Source read mode' }), 'cosmos-documents');
    expect(screen.queryByRole('textbox', { name: 'Gremlin endpoint' })).not.toBeInTheDocument();
    await user.type(
        screen.getByRole('textbox', { name: 'Cosmos .NET SDK document endpoint' }),
        'https://account.documents.azure.com:443/',
    );
    await user.click(screen.getByRole('button', { name: 'Save connection settings' }));
    expect(save).toHaveBeenCalledWith(
        expect.objectContaining({
            sourceMode: 'cosmos-documents',
            documentEndpoint: 'https://account.documents.azure.com:443/',
            documentProfile: 'auto-v1',
        }),
    );
});
it('expands a bare document host on blur and restart, with field guidance above the inputs', async () => {
    const save = vi.fn(async () => {});
    render(
        <FluentProvider theme={webLightTheme}>
            <GraphConnectionSetup project={project} disabled={false} save={save} />
        </FluentProvider>,
    );
    const user = userEvent.setup();
    const endpoint = screen.getByRole('textbox', { name: 'Cosmos .NET SDK document endpoint' });
    expect(endpoint).toHaveAccessibleName('Cosmos .NET SDK document endpoint');
    expect(endpoint).toHaveAccessibleDescription(expect.stringContaining('https:// and :443/ automatically'));
    await user.type(endpoint, 'my-account.documents.azure.com');
    await user.tab();
    expect(endpoint).toHaveValue('https://my-account.documents.azure.com:443/');
    await user.clear(endpoint);
    await user.type(endpoint, 'my-account.documents.azure.com:443');
    await user.click(screen.getByRole('button', { name: 'Save settings and restart migration' }));
    expect(save).toHaveBeenCalledWith(
        expect.objectContaining({
            restart: true,
            documentEndpoint: 'https://my-account.documents.azure.com:443/',
        }),
    );
    const hint = document.getElementById(endpoint.getAttribute('aria-describedby')!);
    expect(hint).toHaveStyle({ order: '-1' });
    expect(endpoint.closest('.fui-Field')).toHaveStyle({ display: 'flex', flexDirection: 'column' });
    expect(screen.getByRole('textbox', { name: 'Cosmos DB database' })).toHaveAccessibleDescription(
        expect.stringContaining('1-255 characters'),
    );
    expect(screen.getByRole('textbox', { name: 'Document-source pacing (RU/s)' })).toHaveAccessibleDescription(
        expect.stringContaining('1 to 1000000000'),
    );
});
it('preserves unsaved settings across phase snapshots and restarts with the displayed draft', async () => {
    const save = vi.fn(async () => {});
    const onDirtyChange = vi.fn();
    const legacy = {
        ...project,
        source: {
            host: 'account.gremlin.cosmos.azure.com',
            database: 'db',
            container: 'graph',
            partitionKeyPath: '/pk',
        },
    } as GraphProject;
    const props = { project: legacy, disabled: false, save, onDirtyChange };
    const { rerender } = render(<GraphConnectionSetup {...props} />);
    const user = userEvent.setup();
    await user.selectOptions(screen.getByRole('combobox', { name: 'Source read mode' }), 'cosmos-documents');
    await user.type(
        screen.getByRole('textbox', { name: 'Cosmos .NET SDK document endpoint' }),
        'https://account.documents.azure.com/',
    );
    rerender(<GraphConnectionSetup {...props} project={{ ...legacy, phases: {} }} />);
    expect(screen.getByRole('combobox', { name: 'Source read mode' })).toHaveValue('cosmos-documents');
    expect(screen.getByRole('textbox', { name: 'Cosmos .NET SDK document endpoint' })).toHaveValue(
        'https://account.documents.azure.com/',
    );
    expect(screen.getByRole('combobox', { name: 'JSON property layout' })).toHaveValue('auto-v1');
    expect(
        screen.getByRole('combobox', { name: 'When source details cannot be verified' }),
    ).toHaveAccessibleDescription(expect.stringContaining('cannot recreate them'));
    expect(onDirtyChange).toHaveBeenLastCalledWith(true);
    const restart = screen.getByRole('button', { name: 'Save settings and restart migration' });
    expect(restart).toHaveAccessibleName('Save settings and restart migration');
    await user.click(restart);
    expect(save).toHaveBeenCalledWith(
        expect.objectContaining({
            restart: true,
            sourceMode: 'cosmos-documents',
            documentEndpoint: 'https://account.documents.azure.com/',
            documentProfile: 'auto-v1',
        }),
    );
});
it('retains existing best-effort consistency instead of replacing it with an unsavable blank value', async () => {
    const save = vi.fn(async () => {});
    const current = {
        ...project,
        consistency: 'best-effort',
        source: {
            host: 'account.gremlin.cosmos.azure.com',
            database: 'db',
            container: 'graph',
            partitionKeyPath: '/pk',
        },
    } as GraphProject;
    render(<GraphConnectionSetup project={current} disabled={false} save={save} />);
    expect(screen.getByRole('combobox', { name: 'Source consistency' })).toHaveValue('best-effort');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Save settings and restart migration' }));
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ restart: true, consistency: 'best-effort' }));
});
