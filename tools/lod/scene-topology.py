#!/usr/bin/env python3
"""Frozen-scene exact draw-topology sidecar, quality first, then GPU milliseconds.

Creates diagnostic transformed shader copies; never changes production exports.
Both candidates retain the original 012/230 triangles and every source attribute.
"""
import argparse
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True

def sha(p):
    h = hashlib.sha256()
    with p.open('rb') as f:
        for part in iter(lambda: f.read(1 << 20), b''):
            h.update(part)
    return h.hexdigest()

def replace_once(text, before, after):
    if text.count(before) != 1:
        raise ValueError('Shader transform expected a unique match: ' + before)
    return text.replace(before, after)

def transform(text, kind):
    signature = 'vertex main0_out main0(uint mcmtl_vid [[vertex_id]],'
    fetch = '    uint mcmtl_e = mcmtl_quads[mcmtl_vid >> 2u];'
    if kind == 'instanced':
        edits = [(signature, signature + ' uint mcmtl_iid [[instance_id]],'),
                 (fetch, '    uint mcmtl_e = mcmtl_quads[mcmtl_iid];')]
    elif kind == 'procedural':
        edits = [(signature, signature.replace('mcmtl_vid', 'mcmtl_physical')),
                 ('    main0_out out = {};',
                  '    main0_out out = {};\n'
                  '    const uint mcmtl_corner[6] = {0u, 1u, 2u, 2u, 3u, 0u};\n'
                  '    uint mcmtl_vid = (mcmtl_physical / 6u) * 4u + mcmtl_corner[mcmtl_physical % 6u];')]
    else:
        raise ValueError(kind)
    changed = text
    for before, after in edits:
        changed = replace_once(changed, before, after)
    # Byte-for-byte inverse proves all functions, shading arithmetic, outputs,
    # bindings and fetch-body attribute decoding outside these exact edits stay.
    restored = changed
    for before, after in reversed(edits):
        restored = replace_once(restored, after, before)
    if restored != text or changed == text:
        raise ValueError('Shader transform round trip failed')
    # Original mcmtl_vid appears only at declaration, quad lookup and corner.
    # Fail closed if future exports add unrelated uses that instancing changes.
    # Current shaders have 3 occurrences: signature, lookup, corner.
    if text.count('mcmtl_vid') != 3:
        raise ValueError('Unreviewed logical vertex ID use')
    return changed, [{'before': b, 'after': a} for b, a in edits]

