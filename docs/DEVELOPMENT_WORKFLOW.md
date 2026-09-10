# Development Workflow

This project follows a **trunk-based development** workflow. This document outlines the branch strategy, development practices, and CI/CD processes.

## Branch Strategy

### Trunk Branch: `master`

- **Always releasable** - The `master` branch should always be in a deployable state
- **Protected** - Direct pushes should be avoided; use pull requests
- **Main integration point** - All changes merge into `master`
- **Production-ready** - Code on `master` can be released at any time

### Short-Lived Branches

All feature work happens in short-lived branches that are merged back to `master` quickly (typically within days, not weeks).

#### Branch Types

1. **`feature/<slug>`** - New features and enhancements
   - Examples: `feature/new-player-ui`, `feature/playlist-import`
   - Use for: Adding new functionality, UI improvements, major enhancements

2. **`fix/<slug>`** - Bug fixes
   - Examples: `fix/youtube-oauth`, `fix/memory-leak`
   - Use for: Fixing bugs, resolving issues, patching vulnerabilities

3. **`chore/<slug>`** - Maintenance tasks
   - Examples: `chore/update-dependencies`, `chore/refactor-audio-handler`
   - Use for: Code cleanup, refactoring, dependency updates, documentation

4. **`deps/<slug>`** - Dependency experiments
   - Examples: `deps/youtube-source-pr195`, `deps/test-jda-6.4`
   - Use for: Testing dependency updates, experimenting with forks, evaluating new libraries

5. **`release/<version>`** - Release stabilization (optional)
   - Examples: `release/0.6.3`, `release/0.6.3-rc1`
   - Use for: Stabilizing a release, release candidates, hotfixes for specific versions
   - **Note**: Only create when you need to stabilize a release. Most releases can go directly from `master`

### Branch Naming Rules

- **Format**: `<type>/<descriptive-slug>`
- **Slug requirements**:
  - Lowercase letters, numbers, and hyphens only
  - Descriptive and concise (e.g., `new-player-ui`, not `ui` or `new-feature`)
  - No underscores or special characters
- **Examples**:
  - Γ£à `feature/new-player-ui`
  - Γ£à `fix/youtube-oauth-error`
  - Γ£à `chore/update-maven-plugins`
  - Γ£à `deps/test-lavaplayer-2.3`
  - Γ£à `release/0.6.3`
  - Γ¥î `feature/newFeature` (uppercase)
  - Γ¥î `fix/bug_123` (underscore)
  - Γ¥î `new-feature` (missing type prefix)
  - Γ¥î `feature/new feature` (spaces)

## Development Process

### 1. Starting Work

```bash
# Create a branch from master
git checkout master
git pull origin master
git checkout -b feature/my-new-feature

# Or for a bug fix
git checkout -b fix/bug-description
```

### 2. Making Changes

- Make small, focused commits
- Write clear commit messages
- Keep the branch up-to-date with `master`:
  ```bash
  git checkout master
  git pull origin master
  git checkout feature/my-new-feature
  git rebase master  # or git merge master
  ```

### 3. Testing Locally

- Run tests: `mvn verify`
- Test Docker build: `docker build -t jmusicbot:test .`
- Verify the bot works as expected

### 4. Creating a Pull Request

- Push your branch: `git push origin feature/my-new-feature`
- Create a PR targeting `master`
- The CI will:
  - Γ£à Validate branch naming
  - Γ£à Run tests and build
  - Γ£à Build Docker image (tagged with branch name)
- Wait for CI to pass and code review

### 5. Merging

- Once approved and CI passes, merge the PR
- **Prefer squash merge** to keep history clean
- Delete the branch after merging (GitHub can do this automatically)

## CI/CD Workflows

### Branch Validation

The `validate-branch-naming.yml` workflow automatically validates branch names on:
- Pull requests (when opened, updated, or edited)
- Direct pushes to non-master branches

**What it checks:**
- Branch name matches allowed patterns
- Slug uses only lowercase, numbers, and hyphens
- Proper type prefix is used

### Build and Test

The `build-and-test.yml` workflow is the pre-merge gate. It runs on pull requests targeting `master` only; pushes to `master` are handled by Auto Release, which runs the same build before releasing.

**What it does:**
- Compiles the project
- Runs unit and integration tests
- Generates code coverage reports
- Uploads coverage to Codecov

### Docker Build

The `docker-publish.yml` workflow builds and publishes Docker images from version tags only:
- Version tags (e.g., `v0.7.1`), pushed by hand or dispatched by `auto-release.yml`: tags as `:0.7.1`, `:latest` and `:sha-<commit>`
- Preview images for any ref can be published manually with `publish-preview-image.yml`: tags as `:preview-<ref>`

There is no separate `master` image build. Every release tag is the newest `master` commit, so `latest` always follows the latest release.

**Image tags:**
- Newest release: `ghcr.io/ragnarrok/jmusicbot:latest`
- Version tag: `ghcr.io/ragnarrok/jmusicbot:0.7.1`
- Preview: `ghcr.io/ragnarrok/jmusicbot:preview-feature-new-player-ui`

