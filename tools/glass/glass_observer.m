/*
 * glass_observer.m -- the on-glass trace (native/src/mcmetal_glass.m, compiled in unchanged) for any CAMetalLayer client
 * that is not our Metal backend, e.g. vanilla 26.3 on Vulkan (MoltenVK). Diagnostics only (results/BASELINE-DEFAULT.md).
 *
 *   zsh tools/glass/build.sh        -> build/glass/libglassobs.dylib
 *   tools/launch.py ... --env DYLD_INSERT_LIBRARIES=<abs>/build/glass/libglassobs.dylib --env MCMTL_GLASS_TRACE=1 \
 *       [--env MCMTL_GLASS_TRACE_OUT=<label>.glass.csv]
 *
 * Mojang's java runtime carries com.apple.security.cs.allow-dyld-environment-variables and disable-library-validation,
 * so the library loads without touching the jar. With MCMTL_GLASS_TRACE set, the constructor wraps
 * -[CAMetalLayer nextDrawable] and registers every returned drawable with mcmtl_glass_will_present: the per-second lines
 * ("[mcmtl glass] ...") are the same as the patched client's, with two differences: the registration time is the
 * acquisition (so "lat" includes the frame's rendering), and a drawable released without a present counts as notShown.
 * Without MCMTL_GLASS_TRACE nothing is replaced.
 */
#import "mcmetal_glass.h"

#include <objc/runtime.h>
#include <stdio.h>
#include <unistd.h>

static IMP g_nextDrawable;

static id glass_next_drawable(id self, SEL _cmd) {
    id drawable = ((id (*)(id, SEL))g_nextDrawable)(self, _cmd);
    if (drawable) mcmtl_glass_will_present(drawable);
    return drawable;
}

__attribute__((constructor)) static void glass_observer_init(void) {
    if (!mcmtl_glass_mode()) return;
    Class layer = objc_getClass("CAMetalLayer");
    Method m = layer ? class_getInstanceMethod(layer, @selector(nextDrawable)) : NULL;
    if (!m) {
        fprintf(stderr, "[mcmtl glass] observer: CAMetalLayer -nextDrawable not found; not tracing\n");
        return;
    }
    g_nextDrawable = method_setImplementation(m, (IMP)glass_next_drawable);
    fprintf(stderr, "[mcmtl glass] observer installed in pid %d (CAMetalLayer -nextDrawable)\n", getpid());
}
