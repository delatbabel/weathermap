#
# Makefile for Weather Map.
#
# For use on Linux systems. The version scheme, the cross-build arrangement and
# the packaging targets are adapted from the VASSAL Extension Utility's, which
# took them in turn from VASSAL's own.
#
# Native Linux packages (deb, rpm) are built with the system JDK's jpackage,
# which bundles a jlink runtime automatically. Cross-building Windows
# (installer .exe via Launch4j + makensis) and macOS (.dmg via libdmg-hfsplus)
# packages needs extra tools and per-platform JDKs; run `make bootstrap` (or
# dist/bootstrap.sh) once to fetch them — makensis comes from the system's
# nsis package. See docs/packaging.md for the full details and requirements.
#

SHELL:=/bin/bash

# =======================================================================
# Version numbering
# =======================================================================

# The numeric version — the single source of truth. Bump this for a release.
VNUM:=1.1.1
# major.minor part
V_MAJ_MIN:=$(shell echo "$(VNUM)" | cut -f1,2 -d'.')
# four-part form required by the Windows .exe version resource
NUMVERSION4:=$(VNUM).0

# The Maven/pom version. Use -SNAPSHOT between releases; plain VNUM to release.
MAVEN_VERSION:=$(VNUM)
#MAVEN_VERSION:=$(VNUM)-SNAPSHOT

# Full, unique build version derived from git (used in artifact filenames):
#   - on a release tag matching MAVEN_VERSION -> just the version
#   - on a release-* branch                   -> version + commit
#   - anywhere else                           -> version + commit + branch
GITBRANCH:=$(subst /,_,$(shell git rev-parse --abbrev-ref HEAD 2>/dev/null))
GITCOMMIT:=$(shell git rev-parse --short HEAD 2>/dev/null)
ifeq ($(shell git describe --tags 2>/dev/null),$(MAVEN_VERSION))
  VERSION:=$(MAVEN_VERSION)
else ifeq ($(patsubst release-%,release,$(GITBRANCH)),release)
  VERSION:=$(MAVEN_VERSION)-$(GITCOMMIT)
else
  VERSION:=$(MAVEN_VERSION)-$(GITCOMMIT)-$(GITBRANCH)
endif

# The version used in package FILENAMES — deliberately just MAVEN_VERSION, with
# none of the commit/branch $(VERSION) may append, and the Windows/macOS names
# drop the redundant platform tag too (the .exe/.dmg suffix already says it).
# GitHub's releases page truncates long file names, and what gets cut is the
# tail — precisely the architecture suffix ("-x86_64.exe") a user needs to pick
# the right download. The full $(VERSION) still goes inside the packages (the
# .exe version resource, the installer's product version and install directory).
PKGVERSION:=$(MAVEN_VERSION)

YEAR:=$(shell date +%Y)

# =======================================================================
# Project identity / paths
# =======================================================================

ARTIFACT:=weathermap
JARNAME:=$(ARTIFACT)-$(MAVEN_VERSION)
MAINCLASS:=org.weathermap.Main

# Names used in the built packages
APPNAME:=Weather Map
PKGNAME:=weathermap
VENDOR:=Weather Map
DESCRIPTION:=Composites NOAA GRIB forecast fields over an OpenStreetMap base map

# Linux launcher/binary name — no spaces, so it is convenient to run and to put
# on PATH. jpackage's --name sets the launcher executable name AND (by default)
# the menu entry's display name; we override the latter back to $(APPNAME) via a
# custom .desktop resource (see $(TMPDIR)/jpackage-res below) so the menu entry
# reads "Weather Map" rather than the launcher filename.
LAUNCHER:=weathermap
# jpackage installs the app under /opt/<package-name>; the launcher is bin/<name>.
LINK_SRC:=/opt/$(PKGNAME)/bin/$(LAUNCHER)
LINK_DST:=/usr/bin/$(LAUNCHER)

MVN:=./mvnw
DISTJAR:=target/$(JARNAME)-jar-with-dependencies.jar

DISTDIR:=dist
TMPDIR:=tmp
TOOLDIR:=$(DISTDIR)/tools
JDKDIR:=$(DISTDIR)/jdks

