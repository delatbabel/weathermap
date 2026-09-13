#!/bin/bash
#
# Downloads and builds the third-party tools and per-platform JDKs needed to
# cross-build Windows and macOS packages of the Weather Map on a
# Linux host.  Everything lands under dist/tools and dist/jdks, both of which
# are git-ignored.  Re-running skips anything already present.
#
# Native Linux packages (deb/rpm) do NOT need this script — they use the
# system JDK's jpackage.  This script is only required for:
#   make release-windows-*   (Launch4j + a Windows JDK)
#   make release-macos       (libdmg-hfsplus + a macOS JDK)
#
# Tools installed here (no root required):
#   - Launch4j 3.50                 (wraps the JAR into a Windows .exe)
#   - CMake (portable)              (only to build libdmg-hfsplus)
#   - libdmg-hfsplus 'dmg'          (converts an ISO into a compressed .dmg)
#   - Temurin JDKs per platform     (their jmods feed jlink for bundled runtimes)
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"          # dist/
TOOLDIR="$HERE/tools"
JDKDIR="$HERE/jdks"
mkdir -p "$TOOLDIR" "$JDKDIR"

LAUNCH4J_VER=3.50
CMAKE_VER=3.30.5
# Java feature releases: 21 has Windows x64/aarch64 + macOS x64/aarch64;
# Windows 32-bit is only published up to 17.
JDK_MAIN=21
JDK_WIN32=17

fetch() { # url dest
  [ -f "$2" ] && { echo "  have $(basename "$2")"; return; }
  echo "  downloading $(basename "$2")"
  curl -fsSL -o "$2" "$1"
}

temurin_url() { # feature os arch  ->  binary download URL
  echo "https://api.adoptium.net/v3/binary/latest/$1/ga/$2/$3/jdk/hotspot/normal/eclipse"
}

# --- Launch4j -------------------------------------------------------------
if [ ! -x "$TOOLDIR/launch4j/launch4j.jar" ] && [ ! -f "$TOOLDIR/launch4j/launch4j.jar" ]; then
  echo "Launch4j $LAUNCH4J_VER"
  fetch "https://downloads.sourceforge.net/project/launch4j/launch4j-3/$LAUNCH4J_VER/launch4j-$LAUNCH4J_VER-linux-x64.tgz" \
        "$TOOLDIR/launch4j-$LAUNCH4J_VER.tgz"
  tar xzf "$TOOLDIR/launch4j-$LAUNCH4J_VER.tgz" -C "$TOOLDIR"
fi
echo "Launch4j ready"

# --- CMake (portable, only needed to build libdmg-hfsplus) ----------------
if [ ! -x "$TOOLDIR/cmake/bin/cmake" ]; then
  echo "CMake $CMAKE_VER"
  fetch "https://github.com/Kitware/CMake/releases/download/v$CMAKE_VER/cmake-$CMAKE_VER-linux-x86_64.tar.gz" \
        "$TOOLDIR/cmake.tgz"
  tar xzf "$TOOLDIR/cmake.tgz" -C "$TOOLDIR"
  mv "$TOOLDIR/cmake-$CMAKE_VER-linux-x86_64" "$TOOLDIR/cmake"
fi

# --- libdmg-hfsplus (the 'dmg' tool) --------------------------------------
if [ ! -x "$TOOLDIR/libdmg-hfsplus/build/dmg/dmg" ]; then
  echo "libdmg-hfsplus"
  [ -d "$TOOLDIR/libdmg-hfsplus" ] || \
    git clone --depth 1 https://github.com/fanquake/libdmg-hfsplus.git "$TOOLDIR/libdmg-hfsplus"
  mkdir -p "$TOOLDIR/libdmg-hfsplus/build"
  ( cd "$TOOLDIR/libdmg-hfsplus/build" && "$TOOLDIR/cmake/bin/cmake" .. >/dev/null && make dmg-bin >/dev/null )
