#!/usr/bin/env python3
"""Union GPU intervals by channel and measure overlap (avoids nested-interval double counting).
Usage: python3 tools/mst_overlap.py <metal-gpu-intervals.xml> [...]
"""
import json, sys, xml.etree.ElementTree as ET
from collections import defaultdict

def union(a):
 out=[]
 for s,e in sorted(a):
  if out and s<=out[-1][1]: out[-1]=(out[-1][0],max(e,out[-1][1]))
  else: out.append((s,e))
 return out

def duration(a): return sum(e-s for s,e in a)
def intersect(a,b):
 i=j=0; total=0
 while i<len(a) and j<len(b):
  total+=max(0,min(a[i][1],b[j][1])-max(a[i][0],b[j][0]))
  if a[i][1]<b[j][1]:i+=1
  else:j+=1
 return total

results={}
for path in sys.argv[1:]:
 ids={}; channels=defaultdict(list); allrows=[]
 for _,el in ET.iterparse(path,events=('end',)):
  if el.tag!='row':continue
  for c in el.iter():
   if 'id' in c.attrib:
    ids[c.attrib['id']]=c.attrib.get('fmt') if c.tag in ['process','formatted-label'] else (c.text or c.attrib.get('fmt'))
  def value(tag):
   c=el.find(tag)
   return ids.get(c.attrib.get('ref',c.attrib.get('id'))) if c is not None else None
  s=int(value('start-time')); e=s+int(value('duration')); allrows.append((s,e))
  if (value('process') or '').startswith('java ('):channels[value('gpu-channel-name')].append((s,e))
  el.clear()
 window=max(e for s,e in allrows)-min(s for s,e in allrows)
 channels={k:union(v) for k,v in channels.items()}
 busy=duration(union([x for v in channels.values() for x in v]))
 result={'windowMs':window/1e6,'javaBusyPercent':100*busy/window,'channelPercent':{k:100*duration(v)/window for k,v in channels.items()},'overlapPercent':{a+' + '+b:100*intersect(channels.get(a,[]),channels.get(b,[]))/window for a,b in [('Vertex','Compute'),('Vertex','Fragment'),('Compute','Fragment')]}}
 results[path]=result
print(json.dumps(results,indent=2))
