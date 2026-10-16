/*
 * mcmetal_glass.m -- on-glass cadence trace (MCMTL_GLASS_TRACE; diagnostics only, off by default).
 * Interface, environment and output fields: mcmetal_glass.h. Method and results: results/BASELINE-DEFAULT.md.
 *
 * Per registered present a record goes into a lock-free ring (seq & GLASS_MASK). The drawable's presented handler (a
 * Core Animation thread) fills presentedTime (0 = never on glass) and the private didComposite, then marks the record
 * done. A reporter thread consumes the records in sequence order once per period: it sorts the period's presentedTime
 * values, merges values closer than a quarter refresh (the same panel refresh), and bins the intervals between the
 * remaining distinct values in refresh units. A record still pending after GLASS_PENDING_TIMEOUT counts as lost.
 *
 * Earlier version (never merged): worktree wf_ad3346a6-063-1 native/src/mcmetal_glass.m (counted every presentedTime > 0,
 * which over-counts when Core Animation reports one refresh for several drawables, plus display-link observers that can
 * change what WindowServer does; both dropped here).
 *
 * Nothing here runs unless the environment switch is set: with the trace off the hot path is one load of a static int.
 */
#import "mcmetal_glass.h"

#import <Metal/Metal.h>
#include <dlfcn.h>
#include <math.h>
#include <objc/message.h>
#include <objc/runtime.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define GLASS_RING (1u << 14)
#define GLASS_MASK (GLASS_RING - 1u)
#define GLASS_PENDING_TIMEOUT 2.0   /* s: a handler later than this is treated as lost */
#define GLASS_MAX_SHOWN 8192        /* shown presents kept per period */
#define GLASS_SAME 0.25             /* refreshes: presentedTime values closer than this are one panel refresh */
#define GLASS_OFF 0.25              /* refreshes: an interval farther than this from a whole number is "off grid" */

enum { REC_FREE = 0, REC_PENDING = 1, REC_DONE = 2, REC_WRITING = 3 };

typedef struct glass_rec {
    _Atomic uint32_t state;
    int32_t composited;         /* didComposite: 1 / 0, -1 = unknown or not shown */
    _Atomic uint64_t seq;
    double tReg;                /* CACurrentMediaTime at registration (same base as presentedTime) */
    double tShown;              /* presentedTime, 0 = never shown */
    double tTarget;             /* -[CAMetalDrawable targetPresentationTimestamp] (private), 0 = unknown */
} glass_rec;

typedef struct glass_shown {
    double t, lat, target;
    int32_t composited;
    uint64_t seq;
} glass_shown;

static glass_rec g_ring[GLASS_RING];
static _Atomic uint64_t g_next;            /* next sequence number */
static _Atomic uint64_t g_overflow;        /* registrations dropped because the ring slot was still in use */
static int g_mode = -1;
static double g_refresh = 1.0 / 120.0;    /* shortest refresh interval of the main screen (s) */
static FILE *g_out;
static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;   /* reporter state, totals, g_out, g_layer */
static CAMetalLayer *g_layer;             /* layer of the latest present (status sampling) */
static void *g_layerRaw;

/* totals (under g_mu) */
static struct {
    uint64_t registered, shown, notShown, lost, distinct, same, disorder, composited, compKnown, latN, periods;
    uint64_t bins[7];            /* sub, 1r, 2r, 3r, 4-7r, 8r+, off-grid */
    uint64_t secDirect, secComposited, secMixed;   /* periods with shown frames: none / all / some composited */
    double latSum, latMax, firstDistinct, lastDistinct;
    double gapSum, heldTime;     /* coverage: time between distinct values, and the part of each gap beyond one refresh */
    uint64_t targets, targetOff;
    double lastTarget;
} g_tot;

static double glass_now(void) { return CACurrentMediaTime(); }

