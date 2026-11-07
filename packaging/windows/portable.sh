#!/usr/bin/env bash
# Cross-builds a portable windows x64 build of dmt from linux (no windows machine needed):
#   - the fat jar built with -Pdmt.target=windows-x64 (skia + ffmpeg windows natives)
#   - a windows java runtime made with jlink from a windows JDK's jmods
#   - the jpackage GUI launcher (dmt.exe) taken from that same JDK
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
JAR="$ROOT/desktop/build/libs/dmt-windows-x64-$VERSION-all.jar"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

[ -f "$JAR" ] || { echo "missing $JAR: run ./gradlew :desktop:fatJar -Pdmt.target=windows-x64" >&2; exit 1; }

APP="$WORK/dmt"
mkdir -p "$APP/app"

MODULES=java.desktop,java.logging,java.naming,java.instrument,java.management,java.sql,jdk.security.auth,jdk.unsupported,jdk.crypto.ec,jdk.crypto.mscapi,jdk.accessibility,jdk.charsets,jdk.zipfs
"$LINUX_JDK/bin/jlink" \
  --module-path "$WIN_JDK/jmods" \
  --add-modules "$MODULES" \
  --strip-debug --no-header-files --no-man-pages \
  --output "$APP/runtime"

# jpackage's windowed launcher: reads app\dmt.cfg, loads runtime\bin\server\jvm.dll
# (a .jmod is a zip with a 4 byte header: unzip warns, exits 1, and extracts fine)
unzip -p "$WIN_JDK/jmods/jdk.jpackage.jmod" classes/jdk/jpackage/internal/resources/jpackageapplauncherw.exe \
  > "$APP/dmt.exe" 2>/dev/null || true
[ -s "$APP/dmt.exe" ] || { echo "could not extract the jpackage launcher" >&2; exit 1; }

cp "$JAR" "$APP/app/dmt.jar"
cp "$ROOT/desktop/icons/dmt.ico" "$APP/dmt.ico"
cat > "$APP/app/dmt.cfg" <<CFG
[Application]
app.classpath=\$APPDIR\\dmt.jar
app.mainclass=dev.jyotiraditya.dmt.MainKt

[JavaOptions]
java-options=-Djpackage.app-version=$VERSION
java-options=-Xss2m
java-options=--enable-native-access=ALL-UNNAMED
CFG
# windows wants CRLF in the cfg
sed -i 's/$/\r/' "$APP/app/dmt.cfg"

cat > "$APP/LEIA-ME.txt" <<'TXT'
dmt - dear music, thanks. (windows x64, versao portatil)

1. extraia a pasta inteira em qualquer lugar (ex.: C:\Programas\dmt)
2. abra dmt.exe
3. na primeira execucao escolha a pasta de musicas (padrao: sua pasta Musicas)

configuracoes: %APPDATA%\dmt     cache: %LOCALAPPDATA%\dmt\cache
para remover, apague a pasta e essas duas pastas.

dmt - dear music, thanks. (windows x64, portable build)
extract the whole folder anywhere and run dmt.exe.
TXT
sed -i 's/$/\r/' "$APP/LEIA-ME.txt"

rm -f "$OUT/dmt-$VERSION-windows-x64-portable.zip"
(cd "$WORK" && zip -qr -9 "$OUT/dmt-$VERSION-windows-x64-portable.zip" dmt)
echo "zip: $OUT/dmt-$VERSION-windows-x64-portable.zip"
