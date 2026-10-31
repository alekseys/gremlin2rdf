/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { copyFile, mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readBounded } from '../src/services/graphMigration/boundedIO.ts';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.join(root, 'docs', 'graph-migration', 'release');
await mkdir(output, { recursive: true });
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const javaBytes = await readBounded(path.join(root, 'java', 'target', 'bom.json'), 16777216);
const javaBom = JSON.parse(javaBytes.toString('utf8'));
await copyFile(path.join(root, 'java', 'target', 'bom.json'), path.join(output, 'java-sbom.json'));
const licenses = await readBounded(
    path.join(root, 'java', 'target', 'generated-sources', 'license', 'THIRD-PARTY.txt'),
    16777216,
);
await writeFile(path.join(output, 'java-third-party.txt'), licenses);
const components = (javaBom.components ?? []).filter(
    (entry) =>
        typeof entry.purl === 'string' &&
        entry.purl.startsWith('pkg:maven/') &&
        entry.group !== 'io.github.alekseys.gremlin2rdf',
);
const packages = [...new Set(components.map((entry) => entry.purl))].sort();
const findings = new Map(packages.map((name) => [name, new Set()]));
let pending = packages.map((purl) => ({ package: { purl } }));
let queried = [...packages],
    scanStatus = 'succeeded',
    scanCode = null;
try {
    for (let page = 0; pending.length && page < 20; page++) {
        const response = await fetch('https://api.osv.dev/v1/querybatch', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ queries: pending }),
            signal: AbortSignal.timeout(30000),
        });
        if (!response.ok) {
            scanStatus = 'blocked';
            scanCode = `OSV_HTTP_${response.status}`;
            break;
        }
        const chunks = [];
        let size = 0;
        for await (const chunk of response.body) {
            size += chunk.length;
            if (size > 16777216) throw new Error('OSV_RESPONSE_BOUND');
            chunks.push(chunk);
        }
        const reply = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        if (!Array.isArray(reply.results) || reply.results.length !== pending.length)
            throw new Error('OSV_RESPONSE_SHAPE');
        const next = [],
            nextPackages = [];
        for (let i = 0; i < reply.results.length; i++) {
            const result = reply.results[i];
            for (const vulnerability of result.vulns ?? []) {
                if (typeof vulnerability.id !== 'string' || vulnerability.id.length > 200)
                    throw new Error('OSV_ID_BOUND');
                findings.get(queried[i]).add(vulnerability.id);
            }
            if (result.next_page_token) {
                if (typeof result.next_page_token !== 'string' || result.next_page_token.length > 65536)
                    throw new Error('OSV_PAGE_TOKEN_BOUND');
                next.push({ package: { purl: queried[i] }, page_token: result.next_page_token });
                nextPackages.push(queried[i]);
            }
        }
        pending = next;
        queried = nextPackages;
    }
    if (pending.length && scanStatus === 'succeeded') {
        scanStatus = 'blocked';
        scanCode = 'OSV_PAGINATION_BOUND';
    }
} catch (error) {
    scanStatus = 'blocked';
    scanCode =
        error instanceof TypeError
            ? 'OSV_NETWORK_UNAVAILABLE'
            : error instanceof Error && error.name === 'TimeoutError'
              ? 'OSV_REQUEST_TIMEOUT'
              : 'OSV_RESPONSE_UNCONFIRMED';
}
const known = [...findings].map(([purl, ids]) => ({ purl, ids: [...ids].sort() })).filter((entry) => entry.ids.length);
const osv = {
    schemaVersion: 1,
    status: scanStatus,
    code: scanCode,
    database: 'https://api.osv.dev/v1/querybatch',
    sbomSha256: hash(javaBytes),
    packagesQueried: packages.length,
    findings: known,
    impactAssessment:
        'not-executed; known package/version advisories are release-review blockers, not exploitability claims',
    observedAt: new Date().toISOString(),
};
await writeFile(path.join(output, 'java-vulnerability-report.json'), JSON.stringify(osv, null, 2) + '\n');
function npm(args) {
    return process.platform === 'win32'
        ? spawnSync(process.env.ComSpec ?? 'cmd.exe', ['/d', '/s', '/c', `npm ${args.join(' ')}`], {
              cwd: root,
              encoding: 'utf8',
              maxBuffer: 16777216,
          })
        : spawnSync('npm', args, { cwd: root, encoding: 'utf8', maxBuffer: 16777216 });
}
const nodeSbom = npm(['sbom', '--sbom-format', 'cyclonedx', '--omit=dev']);
if (!nodeSbom.error && nodeSbom.status === 0)
    await writeFile(path.join(output, 'extension-sbom.json'), nodeSbom.stdout);
const audit = npm(['audit', '--omit=dev', '--json']);
let nodeAudit;
try {
    nodeAudit = JSON.parse(audit.stdout);
} catch {
    nodeAudit = null;
}
const auditExecuted = !audit.error && [0, 1].includes(audit.status) && nodeAudit?.metadata?.vulnerabilities;
if (auditExecuted)
    await writeFile(
        path.join(output, 'extension-vulnerability-report.json'),
        JSON.stringify(nodeAudit, null, 2) + '\n',
    );
const unknownLicenses = components.filter((entry) => !Array.isArray(entry.licenses) || !entry.licenses.length).length;
const unresolved =
    known.reduce((sum, entry) => sum + entry.ids.length, 0) +
    (auditExecuted ? nodeAudit.metadata.vulnerabilities.total : 1) +
    unknownLicenses +
    (scanStatus === 'succeeded' ? 0 : 1) +
    (nodeSbom.status === 0 && !nodeSbom.error ? 0 : 1);
const report = {
    schemaVersion: 1,
    status: unresolved ? 'blocked' : 'succeeded',
    sbomSha256: hash(javaBytes),
    licenseReportSha256: hash(licenses),
    vulnerabilityScanExecuted: scanStatus === 'succeeded' && Boolean(auditExecuted),
    unresolvedReleaseBlockers: unresolved,
    javaVulnerabilityPackages: known.length,
    unknownJavaLicenseDeclarations: unknownLicenses,
    extensionVulnerabilities: auditExecuted ? nodeAudit.metadata.vulnerabilities : 'blocked-audit-unconfirmed',
    extensionSbom: nodeSbom.status === 0 && !nodeSbom.error ? 'succeeded' : 'blocked-generator-unconfirmed',
    sourceDriverPolicy:
        'Pinned 3.4.13 is isolated from Jena; do not override incompatible driver dependencies to hide advisories.',
    remediation:
        'Review license declarations and advisories; apply only supported compatibility-verified dependency fixes. Preserve baseline extension findings separately. A blocked audit never authorizes release.',
    observedAt: new Date().toISOString(),
};
await writeFile(path.join(output, 'dependency-report.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report));
