/*
 * mcmetal_surface.m -- CAMetalLayer surface (create, configure, present, destroy) and the present pass.
 * OWNER: I1 (native-core). Contract: docs/metal/CONTRACT.md section 8; spec section 6.
 *
 * Surface exports run on the render thread (= AppKit main thread). Layer properties are changed inside a
 * CATransaction with implicit actions disabled. The decoder (I2) acquires the drawable lazily while
 * decoding PRESENT (surface->drawable); mcmtl_surface_present / _destroy release it.
 *
 * Present fusion (CONTRACT.md 16.3, MCMTL_PRESENT_FUSION=1 by default): mcmtl_surface_create mirrors the layer
 * vertically (mcmtl_layer_set_flipped, kept across AppKit geometry updates) so the decoder can render the frame
 * straight into the drawable in the main target's memory order; mcmtl_surface_destroy removes the mirror.
 *
 * Mailbox presentation (CONTRACT.md 16.4, MCMTL_MAILBOX=1 by default): with vsync off, configure starts a helper
 * thread that blocks in nextDrawable on the render thread's behalf; the decoder only takes parked drawables
 * (mcmtl_mailbox_take) and skips presenting when there is none. FIFO keeps the blocking nextDrawable.
 *
 * The present pass (spec 6.6) lives here as decoder helpers (mcmetal_core.h): a cached
 * present PSO per destination format, mcmtl_core_encode_present (one full-screen triangle that reads the
 * source at (x, copyH - 1 - y) from the presented mip and forces alpha to 1; BGRA swizzle by the store)
 * and mcmtl_core_surface_encode_present (nextDrawable + present pass + presentDrawable:).
 */
#import "mcmetal_core.h"
#import "mcmetal_glass.h"

#import <QuartzCore/CABase.h>
#import <QuartzCore/CATransaction.h>
#include <dlfcn.h>
#include <objc/runtime.h>
#include <pthread.h>

/* ---------------------------------------------------------------------------------------------- */
/* sRGB colorspace without linking CoreGraphics (the shared build links Foundation, Metal, QuartzCore */
/* and MetalFX only; CoreGraphics is always loaded with QuartzCore).                               */
/* ---------------------------------------------------------------------------------------------- */

typedef CGColorSpaceRef (*mcmtl_cg_create_with_name_fn)(CFStringRef name);
typedef void (*mcmtl_cg_release_fn)(CGColorSpaceRef space);

static CGColorSpaceRef mcmtl_copy_srgb_colorspace(void) {
    static mcmtl_cg_create_with_name_fn createWithName;
    static mcmtl_cg_release_fn release;
    static const CFStringRef *srgbName;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        void *cg = dlopen("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", RTLD_LAZY | RTLD_NOLOAD);
        if (!cg) cg = dlopen("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", RTLD_LAZY);
        if (!cg) return;
        createWithName = (mcmtl_cg_create_with_name_fn)dlsym(cg, "CGColorSpaceCreateWithName");
        release = (mcmtl_cg_release_fn)dlsym(cg, "CGColorSpaceRelease");
        srgbName = (const CFStringRef *)dlsym(cg, "kCGColorSpaceSRGB");
    });
    (void)release;
    if (!createWithName || !srgbName || !*srgbName) return NULL;
    return createWithName(*srgbName);
}

/* ---------------------------------------------------------------------------------------------- */
/* Present fusion: vertically flipped layer (CONTRACT.md 16.3)                                      */
/* ---------------------------------------------------------------------------------------------- */

/* With MCMTL_PRESENT_FUSION=1 (default) the decoder renders the frame's last passes straight into the drawable,
 * whose memory then has the main target's orientation (row 0 = bottom of the picture, GL / Vulkan memory order).
 * The layer therefore shows drawable row 0 at the bottom: transform = mirror about the layer's horizontal center
 * line, i.e. (1, 0, 0, -1, 0, h * (1 - 2 * anchorPoint.y)) in the layer's own space (tools/layer-flip-probe.sh
 * verifies it by compositing offscreen with CARenderer: geometryFlipped and a negative contentsRect do NOT flip
 * the contents; this transform does, in place, for the SDL view's anchorPoint (0, 0) as for (0.5, 0.5)).
 *
 * AppKit owns the geometry of a view's backing layer and resets `transform` to identity on every resize
 * (setAnchorPoint:, setBounds:, setAffineTransform:(identity), setPosition:, in that order, inside its own
 * transaction). Re-applying the flip later would leave a window in which already-rendered drawables are shown the
 * wrong way up, so the layer's class is replaced (isa swizzle, like KVO) by a subclass whose transform / bounds /
 * anchor / frame setters substitute the flip for the current bounds: the flip changes in the same transaction as
 * the geometry. The decoder still reads the layer's actual transform every frame (mcmtl_layer_is_flipped) and
 * picks the present-pass orientation and fusion from it, so anything that bypasses the setters can only cost
 * fusion, never orientation (except for the frames already in flight). */

static const void *const kMCMTLFlipKey = &kMCMTLFlipKey;
#define MCMTL_FLIP_CLASS_PREFIX "MCMTLFlippedLayer_"

int mcmtl_present_fusion_env(void) {
    static int mode = -1;
    if (mode < 0) {
        const char *v = getenv("MCMTL_PRESENT_FUSION");
        /* Default ON again (round 4, CONTRACT.md 16.7): the scene-runner GPU hang was the restore attaching a
           presented drawable; fixed, and faster in the in-game A/B. */
        mode = v ? atoi(v) : 1;
        if (mode != 0 && mode != 1) {
            /* 2 was reserved for strategy S2 (viewport flip without a layer flip), which is not implemented. */
            NSLog(@"[mcmetal] MCMTL_PRESENT_FUSION=%s is not supported (0 = off, 1 = layer flip + fusion); using 0", v);
            mode = 0;
        }
    }
    return mode;
}

static BOOL mcmtl_flip_on(id layer) {
    return objc_getAssociatedObject(layer, kMCMTLFlipKey) != nil;
}