# JVM modules the runtime must contain.
#
# java.net.http is the download client, java.xml supplies the StAX parser the OSM
# reader uses, java.desktop covers Swing, Java2D and ImageIO, and java.prefs is
# reached by the look and feel.
#
# jdk.crypto.ec is NOT optional and is not discoverable with jdeps: it supplies
# the SunEC provider, which is loaded as a service, so nothing in the bytecode
# refers to it. Without it a runtime has no EC key agreement - no x25519, no
# secp256r1 - leaving only the FFDHE groups, which NOMADS and the Overpass
# instances do not accept, and every HTTPS request dies with
#
#     javax.net.ssl.SSLHandshakeException: (handshake_failure)
#                   Received fatal alert: handshake_failure
#
# The Linux .deb/.rpm are built by jpackage, whose runtime holds all the JDK's
# modules, whereas the Windows and macOS runtimes are jlinked from exactly this
# list - so this is the kind of omission that works everywhere it is tested and
# fails on a user's machine. It costs nothing in size to include.
#
# jdk.charsets covers legacy encodings an OSM or GRIB response may declare;
# jdk.localedata is deliberately left out, costing 11 MB to change how dates are
# formatted outside English locales.
APP_MODULES:=java.base,java.desktop,java.xml,java.net.http,java.logging,java.prefs,jdk.crypto.ec,jdk.charsets

# JDK used for native packaging: must ship jmods AND jpackage, so jpackage's
# internal jlink can build a runtime image. Auto-detect the first such JDK;
# override with `make JPACKAGE_JDK=/path/to/jdk`.
JPACKAGE_JDK?=$(shell for d in /usr/lib/jvm/*/ ; do \
  [ -d "$${d}jmods" ] && [ -x "$${d}bin/jpackage" ] && echo "$${d%/}"; done | sort -V | tail -1)
JPACKAGE:=$(JPACKAGE_JDK)/bin/jpackage
# Cross-linking a Windows/macOS runtime requires a host jlink whose Java feature
# version EXACTLY matches the target JDK's jmods (jlink refuses a mismatch, e.g.
# "jlink version 21.0 does not match target java.base version 17.0"). Targets use
# different versions — Windows 32-bit tops out at Java 17 (no newer 32-bit build)
# while everything else uses 21 — so we resolve a jlink per version. These must
# match the versions bootstrap downloads (see JDK_MAIN / JDK_WIN32 in bootstrap.sh).
JDK_MAIN_VER:=21
JDK_WIN32_VER:=17

# Resolve a Linux host jlink of a given Java feature version: prefer one
# bootstrapped under dist/jdks/linux-x86_64-<ver>, else a system JDK of that
# version. Empty if none is available (the build rule then errors with guidance).
find_host_jlink=$(or $(wildcard $(JDKDIR)/linux-x86_64-$(1)/bin/jlink),$(shell \
  for d in /usr/lib/jvm/*/ ; do [ -x "$${d}bin/jlink" ] && \
    [ "$$($${d}bin/jlink --version 2>/dev/null | cut -d. -f1)" = "$(1)" ] && \
    { echo "$${d}bin/jlink"; break; }; done))

JLINK_MAIN:=$(call find_host_jlink,$(JDK_MAIN_VER))
JLINK_WIN32:=$(call find_host_jlink,$(JDK_WIN32_VER))

# Cross-build tools (populated by `make bootstrap`)
LAUNCH4J_JAR:=$(TOOLDIR)/launch4j/launch4j.jar
LAUNCH4J:=java -jar $(LAUNCH4J_JAR)
DMG:=$(TOOLDIR)/libdmg-hfsplus/build/dmg/dmg
GENISOIMAGE:=genisoimage

# Note: no --strip-debug — when cross-linking, its native-symbol stripping runs
# the host objcopy against target-platform binaries (e.g. macOS Mach-O), which
# fails. --compress=2 (not zip-6) is used because it is accepted by every jlink
# version we use as a host: the newer zip-N form is JDK 21+ only, whereas the
# 32-bit Windows target requires a Java 17 host jlink.
JLINK_OPTS:=--no-header-files --no-man-pages --compress=2 \
            --add-modules $(APP_MODULES)

# Assert a linked runtime really contains every module asked for. jlink is happy
# to produce an image that cannot make an HTTPS connection, and the resulting
# failure appears only on the user's machine, in a handshake, a long way from
# anything that names a module. $(1) is the runtime directory.
check_runtime_modules=\
  have="$$(sed -n 's/^MODULES="\(.*\)"$$/\1/p' "$(1)/release")"; \
  for m in $$(echo "$(APP_MODULES)" | tr ',' ' '); do \
    case " $$have " in *" $$m "*) ;; \
      *) echo "ERROR: $(1) is missing module $$m"; exit 1 ;; esac; \
  done; \
  echo "runtime modules OK ($(1)): $$(echo "$$have" | wc -w) modules"

