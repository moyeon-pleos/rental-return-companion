package dev.pleos3.rental.core

import dev.pleos3.car.CarState
import dev.pleos3.car.Gear
import dev.pleos3.car.LocFix
import dev.pleos3.car.NaviPlace
import dev.pleos3.car.WindowPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RentalSessionTest {
    private val minuteMs = 60_000L

    private fun contract(
        returnAtMs: Long = 200L * minuteMs,
        extraFeePer30Min: Int? = 5_000,
    ): Contract = Contract(
        returnAtMs = returnAtMs,
        returnPlace = NaviPlace(
            name = "○○렌터카 공항점",
            address = "공항로 1",
            lat = 33.5,
            lon = 126.5,
        ),
        openHour = 6,
        closeHour = 22,
        station = NaviPlace(
            name = "공항 주유소",
            address = "주유로 2",
            lat = 33.51,
            lon = 126.51,
        ),
        stationToReturnKm = 6.0,
        tankLiters = 60.0,
        extraFeePer30Min = extraFeePer30Min,
    )

    private fun parked(fuel: Int? = 78): CarState = CarState(
        gear = Gear.P,
        driverSeated = true,
        driverDoorOpen = false,
        doorsLocked = false,
        windowOpen = WindowPos.entries.associateWith { false },
        fuelPercent = fuel,
        fuelEconomyKmPerL = 12f,
        odometerKm = 12_480f,
    )

    private fun recordPickup(
        session: RentalSession,
        state: CarState = parked(),
    ) {
        session.onCarState(CarState(), state)
    }

    private fun readyCard(effects: List<Effect>): ReturnCard =
        effects.filterIsInstance<Effect.CardReady>().single().card

    @Test
    fun `처음에는 인수 기록과 카드와 신고가 없다`() {
        var now = 0L
        val session = RentalSession(contract()) { now }

        assertNull(session.pickup)
        assertNull(session.card)
        assertTrue(session.reports.isEmpty())
    }

    @Test
    fun `운전석 착석과 P가 함께 충족될 때만 인수를 기록한다`() {
        var now = 10L
        val session = RentalSession(contract()) { now }

        for (seated in listOf<Boolean?>(null, false)) {
            assertTrue(
                session.onCarState(
                    CarState(),
                    parked().copy(driverSeated = seated),
                ).isEmpty(),
            )
            assertNull(session.pickup)
        }

        for (gear in Gear.entries.filter { it != Gear.P }) {
            assertTrue(
                session.onCarState(
                    CarState(),
                    parked().copy(gear = gear),
                ).isEmpty(),
            )
            assertNull(session.pickup)
        }

        now = 20L
        assertEquals(
            listOf(
                Effect.ShowPickup,
                Effect.Say("인수 때 상태를 기록했습니다"),
            ),
            session.onCarState(
                parked().copy(gear = Gear.D),
                parked(),
            ),
        )
        assertEquals(20L, requireNotNull(session.pickup).timeMs)
    }

    @Test
    fun `첫 입력부터 착석과 P이면 이전 상태가 같아도 인수한다`() {
        var now = 30L
        val session = RentalSession(contract()) { now }
        val state = parked()

        assertEquals(
            listOf(
                Effect.ShowPickup,
                Effect.Say("인수 때 상태를 기록했습니다"),
            ),
            session.onCarState(state, state),
        )
        assertEquals(30L, requireNotNull(session.pickup).timeMs)
    }

    @Test
    fun `인수 시각과 차량 값을 한 번 기록하고 이후 변경하지 않는다`() {
        var now = 100L
        val session = RentalSession(contract()) { now }
        val initial = parked().copy(telltales = setOf("TPMS"))

        recordPickup(session, initial)
        val expected = Pickup(
            timeMs = 100L,
            fuelPercent = 78,
            odometerKm = 12_480f,
            telltales = setOf("TPMS"),
            allClosed = true,
        )
        assertEquals(expected, session.pickup)

        now = 200L
        val changed = initial.copy(
            fuelPercent = 41,
            odometerKm = 12_520f,
            telltales = setOf("CHECK_ENGINE"),
            driverDoorOpen = true,
        )
        assertTrue(session.onCarState(initial, changed).isEmpty())

        val away = changed.copy(gear = Gear.D, driverSeated = false)
        assertTrue(session.onCarState(changed, away).isEmpty())
        now = 300L
        assertTrue(session.onCarState(away, parked(90)).isEmpty())
        assertEquals(expected, session.pickup)
    }

    @Test
    fun `문 또는 어느 창문이든 열려 있으면 모두 닫힘이 아니다`() {
        var now = 0L
        val openStates = listOf(
            parked().copy(driverDoorOpen = true),
        ) + WindowPos.entries.map { window ->
            parked().copy(
                windowOpen = WindowPos.entries.associateWith { it == window },
            )
        }

        for (state in openStates) {
            val session = RentalSession(contract()) { now }
            recordPickup(session, state)
            assertFalse(requireNotNull(session.pickup).allClosed)
        }
    }

    @Test
    fun `인수의 알 수 없는 연료와 주행거리를 그대로 보존한다`() {
        var now = 7L
        val session = RentalSession(contract()) { now }

        recordPickup(
            session,
            parked(fuel = null).copy(odometerKm = null),
        )

        val pickup = requireNotNull(session.pickup)
        assertNull(pickup.fuelPercent)
        assertNull(pickup.odometerKm)
        assertEquals(7L, pickup.timeMs)
    }

    @Test
    fun `인수 전에는 준비 값을 만들지 않는다`() {
        var now = 0L
        val session = RentalSession(contract()) { now }

        assertNull(session.prepare(parked(41), travelMin = 40))
    }

    @Test
    fun `준비는 목표와 현재 연료와 필요량과 주유 포함 출발 시각을 계산한다`() {
        var now = 0L
        val rentalContract = contract(returnAtMs = 990L * minuteMs)
        val session = RentalSession(rentalContract) { now }
        recordPickup(session)

        val prep = requireNotNull(session.prepare(parked(41), travelMin = 40))

        assertEquals(79, prep.targetPercent)
        assertEquals(41, prep.nowPercent)
        assertEquals(22.8, requireNotNull(prep.litersNeeded), 0.000001)
        assertTrue(prep.needFuel)
        assertEquals(40, prep.travelMin)
        assertEquals(925L * minuteMs, prep.departAtMs)
        assertEquals(78, requireNotNull(session.pickup).fuelPercent)
    }

    @Test
    fun `현재 연료가 목표와 같거나 높으면 주유 시간을 빼지 않는다`() {
        var now = 0L
        val rentalContract = contract(returnAtMs = 990L * minuteMs)
        val session = RentalSession(rentalContract) { now }
        recordPickup(session)

        for (fuel in listOf(79, 85)) {
            val prep = requireNotNull(session.prepare(parked(fuel), travelMin = 40))

            assertEquals(79, prep.targetPercent)
            assertEquals(fuel, prep.nowPercent)
            assertFalse(prep.needFuel)
            assertEquals(0.0, requireNotNull(prep.litersNeeded), 0.000001)
            assertEquals(940L * minuteMs, prep.departAtMs)
        }
    }

    @Test
    fun `이동 시간이 없어도 준비 값을 주고 출발 시각은 비워 둔다`() {
        var now = 0L
        val session = RentalSession(contract()) { now }
        recordPickup(session)

        val prep = requireNotNull(session.prepare(parked(41), travelMin = null))

        assertEquals(79, prep.targetPercent)
        assertEquals(41, prep.nowPercent)
        assertEquals(22.8, requireNotNull(prep.litersNeeded), 0.000001)
        assertTrue(prep.needFuel)
        assertNull(prep.travelMin)
        assertNull(prep.departAtMs)
    }

    @Test
    fun `목표 이상으로 주유하면 확인 문장과 반납지 안내를 낸다`() {
        var now = 0L
        val rentalContract = contract()
        val session = RentalSession(rentalContract) { now }
        recordPickup(session)
        session.prepare(parked(41), travelMin = 40)

        for (after in listOf(79, 81)) {
            assertEquals(
                listOf(
                    Effect.Say("주유 전 41%, 지금 $after%. 목표에 맞았습니다"),
                    Effect.RouteTo(rentalContract.returnPlace),
                ),
                session.onRefuel(RefuelResult(41, after)),
            )
        }
    }

    @Test
    fun `목표보다 덜 주유하면 부족 문장만 낸다`() {
        var now = 0L
        val session = RentalSession(contract()) { now }
        recordPickup(session)
        session.prepare(parked(41), travelMin = 40)

        assertEquals(
            listOf(
                Effect.Say("주유 전 41%, 지금 78%. 목표 79%보다 낮습니다"),
            ),
            session.onRefuel(RefuelResult(41, 78)),
        )
    }

    @Test
    fun `P에서는 지연을 묻지 않고 도착 예정이 반납 시각 이하여도 묻지 않는다`() {
        var now = 160L * minuteMs
        val session = RentalSession(contract()) { now }
        recordPickup(session)

        assertTrue(session.onRemainMinutes(45, Gear.P).isEmpty())
        assertTrue(session.onRemainMinutes(39, Gear.D).isEmpty())
        assertTrue(session.onRemainMinutes(40, Gear.D).isEmpty())

        assertEquals(
            listOf(
                Effect.Say("반납이 약 5분 늦겠습니다. 업체에 도착 예정 시각을 보낼까요?"),
                Effect.Say("30분 연장은 5,000원입니다"),
            ),
            session.onRemainMinutes(45, Gear.D),
        )
    }

    @Test
    fun `P가 아닌 모든 기어에서 지연을 알릴 수 있다`() {
        var now = 160L * minuteMs

        for (gear in Gear.entries.filter { it != Gear.P }) {
            val session = RentalSession(contract(extraFeePer30Min = null)) { now }
            recordPickup(session)

            assertEquals(
                listOf(
                    Effect.Say("반납이 약 5분 늦겠습니다. 업체에 도착 예정 시각을 보낼까요?"),
                ),
                session.onRemainMinutes(45, gear),
            )
        }
    }

    @Test
    fun `지연 물음은 같은 대여에서 한 번만 낸다`() {
        var now = 160L * minuteMs
        val session = RentalSession(contract()) { now }
        recordPickup(session)

        assertEquals(2, session.onRemainMinutes(45, Gear.D).size)
        assertTrue(session.onRemainMinutes(60, Gear.D).isEmpty())
        assertTrue(session.onRemainMinutes(1, Gear.P).isEmpty())

        now = 170L * minuteMs
        assertTrue(session.onRemainMinutes(60, Gear.R).isEmpty())
    }

    @Test
    fun `요금표가 있으면 전달된 금액을 문장에 넣는다`() {
        var now = 160L * minuteMs
        val session = RentalSession(contract(extraFeePer30Min = 12_000)) { now }
        recordPickup(session)

        assertEquals(
            listOf(
                Effect.Say("반납이 약 5분 늦겠습니다. 업체에 도착 예정 시각을 보낼까요?"),
                Effect.Say("30분 연장은 12,000원입니다"),
            ),
            session.onRemainMinutes(45, Gear.D),
        )
    }

    @Test
    fun `지연을 물었어도 동의 전에는 보낸 것으로 기록하지 않는다`() {
        var now = 160L * minuteMs
        val rentalContract = contract()
        val session = RentalSession(rentalContract) { now }
        recordPickup(session)

        session.onRemainMinutes(45, Gear.D)
        val card = readyCard(
            session.onArrive(rentalContract.returnPlace.name, parked()),
        )

        assertEquals(LateNotice(205L * minuteMs, sent = false), card.late)
    }

    @Test
    fun `동의할 때만 보내고 물었던 도착 예정 시각을 보존한다`() {
        var now = 160L * minuteMs
        val rentalContract = contract()
        val session = RentalSession(rentalContract) { now }
        recordPickup(session)
        session.onRemainMinutes(45, Gear.D)

        now = 165L * minuteMs
        assertEquals(
            listOf(Effect.Say("도착 예정 시각을 보냈습니다")),
            session.answerLate(send = true),
        )

        val card = readyCard(
            session.onArrive(rentalContract.returnPlace.name, parked()),
        )
        assertEquals(LateNotice(205L * minuteMs, sent = true), card.late)
        assertTrue(session.onRemainMinutes(60, Gear.D).isEmpty())
    }

    @Test
    fun `전송을 거절하면 말하지 않고 미전송으로 기록하며 다시 묻지 않는다`() {
        var now = 160L * minuteMs
        val rentalContract = contract()
        val session = RentalSession(rentalContract) { now }
        recordPickup(session)
        session.onRemainMinutes(45, Gear.D)

        assertTrue(session.answerLate(send = false).isEmpty())

        val card = readyCard(
            session.onArrive(rentalContract.returnPlace.name, parked()),
        )
        assertEquals(LateNotice(205L * minuteMs, sent = false), card.late)
        assertTrue(session.onRemainMinutes(60, Gear.D).isEmpty())
    }

    @Test
    fun `신고는 말한 순간의 시각과 차량 값과 위치를 묶어 순서대로 쌓는다`() {
        var now = 100L
        val session = RentalSession(contract()) { now }
        recordPickup(session)

        val location = LocFix(
            lat = 33.5,
            lon = 126.5,
            confidence = 0.9f,
            timeMs = 150L,
        )
        val firstState = parked(41).copy(
            odometerKm = 12_520f,
            telltales = setOf("TPMS", "CHECK_ENGINE"),
            driverDoorOpen = true,
        )

        now = 200L
        val first = session.report(
            text = "주차해 둔 사이 뒤 범퍼 오른쪽이 긁혔어요",
            now = firstState,
            location = location,
        )
        assertEquals(
            Report(
                saidAtMs = 200L,
                text = "주차해 둔 사이 뒤 범퍼 오른쪽이 긁혔어요",
                fuelPercent = 41,
                odometerKm = 12_520f,
                telltales = setOf("TPMS", "CHECK_ENGINE"),
                driverDoorOpen = true,
                location = location,
                receivedAtMs = null,
            ),
            first,
        )

        now = 300L
        val second = session.report(
            text = "추가로 확인했어요",
            now = parked(fuel = null).copy(
                odometerKm = null,
                driverDoorOpen = null,
            ),
            location = null,
        )
        assertEquals(
            Report(
                saidAtMs = 300L,
                text = "추가로 확인했어요",
                fuelPercent = null,
                odometerKm = null,
                telltales = emptySet(),
                driverDoorOpen = null,
                location = null,
                receivedAtMs = null,
            ),
            second,
        )

        assertEquals(listOf(first, second), session.reports)
        assertEquals(200L, first.saidAtMs)
        assertNull(first.receivedAtMs)
        assertNull(second.receivedAtMs)
    }

    @Test
    fun `인수 전에는 도착 카드와 확정 카드를 만들지 않는다`() {
        var now = 0L
        val rentalContract = contract()
        val session = RentalSession(rentalContract) { now }

        assertTrue(
            session.onArrive(rentalContract.returnPlace.name, parked()).isEmpty(),
        )
        assertTrue(session.finishReturn(parked()).isEmpty())
        assertNull(session.card)
    }

    @Test
    fun `반납지 이름이 다르거나 P가 아니면 도착 카드를 만들지 않는다`() {
        var now = 0L
        val rentalContract = contract()
        val session = RentalSession(rentalContract) { now }
        recordPickup(session)

        for (name in listOf(
            rentalContract.station.name,
            "${rentalContract.returnPlace.name} 입구",
            "",
        )) {
            assertTrue(session.onArrive(name, parked()).isEmpty())
        }

        for (gear in Gear.entries.filter { it != Gear.P }) {
            assertTrue(
                session.onArrive(
                    rentalContract.returnPlace.name,
                    parked().copy(gear = gear),
                ).isEmpty(),
            )
        }

        assertNull(session.card)
    }

    @Test
    fun `인수 뒤라도 P가 아니면 반납을 확정하지 않는다`() {
        var now = 0L
        val session = RentalSession(contract()) { now }
        recordPickup(session)

        for (gear in Gear.entries.filter { it != Gear.P }) {
            assertTrue(session.finishReturn(parked().copy(gear = gear)).isEmpty())
        }

        assertNull(session.card)
    }

    @Test
    fun `반납지에 P로 도착하면 그 순간 값으로 임시 카드를 만든다`() {
        var now = 10L
        val rentalContract = contract()
        val session = RentalSession(rentalContract) { now }
        recordPickup(session)
        val pickup = requireNotNull(session.pickup)

        now = 20L
        val atReturn = parked(79).copy(
            odometerKm = 12_530f,
            telltales = setOf("TPMS"),
        )
        val effects = session.onArrive(rentalContract.returnPlace.name, atReturn)
        val card = readyCard(effects)

        assertEquals(listOf(Effect.CardReady(card)), effects)
        assertEquals(card, session.card)
        assertFalse(card.final)
        assertEquals(20L, card.createdMs)
        assertEquals(pickup, card.pickup)
        assertEquals(
            Pickup(
                timeMs = 20L,
                fuelPercent = 79,
                odometerKm = 12_530f,
                telltales = setOf("TPMS"),
                allClosed = true,
            ),
            card.atReturn,
        )
        assertNull(card.refuel)
        assertNull(card.late)
        assertTrue(card.reports.isEmpty())
    }

    @Test
    fun `도착 알림 없이도 인수 뒤 P에서 확정 카드를 만들 수 있다`() {
        var now = 10L
        val session = RentalSession(contract()) { now }
        recordPickup(session)

        now = 30L
        val effects = session.finishReturn(
            parked(80).copy(odometerKm = 12_536f),
        )
        val card = readyCard(effects)

        assertEquals(
            listOf(
                Effect.CardReady(card),
                Effect.Say("반납 카드를 만들었습니다"),
            ),
            effects,
        )
        assertTrue(card.final)
        assertEquals(30L, card.createdMs)
        assertEquals(30L, card.atReturn.timeMs)
        assertEquals(80, card.atReturn.fuelPercent)
        assertEquals(12_536f, card.atReturn.odometerKm)
        assertEquals(card, session.card)
    }

    @Test
    fun `확정은 당시 값으로 갱신하고 인수와 주유와 목표와 지연과 신고를 보존한다`() {
        var now = 10L
        val rentalContract = contract()
        val session = RentalSession(rentalContract) { now }
        recordPickup(session)
        val pickup = requireNotNull(session.pickup)

        session.prepare(parked(41), travelMin = 40)
        val refuel = RefuelResult(41, 81)
        session.onRefuel(refuel)

        now = 160L * minuteMs
        session.onRemainMinutes(45, Gear.D)
        session.answerLate(send = true)
        val late = LateNotice(205L * minuteMs, sent = true)

        now = 180L * minuteMs
        val report = session.report(
            text = "뒤 범퍼에 긁힘이 있어요",
            now = parked(81),
            location = null,
        )

        now = 205L * minuteMs
        val temporary = readyCard(
            session.onArrive(
                rentalContract.returnPlace.name,
                parked(80).copy(odometerKm = 12_530f),
            ),
        )
        assertFalse(temporary.final)
        assertEquals(205L * minuteMs, temporary.createdMs)
        assertEquals(80, temporary.atReturn.fuelPercent)
        assertEquals(79, temporary.target)
        assertEquals(refuel, temporary.refuel)
        assertEquals(late, temporary.late)
        assertEquals(listOf(report), temporary.reports)

        now = 207L * minuteMs
        val finalState = parked(79).copy(
            odometerKm = 12_536f,
            telltales = setOf("CHECK_ENGINE"),
            driverDoorOpen = true,
        )
        val effects = session.finishReturn(finalState)
        val finalCard = readyCard(effects)

        assertEquals(
            listOf(
                Effect.CardReady(finalCard),
                Effect.Say("반납 카드를 만들었습니다"),
            ),
            effects,
        )
        assertTrue(finalCard.final)
        assertEquals(207L * minuteMs, finalCard.createdMs)
        assertEquals(
            Pickup(
                timeMs = 207L * minuteMs,
                fuelPercent = 79,
                odometerKm = 12_536f,
                telltales = setOf("CHECK_ENGINE"),
                allClosed = false,
            ),
            finalCard.atReturn,
        )
        assertEquals(pickup, finalCard.pickup)
        assertEquals(pickup, session.pickup)
        assertEquals(refuel, finalCard.refuel)
        assertEquals(79, finalCard.target)
        assertEquals(late, finalCard.late)
        assertEquals(listOf(report), finalCard.reports)
        assertEquals(finalCard, session.card)

        assertFalse(temporary.final)
        assertEquals(205L * minuteMs, temporary.createdMs)
        assertEquals(80, temporary.atReturn.fuelPercent)
    }
}
