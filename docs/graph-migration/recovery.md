# Checkpoints, ownership and recovery

The loader copies the selected canonical file into a bounded-memory, anchored, delete-on-close snapshot and verifies its
SHA-256 before any target write. Source population and cleanup use the same mechanism. Snapshot storage requires the input
size in addition to the configured free-disk reserve. A later edit to the selected file cannot change an approved mutation;
the final checksum check refuses a success report if the original file changed. Preserve the ownership journal/checkpoint
and restore the exact approved input before resuming. Never substitute a newly generated or merely similar input.

The local run lock excludes concurrent workers using the same artifact directory. The remote lock excludes workers using
the same run graphs. Acquisition uses one UUID for its entire bounded update/read reconciliation, including an update that
commits but loses its HTTP acknowledgement. Release reads and validates ownership, conditionally removes only its own
marker, and confirms absence or a valid replacement owner. Incomplete, malformed or conflicting markers are not adopted or
deleted. A previous worker can recover only after its recorded host hash/input match and local PID/start-time observation
prove the former process has ended. A foreign-host lock requires explicit operator verification, not automatic adoption.
Hostnames are not a distributed fencing authority; duplicate hostnames or copied ownership state are outside this proof.

Every lock mutation rechecks the exact current `target-write` approval. Expiry leaves the remote marker intact. Renew the
same run-scoped approval and end the former worker before recovery. Do not remove an active marker manually.

A target batch inserts its data and content/offset/count markers in one server update. The checkpoint advances only after
the marker matches the approved content. On restart, a pending checkpoint is accepted only after integrity, current inputs
and remote acknowledgement reconcile. Missing acknowledged data, changed input, malformed markers and contradictory
content require a verified backup or a new run; they are never silently skipped.

Only absent batch acknowledgement and typed HTTP `-1`, 408, 429 or 5xx failures permit bounded retries. Jena query failures
and update failures use different exception classes; both are classified explicitly. A 401/403, conflicting marker,
authorization failure or malformed response stops immediately. Restore least-privilege endpoint access through approved
secret references and verify ambiguous markers before resuming. Batch metrics count batch retry attempts, not individual
HTTP calls, ownership reconciliation attempts or TLS/network overhead.

Source mutations have no automatic retry. Creation intent is fsynced before submission and a restart can accept only exact
owned content. Cleanup records deletion intent first, deletes recorded edges before vertices, requires the observed ETag
and run owner, and refuses vertices with any remaining incident edge. If an uncertain deletion is still present or has
been recreated, reconcile it explicitly; never repeat the delete blindly.

The private integration harness executes real Fuseki updates and injects failures before forwarding, after a successful
backend commit, during marker reads, and during release. It also denies reconciliation after an uncertain write and verifies
one mutation attempt with no checkpoint. Its compound-update rollback test fails during execution, not just parsing.
These prove the private fixture only: deployed Fuseki transaction behavior and Cosmos ETag/cleanup race semantics remain
live-only gates. No code opens live target TDB2 files.

Run the scoped offline probes with a non-live test approval key:

```powershell
$env:GRAPH_TEST_APPROVAL_KEY='synthetic-test-only-key-not-a-live-credential'
& java\mvnw.cmd -f java\pom.xml -pl migration-engine,cosmos-gremlin-source -am '-Dtest=RecoveryTest,HttpFailureTest,PopulationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test -q
exit $LASTEXITCODE
```

Editor restart/worker lifecycle evidence belongs to M6. The load and two-hour soak profiles are distinct from these fast
recovery probes; a short semantic smoke must not be presented as either acceptance gate.
