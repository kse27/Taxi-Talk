package com.taxitalk.speech

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.taxitalk.cloud.GoogleCloud
import com.taxitalk.model.Lang
import com.taxitalk.model.Zone
import com.taxitalk.translate.PlaceGlossary
import com.taxitalk.translate.ScriptHeuristic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

private val Lang.cloudVoiceTag get() = if (this == Lang.ZH) "cmn-CN" else sttTag

/**
 * Google Cloud Speech-to-Text.
 * 직접 마이크(AudioRecord 16kHz)를 녹음하고 무음 감지(VAD)로 발화 끝을 판단한 뒤 한 번에 인식 요청한다.
 * 목적지 지명 사전을 speechContexts 힌트로 넘겨 "Seoul Station" 같은 고유명사 인식률을 높인다.
 */
class CloudSttEngine(private val cloud: GoogleCloud) : SttEngine {
    @Volatile private var cancelled = false

    override val isAvailable get() = cloud.isUsable

    @SuppressLint("MissingPermission") // 권한은 UI 에서 요청 후 진입
    override suspend fun listenOnce(lang: Lang, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): Result<String> =
        listenAny(listOf(lang), onLevel).map { it.first }

    /**
     * 후보 언어 중 무엇을 말했는지까지 함께 인식 (첫 항목이 기본 언어, 최대 4개).
     * 자리별 마이크를 앱에서 구분할 수 없어, 언어로 기사님/손님을 구분할 때 쓴다.
     */
    suspend fun listenAny(langs: List<Lang>, onLevel: (Float) -> Unit = {}): Result<Pair<String, Lang>> =
        withContext(Dispatchers.IO) {
            cancelled = false
            val pcm = try { record(onLevel) } catch (e: SttException) { return@withContext Result.failure(e) }
            onLevel(0f)
            if (cancelled) return@withContext Result.failure(SttException(-5, "cancelled"))
            runCatching { recognize(pcm, langs) }.fold(
                onSuccess = { r ->
                    if (r.first.isBlank()) Result.failure(SttException(-2, "empty")) else Result.success(r)
                },
                onFailure = { Log.w(TAG, "cloud recognize failed", it); Result.failure(SttException(-6, it.message ?: "cloud error")) },
            )
        }

    @SuppressLint("MissingPermission")
    private suspend fun record(onLevel: (Float) -> Unit): ByteArray {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC).firstNotNullOfOrNull { src ->
            runCatching {
                AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, FRAME * 4))
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            }.getOrNull()
        } ?: throw SttException(-1, "AudioRecord unavailable")

        val out = ByteArrayOutputStream()
        val frame = ShortArray(FRAME)
        val bytes = ByteBuffer.allocate(FRAME * 2).order(ByteOrder.LITTLE_ENDIAN)
        var noise = 0.0; var noiseFrames = 0
        var speechStarted = false; var silentMs = 0; var elapsedMs = 0; var maxRms = 0.0
        try {
            rec.startRecording()
            while (kotlin.coroutines.coroutineContext.isActive && !cancelled) {
                val n = rec.read(frame, 0, FRAME)
                if (n <= 0) break
                bytes.clear(); for (i in 0 until n) bytes.putShort(frame[i])
                out.write(bytes.array(), 0, n * 2)
                elapsedMs += n * 1000 / SAMPLE_RATE

                var sum = 0.0; for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
                val rms = sqrt(sum / n)
                if (rms > maxRms) maxRms = rms
                onLevel((ln(1.0 + rms) / ln(6000.0)).toFloat().coerceIn(0f, 1f))

                if (noiseFrames < NOISE_FRAMES) { noise += rms; noiseFrames++; continue }
                val threshold = max(noise / noiseFrames * 2.5, MIN_SPEECH_RMS)
                if (rms > threshold) { speechStarted = true; silentMs = 0 } else if (speechStarted) silentMs += FRAME_MS

                if (speechStarted && silentMs >= END_SILENCE_MS) break
                if (!speechStarted && elapsedMs >= NO_SPEECH_TIMEOUT_MS) break
                if (elapsedMs >= MAX_RECORD_MS) break
            }
        } finally {
            runCatching { rec.stop() }; rec.release()
        }
        Log.i(TAG, "cloud STT recorded ${elapsedMs}ms speech=$speechStarted maxRms=${maxRms.toInt()}")
        if (cancelled) throw SttException(-5, "cancelled")
        if (!speechStarted) throw SttException(-2, "no speech")
        return out.toByteArray()
    }

    private suspend fun recognize(pcm: ByteArray, langs: List<Lang>): Pair<String, Lang> {
        val lang = langs.first()
        val config = JSONObject()
            .put("encoding", "LINEAR16")
            .put("sampleRateHertz", SAMPLE_RATE)
            .put("languageCode", lang.cloudVoiceTag)
            .put("enableAutomaticPunctuation", true)
            .apply { if (langs.size > 1) put("alternativeLanguageCodes", JSONArray(langs.drop(1).map { it.cloudVoiceTag })) }
            .put("maxAlternatives", 1)
            .put("speechContexts", JSONArray().put(
                JSONObject().put("phrases", JSONArray(langs.flatMap(PlaceGlossary::hints))).put("boost", 15)
            ))
        val body = JSONObject()
            .put("config", config)
            .put("audio", JSONObject().put("content", Base64.encodeToString(pcm, Base64.NO_WRAP)))
        val res = cloud.post(URL, body, timeoutMs = 12_000)
        val results = res.optJSONArray("results") ?: return "" to lang
        val text = (0 until results.length()).joinToString(" ") {
            results.getJSONObject(it).getJSONArray("alternatives").getJSONObject(0).optString("transcript")
        }.trim()
        // 문자 체계를 먼저 보고(가장 확실), 판단이 안 되면 API 가 알려준 언어 사용
        val tag = results.optJSONObject(0)?.optString("languageCode").orEmpty().lowercase()
        val fromApi = langs.firstOrNull { tag.startsWith(it.code) || (it == Lang.ZH && tag.startsWith("cmn")) }
        val detected = ScriptHeuristic.detect(text)?.takeIf { it in langs && it != Lang.EN } ?: fromApi ?: lang
        Log.i(TAG, "cloud STT detected=${detected.code} tag=$tag chars=${text.length}") // 대화 내용은 로그에 남기지 않음
        return text to detected
    }

    override fun cancel() { cancelled = true }
    override fun release() = cancel()

    companion object {
        private const val TAG = "TaxiTalkStt"
        private const val URL = "https://speech.googleapis.com/v1/speech:recognize"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 100
        private const val FRAME = SAMPLE_RATE * FRAME_MS / 1000
        private const val NOISE_FRAMES = 3
        private const val MIN_SPEECH_RMS = 350.0
        private const val END_SILENCE_MS = 1_200
        private const val NO_SPEECH_TIMEOUT_MS = 7_000
        private const val MAX_RECORD_MS = 15_000
    }
}

