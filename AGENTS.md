# AGENTS.md

This file provides guidance to Codex when working with code in this repository.

## Project Overview

**QBit Backups**: QQQ QBit: logical backup, restore and verification of QQQ data to S3-compatible storage. Design: [QRun-IO/qqq#1091](https://github.com/QRun-IO/qqq/pull/1091). Implementation in progress.

## Build Commands

```bash
./mvnw compile          # Compile (always use the wrapper: Maven 3.10 cannot read the parent POM)
./mvnw test             # Unit tests
./mvnw verify           # Unit tests + JaCoCo gate (70% instructions, 90% classes)
```

## Architecture

**Entry point:** `BackupsQBitProducer implements QBitMetaDataProducer<BackupsQBitConfig>` in `com.kingsrook.qbits.backups`. Component producers in this package (and below) are found by package scanning.

## Naming (owner rule)

- Tables are lowercase snake_case prefixed with the full qbit name: `backups_<name>`. The QQQ table name and the backend (SQL) name are identical; possible-value sources take the table name.
- Permissions use the full qbit name: `backups.<action>` (for example `backups.view`, `backups.admin`).
- Qbit data lives in standard, visible QQQ tables, secured by permissions and record security locks, never hidden.
- Processes run as table or record actions, never in the nav.

## Code Style

- 3-space indentation, opening braces on the next line, `return (x);`.
- Javadoc flower-box comments (`/*****` with `**` lines) on every class and method.
- Fluent setters (`withX()`).
- Wrapper types (`Integer`, `Boolean`) in config and entities.

## Dependencies

- Java 21.
- Parent `com.kingsrook:qbit-build-parent:2.0.0` (qqq 4.0.0). The default-active `qqq-snapshot` profile imports `qqq-bom-pom:4.1.0-RC.4` ahead of it, as in qbit-quick-search (ADR-0007). Remove that activation when the parent moves to 4.1.
- Tests: JUnit 5, AssertJ.

## Release

- CircleCI qqq-orb 0.6.8.
- `develop` publishes SNAPSHOTs.
- A `release/*` branch publishes RCs (the orb bumps the version).
- A `v*` tag publishes the release.
