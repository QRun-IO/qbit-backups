# Upgrading

## Unreleased

- No schema change and no new include line. Hosts get the help text and the Setup card automatically.
- Plans with `use_read_replica` true are now refused on the next save (the setting was never used). Turn it off.
- Hosts that set a plan `verifyCronExpression` and a scheduler now get working scheduled verification: the `backupVerifyPlan` process exists. It verifies the plan's latest successful run through the plan's `verifyLevel` (CONTENT when unset). Expect verification rows and, on failure, alerts.
