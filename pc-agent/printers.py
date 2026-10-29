"""Bambu Lab printers on the LAN, for the phone's printer setup.

The phone talks to the printer itself (MQTT on port 8883); the PC only helps to find it: printers
announce themselves by SSDP, and Bambu Studio keeps the access code of every printer it used over the
LAN in BambuStudio.conf (read only, nothing is changed there).
"""
import json
import os
import re
import socket
import struct
import time

MODELS = {"C11": "P1P", "C12": "P1S", "C13": "X1E", "BL-P001": "X1 Carbon", "BL-P002": "X1", "N1": "A1 mini",
          "N2S": "A1", "O1D": "H2D", "O1S": "H2S", "N7": "P2S"}
SSDP_GROUP = "239.255.255.250"
SSDP_PORTS = (2021, 1990)  # announcements, answers to M-SEARCH


def studio_codes():
    """{serial: access code} that Bambu Studio saved."""
    if os.name == "nt":
        path = os.path.join(os.environ.get("APPDATA") or "", "BambuStudio", "BambuStudio.conf")
    else:
        path = os.path.join(os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"),
                            "BambuStudio", "BambuStudio.conf")
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            text = f.read()
        data = json.loads(text[text.find("{"):text.rfind("}") + 1])  # the file starts with a comment line
    except (OSError, ValueError):
        return {}
    codes = {}
    for key in ("access_code", "user_access_code"):
        for serial, code in (data.get(key) or {}).items():
            if isinstance(code, str) and code.strip():
                codes.setdefault(serial.upper(), code.strip())
    return codes


def discover(seconds=5.0):
    """[{ip, serial, name, model}] of the printers that answer within the time."""
    socks = []
    for port in SSDP_PORTS:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("", port))
            s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                         struct.pack("4sl", socket.inet_aton(SSDP_GROUP), socket.INADDR_ANY))
        except OSError:
            pass
        s.settimeout(0.25)
        socks.append(s)
    q = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    q.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    q.settimeout(0.25)
    search = "\r\n".join(["M-SEARCH * HTTP/1.1", "HOST: 239.255.255.250:1990", 'MAN: "ssdp:discover"', "MX: 1",
                          "ST: urn:bambulab-com:device:3dprinter:1", "", ""]).encode()
    for addr in ((SSDP_GROUP, 1990), ("255.255.255.255", 2021)):
        try:
            q.sendto(search, addr)
        except OSError:
            pass
    socks.append(q)
    found = {}
    start = time.monotonic()
    try:
        while time.monotonic() - start < seconds and not (found and time.monotonic() - start > 2.5):
            for s in socks:
                try:
                    data, addr = s.recvfrom(4096)
                except OSError:
                    continue
                text = data.decode("utf-8", "replace")

                def field(name, text=text):
                    m = re.search(r"^" + name + r":\s*(.+?)\s*$", text, re.I | re.M)
                    return m.group(1) if m else ""
                serial = field("USN").upper()
                if not serial or "bambu" not in text.lower():
                    continue
                loc = field("Location")
                found[serial] = {"ip": loc if re.match(r"^\d+\.\d+\.\d+\.\d+$", loc) else addr[0],
                                 "serial": serial, "name": field(r"DevName\.bambu\.com") or serial,
                                 "model": MODELS.get(field(r"DevModel\.bambu\.com"), "")}
    finally:
        for s in socks:
            s.close()
    return list(found.values())


def find():
    """Printers on the LAN with the access codes Bambu Studio knows ("" when it doesn't)."""
    codes = studio_codes()
    out = [dict(p, code=codes.get(p["serial"], "")) for p in discover()]
    # printers Bambu Studio knows but that didn't answer (other subnet, asleep): the phone can still use the code
    for serial, code in codes.items():
        if not any(p["serial"] == serial for p in out):
            out.append({"ip": "", "serial": serial, "name": serial, "model": "", "code": code})
    return out
