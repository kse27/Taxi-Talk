package com.taxitalk.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taxitalk.R
import com.taxitalk.announce.Announcement
import com.taxitalk.announce.AnnouncementRules
import com.taxitalk.announce.AnnouncementType
import com.taxitalk.announce.NightSurcharge
import com.taxitalk.model.Lang
import com.taxitalk.model.Speaker
import com.taxitalk.model.TurnState
import com.taxitalk.model.Utterance
import com.taxitalk.model.Zone
import com.taxitalk.speech.AndroidSttEngine
import com.taxitalk.speech.AndroidTtsEngine
import com.taxitalk.speech.AudioDucker
import com.taxitalk.speech.ButtonTone
import com.taxitalk.speech.SttException
import com.taxitalk.speech.PleosSttEngine
import com.taxitalk.speech.PleosTtsEngine
import com.taxitalk.speech.PreferredSttEngine
import com.taxitalk.speech.PreferredTtsEngine
import com.taxitalk.speech.SttEngine
import com.taxitalk.speech.TtsEngine
import com.taxitalk.translate.ChainTranslator
import com.taxitalk.translate.PhrasebookTranslator
import com.taxitalk.translate.MlKitTranslator
import com.taxitalk.translate.TextTranslator
import com.taxitalk.vehicle.NaviEvent
import com.taxitalk.vehicle.PleosNaviSource
import com.taxitalk.vehicle.VehicleRepository
import com.taxitalk.vehicle.VehicleState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicLong

data class UiError(val speaker: Speaker, @androidx.annotation.StringRes val res: Int)

enum class ModelStatus { UNKNOWN, DOWNLOADING, READY, FALLBACK }

data class UiState(
    val passengerLang: Lang? = null,
    val utterances: List<Utterance> = emptyList(),
    val driverNotice: Announcement? = null,
    /** 차가 손님에게 자동으로 안내한 기록 */
    val announceLog: List<Announcement> = emptyList(),
    /** 운행 종료 직후 "대화 삭제됨" 표시 */
    val conversationDeleted: Boolean = false,
    /** 기사 말 실패 시(정차 중) 문구 선택 화면 표시 */
    val phrasePrompt: Boolean = false,
    /** 내비가 도착을 알렸을 때 기사 화면에 잠깐 표시 (목적지 이름, 없으면 빈 문자열) */
    val arrivedAt: String? = null,
    /** 시연 패널: 지도 콘셉트 화면 보기 */
    val showMapConcept: Boolean = false,
    val driverTurn: TurnState = TurnState.IDLE,
    val passengerTurn: TurnState = TurnState.IDLE,
    val partialText: String? = null,
    /** 듣는 중 마이크 입력 레벨 0..1 */
    val micLevel: Float = 0f,
    val error: UiError? = null,
    val sttAvailable: Boolean = false,
    val ttsAvailable: Boolean = false,
    val modelStatus: ModelStatus = ModelStatus.UNKNOWN,
    val simulateNight: Boolean = false,
    val showDemoPanel: Boolean = false,
) {
    val inRide get() = passengerLang != null
}

/**
 * 통역 세션 오케스트레이터. 턴 기반: 발화 → 인식 → 번역 → 좌석 채널 출력 (FR-2.5).
 * 대화 데이터는 메모리에만 존재하고 [endRide] 시 폐기한다 (NFR-3.1).
 */
class TaxiTalkViewModel(app: Application) : AndroidViewModel(app) {

    // 무료 온디바이스 엔진만 사용: Pleos STT/TTS(없으면 Android 기본), 번역은 문구집 → ML Kit
    private val stt: SttEngine = PreferredSttEngine(PleosSttEngine(app), AndroidSttEngine(app))
    private val tts: TtsEngine = PreferredTtsEngine(PleosTtsEngine(app), AndroidTtsEngine(app))
    private val mlKit = MlKitTranslator()
    private val translator: TextTranslator = ChainTranslator(listOf(PhrasebookTranslator, mlKit))
    private val tone = ButtonTone()
    private val ducker = AudioDucker(app)
    val vehicle = VehicleRepository(app, viewModelScope)
    private val navi = PleosNaviSource(app, viewModelScope, ::naviEvent)

