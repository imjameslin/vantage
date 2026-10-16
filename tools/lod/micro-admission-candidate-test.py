#!/usr/bin/env python3
"""CPU math reference for micro-admission-candidate.metal; no shader compilation.

Run: python3 -B tools/lod/micro-admission-candidate-test.py
Integer exhaustive oracles include interior grid points, not just the helper's
corner cases. Binary32 interval endpoints are checked against exact rationals.
This tests the conditional math; it does not establish a native coordinate bound
or Metal compiler behavior, measure admission, or certify depth interpolation.
"""
from fractions import Fraction
from itertools import product
import math
import random
import struct
import unittest


ORDER = ((0, 1, 2), (2, 3, 0))


def f32(x):
    return struct.unpack('<f', struct.pack('<f', x))[0]


def adjacent(x, upward):
    bits = struct.unpack('<I', struct.pack('<f', x))[0]
    if bits & 0x7fffffff == 0:
        bits = 1 if upward else 0x80000001
    else:
        bits += 1 if bool(bits & 0x80000000) != upward else -1
    return struct.unpack('<f', struct.pack('<I', bits))[0]


def snap_interval(x, radius):
    s, r = f32(x * 256), f32(radius * 256)
    lower = adjacent(f32(adjacent(f32(s-r), False) - .5), False)
    upper = adjacent(f32(adjacent(f32(s+r), True) + .5), True)
    return math.ceil(lower), math.floor(upper)


def corners(box):
    return tuple(product(*[sorted(set(axis)) for axis in box]))


def points(box):
    return tuple(product(*[range(lo, hi+1) for lo, hi in box]))


def edge(a, b, p):
    return (b[0]-a[0])*(p[1]-a[1]) - (b[1]-a[1])*(p[0]-a[0])


def edge_pass(a, b, p):
    e = edge(a, b, p)
    return e > 0 or (e == 0 and (b[1] < a[1] or (b[1] == a[1] and b[0] > a[0])))


def edge_class(a, b, p):
    values = [edge_pass(x, y, p) for x, y in product(corners(a), corners(b))]
    return all(values), not any(values)


def area_range(boxes):
    triangles = tuple(product(*(corners(b) for b in boxes)))
    areas = [edge(a, b, c) for a, b, c in triangles]
    max_edge_squared = max(sum((b[k]-a[k])**2 for k in (0, 1))
                           for t in triangles for a, b in ((t[0], t[1]), (t[1], t[2]), (t[2], t[0])))
    return min(areas), max(areas), max_edge_squared


