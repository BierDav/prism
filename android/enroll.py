#!/usr/bin/env python3
"""Enrolls the owner's phone over USB, or a TV over adb on the LAN
(--serial <ip>:5555): installs Prism, makes it the Device Owner and writes
its settings (fleet wraps this as prism-phone-enroll).

  phone-enroll --apk UNSIGNED.apk --version-code N
               (--keystore-sops FILE#KEY --keystore-password-sops FILE#KEY
                | --keystore-file KS.p12 --keystore-password-file F)
               [--setting NAME=VALUE]... [--setting-file NAME=FILE]...
               [--secret NAME=FILE#KEY]...
               [--serial SERIAL] [--package ID] [--settings-only | --no-owner]

The settings include the phone's Rocky replica: rocky_provisioning (a
secret, `prism-worker provision david-pixel`, e.g. --secret
rocky_provisioning=FILE#KEY) and rocky_peers (the workers' contacts from
`prism-worker peer`, one per line, e.g. --setting-file rocky_peers=FILE).
Never give a TV a rocky_provisioning: every replica holds all of Prism's
data, and a TV only updates Prism.

Run it as the owner on the Mac the phone is plugged into, with USB debugging
on and no accounts on the phone (Android allows a Device Owner only then).
Each step is skipped when it is done already, so it can run again, e.g. to
rotate a setting (--settings-only):

 0. Checks the phone before it changes anything: no other app is the Device
    Owner and no account is on the phone (unless --settings-only or
    --no-owner, which leave the Device Owner alone).
 1. Decrypts the release key and the secret settings with sops (the owner's
    admin key, ~/.ssh/id_ed25519). Values stay in this process's memory; the
    keystore exists only in a private temporary directory while apksigner
    signs.
 2. Signs the APK and installs it with adb, unless the phone has this
    version or a newer one.
 3. `dpm set-device-owner at.quickme.prism/.DeviceAdmin`, unless Prism
    is the Device Owner already.
 4. Grants Prism DUMP (batterystats for the phone's metrics) and
    WRITE_SECURE_SETTINGS (switching the digital assistant; turning Wireless
    debugging back on for the shell server, android/shizuku/README.md) with
    `pm grant`.
    Both are development permissions only adb's shell can grant; they stay
    granted across updates.
 5. Writes the settings through adb's stdin into Prism (ProvisionProvider:
    `content write`), so no value is ever on a command line, in logcat or in
    a file on the phone or here.
 6. Reads Prism's status back and checks every setting arrived (by the first
    16 hex digits of each value's SHA-256). Prints the phone's device key
    for the Prism server's devices config.
"""

import argparse
import base64
import hashlib
import json
import os
import subprocess
import sys
import tempfile

RELEASE_PACKAGE = "at.quickme.prism"
# The admin class keeps its name in a debug build with another package id.
ADMIN_CLASS = RELEASE_PACKAGE + ".DeviceAdmin"


def log(*args):
    print("phone-enroll:", *args, flush=True)


def fail(text):
    raise SystemExit("phone-enroll: " + text)


class Adb:
    def __init__(self, serial, package):
        self.base = ["adb"] + (["-s", serial] if serial else [])
        self.package = package
        self.admin = package + "/" + ADMIN_CLASS
        self.provider = "content://" + package + ".provision"

    def run(self, *args, input=None, check=True):
        r = subprocess.run(self.base + list(args), input=input, capture_output=True)
        if check and r.returncode != 0:
            # Both: `adb install` gives its reason (Failure [INSTALL_...]) on
            # stdout and only "failed to install" on stderr.
            out = (r.stderr + b"\n" + r.stdout).decode(errors="replace").strip()
            fail("adb %s: %s" % (" ".join(args[:3]), " ".join(out.split())))
        return r.stdout.decode(errors="replace")

    def shell(self, *args, input=None, check=True):
        return self.run("shell", *args, input=input, check=check)


def pick_device(serial):
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=True).stdout
    devices = [line.split()[:2] for line in out.splitlines()[1:] if line.strip()]
    if serial:
        devices = [d for d in devices if d[0] == serial]
    if not devices:
        fail("no phone on adb: plug it in, turn on USB debugging and allow this Mac")
    if len(devices) > 1:
        fail("more than one device on adb (%s): pick one with --serial" % ", ".join(d[0] for d in devices))
    name, state = devices[0]
    if state != "device":
        fail("%s is %s: unlock the phone and allow this Mac's USB debugging" % (name, state))
    return name


