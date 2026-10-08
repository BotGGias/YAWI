# YAWI

Cross-platform setup wizard ("Windows-like Installer") based on
JavaFX. 
This project aims to provide an windows like installer for your desired operating system.
This means you can distribute a jar, exe, appimage or dmg file. altough you would need to compile it on that system first since there is no cross compile.
Currently only Linux was testet.

Although alternatives exist, real freedom comes only when you own your stuff.
On linux for example appImage exist, but are poorly integrated into KDE startmenu etc.
Flatpacks require someone to provide a service and be liable for it. Which means they need to set arbitrary rules that may change any time.
This Installers follows your rules. No Internet? Good, you provide the software.
Install from Internet? Also good, you download the software.
You can do whatever you want.

# Packager
A packager describes their product in an XML manifest file
(components, data sources: bundled / HTTP / BitTorrent, commands per
operating system); the wizard provides the end user with language selection,
component selection, and target directory selection, and performs the installation with
progress display, logging, and rollback.


## Prerequisites

* **JDK 21** (LTS). Developed and tested with Azul Zulu 21.0.5.
  JavaFX is included as a Maven dependency (`org.openjfx` 17.0.6); a JDK with
  bundled JavaFX is not required.
* Maven is loaded via the included wrapper (`./mvnw`, `mvnw.cmd`).

## Build, Test, Run

```sh
./mvnw clean test     # Build + unit tests (JUnit 5)
./mvnw javafx:run     # Start wizard
./mvnw javafx:run -Djavafx.args="--manifest=/path/installer.xml"   # another manifest
./mvnw javafx:run -Djavafx.args="--silent --help"                   # Silent mode: all options
./mvnw test -Dtest=HttpDownloaderNetworkIT -Dyawi.network.url=https://…/file.zip \
    -Dsurefire.failIfNoSpecifiedTests=false   # opt-in: download against a real server
./mvnw -Pdist package -DskipTests             # portable archive, see "Distribution"
```

The normal test suite never touches the network (download tests run against an
embedded `HttpServer`) and never writes to the real home directory: tests that actually
install run on a platform with the temp directory as home
(`TestPlatforms.scratch`), so registries and shortcuts end up there.

## Distribution (portable archive)

`./mvnw -Pdist package` builds a jpackage app image with its own
jlink runtime image (no installed Java required) and packages it as
`target/dist/yawi-installer-<version>-<os>-<arch>.{tar.gz|zip}` — for the
operating system and architecture of the machine performing the build (jpackage
does not cross-compile). Tools: the JDK 21 running Maven (`jlink`,
`jpackage`), and the system `tar` (Windows: bsdtar, writes the Zip).

| OS      | Archive                          | Entry (launcher, relative to archive root)         |
| ------- | -------------------------------- | -------------------------------------------------- |
| Linux   | `…-linux-x86_64.tar.gz`          | `bin/yawi-installer`                               |
| Windows | `…-windows-x86_64.zip`           | `yawi-installer.exe`                               |
| macOS   | `…-macos-{arm64\|x86_64}.tar.gz` | `yawi-installer.app/Contents/MacOS/yawi-installer` |

The entry point accepts the same arguments as `Main` (`--silent …`, see above); An
`installer.xml` (+ `.sig`) **next to the entry point** takes precedence over the embedded
manifest; the versioned manifest of a launcher release is subsequently placed into the
archive by the launcher (`make release-manifest`, `yawi-release installer-xml`),
`-Dyawi.dist.manifest=<file>` places a custom one there for manual testing. Name,
version (`yawi.app.version`, project version without `-SNAPSHOT`), vendor, and icon are
defined in `pom.xml` (`yawi.app.*`); the icons are in
`src/main/deploy/icons/` (`tools/icons/make-icons.sh` generates them from
`img/icon.png`). To verify a built archive:

```sh
./mvnw test -Dtest=DistArchiveIT -Dyawi.dist.archive=target/dist/yawi-installer-1.0.0-SNAPSHOT-linux-x86_64.tar.gz \
    -Dsurefire.failIfNoSpecifiedTests=false   # extracts, starts the entry without a display, checks installer.xml next to the entry
```

## Silent Mode

With `--silent`, the installation runs without a window and without JavaFX;
`--help` displays all options, `--version` displays the product and version of the manifest.
Each option is `--name` or `--name=value`; anything else is rejected with exit 2.

