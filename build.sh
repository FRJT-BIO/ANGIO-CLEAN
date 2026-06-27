#!/usr/bin/env bash
#
# build.sh — Build Angio-CLEAN_.jar with nothing but a JDK.
#
# It fetches the ImageJ1 source from GitHub, compiles it into a local ij.jar
# (ImageJ1 has NO external dependencies), then compiles and packages the plugin.
# This is the no-Maven path and is fully self-contained and version-independent.
#
# Usage:   ./build.sh
# Output:  ./Angio-CLEAN_.jar   and   ./ij.jar (the ImageJ library, for tests)
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
WORK="$ROOT/.build"
rm -rf "$WORK"; mkdir -p "$WORK"

echo ">> [1/3] Fetching ImageJ source and building ij.jar ..."
git clone --depth 1 https://github.com/imagej/ImageJ.git "$WORK/imagej"
cd "$WORK/imagej"
find ij -name '*.java' > "$WORK/ij-sources.txt"
mkdir -p "$WORK/ij-build"
javac -d "$WORK/ij-build" -encoding utf-8 -nowarn @"$WORK/ij-sources.txt"
cp IJ_Props.txt "$WORK/ij-build/" 2>/dev/null || true
cp images/microscope.gif "$WORK/ij-build/" 2>/dev/null || true
cp images/about.jpg "$WORK/ij-build/" 2>/dev/null || true
( cd "$WORK/ij-build" && jar cf "$ROOT/ij.jar" . )
echo "   ij.jar built."

echo ">> [2/3] Compiling the plugin ..."
mkdir -p "$WORK/classes"
javac --release 8 -cp "$ROOT/ij.jar" -d "$WORK/classes" "$ROOT/src/main/java/Angio_CLEAN.java"
cp "$ROOT/src/main/resources/plugins.config" "$WORK/classes/"

echo ">> [3/3] Packaging Angio-CLEAN_.jar ..."
printf 'Manifest-Version: 1.0\nImplementation-Title: Angio-CLEAN\nImplementation-Version: 3.0\n\n' > "$WORK/MANIFEST.MF"
( cd "$WORK/classes" && jar cfm "$ROOT/Angio-CLEAN_.jar" "$WORK/MANIFEST.MF" . )

echo ""
echo "DONE -> $ROOT/Angio-CLEAN_.jar"
echo "Install: copy Angio-CLEAN_.jar into your ImageJ/Fiji 'plugins' folder and restart."