# A linked runtime is a directory, so Make considers it up to date forever —
# change APP_MODULES and the old runtime is repackaged unchanged, which is how a
# runtime linked before the jdk.crypto.ec fix ended up inside a rebuilt zip. The
# runtime rules therefore depend on this stamp, whose recipe runs every time but
# only rewrites the file when the list actually differs, so ordinary rebuilds
# relink nothing.
MODULES_STAMP:=$(TMPDIR)/.app-modules
$(MODULES_STAMP): FORCE | $(TMPDIR)
	@[ "$$(cat $@ 2>/dev/null)" = "$(APP_MODULES)" ] || { \
	   echo "module list changed — relinking runtimes"; \
	   printf '%s' "$(APP_MODULES)" > $@; }

FORCE:

# =======================================================================
# Common targets
# =======================================================================

.DEFAULT_GOAL:=help

help:
	@echo "Weather Map — available targets:"
	@echo ""
	@echo "  Build:"
	@echo "    compile / build   Compile Java sources"
	@echo "    test              Run unit tests"
	@echo "    jar               Build the executable fat JAR"
	@echo "    run               Run the application (make run ARGS=...)"
	@echo "    cli               Run the command-line tool"
	@echo "    netcdf            Build with the optional NetCDF-Java decoder"
	@echo "    javadoc           Generate Javadoc"
	@echo "    clean             Remove build artefacts"
	@echo ""
	@echo "  Version:"
	@echo "    version-print     Print the full build version ($(VERSION))"
	@echo "    version-set       Set the Maven/pom version to $(MAVEN_VERSION)"
	@echo "    version-bump      Bump the patch version by 0.0.1 (Makefile + pom + docs)"
	@echo "    version-docs      Rewrite the version quoted in the README and docs"
	@echo "    post-release      Re-apply the Maven version after a release"
	@echo ""
	@echo "  Packages (output in $(TMPDIR)/):"
	@echo "    bootstrap             Fetch Windows/macOS cross-build tools + JDKs"
	@echo "    release-linux-deb     Linux .deb        (jpackage)"
	@echo "    release-linux-rpm     Linux .rpm        (jpackage; needs rpmbuild)"
	@echo "    release-windows       Windows installer .exe, all three architectures"
	@echo "                          (Launch4j + makensis)"
	@echo "    release-windows-x86_64 / -aarch64 / -x86_32"
	@echo "    release-macos         macOS .dmg, both architectures (libdmg-hfsplus)"
	@echo "    release-macos-x86_64 / -aarch64"
	@echo "    release-sha256        SHA-256 checksums of all packages"
	@echo "    release               Everything above (deb, rpm, windows, macos)"
	@echo "    clean-release         Remove built packages"
	@echo ""
	@echo "  See docs/packaging.md for prerequisites and details."

build: compile

compile:
	$(MVN) compile

test:
	$(MVN) test

jar: $(DISTJAR)

# Everything the jar is built from. Without these the rule has no prerequisites
# at all, so make sees a file that exists, calls it up to date and does nothing
# - `make jar` after editing a source file rebuilt nothing, and `make release`
# would happily package a jar built from code that had since changed.
SOURCES:=pom.xml $(shell find src -type f 2>/dev/null)

$(DISTJAR): $(SOURCES)
	$(MVN) package

# Replaces the old run.sh. ARGS passes options through, so the command-line tool
# is reachable the same way:  make run ARGS="--cli --profile 'Western Approaches'"
run: $(DISTJAR)
	java -jar $(DISTJAR) $(ARGS)

# Kept from the Makefile this replaced: the command-line tool with no arguments
# repeats the last selection, which is the common case and worth one word.
cli: $(DISTJAR)
	java -jar $(DISTJAR) --cli $(ARGS)

# Also kept: the optional NetCDF-Java decoder, for raw GRIB files and the
# Lambert-conformal NAM and HRRR grids the built-in reader does not handle.
netcdf:
	$(MVN) package -Pnetcdf

javadoc:
	$(MVN) javadoc:javadoc

$(TMPDIR):
	mkdir -p $@

# =======================================================================
# Version management
# =======================================================================

version-print:
	@echo $(VERSION)

# alias kept for backwards compatibility
version: version-print

version-set: version-docs
	$(MVN) versions:set -DnewVersion=$(MAVEN_VERSION) -DgenerateBackupPoms=false

