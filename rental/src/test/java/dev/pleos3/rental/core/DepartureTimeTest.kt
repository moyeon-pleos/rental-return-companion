package dev.pleos3.rental.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DepartureTimeTest {
    private val minuteMs = 60_000L
    private val returnAtMs = 990L * minuteMs

    @Test
    fun `주유가 필요하면 이동과 주유 15분과 여유 10분을 뺀다`() {
        assertEquals(
            925L * minuteMs,
            DepartureTime.compute(
                returnAtMs = returnAtMs,
                travelMin = 40,
                needFuel = true,
            ),
        )
    }

    @Test
    fun `주유가 필요 없으면 이동과 여유 10분만 뺀다`() {
        assertEquals(
            940L * minuteMs,
            DepartureTime.compute(
                returnAtMs = returnAtMs,
                travelMin = 40,
                needFuel = false,
            ),
        )
    }

    @Test
    fun `주유 시간과 여유 시간을 지정할 수 있다`() {
        assertEquals(
            returnAtMs - 67L * minuteMs,
            DepartureTime.compute(
                returnAtMs = returnAtMs,
                travelMin = 40,
                needFuel = true,
                fuelMin = 20,
                bufferMin = 7,
            ),
        )
    }

    @Test
    fun `주유가 필요 없으면 지정한 주유 시간도 더하지 않는다`() {
        assertEquals(
            returnAtMs - 7L * minuteMs,
            DepartureTime.compute(
                returnAtMs = returnAtMs,
                travelMin = 0,
                needFuel = false,
                fuelMin = 20,
                bufferMin = 7,
            ),
        )
    }
}