```sh
yawi-installer --silent --dest=/opt/app --accept-license --lang=de --components=core,server --input.serverPort=5000
yawi-installer --silent --dry-run --dest=/opt/app --accept-license        # show the plan only
yawi-installer --silent --update --dest=/opt/app --cache=/data/updates/1.2.3 --wait-pid=4711 --relaunch \
    --result=/data/updates/1.2.3/result.json --log=/data/logs/installer.log --lang=de --accept-license
```

`--update` brings an existing installation (components and inputs from
`<dest>/.installer/record.xml`) up to the state of the manifest; `--cache` takes
files listed under the `sha256` of a source in `<cache>/SHA256SUMS` from there
after its own verification and downloads only the remainder; `--wait-pid` waits
up to 60 s before the first write for the processes to finish; `--result`
writes `{"exitCode","version","finishedAt","message"}`; `--relaunch` starts
the target of the first `<shortcut>` afterwards.

If a run fails or is aborted, the installer rolls back the
changes: replaced files are stored beforehand under
`<dest>/.installer/backup/<startingTime>/` and restored, created files
are deleted, `run-command` steps are reversed through their `<rollback>` (if
missing, this is reported); the exit code remains that of the error, and `--relaunch`
starts the restored state.

`--uninstall` removes the installation under `--dest` based on its record
: first the `<rollback>` commands of the executed steps, then the
recorded files, finally `.installer/`, the registry entry, and the now-empty
directories. Files that have been modified since installation (modification time
after the end of the run), and files that were not created by the installer, **remain**
and are reported; `--purge` also deletes them and therefore the entire directory.
`--dry-run` displays every action without changing anything.
Exit `0` even with intentionally retained items, `1` if elements cannot be removed
(the record and registry then remain for a second attempt), `11`
without a record; `--relaunch`, `--update`, `--config`, `--components`, and
`--input.*` are not allowed with `--uninstall`.

```sh
yawi-installer --silent --uninstall --dest=/opt/app --dry-run
yawi-installer --silent --uninstall --dest=/opt/app                # modified and foreign files remain
yawi-installer --silent --uninstall --dest=/opt/app --purge        # remove everything under /opt/app
```

A **response file** (`--config=<file>`, XML according to
`src/main/resources/installer-answers-1.xsd`, example
[`docs/examples/answers.xml`](docs/examples/answers.xml)) specifies language,
target directory, components, source selection (`bundled`/`http`/`torrent` per
source), input values, and license acceptance; arguments take precedence over
the file, and the file takes precedence over the manifest defaults. What is in the
file must be complete and match the manifest — a missing required value or
unknown ID is reported with the affected element (exit 2), never guessed.
`--config` is only valid with `--silent` and not together with `--update`. The wizard
writes its selection on the completion page using "Save selection..." in the
same format (inputs that look like passwords are omitted).

```sh
yawi-installer --silent --config=answers.xml                       # everything from the file
yawi-installer --silent --config=answers.xml --dest=/opt/app --input.serverPort=6000   # arguments override
```

Exit codes: `0` ok · `1` general (also: incomplete uninstallation) · `2`
arguments · `3` manifest · `4` checksum/signature · `5` permissions · `6`
disk space · `7` aborted · `8` source unreachable · `9` step
failed · `10` platform · `11` no installation under `--dest`
(`--update`, `--uninstall`) · `12` wait period expired · `13`
administrator privileges required, but no privilege escalation mechanism available
(`core.error.ErrorCode`). Progress is emitted as text lines, `--output=json`
returns one JSON object per event, `--quiet` outputs errors only; the log always goes to
a file (`--log` or the platform location).

## Administrator Privileges

The UI never runs elevated. If a run requires administrator privileges — because
the target directory requires them (then all steps) or because individual
`run-command` steps have `elevated="true"` (then only those, as a block
**after** the others) — the installer **starts itself** a second time
through the system's privilege prompt and passes only the plan to the child
process: Linux `pkexec`, otherwise `sudo -A` with an Askpass program
(`SUDO_ASKPASS`, `ssh-askpass`, `ksshaskpass`, …), otherwise `sudo` in the terminal
from which the installer was started; Windows UAC (`runas`); macOS
`osascript … with administrator privileges`. The installer itself never prompts for
a password. The prompt appears directly when the installation starts; if it is
rejected, this is an abort (exit 7), nothing was written. If no mechanism is
available, the run ends with exit 13. The wizard's summary announces the requirement
and displays the affected commands in plain text (passwords masked); a link leads
back to target selection.