# The prose that quotes the version: a jar name in an example, the number a
# release tag carries, the line in the README. VNUM is only the single source
# of truth if bumping it actually reaches them, and before this it did not -
# the documentation was corrected by hand and went stale in between.
#
# Then it checks its own work, and checks it the blunt way: every three-part
# number in these files must be this version. Matching only the three phrasings
# the sed knows about was tried and is worth nothing - rewording a heading
# stops it matching the sed AND the check together, which is exactly the case
# the check exists for. So a reworded heading fails the release instead of
# quietly shipping last version's number.
#
# The price is that these files may not quote any other version. A file that
# does - docs/image-hosting.md names two releases of rclone - does not belong
# on this list.
VERSIONED_DOCS:=README.md docs/build-and-run.md docs/packaging.md

version-docs:
	@sed -i -E 's/weathermap-[0-9]+\.[0-9]+\.[0-9]+/weathermap-$(VNUM)/g' $(VERSIONED_DOCS)
	@sed -i -E 's/^\*\*Version [0-9]+\.[0-9]+\.[0-9]+\*\*/**Version $(VNUM)**/' README.md
	@sed -i -E 's/tag it is just `[0-9]+\.[0-9]+\.[0-9]+`/tag it is just `$(VNUM)`/' \
		docs/packaging.md
	@stale=$$(grep -rnE '[0-9]+\.[0-9]+\.[0-9]+' $(VERSIONED_DOCS) \
			| grep -vF '$(VNUM)' || true) ; \
	if [ -n "$$stale" ] ; then \
		echo "version-docs: a version was left behind, so the wording no longer" ; \
		echo "matches what this target rewrites. Fix one or the other:" ; \
		echo "$$stale" ; \
		echo "(the other files have already been rewritten; git checkout them" ; \
		echo " if you would rather start again)" ; \
		exit 1 ; \
	fi
	@echo "Documentation now says $(VNUM)"

# Bump the patch component of VNUM (e.g. 1.0.0 -> 1.0.1), rewriting VNUM in this
# Makefile and setting the pom version to match so the build stays consistent.
# (The new value is computed in the shell because make expands $(VNUM) once, at
# parse time — a plain dependency on version-set would use the old value.)
version-bump:
	@new=$$(echo "$(VNUM)" | awk -F. 'BEGIN{OFS="."} {$$NF=$$NF+1; print}') ; \
	echo "Bumping version: $(VNUM) -> $$new" ; \
	sed -i -E "s/^VNUM:=.*/VNUM:=$$new/" Makefile ; \
	$(MVN) -q versions:set -DnewVersion=$$new -DgenerateBackupPoms=false ; \
	$(MAKE) --no-print-directory version-docs ; \
	echo "Updated Makefile VNUM, pom.xml and the documentation to $$new"

post-release: version-set

# =======================================================================
# Cross-build tooling
# =======================================================================

bootstrap:
	$(DISTDIR)/bootstrap.sh

# =======================================================================
# Linux — deb / rpm  (jpackage bundles a runtime automatically)
# =======================================================================

# Common jpackage arguments for the Linux installers.
JPACKAGE_COMMON=--input $(TMPDIR)/jpackage-input \
                --main-jar $(notdir $(DISTJAR)) \
                --main-class $(MAINCLASS) \
                --name $(LAUNCHER) \
                --app-version $(VNUM) \
                --vendor "$(VENDOR)" \
                --description "$(DESCRIPTION)" \
                --icon $(DISTDIR)/linux/weathermap.png \
                --dest $(TMPDIR) \
                --resource-dir $(TMPDIR)/jpackage-res \
                --linux-package-name $(PKGNAME) \
                --linux-app-category science \
                --linux-menu-group "Science;Education;" \
                --linux-shortcut

$(TMPDIR)/jpackage-input/$(notdir $(DISTJAR)): $(DISTJAR) | $(TMPDIR)
	rm -rf $(TMPDIR)/jpackage-input
	mkdir -p $(TMPDIR)/jpackage-input
	cp $(DISTJAR) $(TMPDIR)/jpackage-input/

