package app.ultimate.vision

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.ultimate.air.BodyPose
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The camera with whole-body tracking (MediaPipe Pose, on the phone) and a colour look over the picture. The preview is
 * drawn through a texture so the look can be applied to it; frames are analysed and dropped, never saved. [body] is null
 * while nobody is seen, and stays null if the pose model isn't in this build.
 */
@Composable
internal fun BodyCamera(front: Boolean, filter: FloatArray, body: MutableStateFlow<BodyPose?>) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val pose = remember {
        runCatching {
            PoseLandmarker.createFromOptions(context, PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                .setRunningMode(RunningMode.LIVE_STREAM).setNumPoses(1)
                .setResultListener { result, _ ->
                    val lm = result.landmarks().firstOrNull()
                    body.value = if (lm == null || lm.size < 33) null else BodyPose(lm.map { Triple(it.x(), it.y(), it.visibility().orElse(1f)) }, System.currentTimeMillis())
                }.setErrorListener { body.value = null }.build())
        }.getOrNull()
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown(); runCatching { pose?.close() }; body.value = null } }
    AndroidView({ ctx ->
        FrameLayout(ctx).apply {
            val view = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
            addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                analysis.setAnalyzer(executor) { proxy ->
                    runCatching {
                        val rot = proxy.imageInfo.rotationDegrees
                        val bmp = proxy.toBitmap().let { b -> if (rot == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true) }
                        if (CameraLight.tick++ % 15 == 0) CameraLight.check(bmp)
                        pose?.detectAsync(BitmapImageBuilder(bmp).build(), System.currentTimeMillis())
                    }
                    proxy.close()
                }
                runCatching { provider.unbindAll(); provider.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
            }, ContextCompat.getMainExecutor(ctx))
        }
    }, Modifier.fillMaxSize(), update = { frame ->
        // The whole preview is drawn into one layer, and the look is applied as that layer is put on screen.
        frame.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(filter) })
    })
}

/** True when this build carries the body-tracking model. */
internal fun hasPoseModel(c: android.content.Context) = runCatching { c.assets.open("pose_landmarker_lite.task").close(); true }.getOrDefault(false)