    private val _ui = MutableStateFlow(UiState(sttAvailable = stt.isAvailable))
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    val vehicleState: StateFlow<VehicleState> get() = vehicle.state

    private val ids = AtomicLong()
    private val outputLock = Mutex()
    private var listenJob: Job? = null
    private var modelJob: Job? = null
    /** 버튼을 누르면 올라간다. 그 전에 대기하던 일반 안내는 재생하지 않는다. */
    private val outputEpoch = AtomicLong()
    private var promptingLanguage = false
    private val lastAnnounced = mutableMapOf<AnnouncementType, Long>()
    private var surchargeAnnounced = -1

    init {
        viewModelScope.launch {
            var prev = vehicle.state.value
            vehicle.state.collect { cur ->
                if (cur.isDriving && _ui.value.phrasePrompt) _ui.update { it.copy(phrasePrompt = false) }
                if (_ui.value.inRide) {
                    AnnouncementRules.onVehicleChange(prev, cur).forEach { announce(it) }
                    // 출발 시 할증 시간대면 안내
                    if (!prev.isDriving && cur.isDriving) checkSurcharge()
                }
                prev = cur
            }
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(1_500)
            kotlinx.coroutines.delay(2_500)
            _ui.update { it.copy(ttsAvailable = tts.isAvailable, sttAvailable = stt.isAvailable) }
        }
    }

    // ------------------------------------------------------------------ 탑승 / 설정 (FR-1)

    /** 기사님이 언어를 고르면 바로 운행 시작. 뒷좌석 화면이 없으므로 안내·고지는 음성으로 전달한다. */
    fun startRide(lang: Lang) {
        _ui.update { it.copy(passengerLang = lang) }
        prepareModels(lang)
        navi.start()
        announce(Announcement(AnnouncementType.WELCOME))
        announce(Announcement(AnnouncementType.RIDE_NOTICE))
        checkSurcharge()
    }

    /** 운행 중 언어 변경 (안내 재생 없이 전환). */
    fun changeLanguage(lang: Lang) {
        cancelListening()
        _ui.update { it.copy(passengerLang = lang) }
        prepareModels(lang)
    }

    /** 내가 말하기: 기사 버튼 짧게 / 화면 버튼. */
    fun driverTalk() = toggleListen(Speaker.DRIVER)

    /** 손님 말 듣기: 뒷좌석 버튼 / 기사 버튼 길게 / 화면 버튼. */
    fun passengerTalk() = toggleListen(Speaker.PASSENGER)

    /** 운행 전에 기사 버튼을 누르면 고장으로 오해하지 않도록 앞좌석에 음성으로 안내. */
    fun promptChooseLanguage() {
        if (_ui.value.inRide || promptingLanguage) return
        promptingLanguage = true
        viewModelScope.launch {
            try {
                output(localized(Lang.KO).getString(R.string.drv_choose_lang_first), Lang.KO, Zone.DRIVER_ZONE)
            } finally { promptingLanguage = false }
        }
    }

    /** 딥링크 진입 (FR-1.4): taxitalk://ride?lang=ja */
    fun handleDeepLink(langCode: String?) {
        Lang.fromCode(langCode)?.takeIf { it != Lang.KO }?.let { startRide(it) }
    }

    /** 문구집은 항상 쓸 수 있으므로, 준비 상태는 ML Kit 모델 기준으로 표시한다. */
    private fun prepareModels(lang: Lang) {
        modelJob?.cancel()
        modelJob = viewModelScope.launch {
            _ui.update { it.copy(modelStatus = ModelStatus.DOWNLOADING) }
            val ok = mlKit.prepare(Lang.KO, lang) && mlKit.prepare(lang, Lang.KO)
            _ui.update { it.copy(modelStatus = if (ok) ModelStatus.READY else ModelStatus.FALLBACK) }
        }
    }

    // ------------------------------------------------------------------ 통역 (FR-2)