fi
echo "dmg tool ready"

# --- Per-platform JDKs (jmods used by jlink) ------------------------------
# Normalises each download to $JDKDIR/<platform> containing a jmods/ directory.
install_jdk() { # platform feature os arch
  local platform=$1 feature=$2 os=$3 arch=$4
  [ -d "$JDKDIR/$platform/jmods" ] && { echo "  have JDK $platform"; return; }
  local ext=tar.gz; [ "$os" = windows ] && ext=zip
  local ar="$JDKDIR/$platform.$ext"
  fetch "$(temurin_url "$feature" "$os" "$arch")" "$ar"
  rm -rf "$JDKDIR/$platform.tmp"; mkdir -p "$JDKDIR/$platform.tmp"
  if [ "$ext" = zip ]; then unzip -q "$ar" -d "$JDKDIR/$platform.tmp"
  else tar xzf "$ar" -C "$JDKDIR/$platform.tmp"; fi
  # locate the dir that contains jmods (mac JDKs nest it under Contents/Home)
  local jmods; jmods="$(find "$JDKDIR/$platform.tmp" -type d -name jmods | head -1)"
  [ -n "$jmods" ] || { echo "ERROR: no jmods in $platform JDK"; exit 1; }
  rm -rf "$JDKDIR/$platform"; mv "$(dirname "$jmods")" "$JDKDIR/$platform"
  rm -rf "$JDKDIR/$platform.tmp"
}

echo "Platform JDKs"
install_jdk windows-x86_64  "$JDK_MAIN"  windows x64
install_jdk windows-aarch64 "$JDK_MAIN"  windows aarch64
install_jdk windows-x86_32  "$JDK_WIN32" windows x86
install_jdk macos-x86_64    "$JDK_MAIN"  mac     x64
install_jdk macos-aarch64   "$JDK_MAIN"  mac     aarch64

# --- Host jlink per Java version ------------------------------------------
# Cross-linking needs a Linux host jlink whose version EXACTLY matches the
# target JDK's jmods (jlink refuses a mismatch). The 32-bit Windows JDK is a
# different Java version (17) than the rest (21), so a matching host jlink must
# exist for each. Download a Linux x64 JDK for any version not already provided
# by a system JDK's jlink.
install_host_jdk() { # version
  local ver=$1 d v
  for d in /usr/lib/jvm/*/ ; do
    if [ -x "${d}bin/jlink" ]; then
      v="$(${d}bin/jlink --version 2>/dev/null | cut -d. -f1)"
      [ "$v" = "$ver" ] && { echo "  system jlink $ver present"; return; }
    fi
  done
  [ -x "$JDKDIR/linux-x86_64-$ver/bin/jlink" ] && { echo "  have host JDK $ver"; return; }
  echo "  fetching Linux host JDK $ver (for jlink)"
  fetch "$(temurin_url "$ver" linux x64)" "$JDKDIR/linux-x86_64-$ver.tar.gz"
  rm -rf "$JDKDIR/linux-x86_64-$ver.tmp"; mkdir -p "$JDKDIR/linux-x86_64-$ver.tmp"
  tar xzf "$JDKDIR/linux-x86_64-$ver.tar.gz" -C "$JDKDIR/linux-x86_64-$ver.tmp"
  local jl; jl="$(find "$JDKDIR/linux-x86_64-$ver.tmp" -type f -name jlink -path '*/bin/jlink' | head -1)"
  [ -n "$jl" ] || { echo "ERROR: no jlink in Linux JDK $ver"; exit 1; }
  rm -rf "$JDKDIR/linux-x86_64-$ver"; mv "$(dirname "$(dirname "$jl")")" "$JDKDIR/linux-x86_64-$ver"
  rm -rf "$JDKDIR/linux-x86_64-$ver.tmp"
}

echo "Host jlinks"
install_host_jdk "$JDK_MAIN"
install_host_jdk "$JDK_WIN32"

echo "Bootstrap complete."
