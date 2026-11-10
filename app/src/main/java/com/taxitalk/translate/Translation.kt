package com.taxitalk.translate

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.taxitalk.model.Lang
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/** 번역 엔진 추상화 (NFR-5.1). */
interface TextTranslator {
    /** 모델 준비(다운로드). 성공 여부 반환. */
    suspend fun prepare(source: Lang, target: Lang): Boolean
    suspend fun translate(text: String, source: Lang, target: Lang): Result<String>
    fun close() {}
}

/** 언어 감지 추상화 (FR-1.2). */
interface LanguageDetector {
    suspend fun detect(text: String): Lang?
}

private fun Lang.mlKit(): String = when (this) {
    Lang.KO -> TranslateLanguage.KOREAN
    Lang.EN -> TranslateLanguage.ENGLISH
    Lang.JA -> TranslateLanguage.JAPANESE
    Lang.ZH -> TranslateLanguage.CHINESE
}

/** ML Kit 온디바이스 번역. 모델은 최초 1회 다운로드 후 오프라인 동작 (C-2). */
class MlKitTranslator : TextTranslator {
    private val translators = ConcurrentHashMap<Pair<Lang, Lang>, com.google.mlkit.nl.translate.Translator>()
    private val ready = ConcurrentHashMap.newKeySet<Pair<Lang, Lang>>()

    private fun client(source: Lang, target: Lang) = translators.getOrPut(source to target) {
        Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source.mlKit()).setTargetLanguage(target.mlKit()).build()
        )
    }

    override suspend fun prepare(source: Lang, target: Lang): Boolean {
        val key = source to target
        if (key in ready) return true
        return runCatching {
            withTimeout(60_000) { client(source, target).downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
            ready += key
            true
        }.getOrElse { Log.w(TAG, "model prepare failed $key", it); false }
    }

    override suspend fun translate(text: String, source: Lang, target: Lang): Result<String> = runCatching {
        if (source == target) return@runCatching text
        check(prepare(source, target)) { "Translation model not available" }
        withTimeout(10_000) { client(source, target).translate(text).await() }
    }

    override fun close() { translators.values.forEach { it.close() }; translators.clear() }

    companion object { private const val TAG = "TaxiTalkMlKit" }
}

/**
 * 택시 상용 문장은 검수된 사전 번역을 우선 사용하고(품질·지연 보장),
 * 그 외 자유 발화는 1차 엔진(ML Kit). 1차 엔진 실패 시에도 사전으로 폴백 (NFR-4.1).
 */
class FallbackTranslator(
    private val primary: TextTranslator,
    private val phrasebook: TextTranslator = PhrasebookTranslator,
) : TextTranslator {
    override suspend fun prepare(source: Lang, target: Lang) = primary.prepare(source, target)
    override suspend fun translate(text: String, source: Lang, target: Lang): Result<String> {
        phrasebook.translate(text, source, target).onSuccess { return Result.success(it) }
        return primary.translate(text, source, target)
    }
    override fun close() = primary.close()
}

/** ML Kit 언어 감지 + 문자 체계 기반 휴리스틱 폴백. */
class MlKitLanguageDetector : LanguageDetector {
    private val client = LanguageIdentification.getClient()

    override suspend fun detect(text: String): Lang? {
        val ml = runCatching { withTimeout(3_000) { client.identifyLanguage(text).await() } }.getOrNull()
        val fromMl = when {
            ml == null || ml == "und" -> null
            ml.startsWith("zh") -> Lang.ZH
            else -> Lang.fromCode(ml)
        }
        return fromMl ?: ScriptHeuristic.detect(text)
    }
}

object ScriptHeuristic {
    fun detect(text: String): Lang? {
        var hangul = 0; var kana = 0; var han = 0; var latin = 0
        for (c in text) {
            when (Character.UnicodeBlock.of(c)) {
                Character.UnicodeBlock.HANGUL_SYLLABLES, Character.UnicodeBlock.HANGUL_JAMO,
                Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO -> hangul++
                Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA -> kana++
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS -> han++
                Character.UnicodeBlock.BASIC_LATIN -> if (c.isLetter()) latin++
                else -> {}
            }
        }
        return when {
            hangul > 0 && hangul >= kana -> Lang.KO
            kana > 0 -> Lang.JA
            han > 0 -> Lang.ZH
            latin > 0 -> Lang.EN
            else -> null
        }
    }
}
