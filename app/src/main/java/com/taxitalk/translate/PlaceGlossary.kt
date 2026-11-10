package com.taxitalk.translate

import com.taxitalk.model.Lang

/**
 * 택시 승객이 자주 말하는 목적지 사전.
 * - STT 힌트(speechContexts)로 넘겨 "Seoul Station" 같은 고유명사 인식률을 올린다.
 * - 번역 결과에 섞여 나오는 외국어 지명을 기사님이 바로 알아듣도록 한국어 지명으로 치환한다.
 */
object PlaceGlossary {
    /** ko → (en, ja, zh) */
    private val places: List<Map<Lang, List<String>>> = listOf(
        p("서울역", listOf("Seoul Station"), listOf("ソウル駅"), listOf("首尔站")),
        p("인천공항", listOf("Incheon Airport", "Incheon International Airport", "ICN"), listOf("仁川空港", "仁川国際空港"), listOf("仁川机场", "仁川国际机场")),
        p("김포공항", listOf("Gimpo Airport", "Kimpo Airport", "GMP"), listOf("金浦空港"), listOf("金浦机场")),
        p("명동", listOf("Myeongdong", "Myeong-dong", "Myungdong"), listOf("明洞"), listOf("明洞")),
        p("강남역", listOf("Gangnam Station"), listOf("江南駅"), listOf("江南站")),
        p("강남", listOf("Gangnam"), listOf("江南"), listOf("江南")),
        p("홍대", listOf("Hongdae", "Hongik University"), listOf("弘大"), listOf("弘大")),
        p("이태원", listOf("Itaewon"), listOf("梨泰院"), listOf("梨泰院")),
        p("동대문", listOf("Dongdaemun", "DDP"), listOf("東大門"), listOf("东大门")),
        p("남대문시장", listOf("Namdaemun Market"), listOf("南大門市場"), listOf("南大门市场")),
        p("경복궁", listOf("Gyeongbokgung", "Gyeongbok Palace"), listOf("景福宮"), listOf("景福宫")),
        p("창덕궁", listOf("Changdeokgung"), listOf("昌徳宮"), listOf("昌德宫")),
        p("인사동", listOf("Insadong", "Insa-dong"), listOf("仁寺洞"), listOf("仁寺洞")),
        p("북촌한옥마을", listOf("Bukchon Hanok Village", "Bukchon"), listOf("北村韓屋村"), listOf("北村韩屋村")),
        p("남산타워", listOf("Namsan Tower", "N Seoul Tower", "Seoul Tower"), listOf("Nソウルタワー", "南山タワー"), listOf("N首尔塔", "南山塔")),
        p("롯데월드타워", listOf("Lotte World Tower"), listOf("ロッテワールドタワー"), listOf("乐天世界塔")),
        p("롯데월드", listOf("Lotte World"), listOf("ロッテワールド"), listOf("乐天世界")),
        p("잠실", listOf("Jamsil"), listOf("蚕室"), listOf("蚕室")),
        p("코엑스", listOf("COEX", "Coex Mall"), listOf("コエックス"), listOf("COEX")),
        p("여의도", listOf("Yeouido"), listOf("汝矣島"), listOf("汝矣岛")),
        p("광화문", listOf("Gwanghwamun"), listOf("光化門"), listOf("光化门")),
        p("시청", listOf("City Hall", "Seoul City Hall"), listOf("市庁"), listOf("市厅")),
        p("용산역", listOf("Yongsan Station"), listOf("龍山駅"), listOf("龙山站")),
        p("성수동", listOf("Seongsu", "Seongsu-dong"), listOf("聖水洞"), listOf("圣水洞")),
        p("압구정", listOf("Apgujeong"), listOf("狎鴎亭"), listOf("狎鸥亭")),
        p("가로수길", listOf("Garosu-gil"), listOf("カロスキル"), listOf("林荫道")),
        p("고속터미널", listOf("Express Bus Terminal"), listOf("高速ターミナル"), listOf("高速巴士客运站")),
        p("호텔", listOf("hotel"), listOf("ホテル"), listOf("酒店")),
    )

    private fun p(ko: String, en: List<String>, ja: List<String>, zh: List<String>) =
        mapOf(Lang.KO to listOf(ko), Lang.EN to en, Lang.JA to ja, Lang.ZH to zh)

    /** STT 인식 힌트 (Google speechContexts 한도 내). */
    fun hints(lang: Lang): List<String> = places.flatMap { it[lang].orEmpty() }.distinct()

    /** 외국어 → 한국어 번역 결과 안에 남은 외국어 지명을 한국어 지명으로 치환. */
    fun localizeForDriver(translatedKo: String, source: Lang): String {
        if (source == Lang.KO) return translatedKo
        // 온디바이스 번역이 띄어 쓰는 지명 정리 ("서울 역" → "서울역")
        var out = translatedKo
        for (place in places) {
            val ko = place.getValue(Lang.KO).first()
            if (ko.endsWith("역")) out = out.replace(ko.dropLast(1) + " 역", ko)
        }
        for (place in places) {
            val ko = place.getValue(Lang.KO).first()
            for (name in place[source].orEmpty().sortedByDescending { it.length }) {
                out = out.replace(name, ko, ignoreCase = true)
            }
        }
        return out
    }
}