The two processes communicate exclusively via files in a
private directory `<tmp>/yawi-installer/elevated-…/` (plan, events,
result, markers), which disappears after the run; the child process's log
remains as `installer-elevated.log` next to the main log. Files that the
elevated process creates in the user's account (menu entries, desktop shortcuts,
and there as well for a user target) are subsequently owned by the user again.
`-Dyawi.elevation=none` disables escalation (exit 13 when required),
`-Dyawi.elevation=direct` starts the child process without a privilege prompt (tests,
development as root), `-Dyawi.elevation=<name>` forces a strategy. The internal option
`--elevated-run=<folder>` is the child process's entry point.

## Existing Installation (Registry and Maintenance Page)

Every successful installation — from the wizard as well as from silent mode —
registers itself in a **user registry**:
(Linux) `<configDir>/<productId>/installer/installations/<key>.xml` 
`~/.config/<id>/installer/…`,
Windows `%APPDATA%\<id>\installer\…`, 
macOS `~/Library/Application Support/<id>/installer/…`), one entry per target
directory with product, version, and path. The record
`<dest>/.installer/record.xml` remains the actual source of truth; the registry
only says where it is located — even if the directory has since been deleted.
A dry run or a rolled-back run registers nothing; a failure while
registering does not abort the installation.

When the wizard finds an entry at startup, it displays a **maintenance page**
before the first page: installed and bundled version, target directory, and the options
*Update* (only for a newer version), *Modify*, or *Uninstall*. Update and Modify run
through the normal wizard with pre-filled target, components, and inputs. *Uninstall*
leads to a dedicated page showing what will be removed and asking two questions —
delete files modified since installation? delete additional files in the directory
that were not created by the installer? (both default to "no") — and then directly
to the progress page; the completion page reports what was removed and what was
retained. If the directory or its
record has disappeared, the page offers to remove the orphaned entry. Without an
entry, the page does not appear; the names `maintenance` and `uninstall` are reserved
in the manifest.

## Shortcuts (System Integration)

Every `<shortcut>` in the `<integration>` block of the manifest runs as a separate
step after the component steps — both in the wizard and in silent mode;
`--dry-run` displays it as well. It points to `<target>` (must be within
`${destination}`) and receives `<icon>` (`classpath:` or file) or otherwise the product
icon:

| OS      | Menu (`menu="true"`)                                                                                                                                                                     | Desktop (`desktop="true"`)                        |
| ------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| Linux   | `~/.local/share/applications/<product-id>-<id>.desktop` (system-wide `/usr/share/applications`), icon under `…/icons/hicolor/<B>x<H>/apps/`, then `update-desktop-database` if available | `~/Desktop/<product-id>-<id>.desktop`, executable |
| Windows | `<Name>.lnk` in Start Menu *Programs* (via PowerShell/`WScript.Shell`)                                                                                                                   | `<Name>.lnk` on the Desktop                       |
| macOS   | Symlink `<Name>` or `<Name>.app` in `~/Applications` (system-wide `/Applications`)                                                                                                       | Symlink on the Desktop                            |

System-wide means: the target is at a system path. A shortcut that
cannot be created (target missing, PowerShell blocked, directory not
writable) does **not** abort the installation; it is reported like a
continued step. The files are recorded with absolute paths — existing ones are
copied to the backup beforehand — and disappear with the
uninstallation. The first `<shortcut>` is also the target of "… launch now"
on the completion page and of `--relaunch`; without `<shortcut>` the checkbox is
missing. Windows and macOS are implemented but have not yet been verified there;
file associations and PATH entries follow.

## BitTorrent

