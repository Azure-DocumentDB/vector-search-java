# Java vector search on Azure DocumentDB

Run **IVF, HNSW and DiskANN with cosine similarity** using a precomputed query vector. No Azure OpenAI deployment, model credential or billable inference is required. Java 21, Maven 3.9+, and Python 3 (fixture/security helpers) are prerequisites.

This standalone downstream sample retains the [Microsoft MIT license](LICENSE.md) and [pinned upstream provenance](UPSTREAM.md). See [TESTED.md](TESTED.md) for exact evidence and untested boundaries. **Managed Azure validation of this repository revision is pending**; local mock tests are not evidence of service compatibility.

## Safety and cost boundary

This is a cloud-writing sample, **not a production or read-only application**. Use a separately approved **disposable database** and a collection prefix exclusively owned by this run. You must explicitly set the actual target database, prefix, auth method and `ALLOW_DATABASE_WRITES=true`. There are no default cloud targets, localhost bypasses, arbitrary URI inputs, reset modes, delete operations, collection drops or index drops.

- Each algorithm owns a separate collection: `<prefix>_ivf_cos`, `<prefix>_hnsw_cos`, `<prefix>_diskann_cos`.
- Seed IDs are deterministic (`hotel:<HotelId>`). Only missing IDs are inserted. Identical existing fixture records are reused; any differing fixture record, including user edits or extra fields, causes a **transparent conflict failure before writes for that algorithm**, never an overwrite. Unrelated IDs are retained.
- The **whole index catalog is read before writes**, independent of document count. A missing vector index is created even on a populated collection. The expected name, key and algorithm options must match. Conflicting vector indexes, partial/sparse/hidden/TTL indexes, and non-`_id` unique indexes are refused, even if unrelated to the vector field. Only namespace-not-found is treated as an empty catalog; permission/network errors propagate.
- The run is not a transaction or concurrent-writer coordinator. A batch/network failure can leave some insertions or indexes; rerun with the same fixture and target to reconcile insert-only. Do not share these collections with concurrent writers. Errors never trigger cleanup that could destroy evidence.
- Data/indexes remain after success **and failure**. The resource owner must inspect and separately remove disposable resources when finished. Provisioning and deletion are deliberately not automated here.
- Azure compute/storage accrue charges while resources exist, including failed runs and idle time. Three indexes may add storage/cost. Check current region/tier support for DiskANN (use an approved supporting tier, for example M30 where available); pricing and availability vary. Local OSS does not prove Azure DiskANN behavior.

## 1. Build and test without Azure

From this repository root:

```sh
mvn -B -ntp verify
python3 scripts/test_tools.py
mvn -B -ntp dependency:tree -DoutputType=json -DoutputFile=target/dependencies.json
python3 scripts/audit_dependencies.py target/dependencies.json
```

Default tests use JUnit/Mockito, an HTTP WireMock contract, synthetic fixture data and bounded loopback failure subprocesses. **No default test connects to Azure.** The resolved dependency audit queries public Maven coordinates against OSV, including test dependencies; findings fail exit 1, service/malformed/empty-tree errors fail exit 2. It does not scan shaded vendored contents, Maven plugins or all possible future advisories. CI has read-only permissions, SHA-pinned actions, no cloud secrets and no deployment/live gate.

## 2. Retrieve the pinned fixture and extract a query

Follow [data/README.md](data/README.md) for the deliberate, no-overwrite download. It is **50 hotels × 1536 dimensions**, SHA-256:

```text
60c08c2cdd439444ba8601d94a1f3487f4a9ee56cd48ba201d983c96722de6e4
```

Then:

```sh
python3 scripts/create_query_vector.py data/Hotels_Vector.json data/query-vector.json
```

The extractor verifies the exact hash, record count and every vector, then exclusively creates a new file. Existing output is never replaced. The query is the first hotel's stored vector; this is a **self-match/correctness smoke test, not natural-language semantic quality or a benchmark**. For a custom query, supply a finite, nonzero JSON numeric array matching the document embedding model/dimensions. Disable `VERIFY_FIXTURE_RANKING` for arbitrary real-world retrieval; runtime still validates nonempty, unique, finite-score results and exact TOP_K.

## 3. Explicitly configure an approved disposable target

`.env.example` is a placeholder guide only. **Java does not autoload `.env`**, and old upstream `MONGO_CLUSTER_NAME`/OpenAI variables are no longer read. Use process environment variables or a trusted loader; never commit secrets or paste them into logs. Example for POSIX shells (replace placeholders):

```sh
export DOCUMENTDB_HOST='<approved-cluster>.global.mongocluster.cosmos.azure.com'
export DOCUMENTDB_DATABASE='vectorsearch_disposable_example'
export DOCUMENTDB_COLLECTION_PREFIX='myrun_native'
export ALLOW_DATABASE_WRITES='true'
export DOCUMENTDB_AUTH='native'
export DOCUMENTDB_USERNAME='<native-user>'
# Set DOCUMENTDB_PASSWORD securely in this process, not in shell history.
export DATA_FILE_WITH_VECTORS='data/Hotels_Vector.json'
export QUERY_VECTOR_FILE='data/query-vector.json'
export VERIFY_FIXTURE_RANKING='true'
```

Authentication:

- **Native:** explicit SCRAM-SHA-256 against `admin`; password is passed as a credential, not interpolated into a URI (special characters remain unchanged).
- **Entra OIDC:** set `DOCUMENTDB_AUTH=oidc`, **unset both native username/password variables**, authenticate the intended identity (e.g. `az login`), and register/authorize that identity independently on the actual Azure database resource. Sign-in is not authorization. The current Azure Identity `DefaultAzureCredential` chain can also use an approved workload/managed identity. Use a distinct prefix such as `myrun_oidc` for separate evidence.

The exact lower-case single-cluster Azure hostname is validated **before credential discovery/client construction**. Supported public-cloud suffixes are `mongocluster.cosmos.azure.com`, `global.mongocluster.cosmos.azure.com`, `mongocluster.azure.com` and `global.mongocluster.azure.com`. Schemes, ports, credentials, arbitrary hosts and sovereign-cloud endpoints are rejected. TLS and hostname verification are mandatory with no insecure option. OIDC requests use the fixed scope `https://ossrdbms-aad.database.windows.net/.default`, not a server-provided audience/URL, and require positive token lifetime after a 30-second safety margin. Zero lifetime is rejected, not treated as nonexpiring. Proactive driver refresh is not claimed; expiry remains server-authoritative.

Optional validated settings:

| Setting | Default | Accepted |
| --- | --- | --- |
| `EMBEDDED_FIELD` | `DescriptionVector` | simple field name, max 64 characters |
| `EMBEDDING_DIMENSIONS` | `1536` | 2–2000 |
| `TOP_K` | `5` | 1–100 and no more than fixture records |
| `OPERATION_TIMEOUT_SECONDS` | `20` | 1–60, each DB operation/token call |
| `SEARCH_ATTEMPTS` | `4` | 1–6 |
| `SEARCH_RETRY_DELAY_MS` | `1000` | 0–5000 |
| `VERIFY_FIXTURE_RANKING` | `false` | exactly `true` or `false` |

Database/prefix names must begin with an ASCII letter and contain only letters, digits and underscores (max 48); `admin`, `config`, `local` are prohibited databases. There are bounded operations/search retries, not an unlimited index-readiness loop. Search uses a monotonic deadline of `attempts × operation timeout + (attempts − 1) × retry delay` (83 seconds by default); each database operation is independently driver-time-limited. The full run has multiple preparation operations per case, not a single 20-second wall-clock limit. Shutdown explicitly closes MongoDB, HTTP connections and owned credential/HTTP executors; shutdown failure is a nonzero failure. Native retries are disabled. Error output is sanitized and SDK logging suppressed rather than printing credentials, raw exception messages or stack traces.

## 4. Run the CLI

```sh
# Default: attempt all three, even if the first or middle case fails.
mvn -B -ntp exec:java
# Or a single algorithm:
mvn -B -ntp exec:java -Dexec.args=ivf
mvn -B -ntp exec:java -Dexec.args=hnsw
mvn -B -ntp exec:java -Dexec.args=diskann
```

Original entrypoint names remain compatible:

```sh
mvn -B -ntp exec:java -Dexec.mainClass=com.azure.documentdb.samples.IVF
mvn -B -ntp exec:java -Dexec.mainClass=com.azure.documentdb.samples.HNSW
mvn -B -ntp exec:java -Dexec.mainClass=com.azure.documentdb.samples.DiskAnn
```

Each row contains algorithm, COS metric, exact collection, fixture/total/inserted counts, index reuse, status and projected hits (`_id`, `HotelId`, `HotelName`, score). The summary gives attempted/succeeded/failed. A valid zero score is not confused with a missing score. Direct Java exits **0 only for complete success**, **2 for preconnection config/fixture validation**, **1 for DB/search/conflict/shutdown failures**; Maven can normalize failures to its own nonzero status. No entrypoint catches errors and reports success.

For direct process exit testing and machine-readable output:

```sh
mvn -B -ntp dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
# POSIX classpath separator ':'; on Windows use ';'.
java -cp "target/classes:$(cat target/classpath.txt)" com.azure.documentdb.samples.Main all > target/results.jsonl
python3 scripts/verify_results.py data/Hotels_Vector.json data/query-vector.json target/results.jsonl all
```

Check the Java exit code **before** trusting a log. The independent helper requires the pinned fixture/extracted self-query, exactly 50 records per collection, all requested cases, known unique fixture IDs, correct cosine top-K ranking (ties tolerated), scores and first self-match. `VERIFY_FIXTURE_RANKING=true` additionally performs an in-process rank/score gate. Approximate indexes are not guaranteed exact ranking at arbitrary scale; this stringent small-fixture check must not be advertised as general ANN recall.

## 5. Optional paid Azure integration gate

Only after the resource owner approves the target and cost:

```sh
export RUN_AZURE_INTEGRATION='true'
export VERIFY_FIXTURE_RANKING='true'
mvn -B -ntp verify -Pintegration
```

Selecting `-Pintegration` without consent, required environment or the extracted query **fails, never skips**. It requires three successes, ranks/scores, then an insert-only rerun with zero insertions and reused indexes. It leaves resources intact. Run native and OIDC separately with different owned prefixes. The default CI never selects this profile. Record Azure region/tier, exact source SHA, authentication modes, command outcomes and cleanup evidence before making a live-validated release claim.

Shared provisioning remains [upstream](https://github.com/Azure-Samples/documentdb-samples/tree/f5a2675bbf349737234d0391e3ebee24b260e06f/infra); review firewall/admin defaults before use. This repository neither deploys infrastructure nor implements chat, embedding generation or LLM integration. The old OpenAI code/dependencies were removed cleanly; no optional unvalidated model path remains.