# Package maintainer scripts that symlink the launcher into /usr/bin so it is on
# the user's PATH, plus a .desktop override so the KDE/GNOME menu entry shows a
# friendly name. We take jpackage's OWN templates (from the packaging JDK) and
# inject our own commands after the desktop-install/uninstall markers, so the
# result stays correct across JDK versions and keeps jpackage's default
# behaviour. The symlink is removed on uninstall only if it still points at our
# launcher.
#
# We also register the app icon in the freedesktop hicolor theme via
# xdg-icon-resource (rather than only leaving jpackage's copy at an absolute
# path under /opt and pointing Icon= straight at it). This is what proper Linux
# packages do, and it matters because
# xdg-icon-resource runs gtk-update-icon-cache, which is the signal desktops
# (notably KDE Plasma) use to invalidate their icon caches. Without it, an
# upgrade over a version that had no icon leaves the desktop's cached "no icon"
# in place, so the menu entry never picks up the new artwork. The .desktop file
# therefore references the icon by NAME ($(LAUNCHER)) so it resolves from the
# theme. jpackage still installs the source PNG at /opt/$(PKGNAME)/lib, which is
# where the postinst reads it from to register it.
ICON_SRC:=/opt/$(PKGNAME)/lib/$(LAUNCHER).png
ICON_INSTALL:=command -v xdg-icon-resource >/dev/null 2>&1 && xdg-icon-resource install --novendor --size 256 "$(ICON_SRC)" "$(LAUNCHER)" || true
ICON_UNINSTALL:=command -v xdg-icon-resource >/dev/null 2>&1 && xdg-icon-resource uninstall --size 256 "$(LAUNCHER)" || true
$(TMPDIR)/jpackage-res: | $(TMPDIR)
	@command -v unzip >/dev/null || { echo "unzip is required to build the Linux package scripts"; exit 1; }
	rm -rf $@ && mkdir -p $@/tpl
	@# a .jmod has a 4-byte magic prefix before the zip, so unzip extracts fine
	@# but exits non-zero with a warning — tolerate it, then verify the files.
	cd $@/tpl && unzip -o -q -j "$(JPACKAGE_JDK)/jmods/jdk.jpackage.jmod" \
	    'classes/jdk/jpackage/internal/resources/template.postinst' \
	    'classes/jdk/jpackage/internal/resources/template.prerm' \
	    'classes/jdk/jpackage/internal/resources/template.spec' >/dev/null 2>&1 || true
	@for f in template.postinst template.prerm template.spec ; do \
	    [ -f $@/tpl/$$f ] || { echo "Failed to extract $$f from jdk.jpackage.jmod"; exit 1; }; done
	sed -e '/DESKTOP_COMMANDS_INSTALL/a ln -sf "$(LINK_SRC)" "$(LINK_DST)"' \
	    -e '/DESKTOP_COMMANDS_INSTALL/a $(ICON_INSTALL)' \
	    $@/tpl/template.postinst > $@/postinst
	sed -e '/DESKTOP_COMMANDS_UNINSTALL/a [ "$$(readlink "$(LINK_DST)" 2>/dev/null)" = "$(LINK_SRC)" ] && rm -f "$(LINK_DST)" || true' \
	    -e '/DESKTOP_COMMANDS_UNINSTALL/a $(ICON_UNINSTALL)' \
	    $@/tpl/template.prerm > $@/prerm
	@# jpackage names the .spec resource after the PACKAGE name (--linux-package-name),
	@# not the launcher/app name.
	sed -e '/DESKTOP_COMMANDS_INSTALL/a ln -sf "$(LINK_SRC)" "$(LINK_DST)"' \
	    -e '/DESKTOP_COMMANDS_INSTALL/a $(ICON_INSTALL)' \
	    -e '/DESKTOP_COMMANDS_UNINSTALL/a [ "$$(readlink "$(LINK_DST)" 2>/dev/null)" = "$(LINK_SRC)" ] && rm -f "$(LINK_DST)" || true' \
	    -e '/DESKTOP_COMMANDS_UNINSTALL/a $(ICON_UNINSTALL)' \
	    $@/tpl/template.spec > $@/$(PKGNAME).spec
	rm -rf $@/tpl
	@# .desktop override, named after the launcher (--name) per jpackage's resource
	@# lookup convention: everything but Name= and Icon= keeps jpackage's own
	@# substitution tokens (APPLICATION_DESCRIPTION/LAUNCHER, DEPLOY_BUNDLE_CATEGORY,
	@# DESKTOP_MIMES) so Exec/Categories/MimeType still fill in per-build. Name= is
	@# fixed to $(APPNAME) instead of $(LAUNCHER); Icon= is the theme icon NAME
	@# ($(LAUNCHER)) that the postinst registers via xdg-icon-resource, rather than
	@# jpackage's APPLICATION_ICON absolute path — see the icon-cache note above.
	printf '%s\n' \
	    '[Desktop Entry]' \
	    'Name=$(APPNAME)' \
	    'Comment=APPLICATION_DESCRIPTION' \
	    'Exec=APPLICATION_LAUNCHER' \
	    'Icon=$(LAUNCHER)' \
	    'Terminal=false' \
	    'Type=Application' \
	    'Categories=DEPLOY_BUNDLE_CATEGORY' \
	    'DESKTOP_MIMES' \
	    > $@/$(LAUNCHER).desktop

