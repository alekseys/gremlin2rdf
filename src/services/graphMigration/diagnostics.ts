/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import * as l10n from '@vscode/l10n';
import { z } from 'zod';
import { type GraphProject } from './contracts';
const ruAmount = z.string().regex(/^[0-9]{1,30}(?:\.[0-9]{1,20})?$/u);
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
        ruBudget: ruAmount.optional(),
        ruConsumed: ruAmount.optional(),
        ruRemaining: ruAmount.optional(),
        ruPerSecond: ruAmount.optional(),
        operationLimitMillis: z.number().int().min(1000).max(86400000).optional(),
        elapsedMillis: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).optional(),
        artifactAction: z.enum(['create-pending', 'write-pending', 'flush-pending', 'atomic-replace']).optional(),
        artifactAttempts: z.number().int().min(1).max(5).optional(),
        chargeObservation: z
            .enum([
                'missing-total',
                'partial-charge-only',
                'invalid-total',
                'unconfirmed-in-flight',
                'previous-unobservable',
            ])
            .optional(),
    })
    .strict();
export type SafeDiagnostic = z.infer<typeof safeDiagnosticSchema>;
export function formatDiagnostic(diagnostic: SafeDiagnostic): string {
    const statuses = [
        diagnostic.artifactAction ?? '',
        diagnostic.artifactAttempts !== undefined
            ? l10n.t('local storage attempts {count}', { count: diagnostic.artifactAttempts })
            : '',
        diagnostic.templateId ?? '',
        diagnostic.chargeObservation ?? '',
        diagnostic.httpStatus !== undefined ? `HTTP ${diagnostic.httpStatus}` : '',
        diagnostic.gremlinStatus !== undefined ? `Gremlin ${diagnostic.gremlinStatus}` : '',
        diagnostic.sourceStatus !== undefined ? `source ${diagnostic.sourceStatus}` : '',
        diagnostic.sourceSubstatus !== undefined ? `substatus ${diagnostic.sourceSubstatus}` : '',
        diagnostic.ruBudget !== undefined ? l10n.t('total allowance {amount} RU', { amount: diagnostic.ruBudget }) : '',
        diagnostic.ruConsumed !== undefined ? l10n.t('consumed {amount} RU', { amount: diagnostic.ruConsumed }) : '',
        diagnostic.ruRemaining !== undefined ? l10n.t('remaining {amount} RU', { amount: diagnostic.ruRemaining }) : '',
        diagnostic.ruPerSecond !== undefined ? l10n.t('pacing {amount} RU/s', { amount: diagnostic.ruPerSecond }) : '',
        diagnostic.operationLimitMillis !== undefined
            ? l10n.t('operation limit {seconds} seconds', {
                  seconds: diagnostic.operationLimitMillis / 1000,
              })
            : '',
        diagnostic.elapsedMillis !== undefined
            ? l10n.t('observed host elapsed {seconds} seconds', {
                  seconds: Math.floor(diagnostic.elapsedMillis / 1000),
              })
            : '',
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
export function hasCurrentEnumerationDeadline(project: GraphProject, currentHash: string | undefined): boolean {
    const phase = project.phases['source-enumerate'];
    return Boolean(
        currentHash &&
        phase?.inputHash === currentHash &&
        ['paused', 'blocked', 'failed'].includes(phase.status) &&
        phase.blockers.some((blocker) =>
            /\b(?:OPERATION_DEADLINE(?:_PARTIAL_STATE_PRESERVED)?|SOURCE_ENUMERATION_DURATION_LIMIT)\b/u.test(blocker),
        ),
    );
}
export function hostDiagnostic(error: unknown, stage = 'preparation'): SafeDiagnostic {
    const code = error instanceof GraphDiagnosticError ? error.code : 'HOST_PREPARATION_UNKNOWN';
    const remediation =
        code === 'WORKER_PREVIOUS_STOP_REASON_UNKNOWN'
            ? l10n.t(
                  'This earlier run is paused but no stop acknowledgement or report was recorded. Its exact stop reason is unknown; a timeout, operator stop or resource problem is not inferred. Preserve existing checkpoints and request journals. Guided Prepare must verify them before Resume or a newly reviewed bounded run.',
              )
            : code === 'OPERATION_DEADLINE_PARTIAL_STATE_PRESERVED' || code === 'OPERATION_DEADLINE'
              ? l10n.t(
                    'The configured overall operation time limit was reached; this is not a completed enumeration or migration. Preserved checkpoints and RU journals still require verification. Review the execution duration separately from the total RU, RU/s and enumeration limits before starting another reviewed run; an interrupted request may have unknown spend.',
                )
              : code === 'WORKER_TERMINATION_REQUESTED_PARTIAL_STATE_PRESERVED'
                ? l10n.t(
                      'The worker did not finish within the five-second stop grace period and termination was requested. Partial artifacts and RU journals are preserved, not certified complete. Verify checkpoint and in-flight request accounting before any retry.',
                  )
                : code === 'OPERATOR_PAUSED' || code === 'OPERATOR_CANCELLED'
                  ? l10n.t(
                        'The operator requested a stop. Partial artifacts and checkpoints are preserved, not certified complete. Guided Prepare offers Resume only when the owned checkpoint, budgets and request journal permit it; unknown in-flight spend requires a newly reviewed run.',
                    )
                  : code === 'WORKER_DISCONNECTED'
                    ? l10n.t(
                          'The owned worker exited without a valid completion acknowledgement. Partial state is preserved; do not infer success or zero request cost. Verify the owned checkpoints and RU journal before retrying.',
                      )
                    : code === 'CREDENTIAL_REFERENCE_UNAVAILABLE'
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
