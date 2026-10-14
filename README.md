# QBit Backups

[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/java-21+-blue.svg)](https://adoptium.net/)

> QQQ QBit: logical backup, restore and verification of QQQ data to S3-compatible storage.

**Status:** design: [QRun-IO/qqq#1091](https://github.com/QRun-IO/qqq/pull/1091); implementation in progress. Nothing is published yet.

## Requirements

- Java 21.
- QQQ 4.1 (targets `4.1.0-RC.6`, not yet released).

## Install

Not yet published. Once released, add `com.kingsrook.qbits:qbit-backups` and produce the qbit into your `QInstance`:

```java
new BackupsQBitProducer()
   .withQBitConfig(new BackupsQBitConfig().withBackendName("rdbms"))
   .produce(qInstance);
```

## Naming

Tables are `backups_<name>` (lowercase snake_case, backend name equal to the table name). Permissions are `backups.<action>`.

## Adopting in another app

Follow the QQQ [adoption checklist](https://github.com/QRun-IO/qqq/blob/develop/docs/migration/adoption-checklist.adoc) once the first release is published.

## Build

```bash
./mvnw verify
```

Health checks require the unreleased core health SPI, installed locally as `4.1.0-SNAPSHOT`:

```bash
./mvnw -o verify -Dqqq.snapshot.version=4.1.0-SNAPSHOT -Denforcer.skipRules=requireReleaseDeps -Dtest='!ObjectStorageBackupStorageMinioTest'
```

This skips only the release-only dependency rule for the required local SNAPSHOT
and excludes the Docker/MinIO tests to keep verification offline. The POM stays on RC.6. See [the RC.6 migration notes](docs/RC6-MIGRATION.md) for
fingerprint compatibility, paging guarantees and restore permissions.

Always use the Maven wrapper (Maven 3.9.11); Maven 3.10 cannot read the parent POM.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

### Alerts integration

Backups binds directly to core QAlerts in QQQ 4.1.0-RC.5 and declares its nine
alert types on each instance. Hosts install an `AlertSinkInterface` through
`AlertsInstanceMetaData.withSink(QCodeReference)`. Without a configured sink,
backup alert calls remain no-ops (they do not use core's logging fallback).
Custom `BackupAlertSink` bindings are preserved. Core handles dispatch failures;
adapter failures are caught and logged once, so alerting cannot fail a backup.
Raise and resolve follow core's transaction-aware dispatch; heartbeats run immediately.
The backup SLA checker still owns expected intervals and grace periods.

RC.5 requires hyphenated alert names: `backups.run.missing-in-storage` and
`backups.plan.owner-invalid` replace the former underscore spellings, including
their deduplication keys. Update host routing rules that used those spellings.

## Health checks

The producer registers these central checks under component `backups`, category `STORAGE`:

| Name | Kind | Interval | Timeout |
| --- | --- | --- | --- |
| `backups.sla` | DEPENDENCY | 900s (15 minutes) | 5000ms |
| `backups.destination.<planId>` | DEPENDENCY | 86400s (24 hours) | 30000ms |

The SLA check reads persisted `slaStatus`: critical active plans that are LATE,
FAILING or MISSING_IN_STORAGE yield DOWN; non-critical failures yield DEGRADED.
Only UNVERIFIED/UNKNOWN plans yield UNKNOWN; no active plans yield UP. Details
contain counts per SLA status. The scheduled `backupCheckSla` still computes SLA state.

Destination checks are supplied for active plans and reuse `DestinationProbe` in
read-only list/head mode, including on unlocked buckets. They never write, delete,
or resolve prune credentials. The Test destination action retains its write tests.
Storage calls are bounded by the central runner, with deadline checks between calls.
Override intervals in `health_check` and disable checks through `health_check.enabled`.
