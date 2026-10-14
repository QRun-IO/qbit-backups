# Changelog

All notable changes to this project are documented here. The format follows Keep a Changelog; versions follow semantic versioning.

## [0.1.0] - Unreleased

### Added
- Repository scaffold: `BackupsQBitConfig` and `BackupsQBitProducer` (table prefix `backups_`, permissions `backups.*`), build, CI and repository standard files.

### Changed
- Build against the released QQQ BOM 4.1.0-RC.5; replace reflective alerts binding with core QAlerts and real sink integration tests.
- Register backup alert types for every instance while preserving custom backup sinks and no-op behavior without a configured core sink.
- Use RC.5-valid alert names `backups.run.missing-in-storage` and `backups.plan.owner-invalid` (and matching deduplication keys). Hosts using the old underscore names must update routing rules.
- Target QQQ 4.1.0-RC.6 and replace the fingerprint, keyset pager and reflective restore bridges with typed core APIs.
- New manifests identify `qqq-table-schema-v1` and carry core schema descriptors; restore and content verification still accept pre-RC.6 manifests with the original fingerprint algorithm.
- Preserve primary-key ordering, exclusive resume cursors, bounded pages and caller-owned transactions; reject non-orderable primary keys and scope filters with skip/limit.
- Require the core `backups.restore` permission during Review when using a core restore writer, including anonymous-mode hosts; preserve supplied timestamps through `RestoreWriteFlag`.