    /**
     * 말하기 버튼(화면/물리 버튼 공통). 같은 쪽을 듣는 중에 다시 누르면 취소.
     * 상대 쪽을 듣는 중이면 무시하고, 음성 출력 중이면 끊고 바로 새로 듣는다.
     */
    fun toggleListen(speaker: Speaker) {
        val s = _ui.value
        val turn = if (speaker == Speaker.DRIVER) s.driverTurn else s.passengerTurn
        val other = if (speaker == Speaker.DRIVER) s.passengerTurn else s.driverTurn
        if (other == TurnState.LISTENING) {
            Log.i(TAG, "button ignored: other side is listening ($speaker)")
            return
        }
        val prev = interrupt()
        if (turn == TurnState.LISTENING) {
            Log.i(TAG, "listen cancelled ($speaker)")
            tone.cancelled()
            return
        }
        Log.i(TAG, "listen start ($speaker)")
        tone.started()
        listenJob = viewModelScope.launch {
            prev?.join()
            tts.stop()
            setTurn(speaker, TurnState.LISTENING)
            kotlinx.coroutines.delay(TONE_GAP_MS) // 효과음이 인식에 섞이지 않도록
            val lang = if (speaker == Speaker.DRIVER) Lang.KO else (s.passengerLang ?: Lang.EN)
            val result = stt.listenOnce(lang,
                onPartial = { partial -> _ui.update { it.copy(partialText = partial) } },
                onLevel = { lv -> _ui.update { it.copy(micLevel = lv) } })
            _ui.update { it.copy(partialText = null, micLevel = 0f) }
            result.fold(
                onSuccess = { text -> handleUtterance(speaker, text) },
                onFailure = { e ->
                    when ((e as? SttException)?.code) {
                        -5 -> setTurn(speaker, TurnState.IDLE) // 사용자가 취소
                        -1 -> onTurnFailed(speaker, R.string.err_stt_unavailable, askAgain = false)
                        else -> onTurnFailed(speaker, R.string.err_no_match)
                    }
                },
            )
        }
    }

    /** 문구 선택 / 직접 입력 (음성 실패 시 대안). */
    fun submitText(speaker: Speaker, text: String) {
        if (text.isBlank()) return
        val prev = interrupt()
        listenJob = viewModelScope.launch {
            prev?.join()
            tts.stop()
            handleUtterance(speaker, text.trim())
        }
    }

    fun dismissPhrasePrompt() = _ui.update { it.copy(phrasePrompt = false) }

    /** 시연 패널: 현장에서 인식 실패 흐름을 보여 주기 위한 모사. */
    fun simulateRecognitionFailure(speaker: Speaker) {
        if (!_ui.value.inRide) return
        val prev = interrupt()
        listenJob = viewModelScope.launch {
            prev?.join()
            tts.stop()
            onTurnFailed(speaker, R.string.err_no_match)
        }
    }

    /** 진행 중인 턴과 대기 중인 일반 안내를 멈춘다. 이전 작업을 돌려주므로 끝나길 기다린 뒤 다음을 시작한다. */
    private fun interrupt(): Job? {
        outputEpoch.incrementAndGet()
        val prev = listenJob
        stt.cancel()
        prev?.cancel()
        _ui.update { it.copy(driverTurn = TurnState.IDLE, passengerTurn = TurnState.IDLE, partialText = null, micLevel = 0f, phrasePrompt = false) }
        return prev
    }

    private fun cancelListening() {
        val s = _ui.value
        if (s.driverTurn != TurnState.LISTENING && s.passengerTurn != TurnState.LISTENING) return
        stt.cancel()
        listenJob?.cancel()
        _ui.update { it.copy(driverTurn = TurnState.IDLE, passengerTurn = TurnState.IDLE, partialText = null, micLevel = 0f) }
    }

    /**
     * 인식·번역 실패. 손님은 화면이 없으니 음성으로 다시 요청하고,
     * 기사는 정차 중이면 문구를 고르게 하고 주행 중이면 화면을 쓰지 않고 음성으로만 알린다.
     */
    private suspend fun onTurnFailed(speaker: Speaker, @androidx.annotation.StringRes res: Int, askAgain: Boolean = true) {
        Log.i(TAG, "turn failed ($speaker), driving=${vehicle.state.value.isDriving}")
        setError(speaker, res)
        val passengerLang = _ui.value.passengerLang ?: return
        when {
            speaker == Speaker.PASSENGER -> if (askAgain) sayAgain(passengerLang, Zone.REAR_ZONE)
            vehicle.state.value.isDriving -> if (askAgain) sayAgain(Lang.KO, Zone.DRIVER_ZONE)
            else -> _ui.update { it.copy(phrasePrompt = true) }
        }
    }

