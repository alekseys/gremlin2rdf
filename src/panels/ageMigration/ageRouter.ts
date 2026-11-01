/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import { initWebviewTrpc, type BaseRouterContext, type TypedEventSink } from '@microsoft/vscode-ext-webview';
import { z } from 'zod';
import { ageOperationSchema, type AgeMessage } from '../../services/ageMigration/contracts';
const trpc = initWebviewTrpc<AgeContext>();
export interface AgeContext extends BaseRouterContext {
    eventSink: TypedEventSink<AgeMessage | { type: 'diagnostic'; code: string }>;
    load(): Promise<string | null>;
    save(json: string): Promise<void>;
    run(operation: z.infer<typeof ageOperationSchema>, input: string, output: string): Promise<void>;
    authorize(input: string): Promise<void>;
    authorizeRewrite(input: string, output: string): Promise<void>;
    credentials(): Promise<void>;
    importArtifact(path: string): Promise<void>;
    artifact(path: string): Promise<void>;
    stop(): void;
}
export const ageRouter = trpc.router({
    load: trpc.publicProcedure.query(({ ctx }) => ctx.load()),
    save: trpc.publicProcedure.input(z.string().max(1048576)).mutation(({ ctx, input }) => ctx.save(input)),
    run: trpc.publicProcedure
        .input(
            z
                .object({ operation: ageOperationSchema, input: z.string().max(16384), output: z.string().max(1024) })
                .strict(),
        )
        .mutation(({ ctx, input }) => ctx.run(input.operation, input.input, input.output)),
    authorize: trpc.publicProcedure.input(z.string().max(1024)).mutation(({ ctx, input }) => ctx.authorize(input)),
    authorizeRewrite: trpc.publicProcedure
        .input(z.object({ input: z.string().max(1024), output: z.string().max(1024) }).strict())
        .mutation(({ ctx, input }) => ctx.authorizeRewrite(input.input, input.output)),
    credentials: trpc.publicProcedure.mutation(({ ctx }) => ctx.credentials()),
    importArtifact: trpc.publicProcedure
        .input(z.enum(['inventory.json', 'source-compatibility.json', 'app.cs', 'canonical.jsonl']))
        .mutation(({ ctx, input }) => ctx.importArtifact(input)),
    artifact: trpc.publicProcedure.input(z.string().max(1024)).mutation(({ ctx, input }) => ctx.artifact(input)),
    stop: trpc.publicProcedure.mutation(({ ctx }) => ctx.stop()),
    events: trpc.publicProcedure.subscription(async function* ({ ctx }) {
        for await (const event of ctx.eventSink) {
            if (ctx.signal?.aborted) return;
            yield event;
        }
    }),
});
export const ageCaller = trpc.createCallerFactory;
export type AgeRouter = typeof ageRouter;
