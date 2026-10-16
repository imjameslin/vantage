#!/usr/bin/env python3
"""Admit ready-reference motion inputs; this does not evaluate image quality or FPS."""
import json
import struct
import sys
from pathlib import Path


def check(manifest, result):
    d = json.loads(manifest.read_text())
    r = json.loads(result.read_text())
    assert d['captureComplete'] and not d['error'] and not d['diagnosticOnly'], 'capture incomplete, failed, or diagnostic-only'
    assert d['readySynchronousReference'] and not d['settledBoundaryReferenceOnly']
    assert d['capturedFramesByArm'] == [120, 120] and len(d['frames']) == 240
    assert d['referenceWaitRenders'] > 0 and d['pendingWrites'] == 0
    assert (d['width'], d['height'], d['downscale']) == (3024, 1964, 1)
    t = d['trajectorySpec']
    assert t['referenceReadinessWaits'] and t['runMeshWaits']
    assert not t['candidateRunMeshWaits'] and not t['candidateRunHolds']
    assert t['logicalFrameLightmapInputsBothArms']
    assert t['referenceLocalServerPositionSync'] and not t['candidateServerPositionSync']
    assert not t['forceBuild'] and not t['runWriterBackpressureHolds']
    assert t['logicalDtNanos'] == 6250000
    settings = d['settingsBothArms']
    assert settings['RADIUS'] == 512 and settings['TAU'] == 2 and settings['GPU_BYTES'] == 6144 * 1024**2
    assert settings['renderDistance'] == 12 and settings['clouds'] == 'OFF'
    for k in ['asyncEnabled', 'asyncRequested', 'asyncItems', 'imageReuseEnabled', 'boundaryProbe']:
        assert settings[k] is False, k
    env = d['configAtSetup']['env']
    assert str(env['MTL_DEBUG_LAYER']) == '0' and str(env['MTL_SHADER_VALIDATION']) == '0'
    display = r['launch']['display']
    assert display['pixels'] == '3024x1964' and display['hz'] == 120 and display['default']
    assert r['launch']['exit'] == 0 and r['reuseseq']['captureComplete']
    assert r['reuseseq']['handoffActivity'] == d['handoffActivity'] and d['handoffActivity']['activityObserved']
    off, on = d['frames'][:120], d['frames'][120:]
    for i, (a, b) in enumerate(zip(off, on)):
        assert a['frame'] == b['frame'] == i and a['arm'] == 'off' and b['arm'] == 'on'
        assert a['trajectoryTimeNanos'] == b['trajectoryTimeNanos'] == i * 6250000
        assert a['actualCamera'] == b['actualCamera'] and a['requestedPose'] == b['requestedPose']
        assert a['renderInputs'] == b['renderInputs'] and a['renderInputs']['frozenSpriteCount'] > 0
        assert a['renderInputs']['gameTime'] == 240000 and a['renderInputs']['worldPartial'] == 1
        for row in (a, b):
            for k in ['requestedCameraMatches', 'pairedCameraMatches', 'pairedClockMatches', 'pairedRenderInputsMatch']:
                assert row[k], (i, k)
            assert not row['heldSincePreviousFrame']
            clock = row['renderClock']
            assert clock['frame'] == 32 + i and clock['nanos'] == (32 + i) * 6250000
            assert clock['deltaTicks'] == 0.125 and clock['worldPartial'] == 1
            assert row['lodStateAtCaptureHook']['tau'] == 2
            path = manifest.parent / row['file']
            with path.open('rb') as f:
                head = f.read(24)
            assert head[:8] == b'\x89PNG\r\n\x1a\n' and struct.unpack('>II', head[16:24]) == (3024, 1964), path
        assert {k:v for k,v in a['renderClock'].items() if k != 'steps'} == {k:v for k,v in b['renderClock'].items() if k != 'steps'}
        assert b['renderClock']['steps'] == b['renderClock']['frame'] + 1  # No held or extra candidate renders.
        assert a['renderClock']['steps'] > b['renderClock']['steps']
        assert not a['lodStateAtCaptureHook']['refusedOverBudget'] and not a['lodStateAtCaptureHook']['pendingMeshes']
        for key in ['referenceReadiness', 'referenceReadinessAfterRender']:
            ready = a[key]
            assert ready['queueEmpty'] and ready['loadedNonAir'] > 0 and ready['graphReady']
            assert ready['dirty'] == ready['replacements'] == 0
            assert not ready['uncompiled'] and not ready['uploadPending'] and not ready['fading'] and not ready['missing']
        for row, active in ((a, False), (b, True)):
            c = row['handoffCountersAfterRender']
            assert c['requested'] == active and c['active'] == active
        assert b['handoffCountersAfterRender']['coveredSections'] > 0
    return {'inputAdmission': 'PASS', 'pairedFrames': 120, 'candidateHeldTimerCalls': 0,
            'referenceWaitRenders': d['referenceWaitRenders'], 'imageGate': 'NOT EVALUATED', 'fps': 'NOT MEASURED'}


if __name__ == '__main__':
    try:
        print(json.dumps(check(Path(sys.argv[1]), Path(sys.argv[2])), indent=2))
    except (AssertionError, KeyError, OSError, ValueError) as e:
        raise SystemExit('Ready-reference input admission FAILED: ' + str(e))
