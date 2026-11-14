#!/usr/bin/env bash
# Wraps the Rust Tether binary into a .deb.   scripts/build_deb.sh <tether binary> <version> <out dir>
set -euo pipefail
bin=$1 version=$2 out=$3
here=$(cd "$(dirname "$0")/.." && pwd)
id=io.github.quezka.Tether
arch=$(dpkg --print-architecture)
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
install -Dm755 "$bin" "$stage/usr/bin/tether"
install -Dm644 "$here/packaging/$id.desktop" "$stage/usr/share/applications/$id.desktop"
install -Dm644 "$here/packaging/$id.metainfo.xml" "$stage/usr/share/metainfo/$id.metainfo.xml"
install -Dm644 "$here/packaging/icon.svg" "$stage/usr/share/icons/hicolor/scalable/apps/$id.svg"
mkdir -p "$stage/usr/share/doc/quezka-tether"
cat > "$stage/usr/share/doc/quezka-tether/copyright" <<COPY
Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/
Upstream-Name: Tether
Source: https://github.com/Quezka/tether

Files: *
Copyright: $(date +%Y) Quezka <arsdom15@gmail.com>
License: GPL-3.0-only
 On Debian systems, the full text of the GNU General Public License version 3
 can be found in /usr/share/common-licenses/GPL-3.
COPY
size=$(du -sk "$stage/usr" | cut -f1)
mkdir -p "$stage/DEBIAN"
cat > "$stage/DEBIAN/control" <<CONTROL
Package: quezka-tether
Version: $version
Architecture: $arch
Maintainer: Quezka <arsdom15@gmail.com>
Installed-Size: $size
Depends: libc6, libnotify-bin, libxkbcommon0, libwayland-client0, libvulkan1 | libegl1
Recommends: wl-clipboard, zenity
Section: utils
Priority: optional
Homepage: https://github.com/Quezka/tether
Description: Your phone on your computer
 Tether shows the Android phone's notifications, text messages and calls on
 the computer, sends files both ways and shares the clipboard, over the home
 network, end-to-end encrypted.
CONTROL
mkdir -p "$out"
dpkg-deb --root-owner-group -Zxz --build "$stage" "$out/quezka-tether_${version}_${arch}.deb"
