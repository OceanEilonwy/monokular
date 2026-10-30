# Motion: the baseline

Measured 2026-10-29 on `origin/main` f274ad7 (the till redesign #15 and the
Bell #14), before any motion work. Every number here is the "before"; the
same flows, scripts and emulators give the "after".

## What was measured, and on what

| | Phone | Tablet |
|---|---|---|
| AVD | `Pixel_9_Pro_motion` (a copy of `Pixel_9_Pro`: 1280×2856 @ 480 dpi, API 36, Play image) | `Pixel_Tablet` (2560×1600 @ 320 dpi, API 36) |
| Serial | `emulator-5558` | `emulator-5560` |
| GPU | SwiftShader (software GL) | SwiftShader (software GL) |
| Animator scale | 1.0 | 1.0 |

Builds from the same commit:

- **debug** `app-debug.apk`, `-Pmonokular.square.baseUrl=http://10.0.2.2:8738`,
  connected to Monokulo's `apps_dev_server` on :8737 and `tools/fake-square`
  on :8738, the shift filled with 24 sales (12 paid, 6 expired, 6 live, from
  `curl -X POST localhost:8738/fake/sale` and the dev server's
  `/__dev/orders/{id}/paid|expired`). Every flow below ran on this build: a
  release build can't connect to the cleartext dev stack
  (`ConnectCode.parse(allowCleartext = BuildConfig.DEBUG)`), so it only gives
  size and cold start on the Welcome screen.
- **release** `assembleRelease` (R8, resources shrunk), signed with the debug
  key to install.

Tools: `adb shell am start -W` for cold start, `dumpsys gfxinfo <pkg>
reset` / `framestats` for frames, `dumpsys meminfo` for memory, `apkanalyzer`
for size. The scripts (`flows.sh`, `measure.sh`, `ui.sh`,
`parse_framestats.py`) live in the lead's scratch folder and drive the
emulator through `uiautomator dump` and `input tap|swipe`, so a rerun is one
command per flow: `ANDROID_SERIAL=emulator-5558 flows.sh all`.

### Emulator caveats (read before comparing)

- Both AVDs render through SwiftShader on the host CPU, with three other
  emulators and Gradle builds sharing the same 32 cores. Absolute frame times
  are 3–5× a real Pixel's and vary between runs by ±30%. Compare **counts**
  (frames rendered per second while idle, frames per transition, frames over
  the deadline) and **ratios** on the same emulator in the same session, not
  milliseconds against a phone.
- The phone run and the tablet run differ in host load, not in the app: the
  tablet's shift scroll came out at p50 10 ms and the phone's at 50 ms with
  the same code. Treat each device's numbers as its own baseline.
- `gfxinfo` on the overlay windows reports them as `Window:` with no name;
  the per-window lines below call them "overlay".
- Cold start in debug includes the profile installer and JIT; the release
  number is the floor for the Welcome screen only.
- `uiautomator` doesn't see the overlay windows, so the panel was lowered by
  tapping the bubble again (which toggles it), not the grabber.
- From round 2 on, `uiautomator dump` times out while a sale waits: the
  bubble's border chases every frame, and the dump waits for an idle
  window. Every script that reads the screen through it (`ui.sh nodes`,
  `tap_text`, the A/B's tray gate, `to_shift`) fails until the sale is
  paid, expired or closed. Pay or expire the waiting sale before
  dumping, tap by coordinates, or read the Compose semantics through a
  device test instead; TalkBack does not wait for idle and is unaffected
  (`after.md`, round 2 integrated, finding 8).

## Numbers

### Cold start (`am start -W`, median of 5, process killed between runs)

| | Phone | Tablet |
|---|---|---|
| debug, connected, lands on the shift | 2677 ms (2507–2762) | 2901 ms (2544–3977) |
| release, not connected, lands on Welcome | 882 ms (800–1085) | 1643 ms (1591–1901) |

### Frames

Per flow: `gfxinfo` totals for the process, then the last 120 frames of the
window from `framestats` (IntendedVsync → FrameCompleted; frames flagged as
window-layout changes skipped).

**Shift with 24 sales, 12 swipes up and down**

| | frames | janky | p50 | p90 | p99 | window p50 / p90 / p99 | >33 ms |
|---|---|---|---|---|---|---|---|
| Phone | 361 | 148 (41%) | 42 ms | 69 ms | 150 ms | 50 / 77 / 101 ms | 102 of 120 |
| Tablet | 622 | 39 (6%) | 16 ms | 31 ms | 61 ms | 10 / 15 / 19 ms | 0 of 120 |

**Enlarged code: open from the sale and tap closed, 3 times**

| | frames | janky | p50 | p90 | window p50 / p90 / max |
|---|---|---|---|---|---|
| Phone | 18 | 16 (89%) | 32 ms | 109 ms | 34 / 109 / 111 ms |
| Tablet | 20 | 12 (60%) | 16 ms | 85 ms | 17 / 88 / 92 ms |

Three opens and three closes render **18–20 frames in all**: each is a hard
cut of one frame, and that frame is 85–110 ms (the whole screen composes,
the code is encoded again, the brightness and bar colours change).

**Overlay: home, tap the bubble, lower, 3 times (the panel up ~5 s in all)**

| | frames | janky | p50 | p90 | overlay windows, last 120 frames |
|---|---|---|---|---|---|
| Phone | 615 | 296 (48%) | 42 ms | 81 ms | p50 77 / p90 100 / max 138 ms |
| Tablet | 896 | 274 (31%) | 29 ms | 57 ms | p50 19 / p90 37 / max 43 ms |

Opening and lowering are also hard cuts (`addView` / `removeViewImmediate`).
The 600–900 frames come from the lit bubble's pulse: it redraws its window
every frame the whole time a sale is live.

**Lit bubble alone on the home screen for 5 s (phone):** 279 frames
rendered, i.e. ~56 fps of continuous drawing for a 56 dp halo, while
nothing else happens. The idle bubble draws nothing.

**Bubble drag: across the screen, back and down (3 swipes)**

| | frames | janky | p50 | p90 | overlay windows |
|---|---|---|---|---|---|
| Phone | 149 | 114 (77%) | 53 ms | 85 ms | p50 74 / p90 94 / max 100 ms |
| Tablet | 201 | 79 (39%) | 32 ms | 73 ms | p50 47 / p90 112 / max 373 ms |

The drag moves the window itself (`updateViewLayout` per move event) and the
snap is a `ValueAnimator` with a duration formula, also moving the window
per frame; every frame of it is over the deadline on both emulators.

### Memory (`dumpsys meminfo`, PSS in kB)

| | Java heap | Native heap | Code (RSS) | TOTAL PSS | TOTAL RSS | ViewRootImpl |
|---|---|---|---|---|---|---|
| Phone, debug, shift with 24 sales | 12 148 | 16 392 | 113 696 | 103 276 | 224 560 | 1 |
| Phone, debug, after the overlay flows, bubble up | 12 148 | 16 608 | 114 280 | 105 568 | 227 840 | 2 |
| Phone, debug, back on the shift (settled) | 14 464 | 11 940 | — | 91 729 | 224 424 | 1 |
| Tablet, debug, shift with 24 sales | 19 624 | 17 448 | 113 472 | 107 204 | 234 520 | 1 |
| Tablet, debug, panel and bubble up | 17 424 | 17 176 | 114 144 | 108 741 | 237 184 | 3 |
| Phone, **release**, Welcome (not connected) | 1 160 | 7 012 | 94 928 | 23 916 | 149 928 | 1 |

The panel window costs ~2 MB PSS and one more ViewRootImpl while it's up,
and gives it back when lowered. Debug carries ~70 MB of Compose and ART
debug overhead; the release figure is the floor to watch, not the debug one.

### APK

| | file | download (Play) | dex | native | res |
|---|---|---|---|---|---|
| debug | 23.6 MB | 19.7 MB | 41.2 MB (uncompressed) | 5.1 MB | 0.4 MB |
| release | 8.0 MB | 4.5 MB | 3.6 MB | 5.1 MB | 0.3 MB |

Native is the receipt codec for three ABIs (1.2–1.5 MB each) plus JNA's
`libjnidispatch.so`, which ships for `armeabi`, `mips`, `mips64` and `x86`
too, ABIs the app doesn't build for (~0.5 MB of dead weight; an `abiFilters`
line, outside this work's scope).

## Clips

Short recordings of the baseline, 12 fps webp, in `baseline/`:
`phone-code-open-close`, `phone-panel-open-lower`, `phone-bubble-drag` and
the tablet's three; `phone-panel.webp` and `tablet-panel.webp` are stills of
the panel up. On the tablet the free bubble is **behind** the floating panel
(a sliver shows at the panel's right edge); the designer has it as a QA
finding in decision 147.

## The UI code with a performance eye

What's already right: `BubbleFace`'s pulse and `LoadingMark`'s glints read
their animated value in `drawBehind` / `Canvas`, never in composition; the
panel's drag goes through `Modifier.offset { }` and `graphicsLayer { }`;
`reducedMotion()` exists and the two loops honour it; `QrCode` remembers
its bitmap per text; the shift's rows are keyed by order id.

What to fix as part of this work, most to least important:

1. **The lit bubble pulses at ~56 fps for as long as a sale is live**
   (`Faces.kt` `Circle`, `BubbleLook.Lit`): a 1.6 s `infiniteRepeatable`
   that invalidates the bubble's window every frame, for minutes at a time,
   on a till that spends its day with a sale up. With the panel up on a
   phone the docked bubble pulses too, in the full-screen panel window. The
   pulse is one of the two loops the design allows, but it should run at a
   cadence a halo needs (a 1.6 s cycle is fine; the point is that it runs
   only in the free bubble, never while the panel covers it, and stops the
   moment the look changes).
2. **One countdown timer per Text** (`Till.kt` `secondsLeft`): every status
   row, status pill, shift row of a live sale and the order bar each start
   their own `LaunchedEffect` ticking a `mutableLongStateOf` once a second.
   Six live sales in the shift means six timers and six recompositions a
   second, each rebuilding the row's strings (`ShiftList.row`). One clock
   per screen, read by the texts that count down, with tabular figures so
   the tick never moves layout.
3. **Unstable action bundles**: `PanelActions` is built inside the panel's
   content lambda on every state change (`Overlay.addPanel`), seven new
   lambdas each time, so `PhonePanel` / `SalePanel` recompose wholesale on
   every tick and runner update; `TillActions` and `PanelActions` are plain
   classes, so Compose can't skip on them. Mark them `@Immutable`, build
   `PanelActions` once per sale (`remember(sale.orderId)`), and pass the
   sale's id to the overlay's callbacks rather than capturing the sale.
4. **The code is encoded three times per sale**: the sale screen, the
   enlarged code and the panel each `remember(text) { qrBitmap(text) }` in
   their own composition, and a screen change throws the bitmap away. A
   shared element across screens needs one `ImageBitmap` for the same text:
   a tiny cache (the last few codes) in one place. The notification's large
   icon (`SaleNotifications.kt`, scale 4, ~880 kB) is encoded on the main
   thread in the service's collector; move it off.
5. **Window churn**: `Overlay.show` adds and `removeViewImmediate`s the
   bubble and panel windows on every change, so each open composes the panel
   from nothing (the 85–110 ms frame above) and each lower tears it down
   synchronously. Removal has to wait for the collapse anyway (decision 148);
   `removeViewImmediate` stays only in `stop()`.
6. **`BubbleFrame.snap`** (`Overlay.kt`): `ValueAnimator.ofInt` with a
   hand-made duration, an anonymous `AnimatorListenerAdapter`, and no
   velocity from the finger. Replaced by an `Animatable` + spring seeded with
   the release velocity (decision 148).
7. **The root recomposes on every runner update** (`MainActivity.setContent`
   collects seven flows and the whole `when` reads `runner`): with
   `AnimatedContent` at the root, every poll would hand the transition new
   content lambdas. Hoist `shown`, `TillStatus` and the actions behind
   `derivedStateOf`/`remember` keyed on what each screen needs.
8. **Per-row allocations in the shift**: a `RoundedCornerShape` per row per
   composition and `ShiftList.row` strings per tick. Cheap, but with
   `animateItem` every row becomes an animated layer; precompute the two
   shapes and keep the row model in `remember(sale, seconds)`.
9. **`FullBrightness` and the bar colours** flip in the same frame the code
   screen composes. The system's display controller already ramps the panel
   brightness (an app-side ramp would only add a relayout per frame), so the
   fix is ordering: set them after the grow completes, not before it starts.

Not an issue, checked: the infinite transitions stop when their window goes
(the bubble's window is removed while Monokular is in front; the loading mark
only lives on the Square step of setup); `MarkPaths` parses its paths once;
the glint loop allocates nothing per frame (inline `forEach`, no boxing).

## Architecture for motion

The spec is `docs/motion.md` (the designer's: principles, the preset table,
the spatial model, M1–M14). The technical calls are decisions 148–96 in
`docs/decisions/android.md`; in short:

- **Presets** (148): `ui/Motion.kt` beside the theme tokens, one
  `@Immutable class Motion` with the spec's names (`surface`, `settle`,
  `flick`, `sweep`, `fade`, `fadeQuick`, `pulse`, `glints`, `Dwell.paid`),
  `Motion.Full` and `Motion.Still`, and `LocalMotion` provided by
  `MonokularTheme` from a `rememberReducedMotion()` that observes
  `ANIMATOR_DURATION_SCALE`. Call sites write `motion.surface`, never a
  number. Not Material 3's `MotionScheme`, not a `MotionDurationScale`.
- **Overlay** (149): windows are created per open (the memory above says
  keeping the panel alive isn't worth it); all motion runs inside the
  window: the panel grows from the docked bubble and collapses back into
  it, one `Animatable` read in `graphicsLayer {}`, seeded with the
  grabber's release velocity; the window is removed after the collapse
  (`removeView`; `removeViewImmediate` only in `stop()`). The bubble's
  edge snap is the one window-attribute animation, an `Animatable` spring
  with a `VelocityTracker`'s velocity; `ValueAnimator` goes.
- **Screens** (150): `SharedTransitionLayout` + `AnimatedContent` keyed on
  the screen, direction-aware; the code is a `sharedElement` with
  `ScaleToBounds` (one layer, never re-laid out), the white card and the
  sale's row `sharedBounds`; `PredictiveBackHandler` feeds a
  `SeekableTransitionState`. The experimental opt-in sits in one file;
  brightness is set once, not ramped by hand.
- **One paid dwell** (151): 5 s for the bubble, chip, panel and sale
  screen; the bubble's fade follows the panel's collapse.
- **Testing** (152): rules on the JVM with production values; frames in
  `app/src/androidTest` Compose UI tests with `mainClock`, on the round's
  emulators; no Robolectric.
- **Performance fixes with owners** (153): the list above, split by track.
- **No BOM bump**: compose-bom 2026.09.00 resolves to animation 1.12.1,
  foundation 1.12.1 (`animateItem`, `SeekableTransitionState`,
  `SharedTransitionScope`), material3 1.4.0 and activity-compose 1.13.0
  (`PredictiveBackHandler`, navigationevent 1.0.0 underneath); everything
  needed is there at minSdk 26.

## Environment notes for the team

- `-read-only` instances of `Pixel_9_Pro` refuse to start while dev1's
  writable instance on 5554 is up ("Another emulator instance is running");
  the lead's phone is a copy of the AVD, `Pixel_9_Pro_motion`, on 5558.
  dev2's 5556 needs the same treatment or the tablet.
- A first `./gradlew` in a fresh worktree can fail in `:sales:compileDebugKotlin`
  with `Unresolved reference 'PaymentCode'`: the receipt module's
  `compile_library_classes_jar` came back from the shared build cache with
  half its classes. `rm -rf android/receipt/build android/sales/build` and
  build again.
- Both dev servers were already running when this started and are shared:
  `apps_dev_server` (from `mokulo-wt-push-settings-ui-relay`'s build) on
  :8737, `fake_square.py` on :8738. Never kill them.
