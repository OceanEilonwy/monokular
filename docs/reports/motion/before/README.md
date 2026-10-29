# Before: the till's transitions at `f274ad7`

Recorded by the lead designer on 2026-10-29 for the motion round
(`docs/motion.md`), on a Pixel 9 Pro emulator (API 36, 1280 × 2856, dark
theme) against a local Monokulo (`apps_dev_server`) and the stand-in
Square, with `adb shell screenrecord`, then scaled to 960 px tall at
about 170 kbit/s so each stays under a megabyte. The emulator renders
with software GL under a loaded host, so the clips show *what* changes
between states, not how smoothly; frame timings come from a device
(`docs/motion.md` §8).

| Clip | What it shows | Spec items |
| --- | --- | --- |
| `before-app-sale.mp4` (56 s) | Start Monokular from the Ready step; the shift with its rows; a row opening its sale (a cut); the tinted X and the close sheet (Material's own slide); Back; a sale paid from the shift (the row's badge and words change in one frame, the summary with them) | M7, M10, M11 |
| `before-code-paid.mp4` (46 s) | A live sale; tapping the code: a cut to the white enlarged screen (from the dark ground, the harshest cut in the app); tap to shrink; the sale paid while on screen: the live sale turns into its Paid page in one frame and steps back to the shift after five seconds, another cut | M8, M10, M9 |
| `before-overlay.mp4` (75 s) | Over the home screen: the lit bubble with its pulse; a tap puts the panel up in one frame with the bubble re-appearing docked at its top; a short drag on the grabber springs back; a fling lowers it (the panel vanishes, the free bubble reappears at its edge); the bubble tapped again | M1, M2, M4 |
| `before-overlay2.mp4` (60 s) | The panel's order line opens The order, full screen, in one frame; Back to the code; the sale paid: the panel's code becomes the paid tick in one frame and the docked bubble turns green; after the dwell the panel moves on to the next waiting sale's code, again in one frame; the bubble goes back to lit a second before that | M5, M9 |
| `before-bubble.mp4` (40 s) | The docked bubble tapped to lower the panel; the free bubble dragged across the screen and let go: it slides to the near edge on a timer (no velocity); dragged back | M3, M4 |
| `before-tablet-panel.webp` | The floating panel on the Pixel Tablet emulator over the home screen, the free bubble at the end edge behind it | M1 (tablet), decision 90's finding |
| `before-phone-landscape-shift.webp` | The shift on a phone on its side | M7, M11 |

Not recorded: split screen (the configuration-change path is read, not
run: a panel of the other kind is swapped without animation, decision
89, and the spec keeps that a snap); onboarding's steps (cuts, like
every screen change; M7 gives them a slide); the enlarged code's
brightness, which a screen recording can't show.
