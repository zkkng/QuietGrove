# Quiet Grove: development, test, production

Upstream: https://github.com/MadaraGameDev/SoloMapling. Fork: https://github.com/zkkng/QuietGrove. Preserve upstream history and AGPL-3.0 license.

| Branch | Purpose |
| --- | --- |
| `dev/gm-events`, `dev/trainer-social`, `dev/world-content`, `dev/world-statistics` | Independent team development in separate worktrees. Commit coherent changes and push frequently. |
| `test` | Merge completed milestones here, build one combined package, then let the owner test it. |
| `main` | Production-approved source. Promote the exact tested commit/package only after the owner's explicit approval. |

1. Start team work from `test` in its own worktree; never switch another active worker's shared checkout.
2. Commit each coherent change with the behavior and focused validation in the message. Open a pull request into `test` with current goal IDs and a short manual test list.
3. The milestone workflow runs the complete Java suite and packages committed runtime assets plus the built JAR. Its manifest records the Git commit and SHA256 for every runtime file.
4. Install that package on an isolated test server/database through the guarded installer. The owner receives the exact trainer path, commands, NPCs/map IDs, expected results and current package identity.
5. The owner explicitly approves that commit and package hash for production. Record the approval, test result and rollback location in a release receipt. A correction creates a new test package and requires fresh approval.
6. Merge only the approved test commit into `main`, then install the same package bytes on production with backup and rollback. Never rebuild during promotion.

GitHub checks establish build readiness. Manual game tests, real database acceptance and the owner approval remain required. Keep test and production databases/configuration separate. Git branch changes alone do not provision or deploy a server.

## Initial migration

`dev/import-2026-09-30` captures the accumulated source from the former shared workspace. This is an honest import snapshot, not reconstructed historical commits or proof that all source is deployed. Operational receipts, credentials, dumps, local binaries and raw telemetry stay local. Current deployment identities are recorded in the local weekly handoffs. The initial `main` branch is upstream plus this workflow bootstrap; it is not yet an exact source record of the old live JAR. The first owner-approved release establishes that correspondence.

The existing `codex/quest-repairs` checkout stays available for the active statistics task during migration. Future work uses the team worktrees. Changes made after the import snapshot must be committed separately. The test-server installation is a separate infrastructure step and must be verified before calling a GitHub build a running test deployment.
