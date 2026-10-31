/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const windows = process.platform === 'win32';
const wrapper = path.join(root, 'java', windows ? 'mvnw.cmd' : 'mvnw');
const result = windows
    ? spawnSync(
          process.env.ComSpec ?? 'cmd.exe',
          ['/d', '/s', '/c', `"${wrapper}" -f java\\pom.xml -DskipTests package -q`],
          { cwd: root, stdio: 'inherit', windowsVerbatimArguments: true },
      )
    : spawnSync(wrapper, ['-f', path.join(root, 'java', 'pom.xml'), '-DskipTests', 'package', '-q'], {
          cwd: root,
          stdio: 'inherit',
      });
if (result.error || result.status !== 0) throw new Error('Pinned Java worker packaging failed');
const resources = path.join(root, 'resources', 'graph-migration');
await mkdir(resources, { recursive: true });
await copyFile(
    path.join(root, 'java', 'migration-engine', 'target', 'migration-engine-0.1.0.jar'),
    path.join(resources, 'worker.jar'),
);
await copyFile(
    path.join(root, 'java', 'cosmos-gremlin-source', 'target', 'cosmos-gremlin-source-0.1.0.jar'),
    path.join(resources, 'cosmos-gremlin-source-0.1.0.jar'),
);
await copyFile(
    path.join(root, 'java', 'graph-testkit', 'target', 'graph-testkit-0.1.0.jar'),
    path.join(resources, 'graph-testkit-0.1.0.jar'),
);
for (const mode of ['load', 'soak'])
    await copyFile(
        path.join(root, 'docs', 'graph-migration', 'benchmarks', `reference-${mode}.json`),
        path.join(resources, `reference-${mode}.json`),
    );
const sha256 = async (name) =>
    createHash('sha256')
        .update(await readFile(path.join(resources, name)))
        .digest('hex');
await writeFile(
    path.join(resources, 'worker-manifest.json'),
    JSON.stringify(
        {
            schemaVersion: 1,
            protocolVersion: 1,
            workerVersion: '0.1.0',
            minimumJava: 21,
            workerSha256: await sha256('worker.jar'),
            sourceSha256: await sha256('cosmos-gremlin-source-0.1.0.jar'),
            testkitSha256: await sha256('graph-testkit-0.1.0.jar'),
            jena: '6.2.0',
            cosmosDriver: '3.4.13',
        },
        null,
        2,
    ) + '\n',
);
