/* Optional FFM bridge for worker-only ID pipeline compilation. ABI 2 unchanged. */
#pragma once
#include "mcmetal.h"

/* Render thread while selectedPipeline is live. CPU retains only, no Metal work.
 * Returns one owned recipe; caller drops it even on compile failure. */
MCMTL_API int32_t mcmtl_ext_far_program_pin(mcmtl_handle device, mcmtl_handle selectedPipeline,
                                         uint32_t layerCode, mcmtl_handle *outRecipe);
/* Worker only. Exact function provenance; no ICB/template substitution. Returned
 * program is +1 MCMTLFarShader and remains caller-owned. Error is bounded UTF-8,
 * local to this call, never a decoder/device error. */
MCMTL_API int32_t mcmtl_ext_far_program_compile(mcmtl_handle recipe, mcmtl_handle *outProgram,
                                             char *error, uint32_t errorCapacity);
/* Any thread, one call per +1 recipe/program. Zero is harmless. */
MCMTL_API void mcmtl_ext_far_program_drop(mcmtl_handle object);
/* Render thread while the cache owns a live program. One new +1 reference for a worker packet. */
MCMTL_API mcmtl_handle mcmtl_ext_far_program_retain(mcmtl_handle program);