### Auto Release

The `auto-release.yml` workflow is the only workflow that runs on pushes to `master`. It runs on every push that touches code (documentation-only changes are ignored):

1. Runs the full test suite with coverage, uploads coverage to Codecov and builds the JAR (`mvn verify -Pcoverage`)
2. Picks the next version: the `pom.xml` version if no tag exists for it yet, otherwise the next free patch number
3. Stamps that version into the build (the JAR reports it) without committing anything, and tags the merged commit `vX.Y.Z`
4. Publishes a GitHub release with the JAR attached. The release notes are the description of the pull request that was merged (so write PR descriptions for users, not just reviewers), with a footer linking the README, the Docker image and the full changelog. A direct push without a PR falls back to the commit message.
5. Dispatches `docker-publish.yml` on the new tag, which passes the version into the Docker build as `APP_VERSION`

The workflow never pushes to `master`, so it is compatible with a branch ruleset that requires pull requests. `pom.xml` keeps a base version (bump it by hand for a minor or major release); the released version is recorded only in the tag. The tag is pushed with the workflow token, so it does not trigger further workflow runs.

## Best Practices

### Keep Branches Short-Lived

- **Goal**: Merge within days, not weeks
- **Why**: Reduces merge conflicts, keeps code fresh, enables faster feedback
- **If stuck**: Break work into smaller PRs

### Keep `master` Releasable

- **Never** push broken code to `master`
- **Always** ensure tests pass before merging
- **Use** feature flags if needed for incomplete features
- **Consider** draft PRs for work-in-progress

### Small, Focused PRs

- **One feature/fix per PR** when possible
- **Easier to review** and understand
- **Faster to merge** and deploy
- **Less risk** of conflicts

### Regular Integration

- **Rebase or merge** `master` into your branch regularly
- **Run tests** locally before pushing
- **Fix CI failures** promptly

### Clear Commit Messages

- **Format**: `<type>: <description>`
- **Types**: `feat`, `fix`, `chore`, `docs`, `refactor`, `test`
- **Example**: `feat: add playlist import from YouTube`
- **Why**: Makes history readable and enables automated changelogs

## Release Process

### Standard Release (from master)

Releases are automatic. Every code push to `master` that passes the tests becomes a patch release (`0.7.0` → `0.7.1` → `0.7.2` ...) with the JAR attached and a matching Docker image. See [Auto Release](#auto-release) above.

To make a **minor or major** release, bump the version in `pom.xml` in the same PR as the change:

```bash
mvn versions:set -DnewVersion=0.8.0 && mvn versions:commit
git commit -am "chore: bump version to 0.8.0"
```

When that lands on `master`, the auto release uses `0.8.0` as-is (no tag exists for it yet) and continues with `0.8.1`, `0.8.2`, … from there while `pom.xml` stays at `0.8.0`.

The manual **"Make Release"** workflow still exists for special cases, such as a pre-release from a branch or a release with a hand-written description. Publish its draft promptly: if a code commit lands on `master` while the draft is unpublished, the auto release will claim that version number first.

### Release Branch (for stabilization)

Only use if you need to stabilize a release while continuing development:

1. **Create release branch**
   ```bash
   git checkout -b release/0.6.3
   git push origin release/0.6.3
   ```

2. **Stabilize on release branch**
   - Fix critical bugs
   - Run extensive testing
   - Cherry-pick fixes from `master` if needed

3. **Tag from release branch**
   ```bash
   git tag v0.6.3
   git push origin v0.6.3
   ```

4. **Merge back to master** (if needed)
   - Merge any fixes back to `master`
   - Delete release branch after release

## FAQ

### Q: Can I push directly to master?

**A**: Not recommended. Use pull requests for all changes to ensure:
- Code review
- CI validation
- Better history tracking

### Q: How long should branches live?

**A**: Ideally less than a week. If work takes longer, consider:
- Breaking into smaller PRs
- Using feature flags
- Creating a release branch if needed

### Q: What if I need to experiment?

**A**: Use `deps/<slug>` branches for dependency experiments, or create a personal fork for major experiments.

### Q: Can I use different branch names?

**A**: The CI will reject branches that don't match the allowed patterns. This ensures consistency and makes it easier to understand what each branch is for.

### Q: What about hotfixes?

**A**: For urgent production fixes:
1. Create `fix/<description>` branch from `master`
2. Fix the issue
3. Create PR and merge quickly
4. Tag a new patch version (e.g., `v0.6.3` ΓåÆ `v0.6.4`)

## Summary

- **Trunk**: `master` is always releasable
- **Branches**: Short-lived, type-prefixed (`feature/`, `fix/`, `chore/`, `deps/`, `release/`)
- **Process**: Branch ΓåÆ Develop ΓåÆ Test ΓåÆ PR ΓåÆ Merge ΓåÆ Release
- **CI**: Automatic validation, testing, and Docker builds
- **Goal**: Fast, safe, continuous integration and deployment
