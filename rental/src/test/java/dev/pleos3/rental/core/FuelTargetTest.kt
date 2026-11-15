package dev.pleos3.rental.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FuelTargetTest {
    @Test
    fun `예시 인수 연료와 이동 거리로 목표 79퍼센트를 계산한다`() {
        assertEquals(
            79,
            FuelTarget.targetPercent(
                pickupPercent = 78,
                economyKmPerL = 12f,
                stationToReturnKm = 6.0,
                tankLiters = 60.0,
            ),
        )
    }

    @Test
    fun `연비가 없거나 0 이하이면 12를 사용한다`() {
        for (economy in listOf<Float?>(null, 0f, -5f)) {
            assertEquals(
                "연비 $economy",
                79,
                FuelTarget.targetPercent(78, economy, 6.0, 60.0),
            )
        }
    }

    @Test
    fun `유효한 연비를 사용하고 소모 퍼센트를 올림한다`() {
        assertEquals(80, FuelTarget.targetPercent(78, 6f, 6.0, 60.0))
        assertEquals(81, FuelTarget.targetPercent(78, 12f, 15.0, 60.0))
    }

    @Test
    fun `소모 퍼센트가 정수이면 추가로 올리지 않는다`() {
        assertEquals(80, FuelTarget.targetPercent(78, 10f, 12.0, 60.0))
    }

    @Test
    fun `이동 거리가 0이면 인수 퍼센트가 목표다`() {
        assertEquals(78, FuelTarget.targetPercent(78, 12f, 0.0, 60.0))
    }

    @Test
    fun `목표는 100퍼센트를 넘지 않는다`() {
        assertEquals(100, FuelTarget.targetPercent(99, 12f, 12.0, 60.0))
        assertEquals(100, FuelTarget.targetPercent(100, 12f, 6.0, 60.0))
    }

    @Test
    fun `41에서 80까지 60리터 탱크에 필요한 양은 23점4리터다`() {
        assertEquals(23.4, FuelTarget.litersNeeded(41, 80, 60.0), 0.000001)
    }

    @Test
    fun `현재 연료가 목표 이상이면 필요한 양은 0이다`() {
        assertEquals(0.0, FuelTarget.litersNeeded(80, 80, 60.0), 0.000001)
        assertEquals(0.0, FuelTarget.litersNeeded(90, 80, 60.0), 0.000001)
    }

    @Test
    fun `필요한 양은 전달한 탱크 용량을 사용한다`() {
        assertEquals(19.5, FuelTarget.litersNeeded(41, 80, 50.0), 0.000001)
    }
}
