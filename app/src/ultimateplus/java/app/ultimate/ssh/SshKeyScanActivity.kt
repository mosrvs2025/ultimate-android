package app.ultimate.ssh

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Decode an OpenSSH key QR image locally. Never opens URLs or sends keys off-device. */
class SshKeyScanActivity : ComponentActivity() {
    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) { finish(); return@registerForActivityResult }
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                    val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        val scale = maxOf(info.size.width, info.size.height) / 2048.0
                        if (scale > 1) decoder.setTargetSize((info.size.width / scale).toInt(), (info.size.height / scale).toInt())
                    }
                    try {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
                            mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)).text
                        require(ApprovedKeys.parse(decoded).isNotEmpty()) { "QR does not contain a public key" }
                        decoded
                    } finally { bitmap.recycle() }
                }.getOrNull()
            }
            if (text != null) setResult(Activity.RESULT_OK, Intent().putExtra("SCAN_RESULT", text))
            else android.widget.Toast.makeText(this@SshKeyScanActivity, "No supported public-key QR found in that image", android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); if (savedInstanceState == null) pick.launch("image/*") }
}