def sample_domain(boxes):
    lower = [min(b[k][0] for b in boxes) for k in (0, 1)]
    upper = [max(b[k][1] for b in boxes) for k in (0, 1)]
    # Unclamped infinite viewport is a stronger oracle for these interior cases.
    return tuple(product(*(range(-(-(lo-128)//256), (hi-128)//256+1)
                           for lo, hi in zip(lower, upper))))


def classify(boxes):
    """Integer portion of candidate(), assuming valid clip, size and original fronts."""
    samples = sample_domain(boxes)
    masks = [set(), set()]
    reasons = set()
    for t, order in enumerate(ORDER):
        a, b, c = [boxes[i] for i in order]
        amin, amax, max_edge_squared = area_range((a, b, c))
        if amax <= 0:
            continue
        for s in samples:
            p = tuple(v*256+128 for v in s)
            edges = (edge_class(b, c, p), edge_class(c, a, p), edge_class(a, b, p))
            if any(outside for _, outside in edges):
                continue
            if amin <= 0:
                reasons.add('orientation')
            if amin > 0 and amin*amin < 1024*max_edge_squared:
                reasons.add('sliver')
            if amin <= 0 or not all(inside for inside, _ in edges):
                reasons.add('coverage')
            else:
                masks[t].add(s)
    return ('hardware' if reasons else 'compute' if any(masks) else 'empty'), masks, reasons


def oracle(vertices, samples):
    masks = [set(), set()]
    for t, indices in enumerate(ORDER):
        a, b, c = [vertices[i] for i in indices]
        if edge(a, b, c) <= 0:
            continue
        for s in samples:
            p = tuple(v*256+128 for v in s)
            if all(edge_pass(x, y, p) for x, y in ((b, c), (c, a), (a, b))):
                masks[t].add(s)
    return masks


def fixed(vertices):
    return [((x, x), (y, y)) for x, y in vertices]


class AdmissionMathTest(unittest.TestCase):
    def assert_sound(self, boxes):
        route, masks, _ = classify(boxes)
        if route == 'hardware':
            return
        for vertices in product(*(points(b) for b in boxes)):
            actual = oracle(vertices, sample_domain(boxes))
            self.assertEqual(actual, masks, (route, boxes, vertices))
            if route == 'empty':
                self.assertFalse(any(actual))

    def test_outward_binary32_bounds_include_exact_nearest_choices(self):
        rng = random.Random(19)
        cases = [(f32((n+.5)/256), f32(r))
                 for n in (0, 1, 127, 255, 262144, 774143, 4194303)
                 for r in (0, 1/8192, 1/256)]
        cases += [(f32(rng.uniform(0, 16384)), f32(rng.uniform(0, 1/256))) for _ in range(1000)]
        for x, r in cases:
            lo, hi = snap_interval(x, r)
            exact_lo = math.ceil((Fraction(x)-Fraction(r))*256-Fraction(1, 2))
            exact_hi = math.floor((Fraction(x)+Fraction(r))*256+Fraction(1, 2))
            self.assertLessEqual(lo, exact_lo)
            self.assertGreaterEqual(hi, exact_hi)
        self.assertEqual(snap_interval(.5/256, 0), (0, 1))

    def test_corner_edge_certificates_cover_interior_grid_points(self):
        rng = random.Random(47)
        for _ in range(600):
            boxes = [tuple((v, v+rng.randrange(3)) for v in (rng.randrange(-3, 4), rng.randrange(-3, 4))) for _ in range(2)]
            p = (rng.randrange(-3, 4), rng.randrange(-3, 4))
            inside, outside = edge_class(*boxes, p)
            actual = [edge_pass(a, b, p) for a, b in product(*(points(b) for b in boxes))]
            if inside:
                self.assertTrue(all(actual))
            if outside:
                self.assertFalse(any(actual))

    def test_area_and_altitude_bounds_cover_interior_grid_points(self):
        rng = random.Random(71)
        for _ in range(80):
            boxes = [tuple((v, v+rng.randrange(3)) for v in (rng.randrange(-3, 4), rng.randrange(-3, 4))) for _ in range(3)]
            amin, amax, max_edge_squared = area_range(boxes)
            for a, b, c in product(*(points(b) for b in boxes)):
                self.assertLessEqual(amin, edge(a, b, c))
                self.assertGreaterEqual(amax, edge(a, b, c))
                for x, y in ((a, b), (b, c), (c, a)):
                    self.assertGreaterEqual(max_edge_squared, sum((y[k]-x[k])**2 for k in (0, 1)))

    def test_midpoint_vertex_can_be_admitted_when_samples_stable(self):
        boxes = fixed(((0, 0), (384, 0), (384, 320), (0, 320)))
        boxes[0] = ((0, 1), (0, 1))
        self.assertEqual(classify(boxes)[0], 'compute')
        self.assert_sound(boxes)

    def test_midpoint_edge_through_sample_requires_hardware(self):
        boxes = fixed(((0, 0), (128, 0), (128, 256), (0, 256)))
        boxes[1] = ((127, 129), (0, 0))
        boxes[2] = ((127, 129), (256, 256))
        self.assertIn('coverage', classify(boxes)[2])
        outcomes = {tuple(tuple(sorted(m)) for m in oracle(v, sample_domain(boxes)))
                    for v in product(*(points(b) for b in boxes))}
        self.assertGreater(len(outcomes), 1)

    def test_empty_sliver_and_covered_sliver_are_different_routes(self):
        empty = fixed(((0, 0), (400, 0), (400, 2), (0, 2)))
        covered = fixed(((0, 127), (400, 127), (400, 129), (0, 129)))
        self.assertEqual(classify(empty)[0], 'empty')
        self.assert_sound(empty)
        self.assertIn('sliver', classify(covered)[2])
        self.assertEqual(classify(covered)[0], 'hardware')

    def test_box_contains_sample_but_triangle_union_is_empty(self):
        boxes = fixed(((120, 0), (256, 136), (254, 138), (118, 2)))
        self.assertTrue(sample_domain(boxes))
        self.assertEqual(classify(boxes)[0], 'empty')
        self.assert_sound(boxes)

    def test_exact_diagonal_tie_has_one_owner_and_uncertain_tie_is_rejected(self):
        boxes = fixed(((0, 0), (256, 0), (256, 256), (0, 256)))
        route, masks, _ = classify(boxes)
        self.assertEqual(route, 'compute')
        self.assertEqual(sum(len(m) for m in masks), 1)
        self.assert_sound(boxes)
        boxes[0] = ((0, 1), (0, 1))
        self.assertIn('coverage', classify(boxes)[2])

    def test_empty_box_near_sample_boundary_cannot_hide_possible_coverage(self):
        boxes = fixed(((110, 110), (127, 110), (127, 145), (110, 145)))
        self.assertEqual(classify(boxes)[0], 'empty')
        boxes[1] = ((127, 129), (110, 110))
        boxes[2] = ((127, 129), (145, 145))
        self.assertNotEqual(classify(boxes)[0], 'empty')

    def test_random_admission_certificates_against_exhaustive_raster(self):
        rng = random.Random(97)
        admitted = 0
        for _ in range(150):
            x, y = rng.randrange(256), rng.randrange(256)
            w, h = rng.randrange(1, 400), rng.randrange(1, 400)
            boxes = fixed(((x, y), (x+w, y), (x+w, y+h), (x, y+h)))
            for i in rng.sample(range(4), 2):
                boxes[i] = tuple((lo, hi+rng.randrange(3)) for lo, hi in boxes[i])
            admitted += classify(boxes)[0] != 'hardware'
            self.assert_sound(boxes)
        self.assertGreater(admitted, 50)


if __name__ == '__main__':
    unittest.main()
