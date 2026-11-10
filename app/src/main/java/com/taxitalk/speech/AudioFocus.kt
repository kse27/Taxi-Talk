package com.taxitalk.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator

/**
 * 통역/중요 안내 출력 중 다른 오디오(뒷좌석 미디어 등)를 일시 감쇠 (FR-4.3).
 */
class AudioDucker(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        ).build()

    suspend fun <T> ducking(block: suspend () -> T): T {
        val granted = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        try { return block() } finally { if (granted) am.abandonAudioFocusRequest(request) }
    }
}

/** 버튼을 눌렀을 때 효과음. 주행 중 화면을 보지 않아도 눌렸는지 알 수 있게 한다. */
class ButtonTone {
    private val generator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
    fun started() { play(ToneGenerator.TONE_PROP_BEEP) }
    fun cancelled() { play(ToneGenerator.TONE_PROP_NACK) }
    fun release() { generator?.release() }
    private fun play(tone: Int) { runCatching { generator?.startTone(tone, 150) } }
}