static CATransform3D mcmtl_flip_transform(CALayer *layer) {
    const CGFloat h = layer.bounds.size.height;
    const CGFloat ay = layer.anchorPoint.y;
    return CATransform3DMakeAffineTransform(CGAffineTransformMake(1, 0, 0, -1, 0, h * (1.0 - 2.0 * ay)));
}

/* Our subclass in the object's class chain (a KVO class may sit above it), Nil if none. */
static Class mcmtl_flip_class_of(id object) {
    for (Class c = object_getClass(object); c; c = class_getSuperclass(c)) {
        if (strncmp(class_getName(c), MCMTL_FLIP_CLASS_PREFIX, sizeof MCMTL_FLIP_CLASS_PREFIX - 1) == 0) return c;
    }
    return Nil;
}

static IMP mcmtl_flip_super_imp(id object, SEL sel) {
    Class c = mcmtl_flip_class_of(object);
    return class_getMethodImplementation(c ? class_getSuperclass(c) : object_getClass(object), sel);
}

static void mcmtl_flip_reapply(id self) {
    ((void (*)(id, SEL, CATransform3D))mcmtl_flip_super_imp(self, @selector(setTransform:)))(self, @selector(setTransform:),
                                                                                          mcmtl_flip_transform(self));
}

static void mcmtl_flip_setTransform(id self, SEL _cmd, CATransform3D t) {
    if (mcmtl_flip_on(self)) t = mcmtl_flip_transform(self);
    ((void (*)(id, SEL, CATransform3D))mcmtl_flip_super_imp(self, _cmd))(self, _cmd, t);
}

static void mcmtl_flip_setAffineTransform(id self, SEL _cmd, CGAffineTransform t) {
    if (mcmtl_flip_on(self)) t = CATransform3DGetAffineTransform(mcmtl_flip_transform(self));
    ((void (*)(id, SEL, CGAffineTransform))mcmtl_flip_super_imp(self, _cmd))(self, _cmd, t);
}

static void mcmtl_flip_setBounds(id self, SEL _cmd, CGRect r) {
    ((void (*)(id, SEL, CGRect))mcmtl_flip_super_imp(self, _cmd))(self, _cmd, r);
    if (mcmtl_flip_on(self)) mcmtl_flip_reapply(self);
}

static void mcmtl_flip_setFrame(id self, SEL _cmd, CGRect r) {
    ((void (*)(id, SEL, CGRect))mcmtl_flip_super_imp(self, _cmd))(self, _cmd, r);
    if (mcmtl_flip_on(self)) mcmtl_flip_reapply(self);
}

static void mcmtl_flip_setAnchorPoint(id self, SEL _cmd, CGPoint p) {
    ((void (*)(id, SEL, CGPoint))mcmtl_flip_super_imp(self, _cmd))(self, _cmd, p);
    if (mcmtl_flip_on(self)) mcmtl_flip_reapply(self);
}

/* The flipping subclass of `base` (created once per base class). Nil for KVO classes (they must stay on top). */
static Class mcmtl_flip_subclass(Class base) {
    const char *baseName = class_getName(base);
    if (strncmp(baseName, "NSKVONotifying_", 15) == 0) return Nil;
    char name[256];
    snprintf(name, sizeof name, MCMTL_FLIP_CLASS_PREFIX "%s", baseName);
    Class sub = objc_getClass(name);
    if (sub) return class_getSuperclass(sub) == base ? sub : Nil;
    sub = objc_allocateClassPair(base, name, 0);
    if (!sub) return Nil;
    struct { SEL sel; IMP imp; } methods[] = {
        { @selector(setTransform:), (IMP)mcmtl_flip_setTransform },
        { @selector(setAffineTransform:), (IMP)mcmtl_flip_setAffineTransform },
        { @selector(setBounds:), (IMP)mcmtl_flip_setBounds },
        { @selector(setFrame:), (IMP)mcmtl_flip_setFrame },
        { @selector(setAnchorPoint:), (IMP)mcmtl_flip_setAnchorPoint },
    };
    for (size_t i = 0; i < sizeof methods / sizeof methods[0]; i++) {
        Method m = class_getInstanceMethod(base, methods[i].sel);
        if (!m) {
            objc_disposeClassPair(sub);
            return Nil;
        }
        class_addMethod(sub, methods[i].sel, methods[i].imp, method_getTypeEncoding(m));
    }
    objc_registerClassPair(sub);
    return sub;
}

BOOL mcmtl_layer_is_flipped(CALayer *layer) {
    if (!layer) return NO;
    const CGAffineTransform t = layer.affineTransform;
    return t.a == 1.0 && t.b == 0.0 && t.c == 0.0 && t.d == -1.0;
}