static void glass_wall(char *buf, size_t len) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    struct tm tm;
    localtime_r(&tv.tv_sec, &tm);
    snprintf(buf, len, "%02d:%02d:%02d.%03d", tm.tm_hour, tm.tm_min, tm.tm_sec, (int)(tv.tv_usec / 1000));
}

int mcmtl_glass_mode(void) {
    int m = __atomic_load_n(&g_mode, __ATOMIC_RELAXED);
    if (m < 0) {
        const char *v = getenv("MCMTL_GLASS_TRACE");
        m = v && *v ? atoi(v) : 0;
        if (m < 0) m = 0;
        __atomic_store_n(&g_mode, m, __ATOMIC_RELAXED);
    }
    return m;
}

/* ---- private getters (guarded) ---- */

static double glass_get_double(id obj, SEL sel) {
    if (!obj || ![obj respondsToSelector:sel]) return 0.0;
    return ((double (*)(id, SEL))objc_msgSend)(obj, sel);
}

static int32_t glass_get_composited(id obj) {
    static SEL sel;
    if (!sel) sel = sel_registerName("didComposite");
    if (![obj respondsToSelector:sel]) return -1;
    return ((BOOL (*)(id, SEL))objc_msgSend)(obj, sel) ? 1 : 0;
}

static long long glass_layer_status(void) {
    pthread_mutex_lock(&g_mu);
    CAMetalLayer *layer = g_layer;
    pthread_mutex_unlock(&g_mu);
    SEL sel = sel_registerName("displayCompositingInternalStatus");
    if (!layer || ![layer respondsToSelector:sel]) return -1;
    return (long long)((uint64_t (*)(id, SEL))objc_msgSend)(layer, sel);
}

/* ---- screen / display information (CoreGraphics through dlsym: libmcmetal links Foundation, Metal, QuartzCore and
 * MetalFX only) ---- */

static void glass_screen_info(char *buf, size_t len) {
    long maxFps = 0;
    double minIv = 0, maxIv = 0;
    Class screenCls = objc_getClass("NSScreen");
    id screen = screenCls ? ((id (*)(id, SEL))objc_msgSend)(screenCls, sel_registerName("mainScreen")) : nil;
    if (screen) {
        SEL s = sel_registerName("maximumFramesPerSecond");
        if ([screen respondsToSelector:s]) maxFps = ((long (*)(id, SEL))objc_msgSend)(screen, s);
        minIv = glass_get_double(screen, sel_registerName("minimumRefreshInterval"));
        maxIv = glass_get_double(screen, sel_registerName("maximumRefreshInterval"));
    }
    double hz = -1;
    size_t pw = 0, ph = 0, w = 0, h = 0;
    void *cg = dlopen("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", RTLD_LAZY);
    if (cg) {
        uint32_t (*mainDisplay)(void) = (uint32_t (*)(void))dlsym(cg, "CGMainDisplayID");
        void *(*copyMode)(uint32_t) = (void *(*)(uint32_t))dlsym(cg, "CGDisplayCopyDisplayMode");
        double (*refresh)(void *) = (double (*)(void *))dlsym(cg, "CGDisplayModeGetRefreshRate");
        size_t (*pxW)(void *) = (size_t (*)(void *))dlsym(cg, "CGDisplayModeGetPixelWidth");
        size_t (*pxH)(void *) = (size_t (*)(void *))dlsym(cg, "CGDisplayModeGetPixelHeight");
        size_t (*ptW)(void *) = (size_t (*)(void *))dlsym(cg, "CGDisplayModeGetWidth");
        size_t (*ptH)(void *) = (size_t (*)(void *))dlsym(cg, "CGDisplayModeGetHeight");
        void (*release)(void *) = (void (*)(void *))dlsym(cg, "CGDisplayModeRelease");
        if (mainDisplay && copyMode) {
            void *mode = copyMode(mainDisplay());
            if (mode) {
                if (refresh) hz = refresh(mode);
                if (pxW) pw = pxW(mode);
                if (pxH) ph = pxH(mode);
                if (ptW) w = ptW(mode);
                if (ptH) h = ptH(mode);
                if (release) release(mode);
            }
        }
    }
    if (maxFps > 0) g_refresh = 1.0 / (double)maxFps;
    snprintf(buf, len,
             "NSScreen maximumFramesPerSecond=%ld minimumRefreshInterval=%.3fms maximumRefreshInterval=%.3fms; "
             "CG mode %zux%zu pt %zux%zu px %.2fHz",
             maxFps, minIv * 1e3, maxIv * 1e3, w, h, pw, ph, hz);
}

