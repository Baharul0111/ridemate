package app.ridemate.trip

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import app.ridemate.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** The voice languages RideMate offers when the phone's speech recognition supports them (goal 1). */
enum class VoiceLanguage(val tag: String, @get:StringRes val labelRes: Int) {
    ENGLISH("en-IN", R.string.trip_voice_lang_en),
    HINDI("hi-IN", R.string.trip_voice_lang_hi),
    BENGALI("bn-IN", R.string.trip_voice_lang_bn),
    ASSAMESE("as-IN", R.string.trip_voice_lang_as),
    ;

    companion object {
        /** Hindi for a Hindi UI, else English. */
        fun defaultFor(locale: Locale): VoiceLanguage = when (locale.language) {
            "hi" -> HINDI
            "bn" -> BENGALI
            "as" -> ASSAMESE
            else -> ENGLISH
        }

        /**
         * Which languages the recognizer's reported tags cover: the exact tag (`hi-IN`) or the bare language
         * (`hi`). Null when nothing usable was reported (unknown: offer all and fall back on error).
         */
        fun supportedAmong(tags: Collection<String>): Set<VoiceLanguage>? {
            val normalized = tags.map { it.replace('_', '-').lowercase(Locale.ROOT) }.toSet()
            if (normalized.isEmpty()) return null
            return entries.filter { lang ->
                val tag = lang.tag.lowercase(Locale.ROOT)
                tag in normalized || tag.substringBefore('-') in normalized
            }.toSet()
        }
    }
}

enum class SpeechError { NO_MATCH, LANGUAGE_UNSUPPORTED, NO_PERMISSION, NETWORK, BUSY, UNAVAILABLE, OTHER }

/** What the recognizer reports while listening. The flow ends after [Final] or [Failed]. */
sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    data class Failed(val error: SpeechError) : SpeechEvent
}

/** Voice typing for the destination: Android's own SpeechRecognizer (free, on the phone or Google's service). */
interface SpeechInput {
    fun isAvailable(): Boolean

    /** The supported subset of [VoiceLanguage], or null when the phone can't say (offer all; errors fall back). */
    suspend fun supportedLanguages(): Set<VoiceLanguage>?

    /** Listens once in [language]. Collect on the main thread; cancelling stops the recognizer. */
    fun listen(language: VoiceLanguage): Flow<SpeechEvent>
}

/** [SpeechInput] over [SpeechRecognizer] (needs RECORD_AUDIO, asked by the trip screen first). */
class AndroidSpeechInput(private val context: Context) : SpeechInput {

    override fun isAvailable(): Boolean = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    override suspend fun supportedLanguages(): Set<VoiceLanguage>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isAvailable()) return null
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(SUPPORT_TIMEOUT_MS) { querySupport() }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun querySupport(): Set<VoiceLanguage>? {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        return try {
            suspendCancellableCoroutine { cont ->
                val callback = object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val tags = support.installedOnDeviceLanguages + support.pendingOnDeviceLanguages +
                            support.supportedOnDeviceLanguages + support.onlineLanguages
                        if (cont.isActive) cont.resume(VoiceLanguage.supportedAmong(tags))
                    }

                    override fun onError(error: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                try {
                    recognizer.checkRecognitionSupport(recognizerIntent(null), context.mainExecutor, callback)
                } catch (e: RuntimeException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        } finally {
            recognizer.destroy()
        }
    }

    override fun listen(language: VoiceLanguage): Flow<SpeechEvent> = callbackFlow {
        if (!isAvailable()) {
            trySend(SpeechEvent.Failed(SpeechError.UNAVAILABLE))
            close()
            awaitClose { }
            return@callbackFlow
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechEvent.Ready)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let { trySend(SpeechEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = firstResult(results)
                trySend(if (text != null) SpeechEvent.Final(text) else SpeechEvent.Failed(SpeechError.NO_MATCH))
                close()
            }

            override fun onError(error: Int) {
                trySend(SpeechEvent.Failed(errorOf(error)))
                close()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        try {
            recognizer.startListening(recognizerIntent(language))
        } catch (e: RuntimeException) {
            trySend(SpeechEvent.Failed(SpeechError.OTHER))
            close()
        }
        awaitClose {
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }.flowOn(Dispatchers.Main)

    private fun recognizerIntent(language: VoiceLanguage?): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .apply {
                if (language != null) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.tag)
                }
            }

    companion object {
        private const val SUPPORT_TIMEOUT_MS: Long = 2_000

        fun firstResult(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }?.trim()

        /** Android's error codes, including the API 31+ language codes (12, 13) by value. */
        fun errorOf(code: Int): SpeechError = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NO_MATCH
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.NO_PERMISSION
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
            ERROR_SERVER_DISCONNECTED,
            -> SpeechError.NETWORK
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS -> SpeechError.BUSY
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> SpeechError.LANGUAGE_UNSUPPORTED
            else -> SpeechError.OTHER
        }

        private const val ERROR_TOO_MANY_REQUESTS = 10
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
