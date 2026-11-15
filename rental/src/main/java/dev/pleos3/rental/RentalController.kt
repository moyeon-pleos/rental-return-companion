package dev.pleos3.rental

import dev.pleos3.car.CarLog
import dev.pleos3.car.CarSignals
import dev.pleos3.car.CarState
import dev.pleos3.car.DebugBridge
import dev.pleos3.car.Dictation
import dev.pleos3.car.DictationEffect
import dev.pleos3.car.DictationState
import dev.pleos3.car.Gear
import dev.pleos3.car.LocFix
import dev.pleos3.car.LocationFeed
import dev.pleos3.car.NaviGate
import dev.pleos3.car.SpeechFailure
import dev.pleos3.car.SpeechInput
import dev.pleos3.car.SpeechListener
import dev.pleos3.car.Voice
import dev.pleos3.rental.core.Contract
import dev.pleos3.rental.core.Effect
import dev.pleos3.rental.core.LateDialog
import dev.pleos3.rental.core.LateStep
import dev.pleos3.rental.core.Pickup
import dev.pleos3.rental.core.Prep
import dev.pleos3.rental.core.RefuelResult
import dev.pleos3.rental.core.RefuelWatcher
import dev.pleos3.rental.core.RentalSession
import dev.pleos3.rental.core.Report
import dev.pleos3.rental.core.ReturnCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class RentalScreen {
    WAITING, HOME, PREP, REPORT, CARD, LATE, DRIVING,
}

enum class RentalDictationTarget {
    REPORT,
}

data class RentalUiState(
    val contract: Contract,
    val screen: RentalScreen = RentalScreen.DRIVING,
    val car: CarState = CarState(),
    val pickup: Pickup? = null,
    val prep: Prep? = null,
    val card: ReturnCard? = null,
    val reports: List<Report> = emptyList(),
    val reportCar: CarState? = null,
    val refuel: RefuelResult? = null,
    val lateQuestion: String? = null,
    val lateEtaMs: Long? = null,
    val lateSent: Boolean? = null,
    val arrivalAtMs: Long? = null,
    val nowMs: Long = 0L,
    val dictation: DictationState<RentalDictationTarget> = DictationState.Idle,
)

