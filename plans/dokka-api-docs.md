# Plan: API documentation with Dokka on GitHub Pages

Status: done. GitHub Pages is on (Settings → Pages → Source: GitHub Actions); the "github-pages"
environment still needs the `v*` tag rule from step 5 before the first release tag is pushed.

## Goal

Generate the API reference from the KDoc comments with [Dokka](https://kotlinlang.org/docs/dokka-introduction.html),
build it in GitHub Actions when a release tag (`v0.2.0`) is pushed, and publish it with GitHub
Pages at `https://lux-uno.github.io/magic-wormhole-kotlin/`. The docs then always match a version
that users can download. See "Releases" in [AGENTS.md](../AGENTS.md) for the release steps. [docs/usage.md](../docs/usage.md) stays the
task-based guide; the Dokka site is the reference for every public class and function.

## Cost

Free: the repository is public, and GitHub Pages and GitHub-hosted Linux runners cost nothing
for public repositories. The workflow runs only on release tags, so it runs rarely.

## Steps

### 1. Add the Dokka Gradle plugin (about 30 minutes)

- Add `dokka = "2.2.0"` (the latest stable; 2.3.0 is in beta) to `gradle/libs.versions.toml`,
  and `alias(libs.plugins.dokka)` to `magic-wormhole/build.gradle.kts`.
- Configure it in `magic-wormhole/build.gradle.kts`:

  ```kotlin
  dokka {
      moduleName = "magic-wormhole-kotlin"
      moduleVersion = project.version.toString()
      dokkaPublications.html {
          includes.from("Module.md")
      }
      dokkaSourceSets.configureEach {
          reportUndocumented = true
          sourceLink {
              localDirectory = rootDir
              // Source links point at the release tag, so they match the documented version.
              remoteUrl("https://github.com/lux-uno/magic-wormhole-kotlin/tree/v${project.version}")
              remoteLineSuffix = "#L"
          }
      }
  }
  ```

- Add `magic-wormhole/Module.md`: a short module description (what the library does, a link to
  `docs/usage.md`) and one line per package. Dokka needs the `# Module magic-wormhole` heading.
- Run `./gradlew :magic-wormhole:dokkaGeneratePublicationHtml` and open
  `magic-wormhole/build/dokka/html/index.html`.

Check: only public API appears (`explicitApi()` already makes everything else `internal`).

### 2. Fill KDoc gaps (about 1 hour)

`reportUndocumented` lists public declarations without KDoc. Known gaps: `SendEvent.Progress`,
`ReceiveEvent.Progress`, `WormholeConfig.rendezvousUrl` and `appId`, and properties described only
in their class comment (for example `FileOffered.name` and `OutgoingFile.open`; use `@property`).
Write short KDoc for each; link to `docs/usage.md` where an example helps.

### 3. Check the iOS source sets on Linux (about 15 minutes)

Dokka reads every source set, including `iosMain`. On a Linux runner it may warn or skip the iOS
source sets. They hold only `internal` code, so if they cause trouble, suppress them:

```kotlin
dokkaSourceSets.matching { it.name.startsWith("ios") }.configureEach { suppress = true }
```

### 4. Add the GitHub Actions workflow (about 30 minutes)

New file `.github/workflows/docs.yml`:

```yaml
# Builds the API reference with Dokka and publishes it with GitHub Pages, for each release tag.
# To publish again without a new release (for example after a failed run), start the workflow by
# hand and pick the tag under "Use workflow from".
name: API docs

on:
  push:
    tags: ['v*']
  workflow_dispatch:

# One deployment at a time; a newer tag replaces a waiting one.
concurrency:
  group: pages
  cancel-in-progress: true

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v5
      - name: Check that the tag matches the version
        run: |
          version=$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2)
          if [ "$GITHUB_REF_NAME" != "v$version" ]; then
            echo "::error::$GITHUB_REF_NAME does not match VERSION_NAME=$version in gradle.properties"
            exit 1
          fi
      - uses: actions/setup-java@v5
        with:
          distribution: zulu
          java-version: 21
      - uses: gradle/actions/setup-gradle@v5
      - name: Dokka
        run: ./gradlew :magic-wormhole:dokkaGeneratePublicationHtml
      - uses: actions/upload-pages-artifact@v4
        with:
          path: magic-wormhole/build/dokka/html

  deploy:
    needs: build
    runs-on: ubuntu-latest
    permissions:
      pages: write
      id-token: write
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - id: deployment
        uses: actions/deploy-pages@v5
```

Check the action versions when doing this step (`deploy-pages` was at v5 in September 2026).
The version check also stops a manual run from a branch: the docs only come from release tags.

### 5. Turn on GitHub Pages (5 minutes, repository owner)

1. Repository **Settings → Pages → Build and deployment → Source: GitHub Actions**. No `gh-pages`
   branch is needed.
2. **Settings → Environments → github-pages → Deployment branches and tags**: add a rule for the
   tag pattern `v*`. By default this environment only accepts the default branch, so without the
   rule every deployment from a tag is rejected.

### 6. Link the site (10 minutes)

- README: an "API reference" link next to the link to `docs/usage.md`.
- `docs/usage.md`: link class names to their Dokka pages where it helps.
- AGENTS.md: "Public API changes need KDoc; check with `./gradlew :magic-wormhole:dokkaGeneratePublicationHtml`."

## Later

- **Versioned docs:** keep the docs of older releases next to the latest one with Dokka's
  versioning plugin.
- **Fail on missing KDoc:** once the gaps are filled, run Dokka in the `CI` workflow on pull
  requests with `failOnWarning = true`, so new public API cannot land without KDoc.

## Decisions

1. Publish on release tags, not on every push to `main`, so the docs match the published
   artifact. Doc fixes between releases appear with the next release.
2. The repository is public, so Pages is free.
