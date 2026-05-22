# Releasing

This doc captures the maintainer-side setup needed for the
release pipeline (`.github/workflows/release.yml`) to publish
to both the Gradle Plugin Portal and Maven Central. The
release flow itself — tag → build → attest → publish — is
fully automated on both channels; this doc is only about the
one-time secret and GPG key setup.

## Required GitHub repository secrets

| Secret | Used by step | Where to get it |
| --- | --- | --- |
| `GRADLE_PUBLISH_KEY` | `Publish to Gradle Plugin Portal` | [plugins.gradle.org/u/vedanthvdev](https://plugins.gradle.org/u/vedanthvdev) → API Keys |
| `GRADLE_PUBLISH_SECRET` | `Publish to Gradle Plugin Portal` | Same as above |
| `MAVEN_CENTRAL_USERNAME` | `Publish to Maven Central` | [central.sonatype.com](https://central.sonatype.com) → View Account → Generate User Token (the `username` field) |
| `MAVEN_CENTRAL_PASSWORD` | `Publish to Maven Central` | Same flow as above (the `password` field) |
| `SIGNING_IN_MEMORY_KEY` | `Publish to Maven Central` | Armored GPG private key — see below |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | `Publish to Maven Central` | The passphrase for the GPG key (empty string if the key has no passphrase) |

Add each via **Settings → Secrets and variables → Actions →
New repository secret** in the GitHub UI, or via the CLI:

```bash
gh secret set MAVEN_CENTRAL_USERNAME --body "<value>"
```

## Generating the GPG signing key

Maven Central requires every artifact to be GPG-signed. The
key only needs to be generated once.

```bash
# 1. Generate a key. Pick RSA 4096, no expiry (or 2 years +
#    plan to rotate). Use a strong passphrase.
gpg --full-generate-key

# 2. Find the long key ID.
gpg --list-secret-keys --keyid-format long
#   → sec   rsa4096/ABCDEF1234567890 ...
#                    ^^^^^^^^^^^^^^^^ this is the long key ID

# 3. Export the public key and upload it to a keyserver
#    (Sonatype validates against keys.openpgp.org).
gpg --armor --export ABCDEF1234567890 > public-key.asc
gpg --keyserver keys.openpgp.org --send-keys ABCDEF1234567890

# 3a. IMPORTANT: keys.openpgp.org sends a verification email to the
#     address on the key. The key is NOT searchable (and Sonatype
#     cannot verify your signatures) until that link is clicked.
#     Confirm the key is live with:
gpg --keyserver keys.openpgp.org --recv-keys ABCDEF1234567890

# 4. Export the armored private key for the CI secret.
gpg --armor --export-secret-keys ABCDEF1234567890 > private-key.asc

# 5. Set the secret. The value MUST include the surrounding
#    `-----BEGIN PGP PRIVATE KEY BLOCK-----` and
#    `-----END PGP PRIVATE KEY BLOCK-----` lines.
gh secret set SIGNING_IN_MEMORY_KEY < private-key.asc
gh secret set SIGNING_IN_MEMORY_KEY_PASSWORD --body "<the passphrase>"
# If the key has no passphrase, set an EXPLICIT empty value — an
# unset secret is not the same as an empty one to the workflow:
#   gh secret set SIGNING_IN_MEMORY_KEY_PASSWORD --body ""

# 6. Wipe the local exports. `shred -u` is GNU coreutils; macOS
#    ships with `rm -P` for the same secure-delete behaviour.
#    The chained fallback covers both:
shred -u private-key.asc public-key.asc 2>/dev/null \
  || rm -P private-key.asc public-key.asc 2>/dev/null \
  || rm -f private-key.asc public-key.asc
```

### Verifying the secrets without merging

Once the four secrets are set, run a local signing smoke test
that mirrors the CI publish path. It produces detached `.asc`
signatures under `affected-tests-gradle/build/libs/` without
uploading anything:

```bash
KEY_ID=ABCDEF1234567890   # your long key id
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys $KEY_ID)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword='<your passphrase or empty>'

./gradlew :affected-tests-gradle:signPluginMavenPublication \
          :affected-tests-gradle:signAffectedTestsPluginMarkerMavenPublication

# Cryptographically verify a produced signature:
gpg --verify \
  affected-tests-gradle/build/libs/affected-tests-gradle-*.jar.asc \
  affected-tests-gradle/build/libs/affected-tests-gradle-*.jar
# → expect: "Good signature from Vedanth Vasudev <…>"
```

`BUILD SUCCESSFUL` plus a "Good signature" line means the local
key + passphrase combination is correct. That validates everything
except the GitHub-side secrets — for an end-to-end check using the
actual repo secrets, trigger a `workflow_dispatch` run that only
calls the sign tasks (no publish) on the release branch.

## Maven Central publish (automatic)

Each release triggers `publishAndReleaseToMavenCentral` in
`release.yml`, which uploads the signed bundle to the Sonatype
Central Portal and auto-releases it. No manual step in
[central.sonatype.com](https://central.sonatype.com) is required.

Artifacts appear on
[search.maven.org](https://search.maven.org/search?q=g:io.github.vedanthvdev)
and `repo1.maven.org` ~15–30 minutes after the workflow step
succeeds. The `Check if Maven Central version already published`
step uses that propagation as its idempotency signal.

### Recovering from a failed or re-run release

If you re-run the workflow while a version is still propagating
to `repo1.maven.org` (HEAD returns 404), the `Publish to Maven
Central` step may attempt a duplicate upload. Sonatype rejects it
and the step fails — the Plugin Portal publish, SLSA attestation,
and GitHub Release from the original run are already done.

Wait for propagation (~30 min) and re-run, or check
[central.sonatype.com](https://central.sonatype.com) →
**Deployments** if a deployment is stuck in a non-terminal state.

## Verifying a release

After both publishes complete:

```bash
# Plugin Portal
open "https://plugins.gradle.org/plugin/io.github.vedanthvdev.affectedtests/<version>"

# Maven Central (after ~15–30 min)
open "https://central.sonatype.com/artifact/io.github.vedanthvdev/affected-tests-gradle/<version>"

# Provenance — verify the implementation shadow JAR (NOT the
# plugin-marker POM, which carries no executable code). The JAR
# is at:
#   ~/.gradle/caches/modules-2/files-2.1/io.github.vedanthvdev/affected-tests-gradle/<version>/<sha1>/affected-tests-gradle-<version>.jar
gh attestation verify <path-to-affected-tests-gradle-version.jar> \
  --owner vedanthvdev \
  --repo vedanthvdev/affected-tests
```
