#!/bin/bash
# Only the frozen jar's 12 LOD layerCodes. No client/native rebuild and no draws.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
if [[ $# != 0 ]]; then echo 'usage: bash tools/lod/snapshot-shaders.sh' >&2; exit 2; fi
OUT="$ROOT/build/lod-snapshot-shaders"
mkdir -p "$OUT"
RUN="$(mktemp -d "$OUT/run-XXXXXX")"
echo "Private export artifacts: $RUN"
python3 "$ROOT/tools/gpulock.py" timing --key "root:$ROOT:sh" --label 'Frozen Farsight snapshot shader export' -- \
  python3 -B - "$ROOT" "$RUN" <<'PY'
import hashlib, json, os, pathlib, signal, subprocess, sys, zipfile
root, out = map(pathlib.Path, sys.argv[1:])
jar = root/'build/client-farsight-direct-q1.jar'
scene = root/'build/lod-snapshots/farsight-forest-s2'
jdk = root/'mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home'
def digest(p, kind='sha256'):
    h=hashlib.new(kind)
    with p.open('rb') as f:
        for b in iter(lambda:f.read(1<<20), b''): h.update(b)
    return h.hexdigest()
capture=json.loads((scene/'complete.json').read_text())
assert capture['complete'] and digest(jar,'sha1')==capture['config']['jars']['client']['sha1'], 'Frozen jar does not match captured client'
before=digest(jar)
for name in ['classes','natives','run','tmp','source']: (out/name).mkdir()
sources=[root/'tools/LodSnapshotShaders.java',root/'tools/lod/snapshot-shaders.sh']
for p in sources: (out/'source'/p.name).write_bytes(p.read_bytes())
libs=sorted(p for p in (root/'mc/libraries').rglob('*.jar') if not (str(p).endswith('natives-macos.jar') or str(p).endswith('osx-x86_64.jar')))
cp=os.pathsep.join(map(str,[jar,*libs]))
env=dict(os.environ, TMPDIR=str(out/'tmp')+'/', MTL_DEBUG_LAYER='1')
# Refuse inherited Java options that could load another library/agent or change shader selection.
for k in ['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS']:
    if env.get(k): raise RuntimeError('Unset '+k+' for this isolated export')
def bounded(cmd, logname, seconds):
    with (out/logname).open('w') as log:
        p=subprocess.Popen(cmd,cwd=out/'run',env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        try: status=p.wait(timeout=seconds)
        except subprocess.TimeoutExpired:
            os.killpg(p.pid,signal.SIGKILL); p.wait(); raise RuntimeError(logname+' exceeded '+str(seconds)+' seconds')
    print((out/logname).read_text(),flush=True)
    if status: raise RuntimeError(logname+' exited '+str(status))
bounded([str(jdk/'bin/javac'),'--release','25','-proc:none','-sourcepath','','-cp',cp,'-d',str(out/'classes'),str(out/'source/LodSnapshotShaders.java')],'compile.log',30)
agent=out/'recorder.jar'
with zipfile.ZipFile(agent,'w') as z:
    z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: com.mojang.renderpearl.backend.metal.LodSnapshotShaders\n\n')
    for p in sorted((out/'classes').rglob('*.class')): z.write(p,str(p.relative_to(out/'classes')))
cmd=[str(jdk/'bin/java'),'--enable-native-access=ALL-UNNAMED','-ea','-Xms32m','-Xmx256m','-XX:MaxDirectMemorySize=128m','-XX:MaxMetaspaceSize=128m',
     '-Dorg.lwjgl.system.SharedLibraryExtractPath='+str(out/'natives'),'-javaagent:'+str(agent),'-cp',str(agent)+os.pathsep+cp,
     'com.mojang.renderpearl.backend.metal.LodSnapshotShaders',str(out),str(scene)]
with zipfile.ZipFile(jar) as z:
    names=['com/mojang/renderpearl/backend/metal/'+n+'.class' for n in ['MetalRenderPipeline','MetalRenderPipeline$Prepared','MetalRenderPipeline$ShaderCache','TerrainShaderVariants','TerrainVertexMsl','MetalFormats','MetalSampler','MslTranslator','MtlNative']]
    names+=['net/minecraft/client/renderer/RenderPipelines.class']
    classhash={n:hashlib.sha256(z.read(n)).hexdigest() for n in names}
    nativehash={n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.endswith('libmcmetal.dylib')}
(out/'inputs.json').write_text(json.dumps({'clientJar':str(jar),'clientSha256':before,'clientSha1':digest(jar,'sha1'),
    'captureManifest':str(scene/'complete.json'),'captureManifestSha256':digest(scene/'complete.json'),
    'sourcesSha256':{str(p.relative_to(root)):digest(out/'source'/p.name) for p in sources},
    'frozenClassSha256':classhash,'packagedNativeSha256':nativehash,'command':cmd},indent=2)+'\n')
# Check after any GPU-lane queueing, immediately before the Metal-compiling JVM.
try:
    user_game=subprocess.run(['pgrep','-f','[P]rismLauncher/instances/.*/bin/java '],stdout=subprocess.DEVNULL)
except OSError as e:
    raise SystemExit('Cannot verify user-game guard; shader export did not launch: '+str(e))
if user_game.returncode == 0:
    raise SystemExit('User Prism game is running; shader export did not launch')
if user_game.returncode != 1:
    raise SystemExit('Cannot verify user-game guard; shader export did not launch')
bounded(cmd,'run.log',60)
assert digest(jar)==before, 'Frozen client changed during export'
m=json.loads((out/'manifest.json').read_text())
assert m['complete'] and len(m['pipelines'])==12
for p in m['pipelines']:
    for stage in ['vertex','fragment']:
        assert digest(out.parent/p[stage+'File'])==p[stage+'Sha256']
    assert digest(out.parent/p['descriptorFile'])==p['descriptorSha256']
# Publish the fixed contract only after the complete bounded run and hash checks succeed.
pending=out.parent/'manifest.json.tmp'
pending.write_bytes((out/'manifest.json').read_bytes()); pending.replace(out.parent/'manifest.json')
print('Manifest: '+str(out.parent/'manifest.json'))
PY
