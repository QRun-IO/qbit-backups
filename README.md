# QBit Backups

[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/java-21+-blue.svg)](https://adoptium.net/)

> QQQ QBit: logical backup, restore and verification of QQQ data to S3-compatible storage.

**Status:** design: [QRun-IO/qqq#1091](https://github.com/QRun-IO/qqq/pull/1091); implementation in progress. Nothing is published yet.

## Requirements

- Java 21.
- QQQ 4.1 (built against `4.1.0-RC.5`).

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
