# Stable offscreen readiness prototype

Default-off runtime `LodGpu.stableNearCoverage`; no occlusion reset is removed.
The current handoff loses coverage when a section leaves the camera-filtered
near draw list, causing about180 whole-history resets per10s turn.

Under that list's existing dispatcher lock, re-read ready meshes outside the
actual current view. Extra observations are allowed only for installed L0 slabs
whose immutable cluster bounds are all valid and whose entire origin±34block
envelope misses a side/eye plane. Packed positions occupy[-32,32); padding exceeds
native margins. Missing bounds, missing exact frustum, nonfinite input, rotating
coordinates and incomplete uploads fail closed. Inside-view omissions still need
ordinary visible-list evidence; graph-occluded sections are not assumed drawn.

Keep extra readiness separate until final coverage publication. Preparation may
replace bindings after extraction: revalidate that every final bound is valid.
Real coverage changes retain the full history reset. Owner/reset-generation fade
latches and authoritative air remain unchanged. Use the existing LodGpu entry
point with guarded frame-state reads; no mesher-hashed class or cache policy edits.

Review identified the final-binding ordering case; its regression is covered.
CPU controller64,074 checks and full build pass. In-game same-session turning
screen FAILED: average70.6→46.4FPS,1%low38.9→38.0; mask churn increased.
See results/FARSIGHT-STABLE-COVERAGE.md. Keep off; stop this experiment.
Any future revival's still/motion gates must cover both visibility rings,
turning/translation disocclusion and near geometry becoming empty. Fewer incidental
resets could expose existing temporal-culling weaknesses; CPU tests cannot settle
that. No image, FPS, release or installation qualification is claimed here.
