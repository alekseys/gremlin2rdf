import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, mkdirSync, writeFileSync, statSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const rid = process.platform === 'win32' && process.arch === 'x64' ? 'win-x64' : undefined;
if (!rid) throw new Error('Document source packaging supports verified Windows x64 only.');
const sdk = process.env.DOCUMENT_SOURCE_DOTNET_SDK ?? process.env.AGE_DOTNET_SDK ?? 'dotnet';
const output = path.join(root, 'resources', 'graph-migration', 'document-source', rid);
mkdirSync(output, { recursive: true });
const result = spawnSync(
    sdk,
    [
        'publish',
        path.join(root, 'dotnet', 'DocumentSource', 'DocumentSource.csproj'),
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
    throw new Error('Document source publish failed; configure DOCUMENT_SOURCE_DOTNET_SDK to .NET 10.0.403.');
const files = {};
for (const file of readdirSync(output).sort()) {
    if (!statSync(path.join(output, file)).isFile())
        throw new Error('Unexpected non-leaf document source publish output.');
    files[file] = createHash('sha256')
        .update(readFileSync(path.join(output, file)))
        .digest('hex');
}
writeFileSync(
    path.join(root, 'resources', 'graph-migration', 'document-source', 'worker-manifest.json'),
    JSON.stringify({ schemaVersion: 1, rid, executable: 'DocumentSource.exe', sdkVersion: '3.58.0', files }, null, 2) +
        '\n',
);
