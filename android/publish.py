#!/usr/bin/env python3
"""Publishes Prism for Android on the Prism server, where the phone updates
itself and its settings from (docs/prism-api.md, "The Android app").

  android-publish --url https://prism.quickme.at --token-file F
                  --apk UNSIGNED.apk --version-code N --version-name NAME
                  --keystore KS.p12 --keystore-password-file F
                  [--device NAME [--setting KEY=VALUE]... [--setting-file KEY=FILE]...]

Idempotent, so it runs after every deploy:

 1. Asks the server for the published version (GET /v1/android/release) with
    this machine's event token; the machine must be in the server's
    android_publishers. If that version is this one or newer, it is done.
 2. Signs the APK with the release key (apksigner, from PATH). The phone
    installs only APKs signed with the key it was installed with.
 3. Uploads it with its SHA-256 (PUT /v1/android/release). The server pushes
    app.release, and the phone fetches it.
 4. With --device, uploads that phone's settings (PUT /v1/android/settings),
    the keys of res/xml/app_restrictions.xml. The server pushes
    app.settings only when they changed. Rotating a secret in sops and
    deploying is all a rotation takes.

Secrets are read from files and never printed or put on a command line.
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

UPLOAD_ATTEMPTS = 3


def log(*args):
    print("android-publish:", *args, flush=True)


def read(path):
    with open(path) as f:
        return f.read().strip()


def call(url, token, method="GET", data=None, headers=None, timeout=60):
    req = urllib.request.Request(url, data=data, method=method,
                                 headers=dict(headers or {}, Authorization="Bearer " + token, Accept="application/json"))
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            body = json.loads(e.read() or b"{}")
        except ValueError:
            body = {}
        return e.code, body


def sign(apk, keystore, password_file, out):
    with open(password_file, "rb") as pw:
        subprocess.run(["apksigner", "sign", "--ks", keystore, "--ks-type", "PKCS12", "--ks-pass", "stdin",
                        "--out", out, apk], stdin=pw, check=True)
    subprocess.run(["apksigner", "verify", out], check=True)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", required=True)
    p.add_argument("--token-file", required=True)
    p.add_argument("--apk", required=True)
    p.add_argument("--version-code", type=int, required=True)
    p.add_argument("--version-name", required=True)
    p.add_argument("--keystore", required=True)
    p.add_argument("--keystore-password-file", required=True)
    p.add_argument("--package", default="at.quickme.prism", help="a debug build's own channel")
    p.add_argument("--device", help="the phone whose settings to publish")
    p.add_argument("--setting", action="append", default=[], metavar="KEY=VALUE")
    p.add_argument("--setting-file", action="append", default=[], metavar="KEY=FILE")
    args = p.parse_args()
    if (args.setting or args.setting_file) and not args.device:
        p.error("--setting needs --device")
    status = release(args)
    if args.device:
        settings(args)
    return status


def settings(args):
    values = {}
    for item in args.setting:
        key, _, value = item.partition("=")
        values[key] = value
    for item in args.setting_file:
        key, _, path = item.partition("=")
        values[key] = read(path)
    base = args.url.rstrip("/")
    body = json.dumps({"device": args.device, "settings": values}).encode()
    status, answer = call(base + "/v1/android/settings", read(args.token_file), method="PUT", data=body,
                          headers={"Content-Type": "application/json"})
    if status != 200:
        raise SystemExit("android-publish: settings: HTTP %d %s" % (status, answer.get("error", "")))
    log("settings of", args.device, "(%s):" % ", ".join(sorted(values)), "changed" if answer.get("changed") else "unchanged")


def release(args):

    base = args.url.rstrip("/")
    token = read(args.token_file)
    channel = "?" + urllib.parse.urlencode({"package": args.package})
    status, body = call(base + "/v1/android/release" + channel, token)
    if status == 200:
        published = body.get("versionCode", 0)
        if published >= args.version_code:
            log("version", body.get("versionName"), "(%d) is published; this is %d" % (published, args.version_code))
            return 0
        log("published:", body.get("versionName"), "(%d)" % published)
    elif status == 404:
        log("nothing published yet")
    else:
        raise SystemExit("android-publish: GET /v1/android/release: HTTP %d %s" % (status, body.get("error", "")))

    with tempfile.TemporaryDirectory() as tmp:
        signed = os.path.join(tmp, "prism-%d.apk" % args.version_code)
        sign(args.apk, args.keystore, args.keystore_password_file, signed)
        digest = hashlib.sha256()
        with open(signed, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                digest.update(chunk)
        size = os.path.getsize(signed)
        query = urllib.parse.urlencode({"package": args.package, "versionCode": args.version_code,
                                        "versionName": args.version_name, "sha256": digest.hexdigest()})
        log("uploading", args.version_name, "(%d, %d bytes)" % (args.version_code, size))
        # A dropped connection (a broken pipe mid-upload) is tried again: the
        # job runs again only hours later, and until then no phone updates.
        for attempt in range(1, UPLOAD_ATTEMPTS + 1):
            try:
                with open(signed, "rb") as f:
                    # The socket timeout bounds each read and write, not the whole upload.
                    status, body = call(base + "/v1/android/release?" + query, token, method="PUT", data=f,
                                        headers={"Content-Type": "application/vnd.android.package-archive",
                                                 "Content-Length": str(size)}, timeout=300)
                break
            except OSError as e:  # URLError, BrokenPipeError, timeouts
                if attempt == UPLOAD_ATTEMPTS:
                    raise
                log("upload attempt %d failed (%s); trying again" % (attempt, e))
                time.sleep(30 * attempt)
    if status == 409:
        log("the server has a newer version:", body.get("error", ""))
        return 0
    if status != 201:
        # A proxy may lose the answer to a long upload that did arrive.
        again, now = call(base + "/v1/android/release" + channel, token)
        if again == 200 and now.get("versionCode") == args.version_code and now.get("sha256") == digest.hexdigest():
            log("published", now.get("versionName"), "(%d; the upload's answer was HTTP %d)" % (args.version_code, status))
            return 0
        raise SystemExit("android-publish: upload: HTTP %d %s" % (status, body.get("error", "")))
    log("published", body.get("versionName"), "(%d)" % body.get("versionCode", 0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
