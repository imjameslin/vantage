# How Voxy renders (study notes, 2026-10-07)

Source: the shaders inside voxy-0.2.19-beta.jar (mc/voxy-26.2/mods). Voxy is All-Rights-Reserved: these are notes in our own words
for understanding its technique; no Voxy code is copied into this project. Everything we build is our own implementation.

## Pipeline
1. **Data:** 32x32x32-voxel sections in a level-of-detail tree (level n = 2^n blocks per voxel). Each section is meshed on the CPU
   into greedy-merged **quads of 8 bytes** (64 bits): 5+5+5 bit position in the section, 4+4 bit width/height (merged up to 16x16),
   3 bit face, 16-20 bit block-state/model id, 9 bit biome, 8 bit light. Quads are stored grouped by face direction
   (translucent, double-sided, down, up, north, south, west, east).
2. **GPU tree traversal (compute, every frame):** a work queue walks the tree from the roots. For each node: frustum test, then
   a **hierarchical-Z occlusion test** against a depth pyramid of the previous frame (max-reduced mips, built in one compute pass
   with subgroup reductions); the node descends to its children while its **projected screen area is above a threshold**
   (~64x64 px for a 32-voxel section -> voxels ~1-2 px). Missing children are requested from the CPU and the parent is drawn meanwhile.
3. **Command generation (compute):** for each visible section, one indirect draw per face direction that can face the camera
   (section-level back-face culling: e.g. "up" quads only if the camera is above the section's bottom) -> roughly half the quads
   are never submitted. Sections that became visible this frame are drawn again in a second "temporal" pass (two-pass occlusion,
   no pop-in).
4. **Vertex pulling:** the vertex shader reads the 8-byte quad from a buffer by vertex id / 4, expands the 4 corners, scales by
   the level; one flat varying set per quad + UV. Model data (face UV bounds, indentation, tint, cutout) comes from a model table.
5. **Shading:** texture atlas + biome tint + light from a lightmap id + fixed per-face directional shade. **No baked AO**: a
   **screen-space AO compute pass** afterwards darkens creases using the depth buffer.
6. Translucents (water) sorted coarsely by section distance and drawn after.

## What this means for us
- Voxy's quality comes from **real block faces** (triangles), so trees have trunks and air under canopies, cliffs are exact.
  Our wave/column approach cannot match that by construction; our raster LOD at tau 2 already has the same detail rule.
- Voxy's speed comes from **not drawing** most of the 512-chunk world: HiZ tree culling + per-direction face culling, plus a tiny
  quad format. Our tau 2 raster (48 M quads, 3.1 GB, 178 FPS) draws far more than it needs and stores several times more
  bytes per face (rough estimate from 3.1 GB / 48 M; to be measured).
- Plan (our own implementation, Metal): 8-byte quads; GPU tree traversal with a previous-frame HiZ pyramid; per-direction face
  culling; two-pass occlusion; screen-space AO; then go beyond Voxy with things macOS GL cannot do: Metal mesh/object shaders,
  cluster (64-quad) culling from r12 inside each section, temporal reuse when the camera is still.