release-linux-deb: $(TMPDIR)/jpackage-input/$(notdir $(DISTJAR)) $(TMPDIR)/jpackage-res
	@[ -x "$(JPACKAGE)" ] || { echo "jpackage not found (set JPACKAGE_JDK); see docs/packaging.md"; exit 1; }
	rm -f $(TMPDIR)/$(PKGNAME)_*.deb
	"$(JPACKAGE)" --type deb $(JPACKAGE_COMMON)
	@ls -1 $(TMPDIR)/*.deb

release-linux-rpm: $(TMPDIR)/jpackage-input/$(notdir $(DISTJAR)) $(TMPDIR)/jpackage-res
	@[ -x "$(JPACKAGE)" ] || { echo "jpackage not found (set JPACKAGE_JDK); see docs/packaging.md"; exit 1; }
	@command -v rpmbuild >/dev/null || { echo "rpmbuild not found — install the 'rpm' package (see docs/packaging.md)"; exit 1; }
	rm -f $(TMPDIR)/$(PKGNAME)-*.rpm
	"$(JPACKAGE)" --type rpm $(JPACKAGE_COMMON)
	@ls -1 $(TMPDIR)/*.rpm

release-linux: release-linux-deb release-linux-rpm

# =======================================================================
# Windows — NSIS installer .exe, one per architecture
# =======================================================================
# Each build directory holds the Launch4j-wrapped Weather-Map.exe
# (fat JAR embedded) plus a jlink runtime (jre/) built from that architecture's
# Windows JDK. Those are assembled into a stage/ tree — exactly what lands in
# the target machine's $INSTDIR — from which the install/uninstall manifests
# are generated, and makensis packs the lot into an executable installer
# (dist/windows/nsis/installer.nsi).

NSIS:=makensis

# jlink a Windows runtime for the given arch from its bootstrapped JDK, using a
# host jlink whose version matches that arch's JDK (32-bit = Java 17, else 21).
$(TMPDIR)/windows-%-build/jre: $(MODULES_STAMP) | $(TMPDIR)
	@[ -d $(JDKDIR)/windows-$* ] || { echo "Missing $(JDKDIR)/windows-$* — run 'make bootstrap'"; exit 1; }
	@jlink="$(JLINK_MAIN)"; ver=$(JDK_MAIN_VER); \
	  [ "$*" = "x86_32" ] && { jlink="$(JLINK_WIN32)"; ver=$(JDK_WIN32_VER); }; \
	  [ -n "$$jlink" ] && [ -x "$$jlink" ] || { \
	    echo "No host jlink for Java $$ver (needed to link windows-$*). Run 'make bootstrap'."; exit 1; }; \
	  rm -rf $@; mkdir -p $(TMPDIR)/windows-$*-build; \
	  echo "$$jlink --module-path $(JDKDIR)/windows-$*/jmods --add-modules $(APP_MODULES) --output $@"; \
	  "$$jlink" --module-path $(JDKDIR)/windows-$*/jmods $(JLINK_OPTS) --output $@
	@$(call check_runtime_modules,$@)

# generate the Launch4j config and wrap the JAR into Weather-Map.exe
$(TMPDIR)/windows-%-build/Weather-Map.exe: $(DISTJAR) $(DISTDIR)/windows/launch4j.xml.in $(DISTDIR)/windows/weathermap.ico
	mkdir -p $(TMPDIR)/windows-$*-build
	cp $(DISTJAR) $(TMPDIR)/windows-$*-build/
	sed -e 's|@JAR@|$(CURDIR)/$(TMPDIR)/windows-$*-build/$(notdir $(DISTJAR))|g' \
	    -e 's|@OUTFILE@|$(CURDIR)/$@|g' \
	    -e 's|@OUTNAME@|Weather-Map.exe|g' \
	    -e 's|@ICON@|$(CURDIR)/$(DISTDIR)/windows/weathermap.ico|g' \
	    -e 's|@JREPATH@|jre|g' \
	    -e 's|@NUMVERSION4@|$(NUMVERSION4)|g' \
	    -e 's|@VERSION@|$(VERSION)|g' \
	    $(DISTDIR)/windows/launch4j.xml.in > $(TMPDIR)/windows-$*-build/launch4j.xml
	$(LAUNCH4J) $(CURDIR)/$(TMPDIR)/windows-$*-build/launch4j.xml

