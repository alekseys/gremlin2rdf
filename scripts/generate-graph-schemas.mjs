/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { mkdir, writeFile } from 'node:fs/promises';
import { z } from 'zod';
import {
    canonicalRecordSchema,
    graphProjectSchema,
    identityManifestEntrySchema,
    mappingSchema,
    populationPlanSchema,
    fixtureCatalogSchema,
    queryFixtureSchema,
    mutationFixtureSchema,
    javaRewritePlanSchema,
    workerMessageSchema,
    artifactEnvelopeSchema,
    approvalSchema,
    translationSchema,
    typedValueSchema,
} from '../src/services/graphMigration/contracts.ts';
import { performancePolicySchema } from '../src/services/graphMigration/performance.ts';

const folder = new URL('../schemas/graph-migration/', import.meta.url);
await mkdir(folder, { recursive: true });
for (const [name, schema] of Object.entries({
    project: graphProjectSchema,
    mapping: mappingSchema,
    canonical: canonicalRecordSchema,
    protocol: workerMessageSchema,
    'source-identity': identityManifestEntrySchema,
    'source-population': populationPlanSchema,
    'fixture-catalog': fixtureCatalogSchema,
    'query-fixture': queryFixtureSchema,
    'mutation-fixture': mutationFixtureSchema,
    'java-rewrite-plan': javaRewritePlanSchema,
    'performance-policy': performancePolicySchema,
    'artifact-envelope': artifactEnvelopeSchema,
    approval: approvalSchema,
    translation: translationSchema,
    'typed-value': typedValueSchema,
})) {
    const document = { ...z.toJSONSchema(schema), $id: `urn:gremlin2rdf:schema:v1:${name}` };
    if (name === 'project') {
        const source = document.properties.source.anyOf.find((branch) => branch.type === 'object');
        if (!source) throw new Error('Project source schema is missing its object contract');
        source.allOf = [
            {
                if: { required: ['sourceMode'], properties: { sourceMode: { const: 'cosmos-documents' } } },
                then: { required: ['documentEndpoint', 'documentProfile'] },
            },
        ];
        source.properties.documentEndpoint.format = 'uri';
        source.properties.documentEndpoint.pattern = '^https://[^/?#@]+/?$';
    }
    await writeFile(new URL(`${name}.schema.json`, folder), JSON.stringify(document, null, 2) + '\n');
}