class RentalController(
    private val signals: CarSignals,
    private val voice: Voice,
    private val navi: NaviGate,
    private val speech: SpeechInput,
    private val locations: LocationFeed,
    private val contract: Contract = SampleContract.value,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = RentalSession(contract) { DebugBridge.nowMs() }
    private val dictation = Dictation<RentalDictationTarget>()
    private val mutableState = MutableStateFlow(RentalUiState(contract = contract))
    val state: StateFlow<RentalUiState> = mutableState.asStateFlow()

    private var started = false
    private var page = RentalScreen.HOME
    private var car = CarState()
    private var location: LocFix? = null
    private var reportCar: CarState? = null
    private var watcher = RefuelWatcher()
    private var refuel: RefuelResult? = null
    private var debugRemain: Int? = null
    private var latePending = false
    private var lateDialog: LateDialog? = null
    private var lateListening = false
    private var lateListenGeneration = 0L
    private var lateQuestion: String? = null
    private var lateEtaMs: Long? = null
    private var lateSent: Boolean? = null
    private var pendingArrival: String? = null
    private var arrivalAtMs: Long? = null
    private var loggedScreen: RentalScreen? = null
    private var loggedPrep: Prep? = null

    private val speechListener = object : SpeechListener {
        override fun onPartial(text: String) {
            CarLog.i("HEAR: partial «$text»")
            executeDictation(dictation.onPartial(text))
            publish()
        }

        override fun onFinal(text: String) {
            CarLog.i("HEAR: final «$text»")
            executeDictation(dictation.onFinal(text))
            publish()
        }

        override fun onFailed(reason: SpeechFailure) {
            CarLog.i("HEAR: failed $reason")
            executeDictation(dictation.onFailed(reason))
            publish()
        }
    }

    fun start() {
        if (started) return
        started = true

        // 수집기를 먼저 연결해 시작 직후의 방송과 도착 알림도 받는다.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            signals.state.collect { acceptCar(DebugBridge.apply(it)) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            navi.remainMinutes.collect {
                checkLate()
                publish()
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            navi.arrivals.collect { name ->
                if (name == contract.returnPlace.name) {
                    arrivalAtMs = DebugBridge.nowMs()
                    pendingArrival = name
                    acceptCar(DebugBridge.apply(signals.state.value))
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            locations.fixes.collect { location = it }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            DebugBridge.events.collect { event ->
                if (event["reset"] == "1") {
                    cancelLateAnswer()
                    cancelDictation()
                    debugRemain = null
                    watcher = RefuelWatcher()
                }
                event["remain_min"]?.toIntOrNull()?.let {
                    debugRemain = it.takeIf { value -> value >= 0 }
                }
                acceptCar(DebugBridge.apply(signals.state.value))

                // 같은 연료 값의 반복 방송도 각각 한 번의 관측으로 센다.
                // StateFlow는 같은 값을 합치므로 이 경로를 따로 둔다.
                event["fuel"]?.toIntOrNull()?.let { observeFuel(it) }
                event["tap"]?.let { tap(it) }
                // 시험용 말도 실제 음성과 같은 답변 처리를 거친다.
                event["say"]?.let { acceptText(it) }
                checkLate()
                publish()
            }
        }
        signals.start()
        navi.start()
        locations.start()

        scope.launch {
            while (true) {
                delay(1_000L)
                val fresh = signals.refresh()
                acceptCar(DebugBridge.apply(fresh))

                // 실제 차량은 재조회로 안정 관측을 만든다.
                // 디버그 연료는 방송 횟수만 세어 두 경로의 중복을 막는다.
                if (DebugBridge.apply(CarState()).fuelPercent == null) {
                    car.fuelPercent?.let { observeFuel(it) }
                }
                publish()
            }
        }
    }

    fun stop() {
        cancelLateAnswer()
        cancelDictation()
        publish()
        if (!started) return
        started = false
        scope.cancel()
        locations.stop()
        navi.stop()
        signals.stop()
    }

    fun tap(action: String) {
        // 지연 답변은 운행 중에도 음성 답변과 같은 동작으로 시험할 수 있다.
        if (action == "late_yes" || action == "late_no") {
            answerLate(action == "late_yes")
            return
        }
        if (car.gear != Gear.P) return
        // 지연 답을 기다리는 동안에는 하나뿐인 마이크를 지연 답 듣기가 쓰고 있을 수 있다.
        if (
            latePending &&
            (action == "report" || action == "speak_report" || action == "report1")
        ) {
            return
        }

        when (action) {
            "home" -> showPage(RentalScreen.HOME)

            "prep" -> if (session.pickup != null) {
                showPage(RentalScreen.PREP)
                CarLog.i("PREP: ${session.prepare(car, remainMinutes())}")
            }

            "route_station" -> if (session.pickup != null) {
                showPage(RentalScreen.PREP)
                execute(listOf(Effect.RouteTo(contract.station)))
            }

            "route_return" -> if (session.pickup != null) {
                cancelDictation()
                execute(listOf(Effect.RouteTo(contract.returnPlace)))
            }

            "report" -> if (session.pickup != null) {
                showPage(RentalScreen.REPORT)
                if (visibleScreen() == RentalScreen.REPORT) {
                    executeDictation(dictation.start(RentalDictationTarget.REPORT))
                }
            }

            "speak_report" -> if (visibleScreen() == RentalScreen.REPORT) {
                executeDictation(dictation.start(RentalDictationTarget.REPORT))
            }

            "confirm" -> if (visibleScreen() == RentalScreen.REPORT) {
                executeDictation(dictation.confirm())
            }

            "retry" -> if (visibleScreen() == RentalScreen.REPORT) {
                val effects = dictation.retry()
                if (effects.isNotEmpty()) {
                    CarLog.i("CONFIRM: retry")
                }
                executeDictation(effects)
            }

            "report1" -> if (visibleScreen() == RentalScreen.REPORT) {
                hearReport("주차해 둔 사이 뒤 범퍼 오른쪽이 긁혔어요")
            }

            "finish_return" -> {
                cancelDictation()
                execute(session.finishReturn(car))
            }
        }
        publish()
    }

    private fun acceptCar(next: CarState) {
        val previous = car
        car = next
        if (next.gear != Gear.P) {
            cancelDictation()
        }
        execute(session.onCarState(previous, next))
        if (next.gear == Gear.P) {
            pendingArrival?.let { name ->
                pendingArrival = null
                execute(session.onArrive(name, next))
            }
        }
        checkLate()
        publish()
    }

    private fun observeFuel(percent: Int) {
        if (session.pickup == null || session.card?.final == true) return
        watcher.onFuel(percent)?.let { result ->
            refuel = result
            CarLog.i("REFUEL: before=${result.before}, after=${result.after}")
            execute(session.onRefuel(result))
        }
    }

    private fun remainMinutes(): Int? =
        (debugRemain ?: navi.remainMinutes.value)?.takeIf { it >= 0 }

    private fun checkLate() {
        val minutes = remainMinutes() ?: return
        val effects = session.onRemainMinutes(minutes, car.gear)
        if (effects.isEmpty()) return
        cancelDictation()
        latePending = true
        lateDialog = LateDialog()
        lateSent = null
        lateEtaMs = DebugBridge.nowMs() + minutes.toLong() * 60_000L
        lateQuestion = effects.filterIsInstance<Effect.Say>().firstOrNull()?.text
        CarLog.i("LATE: asked eta=$lateEtaMs, sent=false")
        execute(effects)
        awaitLateAnswer()
    }

    private fun awaitLateAnswer() {
        if (!latePending) return
        val generation = ++lateListenGeneration
        voice.whenQuiet {
            // 디버그 입력이나 취소 전에 걸어 둔 작업은 다시 듣기를 시작하지 않는다.
            if (latePending && generation == lateListenGeneration) {
                listenLateAnswer()
            }
        }
    }

    private fun listenLateAnswer() {
        val dialog = lateDialog ?: return
        if (!latePending || lateListening ||
            dictation.state is DictationState.Listening
        ) {
            return
        }

        val generation = lateListenGeneration
        lateListening = true
        CarLog.i("LATE: listen")

        // 취소했거나 다음 듣기로 넘어간 뒤에 온 낡은 콜백인지 본다.
        fun isStale(): Boolean =
            !latePending || !lateListening ||
                generation != lateListenGeneration || lateDialog !== dialog

        speech.listen(object : SpeechListener {
            override fun onFinal(text: String) {
                if (isStale()) return
                lateListening = false
                hearLateAnswer(text)
            }

            override fun onFailed(reason: SpeechFailure) {
                if (isStale()) return
                lateListening = false
                CarLog.i("LATE: no answer ($reason)")
                handleLateStep(dialog.onNoAnswer())
            }
        })
    }

    private fun hearLateAnswer(text: String) {
        if (!latePending) return
        val dialog = lateDialog ?: return
        cancelLateListening()
        CarLog.i("LATE: heard «$text»")
        handleLateStep(dialog.onHeard(text))
    }

    private fun handleLateStep(step: LateStep) {
        if (!latePending) return
        when (step) {
            is LateStep.Answered -> {
                step.say?.let { voice.say(it) }
                answerLate(step.send)
            }

            is LateStep.AskAgain -> {
                CarLog.i("LATE: ask again")
                voice.say(step.say)
                awaitLateAnswer()
            }

            is LateStep.GiveUp -> {
                CarLog.i("LATE: gave up")
                voice.say(step.say)
                answerLate(false)
            }
        }
    }

    private fun cancelLateListening() {
        lateListenGeneration += 1
        if (lateListening) {
            lateListening = false
            speech.cancel()
        }
    }

    private fun cancelLateAnswer() {
        latePending = false
        lateDialog = null
        cancelLateListening()
    }

    private fun answerLate(send: Boolean) {
        if (!latePending) return
        cancelLateAnswer()
        lateSent = send
        execute(session.answerLate(send))
        CarLog.i("LATE: answered sent=$send, eta=$lateEtaMs, demo=true")
        publish()
    }

    private fun acceptText(text: String) {
        acceptCar(DebugBridge.apply(signals.state.value))
        when {
            visibleScreen() == RentalScreen.REPORT -> hearReport(text)
            latePending -> hearLateAnswer(text)
        }
        publish()
    }

    private fun hearReport(text: String) {
        CarLog.i("HEAR: given «$text»")
        executeDictation(dictation.heard(RentalDictationTarget.REPORT, text))
    }

    private fun cancelDictation() {
        val active = dictation.state != DictationState.Idle
        val effects = dictation.cancel()
        if (active) {
            CarLog.i("HEAR: cancel")
        }
        executeDictation(effects)
    }

    private fun showPage(screen: RentalScreen) {
        if (screen != RentalScreen.REPORT) {
            cancelDictation()
        }
        page = screen
    }

    private fun executeDictation(effects: List<DictationEffect<RentalDictationTarget>>) {
        effects.forEach { effect ->
            when (effect) {
                DictationEffect.StartListening -> {
                    CarLog.i("HEAR: start REPORT")
                    // 차가 읽어 주던 말이 받아 적히지 않게 먼저 끊는다.
                    voice.hush()
                    speech.listen(speechListener)
                }

                DictationEffect.StopListening -> {
                    // 신고 받아쓰기를 버릴 때 지연 답 듣기는 끊지 않는다.
                    if (!lateListening) {
                        speech.cancel()
                    }
                }

                is DictationEffect.Say -> voice.say(effect.text)

                is DictationEffect.Commit -> when (effect.target) {
                    RentalDictationTarget.REPORT -> {
                        CarLog.i("CONFIRM: REPORT «${effect.text}»")
                        saveReport(effect.text)
                    }
                }
            }
        }
    }

    private fun saveReport(text: String) {
        if (car.gear != Gear.P || text.isBlank()) return
        val captured = car.copy(
            telltales = car.telltales.toSet(),
            windowOpen = car.windowOpen.toMap(),
        )
        val report = session.report(text, captured, location)
        reportCar = captured
        CarLog.i("REPORT: $report")
    }

    private fun execute(effects: List<Effect>) {
        effects.forEach { effect ->
            when (effect) {
                is Effect.Say -> voice.say(effect.text)
                is Effect.RouteTo -> navi.routeTo(effect.place)
                Effect.ShowPickup -> {
                    showPage(RentalScreen.HOME)
                    car.fuelPercent?.let { watcher.onFuel(it) }
                    CarLog.i("PICKUP: ${session.pickup}")
                }
                is Effect.CardReady -> {
                    showPage(RentalScreen.CARD)
                    CarLog.i("CARD: ${effect.card}")
                }
            }
        }
    }

    private fun visibleScreen(): RentalScreen = when {
        car.gear != Gear.P -> RentalScreen.DRIVING
        session.pickup == null -> RentalScreen.WAITING
        latePending -> RentalScreen.LATE
        else -> page
    }

    private fun publish() {
        val prep = session.prepare(car, remainMinutes())
        if (prep != null && prep != loggedPrep) {
            loggedPrep = prep
            CarLog.i("PREP: $prep")
        }
        val screen = visibleScreen()
        mutableState.value = RentalUiState(
            contract = contract,
            screen = screen,
            car = car,
            pickup = session.pickup,
            prep = prep,
            card = session.card,
            reports = session.reports,
            reportCar = reportCar,
            refuel = refuel,
            lateQuestion = lateQuestion,
            lateEtaMs = lateEtaMs,
            lateSent = lateSent,
            arrivalAtMs = arrivalAtMs,
            nowMs = DebugBridge.nowMs(),
            dictation = dictation.state,
        )
        if (screen != loggedScreen) {
            loggedScreen = screen
            val name = when (screen) {
                RentalScreen.WAITING -> "인수 대기"
                RentalScreen.HOME -> "인수 기록"
                RentalScreen.PREP -> "반납 준비"
                RentalScreen.REPORT -> "말로 신고"
                RentalScreen.CARD -> "반납 카드"
                RentalScreen.LATE -> "지연 알림"
                RentalScreen.DRIVING -> "운행 중"
            }
            CarLog.i("SCREEN: $name")
        }
    }
}
