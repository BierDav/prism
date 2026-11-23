#!/usr/bin/env python3
"""Install the signed Prism T3 Code build and pair it to the fleet over USB."""

import argparse
import concurrent.futures
import json
import re
import socket
import subprocess
import time
import urllib.parse

from enroll import Adb, RELEASE_PACKAGE, pick_device, status

PACKAGE = "at.quickme.t3chat"
SIGNER = "b983f9b05914943381fbd6bfbc6f338960a8e76d03eaae722b1e874988cd27ce"
HOST = re.compile(r"[a-z0-9][a-z0-9-]*")


def command(args, timeout=20):
    return subprocess.run(args, capture_output=True, check=True, text=True, timeout=timeout).stdout


def pair_url(host, local):
    server = f"{host}.netbird.cloud"
    prefix = [] if host == local else ["ssh", "-oBatchMode=yes", "-oConnectTimeout=8", server]
    cli = ["/run/current-system/sw/bin/t3-nightly"]
    try:
        raw = command(prefix + cli + ["auth", "pairing", "create", "--ttl", "15m",
                                         "--label", "Prism-Pixel-T3", "--json", "--log-level", "none"])
        secret = json.loads(raw)["credential"]
        return f"https://{server}:3774/#" + urllib.parse.urlencode({"token": secret})
    except (subprocess.SubprocessError, OSError, ValueError, KeyError):
        raise SystemExit(f"Could not issue a pairing code for {host}.") from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", help="The fork release's signed t3chat.apk")
    parser.add_argument("hosts", nargs="+", help="The fleet T3 hostnames, including this Mac")
    parser.add_argument("--serial")
    parser.add_argument("--apksigner", default="apksigner")
    options = parser.parse_args()
    hosts = list(dict.fromkeys(options.hosts))
    if len(hosts) > 32 or not all(HOST.fullmatch(host) for host in hosts):
        parser.error("expected up to 32 fleet hostnames")
    adb = Adb(pick_device(options.serial), RELEASE_PACKAGE)
    current = status(adb)
    if not current or not current.get("deviceOwner") or "lastT3Write" not in current:
        raise SystemExit("Install the Prism Device Owner build with T3 provisioning first.")

    signature = command([options.apksigner, "verify", "--print-certs", options.apk])
    if re.findall(r"certificate SHA-256 digest: ([a-f0-9]{64})", signature) != [SIGNER]:
        raise SystemExit("The APK does not carry the Prism T3 signing certificate.")
    local = socket.gethostname().split(".")[0]
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(8, len(hosts))) as batch:
        urls = list(batch.map(lambda host: pair_url(host, local), hosts))
    adb.run("install", "-r", options.apk)

    before = current["lastT3Write"]["at"]
    adb.shell("content", "write", "--uri", adb.provider + "/t3",
              input=json.dumps({"pairingUrls": urls}).encode())
    end = time.monotonic() + 5
    while True:
        outcome = (status(adb) or {}).get("lastT3Write", {})
        if outcome.get("at", 0) > before:
            if outcome["error"]:
                raise SystemExit(outcome["error"])
            break
        if time.monotonic() >= end:
            raise SystemExit("Prism did not acknowledge T3 provisioning.")
        time.sleep(0.1)

    adb.shell("am", "start", "-W", "-a", "android.intent.action.MAIN",
              "-c", "android.intent.category.LAUNCHER", "-n", PACKAGE + "/.MainActivity")
    print("Started T3 Code with pairing configuration for: " + ", ".join(hosts))


if __name__ == "__main__":
    main()
