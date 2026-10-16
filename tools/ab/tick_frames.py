"""Round 7 (results/ROUND7.md 1): own GPU time of tick frames vs plain frames from frame-trace JSONs.

Rows: the frame that ran a client tick ("ticks>0 frame"), the next frame (its command buffer carries the tick's texture
passes: "frame after"), the one after that, and plain frames (all others). Own GPU time = GPU end - max(GPU start,
previous frame's GPU end); cad = GPU end - previous GPU end.

usage: python3 tools/ab/tick_frames.py results/raw/LABEL.json [...]
"""
import json
import statistics as st
import sys


def main(paths):
    for f in paths:
        d = json.load(open(f))
        ft = d['frameTrace']
        cols = ft['columns']
        rows = [dict(zip(cols, r)) for r in ft['rows']]
        prev_end = None
        for r in rows:
            s, e = r['gpuStartNs'], r['gpuEndNs']
            r['own'] = (e - max(s, prev_end)) if prev_end else None
            r['cad'] = (e - prev_end) if prev_end else None
            prev_end = e

        def summ(sel, name):
            xs = [r for r in sel if r['own'] is not None]
            if not xs:
                print(' ', name, 'none')
                return

            def m(k):
                return st.mean(r[k] for r in xs)

            print(f"  {name}: n={len(xs)} own={m('own') / 1e3:.0f}us cad={m('cad') / 1e3:.0f}us busy={m('gpuBusyNs') / 1e3:.0f} "
                  f"enc={m('encoders'):.2f} draws={m('draws'):.0f} cbs={m('cbs'):.2f} blocks={m('blocksCreated'):.2f} "
                  f"presented={sum(1 for r in xs if r['presentKind'] == 1) / len(xs):.2f}")

        print(f, d['medianFps'], d['p1LowFps'])
        tick = set(i for i, r in enumerate(rows) if r['ticks'] > 0)
        summ([rows[i] for i in tick], 'ticks>0 frame')
        summ([rows[i + 1] for i in tick if i + 1 < len(rows)], 'frame after')
        summ([rows[i + 2] for i in tick if i + 2 < len(rows)], 'frame after+1')
        summ([r for i, r in enumerate(rows) if i not in tick and i - 1 not in tick and i - 2 not in tick], 'plain')


if __name__ == '__main__':
    main(sys.argv[1:])