    private suspend fun sayAgain(lang: Lang, zone: Zone) =
        output(localized(lang).getString(R.string.ann_say_again), lang, zone)

    private suspend fun handleUtterance(speaker: Speaker, text: String) {
        val s = _ui.value
        val passengerLang = s.passengerLang ?: Lang.EN
        val source = if (speaker == Speaker.DRIVER) Lang.KO else passengerLang
        val target = if (speaker == Speaker.DRIVER) passengerLang else Lang.KO

        setTurn(speaker, TurnState.TRANSLATING)
        val result = withTimeoutOrNull(TRANSLATE_TIMEOUT_MS) { translator.translate(text, source, target) }
            ?: Result.failure(IllegalStateException("Translation timed out"))
        val utt = Utterance(
            id = ids.incrementAndGet(), speaker = speaker, sourceLang = source, targetLang = target,
            original = text, translated = result.getOrNull(), translationFailed = result.isFailure,
        )
        _ui.update { it.copy(utterances = (it.utterances + utt).takeLast(MAX_HISTORY)) }

        val translated = result.getOrNull()
        if (translated == null) {
            onTurnFailed(speaker, R.string.err_translate)
            return
        }
        setTurn(speaker, TurnState.SPEAKING)
        // 발화 주체에 따라 반대편 좌석 채널로 출력 (FR-2.4)
        val zone = if (speaker == Speaker.DRIVER) Zone.REAR_ZONE else Zone.DRIVER_ZONE
        output(translated, target, zone)
        setTurn(speaker, TurnState.IDLE)
    }

    // ------------------------------------------------------------------ 차량 안내 (FR-3/4/6)

    fun naviEvent(e: NaviEvent) {
        if (!_ui.value.inRide) return
        if (e is NaviEvent.Arrived) {
            val place = e.name.orEmpty()
            _ui.update { it.copy(arrivedAt = place) }
            viewModelScope.launch {
                kotlinx.coroutines.delay(ARRIVED_VISIBLE_MS)
                _ui.update { if (it.arrivedAt == place) it.copy(arrivedAt = null) else it }
            }
        }
        AnnouncementRules.onNavi(e)?.let(::announce)
    }

    /** 시연 패널: 내비에 목적지를 설정해 실제 경로 이벤트를 받는다. */
    fun requestDemoRoute() = navi.requestRoute("서울역", 37.5547, 126.9707)
    fun requestNearbyDemoRoute() = navi.requestNearbyRoute()
    fun requestDemoReRoute() = navi.requestReRoute()

    private fun checkSurcharge() {
        val pct = if (_ui.value.simulateNight) 40 else NightSurcharge.percentAt(LocalTime.now(java.time.ZoneId.of("Asia/Seoul")))
        if (pct > 0 && pct != surchargeAnnounced) {
            surchargeAnnounced = pct
            announce(Announcement(AnnouncementType.NIGHT_SURCHARGE, listOf(pct)))
        }
    }

    private fun announce(a: Announcement) {
        val lang = _ui.value.passengerLang ?: return
        // 문을 여닫거나 벨트를 반복 조작해도 같은 안내를 연달아 내보내지 않는다
        val last = lastAnnounced[a.type]
        if (last != null && a.timestamp - last < REPEAT_GUARD_MS) return
        lastAnnounced[a.type] = a.timestamp
        _ui.update { it.copy(driverNotice = a, announceLog = (it.announceLog + a).takeLast(MAX_HISTORY)) }
        val critical = a.type.priority >= CRITICAL_PRIORITY
        if (critical) { outputEpoch.incrementAndGet(); tts.stop() } // 안전 안내는 재생 중인 음성보다 먼저
        val epoch = outputEpoch.get()
        viewModelScope.launch {
            // 일반 안내는 듣기가 끝난 뒤 재생해 인식에 섞이지 않게 한다
            if (!critical) _ui.first { it.driverTurn != TurnState.LISTENING && it.passengerTurn != TurnState.LISTENING }
            // 승객 언어로 뒷좌석 채널 출력. 운전석에는 한국어 텍스트만 짧게 표시 (FR-4.2, NFR-1.3)
            output(localized(lang).getString(a.type.res, *a.args.toTypedArray()), lang, Zone.REAR_ZONE) {
                if (critical) cancelListening()
                _ui.value.inRide && (critical || epoch == outputEpoch.get())
            }
        }
    }

