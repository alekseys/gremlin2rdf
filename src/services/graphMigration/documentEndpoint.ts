/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

/** Expand only an unambiguous bare hostname; leave full URLs and invalid input for endpoint validation. */
export function normalizeDocumentEndpoint(value: string): string {
    const text = value.trim();
    const bare =
        /^([a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*)(?::([0-9]{1,5}))?\/?$/iu.exec(text);
    return bare ? `https://${bare[1]}:${bare[2] ?? '443'}/` : text;
}
