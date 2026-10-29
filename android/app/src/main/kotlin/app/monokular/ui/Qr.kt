package app.monokular.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.monokular.Move
import app.monokular.sharedSurface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * [text] as a QR code, error correction M, [scale] pixels a module with a
 * white border of [margin] modules. `QR_COMPACT` lets ZXing switch modes
 * inside the code, so a receipt's digits pack in numeric mode.
 */
fun qrBitmap(text: String, scale: Int = 1, margin: Int = 0): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 0,
        EncodeHintType.QR_COMPACT to true,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val side = (matrix.width + 2 * margin) * scale
    val pixels = IntArray(side * side) { i ->
        val x = (i % side) / scale - margin
        val y = (i / side) / scale - margin
        val on = x in 0 until matrix.width && y in 0 until matrix.height && matrix.get(x, y)
        if (on) 0xFF1A1917.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
}

/** How many codes [codeBitmap] keeps: the sale on screen, its panel and a couple that just ended. */
private const val KEEP = 4

/** The last [KEEP] codes drawn, by text, most recently used last. Read on the main thread, as composition is. */
private val codes = object : LinkedHashMap<String, ImageBitmap>(KEEP, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean = size > KEEP
}

/**
 * [text] as a code to draw: the same bitmap for the same text while it's
 * one of the last [KEEP] asked for, so the sale screen, the enlarged code,
 * the panel and a shared element between them draw one image instead of
 * encoding it again (ZXing version 25 with a receipt in it) on every
 * screen change.
 */
fun codeBitmap(text: String): ImageBitmap = codes.getOrPut(text) { qrBitmap(text).asImageBitmap() }

/**
 * A payment code on its white card (the design's QR part): ink on white
 * whatever the theme, the card's padding its quiet zone. With [shared]
 * (the sale's order id) in the app, the code is one shared element
 * between the sale and the enlarged code (M8): the image moves and scales
 * as one layer, never re-laid out, and drawn whole on every frame; with
 * [sharedCard] the white card is the surface that grows into the enlarged
 * code's white screen.
 */
@Composable
fun QrCode(
    text: String,
    description: String,
    modifier: Modifier = Modifier,
    padding: Dp = 20.dp,
    radius: Dp = 36.dp,
    shared: String? = null,
    sharedCard: Boolean = true,
) {
    val bitmap = remember(text) { codeBitmap(text) }
    val shape = remember(radius) { RoundedCornerShape(radius) }
    CodeCard(modifier.aspectRatio(1f), shape, shared?.takeIf { sharedCard }) {
        Box(Modifier.padding(padding).semantics { contentDescription = description }) { CodeImage(bitmap, shared) }
    }
}

/**
 * The white card, a shared surface when [shared]. Its own restartable
 * scope, so a transition starting or landing re-runs this and not
 * [QrCode].
 */
@Composable
private fun CodeCard(modifier: Modifier, shape: Shape, shared: String?, content: @Composable () -> Unit) = Box(
    modifier
        .then(if (shared != null) Modifier.sharedSurface("code-card/$shared", Move.CodeGrow, shape) else Modifier)
        .clip(shape)
        .background(Fixed.White),
) { content() }

/** The code's one bitmap, scaled to fit with no smoothing; one shared layer when [shared]. */
@Composable
private fun CodeImage(bitmap: ImageBitmap, shared: String?) = Image(
    bitmap = bitmap,
    contentDescription = null,
    modifier = Modifier.fillMaxSize()
        .then(if (shared != null) Modifier.sharedSurface("code/$shared", Move.CodeGrow, fade = false) else Modifier),
    contentScale = ContentScale.Fit,
    filterQuality = FilterQuality.None,
)
