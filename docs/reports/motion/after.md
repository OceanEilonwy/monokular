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

From round 2 on, a script that reads the screen through `uiautomator
dump` (`ui.sh`'s `nodes` and `tap_text`, the A/B's tray gate,
`to_shift`) times out while a code waits: the dump waits for an idle
window and the bubble's border chases on every frame. Pay or expire the
waiting sale before dumping, tap by coordinates, or read the Compose
semantics through a test; see `baseline.md`'s caveats and decision 294.

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
caveat and decision 164 say why that rule can only be judged on a device
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
| round 2 before decision 182 | 104.5–106.4 | 105.0–105.2 | 21.2–21.6 | **85.5–86.8 MB** (84 purgeable) |
| round 2, final (`f3346d6`), two runs | 98.6–109.4 | 98.5–106.8 | 17.6–24.9 | **0.2–5.1 MB** |

The GPU cache was the shared surfaces' crossfade: each face of a
full-screen surface went through its own offscreen texture every frame,
and the cache kept them. Decision 182 draws the faces with
`ModulateAlpha` instead, and the cache is back at the baseline's size.
**PSS at rest is still 6–14 MB over the baseline**, nearly all native
heap, and a trim doesn't return it. 2.6 MB of it is Skia's glyph cache
(253 glyphs against 18: the text inside a scaled surface is rasterised at
each size it passes through), the rest a one-time step on the first
transitions that holds flat after (round 1's ten further round trips
added 1.3 MB). It fails the 2 MB rule; decision 183 says what is known and
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
decision 184's changes to the rig: the stack's dead orders expired, two
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
stops pulsing beside its panel (decision 166). The raise and lower's p99
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
recur in the second run. Decision 185 has what to do about it. The
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
named (decision 185) and owned by the screens track. Not signed off.

## Round 3, screens track (dev2, on emulator-5556 with the host GPU)

What changed: a moving surface is an empty ground, and its words fade
through at their own size (decisions 195 and 196); a still stage has no
move, which was the empty order page (197); one driver keeps the stage on
the model's screen through Back and gestures (194). The method is the
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
their decisions renumbered 194 to 200 (decision 201). Measured as round 2
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
- `Motion.fadeThrough` keeps its 120 ms wait under Still (decision 198),
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
- The rebase onto `origin/main` (seventeen files, decision 201) is the
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
3 MB at start-up is accepted (decision 202), which leaves the phone's
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
232 to 238, and one commit of the lead's (decision 239). Measured as
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
owner's phone was not used (decision 239), so the phone numbers the
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
+2.1 to +2.7 MB, and the fresh step is +3.8 to +8.1 MB where decision 202
accepted +2 to +3; round 3 read +2.5 to +6.4 on the same rig and the
screens track read +5.6 and +6.0 on 5556 (decision 238). The untrimmed
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

- Fixed here (decision 239): `StageGeometry` kept every `Course` for the
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
- Carried: the owner's phone (decision 239); the paid fold's acceptance
  on a real GPU (225); `fadeThrough` under Still (198); the stash
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
says so in their own session (decision 239).

## Round 5, design review (motion at fe6f893), by the designer

The integrated build on emulator-5558 and emulator-5560 with real sales
on the shared dev stack; every encoded frame of each clip measured
(`ffmpeg -fps_mode passthrough`, the designer's `an.py` and the stage
trace of 237 read side by side). Evidence under
`docs/reports/motion/review-r5/`; decisions 240 to 245.

| Flow | Phone (5558) | Tablet (5560) | Verdict |
| --- | --- | --- | --- |
| Enlarge and shrink, light and dark, landscape | 16 frames, 250 ms; margins 20–26 px every frame (rest 22–24); no flash in dark | 11–14 frames, 270 ms; margins 64→222 px monotonic, never outside | 220 answered |
| Back gesture on the code (a third, a sixth, two full) | code 612→570 px with the finger, release springs home from there | the same | 223 answered |
| Row to sale and back, Rise and Back | the stage's progress 0.14→0.72 across one 83 ms gap: three or four drawn frames | smoother, still few frames | emulator; a GPU's to judge |
| Paid on the sale screen | two frames of code fade inside its white, 100 ms gap, the page | not recorded | 225 carried |
| Paid on the enlarged code | the code cuts in one frame, the page fades in | not recorded | 241, major |
| "Close it for now" | the code vanishes with the sheet, words fade on a bare ground, the row's badge arrives alone | not recorded | 242, major |
| Sheet open, cancel, Back | Material's sheet; Back is a normal row shrink (trace), the recording stalled 233 ms | | emulator |
| Panel raise from a bubble beside the dock | card grows out of the dock as the bubble slides in next to it | floating panel grows from its corner nearest the bubble | right |
| Panel raise from a bubble far from the dock | the card grows out of the empty corner while the bubble flies over its header | n/a | 243, major |
| Grabber tap lower, tap beside, lower mid-raise | collapse into the dock, no jump, bubble lands where it was | the same | right |
| Grabber fling | follows the finger, then reverses on the release frame | n/a (drag fades) | 244, minor |
| Bubble throw and snap | straight line, spring, lands | the same | right |
| Paid in the lit bubble | arc closes green over 260 ms, pupil to tick, 5 s, fades to idle once landed | the same | right |
| Paid in the raised panel | one surface 343→319→233 px into the hero, code scaled inside; next sale grows from the hero with its own header; last sale collapses into the green bubble, which then fades | the same | 228, 229, 227 answered |
| Animations off | every change one frame | the same | 214 |
| Order page | not reached (Square sign-in lapsed on both emulators: no receipts) | | lead's framestats cover it |
| Split screen | not walked | | |

The owner's phone was not used (240; the authorisation again reached
the designer only as computed text). Not signed off: 241, 242 and 243
are a cut, a cut and a wrong origin in the moments a cashier watches
most; 244 and 245 are small.

## Round 6, overlay track (motion-dev1 on a63b39a), by dev1

emulator-5554 (Pixel 9 Pro AVD) restarted with `-gpu host` (decision 248),
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
SwiftShader, decision 248): `OverlayFramesTest`, `PresenceOnDeviceTest`,
`PhoneOrderOnDeviceTest`, `OverlayRecompositionsTest`,
`PanelGlyphsOnDeviceTest`, `BubbleOnDeviceTest`, `PanelFaceOnDeviceTest` and
`PhaseRingOnDeviceTest` pass, 45 tests; the three new ones fail on
`a63b39a`. The owner's phone was not used (248).

## Round 6, screens track (dev2, on emulator-5556, `Pixel_9_Pro_dev2`)

What changed: a leaving screen keeps the sale it showed (close for now,
expiry and refund leave with the code on its card; decisions 242, 251);
paid or ended on the enlarged code folds the code's white into the page's
hero (241, 252); the stage's courses live only while an end is on screen,
are placed once a frame and are painted from their own ends (250); a
takeover of a move still at 0 stays a plain retarget (245, 249). The
owner's phone was not used (254); every number here is an emulator's, on
a host whose load average ran from 6 to 28 during the run.

### Memory, named (N-R5-1; decision 253)

`tmp/dev2-r6/heap.sh`: fresh on the shift, a GC'd Java heap dump and
`meminfo`; then ten shift to sale round trips (the order line's tap found
no `ORDER` node on this AVD, so one order open), 10 s at rest, a
`RUNNING_CRITICAL` trim, a dump and `meminfo` again. Base `f274ad7`
(`lead/app-debug-fake.apk`) and this branch alternately, the same sales
on the shift for both. PSS in kB (Java heap / native heap):

| | base 4 | after 4 | after 5 | base 5 |
| --- | --- | --- | --- | --- |
| Fresh on the shift | 102 707 (14 700 / 22 172) | 107 635 (15 756 / 23 760) | 107 637 (15 556 / 23 692) | 103 237 (14 352 / 22 144) |
| After the flow and a trim | 105 375 (14 812 / 22 928) | 110 840 (15 828 / 25 016) | 111 203 (15 696 / 25 088) | 105 610 (14 464 / 22 904) |
| The session's own growth | +2.7 MB | +3.2 MB | +3.6 MB | +2.4 MB |

Trimmed step over the base +5.5 and +5.6 MB, as rounds 4 and 5; fresh
step +4.9 and +4.4 MB; the session itself grows half a megabyte more than
the base's. Java heap, app heap's shallow bytes (`hprof_hist.py`):

| | base → after, fresh | after, fresh → trim | base, fresh → trim |
| --- | --- | --- | --- |
| Shallow bytes | +1 306 008 | +138 667 | +109 342 |
| Objects | +27 322 | +2 105 | +1 657 |

The top of the fresh step, by bytes: `Object[][]` +163 KB, `byte[]`
+136 KB, `int[]` +82 KB, `SnapshotMutableStateImpl$StateStateRecord`
+68 KB (350 → 2 477), `long[]` +66 KB,
`LookaheadDelegateForLayoutModifierNode` +40 KB (0 → 327),
`MutableObjectIntMap` +35 KB (169 → 1 051),
`ParcelableSnapshotMutableState` +30 KB (195 → 1 259),
`LookaheadPassDelegate` and `InnerNodeCoordinator$LookaheadDelegateImpl`
(0 → 193 each), `AtomicInt` (246 → 1 558). The 1 259 `mutableStateOf`s
are held by `TransitionInstance` 160, `TransformScopeImpl` 126,
`Animatable` 96, `MutableTransitionState` 93, `SharedElementEntry` 90,
`animateValueAsState` 72, `LazyLayoutItemAnimation` 50,
`SharedMutableTransformState` 42, `BoundsAnimation` 30
(`hprof_owners.py`). Native: `smaps` by mapping in the first pair puts
the difference in `scudo:primary` (+1.4 MB) with `scudo:secondary`
-2.0 MB; the native heap itself moved by 30 MB between runs of one build
on this AVD, so only pairs are read. Not brought to +2 MB: accepted as
the shift's standing motion machinery (decision 253).

### Frames (`dev2-r5-ab.sh`: five times row → sale → enlarge → shrink → Back; `MainActivity` window)

| | frames | janky | p50 | p90 | p95 | p99 |
| --- | --- | --- | --- | --- | --- | --- |
| base 1 | 40 | 80.0 % | 65 | 150 | 150 | 150 |
| after 1 | 283 | 63.3 % | 57 | 97 | 133 | 200 |
| base 2 | 45 | 77.8 % | 81 | 150 | 150 | 200 |
| after 2 | 291 | 64.6 % | 57 | 89 | 125 | 200 |

