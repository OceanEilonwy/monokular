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
| The same over Square's white, where its shadow must go with it (decision 191) | `phone-lower-light-q1..3.png` |
| The code folding into the paid tick | `phone-paid-fold-q*.png` |
| The next sale's code growing out of the tick, opaque from its first frame (decision 192) | `phone-next-sale-q*.png` |
| A tablet's code coming back from the order: whole under the order fading off it | `tablet-back-to-code-q*.png` |
| A phone's code coming back from the order under Still (Remove animations) | `phone-still-back-to-code-q*.png` |
| The order growing out of the card to the window | `phone-order-grow-q*.png` |
| The order line arriving a second after its sale: room opening, words fading (decision 193) | `phone-late-order-q*.png` |

The screens track's moves (RowGrow, CodeGrow, OrderGrow, Rise) are theirs
to add beside these.

## The app's screen moves (round 3)

Written by `MotionFramesTest` on the test clock and converted to webp at
half resolution: each move seeked to 25, 50 and 75 % of the stage's
progress (a hair short of each point first, as a running move's frame
before it is: decision 196); the paid fold at 60 to 360 ms after the sale
is paid. Since round 3 a moving surface is an empty ground and its words
fade through at their own size (decision 195). At 50 % of a grow neither
screen's words are drawn, only the moving ground and the code. Regenerate
with `./gradlew :app:connectedDebugAndroidTest
-Pandroid.testInstrumentationRunnerArguments.class=app.monokular.MotionFramesTest`;
the PNGs land in `app/build/outputs/connected_android_test_additional_output/`.

| Move | Files |
| --- | --- |
| A row growing into its sale, and back | `row-grow-*.webp`, `row-shrink-*.webp` |
| A sale coming up by itself (a slide, decision 198) | `rise-*.webp` |
| The code enlarging, and shrinking | `code-grow-*.webp`, `code-shrink-*.webp` |
| The order card growing into the order page, and back | `order-grow-*.webp`, `order-shrink-*.webp` |
| A sale paid on screen closing its code into the hero | `paid-fold-*.webp` |
