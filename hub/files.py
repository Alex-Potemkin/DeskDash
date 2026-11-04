"""The NAS disks in the hub's web app: browse, download, upload, from anywhere the hub opens.

Paths are "<disk>/<folder>/<file>" with the disk's folder name first; "" is the list of disks. Everything
stays inside the disks: a path that leads out of one (.. or a symlink) is refused.
"""
import os
import shutil

import machines

# Shown in the browser; everything else is only downloaded, so an HTML file on a disk can't run as the hub's own page
INLINE = ("image/jpeg", "image/png", "image/gif", "image/webp", "application/pdf", "text/plain")


def disks(paths):
    return {os.path.basename(p.rstrip("/")): p for p in machines.nas_paths(paths) if p != "/"}


def resolve(paths, rel):
    """The real path of a NAS path, or None for the list of disks. ValueError when it isn't on a disk."""
    parts = [p for p in rel.replace("\\", "/").split("/") if p]
    if not parts:
        return None
    if ".." in parts or "\0" in rel:
        raise ValueError("неверный путь")
    base = disks(paths).get(parts[0])
    if base is None:
        raise ValueError("диск не подключён")
    root = os.path.realpath(base)
    full = os.path.realpath(os.path.join(root, *parts[1:]))
    if full != root and not full.startswith(root + os.sep):
        raise ValueError("неверный путь")
    return full


def name_ok(name):
    name = (name or "").strip()
    if not name or name in (".", "..") or any(c in name for c in "/\\\0"):
        raise ValueError("неверное имя")
    return name


def listing(paths, rel):
    full = resolve(paths, rel)
    if full is None:
        out = []
        for name, p in sorted(disks(paths).items()):
            u = shutil.disk_usage(p)
            out.append({"name": name, "dir": True, "disk": True, "size": u.used, "total": u.total, "free": u.free})
        return out
    if not os.path.isdir(full):
        raise ValueError("нет такой папки")
    out = []
    with os.scandir(full) as it:
        for e in it:
            if e.name.startswith(".") or e.name in ("System Volume Information", "$RECYCLE.BIN"):
                continue
            try:
                st = e.stat()
                d = e.is_dir()
            except OSError:
                continue
            out.append({"name": e.name, "dir": d, "size": 0 if d else st.st_size, "mtime": int(st.st_mtime)})
    out.sort(key=lambda x: (not x["dir"], x["name"].lower()))
    return out


def mkdir(paths, rel, name):
    full = resolve(paths, rel)
    if full is None:
        raise ValueError("сначала открой диск")
    os.mkdir(os.path.join(full, name_ok(name)))


def rename(paths, rel, name):
    full = resolve(paths, rel)
    if full is None or "/" not in rel.strip("/"):
        raise ValueError("диск переименовать нельзя")
    to = os.path.join(os.path.dirname(full), name_ok(name))
    if os.path.exists(to):
        raise ValueError("такое имя уже есть")
    os.rename(full, to)


def delete(paths, rel):
    full = resolve(paths, rel)
    if full is None or "/" not in rel.strip("/"):
        raise ValueError("диск удалить нельзя")
    if os.path.isdir(full) and not os.path.islink(full):
        shutil.rmtree(full)
    else:
        os.remove(full)


def parse_range(header, size):
    """(start, end) of a "bytes=…" header, ends included, or None when it isn't a range this file has."""
    if not header or not header.startswith("bytes=") or "," in header:
        return None
    a, _, b = header[6:].strip().partition("-")
    try:
        if a == "":
            n = int(b)
            return (max(0, size - n), size - 1) if n > 0 and size else None
        start = int(a)
        end = min(int(b), size - 1) if b else size - 1
    except ValueError:
        return None
    return (start, end) if start <= end else None
