/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import vm from 'node:vm';
import ts from 'typescript';
import { toJSONSchema } from 'zod';

const root = path.resolve(import.meta.dirname, '..');
const file = path.join(root, 'src', 'services', 'ageMigration', 'contracts.ts');
const exports = {};
const code = ts.transpileModule(readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
// This evaluates only this repository's build-time contract module, never project/user configuration.
new vm.Script(code, { filename: file }).runInNewContext({ exports, require: createRequire(file) });
const output = path.join(root, 'schemas', 'age-migration');
mkdirSync(output, { recursive: true });
for (const [name, schema] of Object.entries({
    project: exports.ageProjectSchema,
    'worker-message': exports.ageMessageSchema,
    'worker-request': exports.ageRequestSchema,
}))
    writeFileSync(path.join(output, `${name}.schema.json`), JSON.stringify(toJSONSchema(schema), null, 2) + '\n');
