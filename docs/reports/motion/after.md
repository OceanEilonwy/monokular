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