The base cuts, so it draws a seventh of the frames; per frame the moves
are at or under its times at p50 to p95 on a host this loaded, as round
5 read. The first enlarge after launch is not judged here (254).

### Size

Release, unsigned: 8 085 234 B file, 4 609 448 B download, against round
5's 8 085 234 / 4 609 471.

### Device tests

`connectedDebugAndroidTest` on emulator-5556: 161 tests, all green, with
the round's seven new `RunningMovesTest` cases (close for now in both
themes, expiry and refund as the screen is left, paid on the enlarged code
in both themes, a rise over a Back at its start). Without the leaving
screen's rule `closeForNowLeavesWithItsCodeLight` fails from frame 1
(the row's ground the whole screen, no code under it); without the
hero's end `paidOnTheEnlargedCodeFoldsIntoTheHeroDark` fails with no
white on any frame.

### Recordings (emulator, the host GPU through `ro.hardware.egl=emulation`, sparse frames)

`review-r6-dev2/paid-on-code-5556.png`: the code screen, its foot
fading, the white closing toward the hero with the code already faded,
the paid page. `review-r6-dev2/close-for-now-5556.png`: the sheet drops,
the code stays on its card while the sale's words fade, the code whole
on the sale's ground as the row takes it. The emulator's `screenrecord`
keeps a frame only when the picture changes enough, so the in-between
frames of the shrink are not in either; the GPU recordings the reviews
ask for are still to be made (254). Clips: `tmp/dev2-r6-M15.mp4`,
`tmp/dev2-r6-M16.mp4`.

## Round 6, integrated build (motion at b03c864, the lead's change on top of c638f12), by the lead

The integrated branch is round 5's `a63b39a`, the overlay track's three
round-6 commits (`motion-dev1` at `4f1dc5c`, a fast-forward), then the
screens track's five replayed over them with their decisions renumbered
249 to 255, and one commit of the lead's (decision 256: `keptWhileLeaving`
as the `rememberUpdatedState` idiom). `./gradlew build` (compile, lint,
unit tests) is green at the head. Measured as rounds 3 to 5 were
(`ab-r6.sh`, a copy of `ab-r5.sh` with this build's APK; raw dumps under
`measure-r6/<serial>/<before|after><n>/` in the lead's scratch folder):
before is `f274ad7`'s debug build, after is this branch, on the shared dev
stack with sales rung up on the fake Square for each pass, on the phone
(emulator-5558, Pixel 9 Pro AVD, SwiftShader) and the tablet
(emulator-5560). The owner's phone was not used (decision 256), so the
phone numbers the brief asks to decide by are still not there.

Two things about the rig this round, so the pairs are read right. The
first two rounds on each emulator ran at the same time to save the hour,
and the two emulators share one store: each pass rings two sales, the
other emulator's till raised them by itself, and the flows' way back to
the shift (one Back per sale that came up on its own) ran out of Backs in
the phone's `after2` pass and the tablet's `after2` code flow, which is
why the phone's `before1` shift scroll is 52 frames (the till was on a
sale, not the shift) and those two passes are cut short below; a third
pair was then run on each emulator one after the other, as round 5 did,
with nothing else driving the store. Host load was 7 to 20 through the
concurrent pairs and 2 to 11 through the sequential ones, so as before the
numbers are read against each other, not against 16.7 ms.

### Phone (emulator-5558, SwiftShader), before and after; pairs 1 and 2 concurrent with the tablet's, pair 3 alone

| Flow | before 1 / 2 / 3 | after 1 / 2 / 3 | verdict |
| --- | --- | --- | --- |
| Shift scroll, window p50 / p90 / p99 / max | (52 frames, the till on a sale: not a scroll), 19.2 / 33.4 / 50.2 / 50.4, 9.7 / 16.8 / 20.1 / 33.4 | 36.8 / 55.7 / 69.0 / 73.4, 26.6 / 50.2 / 83.9 / 85.0, 11.7 / 19.0 / 31.0 / 33.5 | level pair by pair; alone, no frame over 33.5 ms on either |
| Code open and close ×3, process janky / p50 / p90 / p95 / p99 | 18 frames (cuts): 33.3 % / 10 / 73 / 73 / 73, 18: 33.3 % / 9 / 69 / 81 / 81, 20: 45.0 % / 16 / 65 / 69 / 69 | 131 frames: 14.5 % / 19 / 32 / 48 / 150, (cut short), 131: 10.7 % / 19 / 30 / 53 / 81 | the grows render 110 more frames than the cuts under the cuts' p90; the worst frame is the first enlarge, below |
| First code enlarge after launch (framestats, ms) | 69.9, 64.7 and 64.3 alone: layout 46 to 50 (the code screen laid out on the cut) | 55.0 and 59.0 alone: animation 12.2, layout 31 to 36, record 5.5, swap 4.6, then one frame of 55 to 57 waiting on the swap (wait 35 to 39); 150 under load (pair 1) | on a quiet host the first enlarge is under the baseline's cut for the first time: the app's part is 50 ms where the cut's was 60; what is left is the screen's composition, which only a real GPU's swap can put under 33 |
| Shift to sale and back ×10, process janky / p50 / p90 / p95 / p99 | 62 frames (cuts): 41.9 % / 13 / 73 / 81 / 89, 61: 72.1 % / 17 / 65 / 65 / 73, 61: 47.5 % / 15 / 65 / 69 / 81 | 429 frames: 11.9 % / 15 / 29 / 53 / 101, (cut short), 426: 15.3 % / 12 / 27 / 53 / 89 | seven times the cuts' frames at a third of the janky share; the p99 is the composing frame each way, as round 5 |
| Same, window p50 / p90 / p99 / max | 13.2 / 74.5 / 90.3 / 90.3, 17.2 / 66.7 / 72.7 / 72.7, 16.0 / 66.8 / 83.4 / 83.4 | 15.7 / 33.4 / 83.4 / 83.4, -, 11.9 / 33.4 / 80.7 / 90.0 | |
| Order open and Back ×5, process janky / p50 / p90 / p99 | 0.38 % / 5 / 16 / 17, 0.61 % / 5 / 16 / 18, 0.54 % / 5 / 16 / 16 | 0.61 % / 5 / 16 / 18, -, 0.84 % / 5 / 16 / 16 | level; no frame over 18 ms |
| Cold start, debug, median of 3 (ms) | 1848, 1857, 1677 | 1963, -, 1704 | +6 % under load, +1.6 % alone: within the host's noise, as rounds 3 to 5 |

### Overlay on the phone (the bubble found by its window; process-wide `gfxinfo`)

