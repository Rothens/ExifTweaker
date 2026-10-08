#!/usr/bin/env bash
# Builds the native installer for this OS with jpackage (JDK 17+), from the jar that "mvn package" made:
#
#   src/main/packaging/jpackage.sh <version> [type]
#
# type defaults to deb (Linux), dmg (macOS) or msi (Windows, needs the WiX Toolset 3). The installer bundles a
# small Java runtime, so users don't need Java. Output: target/installer/
set -euo pipefail
# jpackage reads its arguments in the system encoding; the vendor name has accents
export LC_ALL=C.UTF-8 LANG=C.UTF-8

version="${1:?usage: jpackage.sh <version> [type]}"
type="${2:-}"
cd "$(dirname "$0")/../../.."
here=src/main/packaging
jar="target/exiftweaker-${version}-all.jar"
[ -f "$jar" ] || { echo "$jar not found - run: mvn -Drevision=$version package" >&2; exit 1; }

# Installers need a plain numeric version (1.0.0-SNAPSHOT -> 1.0.0)
app_version="${version%%-*}"

input=target/jpackage-input
rm -rf "$input" target/installer
mkdir -p "$input"
cp "$jar" "$input/"

# What the app needs (jdeps --print-module-deps), plus TLS for map tiles, extra charsets and screen readers
modules=java.base,java.desktop,java.management,java.net.http,java.prefs,java.sql,jdk.crypto.ec,jdk.charsets,jdk.accessibility

common=(
  --name ExifTweaker
  --app-version "$app_version"
  --vendor "Máté Dávid"
  --description "Sets the GPS position and other metadata of photos, on a map"
  --copyright "Copyright (c) 2017-2026 Máté Dávid. MIT License."
  --about-url https://github.com/rothens/ExifTweaker
  --license-file LICENSE
  --input "$input"
  --main-jar "$(basename "$jar")"
  --main-class me.rothens.gpsexif.ExifTweaker
  --add-modules "$modules"
  --jlink-options "--strip-debug --no-man-pages --no-header-files"
  --dest target/installer
)

case "$(uname -s)" in
  Linux)
    platform=(--type "${type:-deb}" --icon "$here/ExifTweaker.png"
      --linux-package-name exiftweaker --linux-shortcut
      --linux-menu-group Graphics --linux-app-category graphics)
    ;;
  Darwin)
    platform=(--type "${type:-dmg}" --icon "$here/ExifTweaker.icns"
      --mac-package-identifier me.rothens.exiftweaker --mac-package-name ExifTweaker)
    ;;
  MINGW* | MSYS* | CYGWIN*)
    # Installs for all users (Program Files, asks for administrator rights once); the fixed upgrade UUID lets a
    # new version replace the old one
    platform=(--type "${type:-msi}" --icon "$here/ExifTweaker.ico"
      --win-dir-chooser --win-menu --win-menu-group ExifTweaker --win-shortcut-prompt
      --win-upgrade-uuid 71bb0ac6-5b81-45a3-9fb9-7fef13b1aa6e)
    ;;
  *)
    echo "Unsupported OS: $(uname -s)" >&2
    exit 1
    ;;
esac

jpackage "${common[@]}" "${platform[@]}"

# Say which platform each file is for (the .deb name already does)
cd target/installer
case "$(uname -s)" in
  Darwin) for f in *.dmg *.pkg; do [ -e "$f" ] && mv "$f" "${f%.*}-macos-$(uname -m).${f##*.}"; done ;;
  MINGW* | MSYS* | CYGWIN*) for f in *.msi *.exe; do [ -e "$f" ] && mv "$f" "${f%.*}-windows-x64.${f##*.}"; done ;;
esac
ls -l
