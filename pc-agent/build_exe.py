"""Builds DeskDash.exe (one file, no console) into the project root.

    python -m pip install pyinstaller pystray pillow
    python build_exe.py
"""
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BUILD = os.path.join(HERE, "build")

sys.path.insert(0, HERE)
from deskdash_app import make_icon  # noqa: E402

os.makedirs(BUILD, exist_ok=True)
ico = os.path.join(BUILD, "deskdash.ico")
make_icon(256).save(ico, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (256, 256)])

subprocess.run([
    sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--onefile", "--windowed",
    "--name", "DeskDash", "--icon", ico,
    "--distpath", os.path.join(BUILD, "dist"), "--workpath", os.path.join(BUILD, "work"),
    "--specpath", BUILD,
    "--hidden-import", "pystray._win32",
    os.path.join(HERE, "deskdash_app.py"),
], check=True, cwd=HERE)

shutil.copy2(os.path.join(BUILD, "dist", "DeskDash.exe"), os.path.join(ROOT, "DeskDash.exe"))
print("->", os.path.join(ROOT, "DeskDash.exe"))
