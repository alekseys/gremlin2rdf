# Manual cutover, ownership and rollback

Keep production applications on their prior reviewed configuration until current evidence establishes source fidelity,
structural/SHACL equivalence, supported query bags/sequences, typed CRUD, before/after application behavior, recovery,
reference load/soak and dependency review. Live source/target compatibility must match the exact endpoint configuration.
Do not interpret a successful build, generated report or local fixture as that approval.

Review exact immutable canonical/derived/metadata/validation graph IRIs. Confirm a quiesced or immutable source and
least-privilege credentials. Verify every required artifact checksum, run ID and input hash. Record all unsupported
application constructs as manual actions; a missing build/equivalence report remains blocked.

Authorize publication separately with the current readiness artifact checksum and exact graph scopes. Manual
publication produces a reviewed application configuration artifact; it does not provision resources, change secrets,
restart applications or route production traffic. Version-pointer publication is explicitly unsupported without
independent atomicity/cache-coherence proof.

An operator deploys the reviewed configuration using their approved production process. Before target-only writes,
rollback restores the previous reviewed application configuration. After target-only writes, require reconciliation:
reverse synchronization is not implemented, so a configuration rollback alone can lose new writes.

Cleanup is a separate authorization. Verify current run ownership and exact graph names before each delete, including
the temporary validation graph and ownership-lock capability. A partial, conflicting or foreign marker must not be
adopted or removed. Source fixture cleanup uses its exact dedicated-container identity, plan, run prefix, checksum,
ownership journal and RU budget; it never deletes an entire unreviewed container.

Do not perform source mutations, target capability writes, production cutover, publication or cleanup merely because a
local smoke or workflow-dispatch job ran. Preserve uncertain mutations/checkpoints and request scoped reconciliation.
