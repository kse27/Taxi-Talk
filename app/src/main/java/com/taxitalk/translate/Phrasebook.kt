package com.taxitalk.translate

import com.taxitalk.model.Lang

/**
 * 택시 상용 문장 사전. ML Kit 모델이 없을 때(첫 실행 오프라인, GMS 미탑재 에뮬레이터 등)
 * 데모·핵심 대화가 끊기지 않도록 하는 폴백 번역기이자, 빠른 문장 버튼의 데이터 소스.
 */
object PhrasebookTranslator : TextTranslator {

    /** 각 항목: KO, EN, JA, ZH */
    data class Phrase(val ko: String, val en: String, val ja: String, val zh: String) {
        fun of(lang: Lang) = when (lang) { Lang.KO -> ko; Lang.EN -> en; Lang.JA -> ja; Lang.ZH -> zh }
    }

    val driverPhrases = listOf(
        Phrase("어디로 모실까요?", "Where would you like to go?", "どちらまで行かれますか？", "您要去哪里？"),
        Phrase("안전벨트를 매 주세요.", "Please fasten your seatbelt.", "シートベルトをお締めください。", "请系好安全带。"),
        Phrase("목적지를 다시 한번 확인해 주세요.", "Could you confirm your destination once more?", "目的地をもう一度ご確認ください。", "请再确认一下您的目的地。"),
        Phrase("길이 막혀서 조금 돌아가겠습니다.", "Traffic is heavy, so I'll take a slight detour.", "渋滞しているので、少し迂回します。", "路上堵车，我会稍微绕一下路。"),
        Phrase("거의 다 왔습니다.", "We're almost there.", "もうすぐ到着します。", "快到了。"),
        Phrase("여기서 내려 드릴까요?", "Shall I drop you off here?", "ここで降ろしましょうか？", "在这里让您下车可以吗？"),
        Phrase("카드로 결제하시겠어요?", "Would you like to pay by card?", "カードでお支払いになりますか？", "您要刷卡付款吗？"),
        Phrase("감사합니다. 좋은 여행 되세요.", "Thank you. Have a great trip.", "ありがとうございます。良い旅を。", "谢谢。祝您旅途愉快。"),
    )

    val passengerPhrases = listOf(
        Phrase("경복궁으로 가 주세요.", "Gyeongbokgung Palace, please.", "景福宮までお願いします。", "请去景福宫。"),
        Phrase("인천공항으로 가 주세요.", "Incheon Airport, please.", "仁川空港までお願いします。", "请去仁川机场。"),
        Phrase("얼마나 걸리나요?", "How long will it take?", "どのくらいかかりますか？", "需要多长时间？"),
        Phrase("요금은 얼마인가요?", "How much is the fare?", "料金はいくらですか？", "车费是多少？"),
        Phrase("여기서 세워 주세요.", "Please stop here.", "ここで止めてください。", "请在这里停车。"),
        Phrase("에어컨을 좀 켜 주세요.", "Could you turn on the air conditioner?", "エアコンをつけてください。", "请开一下空调。"),
        Phrase("카드로 결제할게요.", "I'll pay by card.", "カードで払います。", "我用卡支付。"),
        Phrase("감사합니다.", "Thank you.", "ありがとうございます。", "谢谢。"),
    )

    private val all = driverPhrases + passengerPhrases

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    override suspend fun prepare(source: Lang, target: Lang) = true

    override suspend fun translate(text: String, source: Lang, target: Lang): Result<String> {
        if (source == target) return Result.success(text)
        val key = norm(text)
        val hit = all.firstOrNull { norm(it.of(source)) == key }
        return hit?.let { Result.success(it.of(target)) }
            ?: Result.failure(NoSuchElementException("Phrase not in phrasebook"))
    }
}
