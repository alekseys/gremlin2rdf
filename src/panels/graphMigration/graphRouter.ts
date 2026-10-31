/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { initWebviewTrpc, type BaseRouterContext, type TypedEventSink } from '@microsoft/vscode-ext-webview';
import { z } from 'zod';
import { type GraphProject, type WorkerMessage } from '../../services/graphMigration/contracts';
import { graphEvidencePhases } from '../../services/graphMigration/evidence';

export const graphOperationSchema = z.enum([
    'preflight',
    'source-compatibility',
    'source-generate',
    'source-plan',
    'source-recipes',
    'source-rdf',
    'source-populate',
    'source-cleanup',
    'mapping-export',
    'assess',
    'mapping',
    'synthetic',
    'extract',
    'load',
    'resume',
    'validate',
    'translate',
    'rewrite-plan',
    'rewrite-apply',
    'functional',
    'report',
    'publish',
    'cleanup',
    'fixture-generate',
    'fixture-test',
    'benchmark-load',
    'benchmark-soak',
]);
export type GraphOperation = z.infer<typeof graphOperationSchema>;
export const graphApprovalOperationSchema = z.enum([
    'load',
    'resume',
    'publish',
    'cleanup',
    'source-populate',
    'source-cleanup',
    'reduced-fidelity',
    'best-effort-source',
    'rewrite-apply',
    'fixture-test',
]);
export type GraphApprovalOperation = z.infer<typeof graphApprovalOperationSchema>;
export const graphEvidencePhaseSchema = z.enum(graphEvidencePhases);
export type GraphEvidencePhase = z.infer<typeof graphEvidencePhaseSchema>;
export type GraphEvent =
    | { type: 'worker'; message: WorkerMessage }
    | { type: 'diagnostic'; code: string }
    | { type: 'project'; project: GraphProject };
export interface GraphRouterContext extends BaseRouterContext {
    eventSink: TypedEventSink<GraphEvent>;
    load(): Promise<{ project: GraphProject | null; trusted: boolean }>;
    create(): Promise<GraphProject>;
    save(json: string): Promise<void>;
    run(operation: GraphOperation, query: string, sourcePath: string, outputPath: string): Promise<void>;
    approve(operation: GraphApprovalOperation, outputPath: string): Promise<void>;
    openArtifact(relativePath: string): Promise<void>;
    importEvidence(relativePath: string, phase: GraphEvidencePhase): Promise<void>;
    credentials(): Promise<void>;
    cancel(): void;
    pause(): void;
}
const graph = initWebviewTrpc<GraphRouterContext>();
export const graphAppRouter = graph.router({
    load: graph.publicProcedure.query(({ ctx }) => ctx.load()),
    create: graph.publicProcedure.mutation(({ ctx }) => ctx.create()),
    save: graph.publicProcedure.input(z.string().max(1048576)).mutation(({ ctx, input }) => ctx.save(input)),
    run: graph.publicProcedure
        .input(
            z
                .object({
                    operation: graphOperationSchema,
                    query: z.string().max(4096).default(''),
                    sourcePath: z.string().max(500).default(''),
                    outputPath: z.string().max(500).default(''),
                })
                .strict(),
        )
        .mutation(({ ctx, input }) => ctx.run(input.operation, input.query, input.sourcePath, input.outputPath)),
    approve: graph.publicProcedure
        .input(
            z.union([
                graphApprovalOperationSchema,
                z
                    .object({
                        operation: graphApprovalOperationSchema,
                        outputPath: z.string().max(500).default(''),
                    })
                    .strict(),
            ]),
        )
        .mutation(({ ctx, input }) =>
            ctx.approve(
                typeof input === 'string' ? input : input.operation,
                typeof input === 'string' ? '' : input.outputPath,
            ),
        ),
    credentials: graph.publicProcedure.mutation(({ ctx }) => ctx.credentials()),
    openArtifact: graph.publicProcedure
        .input(z.string().max(500))
        .mutation(({ ctx, input }) => ctx.openArtifact(input)),
    importEvidence: graph.publicProcedure
        .input(
            z
                .object({
                    relativePath: z.string().min(1).max(500),
                    phase: graphEvidencePhaseSchema,
                })
                .strict(),
        )
        .mutation(({ ctx, input }) => ctx.importEvidence(input.relativePath, input.phase)),
    cancel: graph.publicProcedure.mutation(({ ctx }) => ctx.cancel()),
    pause: graph.publicProcedure.mutation(({ ctx }) => ctx.pause()),
    events: graph.publicProcedure.subscription(async function* ({ ctx }) {
        for await (const event of ctx.eventSink) {
            if (ctx.signal?.aborted) return;
            yield event;
        }
    }),
});
export const graphCallerFactory = graph.createCallerFactory;
export type GraphAppRouter = typeof graphAppRouter;
