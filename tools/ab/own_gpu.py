import csv, sys, statistics as st
rows = list(csv.DictReader(open(sys.argv[1])))
prevEnd = None
by = {}
for r in rows:
    s = float(r['gpuStartNs']); e = float(r['gpuEndNs'])
    if prevEnd is not None and e > 0:
        own = e - max(s, prevEnd)
        by.setdefault((r['presentKind'], int(r['encoders']) > 2), []).append(own / 1e6)
    prevEnd = e
for k, v in sorted(by.items()):
    print(k, len(v), 'own p50 %.3f mean %.3f' % (st.median(v), st.mean(v)))
