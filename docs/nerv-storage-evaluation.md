# Nerv storage evaluation

Recorded 2026-09-07 for the downstream `alfredw/floci` fork. Upstream remains
`https://github.com/floci-io/floci`. The signature change is on
`nerv/s3-header-signatures`, based on upstream main commit
`3dee17e51dd382c30e065bb755aab58e74673e56`.

## Scope

Nerv needs S3 conditional writes for DurableServer's single-owner protocol.
An acknowledged object write must survive recovery, because PostgreSQL may not
yet contain the accepted document changes. This is stronger than a convenient
development cache.

The retained harness is in [Nerv's GH-11 reference](https://github.com/alfredw/kaizen-studio/tree/ee2538d1014467fb6b2a27ec15eaf468049dd8a5/scripts/durable_server).
It pins DurableServer 0.1.5 and Req 0.7.4. Its existing fingerprint-guarded Req
retry-signing patch remains necessary. The only harness test adjustment for
Floci is replacing the fault proxy's hard-coded port 59011 with
`Harness.store().s3_endpoint`.

## Executed results

| Check | Result |
|---|---|
| Released Floci 2.0.1, persistent mode | 24 retained harness cases passed, 100% harness coverage |
| Patched Floci JVM, WAL mode, header validation enabled | 24 retained harness cases passed, 100% harness coverage |
| Java signature and related S3 regressions | 44 tests passed |
| 16 simultaneous conditional creates | One 200, fifteen 412, correct winner body |
| 16 simultaneous conditional replacements from one ETag | One 200, fifteen 412, correct winner body |
| Normal acknowledged write followed by container SIGKILL/restart | Exact bytes and ETag preserved in persistent and WAL probes |
| Invalid header signature, released image | Accepted with 200 despite presigned-validation flag |
| Invalid header signature, patched JVM with new setting | Rejected with 403 |
| Lost-response retry with released Req | Rejected; retained acceptance test failed |
| Same retry with retained Req patch | Passed |
| Persistent metadata write failure | PUT 200 and live GET 200, then GET 404 after SIGKILL/restart |
| WAL append failure | PUT 200 and live GET 200, then GET 404 after SIGKILL/restart |

The released Req retry returned `400 AuthorizationHeaderMalformed` with the
new validator. The earlier MinIO reference returned 403. Both expose the broken
retry, but this does not claim identical error classification for that case.

The 100% figure covers the four standalone Elixir harness modules, not all of
Floci. The full Java repository suite, native-image packaging, real AWS,
PostgreSQL integration, browser recovery and performance were not exercised.

## Reproduction configuration

Released image:

```text
floci/floci:2.0.1
sha256:4e451c39c7bb88e3cd4f87e8fc0c25d5b47695a51185d521e2241fa00486e8eb
```

The patched build used Java 25/Maven in Docker and the JVM package, with:

```sh
mvn -B -Dtest=S3HeaderSignatureIntegrationTest,PreSignedUrlFilterTest,PreSignedUrlIntegrationTest,S3ConditionalWriteIntegrationTest package
```

Runtime configuration used a dedicated persistent volume and localhost port:

```text
FLOCI_STORAGE_MODE=wal
FLOCI_STORAGE_PERSISTENT_PATH=/data
FLOCI_STORAGE_WAL_COMPACTION_INTERVAL_MS=3600000
FLOCI_SERVICES_S3_VALIDATE_HEADER_SIGNATURES=true
```

The one-hour compaction interval kept the WAL failure probe from being healed
by a later snapshot; it is an experimental setting, not an operational default.
Harness credentials were the documented synthetic `test` / `test` pair:

```sh
SPIKE_S3_ENDPOINT=http://127.0.0.1:59013 \
SPIKE_ACCESS_KEY=test SPIKE_SECRET_KEY=test mix test --cover
```

Run from the standalone harness's `core` directory after its dependencies and
retained Req patch are prepared. A fresh checkout needs all dependencies
compiled before the preparation script's targeted Req compilation.

## Storage failure reproductions

Use only a disposable volume. These probes deliberately prevent metadata
persistence while leaving ordinary object-body writes available.

- Persistent mode: create a directory at the temporary metadata filename
  `s3-objects.json.tmp`. PUT a new object and GET it. Kill the container without
  shutdown hooks, restart, remove the empty injected directory, and GET the
  object again. It changes from 200 to 404.
- WAL mode: preserve `s3-objects.wal`, temporarily replace its pathname with a
  symlink to `/dev/full`, then SIGKILL/restart to open the failing writer. PUT
  and GET a new object. The append reports `No space left on device`, but both
  HTTP operations return 200. Restore the original WAL, SIGKILL/restart, and
  GET the new object: 404. Restore the fixture before ordinary shutdown.

These inject real file-operation failures; they do not claim to simulate a
host power loss or to fill an actual disk. The source explains the result:
`PersistentStorage.persistToDisk` and `WalStorage.appendPut` log IO failures
without propagating them, after publishing the change in memory.

The retained MinIO reference did not run these same storage-process/disk-write
failure probes. It established owner/BEAM recovery and transport-failure behavior.
No MinIO data-loss result is asserted here, and the Floci result alone does not
establish a comparative MinIO durability guarantee.

## Remaining work

The signature implementation makes Floci useful for the required protocol and
retry tests. Storage configuration alone does not meet the durable-acceptance
contract. A separate persistence change needs to prove failed writes cannot be
acknowledged, recover object bytes and metadata consistently, and establish the
required disk-sync behavior. This fork is not a production-storage adoption.

Keep `origin` pointing to `alfredw/floci` and `upstream` pointing to
`floci-io/floci`. Fetch upstream and merge its main branch into the feature
branch when incorporating later releases; do not push Nerv-specific work to
upstream without a separate contribution decision.
