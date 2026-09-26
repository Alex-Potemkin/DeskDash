"""Builds the one-file desktop app into the project root.

Windows -> DeskDash.exe, Linux -> DeskDash-linux-x86_64 (build each on its own OS).

    python -m pip install pyinstaller pystray pillow
    python build_exe.py
"""
import os
import platform
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BUILD = os.path.join(HERE, "build")
IS_WIN = os.name == "nt"

sys.path.insert(0, HERE)
from deskdash_app import make_icon  # noqa: E402

os.makedirs(BUILD, exist_ok=True)
args = [sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--onefile", "--windowed",
        "--name", "DeskDash",
        "--distpath", os.path.join(BUILD, "dist"), "--workpath", os.path.join(BUILD, "work"),
        "--specpath", BUILD]
if IS_WIN:
    ico = os.path.join(BUILD, "deskdash.ico")
    make_icon(256).save(ico, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (256, 256)])
    args += ["--icon", ico, "--hidden-import", "pystray._win32", "--hidden-import", "sys_windows"]
    target = os.path.join(ROOT, "DeskDash.exe")
else:
    args += ["--hidden-import", "pystray._xorg", "--hidden-import", "pystray._appindicator",
             "--hidden-import", "pystray._gtk", "--hidden-import", "sys_linux"]
    target = os.path.join(ROOT, f"DeskDash-linux-{platform.machine()}")

subprocess.run(args + [os.path.join(HERE, "deskdash_app.py")], check=True, cwd=HERE)
built = os.path.join(BUILD, "dist", "DeskDash.exe" if IS_WIN else "DeskDash")
shutil.copy2(built, target)
print("->", target)
