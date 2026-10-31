/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { createHash } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { readBounded } from '../src/services/graphMigration/boundedIO.ts';
import { evaluatePerformance } from '../src/services/graphMigration/performance.ts';

if (process.argv.length < 5 || process.argv.length > 6)
    throw new Error('Usage: report.json policy.json new-output.json [baseline-report.json]');
async function read(name) {
    const bytes = await readBounded(name, 16777216);
    return { value: JSON.parse(bytes.toString('utf8')), sha256: createHash('sha256').update(bytes).digest('hex') };
}
const report = await read(process.argv[2]),
    policy = await read(process.argv[3]);
const baseline = process.argv[5] ? await read(process.argv[5]) : undefined;
const result = {
    ...evaluatePerformance(report.value, policy.value, baseline?.value),
    reportSha256: report.sha256,
    policySha256: policy.sha256,
    baselineSha256: baseline?.sha256 ?? null,
};
await writeFile(process.argv[4], JSON.stringify(result, null, 2) + '\n', { flag: 'wx' });
console.log(
    JSON.stringify({ status: result.status, operationalSlo: result.operationalSlo, baseline: result.baseline.status }),
);
if (result.status === 'failed' || result.baseline.status === 'investigate') process.exitCode = 2;
