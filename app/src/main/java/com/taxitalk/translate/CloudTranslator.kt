package com.taxitalk.translate

import android.text.Html
import android.util.Log
import com.taxitalk.cloud.GoogleCloud
import com.taxitalk.model.Lang
import org.json.JSONObject

/** Google Cloud Translation (v2) — 온라인일 때 ML Kit 보다 자연스러운 번역. */
class CloudTranslator(private val cloud: GoogleCloud) : TextTranslator {
    override suspend fun prepare(source: Lang, target: Lang) = cloud.isConfigured

    override suspend fun translate(text: String, source: Lang, target: Lang): Result<String> = runCatching {
        if (source == target) return@runCatching text
        check(cloud.isUsable) { "Cloud translation unavailable (no key or offline)" }
        val res = cloud.post(
            URL,
            JSONObject()
                .put("q", text)
                .put("source", source.cloudCode)
                .put("target", target.cloudCode)
                .put("format", "text"),
        )
        val out = res.getJSONObject("data").getJSONArray("translations").getJSONObject(0).getString("translatedText")
        Log.i(TAG, "cloud ${source.code}->${target.code}: \"$text\" -> \"$out\"")
        @Suppress("DEPRECATION") Html.fromHtml(out).toString() // 혹시 남은 HTML 엔티티 정리
    }

    companion object {
        private const val TAG = "TaxiTalkCloud"
        private const val URL = "https://translation.googleapis.com/language/translate/v2"
    }
}

private val Lang.cloudCode get() = if (this == Lang.ZH) "zh-CN" else code

/**
 * 순서대로 시도하는 번역기 체인: 검수 문장 사전 → 클라우드(온라인) → ML Kit(오프라인).
 * 승객 → 기사 방향은 결과에 남은 외국어 지명을 한국어 지명으로 바꿔준다.
 */
class ChainTranslator(private val engines: List<TextTranslator>) : TextTranslator {
    override suspend fun prepare(source: Lang, target: Lang): Boolean {
        var any = false
        for (e in engines) if (e.prepare(source, target)) any = true
        return any
    }

    override suspend fun translate(text: String, source: Lang, target: Lang): Result<String> {
        var last: Throwable? = null
        for (e in engines) {
            e.translate(text, source, target)
                .onSuccess { out ->
                    val fixed = if (target == Lang.KO) PlaceGlossary.localizeForDriver(out, source) else out
                    return Result.success(fixed)
                }
                .onFailure { last = it }
        }
        return Result.failure(last ?: IllegalStateException("no translator"))
    }

    override fun close() = engines.forEach { it.close() }
}
