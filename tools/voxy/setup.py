#!/usr/bin/env python3
"""Separate Voxy comparison install (stage 3 round 5, docs/QUEUE.md "Voxy comparison"): vanilla 26.2 + Fabric loader +
Fabric API + Sodium (26.2 build) + Voxy 0.2.19-beta, in mc/voxy-26.2 (git-ignored, inside the project folder). Never touches
the user's Prism instance or our 26.3 install (mc/versions, mc/libraries stay as they are; asset objects are hard-linked
from mc/assets when the hash matches, the rest is downloaded).

Sources (official only): Mojang's version manifest / piston-data / resources (tools/mcdl.py, sha1 checked), Fabric meta +
maven.fabricmc.net (sha1 from the profile when given), Modrinth API + CDN (sha1 + sha512 checked).

usage: tools/voxy/setup.py            (idempotent; prints the resulting layout)
"""
import hashlib, json, os, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import mcdl  # noqa: E402

VERSION = "26.2"
LOADER = "0.19.5"
DEST = os.path.join(ROOT, "mc", "voxy-26.2")
# Modrinth versions (project / version id / file), pinned. Voxy 0.2.19-beta's fabric.mod.json accepts Sodium 0.9.1 and 0.9.2;
# 0.9.2 is the newest 26.2 release.
MODS = [
    ("P7dR8mSH", "ewUK83HI", "fabric-api-0.161.0+26.2.jar"),
    ("AANobbMI", "xJZxADzI", "sodium-fabric-0.9.2+mc26.2.jar"),
    ("fxxUqruK", "LzyXnE51", "voxy-0.2.19-beta.jar"),
]


def link_assets():
    src = os.path.join(ROOT, "mc", "assets", "objects")
    dst = os.path.join(DEST, "assets", "objects")
    n = 0
    for d in os.listdir(src) if os.path.isdir(src) else []:
        for f in os.listdir(os.path.join(src, d)):
            t = os.path.join(dst, d, f)
            if not os.path.exists(t):
                os.makedirs(os.path.dirname(t), exist_ok=True)
                os.link(os.path.join(src, d, f), t)
                n += 1
    print(f"hard-linked {n} asset objects from mc/assets")


def mods():
    md = os.path.join(DEST, "mods")
    os.makedirs(md, exist_ok=True)
    manifest = {}
    for project, vid, fname in MODS:
        v = json.load(urllib.request.urlopen(f"https://api.modrinth.com/v2/version/{vid}", timeout=60))
        f = next(x for x in v["files"] if x["filename"] == fname)
        assert v["project_id"] == project
        local = os.path.join(ROOT, "mc", "fabric", "mods-render", fname)
        path = os.path.join(md, fname)
        if os.path.isfile(local) and mcdl.sha1(local) == f["hashes"]["sha1"] and not os.path.exists(path):
            os.link(local, path)
        mcdl.fetch(f["url"], path, f["hashes"]["sha1"])
        h = hashlib.sha512(open(path, "rb").read()).hexdigest()
        if h != f["hashes"]["sha512"]:
            os.remove(path)
            sys.exit(f"sha512 mismatch: {fname}")
        manifest[fname] = {"project": project, "versionId": vid, "version": v["version_number"], "url": f["url"],
                           "sha1": f["hashes"]["sha1"], "sha512": h, "gameVersions": v["game_versions"]}
        print("mod", fname, v["version_number"])
    json.dump(manifest, open(os.path.join(md, "manifest.json"), "w"), indent=1)


if __name__ == "__main__":
    os.makedirs(DEST, exist_ok=True)
    link_assets()
    mcdl.download_vanilla(VERSION, DEST)
    mcdl.download_fabric(VERSION, LOADER, DEST)
    mods()
    print("done:", DEST)