/* ---- reporter ---- */

static int glass_cmp_shown(const void *x, const void *y) {
    const double a = ((const glass_shown *)x)->t, b = ((const glass_shown *)y)->t;
    return a < b ? -1 : a > b ? 1 : 0;
}

static int glass_cmp_double(const void *x, const void *y) {
    const double a = *(const double *)x, b = *(const double *)y;
    return a < b ? -1 : a > b ? 1 : 0;
}

static void *glass_reporter(void *arg) {
    pthread_setname_np("mcmtl glass reporter");
    static glass_shown shown[GLASS_MAX_SHOWN];
    static double iv[GLASS_MAX_SHOWN];
    const char *pe = getenv("MCMTL_GLASS_TRACE_PERIOD_MS");
    const double period = (pe && atof(pe) > 0 ? atof(pe) : 1000.0) / 1000.0;
    uint64_t cursor = 0;
    double last = glass_now();
    for (;;) {
        @autoreleasepool {
            usleep((useconds_t)(period * 1e6));
            const double now = glass_now();
            const double span = now - last;
            last = now;
            const long long status = glass_layer_status();
            uint32_t reg = 0, nShown = 0, notShown = 0, lost = 0, dropped = 0, comp = 0, compKnown = 0;
            double latSum = 0, latMax = 0;
            pthread_mutex_lock(&g_mu);
            const uint64_t from = cursor;
            const uint64_t end = atomic_load_explicit(&g_next, memory_order_acquire);
            while (cursor < end) {
                glass_rec *r = &g_ring[cursor & GLASS_MASK];
                uint32_t st = atomic_load_explicit(&r->state, memory_order_acquire);
                if (atomic_load_explicit(&r->seq, memory_order_relaxed) != cursor || st == REC_FREE) {
                    /* A registration that found its slot busy (overflow: never written), or one being written right now
                     * (only the newest few): wait for the latter. */
                    if (end - cursor <= 4) break;
                    cursor++;
                    continue;
                }
                if (st == REC_PENDING) {
                    if (now - r->tReg < GLASS_PENDING_TIMEOUT) break;
                    uint32_t expect = REC_PENDING;
                    if (atomic_compare_exchange_strong(&r->state, &expect, REC_FREE)) {
                        reg++;
                        lost++;
                        if (g_out) fprintf(g_out, "F,%llu,%.6f,0,-2,0,0\n", (unsigned long long)cursor, r->tReg);
                        cursor++;
                        continue;
                    }
                    st = expect;
                }
                if (st != REC_DONE) break;   /* the handler is writing it right now */
                reg++;
                if (r->tShown > 0) {
                    if (nShown < GLASS_MAX_SHOWN) {
                        shown[nShown].t = r->tShown;
                        shown[nShown].lat = r->tShown >= r->tReg ? r->tShown - r->tReg : 0;
                        shown[nShown].composited = r->composited;
                        shown[nShown].target = r->tTarget;
                        shown[nShown].seq = cursor;
                        nShown++;
                    } else {
                        dropped++;
                    }
                } else {
                    notShown++;
                    if (g_out) fprintf(g_out, "F,%llu,%.6f,0,-1,0,%.6f\n", (unsigned long long)cursor, r->tReg, r->tTarget);
                }
                atomic_store_explicit(&r->state, REC_FREE, memory_order_release);
                cursor++;
            }
            /* Distinct presentedTime values in time order. */
            qsort(shown, nShown, sizeof shown[0], glass_cmp_shown);
            uint32_t distinct = 0, same = 0, disorder = 0, niv = 0, targets = 0, targetOff = 0;
            uint32_t bins[7] = {0, 0, 0, 0, 0, 0, 0};
            double gapSum = 0, heldTime = 0;
            for (uint32_t i = 0; i < nShown; i++) {
                const glass_shown *s = &shown[i];
                latSum += s->lat;
                if (s->lat > latMax) latMax = s->lat;
                if (s->composited >= 0) {
                    compKnown++;
                    comp += (uint32_t)s->composited;
                }
                int isNew = 1;
                if (g_tot.lastDistinct > 0) {
                    const double dt = s->t - g_tot.lastDistinct;
                    if (dt < -GLASS_SAME * g_refresh) {
                        disorder++;   /* older than a value already counted (handler order): not a new refresh */
                        isNew = 0;
                    } else if (dt < GLASS_SAME * g_refresh) {
                        same++;
                        isNew = 0;
                    } else {
                        const double k = dt / g_refresh;
                        const double rk = round(k);
                        const int bin = k < 0.75 ? 0 : rk <= 1 ? 1 : rk <= 2 ? 2 : rk <= 3 ? 3 : rk <= 7 ? 4 : 5;
                        bins[bin]++;
                        if (bin > 0 && fabs(k - rk) > GLASS_OFF) bins[6]++;
                        iv[niv++] = dt;
                        /* Refresh coverage: a refresh shows a new frame iff a distinct value fell in the refresh before
                         * it, so whatever phase the panel has, the time not covered is the part of each gap beyond one
                         * refresh. On the direct path (gaps = whole refreshes) this equals distinct per second. */
                        gapSum += dt;
                        if (dt > g_refresh) heldTime += dt - g_refresh;
                    }
                }
                if (s->target > 0) {   /* the frame's target refresh (private): distinct values, off the refresh grid */
                    const double dtt = s->target - g_tot.lastTarget;
                    if (g_tot.lastTarget == 0 || dtt >= GLASS_SAME * g_refresh) {
                        if (g_tot.lastTarget > 0) {
                            const double kt = dtt / g_refresh;
                            if (fabs(kt - round(kt)) > GLASS_OFF) targetOff++;
                        }
                        targets++;
                        g_tot.lastTarget = s->target;
                    }
                }
                if (isNew) {
                    distinct++;
                    if (g_tot.firstDistinct == 0) g_tot.firstDistinct = s->t;
                    g_tot.lastDistinct = s->t;
                }
                if (g_out) {
                    fprintf(g_out, "F,%llu,%.6f,%.6f,%d,%d,%.6f\n", (unsigned long long)s->seq, s->t - s->lat, s->t, s->composited,
                            isNew, s->target);
                }
            }
            double ivMed = 0, ivP99 = 0, ivMax = 0;
            if (niv) {
                qsort(iv, niv, sizeof iv[0], glass_cmp_double);
                ivMed = iv[niv / 2];
                ivP99 = iv[(uint32_t)floor(0.99 * (double)(niv - 1))];
                ivMax = iv[niv - 1];
            }
            char wall[32];
            glass_wall(wall, sizeof wall);
            char line[768];
            int n = snprintf(line, sizeof line,
                             "wall=%s t=%.3f distinct=%u hz=%.1f glassHz=%.1f tgt=%u tgtOff=%u shown=%u notShown=%u lost=%u same=%u "
                             "iv:sub=%u,1r=%u,2r=%u,3r=%u,4-7r=%u,8r+=%u,off=%u ivMed=%.2fms ivP99=%.2fms ivMax=%.2fms "
                             "comp=%u/%u status=%lld lat=%.2f/%.2fms registered=%u",
                             wall, now, distinct, span > 0 ? distinct / span : 0.0,
                             gapSum > 0 ? (1.0 - heldTime / gapSum) / g_refresh : 0.0, targets, targetOff, nShown, notShown, lost,
                             same, bins[0], bins[1],
                             bins[2], bins[3], bins[4], bins[5], bins[6], ivMed * 1e3, ivP99 * 1e3, ivMax * 1e3, comp, compKnown,
                             status, nShown ? latSum / nShown * 1e3 : 0.0, latMax * 1e3, reg);
            if (disorder && n > 0 && (size_t)n < sizeof line) n += snprintf(line + n, sizeof line - (size_t)n, " disorder=%u", disorder);
            if (dropped && n > 0 && (size_t)n < sizeof line) n += snprintf(line + n, sizeof line - (size_t)n, " dropped=%u", dropped);
            const uint64_t ovf = atomic_load(&g_overflow);
            if (ovf && n > 0 && (size_t)n < sizeof line) snprintf(line + n, sizeof line - (size_t)n, " overflow=%llu", (unsigned long long)ovf);
            if (cursor != from) {
                fprintf(stderr, "[mcmtl glass] %s\n", line);
                if (g_out) {
                    fprintf(g_out, "S,%s\n", line);
                    fflush(g_out);
                }
            }
            g_tot.periods++;
            g_tot.registered += reg;
            g_tot.shown += nShown;
            g_tot.notShown += notShown;
            g_tot.lost += lost;
            g_tot.distinct += distinct;
            g_tot.same += same;
            g_tot.gapSum += gapSum;
            g_tot.heldTime += heldTime;
            g_tot.targets += targets;
            g_tot.targetOff += targetOff;
            g_tot.disorder += disorder;
            g_tot.composited += comp;
            g_tot.compKnown += compKnown;
            for (int i = 0; i < 7; i++) g_tot.bins[i] += bins[i];
            g_tot.latSum += latSum;
            g_tot.latN += nShown;
            if (latMax > g_tot.latMax) g_tot.latMax = latMax;
            if (compKnown) {
                if (comp == 0) g_tot.secDirect++;
                else if (comp == compKnown) g_tot.secComposited++;
                else g_tot.secMixed++;
            }
            pthread_mutex_unlock(&g_mu);
        }
    }
    return NULL;
}

