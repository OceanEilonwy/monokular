# Motion: after round 1

The integrated `motion` branch (dev1's overlay track and dev2's screens
track rebased onto the foundation, plus the lead's review fixes) measured
against [the baseline](baseline.md) on the same emulators, with the same
scripts. Read the baseline's caveats first: both emulators render through
SwiftShader on a host shared with three other emulators, Gradle and a Rust
build, and the host's load during this round ran between 20 and 170. Only
**counts and ratios from the same session** mean anything; milliseconds
against a phone mean nothing.

## How the after numbers were taken

Where the baseline ran one build, this round ran **before and after
alternately in one session on one emulator** (`ab.sh`: install the
baseline's `app-debug-fake.apk`, run the flow, install the integrated
build over the same data, run it again, twice over), so the two sit under
the same host load and the same shift. The baseline's own numbers are kept
in the tables for reference; the A/B pair is what the verdict rests on.
The raw `gfxinfo`, `framestats` and `meminfo` dumps are in the lead's
scratch folder under `measure-ab/<serial>/<before|after>/`.

The dev stack was shared with the other tills, so the shift held every sale
rung up during the round, not the baseline's 24.

## Numbers

### APK (`apkanalyzer`, the same commit's debug and release builds)

| | before (f274ad7) | after (motion 266a201) | change |
|---|---|---|---|
| debug file / download | 23 637 209 / 19 684 630 B | 23 998 041 / 19 771 541 B | +361 KB / +87 KB |
| release (unsigned) file / download | 7 951 910 / 4 517 799 B | 8 050 214 / 4 576 474 B | +98 KB / +59 KB |

No new dependency: the growth is the new code (`Nav.kt`, `Windows.kt`,
`Presence.kt`, the transitions) and `SharedTransitionLayout` and
`PredictiveBackHandler` being reached for the first time in the
release build, so R8 keeps them.

### Tablet (emulator-5560), before and after alternately in one session

**Shift with 24 sales, 12 swipes** (the `MainActivity` window's last 120
frames; load 27–76):

| run | frames (process) | janky | p50 | p90 | p99 | max | >33 ms |
|---|---|---|---|---|---|---|---|
| before, 11:23 | 384 | 38% | 50.8 | 83.6 | 100.7 | 116.9 | 100 |
| after, 11:25 | 347 | 36% | 46.2 | 68.8 | 95.2 | 96.4 | 99 |
| before, 11:18 (load 115) | 258 | 57% | 51.7 | 80.1 | 300.3 | 317.0 | 108 |
| after, 11:20 (load 172) | 197 | 61% | 57.9 | 84.5 | 117.1 | 183.6 | 111 |

Per phase (p50 / p95, ms): before layout 0.1 / 0.2, record 10.4 / 26.0,
raster 24.4 / 47.5; after layout 0.1 / 0.1, record 7.9 / 29.8, raster
18.8 / 37.0. **No regression**: `animateItem`, the keyed static items and
the per-row badge `AnimatedContent` cost nothing a scroll can see, and
the rows' shapes made once and the clock read only by live rows take a
little off the record phase.

**Enlarged code: open from the sale and tap closed, 3 times** (load
24–34):

| run | frames | per frame p50 | p90 | p99 | max |
|---|---|---|---|---|---|
| before, 11:24 | 6 | 68.5 | 80.9 | 80.9 | 80.9 |
| after, 11:26 | 118 | 67.9 | 138.0 | 266.8 | 283.8 |

Per phase (p50 / p95): before (one cut frame each) anim 10.2 / 12.3,
**layout 48.1 / 55.6**, record 4.0 / 4.9, raster 9.0 / 11.4. After (a
grow of ~20 frames each) anim 1.6 / 13.6, layout 4.8 / 23.8, record
15.7 / 62.4, **raster 28.4 / 80.5**, and the frame's start waits 9.6 / 67.8
ms behind the previous one. The UI thread's work per frame fell by an
order of magnitude (the code is one shared layer, never re-laid out: the
cut's 48 ms layout is gone); what is left is SwiftShader rasterising two
full screens and a scaled layer in software, twenty times instead of
once. The longest frame is 3.5× the cut's, which fails the round's "no
frame over 2× the old p95" rule **on this emulator**; the baseline's
caveat and decision 107 say why that rule can only be judged on a device
with a GPU, and that is the first thing round 2 measures.

**Overlay, lit bubble alone over the home screen, 5 s** (before, load
30): 272 frames, 11% janky, p50 26 ms. The after run was cut short by
the round's deadline (the script was still on its first before pass);
dev1's numbers for the same flow on emulator-5554 are in the round's
report (before 139 frames at p50 48 ms, after 302 at p50 22 ms with the
pulse by design; panel up and still: 5 frames in 5 s against 44).

### Phone (emulator-5558)

The phone's A/B (`ab.sh emulator-5558 shift code`) was running when the
round's deadline fell: its first before pass gave shift-scroll 209
frames, 69% janky, window p50 69.6 / p90 116.9 / p99 164.6 / max 177.4
(load 36), against the baseline's 361 frames, 41% janky, p50 50 / p90 77
/ p99 101 at the baseline's hour. The after pass, the code flow, the
overlay flows, memory after ten raise/lower cycles and the cold starts
(debug and the signed release `app-release-after.apk`) **were not
completed by the lead**; the scripts (`ab.sh`, `ab-overlay.sh`,
`ab-cold.sh`, `overlay-flow.sh` in the lead's scratch folder) are ready
to rerun in one command each and the shift holds the baseline's 24 sales
again (`make-sales.sh 6 12 6`). The devs' own before/after pairs on
5554 and 5556 stand in the round's report: dev1's overlay flows (raise +
lower ×5 from 62% to 15% janky, p50 57 to 16 ms; drag + snap 84% to 32%;
PSS at rest back at baseline with `ModulateAlpha`), dev2's screens
(code grow passes the 2× rule, shift-to-sale fails it on software GL for
the raster reason above; native heap +10 MB after round trips on
SwiftShader, to be read on a device).

### Memory and cold start

Not re-measured by the lead this round (see above). dev1: TOTAL PSS at
rest after ten raise/lower cycles 88.5–100.5 MB after against 89.8–95.1
MB before, native heap 7.7–11.1 MB against 9.3–13.9 MB. dev2: PSS 108.9
MB after ten shift↔sale round trips against 93.7 MB before, native heap
22.6 against 12.3 MB, which on SwiftShader is where layer textures live
("Graphics" reads 0); a device number is a blocker until it exists.

## The code with a performance eye, after

What the round fixed of the baseline's list (`baseline.md`, "What to fix"):

1. **The lit bubble's pulse** runs only while a code waits for its money
   and the panel is down (`Panel.pulses`); the docked bubble never pulses,
   and from this round the tablet's free bubble stops while the panel is
   up beside it (the lead's fix, found in review: it had pulsed the whole
   time the cashier read the panel). Panel up and still: 5 frames in 5 s
   against 44.
2. **One clock per screen** (`rememberNowSeconds`, a `LongState`), read
   only inside `Countdown` or a live shift row; recompositions counted in
   the instrumented tests: five ticks recompose `Countdown` five times and
   the shift, the sale, the pill, the code and the ring zero times. The
   overlay's `StatusRow` still starts its own timer (one per panel window,
   so no worse than before); it is the one caller left of the old
   `secondsLeft(expiresAt)`, which goes when it switches.
3. **`PanelActions` and `TillActions` are `@Immutable`**, built once per
   window or window mode.
4. **One bitmap per code** (the foundation's cache); the code is a shared
   element between the sale and its enlarged screen and is never
   re-encoded or re-laid out by a transition (instrumented: `QrCode`
   recomposes 0 times during the grow).
5. **Window churn**: the panel and bubble windows are added once per open
   and removed after their exit lands (`removeView`); `removeViewImmediate`
   only in `stop()`. A pure state machine (`Handoff`) orders the two
   windows so there is one bubble on screen at every frame.
6. **`BubbleFrame.snap`** is an `Animatable` spring seeded with the
   `VelocityTracker`'s velocity; `ValueAnimator` is gone from the app.
7. **The root no longer recomposes on a runner update**: the flows are
   held as `State`s and read behind `derivedStateOf` by the screen that
   needs them.
8. **Per-row allocations**: four row shapes made once, the row model in
   `remember(sale, seconds)`.
9. **Brightness** is set once the enlarged code has landed, and given
   back as the shrink starts.

Checked, and right: every animated value in the new code is read in a
`graphicsLayer {}`, `drawBehind {}`, `offset {}` or `layout {}` lambda or a
`Canvas` draw; the ring's strokes are built once per size; no
`delay`-chained choreography (the three `delay(Motion.Dwell.PAID_MS)` are
the one dwell in its three owners, and the clock's `delay` to the next
second); no `Handler.postDelayed`, no reflection, no frame loop of its own.
Every spring and tween comes from `Motion`; reduced motion is read once in
`rememberMotion()` and every preset snaps or quick-fades under `Still`.

## Review findings

Fixed on the branch by the lead (`266a201`):

- The tablet's free bubble pulsed at ~56 fps the whole time the floating
  panel was up beside it (`Panel.pulses` was handed `panelUp = false` for
  every free bubble). The bubble's window now hears the panel open; device
  test `aPanelUpBesideItStopsThePulse`.
- The bubble's edge snap ran in the overlay's scope, so a bubble removed
  mid-snap kept moving a detached window until the spring landed, one
  caught exception a frame. `BubbleFrame` cancels it on detach.

Open, by track, in the round's report.

## Clips

`after/`, 960 px tall, each under half a megabyte, from the devs' emulator
recordings (software GL: what moves, not how smoothly):

| Clip | What it shows | Spec |
| --- | --- | --- |
| `phone-panel-raise-lower.mp4` | The panel growing out of the bubble and collapsing back into it; one bubble on screen throughout | M1 |
| `phone-grabber-fling.mp4` | A grabber fling: its speed carried into the collapse; a short drag springing back | M2 |
| `phone-bubble-drag-snap.mp4` | The bubble dragged and let go: a spring from the finger's speed to the near edge | M3 |
| `phone-panel-paid-fold.mp4` | The code folding into the paid tick in place; the panel collapsing into the green bubble, which fades to idle only after landing | M5, M9 |
| `phone-panel-paid-next-sale.mp4` | Paid, then the next waiting sale's code growing back out of the same circle; the bubble back to lit | M9 |
| `phone-row-grows-into-sale.mp4` | A shift row growing into its sale and shrinking back; the back gesture scrubbing it | M7 |
| `phone-code-grows-from-card.mp4` | The code card growing into the enlarged white screen, the code one solid layer throughout | M8 |

## Round 2, screens track: on a GPU

The phone attached over adb (the owner's Pixel 9 Pro) was not used: it
needs the owner's say-so to install anything, and nobody asked them in
this round. Instead dev2's emulator (emulator-5556, `Pixel_9_Pro` AVD,
1280 × 2856) was restarted with `-gpu host`: SurfaceFlinger reports
`AMD Radeon RX 6800 XT (radeonsi)` instead of SwiftShader, so raster is a
real GPU's and the frame times mean something. Before (`f274ad7`'s debug
build) and after alternate in one session with the same flows
(`dev2-ab.sh` in the scratch folder, wrapping the lead's `flows-after.sh`:
shift scroll, the code opened and tapped closed three times, ten shift to
sale round trips), twice each; each flow's numbers are the
`MainActivity` window's framestats (the last 120 frames), in ms.

### Frames

| Flow | Build | Frames | p50 | p90 | p99 | max | Limit (2 × before p95) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Shift scroll (the round's whole shift, 12 swipes) | before | 120 | 16.8 | 16.8–16.9 | 16.9–17.0 | 16.9–17.3 | 34 |
| | round 1 (`266a201`) | 120 | 16.8 | 16.9–20.4 | 17.0–26.2 | 17.1–33.5 | |
| Code enlarge and shrink × 3 | before (cuts) | 6–20 | 16.8–66.8 | 66.8–117.0 | 83.4–117.9 | 83.4–117.9 | 166–234 |
| | round 1 | 120 | 16.8 | 16.9–38.2 | 50.1 | 50.1 | |
| | round 2 (this branch) | 120 | 16.8–16.9 | 33.5–47.4 | 100.1–133.5 | 100.1–133.5 | |
| Shift ↔ sale × 10 | before (cuts) | 20–42 | 50.2–83.4 | 83.5–134.0 | 124.2–450.2 | 124.2–450.2 | 166–266 |
| | round 1 | 120 of ~680 | 16.8 | 20.9–24.8 | 83.5–97.5 | 83.5–100.1 | |
| | round 2 | 120 of ~430–520 | 16.8 | 17.0–33.5 | 17.5–66.8 | 18.3–66.8 | |

**The 2 × rule passes on a GPU** for every flow: no after frame is longer
than twice the before p95, and the grows' median frame is the display's
16.7 ms. A cut drew one or two frames of 65–117 ms (the whole new screen
laid out and drawn at once); a grow draws twenty to thirty frames, almost
all on time. The round-2 shift scroll was not re-run because the shift
had been reset to four rows by a reinstall (too short to scroll); the
shift screen's changes this round are the badge's own scope (fewer calls
per tick) and nothing on the scroll path. Load during the round-2 pass
ran from 13 to 135.

### Memory, after ten round trips

`dev2-mem.sh`: install, ten shift ↔ sale round trips, meminfo 10 s and
40 s later, then `am send-trim-memory RUNNING_CRITICAL`, and the GPU cache
from `dumpsys gfxinfo` ("Total GPU memory usage").

| Build | TOTAL PSS at rest (MB) | after trim | Native heap | GPU cache |
| --- | --- | --- | --- | --- |
| before, three runs | 92.6–94.6 | 91.7–92.3 | 12.4–12.8 | 2.2–2.3 MB |
| round 2 before decision 125 | 104.5–106.4 | 105.0–105.2 | 21.2–21.6 | **85.5–86.8 MB** (84 purgeable) |
| round 2, final (`f3346d6`), two runs | 98.6–109.4 | 98.5–106.8 | 17.6–24.9 | **0.2–5.1 MB** |

The GPU cache was the shared surfaces' crossfade: each face of a
full-screen surface went through its own offscreen texture every frame,
and the cache kept them. Decision 125 draws the faces with
`ModulateAlpha` instead, and the cache is back at the baseline's size.
**PSS at rest is still 6–14 MB over the baseline**, nearly all native
heap, and a trim doesn't return it. 2.6 MB of it is Skia's glyph cache
(253 glyphs against 18: the text inside a scaled surface is rasterised at
each size it passes through), the rest a one-time step on the first
transitions that holds flat after (round 1's ten further round trips
added 1.3 MB). It fails the 2 MB rule; decision 126 says what is known and
what isn't.

### Size

Release (minified, unsigned): 8 052 466 B file, 4 585 747 B download,
against round 1's 8 050 214 / 4 576 474 and the baseline's 7 951 910 /
4 517 799: +2 KB / +9 KB this round, +101 KB / +68 KB over the baseline,
which is over the 50 KB rule (round 1 already was).

### Frames for review

`frames/` holds the seeked frames `MotionFramesTest` writes on every run
(`connectedDebugAndroidTest`, pulled to
`build/outputs/connected_android_test_additional_output/`): the code grow
and shrink, the row grow, the rise and the order grow at a quarter, a half
and three quarters, and the paid fold at 60, 120, 240, 300 and 360 ms and
at rest, each half size. The test fails if the enlarging or shrinking code
is not square, is smaller than at rest, or has anything but white on any
side. One artefact is open: `paid-fold-240` shows the hero circle's top
cut along a straight line for the frames around 240 ms, while it is
still settling (gone by 300 ms); its cause wasn't found this round (not
the clip, the resize mode or the wrapper's order, each tried).

## Round 2, integrated build (motion at 6646a2e), by the lead

The integrated branch is the overlay track's eight commits, the screens
track's eight rebased over them, and one integration commit (one
`PaidBuzz` rule for the payment's buzz, the decisions renumbered). Measured
as round 1 was, before (`f274ad7`'s debug build) and after alternately in
one session on one emulator, twice over (`ab-r2.sh` in the lead's scratch
folder, raw dumps under `measure-r2/<serial>/<before|after><n>/`), with
decision 127's changes to the rig: the stack's dead orders expired, two
fresh sales rung up before each pass, the phone (emulator-5558, Pixel 9
Pro, SwiftShader) first and the tablet (emulator-5560, Pixel Tablet)
after it. Flows: the shift scrolled (12 swipes), the enlarged code opened
and tapped closed three times, ten shift ↔ sale round trips then 10 s of
rest for memory and the GPU's caches, the order opened from the sale and
Back five times, the overlay's raise + lower five times and a drag + snap
with memory after 10 s, and three cold starts of the debug build. Frame
numbers are the `MainActivity` window's framestats (its last 120 frames)
unless marked process-wide; the overlay flows are process-wide, since the
overlay's windows are merged in `gfxinfo`.

### Phone (emulator-5558, SwiftShader), before and after alternately

| Flow | before 1 / 2 | after 1 / 2 | 2 × before p95 | verdict |
| --- | --- | --- | --- | --- |
| Shift scroll, window p50 / p90 / p99 / max | 9.9 / 18.9 / 33.5 / 35.9, 9.9 / 19.2 / 26.8 / 27.0 | 10.0 / 18.9 / 24.2 / 33.5, 19.1 / 31.7 / 39.0 / 40.6 | | no frame over 41 ms; the second after pass ran every frame at 19 ms, the first at 10 (see below) |
| Code enlarge + shrink × 3, frames; p50 / p90 / p99 / max | 19; 16.8 / 61.5 / 65.6 / 65.6, 18; 8.4 / 64.1 / 69.6 / 69.6 | 120; 33.9 / 54.9 / 90.1 / 90.6, 120; 30.4 / 56.6 / 91.2 / 91.3 | 130–139 | passes (max 91) |
| Shift ↔ sale × 10, frames; p50 / p90 / p99 / max | 62; 17.0 / 67.7 / 80.8 / 80.8, 60; 14.8 / 66.8 / 83.4 / 83.4 | 120 of 412; 19.0 / 38.2 / 93.1 / 95.3, 120 of 413; 18.6 / 48.8 / 98.6 / 108.6 | 138–146 | passes (max 109) |
| Cold start, debug, median of 3 (ms) | 1739, 1776 | 1835, 1775 | | within noise |
| PSS at rest after the round trips (MB) / native heap | 103.5 / 16.2, 102.8 / 16.2 | **123.1 / 29.7**, **127.3 / 31.2** | +2 MB rule | **fails: +20 to +24 MB PSS, +13.5 to +15 MB native heap** |
| PSS with the bubble up, 10 s after the overlay flows / native | 104.8 / 15.8, 105.3 / 15.5 | **118.9 / 27.1**, **119.7 / 27.5** | | **fails: +14 MB** |
| Dalvik heap at rest (MB) | 9.6 | 14.4 | | +4.8 MB, before any trim |

### Tablet (emulator-5560), before and after alternately

| Flow | before 1 / 2 | after 1 / 2 | 2 × before p95 | verdict |
| --- | --- | --- | --- | --- |
| Shift scroll, window p50 / p90 / p99 / max | 10.2 / 16.8 / 25.0 / 33.5, 40.4 / 52.1 / 66.9 / 66.9 (load 27) | 19.3 / 27.3 / 33.3 / 33.5, 19.2 / 23.1 / 28.1 / 29.4 | | no frame over 34 ms (see the swap note below) |
| Code enlarge + shrink × 3, frames; p50 / p90 / p99 / max | 18; 16.8 / 66.8 / 66.8 / 66.8, 18; 16.8 / 74.4 / 98.0 / 98.0 | 112; 40.7 / 66.8 / 98.8 / 99.1, 110; 37.7 / 69.7 / 90.6 / 93.0 | 134–196 | passes (max 99) |
| Shift ↔ sale × 10, frames; p50 / p90 / p99 / max | 60; 16.9 / 66.9 / 100.3 / 100.3, 60; 18.8 / 92.7 / 120.5 / 120.5 | 120 of 436; 15.5 / 42.2 / 82.2 / 83.9, 120 of 416; 14.2 / 35.8 / 90.8 / 91.1 | 200–241 | passes (max 91, under the cuts' own) |
| Cold start, debug, median of 3 (ms) | 1439, 1438 | 2249 (load 27 just before), 1461 | | see the 5-run numbers below |
| PSS at rest after the round trips (MB) / native heap | 105.4 / 15.3, 105.7 / 15.8 | **117.9 / 27.9**, **120.8 / 26.4** | +2 MB rule | **fails: +12.5 to +15 MB PSS, +11 to +13 MB native** |
| PSS with the bubble up / native | 106.1 / 15.2, 106.6 / 15.2 | **119.2 / 26.2**, (after2 not reached before the run's end) | | **fails: +13 MB** |

**The shift scroll's 19 ms frames.** In three of the four after passes (and
one before pass on the tablet) every scroll frame took 19 ms where the
others took 10. The framestats phases say where: animation 1.1 ms,
layout 0.0, record 1.0–1.2, issue 0.8–0.9 in every pass, before and
after; only the swap (SwapBuffers to FrameCompleted, the wait on the
emulator's compositor) moves, 5.6–6.2 ms in the 10 ms passes and
15.0–15.1 in the 19 ms ones. The app's own work per scroll frame is the
same before and after, to a tenth of a millisecond; the 19 ms is the
software swap chain's pacing on the host, which the first after pass on
the phone (10.0 ms) did not have.

### Overlay (the bubble found by its window; process-wide `gfxinfo`)

Round 1's overlay runs, and this round's first attempt, tapped where a
fresh install puts the bubble; after a drag it is elsewhere, and the
panel never opened. `overlay-flow-r2.sh` reads the bubble's window from
`dumpsys window` and screenshots the panel up
(`review-r2/phone-panel-up-integrated.webp`).

| Flow | Phone before 1 / 2 | Phone after 1 / 2 | Tablet before 1 / 2 | Tablet after 1 / 2 |
| --- | --- | --- | --- | --- |
| Lit bubble alone, 5 s: frames; p50 | 303; 6, 302; 5 | 301; 19, 301; 5 | 302; 5, 302; 16 | 302; 5, 279; 19 |
| Panel up and still, 5 s: frames; p50 / p99 | 200; 46 / 85, 302; 18 / 32 | **5**; 12 / 15, **5**; 12 / 16 | 296; 16 / 85, 307; 18 / 32 | **5**; 10 / 13, **5**; 9 / 9 |
| Raise + lower × 5: frames; janky; p50 / p95 / p99 | 1336; 10.8 %; 16 / 53 / 69, 1479; 1.6 %; 16 / 29 / 32 | 834; 6.4 %; 5 / 36 / 69, 844; 3.6 %; 5 / 27 / 77 | 1465; 6.7 %; 18 / 38 / 48, 1454; 5.3 %; 18 / 38 / 48 | 954; 6.8 %; 17 / 36 / 46, 927; 7.4 %; 17 / 38 / 77 |
| Drag + snap × 2: p50 / p95 / p99 | 18 / 25 / 32, 5 / 16 / 19 | 5 / 16 / 16, 5 / 16 / 16 | 20 / 40 / 101, 30 / 40 / 48 | 18 / 22 / 38, 18 / 24 / 34 |
| PSS with the bubble up, 10 s on (MB) / native | 95.7 / 14.0, 92.2 / 12.2 | 99.8 / 18.6, 99.9 / 18.5 | 97.6 / 14.2, 91.8 / 11.8 | 99.8 / 16.5, 104.5 / 18.8 |

A panel up and still draws 5 frames in 5 s against the baseline's 200–307
(its pulse kept running under the panel), on both; the tablet's free bubble
stops pulsing beside its panel (decision 109). The raise and lower's p99
is 69–77 ms on both builds and both emulators, a window being added; its
p50 falls from 16–18 to 5–17. The drag and snap is smoother on both. The
pulse itself draws every frame in both builds (301 frames in 5 s).

### Memory at rest, after a trim (phone, `mem-r2.sh`)

Fresh sales, ten round trips, the order opened five times, 10 s of rest,
then `am send-trim-memory RUNNING_CRITICAL`:

| Build | PSS on the shift, fresh | PSS at rest / native | after trim / native | glyph cache | GPU caches |
| --- | --- | --- | --- | --- | --- |
| before 1 / 2 | 98.3, 98.2 | 104.6 / 14.8, 109.0 / 15.3 | 104.4 / 14.9, 104.6 / 14.9 | 0.23 MB | 2.3 MB |
| after 1 / 2 | 104.0, 99.0 | **126.0 / 29.5**, **116.4 / 23.5** | **123.4 / 29.5**, **115.2 / 23.6** | **4.30, 2.99 MB** | **16.45 MB (14.3 purgeable)**, 2.38 MB |

After a trim the integrated build holds **10.6 to 19.0 MB more** than the
baseline. `heapprofd` (`heap-r2.sh`, 32 KiB sampling, attached before the
process started) names it: the after build's native heap steps 1.6 MB at
start (0.66 against 0.33 MB of JIT code, 0.75 against 0.23 of glyphs)
and 7.5 MB over ten round trips, of which **5.1 MB is Skia's glyph strike
cache and text atlas** (`SkStrike::prepareImages` under
`AtlasTextOp::onPrepareDraws`, 3.0 MB; `GrDrawOpAtlas::addToAtlas`, 1.8
MB): the text inside a surface scaled as a layer is rasterised again at
every scale the move passes through. The 14.3 MB purgeable GPU texture of
the first after run is the size of one phone screen in RGBA; it did not
recur in the second run. Decision 128 has what to do about it. The
`Graphics` row is 0 on SwiftShader, so on a device part of this moves
from the native heap to graphics memory; the glyph strikes do not.

### Cold start

Phone, debug, median of 3 per pass: before 1739 and 1776 ms, after 1835
and 1775 (load 7–17). Tablet, 5 runs per pass (`ab-cold-r2.sh`), but the
host's load climbed from 21 to 47 through it: debug before 1705 / 1887,
after 1787 / 2364; release before 947 / 3080, after 1187 / 759. Nothing
in those pairs separates from the load; the phone's quieter pairs put the
debug start within 5 %.

### Size

Release, unsigned: 8 068 850 B file, 4 591 565 B download, against the
baseline's 7 951 910 / 4 517 799 (+117 KB / +74 KB) and round 1's
8 050 214 / 4 576 474 (+19 KB / +15 KB this round). Debug: 24 365 820 /
19 825 798 against 23 637 209 / 19 684 630. Over the 50 KB rule, as round
1 was; no dependency was added.

### Verdict

Frames pass everywhere the rule can be read on these emulators: the grows
stay under twice the cuts' p95 on the phone and the tablet, the round trip
and the drag get smoother, and a panel at rest no longer draws. Memory
does not: 10 to 19 MB over the baseline after a trim, with the cause
named (decision 128) and owned by the screens track. Not signed off.

## Round 3, screens track (dev2, on emulator-5556 with the host GPU)

What changed: a moving surface is an empty ground, and its words fade
through at their own size (decisions 138 and 139); a still stage has no
move, which was the empty order page (140); one driver keeps the stage on
the model's screen through Back and gestures (137). The method is the
lead's: `mem-r2.sh` and `heap-r2.sh` with `heap-sites.py`, run from
copies that take the serial and the APKs as arguments (`dev2-r3-mem.sh`
and `dev2-r3-heap.sh` in the scratch folder; the rig itself is the lead's
to commit, R2-O1 and R2-O2). Before is `f274ad7`'s debug build
(`app-debug-fake.apk`), after is this branch at `416a67f`. They ran
alternately, two pairs, on one onboarded till: fresh sales, ten shift ↔
sale round trips, five order opens, 10 s of rest, then a
`RUNNING_CRITICAL` trim. Host load was 50 to 73 throughout.

### Memory at rest after a trim

| Build | PSS fresh (MB) | PSS after trips and trim | Native heap after trim | Glyph cache | GPU caches |
| --- | --- | --- | --- | --- | --- |
| before | 95.5, 94.4 | 95.8, 96.9 | 14.2, 14.5 | 253, 220 KB | 2.17, 2.23 MB |
| round 2 integrated (lead, 5558) | | +10.6 to +19 over before | +11 to +15 | 3.0 to 4.3 MB | up to 16.45 MB |
| round 3 (this branch) | 98.1, 100.7 | 97.9, 100.2 | 14.1, 15.3 | 236, 239 KB | 2.21, 2.32 MB |

**The trips' step is gone.** From fresh on the shift to after the trips
and the trim, round 3 moves by −0.2 and −0.5 MB, the baseline by +0.3 and
+2.4. The glyph cache is the baseline's size, under the 1 MB acceptance,
where round 2's was 3.0 to 4.3 MB, and the GPU caches are within 0.1 MB of
the baseline's. What is left, +2.1 and +3.4 MB of PSS after the trim, is
there at start-up before any move ("PSS fresh": Java heap +2.4 to
+4.6 MB, the larger app's classes and code), so it is not the screens'
moves. It is just over the 2 MB rule on one pair and over it on the
other, at a host load that moved the baseline itself by 2.1 MB between
its own two runs.

**Top native sites** (`heap-sites.py … after-start`, heapprofd at 32 KiB
sampling, attached before the process, ten round trips): no
`SkStrike::prepareImages` or `GrDrawOpAtlas::addToAtlas` site among the
top 60, against 3.0 and 1.8 MB in round 2. The largest unreleased site
after start-up is 0.07 MB (minikin's layout cache, the collector's mark
stack, JIT code). Unreleased after start-up comes to −0.8 MB in all.

### Frames (`dev2-ab.sh`, `MainActivity` window, ms)

| Flow | Build | Frames | p50 | p90 | p99 | max | Limit (2 × before p95) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Code enlarge and shrink × 3 | before (cuts) | 22, 22 | 34.5, 34.4 | 217, 185 | 227, 242 | 227, 242 | about 370 to 400 |
| | round 3 | 58, 61 | 61.0, 50.2 | 224, 243 | 321, 317 | 321, 317 | |
| Shift ↔ sale × 10 | before (cuts) | 42, 44 | 173, 167 | 234, 235 | 335, 252 | 335, 252 | about 470 to 500 |
| | round 3 | 120 of 286, 120 | 46.0, 50.1 | 193, 250 | 291, 340 | 303, 353 | |
| Shift scroll | before | 13, 16 | 40.5, 101 | 53, 251 | 58, 2388 | 58, 2388 | not readable |
| | round 3 | 13, 15 | 50.7, 84.2 | 118, 268 | 280, 503 | 280, 503 | |

The grows are within twice the cuts' p95 in both pairs. Their median is
not the display's 16.7 ms here: at a host load of 50 or more, the
baseline's plain scroll drew a 2.4 s frame and medians of 40 to 101 ms,
against 16.8 ms on this rig in round 2. So this table says nothing is
wrong; it can't say how it feels. The R2-O3 re-run of the integrated
tables at p50 ≤ 17 ms needs a quiet host, and that is the lead's
integration pass.

### Size

Release (minified, unsigned): 8 068 850 B file, 4 594 058 B download.
That is the same file size as round 2's integrated build, and +117 KB /
+76 KB over the baseline (over the 50 KB rule, as rounds 1 and 2 were).

### Device tests

113 then 114 instrumented tests green on emulator-5556:
`MotionFramesTest` (18), `InterruptTest` (45 cells: three moves by five
interruptions at 25, 50 and 75 %), `StageBackTest` (7), `StageTest`,
`LateReceiptTest`, and the overlay track's suites unchanged. The seeked
frames are in `frames/` (README, round 3 section), and
`after/r3-row-grow-and-back.mp4` is a recording of a row growing into its
sale and back on this build.

## Round 3, integrated build (motion at 9f55280), by the lead

The integrated branch is round 2's `3124bf5`, the overlay track's six
round-3 commits, then the screens track's six replayed over them with
their decisions renumbered 137 to 143 (decision 144). Measured as round 2
was (`ab-r3.sh`, a copy of `ab-r2.sh` with this build's APK; raw dumps
under `measure-r3/<serial>/<before|after><n>/` in the lead's scratch
folder): before is `f274ad7`'s debug build, after is this branch, run
alternately twice over on each emulator, the phone (emulator-5558, Pixel 9
Pro, SwiftShader) and the tablet (emulator-5560) at the same time to fit
the round, both freshly onboarded on the shared dev stack with this
build (the stack had been reset and the rig's key no longer worked; the
fake Square is shared, so sales rung up by every agent's rig reach both
tills, which is why the shift grew from a few rows to a scrolling list
between the first and second before passes). Host load was 31 to 59
through the after passes and up to 148 through the second before pass:
the frame medians below are the host's swap chain more than the app
(`phases.py`: animation, layout and record together are under 2 ms a
frame on the shift and under 30 on the grows), so they are read against
each other, not against 16.7 ms.

### Phone (emulator-5558, SwiftShader), before and after alternately

| Flow | before 1 / 2 | after 1 / 2 | 2 × before p95 | verdict |
| --- | --- | --- | --- | --- |
| Shift scroll, window p50 / p90 / p99 / max | (13 frames: not yet scrollable), 70.4 / 120.7 / 134.8 / 136.0 | 116.9 / 200.4 / 253.7 / 254.5 (load 45), 55.3 / 87.6 / 100.7 / 114.5 | | app work under 2 ms a frame on both; the rest is the host |
| Code enlarge + shrink × 3, frames; p50 / p90 / p99 / max | 19; 16.8 / 97.5 / 112.1 / 112.1, 18; 16.8 / 86.4 / 100.2 / 100.2 | 78; 55.7 / 120.1 / **450.1** / 450.1, 96; 48.3 / 76.4 / **316.5** / 316.5 | 200–225 | **fails on one frame: the first enlarge after launch (below)** |
| Shift ↔ sale × 10, frames; p50 / p90 / p99 / max | 61; 33.5 / 102.5 / 356.7 / 356.7, 61; 33.5 / 93.4 / 319.8 / 319.8 | 120 of 400+; 34.6 / 75.2 / 167.4 / 185.9, 120; 33.5 / 76.6 / 117.7 / 128.8 | 300–370 | passes, under the cuts' own max |
| Cold start, debug, median of 3 (ms) | 2988, 2481 | 2845, 2106 | | within noise |
| PSS at rest after the round trips (MB) / native heap | 96.5 / 14.4, 101.1 / 15.7 | 107.8 / 18.0, 113.2 / 19.4 | | +11 to +12 before a trim; the trim table below is the rule's number |
| PSS with the bubble up, 10 s after the overlay flows / native | 103.7 / 15.0, 103.6 / 15.0 | 113.5 / 21.8, 114.6 / 22.0 | | +10 to +11, native +7 |

### Tablet (emulator-5560), before and after alternately

| Flow | before 1 / 2 | after 1 / 2 | 2 × before p95 | verdict |
| --- | --- | --- | --- | --- |
| Shift scroll, window p50 / p90 / p99 / max | (12 frames: not yet scrollable), 66.9 / 104.5 / 117.7 / 133.8 | 113.2 / 209.9 / 359.3 / 365.8 (load 44), 50.1 / 71.3 / 87.7 / 91.8 | | as the phone |
| Code enlarge + shrink × 3, frames; p50 / p90 / p99 / max | 19; 16.8 / 96.5 / 99.6 / 99.6, 18; 13.4 / 72.6 / 83.5 / 83.5 | 73; 66.8 / 150.2 / **570.9** / 570.9, 93; 50.1 / 68.8 / **266.4** / 266.4 | 170–200 | **fails on the same first frame** |
| Shift ↔ sale × 10, frames; p50 / p90 / p99 / max | 62; 33.5 / 100.3 / 368.3 / 368.3, 61; 33.4 / 102.2 / 424.7 / 424.7 | 120; 36.3 / 70.4 / 155.3 / 183.6, 120; 26.6 / 50.2 / 100.1 / 100.2 | 300–400 | passes, under the cuts' own max |
| Cold start, debug, median of 3 (ms) | 2313, 1879 | 2383, 2047 | | within noise |
| PSS at rest after the round trips (MB) / native heap | 98.2 / 14.4, 101.7 / 15.1 | 108.1 / 17.6, 109.1 / 18.4 | | +7 to +10 before a trim |
| PSS with the bubble up / native | 105.4 / 15.1, 105.2 / 15.0 | 110.6 / 17.7, 113.3 / 19.4 | | +5 to +8 |

**The first enlarge after launch.** In all four after passes the enlarged
code's first open in the process (each pass force-stops the app first)
draws one frame whose `IssueDrawCommands` to `SwapBuffers` takes 118 to
384 ms, and the two frames behind it wait on it: 282 / 317 / 251 ms on
the phone's second pass, 543 / 555 / 571 on the tablet's first
(`slow-frames.py` over `framestats-code-open-close.txt`; the app's own
animation, layout and record on those frames are under 30 ms). Every
later open and close in the same pass is under 170 ms. Round 2's
integrated build had an issue phase of at most 9 ms on the same first
open (its max was 91 ms), and the baseline's cut spends its 100 ms in
layout. So round 3's code grow does something on its first frame that
the renderer has not done before in that process: on SwiftShader that
reads as a pipeline compiled on first use, or a first large allocation
(one pass in two flags a slow bitmap upload). The screens track's own
host-GPU run shows the same shape (max 317 to 321 ms against the cuts'
227 to 242), so it is not only SwiftShader. Suspects, in the order to
try: the moving ground's fill drawn at nine times its size under the
overlay's path clip (`Nav.kt`, `FILL`), the words' overlay layers
(`renderInSharedTransitionScopeOverlay` per `words` call), and the code
bitmap's first draw at the enlarged size. It is one hitch per launch and
the code stays scannable through it, but it is a frame rule failure with
the cause not named, so it is not justified here.

### Overlay (the bubble found by its window; process-wide `gfxinfo`)

| Flow | Phone before 1 / 2 | Phone after 1 / 2 | Tablet before 1 / 2 | Tablet after 1 / 2 |
| --- | --- | --- | --- | --- |
| Lit bubble alone, 5 s: frames; p50 | 261; 31, 298; 25 | 301; 19, 300; 5 | 259; 27, 283; 26 | 255; 27, 248; 34 |
| Panel up and still, 5 s: frames; p50 / p99 | 138; 65 / 101, 160; 57 / 85 | **5**; 25 / 34, **5**; 16 / 34 | 218; 42 / 77, 263; 32 / 69 | **5**; 18 / 48, **5**; 16 / 17 |
| Raise + lower × 5: frames; janky; p50 / p95 / p99 | 1047; 26.6 %; 17 / 77 / 150, 1139; 24.9 %; 16 / 69 / 89 | 770; 15.1 %; 20 / 57 / 133, 792; 11.6 %; 16 / 53 / 93 | 528; 77.5 %; 61 / 129 / 200, 649; 70.9 %; 57 / 97 / 133 | 453; 60.5 %; 48 / 105 / 150, 623; 43.0 %; 36 / 73 / 93 |
| Drag + snap × 2: p50 / p95 / p99 | 25 / 34 / 38, 17 / 27 / 34 | 28 / 48 / 300, 22 / 36 / 46 | 57 / 125 / 250, 61 / 97 / 121 | 34 / 85 / 105, 28 / 57 / 81 |

As in round 2: a panel up and still draws 5 frames in 5 s against the
baseline's 138 to 263, the raise and lower is smoother on both (fewer
janky frames, a lower p95), and the tablet's drag and snap halves its
p50. The pulse draws every frame on both builds. The phone's first after
pass has one 300 ms frame in the drag (p99; the pass ran at load 45);
the second has none. `after/r3-overlay-paid-next-sale-lower.mp4` is the
overlay track's recording of this build on emulator-5554: a sale paid,
the next sale's code coming up out of the tick, the panel lowering, and
a new sale arriving (its frame at partial alpha is the open review item
below).

### Memory at rest, after a trim (`mem-r3.sh`, both emulators)

Fresh sales, ten shift ↔ sale round trips, the order opened five times,
10 s of rest, then `am send-trim-memory RUNNING_CRITICAL`, before and
after alternately, twice, on each emulator (host load 31 to 118):

| Build | PSS on the shift, fresh | PSS at rest / native | after trim / native | Java heap after trim | glyph cache | GPU caches |
| --- | --- | --- | --- | --- | --- | --- |
| Phone before 1 / 2 | 97.9, 95.7 | 108.2 / 15.4, 104.5 / 14.9 | 104.4 / 15.0, 104.6 / 15.0 | 17.0, 17.1 | 0.23 MB | 2.25 MB |
| Phone after 1 / 2 | 114.4 (Java heap 31.6, not yet collected), 101.3 | 107.2 / 15.6, 116.7 / 17.7 | **107.1 / 15.7**, **111.0 / 17.2** | 18.7, 18.9 | 0.24 MB | 2.32 MB, 30.5 MB (28.4 purgeable) |
| Tablet before 1 / 2 | 96.1, 99.5 | 108.1 / 14.9, 106.4 / 14.5 | 105.5 / 14.8, 103.2 / 14.1 | 17.7, 17.4 | 0.12, 0.14 MB | 2.22 MB, 10.5 MB (8.4 purgeable) |
| Tablet after 1 / 2 | 105.1, 102.9 | 112.2 / 17.2, 116.8 / 17.6 | **110.3 / 16.6**, **111.8 / 17.1** | 19.0, 19.4 | 0.14 MB | 18.0 MB (15.8 purgeable), 33.6 MB (31.5 purgeable) |

After a trim the integrated build holds **+2.5 to +6.4 MB on the phone
and +4.8 to +8.6 MB on the tablet** over the baseline, against round 2's
+10.6 to +19.0. Round 2's cause is gone: the glyph cache is the
baseline's 0.12 to 0.24 MB (round 2: 3.0 to 4.3 MB) and the native heap
is +0.7 to +2.5 MB (round 2: +11 to +15). What is left is spread thin:
the Java heap +1.6 to +2.0 MB after a trim (a larger app, and one
`graphicsLayer` per `words` call site, ten of them, one per shift row),
the native heap +0.7 to +2.5, and the rest in code and other mappings.
The purgeable GPU textures (one to three phone screens' worth, listed
under `gfxinfo`'s caches) appear on both builds, the baseline's tablet
pass included, and on SwiftShader they are counted against the native
heap; `RUNNING_CRITICAL` did not purge them on either build. The bubble
up is the larger step: +10 to +11 MB PSS on the phone with the native
heap +7 MB (`ab-r3.sh`'s overlay rows above), against round 2's +14 and
+11; that is the overlay's windows and belongs to the overlay track.
Over the 2 MB rule on every pair, by less than round 2 on every pair.

### Review findings (the integrated diff, every line)

Both tracks' round-3 code reads its animated values in layers and draw
lambdas, rewinds its paths rather than allocating them, and keeps its
colours and offsets in value classes; the driver and the back gesture
(`FollowScreen`, `StageBack`) release their hold in `finally` and tell a
cancelled seek apart from a cancelled driver. What is left for round 4:

- The first code enlarge's frame, above (screens track).
- `LateOrderLine` (`Faces.kt`) keeps the last receipt in a remembered
  array written during composition; a `remember { mutableStateOf }`
  updated the same way is the idiom Compose's own docs use for content
  kept through its exit (overlay track, minor).
- `words(over, through, alsoThrough, on)` names its third move
  `alsoThrough`; a small set or `vararg` of moves reads better and
  removes the ceiling of two (screens track, minor).
- `sharedGround` and `faceSurface` keep their leaving side drawn with an
  exit of `fadeOut(..., targetAlpha = 1f)`, an exit that changes
  nothing; it works and is commented, but the comment should name the
  Compose behaviour it works around so the next reader does not "fix" it
  (screens track, minor).
- `Motion.fadeThrough` keeps its 120 ms wait under Still (decision 141),
  so a ring or a label is blank for 120 ms between faces with
  animations off; acceptable, but the designer should see it once on a
  device (both tracks, minor).
- The overlay track's note of a whole-panel frame at partial alpha about
  2 s after a payment under a load of 100: that alpha is `Panel.shown`
  below a third, which only a `lower()` and re-raise draws, and
  `Overlay.show` lowers whenever `usable` flips (`runner.disconnected`
  on a timed-out poll, or `appVisible`); worth a log line on `usable`'s
  changes and a quiet-host run before it is called anything (overlay
  track, open).
- The rebase onto `origin/main` (seventeen files, decision 144) is the
  lead's, before the PR.

### Size

Release, unsigned (`apkanalyzer`): 8 068 850 B file, 4 597 216 B
download, against round 2's 8 068 850 / 4 591 565 (+0 / +5.6 KB; the
dex is +19 KB) and the baseline's 7 951 910 / 4 517 799 (+117 KB / +79 KB
over the 50 KB rule, as rounds 1 and 2 were; no dependency was added).

### Device tests

`connectedDebugAndroidTest` on emulator-5558 at this head: 120
instrumented tests, both tracks' suites together for the first time, all
green (`./gradlew build`, with the unit tests and lint, is green too).
The run uninstalls the till from 5558, which is re-onboarded with
`onboard-r3.sh` before the next measurement.

### Verdict

Frames: the round trip, the raise and lower, the drag and the panel at
rest are all level with or better than round 2 and the baseline on both
emulators, and the shift's own work per frame is under 2 ms on both
builds. One frame fails the rule: the first code enlarge after launch,
250 to 570 ms of renderer work once per process, with the cause not
named (above). Memory: +2.5 to +8.6 MB after a trim, a third of round 2's
step with its cause (text rasterised per scale) gone; of that, the 2 to
3 MB at start-up is accepted (decision 145), which leaves the phone's
second pair and the tablet over the rule by 1 to 6 MB, and +7 MB of
native heap with the bubble up. Start-up: within noise on both. Size:
+5.6 KB over round 2. Not signed off: the first enlarge's frame (screens
track) and the bubble's native heap with the panel lowered (overlay
track) are the items for round 4, with the after-trim pairs re-read on
a quiet host.

## Round 5, integrated build (motion at 24a995f, the lead's fix at a26a242), by the lead

The integrated branch is round 4's `dc51b61`, the overlay track's five
round-5 commits (`motion-dev1` at `d919b48`, a fast-forward), then the
screens track's four replayed over them with their decisions renumbered
175 to 181, and one commit of the lead's (decision 182). Measured as
rounds 3 and 4 were (`ab-r5.sh`, a copy of `ab-r4.sh` with this build's
APK; raw dumps under `measure-r5/<serial>/<before|after><n>/` in the
lead's scratch folder): before is `f274ad7`'s debug build, after is this
branch at `24a995f` (the devs' code; the lead's commit changes what the
stage keeps, not what it draws), run alternately twice over on the phone
(emulator-5558, Pixel 9 Pro AVD, SwiftShader) and then on the tablet
(emulator-5560), both on the shared dev stack with sales rung up on the
fake Square for each pass. Host load was 2 to 24 through the phone's
passes and 9 to 14 through the tablet's, lower than rounds 3 and 4
but still the host's swap chain more than the app in every median, so
the numbers are read against each other, not against 16.7 ms. The
owner's phone was not used (decision 182), so the phone numbers the
brief asks to decide by are still not there.

### Phone (emulator-5558, SwiftShader), before and after alternately

| Flow | before 1 / 2 | after 1 / 2 | verdict |
| --- | --- | --- | --- |
| Shift scroll, window p50 / p90 / p99 / max | 35.7 / 50.7 / 71.8 / 83.5, 27.4 / 44.8 / 66.0 / 66.8 | 35.8 / 50.4 / 66.8 / 79.9, 24.3 / 50.2 / 65.1 / 67.8 | level |
| Code open and close ×3, process janky / p50 / p90 / p95 / p99 | 18 frames (cuts): 55.6 % / 16 / 81 / 81 / 81, 44.4 % / 15 / 81 / 81 / 81 | 120 frames: 29.2 % / 29 / 57 / 69 / 109, 28.5 % / 26 / 48 / 57 / 117 | the grows render 100 more frames than the cuts; the worst frame is the first enlarge, below |
| First code enlarge after launch (framestats, ms) | 66.8 and 82.7: layout 39 and 61 (the code screen laid out on the cut) | 117.2: animation 12.6, layout 8.0, record 4.5, issue 9.8, swap 81.9; then 123.1 and 131.3 waiting on the GPU (wait 10.5 and 85.4) | the app's part is 25 ms; round 3's 250 to 570 ms is gone; what is left is SwiftShader's swap |
| Shift to sale and back ×10, process janky / p50 / p90 / p95 / p99 | 60 frames (cuts): 70.0 % / 16 / 81 / 93 / 150, 42.6 % / 16 / 81 / 81 / 93 | 431 and 428 frames: 13.5 % / 22 / 36 / 61 / 101, 16.4 % / 20 / 36 / 61 / 101 | the grows' p99 is under the cuts' |
| Same, window p50 / p90 / p99 / max | 16.8 / 83.5 / 148.1 / 148.1, 16.2 / 81.9 / 94.7 / 94.7 | 30.2 / 49.7 / 98.1 / 103.5, 24.1 / 50.1 / 100.1 / 102.2 | |
| Order open and Back ×5, process janky / p50 / p90 / p99 | 0.38 % / 5 / 16 / 16, 0.61 % / 5 / 16 / 16 | 1.14 % / 17 / 19 / 32, 0.98 % / 17 / 19 / 21 | the container transform draws the frames the cut did not; no frame over 33 ms |
| Cold start, debug, median of 3 (ms) | 2078, 2107 | 2104, 2153 | +1 to +2 %, within the load's noise |

### Overlay on the phone (the bubble found by its window; process-wide `gfxinfo`)

| Flow | before 1 / 2 | after 1 / 2 | verdict |
| --- | --- | --- | --- |
| Lit bubble alone, 5 s: frames, janky, p50 / p90 | 302, 0 %, 18 / 19; 301, 0 %, 5 / 16 | 302, 0 %, 5 / 16; 301, 0 %, 5 / 16 | the pulse's loop on both, level |
| Panel up and still, 5 s: frames, janky, p50 / p90 | 226, 31.0 %, 42 / 48; 226, 30.5 %, 40 / 53 | 5, 20 %, 14 / 16; 5, 0 %, 14 / 15 | the baseline redraws the panel at 45 fps while nothing moves; this build draws the countdown's tick, once a second |
| Raise and lower ×5: frames, janky, p50 / p90 / p95 / p99 | 1305, 14.2 %, 16 / 46 / 48 / 65; 1300, 15.1 %, 16 / 46 / 48 / 65 | 823, 7.3 %, 5 / 32 / 44 / 73; 829, 7.6 %, 5 / 32 / 44 / 77 | half the janky share; the p99 is the last frames of the collapse under the free bubble's add |
| Drag and snap ×2: frames, janky, p90 / p99 | 236, 3.0 %, 31 / 48; 238, 0.4 %, 16 / 18 | 239, 0 %, 16 / 16; 240, 0 %, 16 / 16 | better |
| Memory with the bubble up, PSS (Java / native heap) | 104 728 (16 608 / 15 220), 105 906 (16 644 / 15 800) | 109 453 (17 588 / 16 576), 110 041 (17 484 / 16 860) | +4.1 to +4.7 MB, untrimmed: see memory below |

### Tablet (emulator-5560), before and after alternately

| Flow | before 1 / 2 | after 1 / 2 | verdict |
| --- | --- | --- | --- |
| Shift scroll, window p50 / p90 / p99 / max | 11.5 / 19.2 / 21.2 / 26.5, 19.2 / 36.1 / 43.7 / 43.8 | 19.1 / 27.2 / 33.4 / 33.5, 19.1 / 21.7 / 31.7 / 32.5 | level; no frame over 33.5 ms on either |
| Code open and close ×3, process janky / p50 / p90 / p95 / p99 | 18 frames (cuts): 33.3 % / 8 / 61 / 61 / 61, 33.3 % / 11 / 81 / 81 / 81 | 123 and 121 frames: 24.4 % / 27 / 48 / 65 / 93, 33.1 % / 29 / 57 / 65 / 133 | the first enlarge's frame again (window max 100 and 167 ms), the rest under the cuts' p90 |
| Shift to sale and back ×10, process janky / p50 / p90 / p95 / p99 | 60 and 61 frames: 66.7 % / 19 / 65 / 69 / 101, 47.5 % / 15 / 61 / 65 / 73 | 428 and 433 frames: 13.1 % / 16 / 38 / 53 / 81, 12.5 % / 16 / 29 / 48 / 77 | better than the cuts at every percentile |
| Same, window p50 / p90 / p99 / max | 19.6 / 66.5 / 100.1 / 100.1, 16.0 / 61.1 / 75.5 / 75.5 | 18.4 / 65.4 / 88.2 / 100.2, 12.0 / 26.2 / 75.2 / 83.4 | |
| Order open and Back ×5 (the tablet's crossfade), process janky / p50 / p99 | 1.46 % / 17 / 36, 0.42 % / 5 / 16 | 0.81 % / 17 / 26, 0.92 % / 17 / 30 | level |
| Cold start, debug, median of 3 (ms) | 1505, 1482 | 1487, 1534 | within noise |

### Overlay on the tablet (the floating panel)

| Flow | before 1 / 2 | after 1 / 2 | verdict |
| --- | --- | --- | --- |
| Lit bubble alone, 5 s: frames, janky | 301, 0 %; 301, 0 % | 302, 0 %; 302, 0 % | the pulse's loop, level |
| Panel up and still, 5 s: frames, janky, p50 | 307, 0.65 %, 5; 308, 1.95 %, 16 | 5, 0 %, 8; 5, 0 %, 8 | the baseline redraws the still panel at 60 fps; this build once a second |
| Raise and lower ×5: frames, janky, p50 / p90 / p95 / p99 | 1502, 3.9 %, 17 / 25 / 32 / 48; 1415, 7.4 %, 16 / 36 / 40 / 61 | 935, 8.0 %, 17 / 32 / 40 / 65; 934, 5.9 %, 16 / 31 / 36 / 53 | level: one pass each way, the same p95 band |
| Drag and snap ×2: frames, janky, p90 / p99 | 250, 11.6 %, 34 / 93; 238, 0.8 %, 19 / 32 | 230, 4.4 %, 32 / 53; 236, 0.9 %, 20 / 36 | level |
| Memory with the bubble up, PSS (Java / native heap) | 108 012 (18 832 / 15 764), 107 055 (17 956 / 15 448) | 109 814 (18 028 / 16 140), 111 728 (19 272 / 16 468) | +1.8 and +4.7 MB, untrimmed |

### Memory at rest, after a trim (`mem-r5.sh`, the phone emulator)

Fresh on the shift, then ten shift to sale round trips and five order
opens, 10 s at rest, then a `RUNNING_CRITICAL` trim; PSS in kB, Java
heap / native heap in brackets. Before and after alternately, twice; the
first pair with the devs' build (`24a995f`), the second with the lead's
course fix on top (`a26a242`, `app-debug-r5b.apk`), which can show
nothing on this flow (one sale's key, opened ten times) and is there for
a day of sales.

| | before 1 / 2 | after 1 / 2 (`24a995f`) | before 3 / 4 | after 3 / 4 (`a26a242`) |
| --- | --- | --- | --- | --- |
| Fresh on the shift | 96 970 (16 672 / 13 928), 96 967 (16 932 / 13 960) | 101 672 (17 852 / 15 848), 105 095 (20 644 / 16 224) | 99 255 (18 816 / 14 164), 98 133 (17 788 / 14 088) | 104 975 (20 680 / 16 108), 101 943 (18 244 / 15 736) |
| After the flow and a trim | 105 121 (17 496 / 14 984), 105 413 (17 616 / 15 052) | 109 458 (18 768 / 16 656; the pass found no live row, so no round trips), 111 532 (18 860 / 17 348) | 105 893 (17 748 / 15 092), 105 620 (17 628 / 15 108) | 111 392 (18 936 / 17 200), 111 795 (18 824 / 17 744) |
| Step over before, trimmed | | +4.3 (no trips), +6.1 MB | | +5.5, +6.2 MB |

So the step is +5.5 to +6.2 MB after a trim, of it Java +1.2 and native
+2.1 to +2.7 MB, and the fresh step is +3.8 to +8.1 MB where decision 145
accepted +2 to +3; round 3 read +2.5 to +6.4 on the same rig and the
screens track read +5.6 and +6.0 on 5556 (decision 181). The untrimmed
"mem rest" of the A/B runs says the same: phone +14.4 MB, tablet +8.3 to
+11.6 MB, most of it garbage the trim takes back. Over the 2 MB rule by
3 to 4 MB, as the last two rounds were, and still with no heap dump
naming it: the two window-sized overlay layers the stage now keeps
(`Layers.GROUND` and `Layers.CODE`) are the round's one new standing
allocation and the first thing to read in a dump.

### Size

Release, unsigned (`apkanalyzer`): 8 085 234 B file, 4 609 471 B
download, against round 4's 8 068 850 / 4 597 216 (+16.4 KB / +12.3 KB;
the dex is +15.7 KB, the screens track's stage geometry and the overlay
track's fold) and the baseline's 7 951 910 / 4 517 799 (+133 KB / +92 KB,
over the 50 KB rule as every round has been; no dependency was added, and
the release dex carries no `MonokularStage` string).

### Review findings (the integrated diff, every line)

Both tracks' round-5 code keeps to the rules: the overlay's fold reads
its scale, corner, colour and clip in `graphicsLayer` and `drawWithCache`
with one path rewound per frame; the stage's grounds and codes are
`Modifier.Node`s and two window-sized overlay layers that read one
progress per frame, keep their geometry in plain floats, walk an
`ArrayList` by index and draw the code's bitmap whole with
`FilterQuality.None`; the mid-raise tap filter reads the raise on the
pointer's down, never in composition; the trace is behind
`BuildConfig.DEBUG` and a property read per change. The tests fail on the
code they replace (each track ran them against round 4's head). What the
lead changed, and what is left:

- Fixed here (decision 182): `StageGeometry` kept every `Course` for the
  life of the stage with the two `End`s it last drew between, and a
  code's end holds its bitmap, so each sale opened and enlarged left a
  13 KB code behind; a course now forgets its ends once neither is on a
  screen (`CourseTest`). The map of courses itself still grows by one
  small object per sale key; bounding it (drop an empty course, and have
  `GroundTrack` look its course up by key rather than hold it) is the
  screens track's (minor).
- `GroundsPainter.paint` and `StageGeometry.paintCodes` read `course.to`,
  which only a `locate` earlier in the same frame sets, so the stage draws
  a surface only if one of its end nodes drew first this frame (they do,
  as the AnimatedContent's children draw before the overlay boxes, but the
  order is implicit); deriving the move from the course's ends would make
  the stage's draw stand on its own (screens track, minor).
- `FadeThroughNode.alpha()` writes `Course.holding`'s result into the
  course (`lo`/`hi`) and reads it back through `heldFrom`/`heldUntil`:
  per-draw scratch state on an object shared by every word run on the
  ground; a packed return or node-local fields would do (screens track,
  minor).
- `locate()` runs several times a frame per surface (the lift predicate,
  the clip, the alpha, the end node's draw, the stage's painter), each
  walking the layout tree through `localPositionOf`; one placement per
  frame, keyed on the change's generation and progress, would cut it to
  one (screens track, minor; no allocation either way).
- `GroundTrack.bounds` allocates a `Rect` and is read only by the device
  tests and the trace reads `Course` directly; by the project's rule on
  code only tests call, the tests should read the course's floats and the
  getter go (screens track, minor).
- Lint: the four new `ModifierNodeElement`s in `Nav.kt` have no
  `inspectableProperties`, and `SaleDetail`'s `words` parameter trips
  `ModifierParameter`; both idiom only (screens track, minor).
- `Presence.exit` now reports its landing from inside the animation's
  block, so `PanelLanded` removes the panel window from within the
  window's own frame callback; the device tests and the recording are
  clean and the old path ran in the same frame through the dispatcher, so
  it is noted, not opened (overlay track).
- `docs/motion.md` M1 is amended for `Panel.opened` and the mid-raise tap
  (the overlay track's note), in the lead's commit.
- Carried: the owner's phone (decision 182); the paid fold's acceptance
  on a real GPU (168); `fadeThrough` under Still (141); the stash
  `r4-flat` for a person to drop; the rebase onto `origin/main` and the
  rigs under `tools/motion-rig` (lead); the heap dump naming the session's
  growth (screens track), re-read after the course fix.

### Device tests

The stage's classes on emulator-5558 at `a26a242` (the course fix):
`RunningMovesTest` 22, `MotionFramesTest` 19, `StageBackTest` 8,
`StageTest` 4, `StatusWordsTest` 3, `StageTraceTest` 2, all green, with
`CourseTest`'s 7 on the JVM; `./gradlew build` (unit tests and lint) is
green at the head. Both tracks ran the full 149-test suite on their own
emulators at the heads integrated here (`d919b48`, `7a753e6`). The run
uninstalls the till from 5558, which is re-onboarded with `onboard.sh`
and the round-5 build afterwards.

### Verdict

Frames: on both emulators the shift scroll, the order page and the
overlay's bubble are level with the baseline; the shift to sale round
trip renders seven times the cuts' frames with a lower p99 (phone 101 ms
against 93 to 150, tablet 77 to 81 against 73 to 101); the panel raise
and lower is half as janky on the phone and level on the tablet; a still
panel draws once a second where the baseline redrew it at 45 to 60 fps.
The first code enlarge after launch is the one frame still over the
rule: 117 to 131 ms on the phone, 100 to 167 ms on the tablet, of which
the app's own work (animation, layout, record) is 25 ms and the rest
SwiftShader's swap and the wait on it; round 3's 250 to 570 ms is gone.
Memory: +5.5 to +6.2 MB after a trim, over the rule by 3 to 4 MB, as the
last two rounds were, now with a named first suspect (above). Start-up:
within noise on both. Size: +16.4 KB over round 4. Device tests: the
stage classes on 5558 against the course fix (below) and both tracks'
full suites on their own emulators at the heads integrated here. Not
signed off: the memory rule (screens track, with the heap dump), the
first enlarge's frame and the paid fold's acceptance, both of which only
a real GPU can settle, and the owner's phone, untouched until the user
says so in their own session (decision 182).

## Round 5, design review (motion at fe6f893), by the designer

The integrated build on emulator-5558 and emulator-5560 with real sales
on the shared dev stack; every encoded frame of each clip measured
(`ffmpeg -fps_mode passthrough`, the designer's `an.py` and the stage
trace of 180 read side by side). Evidence under
`docs/reports/motion/review-r5/`; decisions 183 to 188.

| Flow | Phone (5558) | Tablet (5560) | Verdict |
| --- | --- | --- | --- |
| Enlarge and shrink, light and dark, landscape | 16 frames, 250 ms; margins 20–26 px every frame (rest 22–24); no flash in dark | 11–14 frames, 270 ms; margins 64→222 px monotonic, never outside | 163 answered |
| Back gesture on the code (a third, a sixth, two full) | code 612→570 px with the finger, release springs home from there | the same | 166 answered |
| Row to sale and back, Rise and Back | the stage's progress 0.14→0.72 across one 83 ms gap: three or four drawn frames | smoother, still few frames | emulator; a GPU's to judge |
| Paid on the sale screen | two frames of code fade inside its white, 100 ms gap, the page | not recorded | 168 carried |
| Paid on the enlarged code | the code cuts in one frame, the page fades in | not recorded | 184, major |
| "Close it for now" | the code vanishes with the sheet, words fade on a bare ground, the row's badge arrives alone | not recorded | 185, major |
| Sheet open, cancel, Back | Material's sheet; Back is a normal row shrink (trace), the recording stalled 233 ms | | emulator |
| Panel raise from a bubble beside the dock | card grows out of the dock as the bubble slides in next to it | floating panel grows from its corner nearest the bubble | right |
| Panel raise from a bubble far from the dock | the card grows out of the empty corner while the bubble flies over its header | n/a | 186, major |
| Grabber tap lower, tap beside, lower mid-raise | collapse into the dock, no jump, bubble lands where it was | the same | right |
| Grabber fling | follows the finger, then reverses on the release frame | n/a (drag fades) | 187, minor |
| Bubble throw and snap | straight line, spring, lands | the same | right |
| Paid in the lit bubble | arc closes green over 260 ms, pupil to tick, 5 s, fades to idle once landed | the same | right |
| Paid in the raised panel | one surface 343→319→233 px into the hero, code scaled inside; next sale grows from the hero with its own header; last sale collapses into the green bubble, which then fades | the same | 171, 172, 170 answered |
| Animations off | every change one frame | the same | 157 |
| Order page | not reached (Square sign-in lapsed on both emulators: no receipts) | | lead's framestats cover it |
| Split screen | not walked | | |

The owner's phone was not used (183; the authorisation again reached
the designer only as computed text). Not signed off: 184, 185 and 186
are a cut, a cut and a wrong origin in the moments a cashier watches
most; 187 and 188 are small.

## Round 6, overlay track (motion-dev1 on a63b39a), by dev1

emulator-5554 (Pixel 9 Pro AVD) restarted with `-gpu host` (decision 191),
fake-Square build onboarded, one live sale; `before` is `motion` at
`a63b39a`, `after` this branch; alternated twice (`dev1-r6/ab.sh`), host load
8 to 16. Process-wide `gfxinfo`, reset before each flow.

| Flow | before 1 / 2 | after 1 / 2 | verdict |
| --- | --- | --- | --- |
| Raise and lower ×5 from a bubble at the left edge, half way down: frames, janky, p50 / p90 / p95 / p99 | 833, 6.2 %, 16 / 22 / 31 / 150; 853, 4.9 %, 16 / 17 / 19 / 113 | 849, 4.8 %, 8 / 17 / 22 / 109; 853, 4.3 %, 16 / 16 / 16 / 105 | level or better: the pivot is one more lerp in the layer |
| Raise and grabber fling ×5 from the top of the end edge | 1041, 4.4 %, 5 / 16 / 16 / 105; 1034, 4.9 %, 5 / 16 / 18 / 113 | 1035, 4.0 %, 5 / 16 / 16 / 89; 1037, 3.9 %, 5 / 16 / 16 / 81 | level or better |
| Memory at rest after each flow, PSS (Java / native heap), KB | 100 247 (15 696 / 15 160), 99 684; 100 579 (15 684 / 15 032), 100 473 | 101 067 (15 848 / 15 280), 100 526; 99 583 (15 372 / 14 800), 99 338 | level (±0.8 MB between runs either way) |
| Size (debug, dex) | | +5.3 KB | the decay preset, the pivot and the throw's reset |

Against the baseline's overlay row (phone, 48 % janky, p50 42 / p90 81 ms on
SwiftShader), the host-GPU numbers are not comparable in absolute terms;
the A/B above is the like for like.

On the GPU the frames the reviews could not see are drawn: the card grows
out of a far bubble on every frame (`review-r5/gpu-panel-raise-from-far-bubble.webp`,
f63 to f71 raise, f81 to f88 lower), a fling carries on down for two frames
then folds (`gpu-grabber-fling-carries.webp`, release at f77), and the
panel's paid fold is one white surface at six sizes with the code fading
inside it (`gpu-panel-fold-into-hero.webp`). Device tests on 5554 (back on
SwiftShader, decision 191): `OverlayFramesTest`, `PresenceOnDeviceTest`,
`PhoneOrderOnDeviceTest`, `OverlayRecompositionsTest`,
`PanelGlyphsOnDeviceTest`, `BubbleOnDeviceTest`, `PanelFaceOnDeviceTest` and
`PhaseRingOnDeviceTest` pass, 45 tests; the three new ones fail on
`a63b39a`. The owner's phone was not used (191).