    private suspend fun output(text: String, lang: Lang, zone: Zone, shouldPlay: () -> Boolean = { true }) {
        outputLock.withLock {
            if (shouldPlay()) ducker.ducking { tts.speak(text, lang, zone) }
        }
    }

    fun localized(lang: Lang): Context {
        val app = getApplication<Application>()
        val config = Configuration(app.resources.configuration).apply { setLocale(lang.locale) }
        return app.createConfigurationContext(config)
    }

    // ------------------------------------------------------------------ 데모 / 세션

    fun setSimulateNight(on: Boolean) { _ui.update { it.copy(simulateNight = on) }; if (on && _ui.value.inRide) checkSurcharge() }
    fun toggleMapConcept() = _ui.update { it.copy(showMapConcept = !it.showMapConcept) }
    fun toggleDemoPanel() = _ui.update { it.copy(showDemoPanel = !it.showDemoPanel) }
    fun simulate(block: (VehicleState) -> VehicleState) = vehicle.simulated.update(block)
    fun dismissError() = _ui.update { it.copy(error = null) }

    /** 운행 종료: 대화·안내 기록 즉시 폐기 (NFR-3.1). */
    fun endRide() {
        outputEpoch.incrementAndGet()
        modelJob?.cancel()
        navi.stop()
        stt.cancel(); tts.stop(); listenJob?.cancel()
        surchargeAnnounced = -1
        lastAnnounced.clear()
        _ui.update {
            UiState(sttAvailable = stt.isAvailable, ttsAvailable = tts.isAvailable,
                simulateNight = it.simulateNight, showDemoPanel = it.showDemoPanel, conversationDeleted = true)
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(4_000)
            _ui.update { it.copy(conversationDeleted = false) }
        }
    }

    private fun setTurn(speaker: Speaker, t: TurnState) = _ui.update {
        if (speaker == Speaker.DRIVER) it.copy(driverTurn = t) else it.copy(passengerTurn = t)
    }

    private fun setError(speaker: Speaker, @androidx.annotation.StringRes res: Int) {
        setTurn(speaker, TurnState.ERROR)
        val err = UiError(speaker, res)
        _ui.update { it.copy(error = err) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(3_500)
            // 그사이 새 턴이 시작됐으면 건드리지 않는다
            _ui.update {
                when {
                    speaker == Speaker.DRIVER && it.driverTurn == TurnState.ERROR -> it.copy(driverTurn = TurnState.IDLE)
                    speaker == Speaker.PASSENGER && it.passengerTurn == TurnState.ERROR -> it.copy(passengerTurn = TurnState.IDLE)
                    else -> it
                }
            }
            _ui.update { if (it.error === err) it.copy(error = null) else it }
        }
    }

    override fun onCleared() {
        stt.release(); tts.release(); translator.close(); tone.release(); navi.release(); vehicle.release()
    }

    companion object {
        private const val TAG = "TaxiTalkVm"
        private const val MAX_HISTORY = 30
        private const val TONE_GAP_MS = 200L
        private const val REPEAT_GUARD_MS = 10_000L
        private const val ARRIVED_VISIBLE_MS = 8_000L
        private const val TRANSLATE_TIMEOUT_MS = 15_000L
        /** 이 우선순위 이상(벨트·차도 쪽 문)은 듣기와 재생을 끊고 바로 안내한다 */
        private const val CRITICAL_PRIORITY = 3
    }
}
