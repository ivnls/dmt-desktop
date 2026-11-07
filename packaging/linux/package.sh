#!/usr/bin/env bash
# Builds dmt .deb (debian / ubuntu), .rpm (fedora / opensuse) and a portable .tar.gz from the
# compose app image (./gradlew :desktop:createReleaseDistributable).
#
#   packaging/linux/package.sh <app-image-dir> <version> <out-dir> [arch]
#
# Needs dpkg-deb for the deb and rpmbuild for the rpm; a format whose tool is missing is skipped.
set -euo pipefail

APP_DIR=$(realpath "$1")
VERSION=$2
OUT=$(mkdir -p "$3" && realpath "$3")
ARCH=${4:-x86_64}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

case "$ARCH" in
  x86_64) DEB_ARCH=amd64 ;;
  aarch64) DEB_ARCH=arm64 ;;
  *) echo "unknown arch $ARCH" >&2; exit 1 ;;
esac

[ -x "$APP_DIR/bin/dmt" ] || { echo "no app image at $APP_DIR (bin/dmt missing)" >&2; exit 1; }

# common file system layout: app in /opt/dmt, launcher on PATH, desktop entry + icons + metainfo
stage() {
  local dest=$1
  mkdir -p "$dest/opt" "$dest/usr/bin" "$dest/usr/share/applications" "$dest/usr/share/metainfo" \
    "$dest/usr/share/icons/hicolor/512x512/apps" "$dest/usr/share/icons/hicolor/256x256/apps" \
    "$dest/usr/share/doc/dmt"
  cp -a "$APP_DIR" "$dest/opt/dmt"
  ln -sf /opt/dmt/bin/dmt "$dest/usr/bin/dmt"
  install -m644 "$HERE/dmt.desktop" "$dest/usr/share/applications/dmt.desktop"
  install -m644 "$HERE/dev.jyotiraditya.dmt.metainfo.xml" "$dest/usr/share/metainfo/dev.jyotiraditya.dmt.metainfo.xml"
  install -m644 "$ROOT/desktop/icons/dmt.png" "$dest/usr/share/icons/hicolor/512x512/apps/dmt.png"
  install -m644 "$ROOT/desktop/src/main/composeResources/drawable/dmt.png" "$dest/usr/share/icons/hicolor/256x256/apps/dmt.png"
  install -m644 "$ROOT/LICENSE" "$dest/usr/share/doc/dmt/copyright"
}

# ---------- deb ----------
if command -v dpkg-deb >/dev/null; then
  D="$WORK/deb"
  stage "$D"
  mkdir -p "$D/DEBIAN"
  SIZE=$(du -sk "$D" --exclude=DEBIAN | cut -f1)
  cat > "$D/DEBIAN/control" <<CONTROL
Package: dmt
Version: $VERSION
Section: sound
Priority: optional
Architecture: $DEB_ARCH
Maintainer: dmt desktop port <dmt@localhost>
Installed-Size: $SIZE
Depends: libasound2t64 | libasound2, libc6, libfontconfig1, libfreetype6, libx11-6, libxext6, libxi6, libxrender1, libxtst6, libgl1, zlib1g, xdg-utils
Recommends: pipewire-alsa | pulseaudio | alsa-utils, zenity | kdialog
Homepage: https://github.com/imjyotiraditya/dmt
Description: dear music, thanks. a tui-inspired music player
 Terminal look, amber on black, cover art as ascii. Plays local files or
 your own Jellyfin, bundled ffmpeg decoders, synced lyrics, cue sheets.
CONTROL
  cat > "$D/DEBIAN/postinst" <<'POST'
#!/bin/sh
set -e
command -v update-desktop-database >/dev/null && update-desktop-database -q /usr/share/applications || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
exit 0
POST
  cp "$D/DEBIAN/postinst" "$D/DEBIAN/postrm"
  chmod 755 "$D/DEBIAN/postinst" "$D/DEBIAN/postrm"
  if command -v fakeroot >/dev/null; then FR=fakeroot; else FR=; fi
  $FR dpkg-deb --build -Zxz "$D" "$OUT/dmt_${VERSION}_${DEB_ARCH}.deb" >/dev/null
  echo "deb: $OUT/dmt_${VERSION}_${DEB_ARCH}.deb"
else
  echo "dpkg-deb not found, skipping deb" >&2
fi

# ---------- rpm ----------
# Only /opt/dmt is owned by the rpm: the jpackage launcher finds its config through the package's
# file list and gets lost when the package also owns files elsewhere. The launcher symlink, menu
# entry, icons and metainfo are put in place by the scriptlets instead.
if command -v rpmbuild >/dev/null; then
  R="$WORK/rpm"
  mkdir -p "$R"/{BUILD,RPMS,SOURCES,SPECS,SRPMS} "$R/root/opt"
  cp -a "$APP_DIR" "$R/root/opt/dmt"
  mkdir -p "$R/root/opt/dmt/share"
  install -m644 "$HERE/dmt.desktop" "$R/root/opt/dmt/share/dmt.desktop"
  install -m644 "$HERE/dev.jyotiraditya.dmt.metainfo.xml" "$R/root/opt/dmt/share/dev.jyotiraditya.dmt.metainfo.xml"
  install -m644 "$ROOT/desktop/icons/dmt.png" "$R/root/opt/dmt/share/dmt-512.png"
  install -m644 "$ROOT/desktop/src/main/composeResources/drawable/dmt.png" "$R/root/opt/dmt/share/dmt-256.png"
  install -m644 "$ROOT/LICENSE" "$R/root/opt/dmt/share/LICENSE"
  cat > "$R/SPECS/dmt.spec" <<SPEC
