#!/usr/bin/env bash
# DeskDash for Linux: installs the app for the current user and adds it to the applications menu.
#
#   ./linux/install.sh              # from the source checkout (Python venv)
#   ./linux/install.sh ./DeskDash-linux-x86_64   # or install a downloaded release binary
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
SHARE="${XDG_DATA_HOME:-$HOME/.local/share}/deskdash"
BIN="$HOME/.local/bin"
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$SHARE" "$BIN" "$APPS"

echo "== system tools (volume/media keys, key presses, Tk) =="
PKGS_APT="python3-tk python3-venv xdotool playerctl"
PKGS_DNF="python3-tkinter xdotool playerctl"
PKGS_PAC="tk xdotool playerctl"
if command -v apt-get >/dev/null; then sudo apt-get install -y $PKGS_APT
elif command -v dnf >/dev/null; then sudo dnf install -y $PKGS_DNF
elif command -v pacman >/dev/null; then sudo pacman -S --needed --noconfirm $PKGS_PAC
else echo "install by hand: Tk for Python, xdotool (X11) or wtype/ydotool (Wayland), playerctl"
fi

if [ "${1:-}" != "" ]; then
  echo "== binary: $1 =="
  install -m 755 "$1" "$SHARE/DeskDash"
  EXEC="$SHARE/DeskDash"
else
  echo "== from source (venv in $SHARE/venv) =="
  python3 -m venv "$SHARE/venv"
  "$SHARE/venv/bin/pip" install --quiet --upgrade pip pystray pillow
  mkdir -p "$SHARE/app"
  cp "$HERE"/pc-agent/{deskdash_app.py,deskdash_agent.py,spotify.py,sys_linux.py,sys_windows.py} "$SHARE/app/"
  EXEC="$SHARE/venv/bin/python $SHARE/app/deskdash_app.py"
fi

printf '#!/bin/sh\nexec %s "$@"\n' "$EXEC" > "$BIN/deskdash"
chmod +x "$BIN/deskdash"

# icon: same clock mark as on Windows/Android
PY="$SHARE/venv/bin/python"; [ -x "$PY" ] || PY=python3
"$PY" - "$SHARE/deskdash.png" <<'PYEOF' || true
import sys
from PIL import Image, ImageDraw
s = 256
img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle([0, 0, s - 1, s - 1], radius=56, fill="#141110")
d.ellipse([51, 51, 205, 205], outline="#EDE6DD", width=18)
d.line([(128, 82), (128, 128), (161, 154)], fill="#C8A27C", width=18, joint="curve")
img.save(sys.argv[1])
PYEOF

cat > "$APPS/deskdash.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=DeskDash
Comment=Phone dashboard companion: macros, Spotify, stats
Exec=$BIN/deskdash
Icon=$SHARE/deskdash.png
Categories=Utility;
StartupWMClass=DeskDash
EOF

echo
echo "Готово. DeskDash есть в меню приложений, из терминала: deskdash"
case ":$PATH:" in *":$BIN:"*) ;; *) echo "(добавь $BIN в PATH, чтобы команда deskdash находилась)";; esac
