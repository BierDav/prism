#!/usr/bin/env python3
# Resolves the app's Maven dependencies (android/deps/roots.txt) into
# android/deps/lock.json, which default.nix fetches with fetchurl: every
# artifact of the closure with its URL and SHA-256, in the version Gradle
# would pick (the highest asked for, Gradle module metadata first, POMs
# otherwise, dependency constraints honoured for modules in the graph).
#
#   python3 -I android/deps/resolve.py        # after changing roots.txt
#
# The previews' renderer (android/previews) runs on top of the app's
# libraries: its own roots (previews.txt) resolve to previews-lock.json, with
# every module the app has kept at the app's version and left out.
#
#   python3 -I android/deps/resolve.py previews.txt previews-lock.json lock.json
#
# The shared module's Kotlin/Native libraries (shared/) resolve the same way
# for one Kotlin/Native target, to its klibs (the standard library comes with
# the compiler):
#
#   python3 -I android/deps/resolve.py --native macos_arm64 \
#     ../../shared/deps/macos-roots.txt ../../shared/deps/macos-lock.json
#
# Only this script touches the network; the build never does.

import hashlib
import json
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPOS = ["https://dl.google.com/android/maven2", "https://repo1.maven.org/maven2"]
# Artifacts the app never needs at run time or compile time.
SKIP = {
    "org.jspecify:jspecify",  # annotations only
    "org.jetbrains.kotlin:kotlin-stdlib-common",  # Kotlin metadata only, nothing for the JVM
}

# A Kotlin/Native target (--native): the variants and files for it instead
# of the Android/JVM ones.
NATIVE = None
NATIVE_SKIP = {
    "org.jetbrains.kotlin:kotlin-stdlib",  # in the Kotlin/Native distribution
}

# "g:m" -> the artifact's extension when a root asks for one ("g:m:v@aar"):
# JNA's POM says jar, its Android build is the .aar next to it. Such a root
# stays in a lock even when the base lock has the module (in another file).
EXT = {}

_cache = {}


def fetch(url):
    if url in _cache:
        return _cache[url]
    # A few tries: a connection reset halfway through ~150 artifacts
    # shouldn't send the whole resolution back to the start.
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "deps-resolver"})
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read()
            break
        except urllib.error.HTTPError as e:
            if e.code == 404:
                data = None
                break
            if e.code < 500 or attempt == 4:
                raise
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            if attempt == 4:
                raise
        time.sleep(1 + attempt * 2)
    _cache[url] = data
    return data


def path(group, module, version, name):
    return f"{group.replace('.', '/')}/{module}/{version}/{name}"


def find(group, module, version, name):
    """(repo, bytes) of the first repository that has the file."""
    for repo in REPOS:
        data = fetch(f"{repo}/{path(group, module, version, name)}")
        if data is not None:
            return repo, data
    return None, None


QUAL = {"dev": 0, "alpha": 1, "a": 1, "beta": 2, "b": 2, "rc": 3, "cr": 3, "m": 1, "eap": 0}


def vkey(v):
    """Maven-ish ordering: 1.5.0-alpha01 < 1.5.0-beta01 < 1.5.0-rc01 < 1.5.0."""
    main, _, qual = v.partition("-")
    nums = tuple(int(x) if x.isdigit() else 0 for x in re.split(r"[.]", main))
    nums = nums + (0,) * (4 - len(nums))
    if not qual:
        return nums + (9, 0)
    m = re.match(r"([A-Za-z]+)[.-]?(\d*)", qual)
    if not m:
        return nums + (5, 0)
    return nums + (QUAL.get(m.group(1).lower(), 5), int(m.group(2) or 0))


def pick_native_variant(meta):
    """The API variant (klibs) of a Gradle module for the --native target."""
    for v in meta.get("variants", []):
        a = v.get("attributes", {})
        if (a.get("org.jetbrains.kotlin.platform.type") == "native"
                and a.get("org.jetbrains.kotlin.native.target") == NATIVE
                and a.get("org.gradle.usage") == "kotlin-api"
                and a.get("org.gradle.category", "library") == "library"):
            return v
    return None


