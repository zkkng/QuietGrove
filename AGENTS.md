# SoloTrainer playtest rule

## Branch and release workflow

The owner authorizes commits and pushes for the Quiet Grove fork. Use a separate worktree on your own `dev/<team>` branch. Merge usable milestones into `test` through a pull request and supply a short owner test checklist with exact paths/commands/map IDs. `main` is reserved for the exact tested release after explicit owner production approval. Preserve upstream SoloMapling history and license. See `docs/branch-and-release-workflow.md` and `docs/quiet-grove-additions.md`.

The existing shared `codex/quest-repairs` checkout is a temporary migration exception for the active statistics task. Preserve its current work; never switch or reset another worker's checkout. Generated binaries, local credentials, private receipts, database data and telemetry are not source commits.

The owner wants a direct, working trainer for this private SoloMapling server. Preserve the flow: open the EXE, click **INJECT HAX**, and use the powers. Do not introduce PINs, pairing codes, map allowlists, mandatory setup prompts, or other unrequested gates that stop a playtest. Do not call a control working until its real game effect has been verified. Keep the trainer connected across ordinary map transitions. Explain plainly which effects are server-backed and which require client code. If a technical or deployment requirement truly cannot be avoided, prepare and test it before putting it in the player's path.
