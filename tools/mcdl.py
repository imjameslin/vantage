#!/usr/bin/env python3
"""Download a vanilla Minecraft client (version JSON, client jar, libraries,
natives, assets) plus Mojang's own Java runtime, straight from Mojang's
servers, verifying every file's SHA1. Optionally layers a Fabric profile.

Usage:
  mcdl.py vanilla <version> <root>
  mcdl.py fabric  <version> <loader> <root>
"""
import hashlib, json, os, platform, stat, sys, urllib.request, zipfile
from concurrent.futures import ThreadPoolExecutor

MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
RUNTIMES = "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json"
RESOURCES = "https://resources.download.minecraft.net"
FABRIC_META = "https://meta.fabricmc.net/v2"
OS_NAME = "osx"
ARCH = "arm64" if platform.machine() == "arm64" else "x86_64"
RUNTIME_PLATFORM = "mac-os-arm64" if ARCH == "arm64" else "mac-os"


def get_json(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.load(r)


def sha1(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(url, dest, want_sha1=None):
    if os.path.exists(dest) and (want_sha1 is None or sha1(dest) == want_sha1):
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
        while chunk := r.read(1 << 20):
            f.write(chunk)
    if want_sha1 and sha1(tmp) != want_sha1:
        os.remove(tmp)
        raise RuntimeError(f"SHA1 mismatch for {url}")
    os.replace(tmp, dest)
    return dest


def rules_allow(rules):
    if not rules:
        return True
    allowed = False
    for rule in rules:
        os_rule = rule.get("os", {})
        if rule.get("features"):
            match = False  # demo/quickplay/custom-resolution features are opt-in
        else:
            match = (os_rule.get("name") in (None, OS_NAME)) and (
                os_rule.get("arch") in (None, ARCH)
            )
        if match:
            allowed = rule["action"] == "allow"
    return allowed


def maven_path(name):
    parts = name.split(":")
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = f"-{parts[3]}" if len(parts) > 3 else ""
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}{classifier}.jar"


def download_runtime(component, root):
    rt = get_json(RUNTIMES)[RUNTIME_PLATFORM][component][0]
    files = get_json(rt["manifest"]["url"])["files"]
    base = os.path.join(root, "runtime", component)
    jobs = []
    for rel, info in files.items():
        dest = os.path.join(base, rel)
        if info["type"] == "directory":
            os.makedirs(dest, exist_ok=True)
        elif info["type"] == "file":
            raw = info["downloads"]["raw"]
            jobs.append((raw["url"], dest, raw["sha1"], info.get("executable")))
    with ThreadPoolExecutor(16) as ex:
        list(ex.map(lambda j: fetch(j[0], j[1], j[2]), jobs))
    for _, dest, _, exe in jobs:
        if exe:
            os.chmod(dest, os.stat(dest).st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    for rel, info in files.items():
        if info["type"] == "link":
            dest = os.path.join(base, rel)
            if not os.path.lexists(dest):
                os.symlink(info["target"], dest)
    print(f"runtime {component} {rt['version']['name']} -> {base}")


def download_vanilla(version, root):
    entry = next(v for v in get_json(MANIFEST)["versions"] if v["id"] == version)
    vdir = os.path.join(root, "versions", version)
    vjson_path = fetch(entry["url"], os.path.join(vdir, f"{version}.json"), entry["sha1"])
    vjson = json.load(open(vjson_path))
    client = vjson["downloads"]["client"]
    fetch(client["url"], os.path.join(vdir, f"{version}.jar"), client["sha1"])

    jobs, natives = [], []
    for lib in vjson["libraries"]:
        if not rules_allow(lib.get("rules")):
            continue
        art = lib.get("downloads", {}).get("artifact")
        if art:
            dest = os.path.join(root, "libraries", art["path"])
            jobs.append((art["url"], dest, art["sha1"]))
            if ":natives-" in lib["name"]:
                natives.append(dest)
    with ThreadPoolExecutor(16) as ex:
        list(ex.map(lambda j: fetch(*j), jobs))

    ndir = os.path.join(vdir, "natives")
    os.makedirs(ndir, exist_ok=True)
    for jar in natives:
        with zipfile.ZipFile(jar) as z:
            for n in z.namelist():
                if n.endswith((".dylib", ".jnilib")) and not n.startswith("META-INF"):
                    out = os.path.join(ndir, os.path.basename(n))
                    if not os.path.exists(out):
                        with z.open(n) as src, open(out, "wb") as dst:
                            dst.write(src.read())

    ai = vjson["assetIndex"]
    idx_path = fetch(ai["url"], os.path.join(root, "assets", "indexes", f"{ai['id']}.json"), ai["sha1"])
    objects = json.load(open(idx_path))["objects"]
    ajobs = []
    for obj in objects.values():
        h = obj["hash"]
        ajobs.append((f"{RESOURCES}/{h[:2]}/{h}", os.path.join(root, "assets", "objects", h[:2], h), h))
    with ThreadPoolExecutor(32) as ex:
        list(ex.map(lambda j: fetch(*j), ajobs))
    print(f"vanilla {version}: {len(jobs)} libraries, {len(ajobs)} assets")

    download_runtime(vjson["javaVersion"]["component"], root)


def download_fabric(version, loader, root):
    profile = get_json(f"{FABRIC_META}/versions/loader/{version}/{loader}/profile/json")
    pid = profile["id"]
    pdir = os.path.join(root, "versions", pid)
    os.makedirs(pdir, exist_ok=True)
    json.dump(profile, open(os.path.join(pdir, f"{pid}.json"), "w"), indent=2)
    for lib in profile["libraries"]:
        path = maven_path(lib["name"])
        url = lib.get("url", "https://maven.fabricmc.net/").rstrip("/") + "/" + path
        fetch(url, os.path.join(root, "libraries", path), lib.get("sha1"))
    print(f"fabric profile {pid}")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "vanilla":
        download_vanilla(sys.argv[2], os.path.abspath(sys.argv[3]))
    elif cmd == "fabric":
        download_fabric(sys.argv[2], sys.argv[3], os.path.abspath(sys.argv[4]))