def sops(spec):
    path, _, key = spec.partition("#")
    if not key:
        fail("a sops reference is FILE#KEY, not %r" % spec)
    env = dict(os.environ)
    if not any(k in env for k in ("SOPS_AGE_KEY", "SOPS_AGE_KEY_FILE", "SOPS_AGE_KEY_CMD")):
        # The owner's admin key, converted on the fly: it never lands in the
        # environment or in a file.
        env["SOPS_AGE_KEY_CMD"] = "ssh-to-age -private-key -i " + os.path.expanduser("~/.ssh/id_ed25519")
    r = subprocess.run(["sops", "-d", "--extract", '["%s"]' % key, path], capture_output=True, env=env)
    if r.returncode != 0:
        fail("sops could not decrypt %s from %s: %s" % (key, os.path.basename(path), r.stderr.decode(errors="replace").strip()))
    return r.stdout.decode().strip()


def fingerprint(value):
    return hashlib.sha256(value.strip().encode()).hexdigest()[:16]


def status(adb):
    out = adb.shell("content", "read", "--uri", adb.provider + "/status", check=False)
    try:
        return json.loads(out)
    except ValueError:
        return None


def installed_version(adb):
    out = adb.shell("dumpsys", "package", adb.package, check=False)
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("versionCode="):
            return int(line.split()[0].split("=")[1])
    return 0


def owners(adb):
    """The Device Owner's component, or "" (dpm list-owners)."""
    out = adb.shell("dpm", "list-owners", check=False)
    for line in out.splitlines():
        if "DeviceOwner" in line and "admin=" in line:
            return line.split("admin=", 1)[1].split(",", 1)[0].strip()
    return ""


def accounts(adb):
    """The number of accounts on the phone (dumpsys account)."""
    out = adb.shell("dumpsys", "account", check=False)
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("Accounts:"):
            try:
                return int(line.split()[1])
            except (IndexError, ValueError):
                break
    return -1


def preflight(adb):
    """Refuses before anything is installed if the phone can't take Prism as its Device Owner."""
    owner = owners(adb)
    if owner and owner.split("/")[0] != adb.package:
        fail("%s is the Device Owner: Android hands the role to another app only after a factory reset"
             " (docs/android-enrollment.md). Nothing was changed." % owner)
    if not owner:
        n = accounts(adb)
        if n != 0:
            fail(("%s account(s) on the phone: remove them (Settings > Passwords, passkeys & accounts),"
                  " run this again, and add them back afterwards. Nothing was changed.") % (n if n > 0 else "Some"))


def keystore_material(args):
    if args.keystore_file:
        with open(args.keystore_file, "rb") as f:
            keystore = f.read()
        with open(args.keystore_password_file) as f:
            password = f.read().strip()
        return keystore, password
    return base64.b64decode(sops(args.keystore_sops)), sops(args.keystore_password_sops)