# The staging tree: everything under it is installed verbatim into $INSTDIR.
# The fat JAR is embedded in the .exe by Launch4j, so it is not staged
# separately; LICENSE gains a .txt suffix so Windows opens it with a click.
$(TMPDIR)/windows-%-build/stage: \
		$(TMPDIR)/windows-%-build/Weather-Map.exe \
		$(TMPDIR)/windows-%-build/jre \
		CHANGES.md LICENSE README.md
	rm -rf $@
	mkdir -p $@
	cp -a $(TMPDIR)/windows-$*-build/Weather-Map.exe $@/
	cp -a $(TMPDIR)/windows-$*-build/jre $@/jre
	cp -a CHANGES.md README.md $@/
	cp -a LICENSE $@/LICENSE.txt
	find $@ -type f -exec chmod 644 \{\} \+
	find $@ -type d -exec chmod 755 \{\} \+
	chmod 755 $@/Weather-Map.exe

# NSIS manifests generated from the staging tree:
# one SetOutPath/File list to install, and its reversal to uninstall.
$(TMPDIR)/windows-%-build/install_files.inc: $(TMPDIR)/windows-%-build/stage
	for i in `find $< -type d` ; do \
		echo SetOutPath \"\$$INSTDIR\\`echo $$i | \
			sed -e 's|$</\?||' -e 's/\//\\\/g'`\" ; \
		find $$i -maxdepth 1 -type f -printf 'File "%p"\n' ; \
	done >$@

$(TMPDIR)/windows-%-build/uninstall_files.inc: $(TMPDIR)/windows-%-build/install_files.inc
	sed -e 's/^SetOutPath/RMDir/' \
			-e 's|^File "$(TMPDIR)/windows-$(*)-build/stage|Delete "$$INSTDIR|' \
			-e 's/\//\\/g' <$< | \
		tac	>$@

$(TMPDIR)/Weather-Map-$(PKGVERSION)-x86_32.exe: BITS:=32
$(TMPDIR)/Weather-Map-$(PKGVERSION)-x86_64.exe: BITS:=64
$(TMPDIR)/Weather-Map-$(PKGVERSION)-aarch64.exe: BITS:=64

$(TMPDIR)/Weather-Map-$(PKGVERSION)-%.exe: \
		$(TMPDIR)/windows-%-build/stage \
		$(TMPDIR)/windows-%-build/install_files.inc \
		$(TMPDIR)/windows-%-build/uninstall_files.inc \
		$(DISTDIR)/windows/nsis/installer.nsi
	@command -v $(NSIS) >/dev/null || { echo "$(NSIS) not found — install the 'nsis' package (see docs/packaging.md)"; exit 1; }
	$(NSIS) -NOCD -DVERSION=$(VERSION) -DPKGVERSION=$(PKGVERSION) -DNUMVERSION=$(VNUM) -DTMPDIR=$(TMPDIR) \
	        -DARCH=$* -DBITS=$(BITS) $(DISTDIR)/windows/nsis/installer.nsi
	@echo "built $@"

release-windows-x86_64:  $(TMPDIR)/Weather-Map-$(PKGVERSION)-x86_64.exe
release-windows-aarch64: $(TMPDIR)/Weather-Map-$(PKGVERSION)-aarch64.exe
release-windows-x86_32:  $(TMPDIR)/Weather-Map-$(PKGVERSION)-x86_32.exe

release-windows: release-windows-x86_64 release-windows-aarch64 release-windows-x86_32

# =======================================================================
# macOS — .dmg via genisoimage + libdmg-hfsplus, one per architecture
# =======================================================================

# Lay out the disk-image staging dir for the given arch: a "Weather Map.app"
# bundle (with a jlink runtime) plus an /Applications symlink.
# The bundle name contains spaces, so it is only ever created inside the recipe
# — the Make target itself (the space-free "image" directory) must not.
APPDIRNAME:=Weather Map.app

