#!/usr/bin/env python3
"""Regression for repeated XML types and reference-only trace rows."""
import tempfile
import unittest
from pathlib import Path

from mst_passes import summarize


class TraceColumns(unittest.TestCase):
    def test_latency_and_surface_label_do_not_replace_interval_and_encoder(self):
        xml = '''<trace>
        <row><start-time id="1">100</start-time><duration id="2">50</duration>
          <gpu-channel-name id="3">Vertex</gpu-channel-name><gpu-frame-number>7</gpu-frame-number>
          <duration id="4">9000</duration>
          <formatted-label id="5" fmt="terrain 0xabc"><string id="6">nested</string></formatted-label>
          <process id="7" fmt="java (42)"><pid>42</pid></process>
          <formatted-label id="8" fmt="Write Surface: 999"/></row>
        <row><start-time>200</start-time><duration ref="2"/><gpu-channel-name ref="3"/>
          <gpu-frame-number>8</gpu-frame-number><duration ref="4"/>
          <formatted-label ref="5"/><process ref="7"/><formatted-label ref="8"/></row>
        <row><start-time>300</start-time><duration>400</duration><gpu-channel-name>Fragment</gpu-channel-name>
          <formatted-label fmt="unrelated"/><process fmt="WindowServer (9)"/></row>
        </trace>'''
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'trace.xml'
            path.write_text(xml)
            count, labels = summarize(path)
        self.assertEqual(count, 2)
        self.assertEqual(dict(labels), {'terrain': {'Vertex': 100}})


if __name__ == '__main__':
    unittest.main()