def install(adb, args):
    have = installed_version(adb)
    if have >= args.version_code:
        log("Prism %d is installed (this is %d): not installing" % (have, args.version_code))
        return
    keystore, password = keystore_material(args)
    with tempfile.TemporaryDirectory() as tmp:
        os.chmod(tmp, 0o700)
        ks = os.path.join(tmp, "release.p12")
        with open(os.open(ks, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as f:
            f.write(keystore)
        signed = os.path.join(tmp, "prism.apk")
        subprocess.run(["apksigner", "sign", "--ks", ks, "--ks-type", "PKCS12", "--ks-pass", "stdin",
                        "--out", signed, args.apk], input=(password + "\n").encode(), check=True)
        os.remove(ks)
        subprocess.run(["apksigner", "verify", signed], check=True)
        log("installing %s %d" % (adb.package, args.version_code) + (" over %d" % have if have else ""))
        adb.run("install", "--no-incremental", "-r", signed)


def make_owner(adb):
    s = status(adb)
    if s and s.get("deviceOwner"):
        log("Prism is the Device Owner already")
        return
    out = adb.shell("dpm", "set-device-owner", adb.admin, check=False)
    if "Success" not in out:
        text = out.strip() or "no answer"
        if "account" in text.lower():
            text += ("\n  Remove every account (Settings > Passwords, passkeys & accounts) and try again;"
                     " add them back afterwards.")
        elif "already" in text.lower() or "device owner" in text.lower():
            text += "\n  Another app is the Device Owner: only a factory reset ends that."
        fail("dpm set-device-owner: " + text)
    log("Prism is the Device Owner")


# Development permissions Prism declares and only adb's shell can grant.
GRANTS = ["android.permission.DUMP", "android.permission.WRITE_SECURE_SETTINGS"]


def grant(adb):
    for permission in GRANTS:
        out = adb.shell("pm", "grant", adb.package, permission, check=False).strip()
        if out:
            fail("pm grant %s: %s" % (permission, out))
    log("granted", ", ".join(p.rsplit(".", 1)[1] for p in GRANTS))


def provision(adb, settings):
    payload = json.dumps({"settings": settings}).encode()
    adb.shell("content", "write", "--uri", adb.provider + "/settings", input=payload)
    s = status(adb)
    if s is None:
        fail("Prism did not answer with its status")
    error = (s.get("lastWrite") or {}).get("error")
    if error:
        fail("Prism refused the settings: " + error)
    have = s.get("settings") or {}
    wrong = []
    for name, value in sorted(settings.items()):
        got = have.get(name) or {}
        ok = value is None or (got.get("source") == "provisioned" and got.get("sha256") == fingerprint(value))
        if not ok:
            wrong.append(name)
    if wrong:
        fail("these settings did not arrive: " + ", ".join(wrong))
    log("settings written:", ", ".join(sorted(settings)))
    return s


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--apk", required=True)
    p.add_argument("--version-code", type=int, required=True)
    p.add_argument("--keystore-sops", metavar="FILE#KEY", help="the release key in sops (base64 PKCS12)")
    p.add_argument("--keystore-password-sops", metavar="FILE#KEY")
    p.add_argument("--keystore-file", metavar="KS.p12", help="a debug key instead (PKCS12)")
    p.add_argument("--keystore-password-file", metavar="FILE")
    p.add_argument("--package", default=RELEASE_PACKAGE, help="the app's package id (a debug build's)")
    p.add_argument("--no-owner", action="store_true", help="install and provision, but leave the Device Owner alone")
    p.add_argument("--setting", action="append", default=[], metavar="NAME=VALUE")
    p.add_argument("--setting-file", action="append", default=[], metavar="NAME=FILE")
    p.add_argument("--secret", action="append", default=[], metavar="NAME=FILE#KEY")
    p.add_argument("--serial")
    p.add_argument("--settings-only", action="store_true", help="only write the settings (rotation)")
    args = p.parse_args()
    if not args.settings_only and not (
            (args.keystore_sops and args.keystore_password_sops) or (args.keystore_file and args.keystore_password_file)):
        p.error("installing needs --keystore-sops and --keystore-password-sops, or --keystore-file and --keystore-password-file")
    if args.package != RELEASE_PACKAGE and args.keystore_sops:
        p.error("a debug build (--package) is never signed with the release key")

    adb = Adb(pick_device(args.serial), args.package)
    settings = {}
    for item in args.setting:
        name, _, value = item.partition("=")
        settings[name] = value
    for item in args.setting_file:
        name, _, path = item.partition("=")
        with open(path) as f:
            settings[name] = f.read().strip()
    for item in args.secret:
        name, _, spec = item.partition("=")
        settings[name] = sops(spec)

    if not args.settings_only:
        if not args.no_owner:
            preflight(adb)
        install(adb, args)
        if not args.no_owner:
            make_owner(adb)
    if installed_version(adb) == 0:
        fail("%s is not installed on the phone" % adb.package)
    grant(adb)
    s = provision(adb, settings)

    print()
    print("%s %s on the phone; Device Owner: %s" % (adb.package, s.get("versionName"), "yes" if s.get("deviceOwner") else "NO"))
    nb = s.get("netbird") or {}
    print("NetBird: %s%s" % (nb.get("status", "?"), " · " + nb["ip"] if nb.get("ip") else ""))
    if s.get("tv"):
        # The workers read only a device's key, as one that may accept
        # approvals; a TV updates without one (GET /v1/android/release).
        print("A TV: no entry in prism-config.json (a device key there could accept approvals).")
    elif s.get("publicKey"):
        device = settings.get("device_name") or "david-pixel"
        print("The device key (devices.%s.key in selfhosted-platform's prism-config.json, which the workers read):"
              % device)
        print("  " + s["publicKey"])
    elif s.get("publicKeyError"):
        print("No device key yet (%s): set a screen lock, then run this again." % s["publicKeyError"])
    print("Updates:", s.get("update", "?"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
