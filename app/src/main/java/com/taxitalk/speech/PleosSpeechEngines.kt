package com.taxitalk.speech

import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import com.taxitalk.model.Lang
import com.taxitalk.model.Zone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ai.pleos.playground.stt.SpeechToText as PleosStt
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener

/**
 * Gleo AI(Pleos) TTS. Pleos 차량/에뮬레이터에서는 표준 TextToSpeech 엔진이 없으므로 이 엔진이 실제 출력을 담당한다.
 * [Zone] → setAudioZoneId: DRIVER_ZONE=0(앞), REAR_ZONE=1(뒷좌석 존) — SSZ 좌석별 출력.
 */
class PleosTtsEngine(context: Context) : TtsEngine {
    private val tts = PleosTts(context.applicationContext, TtsMode.ON_DEVICE)
    private val ready = CompletableDeferred<Boolean>()
    @Volatile private var initOk = false
    @Volatile private var current: CompletableDeferred<Boolean>? = null

    init {
        runCatching {
            tts.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
                override fun onSuccess() { Log.i(TAG, "Pleos TTS init OK"); initOk = true; ready.complete(true) }
                override fun onFailure(p0: TtsError) { Log.w(TAG, "Pleos TTS init failed: $p0"); ready.complete(false) }
            })
            tts.addEventListener(object : EventListener {
                override fun onStart() { Log.i(TAG, "Pleos TTS onStart") }
                override fun onReady() {}
                override fun onDone() { Log.i(TAG, "Pleos TTS onDone"); current?.complete(true) }
                override fun onError(p0: TtsError) { Log.w(TAG, "Pleos TTS onError $p0"); current?.complete(false) }
                override fun onStop() { current?.complete(false) }
                override fun onUpdatedRms(p0: Double) {}
            })
        }.onFailure { Log.w(TAG, "Pleos TTS unavailable", it); ready.complete(false) }
    }

    /** 초기화 결과 대기 (폴백 판단용). */
    suspend fun awaitReady(timeoutMs: Long = 4_000): Boolean =
        runCatching { withTimeout(timeoutMs) { ready.await() } }.getOrDefault(false)

    override val isAvailable get() = initOk

    override suspend fun speak(text: String, lang: Lang, zone: Zone): Boolean = withContext(Dispatchers.Main) {
        if (!awaitReady()) return@withContext false
        val done = CompletableDeferred<Boolean>()
        current?.complete(false)
        current = done
        runCatching {
            tts.setLocale(lang.locale)
            tts.setAudioZoneId(if (zone == Zone.REAR_ZONE) REAR_ZONE_ID else FRONT_ZONE_ID)
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (zone == Zone.REAR_ZONE) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            Log.i(TAG, "Pleos TTS speak lang=${lang.code} zone=$zone \"$text\"")
            tts.speak(text, true)
        }.onFailure { Log.w(TAG, "Pleos TTS speak failed", it); return@withContext false }
        try { withTimeout(30_000) { done.await() } } catch (e: TimeoutCancellationException) { false }
    }

    override fun stop() { runCatching { tts.stop() } }
    override fun release() { runCatching { tts.release() } }

    companion object {
        private const val TAG = "TaxiTalkTts"
        const val FRONT_ZONE_ID = 0
        const val REAR_ZONE_ID = 1
    }
}

/** Gleo AI(Pleos) STT — 온디바이스 인식. */
class PleosSttEngine(context: Context) : SttEngine {
    private val stt = PleosStt(context.applicationContext, SttMode.ON_DEVICE)
    private val ready = CompletableDeferred<Boolean>()
    @Volatile private var initOk = false
    @Volatile private var session: Session? = null
    @Volatile private var maxRms = 0.0
    /** 취소한 세션의 늦은 ended 콜백이 새 세션을 실패시키지 않도록 무시 */
    @Volatile private var ignoreEnded = false