A source can have a `torrent` provider (`<magnet>` or `<torrentFile>`; attributes
`port`, `peerTimeout`, `fallback`, `lsd`, `pex`. The installer uses the vendored bt library with
UDP and HTTP(S) trackers — **no DHT, no UPnP**; a magnet therefore needs `tr=` trackers.
If no peer arrives within `peerTimeout` (default 90 s), the installer falls back to
the next provider (HTTP) — automatically or after asking (`fallback="ask"`). The
source page points out that a port is opened and uploading takes place. The torrent
tests run without network access against an in-process seeder on loopback.

## Checksums and Signatures

Sources with `sha256` in the manifest are verified when acquired — downloads
during writing, bundled data in a single read pass before extraction. A manifest
**from the network** (`--manifest=https://…`) must be signed
(`installer.xml.sig` alongside it, RSA-SHA256, Base64); bundled and local
manifests may be unsigned. The public key is embedded
(`src/main/resources/de/yawi/installer/integrity/manifest-signing.pub.pem`);
the repository contains a **development key pair** under `tools/keys/dev/`,
which is replaced with a dedicated one for a release (see `tools/keys/dev/README.md`).

```sh
./mvnw -q compile exec:java@sign-manifest -Dyawi.sign.args="sign dist/installer.xml tools/keys/dev/manifest-signing.key.pem"
./mvnw -q compile exec:java@sign-manifest -Dyawi.sign.args="verify dist/installer.xml dist/installer.xml.sig tools/keys/dev/manifest-signing.pub.pem"
./mvnw -q compile exec:java@sign-manifest -Dyawi.sign.args="keygen /secure/location"
./mvnw javafx:run -Dyawi.signature.policy=lenient -Djavafx.args="--manifest=http://…/installer.xml"   # Development: unsigned allowed
```

The `yawi.signature.policy` switch (default `strict`) is filtered into a
resource during the build and is fixed in the artifact; even with `lenient`,
the installer does not execute commands from an unsigned network manifest.

The manifest is searched for in this order: `--manifest=<file|url>`,
`installer.xml` next to the application, `/installer.xml` on the classpath (the
bundled one under `src/main/resources/installer.xml`). Schema:
`src/main/resources/installer-manifest-1.xsd`, reference:
[`docs/backlog/manifest-schema.md`](docs/backlog/manifest-schema.md).

Logs (console + rolling file, see `src/main/resources/logback.xml`) begin
under `${java.io.tmpdir}/yawi-installer/installer.log` and switch, once the
manifest has been loaded, to the platform location — `~/.local/state/<product-id>/`,
`%LOCALAPPDATA%\<product-id>\Logs`, `~/Library/Logs/<product-id>`
(`core.error.LogSetup`). "Open log" and "Save report" on the
completion page use the current file; the report masks the home path and
username.

## Structure

```text
src/main/java/de/yawi/installer/
  core/   headless core — manifest, platform (incl. DestinationValidator), error classes (core.error),
          engine (core.engine: Plan, placeholders, steps in core.engine.step, rollback, Uninstaller, LaunchTarget), installation record
          and registry (core.state: InstallationRecord, InstallationRegistry, ExistingInstallation), checksums and PathGuard (core.integrity), downloads (core.download: HttpDownloader,
          SourceResolver, LocalCache, VersionChecker), InstallRunner (core.engine), response file (core.answers:
          AnswerFile, AnswerFileReader/-Writer, schema installer-answers-1.xsd), shortcuts (core.integration:
          ShortcutLayout, DesktopEntry, WindowsShortcutScript, DesktopDatabase) … (no JavaFX)
  Main    entry point (no JavaFX): arguments → Silent mode or Wizard
  ui/     JavaFX wizard: WizardApp, frame (WizardFrameController), page flow (WizardFlow),
          page controllers (ui.page), text bindings (ui.i18n), InstallTask (InstallRunner as JavaFX task),
          UninstallTask (Uninstaller as JavaFX task)
  cli/    silent mode: Arguments, Answers (precedence Arguments > response file), SilentRun, ConsoleReporter,
          ProcessWaiter, ResultFile
src/main/java/bt/   vendored BitTorrent library bt 1.10 (Apache 2.0 — LICENSE, NOTICE)
src/main/resources/de/yawi/installer/i18n/   language files (messages.properties = English, base)
src/main/resources/de/yawi/installer/ui/     wizard_frame.fxml, wizard.css, page/*.fxml (content pages)
src/main/resources/payload/, templates/    demo payload and template installed by installer.xml
src/main/deploy/icons/                     jpackage icons (png/ico/icns), generated by tools/icons/make-icons.sh
src/test/resources/manifest/               test manifests (full.xml = full example, chain.xml, invalid/*)
src/test/resources/answers/, docs/examples/ response files (tests, commented example)
```
