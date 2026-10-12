# Verification record — 2026-10-12

## Local evidence only; cloud pending

Validated on macOS 27.0.1 arm64, Eclipse Temurin **21.0.12.1+1**, Maven **3.9.9**, Python **3.14.7**. Java/Maven reused an existing task-local toolchain/cache; no global installs or runtime/config changes. This record describes the accompanying source revision, not the original upstream implementation.

- `mvn -B -ntp clean verify dependency:tree -DoutputType=json -DoutputFile=target/dependencies.json dependency:build-classpath -Dmdep.outputFile=target/classpath.txt`: **PASS**, **33 JUnit tests**, zero failures/errors/skips.
- `python3 scripts/test_tools.py`: **PASS**, **3 offline helper tests** covering hash/dimension/overwrite checks, malformed/incomplete/empty OSV responses and advisory failures, independent result-gate failure cases.
- `python3 scripts/audit_dependencies.py target/dependencies.json`: **PASS**, **41 resolved Maven coordinates**, **0 OSV findings** at check time. Includes test dependencies. Does not certify shaded contents or Maven plugins.
- Explicit `mvn -B -ntp verify -Pintegration` **without** `RUN_AZURE_INTEGRATION=true`: expected **FAIL, exit 1**, at consent assertion before any cloud connection. Missing consent is not a skipped success. Repeating with consent/rank verification enabled but no target environment also fails exit 1 before connection.
- Real pinned 50×1536 fixture extractor: **PASS**, output is the first hotel's stored vector. Reusing the output path: expected **FAIL, exit 1**, original output preserved.
- Direct Java `Main`, `IVF`, `HNSW`, `DiskAnn` invalid-host tests: **exit 2** each, no credential/invalid endpoint leaked and no database connection attempted.
- Gitleaks working-directory scan: **0 leaks**. `git diff --check`: **PASS**. SHA-pinned GitHub build/test/audit workflow has read-only permissions and no live-resource step; hosted CI has not yet run for this revision.

## Permanent test coverage

- Native credential special characters, `admin` SCRAM-SHA-256 source, strict TLS/hostname and Azure host allowlist; consent/reserved database/unsafe name/bounds/auth validation before client construction.
- OIDC fixed scope, ignored server audience, positive lifetime with safety margin, expired/absent/timed-out token refusal. WireMock exercises HTTP token-response/expiry/redirect-refusal through a test credential adapter; **this is not live Azure Identity authentication**.
- Full catalog conflicts, including unrelated partial/sparse/unique/TTL/hidden indexes; conflicting expected name/key/options and duplicate definitions. Authorization error before insert/index writes; namespace-not-found is the only tolerated catalog error.
- Insert-only reruns preserve unrelated counts, refuse changed fixture content before writes, create missing indexes on populated collections, and surface partial insertion/count failures. No drops/deletes are called.
- All-mode first and middle failure continue through three cases then fail aggregate; single-algorithm selection, bounded retries/monotonic deadline, empty/duplicate/missing/nonfinite score refusal, known cosine rankings/scores/self-match.
- Real Mongo driver bounded loopback failure subprocess terminates without `System.exit` masking resource leaks; explicit Mongo/HTTP/credential executor close, failure-path cleanup and interrupt-preserving shutdown tests.

## Not validated / release boundary

**No Azure provisioning, DB access, native/OIDC live run, DiskANN service run, cloud cleanup, repository push, PR, merge or visibility change was performed for this revision in this local task.** Those are owner-controlled follow-up gates. Run the exact committed CLI and integration profile with native and OIDC on distinct approved prefixes, then run `scripts/verify_results.py`; record resource tier/region, exact SHA, exit codes, ranking and insert-only rerun/conflict preservation evidence before claiming live readiness.

No LLM/OpenAI path remains. No natural-language quality, broad ANN recall/latency/scale, concurrent-writer safety, sovereign-cloud or server-driven proactive OIDC refresh claim is made. Resources are deliberately retained after all outcomes; cleanup is a separate owner action.