$(TMPDIR)/macos-%-build/image: $(DISTJAR) $(MODULES_STAMP) \
		$(DISTDIR)/macos/Info.plist.in $(DISTDIR)/macos/run.sh.in $(DISTDIR)/macos/PkgInfo \
		$(DISTDIR)/macos/weathermap.icns
	@[ -d $(JDKDIR)/macos-$* ] || { echo "Missing $(JDKDIR)/macos-$* — run 'make bootstrap'"; exit 1; }
	@[ -n "$(JLINK_MAIN)" ] && [ -x "$(JLINK_MAIN)" ] || { \
	    echo "No host jlink for Java $(JDK_MAIN_VER) (needed to link macos-$*). Run 'make bootstrap'."; exit 1; }
	rm -rf "$@"
	mkdir -p "$@/$(APPDIRNAME)/Contents/MacOS" "$@/$(APPDIRNAME)/Contents/Resources/Java"
	sed -e 's|@NUMVERSION@|$(VNUM)|g' $(DISTDIR)/macos/Info.plist.in \
	    > "$@/$(APPDIRNAME)/Contents/Info.plist"
	cp $(DISTDIR)/macos/PkgInfo "$@/$(APPDIRNAME)/Contents/PkgInfo"
	cp $(DISTDIR)/macos/weathermap.icns "$@/$(APPDIRNAME)/Contents/Resources/"
	sed -e 's|@JARFILE@|$(notdir $(DISTJAR))|g' $(DISTDIR)/macos/run.sh.in \
	    > "$@/$(APPDIRNAME)/Contents/MacOS/run.sh"
	chmod 755 "$@/$(APPDIRNAME)/Contents/MacOS/run.sh"
	cp $(DISTJAR) "$@/$(APPDIRNAME)/Contents/Resources/Java/"
	"$(JLINK_MAIN)" --module-path $(JDKDIR)/macos-$*/jmods $(JLINK_OPTS) \
	    --output "$@/$(APPDIRNAME)/Contents/MacOS/jre"
	@$(call check_runtime_modules,$@/$(APPDIRNAME)/Contents/MacOS/jre)
	ln -sf /Applications "$@/Applications"

$(TMPDIR)/Weather-Map-$(PKGVERSION)-%-uncompressed.iso: $(TMPDIR)/macos-%-build/image
	$(GENISOIMAGE) -V "Weather Map" -D -R -apple -no-pad -quiet -o $@ "$<"

$(TMPDIR)/Weather-Map-$(PKGVERSION)-%.dmg: \
		$(TMPDIR)/Weather-Map-$(PKGVERSION)-%-uncompressed.iso
	@[ -x "$(DMG)" ] || { echo "dmg tool missing — run 'make bootstrap'"; exit 1; }
	rm -f $@
	$(DMG) $< $@
	@echo "built $@"

release-macos-x86_64:  $(TMPDIR)/Weather-Map-$(PKGVERSION)-x86_64.dmg
release-macos-aarch64: $(TMPDIR)/Weather-Map-$(PKGVERSION)-aarch64.dmg

release-macos: release-macos-x86_64 release-macos-aarch64

# =======================================================================
# Aggregate / checksums / clean
# =======================================================================

# Always from a clean tree. `mvn package` is incremental and does not remove a
# resource that has been deleted from the source: a note moved out of docs/ was
# still inside the built jar afterwards, and would have shipped in every
# package. Incremental is right for the development loop above and wrong here,
# where the whole point is that what ships matches the tree.
#
# Written as recipe lines rather than prerequisites so the clean is ordered
# before the builds even under make -j.
release:
	$(MAKE) clean
	$(MAKE) release-linux release-windows release-macos

release-sha256: | $(TMPDIR)
	pushd $(TMPDIR) >/dev/null ; \
	  sha256sum *.deb *.rpm *.exe *.dmg 2>/dev/null \
	    > Weather-Map-$(VERSION).sha256 || true ; \
	  popd >/dev/null
	@echo "wrote $(TMPDIR)/Weather-Map-$(VERSION).sha256"
	@cat $(TMPDIR)/Weather-Map-$(VERSION).sha256 2>/dev/null || true

clean-release:
	$(RM) -r $(TMPDIR)

clean: clean-release
	$(MVN) clean

# prevents make from deleting intermediate files (jre/, .app, .iso)
.SECONDARY:

.PHONY: FORCE help build compile test jar run cli netcdf javadoc clean clean-release \
        version version-print version-set version-bump version-docs post-release bootstrap \
        release release-linux release-linux-deb release-linux-rpm \
        release-windows release-windows-x86_64 release-windows-aarch64 release-windows-x86_32 \
        release-macos release-macos-x86_64 release-macos-aarch64 release-sha256
