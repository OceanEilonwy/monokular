# Seeked frames of the overlay's moves

Written by `OverlayFramesTest` (`connectedDebugAndroidTest`) on the
test clock: each move stopped at a quarter, a half and three quarters of
`motion.surface`'s settling time (q1–q3) and at rest (q4), at a quarter of
the phone's resolution, on a red backdrop that stands for whatever app is
behind the overlay. The test also holds the payment code square on every
seeked frame. Regenerate with:

    ./gradlew :app:connectedDebugAndroidTest \
      -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
      -Pandroid.testInstrumentationRunnerArguments.class=app.monokular.OverlayFramesTest
    adb pull /sdcard/Android/data/app.monokular.debug/files/frames docs/reports/motion/frames

| Move | Files |
| --- | --- |
| The panel growing out of the bubble | `phone-raise-q*.png` |
| The panel collapsing into it | `phone-lower-q*.png` |
| The same over Square's white, where its shadow must go with it (decision 134) | `phone-lower-light-q1..3.png` |
| The code folding into the paid tick | `phone-paid-fold-q*.png` |
| The next sale's code growing out of the tick | `phone-next-sale-q*.png` |
| The order growing out of the card to the window | `phone-order-grow-q*.png` |

The screens track's moves (RowGrow, CodeGrow, OrderGrow, Rise) are theirs
to add beside these.