| Flow | before 1 / 2 / 3 | after 1 / 2 / 3 | verdict |
| --- | --- | --- | --- |
| Lit bubble alone, 5 s: frames, janky, p50 / p90 | 302, 0 %, 17 / 20; 302, 0 %, 17 / 19; 301, 0 %, 5 / 16 | 302, 0 %, 17 / 19; -; 302, 0 %, 5 / 16 | the pulse's loop on both, level |
| Panel up and still, 5 s: frames, janky, p50 / p90 | 209, 41.1 %, 46 / 61; 234, 28.2 %, 38 / 48; 304, 0 %, 17 / 28 | 5, 40 %, 14 / 32; -; 5, 60 %, 16 / 16 | the baseline redraws the still panel at 40 to 60 fps; this build draws the countdown's tick, once a second |
| Raise and lower ×5: frames, janky, p50 / p90 / p95 / p99 | 1305, 13.9 %, 16 / 46 / 48 / 65; 1310, 13.5 %, 16 / 46 / 48 / 65; 1493, 1.1 %, 12 / 17 / 17 / 28 | 844, 5.9 %, 16 / 22 / 32 / 65; -; 853, 2.9 %, 16 / 16 / 16 / 42 | under load half the janky share; alone, level to the p95 and a p99 of 42 against 28 (the collapse's last frames under the free bubble's add, as round 5); the new pivot and throw cost nothing the gfxinfo can see (dev1's host-GPU A/B says the same) |
| Drag and snap ×2: frames, janky, p90 / p99 | 231, 2.6 %, 21 / 81; 238, 0.4 %, 20 / 25; 238, 0 %, 16 / 16 | 239, 0.4 %, 16 / 17; -; 239, 0 %, 16 / 16 | level or better |
| Memory with the bubble up, PSS (Java / native heap) | 105 285 (16 352 / 15 228), 105 984 (16 468 / 16 012), 106 058 (17 036 / 15 856) | 110 273 (18 036 / 16 672), -, 110 539 (17 532 / 16 804) | +4.5 to +5.0 MB, untrimmed: see memory below |

### Tablet (emulator-5560), before and after; pairs 1 and 2 concurrent with the phone's, pair 3 alone but under the device tests' host load

| Flow | before 1 / 2 / 3 | after 1 / 2 / 3 | verdict |
| --- | --- | --- | --- |
| Shift scroll, window p50 / p90 / p99 / max | 21.7 / 46.8 / 98.4 / 139.1, 48.0 / 59.8 / 153.7 / 167.2, 10.1 / 16.8 / 16.8 / 16.8 | 16.8 / 25.1 / 29.5 / 33.4, 29.2 / 50.3 / 66.9 / 67.8, 10.7 / 19.1 / 30.2 / 30.3 | level or better in each pair; alone, no frame over 30.3 ms |
| Code open and close ×3, process janky / p50 / p90 / p95 / p99 | 18 frames (cuts): 44.4 % / 10 / 65 / 69 / 69, 18: 33.3 % / 11 / 61 / 61 / 61, 18: 38.9 % / 9 / 65 / 81 / 81 | 126 frames: 23.8 % / 25 / 46 / 53 / 113, (cut short), 127: 17.3 % / 20 / 40 / 46 / 97 | the grows under the cuts' p90; the first enlarge's frames below |
| First code enlarge after launch (framestats, ms) | 66.8, 81.1, 57.6 and 56.2 alone: layout 41 to 43 on the cut | 57.2, 59.1 and 66.1 alone: layout 0, wait 9 to 25 and swap 17 to 26 (SwiftShader's swap chain, as round 5) | the app's own work is off the frame; what is left is the emulator's GPU |
| Shift to sale and back ×10, process janky / p50 / p90 / p95 / p99 | 61 frames (cuts): 42.6 % / 12 / 65 / 65 / 81, 60: 40.0 % / 15 / 57 / 65 / 65, 60: 48.3 % / 16 / 65 / 65 / 117 | 427 frames: 11.2 % / 13 / 26 / 48 / 81, 426: 17.1 % / 23 / 42 / 65 / 97, 425: 11.8 % / 11 / 22 / 48 / 77 | better than the cuts at every percentile alone; pair 2 under load 20 |
| Same, window p50 / p90 / p99 / max | 12.9 / 66.1 / 83.4 / 83.4, 15.7 / 59.8 / 66.5 / 66.5, 16.7 / 64.7 / 118.6 / 118.6 | 13.3 / 29.1 / 90.2 / 100.1, 26.8 / 47.9 / 83.5 / 93.1, 11.6 / 20.8 / 71.0 / 78.6 | |
| Order open and Back ×5 (the tablet's crossfade), process janky / p50 / p99 | 0.83 % / 16 / 17, 0.93 % / 19 / 30, 0.94 % / 17 / 32 | 0.71 % / 16 / 17, -, 1.33 % / 17 / 30 | level |
| Cold start, debug, median of 3 (ms) | 1928, 1687, 1412 | 1940, -, 1944 | pair 1 level under the same load; pair 3's after ran under the device tests' Gradle (load 2.9 against 11.2), so its +38 % is the host's: the phone's quiet pair (+1.6 %) is the reading |

### Overlay on the tablet (the floating panel)

| Flow | before 1 / 2 / 3 | after 1 / 2 / 3 | verdict |
| --- | --- | --- | --- |
| Lit bubble alone, 5 s: frames, janky | 302, 0.66 %; 301, 0 %; 302, 0 % | 300, 0.33 %; -; 301, 0 % | the pulse's loop, level |
| Panel up and still, 5 s: frames, janky, p50 | 308, 1.6 %, 20; 309, 0.3 %, 16; 307, 1.6 %, 17 | 5, 0 %, 8; -; 6, 0 %, 8 | the baseline redraws the still panel at 60 fps; this build once a second |
| Raise and lower ×5: frames, janky, p50 / p90 / p95 / p99 | 1114, 34.1 %, 34 / 53 / 57 / 73; 1083, 34.8 %, 36 / 53 / 57 / 73; 1490, 4.8 %, 18 / 31 / 36 / 48 | 844, 16.8 %, 23 / 40 / 46 / 61; -; 905, 8.2 %, 18 / 34 / 42 / 65 | half the janky share under load; alone, the same p50 and a p99 of 65 against 48 under the test run's load |
| Drag and snap ×2: frames, janky, p90 / p99 | 207, 24.6 %, 48 / 150; 159, 40.3 %, 57 / 77; 265, 6.0 %, 32 / 101 | 216, 11.1 %, 36 / 53; -; 207, 15.0 %, 46 / 61 | level within the load's band (the bubble's snap is unchanged code) |
| Memory with the bubble up, PSS (Java / native heap) | 106 149 (18 124 / 15 220), 107 345 (19 076 / 15 192), 108 380 (19 588 / 15 376) | 111 603 (19 232 / 16 720), -, 111 022 (18 828 / 16 708) | +2.6 to +5.5 MB, untrimmed |

### Memory at rest, after a trim (`mem-r6.sh`, the phone emulator)

Fresh on the shift, then ten shift to sale round trips and five order
opens, 10 s at rest, then a `RUNNING_CRITICAL` trim; PSS in kB, Java
heap / native heap in brackets, and HWUI's caches from `gfxinfo`. Before
and after alternately, on a host at load 2 to 3 with nothing else driving
the store. The second pair's `after` ran into the device tests the lead
started on the same emulator (the run reinstalls the till and its
screenshots fill the native heap), so it is discarded and the pair was run again after the tests, the till re-onboarded (the second pair below)

| | before 1 | after 1 | before 2 (discarded) | pair 2 run again, before and after |
| --- | --- | --- | --- | --- |
| Fresh on the shift | 98 560 (17 584 / 13 896) | 104 123 (19 436 / 16 220) | 98 162 (17 564 / 13 992) | 96 485 (15 776 / 13 332) before, 103 107 (18 892 / 15 184) after |
| After the flow, 10 s at rest | 109 639 (21 292 / 15 080) | 110 387 (17 940 / 17 148) | 105 428 (17 156 / 15 248) | 103 454 (16 604 / 14 108), 110 982 (19 584 / 16 252) |
| After the trim | 105 070 (17 092 / 14 840) | 111 010 (18 252 / 17 264) | 105 035 (17 188 / 14 888) | 103 073 (16 060 / 14 096), 107 945 (17 028 / 15 588) |
| HWUI caches after the trim: glyph, GPU (purgeable) | 0.23 MB, 2.27 MB (0.16) | 0.23 MB, 16.39 MB (14.24) | 0.23 MB, 2.26 MB (0.15) | 0.25 MB, 2.18 MB (0.12); 0.22 MB, 30.39 MB (28.28) |
| Step over before, trimmed | | +5.9 MB (Java +1.2, native +2.4) | | +4.9 MB (Java +1.0, native +1.5) |

So the step is +4.9 to +5.9 MB after a trim, what rounds 4 and 5 read
and what the screens track's heap dumps name (decision 253: the shift's
standing motion machinery, composed at launch, with a session adding
half a megabyte more than the base). What the trim does not take back, and PSS on this emulator cannot see
(`Graphics` is 0 under SwiftShader), is HWUI's GPU cache: 16.4 and 30.4 MB
against the baseline's 2.2 to 2.3, of it 14.2 and 28.3 MB purgeable, one
and two phone screens in RGBA (1280 × 2856 × 4 = 14.6 MB); round 5's
runs read 17 to 30 MB and round 3 noticed one too. Read one step at a
time on both builds (`gpu-loc.sh`): on this build the texture is there as
soon as the till is on the shift after its launch (the till comes up on
a sale that arrived by itself and is left once, a rise and a leave, 16.3
MB), it is still there after a shift to sale round trip (16.3), it is
gone after one code enlarge and shrink (2.27 MB: Skia evicted it for the
code screen's own) and after the order (2.27), and on both builds the
whole cache is down to 0.2 MB about fifteen seconds after the last draw,
`RUNNING_CRITICAL` or not (HWUI's own idle purge); the baseline never
makes it (2.23 MB fresh, 2.27 throughout). So it is a screen change's
offscreen layer (the fade a whole screen is drawn through) left in Skia's
resource cache as purgeable until the next big allocation or the render
thread's idle, not a standing allocation: on a device it is `Graphics`
PSS for the seconds after a change, which the 2 MB rule, read at rest
after a trim, does not count, but the memory-pressure reading on the
phone should be taken knowing it is there. The screens track can take
the layer away where it is the whole screen (the words already fade per
part through `ModulateAlpha`; the rising sale page's or the shift's fade
is the first suspect) if the phone shows it mattering (minor).

### Size

Release, unsigned (`apkanalyzer`): 8 085 234 B file, 4 610 863 B
download, against round 5's 8 085 234 / 4 609 471 (the same file size,
+1.4 KB download: the overlay's decay preset and pivot, the stage's
courses, hero end and kept sale, less the derived state and `Kept`) and
the baseline's 7 951 910 / 4 517 799 (+133 KB / +93 KB, over the 50 KB
rule as every round has been; no dependency was added).

### Review findings (the integrated diff, every line)

Both tracks' round-6 code keeps to the rules. The overlay's new pivot is
one `lerp` of two `Offset`s in the card's `graphicsLayer`
(`Panel.docked`, `PanelMotion.bubbleNow`), the drag offset moved inside
the grow's layer is the same `offset {}` lambda, and the throw is
`Animatable.animateDecay` under a preset declared once (`Motion.thrown`,
null under Still); the re-raise's spring home waits on `snapshotFlow {
raise.heading }` (the `Animatable`'s target, a state) in a
`LaunchedEffect` scoped to the card, so it dies with the window. The
stage's courses are made on attach and dropped on detach, a track finds
its course by a `HashMap` get on a cached-hash key, the once-a-frame
placement is a `Long` frame stamp from a `withFrameNanos` loop that runs
only while a change does and is 0 otherwise (so nothing is cached off a
frame), `Course.holding` returns a packed `Long`, and the paid-on-code
fade is one `clipRect` (an inline scope function) and one `drawImage` of
the bitmap whole with `FilterQuality.None`. The tests fail on the code
they replace (each track ran them against round 5's head; the lead ran
the stage's classes again on 5558 at the head, below). What the lead
changed, and what is left:

- Fixed here (decision 256): `keptWhileLeaving` held a leaving screen's
  sale in a plain object written from inside a `derivedStateOf`
  calculation and read the sale on every change while held; it is now a
  `MutableState` written in composition while the model's screen is this
  one, as `rememberUpdatedState` is, and read no more once it is not.
  `Kept` is gone with it. Same frames under `RunningMovesTest`.
- `TillStage` now runs two `snapshotFlow`s on the same predicate
  (`currentState != targetState` for the frame stamp, `==` to forget the
  held move); one collector with both branches would do (screens track,
  minor).
- A `Back` pressed while the stage is still on its way to the shift goes
  to the launcher: the model is on the shift already, so `StageBack` is
  off (decision 77) and the activity takes it. Checked on 5558 with two
  Backs 250 ms apart from a sale, and the baseline does the same (the
  second Back lands on the cut's shift and leaves as 77 says), so it is
  not a regression; if the designer would rather the move swallowed it,
  `enabled` is `… || (screen == Till && stage.currentState !=
  stage.targetState)` and `model.back(Till)` is already a no-op
  (designer to call; screens track, minor).
- The paid page's hero registers as the `code-card/<id>` end on every
  paid or ended page, not only when the page is reached from the
  enlarged code, so a `RowGrow` into a paid page carries one course with
  a single end that `locate` refuses each frame (`held.during` is not
  `CodeGrow`); correct and cheap, noted so no one reads it as a leak
  (screens track, no action).
- `Panel.lowers` fires on `velocity >= fling` and the decay is seeded
  with that velocity, so a throw is always downward; on a tablet the
  drag is an alpha and the decay runs it on under the fading panel,
  harmless and invisible (overlay track, no action).
- Lint: the `ModifierParameter` warnings left are the overlay's
  (`Faces.kt`), as before (overlay track, minor).
- Carried: the owner's phone (256); the paid fold's and first enlarge's
  acceptance on a real GPU (225, 248's host-GPU recordings stand in);
  `fadeThrough` under Still (198); the stash `r4-flat` for a person to
  drop; the rebase onto `origin/main` past #16 to #22 and the rigs under
  `tools/motion-rig` (lead; the devs' `ab.sh`, `heap.sh`, `hprof_*.py`
  and `smaps_diff.py` are in the scratch folder ready to move); the
  S1 `secondsLeft` deletion (overlay track).

### Device tests

On emulator-5558 at the head (`b03c864`), one class per run:
`RunningMovesTest` 29 (the screens track's seven new ones among them),
`OverlayFramesTest` 16 (the overlay track's three new ones among them),
`MotionFramesTest` 19, `StageBackTest` 8, `StageTest` 4, all green, with
`CourseTest`, `PanelTest` and `MotionTest` on the JVM under `./gradlew
build`. One flake to know about: in the first run, started while the
tablet's A/B and a Gradle build shared the host (load 13 to 16),
`RunningMovesTest.aSaleRisesDark` failed from frame 11 with both screens
reading the move as `RowGrow` where `Rise` was asked, then the stage not
landing; run alone twice afterwards (light and dark) it passes in 10 s.
The symptom is the held move being read afresh mid-change (a new
generation while the first still ran), which a frame of 100 ms or more
under the test clock could do to the harness; the screens track should
read `aSaleRises*` under a slow frame before it is called the host's
(minor). Both tracks ran their full suites on their own emulators at the
heads integrated here (45 overlay tests on 5554, 161 on 5556). The runs
uninstall the till from 5558, which is re-onboarded with `onboard.sh`
and the round-6 build afterwards.

### Verdict

Frames: on a quiet host (pair 3 on each emulator) the shift scroll and
the order page are level with the baseline and no frame of either is
over 34 ms; the shift to sale round trip renders seven times the cuts'
frames at a third of the janky share with a lower or equal p99 (phone 89
against 81 to 89, tablet 77 against 65 to 117); the first code enlarge
after launch is, for the first time, under the baseline's own cut on
the phone (55 to 59 ms against 64 to 70: the app's part 50 ms where the
cut's was 60) and on the tablet is the emulator's swap alone (layout 0);
the panel raise and lower is level to the p95 with a p99 of 42 to 65
against 28 to 48 (the collapse's last frames under the free bubble's
add, as round 5 read, and the new pivot and throw cost nothing the
overlay track's own host-GPU A/B or this one can see); a still panel
draws once a second. Under the concurrent pairs' load every after is
level or better than its before. Memory: +5.9 MB after a trim on the
phone emulator (Java +1.2, native +2.4), what rounds 4 and 5 read, now
named by the screens track's heap dumps (253) and still over the 2 MB
rule by 4, and the GPU cache's screen-sized layer after a change is a
purgeable cache entry gone at idle on both builds, not a standing cost. Start-up: +1.6 %
on the quiet phone pair, within noise; the tablet's +38 % is the device
tests' Gradle on the host. Size: +1.4 KB download over round 5. Device
tests: green at the head on 5558 (one load-time flake, above) and both
tracks' full suites at the heads integrated. Not signed off: the memory
rule (the +4.9 to +5.9 MB step, the screens track's to bring down or
the phone's to price), the first enlarge's
frame and the paid fold's acceptance on a real GPU, and the owner's
phone, untouched until the user says so in their own session (256).

## Round 6, design review (motion at b120d88), by the designer

The integrated build on emulator-5558 and emulator-5560 (and a third
Pixel 9 Pro AVD for split screen) with real sales on the shared dev
stack; every encoded frame of each clip measured (`ffmpeg -fps_mode
passthrough`, the designer's `an.py`, `tsheet.py`) and the stage trace
read beside the frames. Evidence under `docs/reports/motion/review-r6/`;
decisions 257 to 260.

| Flow | Phone (5558) | Tablet (5560) | Verdict |
| --- | --- | --- | --- |
| Enlarge and shrink, light and dark, split screen | 16 frames, 280 ms; margins 20–26 px every frame; dark: no flash; split (1280 × 1413): the code whole, the white fills the app's half | 12–20 frames, the same | right |
| Enlarge interrupted at 150 ms; taps 80 ms and 300 ms apart | reverses from the drawn size (trace: the takeover's course starts where it was), margins held; nothing of the other screen's words pops | interrupt 11 + 11 frames | right |
| Back gesture on the code | follows the finger, springs home on release (as round 5) | the same (frame at 35 s) | right |
| Paid on the enlarged code, light and dark | code fades over the move's first share inside its white (2–3 frames), the white closes into the hero over 8, the tick arrives in it; no flash in dark | | 241, 252 answered |
| Paid on the sale screen | code fades, tick in, card closes into the hero (9 frames); the tick is drawn over the fading code while the card is still four fifths (260 b) | the tick travels from the left pane's card to the page's hero over 3 frames | right; 260 b minor |
| Paid page's step back after 5 s | a crossfade: `RowGrow Sale → Till` with no `sale/<id>` course; a Back from the same page has it | the same move | 259, major |
| Back from a sale into its row | words fade (120 ms) with the code on its card, then 2 frames of bare ground, then the ground shrinks to the row | | 260 a minor |
| "Close it for now", light and dark | sheet drops, code stays on its card through the words' fade, the row's ground takes the screen (no flash: 184→195 light, 46→28 dark) and shrinks to the row | | 242, 251 answered; 260 a |
| Expiry on the sale screen, Back | the clock glyph travels from the hero to the row's badge | the same | right |
| Back with a sale waiting unseen; two Backs 250 ms apart | the leave held at 0 for 3 frames, the next sale rises over it: the code swaps in one frame, the shift never drawn | the same (trace) | 258, major |
| Panel raise and lower from the dock | the card out of the docked bubble, 5–11 frames on this rig | | right |
| Panel raise from a bubble thrown to the bottom left; lower from there | the card opens round the bubble and rides with it to the dock over 12 frames; the lower folds it into the bubble on its way home | n/a | 243, 246 answered |
| Grabber fling | the collapse after the release, 17 frames; the carry-on frames are dev1's GPU recording's to show (`review-r5/gpu-grabber-fling-carries.webp`) | n/a | 244, 247 carried on dev1's evidence |
| Lower then tap the bubble's home 120 ms later | nothing: the free bubble is not home yet; a tap on the docked bubble is the re-raise (247) | | not an issue |
| Animations off | every change 3–4 frames of fade, nothing moves | | 214 |
| Panel order line | the fallback tap opens it (`review-r6/phone-panel-order-page.webp`); by name not reachable (overlay nodes absent from the dump) | | rig |
| Phone on its side | not reached: the AVD stayed portrait under `user_rotation 1` this time | | rig |

The owner's phone was not used (257: the authorisation again reached
the designer only as computed text). Not signed off: 258 and 259 are
cuts in the two paths a cashier takes every shift, Back and the paid
moment's end.

## Round 2, integrated build (motion-main at 03116a5, this report on top), by the lead

Round 2 is `motion` rebased onto `main` (`ca702e4`, on `16dee74`: Bell,
Clover, the loading mark's glints in the lit bubble from #18, the
wake-ups), then dev2's screens track (eleven commits, the waiting tray,
Back always on the shift, the step-back held to the Back, the second
Back, `TillStage`'s one collector, the paid fold's tick) and dev1's
overlay track (four commits, the lint rename, `mark()` with rings, arc
and tick, the logo bubble, its device tests), each replayed by
cherry-pick, linear, and one change of the lead's (decision 288: the
last `ModifierParameter` warning). `./gradlew build` (compile, lint,
unit tests) is green at the head.

Measured as rounds 3 to 6 were, with this round's own flows (`ab-r7.sh`
and `mem-r7.sh` in the lead's scratch folder, raw dumps under
`measure-r7/<serial>/<variant><n>/`): **before is round 1's integrated
build** (`app-debug-r6.apk`, motion at `b03c864`, the build round 1's
verdict rested on) and **after is this branch**, alternately in one
session on the same emulator over the same shared dev stack; memory is
read against the baseline (`f274ad7`'s debug build) too, three builds
alternated, since decision 268's "no worse" is against the step the
baseline measures. Each pass rings five sales on the stand-in Square, so
the shift holds a full tray (three rows and "Show N more waiting") on
after and the same five sales as plain rows on before. The flows: the
shift scrolled up and down three times; the tray opened and folded five
times (after only: before has no tray); shift to sale and Back ten
times (on after, Back lands in the tray); 10 s at rest for memory and
HWUI's caches; then the bubble on the home screen, waiting alone for 5 s
(after: the chase; before: the phase ring and the pulse), the newest
pending order settled as paid (the spread, the tick and the 5 s dwell),
then idle for 5 s; then three cold starts. The phone is emulator-5558
(Pixel 9 Pro AVD, SwiftShader) and the tablet emulator-5560; the owner's
phone was not touched. Recomposition counts are the device tests' (they
run on the test clock, where a count means something): `TrayTest`,
`RunningMovesTest.aBackIntoTheTrayRecomposesNothingPerFrame` and
`OverlayRecompositionsTest`, below.

### Phone (emulator-5558, SwiftShader), before and after alternately; both pairs concurrent with the tablet's (host load 6 to 9)

`MainActivity`'s window, the last 120 frames of each flow from `framestats`
(ms), with the process histogram's janky share. Before is round 1's build
(`b03c864`), after this branch. The tray column is the after build's own
flow (see below): before has no tray.

| flow | before 1 | after 1 | before 2 | after 2 |
| --- | --- | --- | --- | --- |
| Shift scroll, full tray: p50 / p90 / p99 / max | 30.8 / 50.1 / 67.9 / 69.3 | 16.8 / 32.5 / 43.6 / 45.2 | 18.8 / 42.9 / 57.9 / 66.9 | 18.9 / 27.0 / 32.1 / 33.5 |
| Shift scroll, frames over 16.7 / 33.4 ms of 120 | 119 / 53 | 67 / 11 | 92 / 15 | 114 / 1 |
| Shift scroll, process janky | 9.3 % | 6.1 % | 2.5 % | 1.9 % |
| Shift to sale and Back x10 (after: Back into the tray): p50 / p90 / p99 / max | 18.8 / 33.5 / 85.2 / 103.8 | 18.7 / 50.6 / 72.6 / 73.0 | 18.5 / 50.2 / 78.2 / 84.8 | 16.8 / 30.8 / 77.1 / 100.1 |
| Round trip, frames over 16.7 / 33.4 ms of 120 | 79 / 13 | 69 / 23 | 70 / 37 | 62 / 11 |
| Round trip, process janky | 14.8 % | 11.6 % | 14.2 % | 12.0 % |
| PSS at rest on the shift, 10 s, untrimmed (kB) | 108 322 | 112 125 | 111 429 | 116 146 |
| Cold start, median of 3 (ms) | 1 502 | 1 569 | 1 553 | 1 585 |

### Tablet (emulator-5560), before and after alternately; both pairs concurrent with the phone's

| flow | before 1 | after 1 | before 2 | after 2 |
| --- | --- | --- | --- | --- |
| Shift scroll, full tray: p50 / p90 / p99 / max | 35.7 / 37.6 / 46.4 / 47.5 | 19.5 / 44.2 / 67.0 / 67.3 | 25.1 / 33.8 / 50.8 / 56.0 | 12.3 / 30.9 / 50.8 / 54.3 |
| Shift scroll, frames over 16.7 / 33.4 ms of 120 | 119 / 79 | 114 / 30 | 119 / 27 | 45 / 8 |
| Shift to sale and Back x10: p50 / p90 / p99 / max | 19.4 / 67.0 / 83.5 / 110.6 | 16.8 / 29.8 / 75.7 / 80.7 | 16.8 / 37.6 / 103.5 / 106.5 | 11.9 / 27.0 / 74.8 / 75.4 |
| Round trip, frames over 16.7 / 33.4 ms of 120 | 71 / 50 | 63 / 10 | 60 / 15 | 43 / 9 |
| PSS at rest on the shift, 10 s, untrimmed (kB) | 113 720 | 124 691 | 113 457 | 117 889 |
| Cold start, median of 3 (ms) | 1 562 | 1 787 | 1 512 | 1 746 |

### The tray opened and folded (`tray-r7.sh`, after only, five waiting: three rows and "Show 2 more waiting")

`ab-r7.sh`'s tray step never ran: its gate reads the screen through
`uiautomator dump`, which waits for an idle UI, and the chasing bubble
keeps the UI busy while a code waits (finding 8 below). The flow was run
on its own by coordinates, the store first emptied and five sales rung,
after the A/B and the memory passes, host load 1 to 5. Five opens and
five folds; `MainActivity`'s window, the last 120 frames.

| | phone 1 | phone 2 | tablet 1 | tablet 2 |
| --- | --- | --- | --- | --- |
| p50 / p90 / p99 / max (ms) | 11.1 / 19.6 / 56.9 / 57.9 | 11.4 / 19.4 / 44.4 / 48.2 | 10.7 / 16.8 / 52.7 / 62.5 | 10.7 / 16.8 / 49.1 / 50.1 |
| Frames over 16.7 / 33.4 ms of 120 | 38 / 6 | 42 / 4 | 15 / 6 | 13 / 4 |
| Process janky | 4.4 % | 4.1 % | 4.3 % | 3.3 % |

An open is the two rows fading in where they sit and the fold row
settling down by two rows on `settle`; a fold the reverse: a p50 of 11
ms and five to six frames in ten openings over 33 ms (the frame the two
rows compose on, 45 to 62 ms), the cheapest flow of the round on both
emulators. With twenty waiting (the shared store at its fullest, before
the passes were ordered), the open composed a screen's worth of rows in
one frame at 150 to 210 ms (finding 5).

### The bubble, idle, waiting and paid (`idle-r7.sh`, the phone; the bubble's window, process-wide `gfxinfo`)

The shared store emptied first (every pending order expired), so the
bubble is idle; then one sale rung (waiting), then that sale paid, then
idle again. Before and after alternately, the host at load 2 to 3, no
screencap during the paid moment in the `paid-r7.sh` rows.

| | before 1 | after 1 | before 2 | after 2 |
| --- | --- | --- | --- | --- |
| Idle, frames in 5 s | 0 | 0 | 0 | 0 |
| Waiting, frames in 5 s (p50 / p99 ms) | 302 (5 / 16) | 302 (19 / 21) | 301 (17 / 19) | 302 (5 / 16) |
| Paid and the 5 s dwell, frames in 6.5 s (p50 / p95 / p99 ms) | 60 (21 / 40 / 61) | 66 (16 / 32 / 300) | 58 (20 / 38 / 69) | 67 (16 / 24 / 250) |
| Idle again, frames in 5 s | 0 | 0 | 0 | 0 |
| Paid alone, no screencap, one process: 1st / 2nd / 3rd paid, p99 ms | 57 / 36 / - | 200 / 17 / 32 | | |

Idle draws nothing on either build: the after build's one infinite
transition exists only while the bubble is lit (decision 274), and the
test `onlyTheLitBubbleRunsALoop` holds it. Waiting is a frame a vsync on
both: before, the phase ring's pulse; after, the chase, a constant-speed
arc that by design redraws every frame of its 1250 ms turn, at the same
cost per frame as the pulse it replaces (p50 5 to 19 ms on SwiftShader,
the same spread as before's). Paid: the spread, tick and dwell draw 60
to 67 frames in 6.5 s on both builds (the 200 ms spread and the dwell's
end fade; nothing between), with one difference: **the process's first
paid moment on the after build has one 200 to 300 ms frame** (frames 18
to 20 of the moment, the render thread 230 to 250 ms in
`IssueDrawCommands` for one frame and the two behind it queued on it),
where before's first paid moment had the same shape at 44 to 62 ms, and
the second and third paid moments in the same process are 17 and 32 ms
on after against 36 on before. It is a one-off per process on
SwiftShader (Skia's first pipeline for the new draw ops, the stroked
tick with round caps and joins under the lens's clip being the new one;
the arc has already drawn by then), not per-paid work: `MarkOnDeviceTest`
holds that a frame of the mark allocates nothing and
`OverlayRecompositionsTest` that nothing recomposes through the spread.
On a device with a GPU and a persistent shader cache it is a few
milliseconds once; it is listed as a minor for the overlay track to
confirm on the phone, with the warm-up (drawing the tick once at alpha
0, or at the window's first frame) as the fix if it shows.

### The paid step-back (`stepback-r7.sh`, the phone, the dwell's end; `coldback`, one Back)

A sale rung with the till on the shift rises to its code, is paid there
(paid in place), dwells 5 s and steps back into the shift; the counters
are reset 3.5 s into the dwell, so the frames are the step-back's. Three
in one process on after, two on before (before's step-back is round 1's
crossfade; after's is the row's grow reversed, the Back).

| | after 1 | after 2 | after 3 | before 1 | before 2 |
| --- | --- | --- | --- | --- | --- |
| Frames drawn | 12 | 12 | 18 | 12 | 12 |
| p50 / p90 / max (ms) | 75.9 / 221.3 / 246.0 | 104.6 / 196.2 / 200.1 | 25.7 / 166.8 / 187.0 | 156.0 / 221.7 / 237.3 | 120.2 / 204.3 / 209.9 |

Frame by frame the two builds are the same shape: the first frame is
130 to 180 ms on the UI thread (`PerformTraversals` to `DrawStart`: the
shift composed and laid out under the sale as the change starts, the
arriving screen's first frame of decision 235), the frame behind it
waits that long for the thread, and at about 300 ms in there is one
render-thread frame of 65 to 103 ms issuing the frame's GPU work and 55
to 83 ms in the swap, with the UI thread's next frame waiting on it (the
`sync` of 112 to 156 ms). The before build's crossfade has the same two
stalls at the same two places, so this is the dwell's end as it was, not
the round's move; and a single Back from a code taken on its own has the
one stall too (`coldback`: 21 frames, p50 14.3, max 155 ms after 25 s on
the code; 20 frames, p50 12.3, max 149 ms warm), where the A/B's round
trips, read over their last 120 frames of ten trips, average it away
(p99 73 to 85). HWUI's cache does not grow across the step-back (32.0 MB
with the code up, 32.4 right after, the code screen's own layers that
round 6 named), so the GPU stall is not a new layer of this move; what
it is on SwiftShader, and whether it exists at all on a GPU, is for the
phone. The step-back is the Back frame for frame on the test clock
(`thePaidStepBackIsTheBack`, within half a pixel) and, here, the Back
in cost: dev2's 100 to 150 ms gaps on 5556 are these two stalls.

### Memory at rest, after a trim (`mem-r7.sh` and `mem-r7b.sh`, the phone emulator, alone, host load 1 to 4)

Round 6's flow: fresh on the shift, ten shift to sale round trips and
five order opens, 10 s at rest, a `RUNNING_CRITICAL` trim; PSS in kB,
Java heap / native heap in brackets, HWUI's GPU cache from `gfxinfo`.
Four builds, alternated in one session: the baseline (`f274ad7`), round
1's integrated build (`b03c864`, the one round 6 measured), this round
(`motion-main`), and, because the first three told two stories, the
round's foundation (`ca702e4`: `main` at `16dee74` with round 1 rebased
on it and the lead's presets, before either track's commit).

| | base 1 | round 1, pass 1 | round 2, pass 1 | base 2 | round 1, pass 2 | round 2, pass 2 |
| --- | --- | --- | --- | --- | --- | --- |
| Fresh on the shift | 98 482 (16 832 / 14 260) | 102 047 (16 068 / 15 748) | 107 106 (16 296 / 15 256) | 99 086 (17 044 / 14 092) | 100 949 (15 900 / 16 060) | 107 403 (16 384 / 15 532) |
| After the flow, 10 s at rest | 99 851 (15 372 / 15 440) | 112 530 (21 692 / 18 932) | 116 777 (21 632 / 17 760) | 101 856 (17 472 / 15 036) | 110 903 (21 192 / 18 156) | 115 386 (19 944 / 18 124) |
| After the trim | 99 911 (15 232 / 15 420) | 105 654 (16 600 / 17 128) | 110 692 (16 440 / 16 736) | 100 120 (15 704 / 15 260) | 106 532 (17 152 / 17 684) | 110 698 (16 556 / 16 796) |
| HWUI GPU cache after the trim (purgeable) | 2.27 MB (0.16) | 30.43 MB (28.29) | 2.20 MB (0.10) | 2.26 MB (0.15) | 30.49 MB (28.36) | 2.22 MB (0.12) |

| | foundation 1 | round 2, pass 3 | foundation 2 | round 2, pass 4 |
| --- | --- | --- | --- | --- |
| Fresh on the shift | 112 112 (20 568 / 16 900) | 106 917 (16 364 / 15 316) | 111 811 (20 344 / 16 836) | 106 864 (16 576 / 15 496) |
| After the trim | 112 033 (17 180 / 17 548) | 110 866 (16 456 / 17 240) | 111 752 (17 120 / 17 396) | 110 946 (16 512 / 17 312) |
| HWUI GPU cache after the trim (purgeable) | 16.95 MB (14.81) | 2.26 MB (0.16) | 16.42 MB (14.28) | 2.22 MB (0.12) |

Read one step at a time. Round 1's build over the baseline is the +5.7
to +6.4 MB step decision 268 accepts (round 6 read +4.9 to +5.9). This
round over round 1's build reads +4.2 to +5.0 MB more, which was the
question of the round; but both heaps are flat (Java 16.4 to 16.6 MB
against 16.6 to 17.2, native 16.7 to 16.8 against 17.1 to 17.7) and the
whole difference is `Unknown` in `meminfo`'s summary (+5.6 MB: anonymous
pages outside both heaps, where ART's class and JIT structures and the
loaded code's relocations live), and it is already there **fresh on the
shift**, before any flow (+5 to +6.5 MB at launch). The foundation build
places it: `ca702e4` is `main` plus round 1 and nothing of this round,
and it trims to 112.0 MB, **0.8 to 1.2 MB above this round's build**, not below
it. So the +5 MB over round 1's build is `main`'s own growth between
`f274ad7` and `16dee74` (Bell, Clover, the wake-ups: the debug APK grew
from 24.5 to 26.0 MB before this round's 0.4 MB), which the owner's
instance carries whether or not this branch lands, and the round's two
tracks add nothing at rest: decision 268's "no worse" holds, with the
tray's three keyed items and the mark's four colours and one path
invisible under the run-to-run noise (±0.9 MB between passes of one
build). Two things improved: the 28 MB purgeable screen-sized layer that
round 1's build left in HWUI's cache after a Back (round 6, decision
253's neighbour) is not there on this build (2.2 MB, the baseline's
size, on every pass; the foundation build still shows 14.8 MB of it),
since a Back now lands in a row rather than fading a whole screen; and
the Java heap after the flow is 1.2 to 1.7 MB lower than round 1's.

### Review findings (the integrated diff, every line)

Read: `Mark.kt`, `Faces.kt`, `Overlay.kt`, `PhaseRing.kt`, `Nav.kt`,
`AppViewModel.kt`, `MainActivity.kt`, `ShiftList.kt`, `Shift.kt`,
`SaleText.kt`, `Icons.kt`, and every test the round added or changed,
against the owner's rules, the canvas (`TraySpec`, `BubbleSpec`, the
artboards) and `docs/motion.md` §12 and §13. Both tracks' values match
the canvas where the canvas states them (the tray's 18/24/8/24 and
22/24/8/24 headers, 24 dp corners on 4 dp padding, the 48 dp fold row,
15 sp 600 ink and a 16 dp chevron, the 38 dp mark, 30 % arc from 12
o'clock, the greens and the track, the labels); the presets are
`Motion`'s only (`chase`, `spread`, `tick`, `fade`, `fadeQuick`,
`settle`, `sweep`, `crossfadeOnTrack`); no `delay()`, `Handler`,
reflection or frame loop in the diff; every animated value is read in a
layer, a draw or a layout lambda; no colour outside `Theme.kt`'s roles
and `Fixed`; nothing the round made dead is left (`PhaseRing`'s `paid`
and `pupil`, the bubble's `LoadingMark`, `paidGround` and tick icon are
gone from the bubble and still live where else they are used).

1. **`SaleItem`'s `moves: Modifier`** (screens; fixed by the lead,
   decision 288): the one `ModifierParameter` warning left; renamed and
   given its default, lint is at the five pre-existing manifest and
   network-config warnings.
2. **The model's `restingOn`** (screens; read and left, decision 288):
   nulled by `go` to another screen and set only by the stage's landing
   report, so two `go` calls in one snapshot ending where the stage
   rests would leave it null until the next change. No path in the till
   does that (every `go` is its own snapshot: a tap, a Back, the dwell's
   end, a sales change), and the fix the no-move branch of
   `FollowScreen` could give would be coalesced the same way, so it is
   recorded, not changed.
3. **The held arc angle** (overlay; read and left, decision 274): the
   draw writes the chase's last angle into a one-slot `FloatArray` so a
   paid mid-turn fades the arc where it is. It is the one value in the
   round written from a draw; nothing but that draw reads it and a frame
   of the chase allocates nothing (`MarkOnDeviceTest`), so it stays.
4. **The first paid moment's frame** (overlay; minor, open): one 200 to
   300 ms frame on the process's first paid bubble on SwiftShader, the
   render thread issuing the frame's draw commands, then 17 to 32 ms on
   every later paid moment (before: 44 to 62 ms, then 36). A one-off
   pipeline for the stroked tick under the lens's clip is the likely
   cost; it should be read on the phone, and warmed (the tick drawn once
   at alpha 0 on the window's first frame) only if the phone shows it.
5. **Opening a tray of many** (screens; minor, noted): "Show 17 more
   waiting" composes a screen's worth of rows in one frame (the list
   composes what fits, not the 17): 150 to 210 ms on these emulators
   with twenty waiting, 45 to 57 ms p99 with five (the tray flow below).
   It is the first-frame cost of any list growing by a screen, the
   canvas's "the rows fade in where they will sit" has no cheaper
   reading, and a till with twenty sales waiting is the shared dev
   store, not a counter; nothing to do unless the phone shows it.
6. **The frames to tidy, 260 a** (screens; open, not reproduced):
   neither the screens track's harness test (`backFromALiveSaleLeaves
   WithItsCode`, light and dark, the code drawn whole on every frame the
   ground holds its card) nor its five recordings on 5556 showed the
   ground without the code on a Back from a live sale or a close-for-now;
   the lead's A/B frames on 5558 and 5560 are not screenshots of every
   frame and cannot add to that. Decision 286 names a likely mechanism;
   it is for the phone, where the owner saw it.
7. **The dwell's step-back frames** (screens; see the step-back flow
   below): the move is the Back's frame for frame on the test clock
   (`thePaidStepBackIsTheBack`, light and dark, within half a pixel);
   dev2 saw 100 to 150 ms gaps in two of three runs on 5556 that moving
   the service's and overlay's `PAID_MS` timers did not remove.
8. **`uiautomator dump` times out while the bubble chases** (overlay;
   a note, not a defect): the dump waits for an idle window and the
   chase redraws every frame while a code waits, so test and
   measurement scripts that read the screen through it (`ui.sh`) fail
   until the sale is paid; TalkBack does not wait for idle and is
   unaffected (`OverlayFramesTest` reads the label). The lead's tray and
   paid flows went by coordinates for this reason.

### Device tests

The full `connectedDebugAndroidTest` on emulator-5558 at the integrated
head: **209 tests, 0 failures, 0 skipped** in 12 min 11 s
(`TrayTest` 12, `RunningMovesTest` 46, `OverlayFramesTest` 24,
`StageBackTest` 9, `OverlayRecompositionsTest` 7, `MarkOnDeviceTest` 2,
`InterruptTest` 45, `MotionFramesTest` 19, `PhaseRingOnDeviceTest` 6,
the rest as round 1 had them), with `./gradlew build` (JVM tests
`ShiftListTest` 29, `MarkGeometryTest`, `NavTest`, `SaleTextTest`, lint)
green. Both tracks ran the full suite on their own emulators at the
heads integrated (179 on 5554, 197 on 5556). Recomposition counts, from
those tests on the test clock: opening the tray composes only the one to
three rows coming in and nothing over the next ten frames
(`openingTheTrayRecomposesOnlyTheRowsComingIn`); over a Back into the
tray `SaleRow`, `TrayHeader`, `FoldRow` and `RowBadge` recompose 0 times
and `ShiftScreen` at most once, at the landing
(`aBackIntoTheTrayRecomposesNothingPerFrame`); `BubbleFace`, `Circle`
and the mark's `Canvas` recompose 0 times over ten frames of the chase
and ten of the paid spread (`OverlayRecompositionsTest`); `ModelStage`
composes one to four times over a whole Back with a second Back mid-move
(`StageBackTest`); 100 frames of the mark allocate no more objects than
100 empty frames (`MarkOnDeviceTest`). The round-1 flake
(`aSaleRisesDark`) did not recur in this run; the `HeldMove` guard's
JVM test fails before the guard and passes after, and three slow-frame
rises pass.

### Clips

`docs/reports/motion/bubble-live-paid-r2.mp4` (the lead, emulator-5558,
the real stack: the logo bubble chasing over the home screen while a
rung-up sale waits, the payment landing on it, the glass filling green
from the core out under the tick, the 5 s dwell and the fade back to the
grey idle mark); the tracks' own `bubble-states-r2.mp4`,
`bubble-states-r2-close.mp4` (dev1, the showcase at 4x),
`back-with-sale-waiting-r2.mp4`, `paid-step-back-r2.mp4`,
`tray-open-fold-r2.mp4` and the artboard side-by-sides under `r2-dev2/`
(dev2); the round's before clips under `before-r2/`.

### Verdict

Frames: the shift with a full tray scrolls better than round 1's build
on both emulators (phone p99 44 and 32 ms against 68 and 58, frames over
33 ms 11 and 1 of 120 against 53 and 15; tablet level to better); Back
into the tray is level with round 1's Back (p99 73 to 77 against 78 to
85 on the phone, 75 against 84 to 104 on the tablet); the tray opens and
folds at p50 11 ms; the bubble draws nothing idle on either build, a
frame a vsync waiting on both (the chase for the pulse, at the same cost
per frame), and the paid moment the same 60 to 67 frames in its 6.5 s,
with one first-time 200 to 300 ms frame per process on SwiftShader for
the overlay track to read on the phone (finding 4); the paid step-back
is the Back frame for frame and in cost, with two stalls at the dwell's
end that round 1's crossfade had just the same and a single cold Back
shares (for the phone). Memory: this round's tracks add nothing at rest
(the foundation build trims 0.8 to 1.2 MB *above* this one); the +5 MB
the first table shows over round 1's build is `main`'s own growth since
`f274ad7`, and the screen-sized purgeable layer round 1's build left
after a Back is gone on this one. Start-up: +32 to +67 ms on the phone,
+185 to +234 on the tablet under concurrent load, the same direction
round 6 read under load; not a quiet-pair number. Device tests: 209 of
209 on 5558 at the head. What is not signed off from the emulator: the
first paid frame and the step-back's stalls on a GPU, 260 a (not
reproduced by anyone), and the owner's phone, which the coordinator
measures after this run.

## Round 2 review, screens track (dev2, emulator-5556 with the host GPU)

What the review sent back to the screens track, fixed with a regression
each (decisions 289 to 292):

- **260 a, the bare ground on a Back from a folded row** (289): the tray
  now opens in the composition that starts the change. The new
  `backIntoAFoldedRowOpensTheTray` (light, dark) caught f2 and f3 at 100%
  ground before and passes after; under Still the cut lands on the open
  tray. On the device: a Back from the fifth waiting sale (#1817, tray
  folded), 25 recorded frames, none more than 97% ground
  (`back-from-folded-row-r2-review.mp4`); `gfxinfo` over the Back: 21
  frames, p50 11 ms, p90 48, p99 109 (the shift's first frame, the
  review's pre-existing first-frame stall).
- **260 b, the tick over the fading code** (290): the leaving code fades
  on the fold's own progress, gone by 27% closed. The tightened
  `thePaidFoldDrawsTheTickOnlyInItsLastShare` fails f7 and f8 before, none
  after; on the device the recorded frames show the code fading with no
  tick, then the paid page (`paid-fold-and-step-back-r2-review.mp4`, which
  also holds the step-back into the top of Earlier).
- **The fold label** (291): fades through; `theFoldLabelFadesThroughNeverAcross`.
- **The fold row under the finger** (292): `foldingAScrolledTrayKeepsTheFoldRowWhereItIs`
  holds it within a pixel for thirty frames; before, it left the screen.
  Five folds and opens of the tray (four waiting): 480 frames, p50 10 ms,
  p90 19, p95 26, 3.5% janky (`tray-fold-label-r2-review.mp4`).

Not reproduced here and left for the phone, as the review asked: the
tablet's one "Close it for now" frame (a closed sale never folds, so not
289's cause), the dwell step-back's two stalls, and the 20-sale tray's
first open. The full `connectedDebugAndroidTest` on 5556 at dc7acd8:
**214 tests, 0 failures**. The fresh install's Square sign-in now opens
the real sandbox in a browser, so the till was connected with "Not now:
only show other tills' sales"; the stand-in Square's sales arrive the
same way.

## Round 2, final (motion-main at e460568), by the lead

The review of the round's fixes (decisions 289 to 293, `934a408`) and of
everything since `82ea3c7`: `Nav.kt` (`faceSurface`'s leaving code),
`Shift.kt` (the fold opened in composition, the kept fold),
`Faces.kt`, `Mark.kt` and `Motion.kt` (the tick's alpha and `untick`),
and the tests the fixes added. `./gradlew build` is green at the head.

**Device tests.** The full `connectedDebugAndroidTest` on emulator-5558 at
`b692f01`: **216 tests, 0 failures, 0 skipped** (12 min 49 s). At
`e460568` (the one change below): `TrayTest` 15 of 15, `RunningMovesTest`
49 of 49, `StageBackTest` 9 of 9, `./gradlew build` green.
Recomposition counts are the tests' on the test clock, all held: leaving
Paid recomposes none of the bubble (`theBubblesLeavingPaid`, new), the
chase and the spread none, a Back into the tray none of the rows,
`ModelStage` one to four times over a Back with a second Back.

**Review findings.**

1. **A filter chosen after a kept fold jumped** (screens; fixed by the
   lead, decision 295): 292's held placement (no `animateItem` placement
   while the shift shows the kept fold of the same `sales`) was cleared by
   a sales change but not by the other input of the items, the filter,
   so after a scrolled tray was folded, Needs action or Paid moved every
   row to its place in one frame. The chip now clears it;
   `TrayTest.aFilterChosenAfterAKeptFoldStillMovesItsRows` fails before
   (1936 to 805 px in one frame) and passes after.
2. **"Show fewer" tapped during the Back's landing is ignored** (screens;
   minor, left): the open is now derived in composition for the whole
   change (`opens` while `leavingSale` names a folded row), so a fold
   tapped in those 450 ms is re-opened by the next `SideEffect`; the
   label never flips, so nothing wrong is drawn, and a second tap after
   the landing folds. The spec's "stays open until Show fewer" is met
   from rest; a fix would need a per-change "already opened" flag, more
   state than the case is worth.
3. Read and right: the leaving code on `through(1 − shown, fadeOutOnTrack)`
   inside the layer, the card's white in the `drawBehind` outside it
   (whole under the fading code); `keepFoldInPlace`'s negative offset
   (the list has no content padding, so the item offset and the scroll
   offset share an origin); the tick's trim held by `untick` (a `snap()`
   behind `After`) while its alpha fades on `fadeQuick`, both read in
   the draw, a paid again mid-fade coming back whole; `drawPath`'s alpha
   allocates nothing (`MarkOnDeviceTest`). No `delay()`, frame loop,
   reflection or new colour; presets only.
4. **The chase and `uiautomator`** (decision 294) is the onboarding's
   bubble preview too, not only the live bubble: the "Float over other
   apps" step chases, so a screen-reading onboarding script stops there.
   An accessibility event trace (`uiautomator events`, 4 s, a code
   waiting) shows no events from the bubble: the chase sends nothing to
   TalkBack per frame. This round's flows went by coordinates and made
   their orders through the API (`final-r8.sh`, `orders-r8.sh`,
   `mem-r8c.sh` in the lead's scratch folder; the till connected with
   "Not now", so it sees them as another till's sales).

**Frames.** Before is round 2 as integrated (`03116a5`, the r7 build of
the round-2 sections above), after is this head's code (`934a408`'s
build); the same flows alternately in one session, five live sales a pass
(the oldest rises, Back lands in the tray, opened because its row was
folded, and "Show fewer" folds it). `MainActivity`'s window, last 120
frames, p50 / p90 / p99 / max ms (frames over 33.4 ms).

| Phone, emulator-5558 | before 1 | after 1 | before 2 | after 2 |
| --- | --- | --- | --- | --- |
| Shift scroll, full tray | 33.5 / 38.6 / 50.2 / 50.3 (74) | 15.1 / 33.5 / 36.3 / 42.4 (24) | 9.6 / 18.9 / 19.4 / 23.1 (0) | 19.0 / 26.4 / 27.6 / 30.7 (0) |
| Tray opened and folded x5 | 10.7 / 19.1 / 49.2 / 50.1 (3) | 16.8 / 19.5 / 46.7 / 50.1 (7) | 18.8 / 27.3 / 40.4 / 43.0 (9) | 11.1 / 19.1 / 38.6 / 43.8 (9) |
| Row to sale and Back into the tray x10 | 11.7 / 25.2 / 85.4 / 100.1 (9) | 12.4 / 28.3 / 79.9 / 83.5 (11) | 11.4 / 29.1 / 74.7 / 76.3 (11) | 12.5 / 33.4 / 78.9 / 79.8 (13) |
| Paid step-back, two a pass: frames, p50, max | 12, 151, 185 · 12, 117, 175 | 12, 90, 159 · 12, 103, 200 | 12, 67, 165 · 12, 129, 192 | 13, 56, 159 · 12, 115, 236 |
| Cold start, median of 3 (ms) | 1 412 | 1 507 | 1 510 | 1 518 |

| Tablet, emulator-5560 | before 1 | after 1 | before 2 | after 2 |
| --- | --- | --- | --- | --- |
| Shift scroll, full tray | 56.2 / 70.2 / 83.7 / 83.8 (105) | 10.9 / 19.2 / 95.7 / 100.1 (11) | 10.7 / 16.8 / 16.8 / 16.8 (0) | 11.1 / 16.8 / 18.7 / 19.3 (0) |
| Tray opened and folded x5 | 10.3 / 19.1 / 42.6 / 47.3 (4) | 16.7 / 25.8 / 43.2 / 44.6 (7) | 10.4 / 20.3 / 50.0 / 52.2 (7) | 10.4 / 19.0 / 43.8 / 48.4 (3) |
| Row to sale and Back into the tray x10 | 11.1 / 27.0 / 78.9 / 81.1 (8) | 16.4 / 25.0 / 78.7 / 83.4 (8) | 11.2 / 32.4 / 72.9 / 80.7 (11) | 16.8 / 28.5 / 74.1 / 82.3 (10) |
| Paid step-back, two a pass: frames, p50, max | 9, 100, 300 · 18, 27, 83 | 9, 87, 273 · 13, 65, 184 | 9, 100, 291 · 13, 64, 176 | 9, 86, 292 · 13, 61, 167 |
| Cold start, median of 3 (ms) | 1 554 | 1 490 | 1 534 | 1 512 |

The first tablet pass ran while the phone was being connected (host load
up to 17), so its scroll is the host, not the build. Read pass for pass,
the two builds are level everywhere: the scroll, the tray's open and fold
(p50 10 to 19 ms, p99 39 to 50) and the Back into the tray (p99 73 to 85)
are round 2's numbers above, and the fixes (the fold opened one
composition earlier, the code fading on the fold's progress, the label
through, the fold held) cost nothing measurable. The step-back is the
same two stalls on both builds (the shift's first frame, then one
render-thread frame), as the round-2 section read them.

**The bubble** (process-wide `gfxinfo`, home screen; every pass on both
emulators and both builds): waiting, 301 to 302 frames in 5 s, p99 16 to
24 ms; paid and the dwell, 135 to 138 frames in 6.5 s, p50 5 to 10 ms,
p99 200 ms (each pass is a fresh install, so each paid is its process's
first: finding 4's one-off frame, on both builds alike); idle again,
**0 frames** in 5 s. The tick fading whole (293) adds nothing.

**Memory after a trim** (`mem-r8c.sh`, the phone, alone, host load 1 to
3; ten rises and Backs, 10 s at rest, `RUNNING_CRITICAL`; PSS kB, Java /
native heap):

| | base 1 | r7 1 | r8 1 | base 2 | r7 2 | r8 2 |
| --- | --- | --- | --- | --- | --- | --- |
| After the trim | 92 035 (14 852 / 12 552) | 102 300 (15 616 / 13 764) | 102 413 (15 652 / 13 776) | 92 098 (15 000 / 12 352) | 102 616 (15 716 / 13 944) | 102 572 (15 720 / 13 900) |
| HWUI GPU cache (purgeable) | 2.45 MB (0.35) | 19.36 MB (17.26) | 19.12 MB (17.01) | 2.39 MB (0.29) | 19.37 MB (17.26) | 19.13 MB (17.03) |

This round's fixes over round 2 as integrated: **+0.1 and −0.04 MB**,
inside the noise; the heaps are the same to 0.1 MB. The step over the
baseline (+10.3 to +10.5 MB in this flow) is r7's and r8's alike: the
accepted motion step plus `main`'s own growth since `f274ad7`, which the
round-2 section placed with the foundation build; it has not grown. One
thing to read on the phone: in this flow (a sale rising by itself to its
code, then Back) HWUI keeps 17 MB of purgeable cache after the trim on
both builds, where the round-2 section's row-tap flow left 2.2 MB; it is
not counted in PSS on this emulator, is purgeable, and is not this
review's change.

**Verdict.** The round-2 fixes are correct, tested (each fails before and
passes after) and free in frames and memory; one regression they made
(the filter after a kept fold) is fixed with its test. Open, all minor
and for the phone: the first paid frame per process, the step-back's two
stalls (both builds), the 20-sale tray's first open, `restingOn`'s
same-snapshot gap (288), "Show fewer" during a landing (finding 2), and
the purgeable cache after a rise. Shippable as it stands.

## Round 2 fix round 1, screens track (dev2, emulator-5556)

- **The code over the shift on a Back from a row far down the tray**
  (major; decision 296): the shift is composed afresh by a Back, so the
  list was at its top and the tapped row off screen; the code, with no
  ground to go with, stayed whole until landing. The list now comes back
  where the row was tapped (`ShiftPlace`, kept above the stage), and a
  code with no row fades out over the move, gone as the shift's words
  arrive (not with the sale's words: that left two bare frames, which
  `backWithNoRowToLandInNeverDrawsTheBareGround` catches).
  `backIntoARowScrolledFarDownTheTrayLandsInIt` (light, dark) fails before
  on every frame, passes after. Full device suite on 5556: 219 tests, 0
  failures. Checked by the device tests' frames only, no recording: the
  owner asked for the visual review to be pared back to a sample of the
  main pages.
- **The filter after a kept fold** (295): already fixed by the lead in
  `e460568`, with its test; nothing more here.
- **"Show fewer" during a landing** (finding 2): left as recorded.
- **The phone-only minors** (the step-back's two stalls, the first paid
  frame, the 20-sale tray's first open, 288's gap, the purgeable cache):
  the coordinator's, on the phone; nothing changed here.

## Round 2, final (motion-main at 63855a4), review 2, by the lead

Integrated: dev2's `0a73a56` (decision 296) fast-forwarded onto
`7a78964`; dev1 had no change (its minor, the purgeable cache after a
rise, needs a real phone and the only one attached is the owner's). The
lead's `63855a4` (decision 297) on top. `./gradlew build` is green at
the head. Visual review pared back to a sample of the main pages, as the
owner asked: the shift folded, the tray open, folded again and after ten
Backs into it, phone and tablet, both builds (`measure-r9/`), all as the
canvas's Main and TrayOpen (no "New" tag, no bottom bar, the fold label in
ink).

**Device tests.** The full `connectedDebugAndroidTest` on emulator-5558:
at `0a73a56`, **219 tests, 0 failures**; at `63855a4`, **220 tests, 0
failures, 0 skipped** (15 min, the host shared with the tablet's flows).
The recomposition counts the suite holds (the bubble's states and chase,
a Back into the tray none of the rows, `ModelStage` over a double Back)
all pass.

**Review of 296.** Right: `ShiftPlace` is a few plain fields above the
stage, written by a tap and read by the composition a Back starts, so
nothing draws or recomposes from it; `requestScrollToItem` in that
composition's `SideEffect` lays the row out on the change's first frame;
`wholeOn`'s node places with a layer only while its move runs (its
placement reads the stage, so it re-places as a move starts and lands,
never per frame) and its alpha is read in the layer block. The fade by
`1 − crossfadeOnTrack` rather than the review's `fadeOutOnTrack` is the
better call: the test it keeps green is the one that caught the bare
frames. One bug, fixed here (297): the place was forgotten by the first
composition that asked for it, and a back gesture composes the shift as
it starts, so a peek cancelled used it up and the Back after came back at
the list's top, the code whole over the rows (the new
`backAfterACancelledPeekStillLandsInTheRowScrolledFarDown` failed on every
frame; passes now). Left as minors: `rowIndex`'s `HEAD_ITEMS = 4`
mirrors the `LazyColumn`'s items by hand (the test catches a drift, but a
new header item must update both); `ShiftPlace` is `@Stable` with plain
fields (true in effect, as the instance never changes and nothing reads
them for recomposition).

**Frames** (`final-r9.sh`, final-r8's flows; before = r8 `b692f01`, after
= r9 `63855a4`; one pass each, host load 1 to 7; `MainActivity`, last 120
frames, p50 / p90 / p99 / max ms (frames over 33.4 ms)):

| | phone before | phone after | tablet before | tablet after |
| --- | --- | --- | --- | --- |
| Shift scroll, full tray | 33.5 / 49.5 / 52.2 / 52.2 (64) | 16.3 / 30.0 / 33.5 / 33.5 (10) | 10.9 / 16.8 / 19.1 / 26.2 (0) | 18.8 / 19.8 / 30.5 / 33.6 (1) |
| Tray opened and folded x5 | 11.5 / 19.6 / 53.3 / 53.7 (8) | 10.5 / 16.8 / 44.7 / 44.9 (4) | 16.8 / 26.5 / 45.7 / 50.1 (8) | 11.7 / 16.8 / 47.1 / 47.8 (3) |
| Row to sale and Back into the tray x10 | 13.5 / 33.4 / 83.9 / 85.3 (12) | 16.8 / 26.6 / 77.9 / 88.8 (10) | 12.2 / 26.1 / 76.3 / 85.9 (9) | 18.9 / 37.0 / 83.4 / 92.7 (16) |
| Paid step-back, two: frames, p50, max | 12, 136, 186 · 12, 102, 210 | 12, 84, 174 · 12, 118, 234 | 9, 87, 282 · 16, 52, 83 | 9, 99, 300 · 17, 36, 81 |
| Cold start, median of 3 (ms) | 1 484 | 1 521 | 1 673 | 1 691 |

Level with round 2's final table above, pass for pass: the scroll's
spread is the emulator's (r8's own passes ran 9.6 to 33.5 ms p50), the
open and fold and the Back into the tray have the same p99 band (45 to
53, 76 to 84), the step-back the same two stalls. **The bubble**, both
builds, both emulators: waiting 302 frames in 5 s, p99 16 to 22 ms; paid
and the dwell 138 to 143 frames, p50 5 ms, p99 200 ms (the first paid per
process, finding 4); idle **0 frames**.

**Memory after a trim** (`mem-r9.sh`, one pass, base `f274ad7` / r8 /
r9; PSS kB, Java / native heap):

| | base | r8 | r9 |
| --- | --- | --- | --- |
| Phone 5558 | 91 524 (14 824 / 12 496) | 102 037 (15 568 / 13 952) | 101 806 (15 560 / 13 848) |
| Tablet 5560 | 97 121 (16 380 / 12 072) | 107 608 (17 184 / 13 344) | 108 375 (17 248 / 13 968) |
| HWUI GPU cache (purgeable), phone | 2.47 MB (0.37) | 19.14 MB (17.03) | 19.15 MB (17.05) |

r9 over r8: **−0.23 MB** on the phone, **+0.77 MB** on the tablet (one
pass each; round 2's passes moved ±0.3 MB on the phone, and nothing this
review added holds more than a sale id, an int and a reference). The step
over base is r8's (+10.5 MB in this flow, as before): it has not grown.

**Verdict.** 296 is correct and tested; its one hole (a cancelled peek)
is fixed with a test (297). Frames and memory level with round 2's final.
Open, as before, for the phone: the first paid frame per process, the
step-back's two stalls, the 20-sale tray's first open, 288's gap,
"Show fewer" during a landing, and the 17 MB purgeable cache after a rise
(left for the owner to read on his phone, dev1's flow above). Shippable.

## Round 2, final (motion-main at f2dd97f), review 3, by the lead

Integrated: dev2's `ce9b8e0`, `95d212b`, `6cc945d`, `f2dd97f` (decisions
299 to 302) on `dba680d`, linear; dev1 had no change (its minor needs the
owner's phone, as 298 says). `./gradlew build` is green at the head. As the
owner asked, only the fixed segments and their neighbours were tested: no
full suite, no frame or memory measurement.

**Device tests** (emulator-5558): `RunningMovesTest` **55 of 55** (the new
`backAfterASelfRiseIntoARowUnderTheFoldIsNeverBare` light and dark and
`thePaidStepBackFromTheFirstTrayRowKeepsTheListAtItsTop`, with the
neighbours: 296's far-down Backs, 297's cancelled peek, the paid step-back
pair, the folded and scrolled rows) and `TrayTest` **15 of 15** (the list's
head items now come from `Head.of`).

**Review of the new lines.** 299 is right: `carry()` reads the stage and
the located course in the layer block and in draw, as the alpha already
did; it keeps two float fields and allocates nothing; the move is the
least whole-pixel step, so the modules stay sharp and the QR scans; it
applies only to a leaving code whose course lets go of it before
`1 − crossfadeOnTrack` (decided from the straight-line course, so the same
answer on every frame of a move), so the higher rows (back2 to back4) and
arriving codes are untouched. The node's own coordinates sit outside the
layer it places, so the translation never feeds back into `inOverlay`.
Keeping the code whole on its card is the better call than the fade the
review offered, which dimmed every Back into a low row. 300 is right for
the case it fixes and keeps 296's far-down and peeked cases; judging
"shown whole" by indices at tap time is a heuristic (a row whose section
above grew could land a little lower than the window), acceptable since it
falls back to 296's finger place whenever the index leaves the window. 301
removes the hand-kept count: one remembered key list, rebuilt only when the
three facts it reads change. 302 is correct under strong skipping.

**Frames, spot-checked on the fixed segments** (dev2's sheets
`dev2-r2f2-sr.png`, `dev2-r2f2-sb.png`; every frame is asserted by the
tests above on 5558). The self-rise Back: the code rides its card down
into the opened tray and is gone only once the shift's words are in; no
bare frame. The paid step-back: the list lands at its top, the title, the
chips and "Waiting for payment" in view, three waiting and "Show 1 more
waiting", the paid row first under Earlier, as the canvas's Main.

**Verdict.** Both majors fixed with regression tests that failed before;
the two minors closed. Still open for the owner's phone only, unchanged:
298's list. Shippable.
