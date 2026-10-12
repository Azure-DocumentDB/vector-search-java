# Upstream provenance

- Source repository: https://github.com/Azure-Samples/documentdb-samples
- Exact source revision: [f5a2675bbf349737234d0391e3ebee24b260e06f](https://github.com/Azure-Samples/documentdb-samples/commit/f5a2675bbf349737234d0391e3ebee24b260e06f)
- Source directory: [ai/vector-search-java](https://github.com/Azure-Samples/documentdb-samples/tree/f5a2675bbf349737234d0391e3ebee24b260e06f/ai/vector-search-java)
- Extraction date: 2026-09-30
- License: original Microsoft MIT notice preserved verbatim in [LICENSE.md](LICENSE.md)
- History model: independently maintained source snapshot, not a GitHub fork or ownership transfer

## Standalone adaptations

Source is at repository root. The README is rewritten for that layout, and placeholder-only configuration/data setup replaces reliance on the parent checkout.
- Adapted source file: `README.md`

Hotel fixtures are not redistributed: [data/README.md](data/README.md) pins the original input locations and checksums. Saved output, Learn-only quickstart pages, and devcontainer automation are omitted. Shared infrastructure and root monorepo CI are not copied.

### 2026-10-12 safe Java vector-search adaptation

- Replaced the three destructive hardcoded collection-reset programs with a shared insert-only COS runner, retaining compatible `IVF`, `HNSW`, and `DiskAnn` main entrypoints and adding `Main all`.
- Ported the safe connection, fixture and comparison foundation from the independently maintained [select-algorithm-java](https://github.com/Azure-DocumentDB/select-algorithm-java) downstream snapshot `d6962f52b5ce72c2453afca06e679322d4c6978e`, preserving its Microsoft MIT provenance. This is an explicit local adaptation, not an upstream merge claim.
- Removed OpenAI/LLM calls and dependencies entirely; precomputed query-file extraction is checksum-pinned and never overwrites an existing file.
- Updated MongoDB driver/Azure Identity/HTTP transport and pinned Maven/test tooling. Added native SCRAM and Entra OIDC, strict destination/TLS validation, actual positive token lifetime, bounded calls/retries, and owned resource shutdown.
- Require actual target database/collection prefix and explicit write consent. Seed deterministic IDs insert-only; refuse modified fixture conflicts. Validate the full catalog before writes; never drop collections/indexes or replace documents. Create missing vector indexes independently of document count.
- Retain IVF/HNSW/DiskANN with COS; use small-fixture settings IVF `numLists=1`, HNSW `m=16, efConstruction=64`, DiskANN `maxDegree=32, lBuild=50` (intentional change from original vector sample). Unneeded scalar indexes are no longer created.
- Added truthful nonzero failure/all-case aggregation, ranking/self-match verification, permanent JUnit/mock/WireMock/process tests, fail-closed live opt-in, and read-only SHA-pinned CI plus resolved-dependency OSV audit. No infrastructure or live Azure success is implied.

## Maintenance

There is no automatic synchronization. Review upstream changes against this pinned baseline, apply only relevant fixes, retain notices, and record the imported revision and local adaptations here.
Issue/PR history is not transferred. The maintainers of the repository hosting this extraction own its downstream fixes; contribute reusable fixes upstream under its contribution policy.
