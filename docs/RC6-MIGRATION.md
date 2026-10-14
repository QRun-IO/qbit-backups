# RC.6 core migration (issue #19, F3)

The POM targets `4.1.0-RC.6`. Until that release is available, local verification
uses `./mvnw -B verify -Dqqq.snapshot.version=4.1.0-ARCHRC6-SNAPSHOT` against the
installed build from `qqq-wt-archrc6`. No core source changes are needed.

## Fingerprint compatibility

The algorithms are **not byte-compatible**, even for identical table metadata:

| Descriptor property | Pre-RC.6 backup algorithm | Core algorithm |
| --- | --- | --- |
| Table and key | `name`, `primaryKey` | `table`, `primaryKeyField` |
| Fields | Map keyed by name | Sorted list with explicit `name` |
| Field flags | `required`, `pvs`, `heavy`, `sensitive` | `isRequired`, `possibleValueSource`, `isHeavy`, `isSensitive` |
| Unique keys | Sorted `+`-joined strings | Sorted lists of field names |
| Associations | Sorted `name->table` strings | Objects with name, associatedTable and joinName |
| Null backend type | JSON null | Empty string |
| Escaping | Historical hand-written escaping | Jackson JSON escaping |

New table entries contain `fingerprintAlgorithm: "qqq-table-schema-v1"`, the core
`schema` descriptor, and `fingerprint: "sha256:" + core.getHash()`. The outer
manifest remains version 1; row parts, checksums, compression and storage paths
are unchanged. The instance/catalog hash still combines the ordered table
fingerprint strings, so its value changes too. New manifests require this RC.6
reader; older readers do not understand the new descriptor shape.

An absent algorithm identifies the old `qbit-backups-table-v1` algorithm. The
reader also accepts that explicit identifier. `LegacyTableFingerprint` freezes
the original descriptor and hash implementation solely for comparison against
existing backups; no new backup uses it. Restore and content verification select
the declared algorithm. Unknown identifiers never match a known fingerprint.
Restore normalizes either field shape for its existing compatibility analysis
when a table has changed. Fingerprint equality is not required for a compatible
schema migration, as before.

`src/test/resources/manifests/legacy-schema.json` freezes descriptors and hashes
produced by the original stand-in for the blog fixture. Tests place these into a
real backup manifest without the algorithm field, verify its content, mutate the
live data, then restore and compare the complete content hash including dates.
They also cover optional additions and incompatible type changes in old schemas.

## Paging and consistency

`TableReader.Keyset` delegates to core `KeysetPager`. Every query uses
`(scope filter) AND pk > lastKey ORDER BY pk ASC LIMIT pageSize`, with no offset.
The resume key is exclusive. Page size is a row bound, **not a JDBC fetch-size
setting**. Core's normal bounded QueryAction path retains backend driver defaults;
this adapter does not opt into the large-result streaming query hint or change
JDBC fetch size. Heavy/hidden/password fields and untranslated stored values keep
the same query settings and existing backup permission boundary.

Every page receives the caller's transaction. The pager does not open, commit,
close, or replace that transaction. A test opens a real core memory snapshot,
changes a later page's data, and proves every page still sees the original state.
The existing BackupEngine continues to report BEST_EFFORT and reject
SNAPSHOT_REQUIRED: adopting the consistent-read opening API at engine level is
separate work. This migration does not claim snapshot guarantees for ordinary
backup runs.

Core can stream non-orderable keys without deterministic key order; backups
explicitly reject those keys instead. Core rejects scope filters carrying skip
or limit, and replaces user ordering with ascending primary-key ordering. Tests
cover OR filters, resume, full and partial pages, empty scans and invalid inputs.

## Restore writes

`StandardRestoreWriter` now constructs `RestoreWriteFlag` directly and calls
`QBackendModuleInterface.resyncKeySequences`, consuming typed `SequenceResyncResult`
values. Only RESYNCED results are reported as sequence changes; NOT_NEEDED and
NOT_APPLICABLE remain normal outcomes. There is no reflection or unavailable-core
fallback. The core flag preserves supplied dates and carries the requested
customizer, automation, audit and event settings.

Core always requires an actual session permission `backups.restore` for its
restore flag, including hosts with general core enforcement disabled. The qbit's
anonymous-mode convenience setting does not grant that permission. Review now
reports this requirement before executing a restore; test sessions explicitly
hold it. Custom writers that do not use the core flag keep their existing qbit
permission checks.

## HTTP operations and wire changes

No HTTP operations are added, removed or modified: method, path and operationId
are unchanged (no operation list entries for this migration). The RC.6 UI API
extension documentation was reviewed; this task introduces no Javalin route or
middleware extension. Wire changes are confined to the manifest fields and
fingerprint values described above, plus an earlier restore permission error in
Review for anonymous sessions lacking `backups.restore`.

## Local verification (2026-10-13)

- `./mvnw -B clean verify -Dqqq.snapshot.version=4.1.0-ARCHRC6-SNAPSHOT`:
  BUILD SUCCESS; 158 tests, zero failures/errors/skips; both JaCoCo gates passed.
  Existing backup, restore and MinIO integration tests ran.
- Separate Checkstyle 14.3.0 audit of all 16 changed Java files (main and test):
  zero violations, using the shared Kingsrook-derived rules with this repository's
  Apache file header. This repository has no Checkstyle execution configured in
  its POM; audit configuration and results are local under `.cache/style/`.
- `git diff --check`: passed. No commit, push or core source modification.