/**
 * Google Cloud Text-to-Speech. Gleo 온디바이스 TTS 가 지원하지 않는 일본어/중국어 음성 출력용.
 * LINEAR16(WAV) 으로 받아 AudioTrack 으로 재생한다.
 */
class CloudTtsEngine(private val cloud: GoogleCloud) : TtsEngine {
    @Volatile private var track: AudioTrack? = null

    override val isAvailable get() = cloud.isUsable

    override suspend fun speak(text: String, lang: Lang, zone: Zone): Boolean = withContext(Dispatchers.IO) {
        if (!cloud.isUsable) return@withContext false
        val wav = runCatching {
            val res = cloud.post(URL, JSONObject()
                .put("input", JSONObject().put("text", text))
                .put("voice", JSONObject().put("languageCode", lang.cloudVoiceTag))
                .put("audioConfig", JSONObject().put("audioEncoding", "LINEAR16").put("sampleRateHertz", RATE)))
            Base64.decode(res.getString("audioContent"), Base64.DEFAULT)
        }.getOrElse { Log.w(TAG, "cloud TTS failed", it); return@withContext false }

        val pcm = wavData(wav)
        if (pcm.isEmpty()) return@withContext false
        stop()
        val usage = if (zone == Zone.REAR_ZONE) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_ASSISTANT
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size)
            .build()
        track = t
        t.write(pcm, 0, pcm.size)
        t.play()
        Log.i(TAG, "cloud TTS speak lang=${lang.code} zone=$zone \"$text\"")
        val frames = pcm.size / 2
        while (track === t && t.playState == AudioTrack.PLAYSTATE_PLAYING && t.playbackHeadPosition < frames) delay(50)
        val finished = track === t
        if (finished) { t.release(); track = null }
        finished
    }

    /** WAV 헤더를 건너뛰고 "data" 청크만 반환. */
    private fun wavData(wav: ByteArray): ByteArray {
        if (wav.size < 12 || String(wav, 0, 4) != "RIFF") return wav
        var i = 12
        while (i + 8 <= wav.size) {
            val id = String(wav, i, 4)
            val len = ByteBuffer.wrap(wav, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") return wav.copyOfRange(i + 8, minOf(wav.size, i + 8 + len))
            i += 8 + len
        }
        return ByteArray(0)
    }

    override fun stop() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }

    override fun release() = stop()

    companion object {
        private const val TAG = "TaxiTalkTts"
        private const val URL = "https://texttospeech.googleapis.com/v1/text:synthesize"
        private const val RATE = 24_000
    }
}

/**
 * 언어별 엔진 라우팅.
 * - 한국어(기사님): Gleo 온디바이스 STT/TTS (Pleos SDK 핵심 사용, 오프라인 가능)
 * - 승객 언어(EN/JA/ZH): 온라인이면 Google Cloud (고유명사·다국어 품질), 아니면 Gleo/Android 로 폴백
 */
class RoutingSttEngine(private val cloud: CloudSttEngine, private val local: SttEngine) : SttEngine {
    @Volatile private var active: SttEngine? = null
    override val isAvailable get() = cloud.isAvailable || local.isAvailable
    override suspend fun listenOnce(lang: Lang, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): Result<String> {
        if (lang != Lang.KO && cloud.isAvailable) {
            active = cloud
            val r = cloud.listenOnce(lang, onPartial, onLevel)
            // 마이크를 직접 열 수 없는 환경이면 Gleo STT 로 재시도
            if (r.exceptionOrNull().let { it is SttException && it.code == -1 }.not()) return r
            Log.w("TaxiTalkStt", "cloud STT mic unavailable → local")
        }
        active = local
        return local.listenOnce(lang, onPartial, onLevel)
    }
    override fun cancel() { cloud.cancel(); local.cancel() }
    override fun release() { cloud.release(); local.release() }
}

class RoutingTtsEngine(private val cloud: CloudTtsEngine, private val local: TtsEngine) : TtsEngine {
    override val isAvailable get() = cloud.isAvailable || local.isAvailable
    override suspend fun speak(text: String, lang: Lang, zone: Zone): Boolean {
        val cloudFirst = (lang == Lang.JA || lang == Lang.ZH) && cloud.isAvailable
        if (cloudFirst && cloud.speak(text, lang, zone)) return true
        if (local.speak(text, lang, zone)) return true
        return !cloudFirst && cloud.isAvailable && cloud.speak(text, lang, zone)
    }
    override fun stop() { cloud.stop(); local.stop() }
    override fun release() { cloud.release(); local.release() }
}
