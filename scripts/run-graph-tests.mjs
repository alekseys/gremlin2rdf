/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const windows = process.platform === 'win32';
const wrapper = path.join(root, 'java', windows ? 'mvnw.cmd' : 'mvnw');
const env = { ...process.env, GRAPH_TEST_APPROVAL_KEY: 'synthetic-test-only-key-not-a-live-credential' };
const result = windows
    ? spawnSync(process.env.ComSpec ?? 'cmd.exe', ['/d', '/s', '/c', `"${wrapper}" -f java\\pom.xml test -q`], {
          cwd: root,
          env,
          stdio: 'inherit',
          windowsVerbatimArguments: true,
      })
    : spawnSync(wrapper, ['-f', path.join(root, 'java', 'pom.xml'), 'test', '-q'], {
          cwd: root,
          env,
          stdio: 'inherit',
      });
if (result.error || result.status !== 0) throw new Error('Isolated graph tests failed');
