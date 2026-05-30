#!/usr/bin/env python3
"""
Real FlashForge UDP discovery + seed the emulator's saved printers over adb.

After a destructive DB wipe (every schema bump drops the Room DB in this prototype) this re-pairs
your printers without touching the UI:

  1. Broadcasts the exact same empty-payload discovery datagrams the app sends
     (UdpDiscovery.kt) and parses the replies for live IP / serial / name / ports.
  2. Joins discovered serials against your saved check-codes (printers.local.json, the FFUI-Electron
     printers config format) — discovery does NOT carry the check-code, it's a per-printer secret.
  3. Pushes the matched, *online* printers to the app via a debug intent extra (DebugPrinterSeeder),
     then relaunches so startup-reconnect brings them up live.

Usage:
  python scripts/seed_printers.py                 # discover online printers, seed matches
  python scripts/seed_printers.py --all           # skip discovery, seed every printer in config
  python scripts/seed_printers.py --device emulator-5554
  python scripts/seed_printers.py --config path/to/printers.json
  python scripts/seed_printers.py --dry-run       # discover + print, don't touch adb
"""
import argparse
import base64
import json
import os
import socket
import subprocess
import sys
import time

APP_ID = "me.ghost.ffui"
ACTIVITY = f"{APP_ID}/.MainActivity"
SEED_EXTRA = "seed_b64"

# Mirrors UdpDiscovery.kt: empty datagrams to these multicast/broadcast targets.
DISCOVERY_TARGETS = [
    ("225.0.0.9", 19000),
    ("225.0.0.9", 8899),
    ("255.255.255.255", 48899),
    ("255.255.255.255", 19000),
    ("255.255.255.255", 8899),
]

DEFAULT_CONFIG = os.path.join(os.path.dirname(__file__), "printers.local.json")


# ── Discovery ────────────────────────────────────────────────────────────────

def discover(rounds=3, listen_secs=3.0):
    """Replicate UdpDiscovery.kt. Returns a list of dicts: ip, name, serial, is_modern, ports."""
    found = {}  # (ip, cmd_port) -> printer dict

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    sock.bind(("", 0))
    sock.settimeout(1.5)

    try:
        for r in range(rounds):
            for ip, port in DISCOVERY_TARGETS:
                try:
                    sock.sendto(b"", (ip, port))
                except OSError as e:
                    print(f"  ! send to {ip}:{port} failed: {e}", file=sys.stderr)

            end = time.time() + listen_secs
            while time.time() < end:
                try:
                    data, addr = sock.recvfrom(512)
                except socket.timeout:
                    break
                printer = _parse_reply(data, addr[0])
                if printer is None:
                    continue
                key = (printer["ip"], printer["cmd_port"])
                existing = found.get(key)
                if existing is None or (printer["is_modern"] and not existing["is_modern"]):
                    found[key] = printer

            if found:
                break  # early exit once we've heard from anyone
            if r < rounds - 1:
                time.sleep(1.0)
    finally:
        sock.close()

    return list(found.values())


def _parse_reply(data, ip):
    """Byte layout per UdpDiscovery.kt. Replies shorter than 140 bytes are ignored."""
    if len(data) < 140:
        return None
    name = data[0:128].split(b"\x00", 1)[0].decode("utf-8", "replace").strip()
    is_modern = len(data) >= 276
    cmd_port = (data[0x84] << 8) | data[0x85]
    http_port = ((data[0x8E] << 8) | data[0x8F]) if is_modern else 8898
    serial = ""
    if is_modern:
        raw = data[146:146 + 128]
        serial = raw.split(b"\x00", 1)[0].decode("utf-8", "replace").strip()
    return dict(ip=ip, name=name, serial=serial, is_modern=is_modern,
                cmd_port=cmd_port, http_port=http_port)


# ── Config (check-codes) ─────────────────────────────────────────────────────

