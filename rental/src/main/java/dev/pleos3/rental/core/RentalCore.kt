package dev.pleos3.rental.core

import dev.pleos3.car.CarState
import dev.pleos3.car.Gear
import dev.pleos3.car.LocFix
import dev.pleos3.car.NaviPlace
import dev.pleos3.car.WindowPos
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

data class Contract(
    val returnAtMs: Long,
    val returnPlace: NaviPlace,
    val openHour: Int,
    val closeHour: Int,
    val station: NaviPlace,
    val stationToReturnKm: Double,
    val tankLiters: Double,
    val extraFeePer30Min: Int?,
)

data class Pickup(
    val timeMs: Long,
    val fuelPercent: Int?,
    val odometerKm: Float?,
    val telltales: Set<String>,
    val allClosed: Boolean,
)

object FuelTarget {
    fun targetPercent(
        pickupPercent: Int,
        economyKmPerL: Float?,
        stationToReturnKm: Double,
        tankLiters: Double,
    ): Int {
        val economy = economyKmPerL
            ?.takeIf { it.isFinite() && it > 0f }
            ?.toDouble()
            ?: 12.0
        val distance = stationToReturnKm
            .takeIf { it.isFinite() && it >= 0.0 }
            ?: 0.0
        val extraPercent = if (tankLiters.isFinite() && tankLiters > 0.0) {
            ceil(distance / economy / tankLiters * 100.0)
        } else {
            0.0
        }
        return (pickupPercent.toDouble() + extraPercent)
            .coerceIn(0.0, 100.0)
            .toInt()
    }

    fun litersNeeded(
        nowPercent: Int,
        targetPercent: Int,
        tankLiters: Double,
    ): Double {
        if (!tankLiters.isFinite() || tankLiters <= 0.0) return 0.0
        return max(0.0, (targetPercent.toDouble() - nowPercent) / 100.0 * tankLiters)
    }
}

object DepartureTime {
    fun compute(
        returnAtMs: Long,
        travelMin: Int,
        needFuel: Boolean,
        fuelMin: Int = 15,
        bufferMin: Int = 10,
    ): Long {
        val minutes = travelMin.toLong() +
            (if (needFuel) fuelMin.toLong() else 0L) +
            bufferMin.toLong()
        return returnAtMs - minutes * 60_000L
    }
}

data class RefuelResult(
    val before: Int,
    val after: Int,
)

class RefuelWatcher(private val stableReadings: Int = 3) {
    private var previous: Int? = null
    private var before: Int? = null
    private var equalCount = 0

    fun onFuel(percent: Int): RefuelResult? {
        val last = previous
        if (last == null) {
            previous = percent
            return null
        }

        when {
            percent > last -> {
                if (before == null) before = last
                equalCount = 1
            }

            percent == last && before != null -> {
                equalCount += 1
            }

            percent < last -> {
                // 하락하면 이번 상승 구간을 취소하고 다음 상승을 기다린다.
                before = null
                equalCount = 0
            }
        }

        previous = percent
        val start = before ?: return null
        if (equalCount < stableReadings.coerceAtLeast(1)) return null

        before = null
        equalCount = 0
        return RefuelResult(start, percent)
    }
}

data class Report(
    val saidAtMs: Long,
    val text: String,
    val fuelPercent: Int?,
    val odometerKm: Float?,
    val telltales: Set<String>,
    val driverDoorOpen: Boolean?,
    val location: LocFix?,
    val receivedAtMs: Long?,
)

data class LateNotice(
    val etaMs: Long,
    val sent: Boolean,
)

data class ReturnCard(
    val createdMs: Long,
    val final: Boolean,
    val pickup: Pickup,
    val atReturn: Pickup,
    val refuel: RefuelResult?,
    val target: Int?,
    val late: LateNotice?,
    val reports: List<Report>,
)

sealed interface Effect {
    data class Say(val text: String) : Effect
    data class RouteTo(val place: NaviPlace) : Effect
    data object ShowPickup : Effect
    data class CardReady(val card: ReturnCard) : Effect
}

data class Prep(
    val targetPercent: Int,
    val nowPercent: Int?,
    val litersNeeded: Double?,
    val needFuel: Boolean,
    val travelMin: Int?,
    val departAtMs: Long?,
)

