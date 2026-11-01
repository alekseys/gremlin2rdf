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

const project = { source: null, target: null, consistency: 'immutable' } as GraphProject;
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
    expect(screen.getByRole('textbox', { name: 'Gremlin endpoint' })).toHaveValue('account.gremlin.cosmos.azure.com');
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
            }),
        ),
    );
    expect(screen.queryByRole('textbox', { name: 'Graph project JSON' })).not.toBeInTheDocument();
});