def load_config(path):
    """Parse the FFUI-Electron printers config -> {serial: {name, checkCode, ip}}."""
    with open(path, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    out = {}
    for serial, p in cfg.get("printers", {}).items():
        out[serial] = dict(
            name=p.get("Name", serial),
            checkCode=p.get("CheckCode", ""),
            ip=p.get("IPAddress", ""),
        )
    return out


# ── adb ──────────────────────────────────────────────────────────────────────

def adb(args, device=None):
    cmd = ["adb"] + (["-s", device] if device else []) + args
    return subprocess.run(cmd, capture_output=True, text=True)


def resolve_device(requested):
    r = adb(["devices"])
    devices = [ln.split("\t")[0] for ln in r.stdout.splitlines()[1:]
               if ln.strip() and ln.endswith("\tdevice")]
    if requested:
        if requested not in devices:
            sys.exit(f"Device '{requested}' not found. Connected: {devices or 'none'}")
        return requested
    if not devices:
        sys.exit("No adb devices/emulators connected.")
    if len(devices) > 1:
        sys.exit(f"Multiple devices connected: {devices}. Pass --device.")
    return devices[0]


def seed(device, seeds, relaunch_wait):
    payload = base64.b64encode(json.dumps(seeds).encode("utf-8")).decode("ascii")
    print(f"-> seeding {len(seeds)} printer(s) into {device} ...")
    adb(["shell", "am", "force-stop", APP_ID], device)
    r = adb(["shell", "am", "start", "-n", ACTIVITY, "--es", SEED_EXTRA, payload], device)
    if r.returncode != 0:
        sys.exit(f"am start failed:\n{r.stderr or r.stdout}")
    # Give the app time to launch + commit the Room writes before we kill it.
    time.sleep(relaunch_wait)
    print("-> relaunching to trigger startup-reconnect ...")
    adb(["shell", "am", "force-stop", APP_ID], device)
    adb(["shell", "am", "start", "-n", ACTIVITY], device)


# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(description="FlashForge UDP discovery + emulator seed.")
    ap.add_argument("--config", default=DEFAULT_CONFIG, help="FFUI printers config JSON (check-codes).")
    ap.add_argument("--device", help="adb device serial (auto-detected if only one).")
    ap.add_argument("--all", action="store_true", help="Skip discovery; seed every printer in config.")
    ap.add_argument("--dry-run", action="store_true", help="Discover + print, don't touch adb.")
    ap.add_argument("--relaunch-wait", type=float, default=3.0, help="Seconds to wait before relaunch.")
    args = ap.parse_args()

    if not os.path.exists(args.config):
        sys.exit(f"Config not found: {args.config}\n"
                 f"Create it from your FFUI printers JSON (see scripts/printers.local.json).")
    config = load_config(args.config)
    if not config:
        sys.exit("Config has no printers.")

    if args.all:
        seeds = [dict(serial=s, ip=c["ip"], name=c["name"], checkCode=c["checkCode"])
                 for s, c in config.items()]
        print(f"--all: seeding {len(seeds)} configured printer(s), discovery skipped.")
    else:
        print("Discovering printers (real UDP broadcast)...")
        discovered = discover()
        if not discovered:
            sys.exit("No printers answered discovery. Use --all to seed from config regardless.")
        seeds = []
        for d in discovered:
            label = f"{d['name'] or '?'} @ {d['ip']} (serial={d['serial'] or 'n/a'}, " \
                    f"cmd={d['cmd_port']}, http={d['http_port']})"
            c = config.get(d["serial"])
            if c:
                # Prefer the *live* discovered IP; check-code + name come from config.
                seeds.append(dict(serial=d["serial"], ip=d["ip"],
                                  name=c["name"] or d["name"], checkCode=c["checkCode"]))
                print(f"  [+] {label}  [matched check-code]")
            else:
                print(f"  [-] {label}  [no check-code in config, skipped]")
        if not seeds:
            sys.exit("Discovered printers, but none matched a check-code in the config.")

    if args.dry_run:
        print("\n--dry-run, not seeding. Payload would be:")
        print(json.dumps(seeds, indent=2))
        return

    device = resolve_device(args.device)
    seed(device, seeds, args.relaunch_wait)
    print(f"Done. Seeded {len(seeds)} printer(s); the app should reconnect on relaunch.")


if __name__ == "__main__":
    main()