void mcmtl_glass_summary(void) {
    if (!mcmtl_glass_mode()) return;
    pthread_mutex_lock(&g_mu);
    const double span = g_tot.lastDistinct - g_tot.firstDistinct;
    char line[1024];
    snprintf(line, sizeof line,
             "total distinct=%llu over %.1fs (%.1f/s) glassHz=%.1f tgt=%llu tgtOff=%llu shown=%llu notShown=%llu lost=%llu same=%llu "
             "iv:sub=%llu,1r=%llu,2r=%llu,3r=%llu,4-7r=%llu,8r+=%llu,off=%llu comp=%llu/%llu periods direct/composited/mixed=%llu/%llu/%llu "
             "lat=%.2f/%.2fms refresh=%.3fms registered=%llu disorder=%llu",
             (unsigned long long)g_tot.distinct, span, span > 0 && g_tot.distinct > 1 ? (double)(g_tot.distinct - 1) / span : 0.0,
             g_tot.gapSum > 0 ? (1.0 - g_tot.heldTime / g_tot.gapSum) / g_refresh : 0.0, (unsigned long long)g_tot.targets,
             (unsigned long long)g_tot.targetOff,
             (unsigned long long)g_tot.shown, (unsigned long long)g_tot.notShown, (unsigned long long)g_tot.lost,
             (unsigned long long)g_tot.same, (unsigned long long)g_tot.bins[0], (unsigned long long)g_tot.bins[1],
             (unsigned long long)g_tot.bins[2], (unsigned long long)g_tot.bins[3], (unsigned long long)g_tot.bins[4],
             (unsigned long long)g_tot.bins[5], (unsigned long long)g_tot.bins[6], (unsigned long long)g_tot.composited,
             (unsigned long long)g_tot.compKnown, (unsigned long long)g_tot.secDirect, (unsigned long long)g_tot.secComposited,
             (unsigned long long)g_tot.secMixed, g_tot.latN ? g_tot.latSum / (double)g_tot.latN * 1e3 : 0.0, g_tot.latMax * 1e3,
             g_refresh * 1e3, (unsigned long long)g_tot.registered, (unsigned long long)g_tot.disorder);
    fprintf(stderr, "[mcmtl glass] %s\n", line);
    if (g_out) {
        fprintf(g_out, "T,%s\n", line);
        fflush(g_out);
    }
    pthread_mutex_unlock(&g_mu);
}

