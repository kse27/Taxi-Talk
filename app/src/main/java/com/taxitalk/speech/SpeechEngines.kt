package com.taxitalk.speech

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.taxitalk.model.Lang
import com.taxitalk.model.Zone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 음성 인식(STT) 추상화 — 엔진 교체 가능 (NFR-5.1). */
interface SttEngine {
    val isAvailable: Boolean
    /** 한 번의 발화를 인식. 실패 시 Result.failure. [onPartial]은 중간 결과. */
    suspend fun listenOnce(lang: Lang, onPartial: (String) -> Unit = {}, onLevel: (Float) -> Unit = {}): Result<String>
    fun cancel()
    fun release()
}

/** 음성 합성(TTS) 추상화. [zone]은 좌석 채널. */
interface TtsEngine {
    val isAvailable: Boolean
    suspend fun speak(text: String, lang: Lang, zone: Zone): Boolean
    fun stop()
    fun release()
}

class SttException(val code: Int, message: String) : Exception(message)

/** android.speech 기반 STT. 기기에 인식 서비스가 없으면 isAvailable=false → 텍스트 입력 폴백. */
class AndroidSttEngine(private val context: Context) : SttEngine {
    private var recognizer: SpeechRecognizer? = null

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    override suspend fun listenOnce(lang: Lang, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): Result<String> =
        withContext(Dispatchers.Main) {
            if (!isAvailable) return@withContext Result.failure(SttException(-1, "Speech recognition unavailable"))
            cancel()
            val result = CompletableDeferred<Result<String>>()
            val sr = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { Log.i(TAG, "STT ready (${lang.sttTag})") }
                override fun onBeginningOfSpeech() { Log.i(TAG, "STT speech begin") }
                override fun onRmsChanged(rmsdB: Float) { onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { Log.i(TAG, "STT speech end") }
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let(onPartial)
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    Log.i(TAG, "STT result: $text")
                    result.complete(
                        if (text.isNullOrBlank()) Result.failure(SttException(SpeechRecognizer.ERROR_NO_MATCH, "No match"))
                        else Result.success(text)
                    )
                }
                override fun onError(error: Int) {
                    Log.w(TAG, "STT error $error")
                    result.complete(Result.failure(SttException(error, "STT error $error")))
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.sttTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            sr.startListening(intent)
            val r = result.await()
            sr.destroy()
            if (recognizer === sr) recognizer = null
            r
        }

    override fun cancel() {
        recognizer?.let { it.cancel(); it.destroy() }
        recognizer = null
    }

    override fun release() = cancel()

    companion object { private const val TAG = "TaxiTalkStt" }
}

/**
 * android TextToSpeech 기반 TTS.
 * SSZ(좌석별 음장) 하드웨어 제어는 범위 외이므로, 채널 분리는 소프트웨어로 모사한다:
 *  - REAR_ZONE: 승객 안내용 USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, 정상 볼륨
 *  - DRIVER_ZONE: USAGE_ASSISTANT, 운전 집중을 위해 약간 낮은 볼륨
 * 실제 차량에서는 이 지점을 CarAudioManager 오디오 존 라우팅으로 교체한다.
 */
class AndroidTtsEngine(context: Context) : TtsEngine {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val seq = AtomicInteger()
    @Volatile private var initOk = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        initOk = status == TextToSpeech.SUCCESS
        Log.i(TAG, "TTS init status=$status engine=${runCatching { ttsEngineName() }.getOrNull()}")
        ready.complete(initOk)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { Log.i(TAG, "TTS onStart $utteranceId") }
            override fun onDone(utteranceId: String?) { Log.i(TAG, "TTS onDone $utteranceId"); utteranceId?.let { pending.remove(it)?.complete(true) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(false) } }
            override fun onError(utteranceId: String?, errorCode: Int) { Log.w(TAG, "TTS onError $utteranceId code=$errorCode"); utteranceId?.let { pending.remove(it)?.complete(false) } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { pending.remove(it)?.complete(false) } }
        })
    }

    override val isAvailable: Boolean get() = initOk

    override suspend fun speak(text: String, lang: Lang, zone: Zone): Boolean {
        if (!ready.await()) return false
        val langResult = tts.setLanguage(lang.locale)
        Log.i(TAG, "TTS setLanguage ${lang.locale} -> $langResult (zone=$zone)")
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "TTS language not supported: ${lang.code}")
            return false
        }
        val usage = if (zone == Zone.REAR_ZONE) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        else AudioAttributes.USAGE_ASSISTANT
        tts.setAudioAttributes(
            AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        val id = "tt-${seq.incrementAndGet()}"
        val done = CompletableDeferred<Boolean>().also { pending[id] = it }
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, if (zone == Zone.DRIVER_ZONE) 0.85f else 1.0f)
        }
        // 중요한 안내/통역은 이전 출력을 끊고 즉시 재생 (FR-4.3)
        val rc = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        Log.i(TAG, "TTS speak id=$id rc=$rc text=\"$text\"")
        if (rc != TextToSpeech.SUCCESS) {
            pending.remove(id); return false
        }
        return done.await()
    }

    override fun stop() { tts.stop() }

    override fun release() { tts.stop(); tts.shutdown() }

    private fun ttsEngineName(): String = tts.defaultEngine

    companion object { private const val TAG = "TaxiTalkTts" }
}
