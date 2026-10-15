# Changelog

All notable changes to this project are documented here. The format follows Keep a Changelog; versions follow semantic versioning.

## [0.1.0] - Unreleased

### Added
- Central `backups.sla` and per-active-plan `backups.destination.<planId>` health checks, with bounded read-only destination diagnostics and secret-free status details.
- Always-durable backup, resume, scheduled backup, restore and verify processes with conservative `NONE` idempotency and stable backup identities on explicit job retry.
- Weighted backup phases, table/file counts, stored bytes, safe per-table/artifact logs, warnings, failures and resume notices through QQQ `JobProgress`.
- Cooperative job cancellation during backup pages, media copying, restore pages and verification scans; local media-reference spooling keeps phase separation from growing heap usage across tables.
- Complete host-neutral in-app help bundle with live references and a help coverage gate. Hosts can override entries with a HOST bundle.
- Guided empty states: help bundle `help/qbit-backups.yaml` (app and all nine tables, with "How rows get created") applied by `BackupsHelp`, and a Setup card on the Backups home (plan, scope rules, storage, schedule, verification, prune bucket).
- "Create default plan" table action on Backup plans: adds "Nightly full" for a storage bucket alias; insert-only.
- `backupVerifyPlan`: the scheduled verification process. A plan verify cron created a scheduled job for a process that did not exist, so scheduled verification never ran.
- Repository scaffold: `BackupsQBitConfig` and `BackupsQBitProducer` (table prefix `backups_`, permissions `backups.*`), build, CI and repository standard files.

### Changed
- Read the QBit metadata version from the Maven artifact version so release builds report their released version.
- Target QQQ `4.1.0-RC.8` (job progress).
- Persist controlled backup failure messages instead of untrusted provider exception text.
- Pin QQQ to released 4.1.0-RC.7 and replace the temporary help loader with core HelpBundles registration.
- Saving a plan with Use read replica on is refused (it was never read); the not-yet-available settings (read replica, native dump, incremental, PARQUET, ZSTD) say so in their field help; scheduledJobId is documented as unused.
- Build against the released QQQ BOM 4.1.0-RC.5; replace reflective alerts binding with core QAlerts and real sink integration tests.
- Register backup alert types for every instance while preserving custom backup sinks and no-op behavior without a configured core sink.
- Use RC.5-valid alert names `backups.run.missing-in-storage` and `backups.plan.owner-invalid` (and matching deduplication keys). Hosts using the old underscore names must update routing rules.
- Target QQQ 4.1.0-RC.6 and replace the fingerprint, keyset pager and reflective restore bridges with typed core APIs.
- New manifests identify `qqq-table-schema-v1` and carry core schema descriptors; restore and content verification still accept pre-RC.6 manifests with the original fingerprint algorithm.
- Preserve primary-key ordering, exclusive resume cursors, bounded pages and caller-owned transactions; reject non-orderable primary keys and scope filters with skip/limit.
- Require the core `backups.restore` permission during Review when using a core restore writer, including anonymous-mode hosts; preserve supplied timestamps through `RestoreWriteFlag`.

### Removed
- The legacy `BackupsHealthIndicator` and its optional `qqq-middleware-health` dependency; central health registration replaces manual indicator configuration.
