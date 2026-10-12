# Online updates

Players install a build with the updater once. From then on, each new release downloads in the background and replaces the jar when they close the game.

## How it works

1. On startup the client reads a small file, `update.properties`, from the latest GitHub release. It holds the version, the jar's file name, its SHA-256 hash and a signature.
2. If that release is newer, the client downloads the jar in the background and checks three things:
   - the file size
   - the SHA-256 hash
   - the **signature**, made with your private key and checked against the public key built into the jar

   If any check fails, the file is discarded and nothing is installed.
3. When the player is in a world, chat shows: "v2.2.0 is downloaded and verified; it installs when you close the game."
4. After the game closes, a small helper process waits until the game has fully exited. Windows does not allow replacing a jar while it is in use. The helper then moves the old jar to `.minecraft/myau-update/backup/` and puts the new one in `mods`. If that second step fails, it puts the old jar back.
5. The next launch runs the new version.

## One-time setup

**1. Generate the signing key.** From the project folder, run:

```
java tools/update/GenUpdateKey.java
```

This writes two files:

- `src/main/resources/myau-update-key.pub` is the **public key**. Commit it.
- `update-signing-key.txt` is the **private key**. **Never commit it.** `.gitignore` already excludes it.

**2. Add the private key to GitHub.** In the repository, go to Settings → Secrets and variables → Actions → New repository secret.

- Name: `UPDATE_SIGNING_KEY`
- Value: the full contents of `update-signing-key.txt`

**3. Back up the private key** somewhere safe, such as a password manager. Then delete `update-signing-key.txt`.

> If you lose the private key, clients already installed can no longer auto-update, and players have to reinstall once by hand.
> If the private key leaks, someone else can sign updates your clients will trust. Generate a new key (`--force`) and ask players to reinstall by hand.

## Publishing a release

1. Set `version` in `gradle.properties` (for example `2.1.2`), then commit and push.
2. Create a GitHub release with the tag **`v2.1.2`**: the same version with a leading `v`. Write the notes and click Publish.
   **You do not need to upload the jar.**
3. Wait for the **Release (online update)** workflow to finish (about 5–10 minutes). It builds the tag, signs the jar, and attaches `Myau-Atlas-v2.1.2.jar` and `update.properties` to the release.
4. Done. Players get the update the next time they start the game.

Notes:

- If the tag does not match `version`, the workflow fails with a message that says why. A mismatched release is never published to players.
- Releases marked **pre-release** are **not** offered to players. Testers can still download them by hand.
- If the workflow fails, fix the cause, then open Actions → Release (online update) → Run workflow and enter the tag to run it again.

## In-game commands

| Command | What it does |
|---|---|
| `.update` | Shows the current version, the latest version and the update state |
| `.update check` | Checks for an update now |
| `.update download` | Downloads the update by hand when the mode is `notify` |
| `.update cancel` | Skips installing the update when the game closes this time |
| `.update mode auto` | Installs updates automatically (default) |
| `.update mode notify` | Only tells you about new versions |
| `.update mode off` | Turns updates off completely |

The mode is saved in `config/Myau/updater.properties`. The installer writes a log to `.minecraft/myau-update/install.log`.

## Where the code is

- `src/main/java/myau/update/`: the update logic (`Updater`), version comparison, signature checks, the jar-swap helper (`UpdateInstaller`) and the chat notices
- `src/main/java/myau/command/commands/UpdateCommand.java`: the `.update` command
- `.github/workflows/release.yml`: builds and signs releases
- `tools/update/`: the key generator and the signing tool
- `src/test/java/myau/update/UpdaterTest.java`: 17 tests, covering tampered files, foreign signatures, downgrades and a real jar swap
