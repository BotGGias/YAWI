#!/usr/bin/env bash
# creates the jpackage-Icons of Plattform from src/main/resources/img/icon.png :
#   src/main/deploy/icons/your-id.png   Linux   (jpackage --icon)
#   src/main/deploy/icons/your-id.ico   Windows (256/128/64/48/32/16 px, ImageMagick)
#   src/main/deploy/icons/your-id.icns  macOS   (ICNS-Container with PNG-ENTRIES, python3;
#                                                     ImageMagick can only read ICNS )
set -euo pipefail
cd "$(dirname "$0")/../.."
src=src/main/resources/img/icon.png
out=src/main/deploy/icons
command -v magick >/dev/null || { echo "make-icons.sh: ImageMagick (magick) fehlt" >&2; exit 1; }
command -v python3 >/dev/null || { echo "make-icons.sh: python3 fehlt" >&2; exit 1; }
mkdir -p "$out"
cp "$src" "$out/yawi-installer.png"
chmod 644 "$out/yawi-installer.png"
magick "$src" -define icon:auto-resize=256,128,64,48,32,16 "$out/yawi-installer.ico"

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
for px in 256 128 64 32; do magick "$src" -resize "${px}x${px}" "$tmp/$px.png"; done
python3 - "$tmp" "$out/yawi-installer.icns" <<'PY'
import struct, sys, pathlib
tmp, target = pathlib.Path(sys.argv[1]), sys.argv[2]
# OSType je Groesse (PNG-Eintraege): ic08 256, ic13 128@2x=256, ic07 128, ic12 32@2x=64, ic11 16@2x=32
entries = [(b"ic08", 256), (b"ic13", 256), (b"ic07", 128), (b"ic12", 64), (b"ic11", 32)]
body = b""
for ostype, px in entries:
    png = (tmp / f"{px}.png").read_bytes()
    body += ostype + struct.pack(">I", 8 + len(png)) + png
pathlib.Path(target).write_bytes(b"icns" + struct.pack(">I", 8 + len(body)) + body)
PY
ls -l "$out"