def freeze_shaders(source, root):
    manifest = json.loads((source/'manifest.json').read_text())
    if manifest.get('schema') != 'farsight-snapshot-shaders-1' or not manifest.get('complete'):
        raise ValueError('Complete production shader export required')
    original = root/'original'
    original.mkdir()
    shutil.copy2(source/'manifest.json', original/'manifest.json')
    for p in manifest['pipelines']:
        for kind in ('vertex', 'fragment', 'descriptor'):
            name = Path(p[kind+'File'])
            if name.is_absolute() or '..' in name.parts:
                raise ValueError('Shader path escape')
            if sha(source/name) != p[kind+'Sha256']:
                raise ValueError('Changed production shader/descriptor: ' + str(name))
            dest = original/name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source/name, dest)
    tests = []
    for kind in ('instanced', 'procedural'):
        dest = root/kind
        shutil.copytree(original, dest)
        changed = copy.deepcopy(manifest)
        changed['schema'] = 'farsight-diagnostic-topology-shaders-1'
        changed['NOT_PRODUCTION_EXPORT'] = True
        changed['topology'] = kind
        changed['originalManifestSha256'] = sha(original/'manifest.json')
        for p, old in zip(changed['pipelines'], manifest['pipelines']):
            path = dest/p['vertexFile']
            text = path.read_text()
            converted, edits = transform(text, kind)
            path.write_text(converted)
            p['vertexSha256'] = sha(path)
            # Numeric PSO/descriptor, fragment source and all other manifest
            # fields must be unchanged. Only vertex hash and root labels differ.
            restored = dict(p, vertexSha256=old['vertexSha256'])
            if restored != old:
                raise ValueError('Unintended pipeline state edit')
            for x in ('fragment','descriptor'):
                if sha(dest/p[x+'File']) != old[x+'Sha256']:
                    raise ValueError('Fragment or numeric descriptor changed')
            tests.append({'kind':kind,'layerCode':p['layerCode'], 'exactEdits':edits,
                          'originalVertexSha256':old['vertexSha256'],
                          'transformedVertexSha256':p['vertexSha256'],
                          'roundTripExact':True,'fragmentDescriptorAndPipelineStateExact':True})
        (dest/'manifest.json').write_text(json.dumps(changed, indent=2)+'\n')
    if len(tests) != 24:
        raise ValueError('Expected all 12 production pipeline codes in each candidate')
    (root/'transform-tests.json').write_text(json.dumps({'passed':True,'shaderTransformCases':len(tests),
        'roundTripChecks':len(tests),'tests':tests},indent=2)+'\n')
    return manifest

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('snapshot', type=Path)
    parser.add_argument('shaders', type=Path)
    parser.add_argument('--quality-only', action='store_true')
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--locked', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not args.prepare_only and not args.locked:
        cmd = [sys.executable, str(ROOT/'tools/gpulock.py'), 'timing', '--label',
               'farsight-draw-topology', '--', sys.executable, str(Path(__file__).resolve()),
               str(args.snapshot.resolve()), str(args.shaders.resolve()), '--locked']
        if args.quality_only:
            cmd.append('--quality-only')
        os.execv(sys.executable, cmd)
    parent = ROOT/'build/lod-scene-topology'
    parent.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix='run-', dir=parent))
    print('Topology artifacts:',out,flush=True)
    for name in ('source','shaders','tmp','module-cache'):
        (out/name).mkdir()
    for name in ('lod_scene_topology.mm','lod_scene_replay.mm','lod_scene_data.h'):
        shutil.copy2(ROOT/'native/tests'/name,out/'source'/name)
    for name in ('scene-topology.py','scene-replay.py'):
        shutil.copy2(ROOT/'tools/lod'/name,out/'source'/name)
    shutil.copy2(ROOT/'tools/PerceptualDiff.java',out/'source/PerceptualDiff.java')
    # Reuse the frozen existing read-only verification/guard helpers.
    spec = importlib.util.spec_from_file_location('frozen_replay', out/'source/scene-replay.py')
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    manifest = freeze_shaders(args.shaders.resolve(), out/'shaders')
    env = dict(os.environ, TMPDIR=str(out/'tmp')+'/')
    command = ['xcrun','--sdk','macosx','clang++','-std=c++17','-fobjc-arc','-O2','-Wall','-Wextra','-Werror',
               '-fmodules-cache-path='+str(out/'module-cache'),'-framework','Foundation','-framework','Metal',
               '-framework','CoreGraphics','-framework','ImageIO','-lz',str(out/'source/lod_scene_topology.mm'),
               '-o',str(out/'topology')]
    subprocess.run(command,env=env,check=True,timeout=60)
    sources={str(f.relative_to(out)):sha(f) for d in ('source','shaders') for f in (out/d).rglob('*') if f.is_file()}
    if args.prepare_only:
        (out/'prepare.json').write_text(json.dumps({'compiled':True,'sourceSha256':sources},indent=2)+'\n')
        print('Prepare-only compile and 24 shader transform tests passed. No GPU device/run.',flush=True)
        return
    start = helper.lock_receipt()
    helper.user_game_guard()
    snapshot = args.snapshot.resolve()
    verification = helper.verify(snapshot)
    receipt = json.loads((snapshot/'receipt.json').read_text())
    prov = manifest['provenance']
    if (prov['captureManifestSha256'] != verification['manifestSha256'] or
            prov['clientSha256'] not in receipt['jarSha256'].values()):
        raise ValueError('Shader/client/snapshot provenance mismatch')
    options = manifest['options']
    if options['mathMode'] != 'fast' or options['languageVersion'] != 196610 or options['sampleCount'] != 1 or options['alphaToCoverage']:
        raise ValueError('Unsupported original shader options')
    (out/'input.json').write_text(json.dumps({'snapshot':str(snapshot),'verification':verification,
        'captureReceiptSha256':sha(snapshot/'receipt.json'),'sourceSha256':sources,'lockStart':start,
        'scope':'Diagnostic transformed vertex shader copies, NOT production exports. Original source/input/export hashes retained.'},indent=2)+'\n')
    modes=['quality'] if args.quality_only else ['quality','timing']
    for mode in modes:
        helper.user_game_guard()
        dest=out/mode
        dest.mkdir()
        env['MTL_DEBUG_LAYER']='1' if mode=='quality' else '0'
        t=time.monotonic()
        with (dest/'run.log').open('w') as log:
            try:
                proc=subprocess.run([str(out/'topology'),str(snapshot),str(out/'shaders'),str(dest),mode],
                    env=env,stdout=log,stderr=subprocess.STDOUT,timeout=180)
                code=proc.returncode
            except subprocess.TimeoutExpired:
                code=124
        path=dest/'result.json'
        result=json.loads(path.read_text()) if path.exists() else {}
        result.update(executableExitCode=code,wrapperWallSeconds=time.monotonic()-t,
                      MetalAPIValidation=mode=='quality',lockStart=start,lockEnd=helper.lock_receipt())
        if code:
            result.update(status='failed',reason='See run.log; native process failed or timed out')
        path.write_text(json.dumps(result,indent=2,sort_keys=True)+'\n')
        print((dest/'run.log').read_text(),end='',flush=True)
        if code or result.get('status') != 'passed':
            raise SystemExit(code if code>0 else 1)
        if mode=='quality':
            java=ROOT/'mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java'
            checks=[]
            for candidate in ('instanced-quad','procedural-six'):
                with (dest/(candidate+'-perceptual.log')).open('w') as log:
                    p=subprocess.run([str(java),'-Xmx2g','-Djava.awt.headless=true',str(out/'source/PerceptualDiff.java'),
                                      str(dest/'indexed-original'),str(dest/candidate)],env=env,
                                     stdout=log,stderr=subprocess.STDOUT,timeout=150)
                checks.append({'candidate':candidate,'exitCode':p.returncode,
                               'log':str(dest/(candidate+'-perceptual.log'))})
                print((dest/(candidate+'-perceptual.log')).read_text(),end='',flush=True)
            result['perceptualDiff']=checks
            if any(c['exitCode'] for c in checks):
                result['status']='failed'
            path.write_text(json.dumps(result,indent=2,sort_keys=True)+'\n')
            if result['status']!='passed':
                raise SystemExit('PerceptualDiff failed; timing not run')
        print('Result:',path,flush=True)
    # Source/export stability checks after the serial pair; original inputs are
    # read only and the native loader independently checked every geometry stream.
    final={str(f.relative_to(out)):sha(f) for d in ('source','shaders') for f in (out/d).rglob('*') if f.is_file()}
    if final != sources:
        raise ValueError('Frozen source/shader artifacts changed during run')
    (out/'receipt.json').write_text(json.dumps({'complete':True,'lockEnd':helper.lock_receipt(),
        'frozenSourcesStable':True,'executableSha256':sha(out/'topology'),
        'originalExportManifestSha256':sha(args.shaders.resolve()/'manifest.json')},indent=2)+'\n')

if __name__=='__main__':
    main()
