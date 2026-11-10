package com.taxitalk.model

import java.util.Locale

/** 지원 언어. 운전자는 항상 KO, 승객은 EN/JA/ZH. */
enum class Lang(
    val code: String,          // ML Kit / BCP-47 language code
    val sttTag: String,        // SpeechRecognizer locale tag
    val locale: Locale,
    val nativeName: String,
    val flag: String,
) {
    KO("ko", "ko-KR", Locale.KOREA, "한국어", "🇰🇷"),
    EN("en", "en-US", Locale.US, "English", "🇺🇸"),
    JA("ja", "ja-JP", Locale.JAPAN, "日本語", "🇯🇵"),
    ZH("zh", "zh-CN", Locale.SIMPLIFIED_CHINESE, "中文", "🇨🇳");

    companion object {
        val passengerLanguages = listOf(EN, JA, ZH)
        fun fromCode(code: String?): Lang? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

/** 발화 주체 = 좌석 역할. */
enum class Speaker { DRIVER, PASSENGER, VEHICLE }

/**
 * 출력 채널(SSZ 좌석별 소리 분리 모사).
 * DRIVER_ZONE: 운전석 디스플레이/스피커, REAR_ZONE: 뒷좌석 뷰/스피커.
 */
enum class Zone { DRIVER_ZONE, REAR_ZONE }

/** 통역 한 턴의 상태 (FR-2.5). */
enum class TurnState { IDLE, LISTENING, RECOGNIZING, TRANSLATING, SPEAKING, ERROR }

data class Utterance(
    val id: Long,
    val speaker: Speaker,
    val sourceLang: Lang,
    val targetLang: Lang,
    val original: String,
    val translated: String?,
    val translationFailed: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)