static void glass_start(void) {
    const char *path = getenv("MCMTL_GLASS_TRACE_OUT");
    if (path && *path) {
        g_out = fopen(path, "a");
        if (!g_out) fprintf(stderr, "[mcmtl glass] cannot open %s\n", path);
    }
    char info[384];
    glass_screen_info(info, sizeof info);
    char wall[32];
    glass_wall(wall, sizeof wall);
    fprintf(stderr, "[mcmtl glass] on (mode %d, pid %d, wall %s): %s; interval bins in units of %.3f ms\n", g_mode, getpid(), wall,
            info, g_refresh * 1e3);
    if (g_out) {
        fprintf(g_out, "I,%d,%.6f,%s,%s\n", g_mode, glass_now(), wall, info);
        fflush(g_out);
    }
    pthread_t thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_attr_set_qos_class_np(&attr, QOS_CLASS_UTILITY, 0);
    pthread_create(&thread, &attr, glass_reporter, NULL);
    pthread_attr_destroy(&attr);
    atexit(mcmtl_glass_summary);
}

void mcmtl_glass_will_present(id<CAMetalDrawable> drawable) {
    if (!drawable || !mcmtl_glass_mode()) return;
    pthread_once(&g_once, glass_start);
    CAMetalLayer *layer = drawable.layer;
    if ((__bridge void *)layer != g_layerRaw) {   /* new layer (startup, fullscreen switch): keep it for status reads */
        pthread_mutex_lock(&g_mu);
        g_layer = layer;
        g_layerRaw = (__bridge void *)layer;
        pthread_mutex_unlock(&g_mu);
    }
    const uint64_t seq = atomic_fetch_add_explicit(&g_next, 1, memory_order_acq_rel);
    glass_rec *r = &g_ring[seq & GLASS_MASK];
    if (atomic_load_explicit(&r->state, memory_order_acquire) != REC_FREE) {
        atomic_fetch_add(&g_overflow, 1);   /* the reporter is more than GLASS_RING presents behind */
        return;
    }
    r->tReg = glass_now();
    r->tShown = 0;
    r->tTarget = 0;
    r->composited = -1;
    atomic_store_explicit(&r->seq, seq, memory_order_relaxed);
    atomic_store_explicit(&r->state, REC_PENDING, memory_order_release);
    [drawable addPresentedHandler:^(id<MTLDrawable> shownDrawable) {
        uint32_t expect = REC_PENDING;
        if (atomic_load_explicit(&r->seq, memory_order_relaxed) != seq ||
            !atomic_compare_exchange_strong(&r->state, &expect, REC_WRITING)) {
            return;   /* timed out and reused */
        }
        static SEL selTarget;
        if (!selTarget) selTarget = sel_registerName("targetPresentationTimestamp");
        r->tShown = shownDrawable.presentedTime;
        r->tTarget = glass_get_double(shownDrawable, selTarget);
        r->composited = r->tShown > 0 ? glass_get_composited(shownDrawable) : -1;
        atomic_store_explicit(&r->state, REC_DONE, memory_order_release);
    }];
}