Name:           dmt
Version:        $(echo "$VERSION" | tr '-' '~')
Release:        1
Summary:        dear music, thanks. a tui-inspired music player
License:        GPL-3.0-or-later
URL:            https://github.com/imjyotiraditya/dmt
BuildArch:      $ARCH
AutoReqProv:    no
Requires:       alsa-lib, libX11, libXext, libXi, libXrender, libXtst, freetype, fontconfig, libglvnd-glx, xdg-utils
Recommends:     pipewire-alsa
Recommends:     zenity

%description
Terminal look, amber on black, cover art as ascii. Plays local files or your own
Jellyfin, bundled ffmpeg decoders, synced lyrics, cue sheets.

%define __strip /bin/true
%define __brp_strip /bin/true
%define __brp_strip_static_archive /bin/true
%define __brp_strip_comment_note /bin/true
%define __brp_mangle_shebangs /bin/true
%define __brp_check_rpaths /bin/true
%define _build_id_links none
%global debug_package %{nil}

%install
cp -a $R/root/. %{buildroot}/

%post
ln -sf /opt/dmt/bin/dmt /usr/bin/dmt
install -Dm644 /opt/dmt/share/dmt.desktop /usr/share/applications/dmt.desktop
install -Dm644 /opt/dmt/share/dev.jyotiraditya.dmt.metainfo.xml /usr/share/metainfo/dev.jyotiraditya.dmt.metainfo.xml
install -Dm644 /opt/dmt/share/dmt-512.png /usr/share/icons/hicolor/512x512/apps/dmt.png
install -Dm644 /opt/dmt/share/dmt-256.png /usr/share/icons/hicolor/256x256/apps/dmt.png
update-desktop-database -q /usr/share/applications >/dev/null 2>&1 || :
gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor >/dev/null 2>&1 || :

%postun
if [ "\$1" = 0 ]; then
  rm -f /usr/bin/dmt /usr/share/applications/dmt.desktop /usr/share/metainfo/dev.jyotiraditya.dmt.metainfo.xml \\
    /usr/share/icons/hicolor/512x512/apps/dmt.png /usr/share/icons/hicolor/256x256/apps/dmt.png
  update-desktop-database -q /usr/share/applications >/dev/null 2>&1 || :
  gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor >/dev/null 2>&1 || :
fi

%files
%license /opt/dmt/share/LICENSE
/opt/dmt
SPEC
  rpmbuild --quiet -bb --define "_topdir $R" --define "_rpmdir $OUT" \
    --define "_build_name_fmt %%{NAME}-%%{VERSION}-%%{RELEASE}.%%{ARCH}.rpm" "$R/SPECS/dmt.spec"
  echo "rpm: $(ls "$OUT"/dmt-*.rpm)"
else
  echo "rpmbuild not found, skipping rpm" >&2
fi

# ---------- tar.gz (any distro) ----------
T="$WORK/tar/dmt-$VERSION-linux-$ARCH"
mkdir -p "$T"
cp -a "$APP_DIR/." "$T/"
install -m644 "$HERE/dmt.desktop" "$T/dmt.desktop"
install -m644 "$ROOT/desktop/icons/dmt.png" "$T/dmt.png"
cat > "$T/install.sh" <<'INST'
#!/bin/sh
# per-user install: ~/.local/share/dmt + launcher, menu entry and icon
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
DEST=${XDG_DATA_HOME:-$HOME/.local/share}
mkdir -p "$DEST/dmt" "$HOME/.local/bin" "$DEST/applications" "$DEST/icons/hicolor/512x512/apps"
cp -a "$HERE/bin" "$HERE/lib" "$DEST/dmt/"
ln -sf "$DEST/dmt/bin/dmt" "$HOME/.local/bin/dmt"
sed "s|^Exec=dmt|Exec=$DEST/dmt/bin/dmt|" "$HERE/dmt.desktop" > "$DEST/applications/dmt.desktop"
cp "$HERE/dmt.png" "$DEST/icons/hicolor/512x512/apps/dmt.png"
update-desktop-database -q "$DEST/applications" 2>/dev/null || true
echo "installed. run: dmt (make sure ~/.local/bin is on your PATH)"
INST
chmod 755 "$T/install.sh"
tar -C "$WORK/tar" -czf "$OUT/dmt-$VERSION-linux-$ARCH.tar.gz" "dmt-$VERSION-linux-$ARCH"
echo "tar: $OUT/dmt-$VERSION-linux-$ARCH.tar.gz"
