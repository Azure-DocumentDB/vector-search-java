# Validation record — 2026-10-12

## Managed Azure result

**6/6 algorithm/authentication cases passed** on a disposable managed Azure DocumentDB M30 in West US2, one shard, MongoDB8.0 compatibility,128GiB storage and high availability disabled. The matrix is IVF/HNSW/DiskANN × COS, repeated under native SCRAM-SHA-256 and Microsoft Entra OIDC with verified TLS and a client-IP firewall.

Tested implementation: `385f024e24bf647b19c8589ed2f11e84263c48ca`. The later commit containing this record changes only documentation.

The fixture contains50pinned public hotel records with1536-dimensional precomputed vectors; the query is the first stored vector. Actual index definitions were checked. Every case returned five results checked against a numeric reference appropriate to its metric; raw scores from different metrics are not comparable. This is not a semantic-quality, latency/throughput, production-size ANN or service-tier support benchmark.

Actual command-line execution and repeat runs were checked, not merely client construction. Repeated seeding inserted zero duplicates. An unrelated sentinel survived in every exercised collection. Existing user edits were preserved by insert-only seeding or safely refused as a fixture conflict; no test path dropped/reset/replaced existing data or indexes.

Each CLI also rejected disabled write consent, a system database target, a foreign host and an incorrect native password with a nonzero exit and without printing credentials. No model calls or Azure OpenAI account were required.

## Conflict and process-completion proof

Standalone native and OIDC processes completed naturally and their top-five IDs/scores matched an independent cosine reference. A deliberate edit to the first fixture record was preserved: the affected case failed at preparation, the remaining two algorithms still passed, all three were attempted and the process exited1. This is explicit conflict refusal, not an automatic edit merge.

## Local checks

Temurin21.0.12.1+1 and Maven3.9.9.33 JUnit tests and7helper tests pass, no failures/skips. OSV audit41resolved Maven coordinates reports zero findings; per-result service errors and malformed/incomplete responses fail closed. Direct entrypoints reject unsafe config and terminate.

Full repository history and tracked publication files were scanned for secrets with no matches. MIT licensing and pinned upstream provenance remain. Scans are point-in-time findings, not a guarantee of absolute security. CI uses pinned GitHub-owned actions, read-only permissions and no cloud credentials or deployment steps.

## Scope and cleanup

This repository supports precomputed database/vector workflows only; live embedding/model generation was removed rather than falsely advertised as tested. The available subscription's model-service eligibility is not needed by the quickstart. No model-backed end-to-end, high availability, backup recovery, production capacity or broader cloud support is claimed.

Testing used isolated disposable namespaces, not existing customer data. Environment owners are responsible for deliberately removing their test resources; the sample never automatically deletes databases/collections/indexes. The release operator verifies deletion of the shared test resource group and hosted CI before publication. No credentials, subscription identifiers or private infrastructure details are included here.
