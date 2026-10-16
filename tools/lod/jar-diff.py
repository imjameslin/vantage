#!/usr/bin/env python3
"""List entries whose bytes differ between two client jars (mesh-cache identity check: only
LodMeshCacheContext.NON_MESH_CODE_FAMILIES classes may differ for a warm cache)."""
import hashlib, sys, zipfile


def digests(path):
    z = zipfile.ZipFile(path)
    return {n: hashlib.sha1(z.read(n)).hexdigest() for n in z.namelist() if not n.endswith('/')}


a, b = digests(sys.argv[1]), digests(sys.argv[2])
for name in sorted(set(a) | set(b)):
    if a.get(name) != b.get(name):
        print(name)