class RentalSession(
    private val contract: Contract,
    private val clock: () -> Long,
) {
    private var recordedPickup: Pickup? = null
    private var currentCard: ReturnCard? = null
    private val recordedReports = mutableListOf<Report>()
    private var pickupEconomy: Float? = null
    private var currentTarget: Int? = null
    private var lastRefuel: RefuelResult? = null
    private var lateNotice: LateNotice? = null
    private var lateAsked = false
    private var lateAnswered = false

    val pickup: Pickup?
        get() = recordedPickup

    val card: ReturnCard?
        get() = currentCard

    val reports: List<Report>
        get() = recordedReports.toList()

    fun onCarState(prev: CarState, now: CarState): List<Effect> {
        if (recordedPickup != null || now.driverSeated != true || now.gear != Gear.P) {
            return emptyList()
        }
        recordedPickup = snapshot(now, clock())
        pickupEconomy = now.fuelEconomyKmPerL
        currentTarget = now.fuelPercent?.let {
            FuelTarget.targetPercent(
                it,
                pickupEconomy,
                contract.stationToReturnKm,
                contract.tankLiters,
            )
        }
        return listOf(
            Effect.ShowPickup,
            Effect.Say("인수 때 상태를 기록했습니다"),
        )
    }

    fun prepare(now: CarState, travelMin: Int?): Prep? {
        val initialFuel = recordedPickup?.fuelPercent ?: return null
        val economy = now.fuelEconomyKmPerL
            ?.takeIf { it.isFinite() && it > 0f }
            ?: pickupEconomy
        val target = FuelTarget.targetPercent(
            initialFuel,
            economy,
            contract.stationToReturnKm,
            contract.tankLiters,
        )
        currentTarget = target
        val fuel = now.fuelPercent
        val needFuel = fuel != null && fuel < target
        return Prep(
            targetPercent = target,
            nowPercent = fuel,
            litersNeeded = fuel?.let {
                FuelTarget.litersNeeded(it, target, contract.tankLiters)
            },
            needFuel = needFuel,
            travelMin = travelMin,
            // 現在の燃料が不明な場合は出発時刻も確定しない。
            departAtMs = if (travelMin != null && fuel != null) {
                DepartureTime.compute(contract.returnAtMs, travelMin, needFuel)
            } else {
                null
            },
        )
    }

    fun onRefuel(result: RefuelResult): List<Effect> {
        if (recordedPickup == null) return emptyList()
        lastRefuel = result
        val target = currentTarget ?: return emptyList()
        val prefix = "주유 전 ${result.before}%, 지금 ${result.after}%."
        return if (result.after >= target) {
            listOf(
                Effect.Say("$prefix 목표에 맞았습니다"),
                Effect.RouteTo(contract.returnPlace),
            )
        } else {
            listOf(
                Effect.Say("$prefix 목표 $target%보다 낮습니다"),
            )
        }
    }

    fun onRemainMinutes(remainMin: Int, gear: Gear): List<Effect> {
        if (recordedPickup == null || currentCard?.final == true ||
            gear == Gear.P || lateAsked || remainMin < 0
        ) {
            return emptyList()
        }
        val eta = clock() + remainMin.toLong() * 60_000L
        if (eta <= contract.returnAtMs) return emptyList()

        lateAsked = true
        lateNotice = LateNotice(eta, sent = false)
        val delay = ceil((eta - contract.returnAtMs) / 60_000.0).toLong()
        return buildList {
            add(
                Effect.Say(
                    "반납이 약 ${delay}분 늦겠습니다. 업체에 도착 예정 시각을 보낼까요?",
                ),
            )
            contract.extraFeePer30Min?.let { fee ->
                val amount = NumberFormat.getIntegerInstance(Locale.KOREA).format(fee)
                add(Effect.Say("30분 연장은 ${amount}원입니다"))
            }
        }
    }

    fun answerLate(send: Boolean): List<Effect> {
        val notice = lateNotice ?: return emptyList()
        if (lateAnswered) return emptyList()
        lateAnswered = true
        lateNotice = notice.copy(sent = send)
        return if (send) {
            listOf(Effect.Say("도착 예정 시각을 보냈습니다"))
        } else {
            emptyList()
        }
    }

    fun report(text: String, now: CarState, location: LocFix?): Report {
        val report = Report(
            saidAtMs = clock(),
            text = text,
            fuelPercent = now.fuelPercent,
            odometerKm = now.odometerKm,
            telltales = now.telltales.toSet(),
            driverDoorOpen = now.driverDoorOpen,
            location = location,
            receivedAtMs = null,
        )
        recordedReports.add(report)
        return report
    }

    fun onArrive(placeName: String, now: CarState): List<Effect> {
        if (recordedPickup == null || now.gear != Gear.P ||
            placeName != contract.returnPlace.name || currentCard?.final == true
        ) {
            return emptyList()
        }
        val ready = makeCard(now, final = false) ?: return emptyList()
        return listOf(Effect.CardReady(ready))
    }

    fun finishReturn(now: CarState): List<Effect> {
        if (now.gear != Gear.P) return emptyList()
        val ready = makeCard(now, final = true) ?: return emptyList()
        return listOf(
            Effect.CardReady(ready),
            Effect.Say("반납 카드를 만들었습니다"),
        )
    }

    private fun makeCard(now: CarState, final: Boolean): ReturnCard? {
        val initial = recordedPickup ?: return null
        val time = clock()
        return ReturnCard(
            createdMs = time,
            final = final,
            pickup = initial,
            atReturn = snapshot(now, time),
            refuel = lastRefuel,
            target = currentTarget,
            late = lateNotice,
            reports = recordedReports.toList(),
        ).also { currentCard = it }
    }

    private fun snapshot(state: CarState, time: Long): Pickup = Pickup(
        timeMs = time,
        fuelPercent = state.fuelPercent,
        odometerKm = state.odometerKm,
        telltales = state.telltales.toSet(),
        allClosed = state.driverDoorOpen == false &&
            WindowPos.entries.all { state.windowOpen[it] == false },
    )
}
