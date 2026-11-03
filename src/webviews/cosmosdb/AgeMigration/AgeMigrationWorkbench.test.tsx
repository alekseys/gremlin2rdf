/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

// @vitest-environment jsdom
import { AriaLiveAnnouncer, FluentProvider, webLightTheme } from '@fluentui/react-components';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgeMigrationWorkbench } from './AgeMigrationWorkbench';
const mocks = vi.hoisted(() => ({ load: vi.fn(), save: vi.fn(), run: vi.fn(), subscribe: vi.fn(), stop: vi.fn() }));
const client = {
    load: { query: mocks.load },
    save: { mutate: mocks.save },
    run: { mutate: mocks.run },
    events: { subscribe: mocks.subscribe },
    stop: { mutate: mocks.stop },
};
vi.mock('@microsoft/vscode-ext-webview/react', () => ({ useTrpcClient: () => client }));
beforeEach(() => {
    vi.clearAllMocks();
    mocks.load.mockResolvedValue(null);
    mocks.subscribe.mockReturnValue({ unsubscribe: vi.fn() });
});
afterEach(cleanup);
function show() {
    return render(
        <FluentProvider theme={webLightTheme}>
            <AriaLiveAnnouncer>
                <AgeMigrationWorkbench />
            </AriaLiveAnnouncer>
        </FluentProvider>,
    );
}
describe('AGE workbench smoke', () => {
    it('names mandatory inputs and keeps optional settings collapsed', () => {
        show();
        expect(screen.getByRole('textbox', { name: 'Cosmos Gremlin hostname' })).toHaveAttribute(
            'aria-required',
            'true',
        );
        expect(screen.getByRole('textbox', { name: 'PostgreSQL username' })).toHaveAttribute('aria-required', 'true');
        expect(screen.getByRole('button', { name: 'Load approved graph' })).toBeDisabled();
        expect(screen.getByLabelText('Advanced AGE project JSON')).not.toBeVisible();
        expect(screen.getByText('Optional: offline fixtures, C# assessment and advanced settings')).toBeVisible();
    });
    it('creates separate AGE state and previews Cypher without Java', async () => {
        const user = userEvent.setup();
        show();
        await user.click(screen.getByRole('button', { name: 'Save configuration' }));
        await waitFor(() => expect(mocks.save).toHaveBeenCalledOnce());
        const p = JSON.parse(mocks.save.mock.calls[0][0]);
        expect(p.migrationKind).toBe('cosmos-gremlin-to-age');
        expect(p.target).toBeNull();
        await user.click(screen.getByRole('button', { name: 'Preview Cypher' }));
        expect(mocks.run).toHaveBeenCalledWith({ operation: 'translate', input: 'g.V().count()', output: '' });
    });
});
