# QBit Backups: design

The design for this qbit is tracked in [QRun-IO/qqq#1091](https://github.com/QRun-IO/qqq/pull/1091). This repository implements it; the implementation is in progress.


## Job progress implementation

Backup, resume, scheduled backup, restore, and verify execution use always-durable
jobs with `NONE` idempotency against the QQQ job-progress API targeted for RC.8.
Backup phases are `prepare`, `tables`, `media`, and `upload`, weighted 2, 75, 18,
and 5. Verification runs separately with a single `verify` phase.

Progress reports table/file counts, stored bytes, and safe artifact names without
row data, source keys, credentials, or endpoints. Cancellation is cooperative at
page and media-copy boundaries. Media references spool locally between phases.
Object writes and catalog updates are not atomic, so worker interruptions require
review before retry. Explicit backup retries reuse the run and resume completed
parts; snapshot runs that cannot resume require a new backup.
