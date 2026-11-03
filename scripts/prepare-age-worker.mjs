/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import './export-age-schemas.mjs';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, mkdirSync, writeFileSync, copyFileSync, unlinkSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const sdk = process.env.AGE_DOTNET_SDK ?? 'dotnet';
const rid = process.platform === 'win32' && process.arch === 'x64' ? 'win-x64' : undefined;
if (!rid)
    throw new Error(
        'AGE packaging currently supports Windows x64 only. Build an explicitly verified RID before packaging elsewhere.',
    );
const output = path.join(root, 'resources', 'age-migration', rid);
mkdirSync(output, { recursive: true });
const result = spawnSync(
    sdk,
    [
        'publish',
        path.join(root, 'dotnet', 'AgeWorker', 'AgeWorker.csproj'),
        '-c',
        'Release',
        '-r',
        rid,
        '--self-contained',
        'true',
        '--nologo',
        '-p:RestoreLockedMode=true',
        '-p:DebugType=None',
        '-o',
        output,
    ],
    {
        cwd: path.join(root, 'dotnet'),
        stdio: 'inherit',
        shell: false,
        env: { ...process.env, DOTNET_CLI_TELEMETRY_OPTOUT: '1', DOTNET_GENERATE_ASPNET_CERTIFICATE: 'false' },
    },
);
if (result.error || result.status !== 0)
    throw new Error('AGE worker publish failed; configure AGE_DOTNET_SDK to a pinned .NET 10.0.403 SDK.');
const files = {};
copyFileSync(
    path.join(root, 'dotnet', 'AgeWorker', 'Fixtures', 'Repository.cs.txt'),
    path.join(output, 'Repository.cs.txt'),
);
for (const file of readdirSync(output).sort()) {
    if (file.endsWith('.pdb')) {
        unlinkSync(path.join(output, file));
        continue;
    }
    files[file] = createHash('sha256')
        .update(readFileSync(path.join(output, file)))
        .digest('hex');
}
writeFileSync(
    path.join(root, 'resources', 'age-migration', 'worker-manifest.json'),
    JSON.stringify(
        {
            schemaVersion: 1,
            protocolVersion: 1,
            rid,
            executable: 'AgeWorker.exe',
            files,
            sdk: '10.0.403',
            gremlin: '3.4.13',
            sourceCompatibility: 'requires-live-evidence',
        },
        null,
        2,
    ) + '\n',
);
