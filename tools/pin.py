#!/usr/bin/env python3
"""Pinned, read-only snapshots for the parity harness (docs/metal/CONTRACT.md 11.6).

A pinned directory holds a SHA256SUMS manifest (`shasum -a 256` format, sorted by path) of every other file in it and is
made read-only (chmod a-w), so a game run, a script or an agent cannot change it by accident, and any change made on
purpose is detected by --verify (and by every launch.py restore).

  pin.py world <src game dir> <dst> [--world bench] [--gamerule name=int ...]
                                                      pin <src>/saves/<world>/ and <src>/options.txt (session.lock skipped),
                                                      with these integer game rules set in the copy's game_rules.dat
  pin.py shots <src shots dir> <dst>                  pin a reference: <src>/screenshots/ (scenes PNGs, or zoomseq/ with
                                                      its index.csv) -> <dst>/screenshots/
  pin.py --verify <dst>                               check a pinned directory against its manifest; prints its id

launch.py restores the world snapshot into the game dir before every scenes / zoomseq run (restore()).
"""
import argparse, gzip, hashlib, os, shutil, stat, struct, sys

MANIFEST = "SHA256SUMS"


def _files(root):
    out = []
    for d, _, names in os.walk(root):
        for n in names:
            rel = os.path.relpath(os.path.join(d, n), root)
            if rel != MANIFEST:
                out.append(rel)
    return sorted(out)


def _sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def _manifest_text(root):
    return "".join(f"{_sha(os.path.join(root, rel))}  {rel}\n" for rel in _files(root))


def snapshot_id(root):
    """First 16 hex digits of the manifest's own SHA-256: names the pinned content in logs and results."""
    with open(os.path.join(root, MANIFEST), "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()[:16]


def verify(root):
    """Raises ValueError unless every file under root is listed in its manifest with the same hash (and vice versa)."""
    path = os.path.join(root, MANIFEST)
    if not os.path.isfile(path):
        raise ValueError(f"{root}: no {MANIFEST} (not a pinned snapshot; create it with tools/pin.py)")
    with open(path) as f:
        expected = f.read()
    actual = _manifest_text(root)
    if actual != expected:
        exp = dict(l.split("  ", 1)[::-1] for l in expected.splitlines())
        act = dict(l.split("  ", 1)[::-1] for l in actual.splitlines())
        diff = sorted(k for k in set(exp) | set(act) if exp.get(k) != act.get(k))
        raise ValueError(f"{root}: content differs from {MANIFEST} ({len(diff)} files, e.g. {diff[:5]}); "
                         "the pinned snapshot was modified")
    return snapshot_id(root)


def _seal(root):
    with open(os.path.join(root, MANIFEST), "w") as f:
        f.write(_manifest_text(root))
    for d, dirs, names in os.walk(root, topdown=False):
        for n in names:
            p = os.path.join(d, n)
            os.chmod(p, stat.S_IMODE(os.stat(p).st_mode) & ~0o222)
        os.chmod(d, stat.S_IMODE(os.stat(d).st_mode) & ~0o222)
    return snapshot_id(root)


def _copy_tree(src, dst, skip=()):
    """Plain copy (data only, default modes: the copy of a read-only snapshot is writable)."""
    for d, _, names in os.walk(src):
        rel = os.path.relpath(d, src)
        os.makedirs(os.path.join(dst, rel), exist_ok=True)
        for n in names:
            if n not in skip:
                shutil.copyfile(os.path.join(d, n), os.path.join(dst, rel, n))


def _make_writable(root):
    for d, _, names in os.walk(root):
        os.chmod(d, stat.S_IMODE(os.stat(d).st_mode) | 0o700)
        for n in names:
            p = os.path.join(d, n)
            if not os.path.islink(p):
                os.chmod(p, stat.S_IMODE(os.stat(p).st_mode) | 0o600)


def set_int_gamerules(world_dir, rules):
    """Sets integer game rules ({"random_tick_speed": 0, ...}) in <world>/data/minecraft/game_rules.dat (26.3: gzipped NBT,
    compound "data" of "minecraft:<rule>" tags). Only existing TAG_Int values are overwritten in place, so the file's
    structure does not change."""
    path = os.path.join(world_dir, "data", "minecraft", "game_rules.dat")
    with gzip.open(path) as f:
        nbt = bytearray(f.read())
    for rule, value in rules.items():
        key = rule if ":" in rule else "minecraft:" + rule
        tag = b"\x03" + struct.pack(">H", len(key)) + key.encode()   # TAG_Int, name length, name
        at = nbt.find(tag)
        if at < 0 or nbt.find(tag, at + 1) >= 0:
            raise ValueError(f"{path}: no single TAG_Int {key}")
        at += len(tag)
        nbt[at:at + 4] = struct.pack(">i", value)
    with open(path, "wb") as f:
        f.write(gzip.compress(bytes(nbt), mtime=0))


def pin_world(src_game_dir, dst, world="bench", gamerules=None):
    if os.path.exists(dst):
        sys.exit(f"pin.py: {dst} exists (pinned snapshots are never overwritten; pick a new name)")
    _copy_tree(os.path.join(src_game_dir, "saves", world), os.path.join(dst, "saves", world), skip=("session.lock",))
    shutil.copyfile(os.path.join(src_game_dir, "options.txt"), os.path.join(dst, "options.txt"))
    if gamerules:
        set_int_gamerules(os.path.join(dst, "saves", world), gamerules)
    return _seal(dst)


def pin_shots(src_shots_dir, dst):
    if os.path.exists(dst):
        sys.exit(f"pin.py: {dst} exists (pinned snapshots are never overwritten; pick a new name)")
    _copy_tree(os.path.join(src_shots_dir, "screenshots"), os.path.join(dst, "screenshots"), skip=(".DS_Store",))
    return _seal(dst)


def restore(snapshot, game_dir, world="bench", options=True):
    """Replace <game_dir>/saves/<world>/ and (options=True) <game_dir>/options.txt with the verified snapshot's. Returns its id."""
    sid = verify(snapshot)
    src = os.path.join(snapshot, "saves", world)
    if not os.path.isdir(src):
        raise ValueError(f"{snapshot}: no saves/{world}")
    dst = os.path.join(game_dir, "saves", world)
    if os.path.lexists(dst):
        _make_writable(dst)   # (a half-finished restore or a copy made with cp -R from the read-only snapshot)
        shutil.rmtree(dst)
    _copy_tree(src, dst)
    if not options:
        return sid
    opts = os.path.join(game_dir, "options.txt")
    if os.path.lexists(opts):
        os.remove(opts)
    shutil.copyfile(os.path.join(snapshot, "options.txt"), opts)
    return sid


def main(argv):
    if len(argv) == 2 and argv[0] == "--verify":
        try:
            print(f"{argv[1]}: ok, id {verify(argv[1])}")
        except ValueError as e:
            sys.exit(f"pin.py: {e}")
        return
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("kind", choices=["world", "shots"])
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--world", default="bench")
    ap.add_argument("--gamerule", action="append", default=[], help="name=int, world only")
    a = ap.parse_args(argv)
    if a.kind == "world":
        rules = {k: int(v) for k, v in (r.split("=", 1) for r in a.gamerule)}
        print(f"{a.dst}: pinned, id {pin_world(a.src, a.dst, a.world, rules)}")
    else:
        print(f"{a.dst}: pinned, id {pin_shots(a.src, a.dst)}")


if __name__ == "__main__":
    main(sys.argv[1:])
