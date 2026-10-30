# Before round 2: the till on `motion-main` at 65a81c7

Recorded by the lead designer on 2026-10-30 for round 2 of the motion
work (`docs/motion.md` §12), on the Pixel 9 Pro emulator (emulator-5558,
API 36, 1280 × 2856, light theme) against the local Monokulo
(`apps_dev_server` on :8737) and the stand-in Square on :8738, with
`adb shell screenrecord --size 640x1428`, then scaled to 480 px wide
(x264, crf 28) so each stays under 300 KB. The emulator renders with
software GL, so the clips show *what* happens between states, not how
smoothly. Each is the behaviour §12 changes.

| Clip | What it shows | Spec |
| --- | --- | --- |
| `back-with-sale-waiting.mp4` (9 s) | On sale #1655 with #1656 rung up meanwhile. Back at 2 s: #1655's words fade, #1656's code replaces its code in one frame, #1656's words fade in; the shift is never drawn (decision 258). Back again at 5 s: the sale shrinks into its row in the plain list, no tray | §12.1, §12.3 |
| `paid-step-back.mp4` (13 s) | Sale #1657 live; paid at 1 s: the code folds into the paid hero (the tick drawn over the fading code for a few frames, decision 260 b); the 5 s dwell; then the page's words fade and the shift's list fades in with no surface moving (decision 259's crossfade) | §12.2, §12.4 b |
| `bubble-states.mp4` (20 s) | Over the home screen, bubble at the end edge: idle (grey mark at 55 %); a sale arriving at 3 s, the lit bubble with the loading mark's glints and the pulse; paid at 11 s, the green ring closing round the tick on `paidGround`; after the dwell, idle | §12.5 |
| `bubble-states-close.mp4` (20 s) | The same recording, a 176 px crop round the bubble at 2× | §12.5 |

Not recorded: the frames of decision 260 a and c (they are in
`review-r6/phone-back-into-row.webp` and `phone-close-for-now-light.webp`,
and 260 c is a Back that leaves the app, which a clip does not show
better than words); the dark theme (the tray's dark artboard is the
design; the behaviour is the same).
