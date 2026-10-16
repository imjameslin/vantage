/*
 * mcmetal_glass.h -- on-glass cadence trace (diagnostics only, off by default; results/BASELINE-DEFAULT.md).
 *
 * Environment MCMTL_GLASS_TRACE=1 turns it on (read once). Every present the decoder registers
 * (mcmtl_mailbox_will_present: fused and present-pass frames, mailbox or not) gets a presented handler. A reporter
 * thread prints one line per second to stderr (the game's launch.log), tag "[mcmtl glass]":
 *
 *   distinct   DISTINCT -[MTLDrawable presentedTime] values (two drawables reported within a quarter refresh of each
 *              other count once). On the direct path presentedTime is the refresh that showed the frame, so this is the
 *              on-glass frame rate. On a COMPOSITED layer (didComposite = YES) it is not: presentedTime is when
 *              WindowServer took the frame, off the refresh grid, and can exceed the refresh rate (BASELINE-DEFAULT.md)
 *   hz         distinct / reporter period
 *   glassHz    refreshes per second that had a new frame to show, independent of the panel's phase: 1/refresh x (1 - the
 *              part of each gap between distinct values beyond one refresh / the gaps' total). Equals hz on the direct
 *              path; on a composited layer it is the rate a 120 Hz panel can show at most
 *   tgt / tgtOff  distinct -[CAMetalDrawable targetPresentationTimestamp] values (private; 0 when it reports none) and
 *              how many of their gaps are off the refresh grid
 *   shown / notShown / lost   presents with presentedTime > 0 / == 0 (replaced before the panel took them) / handler
 *              never ran within 2 s
 *   iv:        intervals between consecutive distinct values in units of the panel's shortest refresh (8.33 ms at
 *              120 Hz): 1r, 2r, 3r, 4-7r, 8r+, "sub" (< 0.75 r: variable refresh only) and "off" (> 0.25 r away from a
 *              whole number of refreshes); ivMed / ivP99 / ivMax in ms
 *   comp       shown presents with the private -[CAMetalDrawable didComposite] = YES / shown presents where it is known.
 *              0/N = every frame went direct to display (no WindowServer composition)
 *   status     -[CAMetalLayer displayCompositingInternalStatus] (private), read once per period on the reporter thread
 *   lat        mean / max time from present registration to presentedTime
 *
 * At exit (atexit) a "total" line summarizes the whole run. MCMTL_GLASS_TRACE_OUT=<file> also writes a raw CSV:
 * I (screen info), F (seq, registered, presentedTime, didComposite, distinct flag, target timestamp) per present, S (the
 * per-second line), T (the total).
 * tools/glass_report.py turns the lines of the bench's measured window into a per-run report.
 *
 * Cost with the trace off: one load of a static int per present.
 */
#ifndef MCMETAL_GLASS_H
#define MCMETAL_GLASS_H

#import <Foundation/Foundation.h>
#import <QuartzCore/QuartzCore.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Trace mode from MCMTL_GLASS_TRACE (0 = off). Cheap after the first call. */
int mcmtl_glass_mode(void);

/* Register the present of `drawable` (before presentDrawable:). No-op when off. Render thread. */
void mcmtl_glass_will_present(id<CAMetalDrawable> drawable);

/* Prints the totals since start (also run at exit). */
void mcmtl_glass_summary(void);

#ifdef __cplusplus
}
#endif

#endif
