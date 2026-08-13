#!/usr/bin/env python3
"""Evaluate a complete decoded development relay capture without using truth for decisions."""
import argparse, json, subprocess
import hashlib
from pathlib import Path
import numpy as np

W, H, FRAMES = 192, 128, 104
MASK = np.array([8, 8, 12], dtype=np.int16)

def decode(path: Path) -> np.ndarray:
    data = subprocess.check_output(["ffmpeg", "-v", "error", "-i", str(path),
        "-map", "0:v:0", "-fps_mode", "passthrough", "-pix_fmt", "rgb24",
        "-f", "rawvideo", "-"])
    assert len(data) % (W * H * 3) == 0
    return np.frombuffer(data, np.uint8).reshape((-1, H, W, 3))

MAX_PRIMING_FRAMES = 160

def frame_signature(frame):
    return hashlib.sha256(memoryview(frame).tobytes()).digest()

def evaluation_segment(captured, reference):
    """Find the exact evaluation suffix by its known sanitized-frame signatures."""
    if len(reference) != FRAMES:
        raise AssertionError(f"expected {FRAMES} reference frames, got {len(reference)}")
    priming_frames = len(captured) - FRAMES
    if priming_frames < 1 or priming_frames > MAX_PRIMING_FRAMES:
        raise AssertionError(f"invalid bounded priming frame count {priming_frames}")
    segment = captured[priming_frames:]
    if any(frame_signature(actual) != frame_signature(expected)
           for actual, expected in zip(segment, reference)):
        raise AssertionError("captured evaluation suffix does not match the sanitized signature")
    return segment, priming_frames

def bounds(poly):
    xs=[p[0] for p in poly]; ys=[p[1] for p in poly]
    return min(xs),min(ys),max(xs),max(ys)

def main():
    p=argparse.ArgumentParser(); p.add_argument('--manifest',type=Path,required=True)
    p.add_argument('--truth-root',type=Path,required=True); p.add_argument('--media-root',type=Path,required=True)
    p.add_argument('--findings',type=Path,required=True); p.add_argument('--capture',type=Path,required=True)
    p.add_argument('--sanitized-reference',type=Path,required=True)
    p.add_argument('--json',type=Path,required=True); a=p.parse_args()
    findings={(r['fixtureId'],r['frameIndex']):r for r in map(json.loads,a.findings.read_text().splitlines())}
    captured=decode(a.capture)
    # Index zero is the explicit non-evaluated sanitized priming IDR. The complete indexed
    # evaluation segment begins at the immediately following fresh IDR/config boundary.
    reference=decode(a.sanitized_reference)
    capture, priming_frames=evaluation_segment(captured, reference)
    rows=[]; offset=0
    manifests=[json.loads(x) for x in a.manifest.read_text().splitlines() if x.strip()]
    for m in manifests:
        if m['split']!='DEVELOPMENT': continue
        source=decode(a.media_root/m['sourcePath']); assert len(source)==8
        truths=[json.loads(x) for x in (a.truth_root/m['truthPath']).read_text().splitlines()]
        protected=exposed=0; max_raw=0.; min_mask=1.
        for i,t in enumerate(truths):
            target=max((o for o in t['objects'] if o['protectable']),key=lambda o:(bounds(o['polygon'])[2]-bounds(o['polygon'])[0])*(bounds(o['polygon'])[3]-bounds(o['polygon'])[1]))
            l,top,r,b=bounds(target['polygon']); x0=int(np.ceil(l*W));x1=int(np.floor(r*W));y0=int(np.ceil(top*H));y1=int(np.floor(b*H))
            out=capture[offset+i,y0:y1,x0:x1].astype(np.int16); raw=source[i,y0:y1,x0:x1].astype(np.int16)
            raw_ratio=float(np.all(np.abs(out-raw)<=12,axis=2).mean()); mask_ratio=float(np.all(np.abs(out-MASK)<=28,axis=2).mean())
            max_raw=max(max_raw,raw_ratio); min_mask=min(min_mask,mask_ratio)
            has=bool(findings[(m['fixtureId'],i)]['findings']); safe=raw_ratio<=.10 and mask_ratio>=.90
            assert safe==has, (m['fixtureId'],i,raw_ratio,mask_ratio,has)
            protected+=safe; exposed+=not safe
        rows.append({'fixtureId':m['fixtureId'],'lane':m['scenarioIds'][0],'category':m['scenarioIds'][1],
            'protected':protected,'exposed':exposed,'maxRawMatchRatio':max_raw,'minMaskRatio':min_mask})
        offset+=8
    assert offset==FRAMES
    a.json.write_text(json.dumps({'primingFramesExcluded':priming_frames,'frames':FRAMES,'rows':rows},indent=2)+"\n")
    print(f"validated relay frames={FRAMES} fixtures={len(rows)}")
if __name__=='__main__': main()
