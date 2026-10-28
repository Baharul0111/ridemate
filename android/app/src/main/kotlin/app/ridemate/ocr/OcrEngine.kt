package app.ridemate.ocr

import android.graphics.Rect
import app.ridemate.capture.FrameImage
import app.ridemate.core.ocr.Box
import app.ridemate.core.ocr.OcrFrame
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Channel B's eyes (§4.2 OcrAndMask): tokens and pixel boxes of one frame, as core's ML Kit-independent [OcrFrame].
 * Implementations never store or upload the image.
 */
interface OcrEngine {
    /** Recognises [frame]; throws [OcrFailedException] when no recogniser produced a result. */
    suspend fun recognize(frame: FrameImage): OcrFrame
}

/** Every recogniser failed on a frame (no text came back at all, as opposed to an empty screen). */
class OcrFailedException(cause: Throwable? = null) : Exception("ocr failed", cause)

/** Which ML Kit model read a token. */
enum class OcrScript { LATIN, DEVANAGARI }

/** One ML Kit element: its text, pixel box, and the engine's own line and block indices. */
data class RecognizedElement(val text: String, val box: Box, val line: Int, val block: Int)

/**
 * ML Kit Text Recognition v2 with the bundled Latin and Devanagari models (both run on every frame, on the phone;
 * goal 26 asks for Hindi digits and words). The two results are merged by [TokenMerge].
 *
 * ML Kit tasks keep running after a deadline cancels the caller, so each task holds a [FrameImage.retain]ed
 * reference until it completes; the bitmap is never recycled under it.
 */
class MlKitOcrEngine(
    private val latin: Lazy<TextRecognizer> = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) },
    private val devanagari: Lazy<TextRecognizer> = lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    },
) : OcrEngine, AutoCloseable {

    override suspend fun recognize(frame: FrameImage): OcrFrame = coroutineScope {
        val latinRead = async { runCatching { process(latin.value, frame) } }
        val devanagariRead = async { runCatching { process(devanagari.value, frame) } }
        val l = latinRead.await()
        val d = devanagariRead.await()
        if (l.isFailure && d.isFailure) throw OcrFailedException(l.exceptionOrNull())
        TokenMerge.merge(
            width = frame.width,
            height = frame.height,
            latin = l.getOrNull()?.let(::elementsOf).orEmpty(),
            devanagari = d.getOrNull()?.let(::elementsOf).orEmpty(),
        )
    }

    private suspend fun process(recognizer: TextRecognizer, frame: FrameImage): Text {
        if (!frame.retain()) throw OcrFailedException()
        val task: Task<Text> = try {
            frame.read { bitmap -> recognizer.process(InputImage.fromBitmap(bitmap, 0)) }
        } catch (e: Exception) {
            frame.release()
            throw OcrFailedException(e)
        }
        task.addOnCompleteListener { frame.release() }
        return suspendCancellableCoroutine { cont ->
            task.addOnSuccessListener { cont.resume(it) }
            task.addOnFailureListener { cont.resumeWithException(OcrFailedException(it)) }
            task.addOnCanceledListener { cont.cancel() }
        }
    }

    override fun close() {
        if (latin.isInitialized()) latin.value.close()
        if (devanagari.isInitialized()) devanagari.value.close()
    }

    companion object {
        /** Flattens ML Kit's blocks → lines → elements; elements without a box or text are skipped. */
        fun elementsOf(text: Text): List<RecognizedElement> {
            val out = mutableListOf<RecognizedElement>()
            var lineIndex = 0
            text.textBlocks.forEachIndexed { blockIndex, block ->
                for (line in block.lines) {
                    for (element in line.elements) {
                        val rect: Rect = element.boundingBox ?: continue
                        if (element.text.isBlank() || rect.isEmpty) continue
                        out += RecognizedElement(element.text, Box(rect.left, rect.top, rect.right, rect.bottom), lineIndex, blockIndex)
                    }
                    lineIndex++
                }
            }
            return out
        }
    }
}
