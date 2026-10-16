/* Exact selected-function metadata for optional worker-built far-image PSOs. */
#pragma once
#import <Metal/Metal.h>

/* Returns immutable source/name plus an OWNED options copy, or NO when source
 * capture was not enabled before this function was compiled. Never compiles.
 * Caller pins the function. Does not return the ICB-rewritten function/source.
 */
BOOL mcmtl_shader_copy_source(id<MTLFunction> function, NSString **source,
                              NSString **entryPoint, MTLCompileOptions **options);
