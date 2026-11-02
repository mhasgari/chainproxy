---
description: Review, correct, finalize everything, re-index with codebase-memory-mcp, then commit and push
---
Finish the current work in one pass. Answer in Persian; code, comments, UI strings and logs stay English.

1. **Review**: `git status --short` and `git diff --stat`; read the changed files. Look for leftovers (debug code, noisy
   logging, unused strings), a missing unit test for new logic, and rule violations from CLAUDE.md (no personal name or
   repository text in shipped software, ASCII+CRLF scripts, DEFAULTS + docs/DEVELOPER.md for new desktop keys).
2. **Consistency**: the version in `chain.py` / `android/app/build.gradle.kts` matches the docs, `docs/` (Persian) describe the
   new behaviour and the true test status, `CLAUDE.md` "Verified state" is current, `android/PROGRESS.md` has a dated line.
   Say honestly what was and was not tested (emulator vs phone vs VM).
3. **Tests** (run what the change needs): `make test` always; `make check` if chain.py/tests/dev tools changed;
   `make android-test` (+ `make android-emu-test` with the emulator running, then remove the lab config:
   `run-as app.chainproxy rm -f files/chain-config.json`) if `android/` changed; `make vm-*` stages for Windows-only behaviour.
   Stop the emulator afterwards. Fix failures before going on.
4. **ADR and memory**: update the ADR with `manage_adr mode=set_sections` (section names WITHOUT `#`, whole ADR under ~8000
   chars: compress older items), update the memory files in `~/.claude/projects/.../memory/` and the MEMORY.md index.
5. **Index**: `index_repository(repo_path="/home/hadi/projects/chainproxy-win", mode="full")`, then check
   `index_status` says indexed (a changed file list or `detect_changes` must show the new code).
6. **Commit and push** (only now, the user asked for this command): `git add -A`, review `git status` (never `subs.txt`,
   keystores, `dist/`), commit with a short message ending with the Co-Authored-By line from the session reminder,
   `git push origin master`.
7. **Release** only if the user also asked for it: follow `.claude/commands/release.md` (Android tag `android-vX.Y.Z`, never
   Latest; desktop `vX.Y.Z` last, `--latest`).
8. Finish with a short Persian summary: what changed, what was tested where, what is NOT verified, the commit hash.
