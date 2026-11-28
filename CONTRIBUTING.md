# Contributing

This project follows the unified development workflow shared across the
maintainer's repositories (modeled on aegis-app/aegis-bot).

## Development workflow

1. **Open an issue first.** Every change starts with an issue (use the
   issue templates). Describe the purpose, the planned work, and the
   definition of done.
2. **Create a branch from `main`** named `<type>/<issue-number>-<slug>`,
   e.g. `feat/12-storage-analysis`, `fix/9-search-race`, `docs/3-readme`.
3. **Implement and test.** Run the unit tests and the build locally;
   both must pass (`./tests/run-tests.sh`, `./build.sh`).
4. **Open a pull request.** Fill in the PR template and link the issue
   with `Closes #<number>`.
5. **Merge with "Rebase and merge"** to keep the history of `main`
   linear, then delete the branch.

## Commit messages

Commit messages use **Conventional Commits with English subjects**:

```
feat: add storage usage analysis to home
fix: keep paste destination fixed while an operation runs
docs: update documentation for v1.0.0
```

Types in use: `feat`, `fix`, `docs`, `test`, `chore`, `refactor`, `build`.

## Releases

Releases are cut from `main`: bump the version (versionName and
versionCode in the manifest), tag `vX.Y.Z`, and publish a GitHub Release
with notes only (no binaries are attached). Signed release builds are
produced locally by the maintainer via `./build.sh release` with the
signing material passed through environment variables only.

## Notes

- Never commit secrets. Release signing keys are passed via the
  `RELEASE_*` environment variables and are never written into the repo.
- The debug keystore at the repository root is kept intentionally so
  debug builds share one stable signature.
