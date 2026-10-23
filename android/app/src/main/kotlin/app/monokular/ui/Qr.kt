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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/**
 * A payment code on its white card (the design's QR part): ink on white
 * whatever the theme, the card's padding its quiet zone.
 */
@Composable
fun QrCode(text: String, description: String, modifier: Modifier = Modifier, padding: Dp = 20.dp, radius: Dp = 36.dp) {
    val bitmap = remember(text) { qrBitmap(text).asImageBitmap() }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(radius))
            .background(Color.White)
            .padding(padding)
            .semantics { contentDescription = description },
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
        )
    }
}
