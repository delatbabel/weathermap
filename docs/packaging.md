# Packaging

One `Makefile` builds installable packages for four platforms. `make help`
lists every target; this page covers what each needs.

```bash
make help                  # every target, with a one-line description
make version-print         # the full build version
make run                   # build if needed, then launch
make run ARGS="--cli"      # the command-line tool
```


## What ends up inside a package

Everything under `docs/` matching the `<resources>` block in `pom.xml` is bundled
into the jar as the Help pages, and therefore ships inside every package built
here. A file put in `docs/` goes out to anyone who installs the application, so
working material — research, costings, notes — belongs in `reference/`, which
nothing reads and nothing ships.

`make release` builds from a clean tree for the same reason. `mvn package` is
incremental and does not remove a resource that has been deleted from the source,
so a document moved out of `docs/` stayed inside the built jar until the next
clean. Incremental is right for `make run`; it is wrong for the thing being
handed to other people.

The jar itself also has its sources as prerequisites. Without them the rule had
none at all, make treated an existing jar as up to date whatever had changed
underneath it, and a release could package code that no longer matched the tree.


## Version numbering

`VNUM` at the top of the `Makefile` is the single source of truth. Everything
else is derived:

| Target | What it does |
|---|---|
| `version-print` | The full build version, which includes the commit and branch away from a release |
| `version-set` | Sets the pom version and the documentation to match `VNUM` |
| `version-docs` | Rewrites the version quoted in the README and in these pages |
| `version-bump` | Bumps the patch component in the `Makefile`, the pom and the documentation |
| `post-release` | Re-applies the Maven version after a release |

`version-docs` checks its own work the blunt way: every three-part number in
the files it rewrites must be the current version. Rewording a heading stops it
matching both the rewrite and the check, so the release fails instead of
shipping last version's number quietly. The price is that those files may not
quote any other version — which is why this page and
[Build and run](build-and-run.md) are on the list and
[Hosting the images](image-hosting.md), which names two releases of rclone, is
not.

The build version is deliberately richer than the package version. On a release
tag it is just `1.1.1`; on a `release-*` branch it gains the commit; anywhere
else it gains the commit and the branch, so a package built from a working tree
says so. Package *filenames* use the plain version, because a releases page
truncates long names from the tail — exactly where the architecture suffix lives.

## Linux — `.deb` and `.rpm`

```bash
make release-linux-deb
make release-linux-rpm     # additionally needs rpmbuild
make release-linux         # both
```

Built by the system JDK's `jpackage`, which bundles its own runtime, so there is
nothing to download first. The JDK must ship `jmods` *and* `jpackage`; the
Makefile picks the newest such JDK automatically, or set `JPACKAGE_JDK`.

Both packages install under `/opt/weathermap`, symlink the launcher into
`/usr/bin`, and register the icon with `xdg-icon-resource` — which is what runs
`gtk-update-icon-cache`, and without it an upgrade over a version that had no
icon leaves the desktop's cached "no icon" in place.

## Windows and macOS — cross-built

```bash
make bootstrap             # once: fetches per-platform JDKs and the tools
make release-windows       # x86_64, aarch64 and x86_32 installers
make release-macos         # x86_64 and aarch64 disk images
```

These cannot use `jpackage`, which only targets the platform it runs on, so the
runtime is `jlink`ed from a downloaded JDK for each target and the result is
wrapped:

- **Windows** — Launch4j embeds the fat jar in an `.exe`, then `makensis` packs
  that plus the runtime into an installer. Install the system `nsis` package.
- **macOS** — a `.app` bundle with its own runtime, turned into a `.dmg` by
  `genisoimage` and `libdmg-hfsplus`.

`make bootstrap` downloads several hundred megabytes into `dist/`, which is
gitignored. 32-bit Windows tops out at Java 17 — there is no newer 32-bit build
— so `bootstrap` fetches two host JDKs: `jlink` refuses to link a runtime whose
feature version does not match its own.

### The module list is not guesswork

`APP_MODULES` in the `Makefile` is what the linked runtimes contain, and
`jdk.crypto.ec` is in it for a reason no tool will tell you: it supplies the
SunEC provider, which is loaded as a *service*, so nothing in the bytecode
mentions it and `jdeps` cannot find it. A runtime without it has no elliptic
curve key agreement, and every HTTPS request fails in the handshake — on the
user's machine, in a message that names no module. The Linux packages never show
this, because `jpackage` includes the whole JDK.

Every linked runtime is checked against the list after linking, and the list is
kept in a stamp file so that changing it actually relinks: a runtime is a
directory, and Make would otherwise consider it up to date forever.

## Checksums and cleaning

```bash
make release-sha256        # SHA-256 of every built package
make clean-release         # remove tmp/
make clean                 # that, plus mvn clean
```

Every package carries the `LICENSE` file; the Windows staging step renames it
`LICENSE.txt` so Windows opens it with a click.

## Not yet covered

- **Nothing is signed.** Windows will warn about an unknown publisher and macOS
  will refuse to open the bundle without a right-click, until the packages are
  signed and — on macOS — notarised.
