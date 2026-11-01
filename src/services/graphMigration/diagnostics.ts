/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import * as l10n from '@vscode/l10n';
import { z } from 'zod';
import { type GraphProject } from './contracts';
export const safeDiagnosticSchema = z
    .object({
        status: z.literal('blocked'),
        code: z.string().regex(/^[A-Z][A-Z0-9_]{1,79}$/u),
        stage: z.string().regex(/^[a-z][a-z0-9-]{0,79}$/u),
        remediation: z.string().min(1).max(1000),
        httpStatus: z.number().int().min(100).max(599).optional(),
        gremlinStatus: z.number().int().min(100).max(999).optional(),
        sourceStatus: z.number().int().min(100).max(599).optional(),
        sourceSubstatus: z.number().int().min(0).max(2147483647).optional(),
        templateId: z.enum(['source-keyset-page-v1', 'source-keyset-page-v2']).optional(),
    })
    .strict();
export type SafeDiagnostic = z.infer<typeof safeDiagnosticSchema>;
export function formatDiagnostic(diagnostic: SafeDiagnostic): string {
    const statuses = [
        diagnostic.templateId ?? '',
        diagnostic.httpStatus !== undefined ? `HTTP ${diagnostic.httpStatus}` : '',
        diagnostic.gremlinStatus !== undefined ? `Gremlin ${diagnostic.gremlinStatus}` : '',
        diagnostic.sourceStatus !== undefined ? `source ${diagnostic.sourceStatus}` : '',
        diagnostic.sourceSubstatus !== undefined ? `substatus ${diagnostic.sourceSubstatus}` : '',
    ].filter(Boolean);
    return `${diagnostic.stage} (${[diagnostic.code, ...statuses].join(', ')}): ${diagnostic.remediation}`;
}
export class GraphDiagnosticError extends Error {
    constructor(
        readonly code: string,
        readonly stage: string,
    ) {
        super(code);
    }
}
export function hostDiagnostic(error: unknown, stage = 'preparation'): SafeDiagnostic {
    const code = error instanceof GraphDiagnosticError ? error.code : 'HOST_PREPARATION_UNKNOWN';
    const remediation =
        code === 'CREDENTIAL_REFERENCE_UNAVAILABLE'
            ? l10n.t('Use Store credentials securely for the configured source/target reference, then retry.')
            : code === 'WORKER_PACKAGE_INVALID'
              ? l10n.t(
                    'Reinstall the complete current VSIX or rebuild its pinned workers. Do not replace individual JAR files.',
                )
              : code === 'SOURCE_CONFIGURATION_REQUIRED'
                ? l10n.t('Save the Gremlin source connection settings first.')
                : code === 'SOURCE_INVENTORY_REQUIRED'
                  ? l10n.t(
                        'Complete read-only identity enumeration first. Its prior failure is separate evidence; an empty bootstrap inventory cannot establish source fidelity.',
                    )
                  : code === 'SOURCE_COMPATIBILITY_NOT_CURRENT'
                    ? l10n.t(
                          'Extraction needs a succeeded source fidelity check for the current inputs. Resolve the enumeration blocker first, then run fidelity. Connection and mapping success do not replace that evidence.',
                      )
                    : l10n.t(
                          'Review the saved configuration, owned artifact paths and confirmed state. The cause is not yet classified; do not assume an inventory problem caused it.',
                      );
    return { status: 'blocked', code, stage: error instanceof GraphDiagnosticError ? error.stage : stage, remediation };
}
export function hasFidelityInventory(project: GraphProject): boolean {
    return Boolean(
        project.source &&
        (project.source.identityManifestHash !== 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' ||
            project.artifacts.some(
                (a) => a.ownerRunId === project.runId && a.relativePath.startsWith('artifacts/adopted-inventory-'),
            )),
    );
}
export function hasCurrentSourceCompatibility(project: GraphProject, currentInputHash: string | undefined): boolean {
    const phase = project.phases['source-compatibility'];
    return Boolean(
        hasFidelityInventory(project) &&
        phase?.status === 'succeeded' &&
        currentInputHash !== undefined &&
        phase.inputHash === currentInputHash &&
        project.source?.compatibilityEvidence &&
        phase.evidence.includes(project.source.compatibilityEvidence) &&
        project.artifacts.some(
            (a) => a.ownerRunId === project.runId && a.relativePath === project.source?.compatibilityEvidence,
        ),
    );
}
export function safeDiagnostics(value: unknown): SafeDiagnostic[] {
    if (!value || typeof value !== 'object') return [];
    const candidates = 'diagnostics' in value && Array.isArray(value.diagnostics) ? value.diagnostics : [value];
    return candidates.slice(0, 20).flatMap((item) => {
        const parsed = safeDiagnosticSchema.safeParse(item);
        return parsed.success ? [parsed.data] : [];
    });
}
