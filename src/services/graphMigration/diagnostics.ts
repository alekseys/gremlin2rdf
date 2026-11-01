/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import * as l10n from '@vscode/l10n';
import { z } from 'zod';
export const safeDiagnosticSchema = z
    .object({
        status: z.literal('blocked'),
        code: z.string().regex(/^[A-Z][A-Z0-9_]{1,79}$/u),
        stage: z.string().regex(/^[a-z][a-z0-9-]{0,79}$/u),
        remediation: z.string().min(1).max(1000),
    })
    .strict();
export type SafeDiagnostic = z.infer<typeof safeDiagnosticSchema>;
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
                : l10n.t(
                      'Review the saved configuration, owned artifact paths and confirmed state. The cause is not yet classified; do not assume an inventory problem caused it.',
                  );
    return { status: 'blocked', code, stage: error instanceof GraphDiagnosticError ? error.stage : stage, remediation };
}
export function safeDiagnostics(value: unknown): SafeDiagnostic[] {
    if (!value || typeof value !== 'object') return [];
    const candidates = 'diagnostics' in value && Array.isArray(value.diagnostics) ? value.diagnostics : [value];
    return candidates.slice(0, 20).flatMap((item) => {
        const parsed = safeDiagnosticSchema.safeParse(item);
        return parsed.success ? [parsed.data] : [];
    });
}
