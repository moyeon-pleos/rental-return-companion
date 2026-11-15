package dev.pleos3.rental.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefuelWatcherTest {
    @Test
    fun `오른 뒤 같은 값이 세 번 이어져야 결과를 낸다`() {
        val watcher = RefuelWatcher()

        assertNull(watcher.onFuel(41))
        assertNull(watcher.onFuel(60))
        assertNull(watcher.onFuel(81))
        assertNull(watcher.onFuel(81))
        assertEquals(RefuelResult(41, 81), watcher.onFuel(81))
    }

    @Test
    fun `오르지 않은 채 같은 값이 이어져도 결과를 내지 않는다`() {
        val watcher = RefuelWatcher()

        repeat(6) {
            assertNull(watcher.onFuel(41))
        }
        assertNull(watcher.onFuel(40))
        repeat(6) {
            assertNull(watcher.onFuel(40))
        }
    }

    @Test
    fun `주유 전 값은 최초 관측값이 아니라 오르기 직전 값이다`() {
        val watcher = RefuelWatcher()

        assertNull(watcher.onFuel(41))
        assertNull(watcher.onFuel(39))
        assertNull(watcher.onFuel(38))
        assertNull(watcher.onFuel(60))
        assertNull(watcher.onFuel(60))
        assertEquals(RefuelResult(38, 60), watcher.onFuel(60))
    }

    @Test
    fun `멈추기 전에 다시 오르면 같은 값의 횟수를 다시 센다`() {
        val watcher = RefuelWatcher()

        assertNull(watcher.onFuel(40))
        assertNull(watcher.onFuel(50))
        assertNull(watcher.onFuel(50))
        assertNull(watcher.onFuel(55))
        assertNull(watcher.onFuel(55))
        assertEquals(RefuelResult(40, 55), watcher.onFuel(55))
    }

    @Test
    fun `결과를 한 번 낸 뒤 같은 값이 이어져도 다시 내지 않는다`() {
        val watcher = RefuelWatcher()

        assertNull(watcher.onFuel(41))
        assertNull(watcher.onFuel(81))
        assertNull(watcher.onFuel(81))
        assertEquals(RefuelResult(41, 81), watcher.onFuel(81))

        repeat(6) {
            assertNull(watcher.onFuel(81))
        }
    }

    @Test
    fun `한 감시기로 두 번의 주유를 각각 감지한다`() {
        val watcher = RefuelWatcher()

        assertNull(watcher.onFuel(41))
        assertNull(watcher.onFuel(81))
        assertNull(watcher.onFuel(81))
        assertEquals(RefuelResult(41, 81), watcher.onFuel(81))

        assertNull(watcher.onFuel(70))
        assertNull(watcher.onFuel(72))
        assertNull(watcher.onFuel(90))
        assertNull(watcher.onFuel(90))
        assertEquals(RefuelResult(70, 90), watcher.onFuel(90))
    }

    @Test
    fun `설정한 안정 관측 횟수를 사용한다`() {
        val watcher = RefuelWatcher(stableReadings = 2)

        assertNull(watcher.onFuel(30))
        assertNull(watcher.onFuel(45))
        assertEquals(RefuelResult(30, 45), watcher.onFuel(45))
    }
}
