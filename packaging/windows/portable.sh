#!/usr/bin/env bash
# Cross-builds a portable windows x64 build of tmpl from linux (no windows machine needed):
#   - the fat jar built with -Ptmpl.target=windows-x64 (skia + ffmpeg windows natives)
#   - a windows java runtime made with jlink from a windows JDK's jmods
#   - the jpackage GUI launcher (tmpl.exe) taken from that same JDK
#
#   packaging/windows/portable.sh <windows-jdk-dir> <linux-jdk-dir-with-same-version> <version> <out-dir>
#
# Both JDKs must be the same release (e.g. temurin 21.0.8+9 for windows and for linux), because
# jlink only links jmods of its own version. Real installers (.msi/.exe) are built on windows by
# .github/workflows/release.yml.
set -euo pipefail

WIN_JDK=$(realpath "$1")
LINUX_JDK=$(realpath "$2")
VERSION=$3
OUT=$(mkdir -p "$4" && realpath "$4")
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
JAR="$ROOT/desktop/build/libs/tmpl-windows-x64-$VERSION-all.jar"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

[ -f "$JAR" ] || { echo "missing $JAR: run ./gradlew :desktop:fatJar -Ptmpl.target=windows-x64" >&2; exit 1; }

APP="$WORK/tmpl"
mkdir -p "$APP/app"

MODULES=java.desktop,java.logging,java.naming,java.instrument,java.management,java.sql,jdk.security.auth,jdk.unsupported,jdk.crypto.ec,jdk.crypto.mscapi,jdk.accessibility,jdk.charsets,jdk.zipfs
"$LINUX_JDK/bin/jlink" \
  --module-path "$WIN_JDK/jmods" \
  --add-modules "$MODULES" \
  --strip-debug --no-header-files --no-man-pages \
  --output "$APP/runtime"

# jpackage's windowed launcher: reads app\tmpl.cfg, loads runtime\bin\server\jvm.dll
# (a .jmod is a zip with a 4 byte header: unzip warns, exits 1, and extracts fine)
unzip -p "$WIN_JDK/jmods/jdk.jpackage.jmod" classes/jdk/jpackage/internal/resources/jpackageapplauncherw.exe \
  > "$APP/tmpl.exe" 2>/dev/null || true
[ -s "$APP/tmpl.exe" ] || { echo "could not extract the jpackage launcher" >&2; exit 1; }

cp "$JAR" "$APP/app/tmpl.jar"
cp "$ROOT/desktop/icons/tmpl.ico" "$APP/tmpl.ico"
cat > "$APP/app/tmpl.cfg" <<CFG
[Application]
app.classpath=\$APPDIR\\tmpl.jar
app.mainclass=dev.jyotiraditya.dmt.MainKt

[JavaOptions]
java-options=-Djpackage.app-version=$VERSION
java-options=-Xss2m
java-options=--enable-native-access=ALL-UNNAMED
CFG
# windows wants CRLF in the cfg
sed -i 's/$/\r/' "$APP/app/tmpl.cfg"

cat > "$APP/LEIA-ME.txt" <<'TXT'
tmpl - player de musica (windows x64, versao portatil)
desenvolvido por ivnls, baseado no dmt de jyotiraditya panda

1. extraia a pasta inteira em qualquer lugar (ex.: C:\Programas\tmpl)
2. abra tmpl.exe
3. na primeira execucao escolha a pasta de musicas (padrao: sua pasta Musicas)

configuracoes: %APPDATA%\tmpl     cache: %LOCALAPPDATA%\tmpl\cache
para remover, apague a pasta e essas duas pastas.

tmpl - music player (windows x64, portable build), by ivnls, based on dmt by jyotiraditya panda
extract the whole folder anywhere and run tmpl.exe.
TXT
sed -i 's/$/\r/' "$APP/LEIA-ME.txt"

rm -f "$OUT/tmpl-$VERSION-windows-x64-portable.zip"
(cd "$WORK" && zip -qr -9 "$OUT/tmpl-$VERSION-windows-x64-portable.zip" tmpl)
echo "zip: $OUT/tmpl-$VERSION-windows-x64-portable.zip"