def pick_variant(meta):
    """The Android (or else JVM) runtime variant of a Gradle module."""
    if NATIVE:
        return pick_native_variant(meta)
    variants = meta.get("variants", [])

    def attrs(v):
        return v.get("attributes", {})

    def score(v):
        a = attrs(v)
        if a.get("org.gradle.usage") not in ("java-runtime", "java-api") and "available-at" not in v:
            return -1
        if a.get("org.gradle.category", "library") != "library":
            return -1
        if a.get("org.gradle.docstype") or "sources" in v["name"].lower() or "javadoc" in v["name"].lower():
            return -1
        if "metadata" in v["name"].lower() and "available-at" not in v:
            return -1
        s = 0
        platform = a.get("org.jetbrains.kotlin.platform.type")
        if platform == "androidJvm":
            s += 40
        elif platform == "jvm":
            s += 20
        elif platform is not None:
            return -1
        if a.get("org.gradle.usage") == "java-runtime":
            s += 5
        bt = a.get("com.android.build.api.attributes.BuildTypeAttr")
        if bt == "release":
            s += 3
        elif bt is not None:
            return -1
        return s

    best = max(variants, key=score, default=None)
    if best is None or score(best) < 0:
        return None
    return best


def parse_pom(raw):
    return ET.fromstring(re.sub(rb'\sxmlns="[^"]+"', b"", raw, count=1))


def pom_context(pom, group, version):
    """The properties and managed versions a POM sees, its parents' included."""
    chain = [(pom, group, version)]
    parent = pom.find("parent")
    while parent is not None:
        pg, pm, pv = (parent.findtext(t).strip() for t in ("groupId", "artifactId", "version"))
        _, raw = find(pg, pm, pv, f"{pm}-{pv}.pom")
        if raw is None:
            break
        p = parse_pom(raw)
        chain.append((p, pg, pv))
        parent = p.find("parent")
    props = {}
    for p, g, v in reversed(chain):
        props.update({e.tag: (e.text or "").strip() for e in p.findall("properties/*")})
        props["project.version"] = props["version"] = v
        props["project.groupId"] = g
        props["project.parent.version"] = p.findtext("parent/version") or v
    managed = {}
    for p, _, _ in reversed(chain):
        for d in p.findall("dependencyManagement/dependencies/dependency"):
            key = f"{(d.findtext('groupId') or '').strip()}:{(d.findtext('artifactId') or '').strip()}"
            managed[key] = (d.findtext("version") or "").strip()
    managed = {re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m.group(1), m.group(0)), k):
               re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m.group(1), m.group(0)), v) for k, v in managed.items()}
    return props, managed


def resolve_module(group, module, version):
    """(artifact files, deps, constraints, repo) of one coordinate."""
    repo, raw = find(group, module, version, f"{module}-{version}.module")
    if raw is not None:
        meta = json.loads(raw)
        v = pick_variant(meta)
        if v is None:
            raise SystemExit(f"no usable variant in {group}:{module}:{version}")
        if "available-at" in v:
            at = v["available-at"]
            return ("redirect", (at["group"], at["module"], at["version"]))
        deps = [(d["group"], d["module"], d.get("version", {}).get("requires") or d.get("version", {}).get("strictly") or d.get("version", {}).get("prefers"))
                for d in v.get("dependencies", [])]
        cons = [(d["group"], d["module"], d.get("version", {}).get("requires") or d.get("version", {}).get("strictly"))
                for d in v.get("dependencyConstraints", [])]
        files = [f["url"] for f in v.get("files", [])]
        return ("ok", (files, deps, cons, repo))
    if NATIVE:
        raise SystemExit(f"no Gradle module metadata (needed for {NATIVE}): {group}:{module}:{version}")
    repo, raw = find(group, module, version, f"{module}-{version}.pom")
    if raw is None:
        raise SystemExit(f"not found: {group}:{module}:{version}")
    pom = parse_pom(raw)
    moved = pom.find("distributionManagement/relocation")
    if moved is not None:
        return ("redirect", tuple((moved.findtext(t) or d).strip() for t, d in
                                  (("groupId", group), ("artifactId", module), ("version", version))))
    packaging = (pom.findtext("packaging") or "jar").strip()
    props, managed = pom_context(pom, group, version)

    def sub(text):
        return re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m.group(1), m.group(0)), (text or "").strip())

    deps = []
    for d in pom.findall("dependencies/dependency"):
        scope = (d.findtext("scope") or "compile").strip()
        if scope not in ("compile", "runtime") or (d.findtext("optional") or "").strip() == "true":
            continue
        dg, dm = sub(d.findtext("groupId")), sub(d.findtext("artifactId"))
        # No version: the parents' dependencyManagement has it.
        ver = sub(d.findtext("version")) or managed.get(f"{dg}:{dm}", "")
        ver = ver.strip("[]()").split(",")[0]
        deps.append((dg, dm, ver))
    ext = EXT.get(f"{group}:{module}") or ("aar" if packaging == "aar" else "jar")
    files = [] if packaging == "pom" else [f"{module}-{version}.{ext}"]
    # Some artifacts say "pom" yet ship a jar (layoutlib); Gradle takes it.
    if packaging == "pom" and find(group, module, version, f"{module}-{version}.jar")[1] is not None:
        files = [f"{module}-{version}.jar"]
    return ("ok", (files, deps, [], repo))