    private class Session(val onPartial: (String) -> Unit, val onLevel: (Float) -> Unit) {
        val result = CompletableDeferred<Result<String>>()
        @Volatile var lastPartial: String = ""
    }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        runCatching {
            stt.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                override fun onSuccess() { Log.i(TAG, "Pleos STT init OK"); initOk = true; ready.complete(true) }
                override fun onFailure(p0: SttError) { Log.w(TAG, "Pleos STT init failed: $p0"); ready.complete(false) }
            })
            stt.addListener(object : ResultListener {
                override fun onUpdated(p0: String, p1: Boolean) {
                    Log.i(TAG, "Pleos STT ${if (p1) "final" else "partial"}: $p0")
                    val s = session ?: return
                    if (p1) {
                        s.result.complete(if (p0.isBlank()) Result.failure(SttException(-2, "empty")) else Result.success(p0))
                    } else { s.lastPartial = p0; s.onPartial(p0) }
                }
                override fun onUpdatedEpdData(p0: Long, p1: Long) {}
                override fun onStartedRecognition() { Log.i(TAG, "Pleos STT started"); ignoreEnded = false }
                override fun onEndedRecognition() {
                    Log.i(TAG, "Pleos STT ended")
                    if (ignoreEnded) { ignoreEnded = false; return }
                    // SDK는 ended 이후에 final 결과를 보내므로 잠시 기다렸다가, 없으면 마지막 중간 결과를 사용
                    val s = session ?: return
                    handler.postDelayed({
                        val text = s.lastPartial
                        s.result.complete(
                            if (text.isNotBlank()) Result.success(text)
                            else Result.failure(SttException(-3, "ended without result"))
                        )
                    }, FINAL_GRACE_MS)
                }
                override fun onError(p0: SttError) {
                    Log.w(TAG, "Pleos STT error $p0")
                    session?.result?.complete(Result.failure(SttException(p0.ordinal, p0.name)))
                }
                override fun onReady() { Log.i(TAG, "Pleos STT ready") }
                override fun onUpdatedRms(p0: Double) {
                    if (p0 > maxRms) maxRms = p0
                    // 0~약 1500 범위 → 0..1 (로그 스케일)
                    session?.onLevel?.invoke((kotlin.math.ln(1.0 + p0) / kotlin.math.ln(1500.0)).toFloat().coerceIn(0f, 1f))
                }
            })
        }.onFailure { Log.w(TAG, "Pleos STT unavailable", it); ready.complete(false) }
    }

    suspend fun awaitReady(timeoutMs: Long = 4_000): Boolean =
        runCatching { withTimeout(timeoutMs) { ready.await() } }.getOrDefault(false)

    override val isAvailable get() = initOk

    override suspend fun listenOnce(lang: Lang, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): Result<String> = withContext(Dispatchers.Main) {
        if (!awaitReady()) return@withContext Result.failure(SttException(-1, "Pleos STT not ready"))
        val s = Session(onPartial, onLevel).also { session = it }
        maxRms = 0.0
        runCatching {
            stt.setLocale(lang.locale)
            stt.request()
        }.onFailure { session = null; return@withContext Result.failure(it) }
        val r = try { withTimeout(LISTEN_TIMEOUT_MS) { s.result.await() } }
        catch (e: TimeoutCancellationException) {
            runCatching { stt.stop() }
            if (s.lastPartial.isNotBlank()) Result.success(s.lastPartial) else Result.failure(SttException(-4, "timeout"))
        }
        if (session === s) session = null
        Log.i(TAG, "Pleos STT session end: maxRms=$maxRms result=$r")
        r
    }

    override fun cancel() {
        if (session != null) ignoreEnded = true
        session?.result?.complete(Result.failure(SttException(-5, "cancelled")))
        runCatching { stt.stop() }
    }

    override fun release() { cancel(); runCatching { stt.release() } }

    companion object {
        private const val TAG = "TaxiTalkStt"
        private const val LISTEN_TIMEOUT_MS = 15_000L
        private const val FINAL_GRACE_MS = 1_500L
    }
}

/** Pleos 엔진 우선, 초기화 실패 시 표준 Android 엔진으로 폴백 (NFR-4.1). */
class PreferredTtsEngine(private val pleos: PleosTtsEngine, private val android: TtsEngine) : TtsEngine {
    override val isAvailable get() = pleos.isAvailable || android.isAvailable
    override suspend fun speak(text: String, lang: Lang, zone: Zone) =
        if (pleos.awaitReady()) pleos.speak(text, lang, zone) else android.speak(text, lang, zone)
    override fun stop() { pleos.stop(); android.stop() }
    override fun release() { pleos.release(); android.release() }
}

class PreferredSttEngine(private val pleos: PleosSttEngine, private val android: SttEngine) : SttEngine {
    override val isAvailable get() = pleos.isAvailable || android.isAvailable
    override suspend fun listenOnce(lang: Lang, onPartial: (String) -> Unit, onLevel: (Float) -> Unit) =
        if (pleos.awaitReady()) pleos.listenOnce(lang, onPartial, onLevel) else android.listenOnce(lang, onPartial, onLevel)
    override fun cancel() { pleos.cancel(); android.cancel() }
    override fun release() { pleos.release(); android.release() }
}