void mcmtl_layer_set_flipped(CALayer *layer, BOOL flipped) {
    if (!layer) return;
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    @try {
        if (flipped) {
            if (!mcmtl_flip_class_of(layer)) {
                Class sub = mcmtl_flip_subclass(object_getClass(layer));
                if (sub) object_setClass(layer, sub);   /* else: flipped once, unprotected (decoder copes) */
            }
            objc_setAssociatedObject(layer, kMCMTLFlipKey, @YES, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            layer.transform = mcmtl_flip_transform(layer);
        } else {
            objc_setAssociatedObject(layer, kMCMTLFlipKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            Class sub = mcmtl_flip_class_of(layer);
            if (sub && object_getClass(layer) == sub) object_setClass(layer, class_getSuperclass(sub));
            layer.transform = CATransform3DIdentity;
        }
    } @finally {
        [CATransaction commit];
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* Non-blocking ("mailbox") presentation with vsync off (CONTRACT.md 16.4)                          */
/* ---------------------------------------------------------------------------------------------- */

/* With vsync off (no MCMTL_SURFACE_FLAG_DISPLAY_SYNC) and MCMTL_MAILBOX != 0 (default 1), the render thread never
 * calls -[CAMetalLayer nextDrawable]. A helper thread per surface ("acquirer") blocks in nextDrawable and parks the
 * drawable it gets (at most one). The decoder takes the parked drawable without waiting; when there is none, the
 * frame is rendered completely into the game's target and not presented, which is what Vulkan's MAILBOX does
 * (frames that cannot be shown in time are dropped, the display gets the newest finished one). Taking a drawable
 * wakes the acquirer for the next one.
 *
 * Queue gate (MCMTL_MAILBOX_MAX_QUEUED, default 1): a drawable is only taken while fewer presented frames than that
 * still wait for the display (counted from registration to the drawable's presented handler). Without the gate,
 * a composited layer queues presents FIFO at the refresh (DIRECT-TO-DISPLAY.md: 3 drawables, sync off = strict
 * FIFO) and the displayed frame would be two refreshes old; with it at most one frame waits, so each refresh shows
 * a frame finished during the previous one. Handlers that never arrive (lost presents) are forgiven after
 * MCMTL_MAILBOX_QUEUE_TIMEOUT_MS (default 100).
 *
 * Lifetime: the surface owns the mailbox; the acquirer thread holds its own reference and exits by itself after
 * mcmtl_mailbox_shutdown (it may still be inside nextDrawable for up to the 1 s timeout; whatever it gets then is
 * released unpresented). A parked drawable is released by take, by configure (size / mode change), by disabling and
 * by shutdown, so no drawable outlives the surface's use of it. Every nextDrawable runs in its own autorelease pool.
 */

static int mcmtl_env_int(const char *name, int fallback) {
    const char *v = getenv(name);
    return v && *v ? atoi(v) : fallback;
}

/* MCMTL_MAILBOX: 1 (default) = non-blocking presentation when vsync is off; 0 = the previous blocking path. */
static int mcmtl_mailbox_env(void) {
    static int mode = -1;
    if (mode < 0) mode = mcmtl_env_int("MCMTL_MAILBOX", 1) != 0 ? 1 : 0;
    return mode;
}

@interface MCMTLMailbox : NSObject {
@public
    pthread_mutex_t mu;
    pthread_cond_t cv;
    /* ---- guarded by mu ---- */
    CAMetalLayer *layer;
    id<CAMetalDrawable> ready;          /* the parked drawable                                          */
    uint64_t gen;                       /* bumped by configure / enable / disable: older acquisitions drop */
    BOOL enabled;                       /* the acquirer may acquire                                    */
    BOOL stop;                          /* shutdown: the acquirer exits                                */
    BOOL threadStarted;
    BOOL threadRunning;
    uint64_t retryNs;                   /* nil backoff (0 = none)                                      */
    uint32_t nilStreak;                 /* consecutive nil drawables                                   */
    id<CAMetalDrawable> (^acquire)(CAMetalLayer *layer); /* test hook, nil = nextDrawable              */
    void (^onNoneReady)(void);          /* test hook, called by take after a NONE_READY result         */
    uint64_t acquires, acquireWaitNs, acquireNil, discarded;
    /* ---- render thread ---- */
    BOOL active;                        /* mirror of `enabled` read lock-free by the decoder           */
    uint32_t maxQueued;
    uint64_t queueTimeoutNs;
    uint64_t registered;                /* presents registered                                         */
    uint64_t forgiven;                  /* presents whose handler the gate stopped waiting for          */
    uint64_t lastPresentNs;
    uint64_t staleDiscarded, gateTimeouts;
    /* ---- presented handlers (any thread) ---- */
    _Atomic uint64_t handled, displayed, dropped, latencyNs;
}
@end

@implementation MCMTLMailbox
- (void)dealloc {
    pthread_mutex_destroy(&mu);
    pthread_cond_destroy(&cv);
}
@end

static MCMTLMailbox *mcmtl_mailbox_new(CAMetalLayer *layer) {
    MCMTLMailbox *mb = [MCMTLMailbox new];
    pthread_mutex_init(&mb->mu, NULL);
    pthread_cond_init(&mb->cv, NULL);
    mb->layer = layer;
    mb->maxQueued = (uint32_t)MAX(0, mcmtl_env_int("MCMTL_MAILBOX_MAX_QUEUED", 1));
    mb->queueTimeoutNs = (uint64_t)MAX(1, mcmtl_env_int("MCMTL_MAILBOX_QUEUE_TIMEOUT_MS", 100)) * 1000000ull;
    return mb;
}

static void *mcmtl_mailbox_thread(void *arg) {
    MCMTLMailbox *mb = (__bridge_transfer MCMTLMailbox *)arg;   /* this thread's reference, released at exit */
    pthread_setname_np("mcmetal drawable acquirer");
    pthread_mutex_lock(&mb->mu);
    mb->threadRunning = YES;
    pthread_cond_broadcast(&mb->cv);
    while (!mb->stop) {
        if (!mb->enabled || mb->ready) {
            pthread_cond_wait(&mb->cv, &mb->mu);
            continue;
        }
        if (mb->retryNs) {
            /* nextDrawable returned nil (no device, zero size, 1 s timeout): retry after the backoff, or at once
             * when configure / shutdown wakes this thread. */
            const uint64_t retry = mb->retryNs;
            struct timespec ts = { (time_t)(retry / 1000000000ull), (long)(retry % 1000000000ull) };
            pthread_cond_timedwait_relative_np(&mb->cv, &mb->mu, &ts);
            mb->retryNs = 0;
            continue;
        }
        const uint64_t gen = mb->gen;
        CAMetalLayer *layer = mb->layer;
        id<CAMetalDrawable> (^acquire)(CAMetalLayer *layer) = mb->acquire;
        pthread_mutex_unlock(&mb->mu);

        id<CAMetalDrawable> drawable = nil;
        const uint64_t t0 = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
        @autoreleasepool {
            @try {
                drawable = acquire ? acquire(layer) : [layer nextDrawable];   /* blocks up to ~1 s */
            } @catch (NSException *e) {
                drawable = nil;
            }
        }
        const uint64_t t1 = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
        layer = nil;
        acquire = nil;

        id<CAMetalDrawable> discard = nil;
        pthread_mutex_lock(&mb->mu);
        mb->acquires++;
        mb->acquireWaitNs += t1 - t0;
        if (!drawable) {
            mb->acquireNil++;
            /* 2, 4, 8 ... 128 ms while nil keeps coming back */
            mb->retryNs = 2000000ull << (mb->nilStreak < 6 ? mb->nilStreak : 6);
            mb->nilStreak++;
        } else if (mb->stop || !mb->enabled || mb->gen != gen || mb->ready) {
            mb->discarded++;            /* acquired for an older configuration or after shutdown */
            discard = drawable;
        } else {
            mb->ready = drawable;
            mb->nilStreak = 0;
        }
        drawable = nil;
        pthread_cond_broadcast(&mb->cv);   /* test waiters */
        if (discard) {
            pthread_mutex_unlock(&mb->mu);
            discard = nil;                 /* back to the layer's pool, outside the lock */
            pthread_mutex_lock(&mb->mu);
        }
    }
    mb->threadRunning = NO;
    pthread_cond_broadcast(&mb->cv);
    pthread_mutex_unlock(&mb->mu);
    return NULL;
}

/* Turns acquisition on or off and drops the parked drawable (configure always calls it: size or mode may have
 * changed). Starts the acquirer thread on first use; if that fails the surface keeps the blocking path. */
static void mcmtl_mailbox_set_enabled(MCMTLMailbox *mb, BOOL enabled) {
    if (!mb) return;
    id<CAMetalDrawable> drop = nil;
    BOOL start = NO;
    pthread_mutex_lock(&mb->mu);
    mb->gen++;
    drop = mb->ready;
    mb->ready = nil;
    if (drop) mb->discarded++;
    mb->enabled = enabled && !mb->stop;
    mb->retryNs = 0;
    if (mb->enabled && !mb->threadStarted) {
        mb->threadStarted = YES;
        start = YES;
    }
    pthread_cond_broadcast(&mb->cv);
    pthread_mutex_unlock(&mb->mu);
    drop = nil;
    if (start) {
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
        pthread_attr_set_qos_class_np(&attr, QOS_CLASS_USER_INTERACTIVE, 0);
        pthread_t thread;
        void *ref = (__bridge_retained void *)mb;
        if (pthread_create(&thread, &attr, mcmtl_mailbox_thread, ref) != 0) {
            CFRelease(ref);
            NSLog(@"[mcmetal] mailbox: could not start the drawable acquirer thread; presenting with blocking nextDrawable");
            pthread_mutex_lock(&mb->mu);
            mb->threadStarted = NO;
            mb->enabled = NO;
            pthread_mutex_unlock(&mb->mu);
        }
        pthread_attr_destroy(&attr);
    }
    pthread_mutex_lock(&mb->mu);
    mb->active = mb->enabled;
    pthread_mutex_unlock(&mb->mu);
}

/* Surface destroy: the acquirer exits on its own (after its current nextDrawable, if any, which keeps its own
 * reference to the layer). The mailbox lets go of the layer now: presented handlers still pending keep the mailbox
 * alive, and must not keep the layer (and through it their own drawables) alive with it. */
static void mcmtl_mailbox_shutdown(MCMTLMailbox *mb) {
    if (!mb) return;
    id<CAMetalDrawable> drop = nil;
    CAMetalLayer *layer = nil;
    pthread_mutex_lock(&mb->mu);
    mb->stop = YES;
    mb->enabled = NO;
    mb->active = NO;
    mb->gen++;
    drop = mb->ready;
    mb->ready = nil;
    if (drop) mb->discarded++;
    layer = mb->layer;
    mb->layer = nil;
    mb->acquire = nil;
    mb->onNoneReady = nil;
    pthread_cond_broadcast(&mb->cv);
    pthread_mutex_unlock(&mb->mu);
    drop = nil;
    (void)layer;   /* released here, outside the lock */
    layer = nil;
}

BOOL mcmtl_mailbox_active(MCMTLSurface *surface) {
    MCMTLMailbox *mb = surface ? surface->mailbox : nil;
    return mb && mb->active;
}

id<CAMetalDrawable> mcmtl_mailbox_take(MCMTLSurface *surface, uint32_t *outWhy) {
    MCMTLMailbox *mb = surface ? surface->mailbox : nil;
    uint32_t why = MCMTL_MAILBOX_NONE_READY;
    id<CAMetalDrawable> drawable = nil;
    if (mb && mb->active) {
        const uint64_t now = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
        BOOL gateOpen = YES;
        if (mb->maxQueued) {
            const uint64_t handled = atomic_load_explicit(&mb->handled, memory_order_acquire);
            int64_t pending = (int64_t)(mb->registered - mb->forgiven - handled);
            if (pending < 0) {                     /* late handlers of forgiven presents */
                mb->forgiven -= (uint64_t)(-pending);
                pending = 0;
            }
            if (pending >= (int64_t)mb->maxQueued) {
                if (now - mb->lastPresentNs < mb->queueTimeoutNs) {
                    gateOpen = NO;
                    why = MCMTL_MAILBOX_QUEUE_FULL;
                } else {
                    mb->forgiven += (uint64_t)pending;   /* never shown or handler lost: stop waiting for them */
                    mb->gateTimeouts++;
                }
            }
        }
        if (gateOpen) {
            pthread_mutex_lock(&mb->mu);
            drawable = mb->ready;
            mb->ready = nil;
            if (drawable) pthread_cond_broadcast(&mb->cv);   /* acquire the next one now */
            pthread_mutex_unlock(&mb->mu);
            if (drawable) {
                /* A drawable parked before the layer changed size (live resize, fullscreen switch before the game
                 * reconfigures) would show the old size: drop it; the acquirer gets one of the new size. */
                id<MTLTexture> t = drawable.texture;
                const CGSize size = surface->layer.drawableSize;
                if (t.width != (NSUInteger)size.width || t.height != (NSUInteger)size.height) {
                    mb->staleDiscarded++;
                    drawable = nil;
                    why = MCMTL_MAILBOX_STALE;
                } else {
                    why = MCMTL_MAILBOX_TAKEN;
                }
            } else if (mb->onNoneReady) {
                mb->onNoneReady();   /* decoder_test: make a drawable appear right after the miss */
            }
        }
    }
    if (outWhy) *outWhy = why;
    return drawable;
}

BOOL mcmtl_mailbox_ready(MCMTLSurface *surface) {
    MCMTLMailbox *mb = surface ? surface->mailbox : nil;
    if (!mb || !mb->active) return NO;
    pthread_mutex_lock(&mb->mu);
    const BOOL ready = mb->ready != nil;
    pthread_mutex_unlock(&mb->mu);
    return ready;
}

void mcmtl_mailbox_will_present(MCMTLSurface *surface, id<CAMetalDrawable> drawable) {
    /* On-glass cadence trace (MCMTL_GLASS_TRACE, diagnostics, off by default; mcmetal_glass.h): every present the
     * decoder registers, fused or not, with or without a mailbox. */
    if (mcmtl_glass_mode()) mcmtl_glass_will_present(drawable);
    MCMTLMailbox *mb = surface ? surface->mailbox : nil;
    if (!mb || !drawable) return;
    mb->registered++;
    mb->lastPresentNs = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    const CFTimeInterval registeredAt = CACurrentMediaTime();   /* presentedTime uses the same host-time base */
    MCMTLMailbox *box = mb;   /* the handler keeps the counters alive past the surface */
    [drawable addPresentedHandler:^(id<MTLDrawable> shown) {
        const CFTimeInterval at = shown.presentedTime;
        if (at > 0) {
            atomic_fetch_add_explicit(&box->displayed, 1, memory_order_relaxed);
            if (at > registeredAt) atomic_fetch_add_explicit(&box->latencyNs, (uint64_t)((at - registeredAt) * 1e9), memory_order_relaxed);
        } else {
            atomic_fetch_add_explicit(&box->dropped, 1, memory_order_relaxed);
        }
        atomic_fetch_add_explicit(&box->handled, 1, memory_order_release);
    }];
}

void mcmtl_mailbox_get_stats(MCMTLSurface *surface, mcmtl_mailbox_stats *out) {
    memset(out, 0, sizeof *out);
    MCMTLMailbox *mb = surface ? surface->mailbox : nil;
    if (!mb) return;
    pthread_mutex_lock(&mb->mu);
    out->active = mb->active ? 1u : 0u;
    out->threadRunning = mb->threadRunning ? 1u : 0u;
    out->parked = mb->ready ? 1u : 0u;
    out->acquires = mb->acquires;
    out->acquireWaitNs = mb->acquireWaitNs;
    out->acquireNil = mb->acquireNil;
    out->discarded = mb->discarded;
    pthread_mutex_unlock(&mb->mu);
    out->drawableCount = surface->layer ? (uint32_t)surface->layer.maximumDrawableCount : 0u;
    out->maxQueued = mb->maxQueued;
    out->staleDiscarded = mb->staleDiscarded;
    out->gateTimeouts = mb->gateTimeouts;
    out->presentsRegistered = mb->registered;
    out->presentsHandled = atomic_load(&mb->handled);
    const int64_t pending = (int64_t)(mb->registered - mb->forgiven - out->presentsHandled);
    out->presentsPending = pending > 0 ? (uint64_t)pending : 0u;
    out->presentsDisplayed = atomic_load(&mb->displayed);
    out->presentsDropped = atomic_load(&mb->dropped);
    out->displayLatencyNs = atomic_load(&mb->latencyNs);
}

/* ---- decoder_test hooks (hidden symbols) ---- */

/* Gives a hand-built surface a mailbox (if it has none) and turns it on or off like configure would. `acquire`
 * replaces [layer nextDrawable] on the acquirer thread (nil = keep the current source; a new mailbox uses the real
 * call); `maxQueued` / `queueTimeoutNs` replace the environment values. */
void mcmtl_mailbox_test_enable(MCMTLSurface *surface, BOOL enable, uint32_t maxQueued, uint64_t queueTimeoutNs,
                               id<CAMetalDrawable> (^acquire)(CAMetalLayer *layer));
void mcmtl_mailbox_test_on_none_ready(MCMTLSurface *surface, void (^block)(void));
BOOL mcmtl_mailbox_test_wait_ready(MCMTLSurface *surface, uint64_t timeoutNs);
void mcmtl_mailbox_test_add_queued(MCMTLSurface *surface, uint32_t n);
void mcmtl_mailbox_test_shutdown(MCMTLSurface *surface);

void mcmtl_mailbox_test_enable(MCMTLSurface *surface, BOOL enable, uint32_t maxQueued, uint64_t queueTimeoutNs,
                               id<CAMetalDrawable> (^acquire)(CAMetalLayer *layer)) {
    if (!surface) return;
    if (!surface->mailbox) surface->mailbox = mcmtl_mailbox_new(surface->layer);
    MCMTLMailbox *mb = surface->mailbox;
    if (acquire) {
        pthread_mutex_lock(&mb->mu);
        mb->acquire = acquire;
        pthread_mutex_unlock(&mb->mu);
    }
    mb->maxQueued = maxQueued;
    mb->queueTimeoutNs = queueTimeoutNs;
    mcmtl_mailbox_set_enabled(mb, enable);
}

/* Called by take after each NONE_READY miss (nil = none). */
void mcmtl_mailbox_test_on_none_ready(MCMTLSurface *surface, void (^block)(void)) {
    if (surface && surface->mailbox) surface->mailbox->onNoneReady = block;
}

/* Waits until a drawable is parked (YES) or the timeout passes (NO). */
BOOL mcmtl_mailbox_test_wait_ready(MCMTLSurface *surface, uint64_t timeoutNs) {
    MCMTLMailbox *mb = surface ? surface->mailbox : nil;
    if (!mb) return NO;
    const uint64_t end = clock_gettime_nsec_np(CLOCK_UPTIME_RAW) + timeoutNs;
    pthread_mutex_lock(&mb->mu);
    while (!mb->ready) {
        const uint64_t now = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
        if (now >= end) break;
        const uint64_t left = end - now;
        struct timespec ts = { (time_t)(left / 1000000000ull), (long)(left % 1000000000ull) };
        pthread_cond_timedwait_relative_np(&mb->cv, &mb->mu, &ts);
    }
    const BOOL ready = mb->ready != nil;
    pthread_mutex_unlock(&mb->mu);
    return ready;
}

/* Pretends `n` more presents are queued for display (the gate's count) without presenting anything. */
void mcmtl_mailbox_test_add_queued(MCMTLSurface *surface, uint32_t n) {
    MCMTLMailbox *mb = surface ? surface->mailbox : nil;
    if (!mb) return;
    mb->registered += n;
    mb->lastPresentNs = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
}

/* Shuts the mailbox down like mcmtl_surface_destroy, but leaves it attached (inactive) so its counters stay readable. */
void mcmtl_mailbox_test_shutdown(MCMTLSurface *surface) {
    if (!surface || !surface->mailbox) return;
    mcmtl_mailbox_shutdown(surface->mailbox);
}

/* ---------------------------------------------------------------------------------------------- */
/* Surface exports                                                                                  */
/* ---------------------------------------------------------------------------------------------- */

MCMTL_API int32_t mcmtl_surface_create(mcmtl_handle device, void *caMetalLayer, mcmtl_handle *outSurface) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (outSurface) *outSurface = 0;
        if (!device || !caMetalLayer || !outSurface) {
            mcmtl_set_error("mcmtl_surface_create: NULL argument");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        id object = (__bridge id)caMetalLayer;
        if (![object isKindOfClass:[CAMetalLayer class]]) {
            mcmtl_set_error("mcmtl_surface_create: layer is a %s, not a CAMetalLayer", class_getName([object class]));
            return MCMTL_E_SURFACE;
        }
        CAMetalLayer *layer = (CAMetalLayer *)object;
        CGColorSpaceRef srgb = mcmtl_copy_srgb_colorspace();
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        @try {
            layer.device = dev->device;
            layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
            layer.framebufferOnly = getenv("MCMTL_FBONLY") ? (atoi(getenv("MCMTL_FBONLY")) != 0) : YES;
            layer.opaque = YES;
            if (getenv("MCMTL_NO_COLORSPACE")) layer.colorspace = nil;
            else if (srgb) layer.colorspace = srgb;
            layer.wantsExtendedDynamicRangeContent = NO;
            layer.maximumDrawableCount = 3;
            layer.allowsNextDrawableTimeout = YES;
            layer.presentsWithTransaction = NO;
            if (getenv("MCMTL_FILTER_NEAREST")) {
                layer.minificationFilter = kCAFilterNearest;
                layer.magnificationFilter = kCAFilterNearest;
            }
            const char *grav = getenv("MCMTL_GRAVITY");
            if (grav) {
                if (!strcmp(grav, "topLeft")) layer.contentsGravity = kCAGravityTopLeft;
                else if (!strcmp(grav, "resize")) layer.contentsGravity = kCAGravityResize;
                else if (!strcmp(grav, "center")) layer.contentsGravity = kCAGravityCenter;
            }
            layer.displaySyncEnabled = getenv("MCMTL_SYNC_AT_CREATE") ? (atoi(getenv("MCMTL_SYNC_AT_CREATE")) != 0) : YES;
            /* Present fusion (CONTRACT.md 16.3): drawable row 0 at the bottom of the window. */
            if (mcmtl_present_fusion_env() == 1) mcmtl_layer_set_flipped(layer, YES);
        } @finally {
            [CATransaction commit];
            if (srgb) CFRelease(srgb);
        }
        MCMTLSurface *surface = [MCMTLSurface new];
        surface->owner = dev;
        surface->layer = layer;
        CGSize size = layer.drawableSize;
        surface->configuredWidth = size.width > 0 ? (uint32_t)size.width : 0;
        surface->configuredHeight = size.height > 0 ? (uint32_t)size.height : 0;
        surface->configFlags = MCMTL_SURFACE_FLAG_DISPLAY_SYNC;
        surface->mailbox = mcmtl_mailbox_new(layer);   /* idle until configure turns vsync off */
        *outSurface = mcmtl_handle_retain(surface);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

MCMTL_API int32_t mcmtl_surface_configure(mcmtl_handle surface, const mcmtl_surface_config *config) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!surface || !config) {
            mcmtl_set_error("mcmtl_surface_configure: NULL argument");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (config->structSize != sizeof(mcmtl_surface_config)) {
            mcmtl_set_error("mcmtl_surface_configure: structSize %u != %zu", config->structSize, sizeof(mcmtl_surface_config));
            return MCMTL_E_ABI_MISMATCH;
        }
        if (config->width < 1 || config->height < 1 || config->width > 16384 || config->height > 16384) {
            mcmtl_set_error("mcmtl_surface_configure: size %ux%u outside 1..16384", config->width, config->height);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (config->flags & ~MCMTL_SURFACE_FLAG_DISPLAY_SYNC) {
            mcmtl_set_error("mcmtl_surface_configure: unknown flags 0x%x", config->flags & ~MCMTL_SURFACE_FLAG_DISPLAY_SYNC);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (config->maximumDrawableCount != 0 && config->maximumDrawableCount != 2 && config->maximumDrawableCount != 3) {
            mcmtl_set_error("mcmtl_surface_configure: maximumDrawableCount %u must be 0 (unchanged), 2 or 3",
                            config->maximumDrawableCount);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        MCMTLSurface *s = MCMTL_SURFACE(surface);
        CAMetalLayer *layer = s->layer;
        if (!layer) {
            mcmtl_set_error("mcmtl_surface_configure: surface has no layer");
            return MCMTL_E_SURFACE;
        }
        /* Vsync off: non-blocking mailbox presentation (CONTRACT.md 16.4) with MCMTL_MAILBOX_DRAWABLES (default 3)
         * drawables, whatever Java asked for (Java's 2 for IMMEDIATE is the blocking path's workaround for the
         * composited 3-drawable FIFO pacing, which the mailbox no longer waits on). MCMTL_DRAWABLES still wins. */
        const BOOL mailbox = !(config->flags & MCMTL_SURFACE_FLAG_DISPLAY_SYNC) && mcmtl_mailbox_env() && s->mailbox;
        uint32_t drawableCount = config->maximumDrawableCount;
        if (mailbox) {
            const int n = mcmtl_env_int("MCMTL_MAILBOX_DRAWABLES", 3);
            drawableCount = (n == 2 || n == 3) ? (uint32_t)n : 3u;
        }
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        @try {
            layer.drawableSize = CGSizeMake(config->width, config->height);
            /* With SDL's auto-resize hint off, SDL no longer maintains contentsScale; a 1.0 scale on a Retina
               window makes WindowServer resample (and composite) every frame. Keep pixels:points at 1:1 with
               the drawable so fullscreen can go direct to display. */
            CGFloat boundsWidth = layer.bounds.size.width;
            if (boundsWidth > 0 && !getenv("MCMTL_NO_CONTENTS_SCALE")) {
                layer.contentsScale = (CGFloat)config->width / boundsWidth;
            }
            layer.displaySyncEnabled = (config->flags & MCMTL_SURFACE_FLAG_DISPLAY_SYNC) != 0;
            if (drawableCount) layer.maximumDrawableCount = drawableCount;
            /* The flip follows bounds changes by itself; re-assert it in case something bypassed the setters. */
            if (mcmtl_flip_on(layer)) mcmtl_layer_set_flipped(layer, YES);
            if (getenv("MCMTL_NO_COLORSPACE")) layer.colorspace = nil;
            const char *dc = getenv("MCMTL_DRAWABLES");
            if (dc && (atoi(dc) == 2 || atoi(dc) == 3)) layer.maximumDrawableCount = (NSUInteger)atoi(dc);
            if (getenv("MCMTL_LOG_SURFACE")) {
                NSLog(@"mcmtl surface configure: %ux%u displaySync=%d drawables=%lu contentsScale=%.3f bounds=%.0fx%.0f fbOnly=%d mailbox=%d",
                      config->width, config->height, layer.displaySyncEnabled, (unsigned long)layer.maximumDrawableCount,
                      layer.contentsScale, layer.bounds.size.width, layer.bounds.size.height, layer.framebufferOnly, mailbox);
            }
        } @finally {
            [CATransaction commit];
        }
        /* After the new size is set: drops a parked drawable of the old size / mode and (re)starts acquisition. */
        mcmtl_mailbox_set_enabled(s->mailbox, mailbox);
        s->configuredWidth = config->width;
        s->configuredHeight = config->height;
        s->configFlags = config->flags;
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

MCMTL_API int32_t mcmtl_surface_present(mcmtl_handle surface) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!surface) {
            mcmtl_set_error("mcmtl_surface_present: surface is 0");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        /* Classic: presentDrawable: was registered on the command buffer before its commit; dropping our
         * reference returns the drawable to Core Animation's pool once it is displayed. */
        MCMTL_SURFACE(surface)->drawable = nil;
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

MCMTL_API int32_t mcmtl_surface_destroy(mcmtl_handle surface) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!surface) {
            mcmtl_set_error("mcmtl_surface_destroy: surface is 0");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        MCMTLSurface *s = MCMTL_SURFACE(surface);
        s->drawable = nil;
        mcmtl_mailbox_shutdown(s->mailbox);   /* releases a parked drawable; the acquirer thread exits by itself */
        s->mailbox = nil;
        if (s->layer && mcmtl_flip_on(s->layer)) mcmtl_layer_set_flipped(s->layer, NO);   /* hand the layer back as found */
        s->layer = nil;      /* the layer itself belongs to the SDL Metal view */
        s->owner = nil;
        mcmtl_handle_release(surface);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

/* ---------------------------------------------------------------------------------------------- */
/* Present pass (spec 6.6)                                                                          */
/* ---------------------------------------------------------------------------------------------- */

static NSString *const mcmtl_present_source =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct PresentVOut { float4 pos [[position]]; };\n"
     "vertex PresentVOut mcmtl_present_vs(uint vid [[vertex_id]]) {\n"
     "    float2 p = float2((vid << 1) & 2, vid & 2);\n"
     "    PresentVOut o;\n"
     "    o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0);\n"
     "    return o;\n"
     "}\n"
     "struct PresentParams { uint copyHeight; uint level; };\n"
     "fragment half4 mcmtl_present_fs(PresentVOut in [[stage_in]],\n"
     "                                texture2d<half, access::read> src [[texture(0)]],\n"
     "                                constant PresentParams &params [[buffer(0)]]) {\n"
     "    uint2 d = uint2(in.pos.xy);                              // drawable pixel, row 0 = top\n"
     "    uint2 s = uint2(d.x, params.copyHeight - 1u - d.y);      // render-target row 0 = bottom\n"
     "    return half4(src.read(s, params.level).rgb, 1.0h);       // opaque; the store swizzles to BGRA\n"
     "}\n";

typedef struct mcmtl_present_params {
    uint32_t copyHeight;
    uint32_t level;
} mcmtl_present_params;

id<MTLRenderPipelineState> mcmtl_core_present_pipeline(MCMTLDevice *dev, MTLPixelFormat format, NSString **failure) {
    MCMTLCoreState *core = mcmtl_core_state(dev);
    if (!core) {
        if (failure) *failure = @"no device";
        return nil;
    }
    NSNumber *key = @((NSUInteger)format);
    os_unfair_lock_lock(&core->lock);
    id<MTLRenderPipelineState> pso = core->presentPipelines[key];
    id<MTLLibrary> library = core->presentLibrary;
    os_unfair_lock_unlock(&core->lock);
    if (pso) return pso;

    NSError *error = nil;
    if (!library) {
        MTLCompileOptions *options = [MTLCompileOptions new];
        options.languageVersion = MTLLanguageVersion3_2;
        library = [dev->device newLibraryWithSource:mcmtl_present_source options:options error:&error];
        if (!library) {
            if (failure) *failure = [NSString stringWithFormat:@"present shader compile failed: %@", error.localizedDescription];
            return nil;
        }
        library.label = @"mcmetal present";
    }
    MTLRenderPipelineDescriptor *descriptor = [MTLRenderPipelineDescriptor new];
    descriptor.label = @"mcmetal present";
    descriptor.vertexFunction = [library newFunctionWithName:@"mcmtl_present_vs"];
    descriptor.fragmentFunction = [library newFunctionWithName:@"mcmtl_present_fs"];
    descriptor.colorAttachments[0].pixelFormat = format;
    descriptor.colorAttachments[0].blendingEnabled = NO;
    descriptor.colorAttachments[0].writeMask = MTLColorWriteMaskAll;
    descriptor.rasterSampleCount = 1;
    error = nil;
    id<MTLRenderPipelineState> created = [dev->device newRenderPipelineStateWithDescriptor:descriptor error:&error];
    if (!created) {
        if (failure) *failure = [NSString stringWithFormat:@"present PSO (format %lu) failed: %@", (unsigned long)format,
                                                           error.localizedDescription];
        return nil;
    }
    os_unfair_lock_lock(&core->lock);
    if (!core->presentLibrary) core->presentLibrary = library;
    pso = core->presentPipelines[key];
    if (!pso) {
        core->presentPipelines[key] = created;
        pso = created;
    }
    os_unfair_lock_unlock(&core->lock);
    return pso;
}

BOOL mcmtl_core_encode_present(MCMTLDevice *dev, id<MTLCommandBuffer> commandBuffer, id<MTLTexture> dst, id<MTLTexture> src,
                               uint32_t level, NSString **failure) {
    if (!dev || !commandBuffer || !dst || !src) {
        if (failure) *failure = @"present: missing device, command buffer, destination or source";
        return NO;
    }
    if (src.textureType != MTLTextureType2D || level >= src.mipmapLevelCount ||
        (src.usage != MTLTextureUsageUnknown && !(src.usage & MTLTextureUsageShaderRead)) ||
        mcmtl_core_format_is_depth_stencil((uint32_t)src.pixelFormat) || mcmtl_core_format_is_integer((uint32_t)src.pixelFormat)) {
        if (failure) {
            *failure = [NSString stringWithFormat:@"present: source (type %lu, format %lu, usage 0x%lx, %lu mips) cannot be presented from level %u",
                                                  (unsigned long)src.textureType, (unsigned long)src.pixelFormat,
                                                  (unsigned long)src.usage, (unsigned long)src.mipmapLevelCount, level];
        }
        return NO;
    }
    id<MTLRenderPipelineState> pso = mcmtl_core_present_pipeline(dev, dst.pixelFormat, failure);
    if (!pso) return NO;

    const uint32_t dstW = (uint32_t)dst.width, dstH = (uint32_t)dst.height;
    uint32_t srcW = (uint32_t)src.width >> level, srcH = (uint32_t)src.height >> level;
    if (srcW < 1) srcW = 1;
    if (srcH < 1) srcH = 1;
    const uint32_t copyW = dstW < srcW ? dstW : srcW;
    const uint32_t copyH = dstH < srcH ? dstH : srcH;

    MCMTLCoreState *core = mcmtl_core_state(dev);
    MTLRenderPassDescriptor *pass = core->presentPassDescriptor;   /* render thread only */
    if (!pass) {
        pass = [MTLRenderPassDescriptor renderPassDescriptor];
        pass.colorAttachments[0].storeAction = MTLStoreActionStore;
        pass.colorAttachments[0].clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 1.0);
        core->presentPassDescriptor = pass;
    }
    MTLRenderPassColorAttachmentDescriptor *attachment = pass.colorAttachments[0];
    attachment.texture = dst;
    attachment.level = 0;
    attachment.slice = 0;
    /* Copy covers the drawable: its previous contents are irrelevant. Else clear the uncovered area. */
    attachment.loadAction = (copyW == dstW && copyH == dstH) ? MTLLoadActionDontCare : MTLLoadActionClear;
    id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:pass];
    attachment.texture = nil;   /* never keep the drawable alive through the cached descriptor */
    if (!encoder) {
        if (failure) *failure = @"present: renderCommandEncoderWithDescriptor: returned nil";
        return NO;
    }
    encoder.label = @"present";
    [encoder setRenderPipelineState:pso];
    [encoder setViewport:(MTLViewport){0.0, 0.0, (double)copyW, (double)copyH, 0.0, 1.0}];
    [encoder setScissorRect:(MTLScissorRect){0, 0, copyW, copyH}];
    [encoder setFragmentTexture:src atIndex:0];
    const mcmtl_present_params params = {copyH, level};
    [encoder setFragmentBytes:&params length:sizeof params atIndex:0];
    [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [encoder endEncoding];
    return YES;
}

uint32_t mcmtl_core_surface_encode_present(MCMTLSurface *surface, id<MTLCommandBuffer> commandBuffer, id<MTLTexture> src,
                                           uint32_t level, uint32_t *outWidth, uint32_t *outHeight, NSString **failure) {
    if (outWidth) *outWidth = 0;
    if (outHeight) *outHeight = 0;
    if (!surface || !surface->layer || !surface->owner) {
        if (failure) *failure = @"present: surface has no layer";
        return 0;
    }
    id<CAMetalDrawable> drawable = nil;
    @autoreleasepool {
        drawable = [surface->layer nextDrawable];   /* blocking, nil after ~1 s (allowsNextDrawableTimeout) */
    }
    if (!drawable) return MCMTL_CORE_DRAWABLE_NIL;
    id<MTLTexture> target = drawable.texture;
    const uint32_t w = (uint32_t)target.width, h = (uint32_t)target.height;
    if (outWidth) *outWidth = w;
    if (outHeight) *outHeight = h;
    uint32_t flags = 0;
    if (w != surface->configuredWidth || h != surface->configuredHeight) flags |= MCMTL_CORE_DRAWABLE_RESIZED;
    if (!mcmtl_core_encode_present(surface->owner, commandBuffer, target, src, level, failure)) {
        return flags;   /* drawable dropped unpresented: it returns to the pool */
    }
    [commandBuffer presentDrawable:drawable];
    surface->drawable = drawable;
    surface->presentedFrames++;
    return flags | MCMTL_CORE_PRESENT_ENCODED;
}