def main():
    global NATIVE
    args = sys.argv[1:]
    if args[:1] == ["--native"]:
        NATIVE, args = args[1], args[2:]
        SKIP.update(NATIVE_SKIP)
    roots_file, lock_file, base_file = (args + [None, None, None])[:3]
    roots = []
    for line in (HERE / (roots_file or "roots.txt")).read_text().splitlines():
        line = line.split("#")[0].strip()
        if line:
            coordinate, _, ext = line.partition("@")
            g, m, v = coordinate.split(":")
            if ext:
                EXT[f"{g}:{m}"] = ext
            roots.append((g, m, v))

    wanted = {}       # "g:m" -> highest version asked for
    forced = {f"{g}:{m}": v for g, m, v in roots}
    # The base lock's modules: at its versions, and not in this lock.
    base = {}
    if base_file:
        for d in json.loads((HERE / base_file).read_text()):
            g, m, v = d["coordinate"].split(":")
            base[f"{g}:{m}"] = v
    forced.update(base)
    constraints = {}  # "g:m" -> highest constrained version
    info = {}         # "g:m:v" -> resolved data

    def bump(table, key, v):
        if v and (key not in table or vkey(v) > vkey(table[key])):
            table[key] = v
            return True
        return False

    for g, m, v in roots:
        bump(wanted, f"{g}:{m}", v)

    changed = True
    while changed:
        changed = False
        for key in list(wanted):
            if key in SKIP:
                continue
            g, m = key.split(":")
            v = wanted[key]
            if key in constraints and vkey(constraints[key]) > vkey(v) and key not in forced:
                wanted[key] = v = constraints[key]
                changed = True
            if key in forced:
                v = wanted[key] = forced[key]
            ck = f"{key}:{v}"
            if ck in info:
                continue
            kind, data = resolve_module(g, m, v)
            info[ck] = (kind, data)
            changed = True
            if kind == "redirect":
                rg, rm, rv = data
                bump(wanted, f"{rg}:{rm}", rv)
                continue
            files, deps, cons, repo = data
            for dg, dm, dv in deps:
                if f"{dg}:{dm}" not in SKIP:
                    bump(wanted, f"{dg}:{dm}", dv)
            for cg, cm, cv in cons:
                bump(constraints, f"{cg}:{cm}", cv)

    out = []
    for key in sorted(wanted):
        if key in SKIP or (key in base and key not in EXT):
            continue
        v = wanted[key]
        kind, data = info[f"{key}:{v}"]
        if kind == "redirect":
            continue
        files, _deps, _cons, repo = data
        g, m = key.split(":")
        for name in files:
            url = f"{repo}/{path(g, m, v, name)}"
            blob = fetch(url)
            if blob is None:
                raise SystemExit(f"missing file {url}")
            out.append({
                "coordinate": f"{g}:{m}:{v}",
                "file": name,
                "url": url,
                "sha256": hashlib.sha256(blob).hexdigest(),
            })
            print(f"{g}:{m}:{v}  {name}  {len(blob) // 1024} KiB", file=sys.stderr)
    (HERE / (lock_file or "lock.json")).write_text(json.dumps(out, indent=1) + "\n")
    print(f"{len(out)} artifacts", file=sys.stderr)


if __name__ == "__main__":
    main()
