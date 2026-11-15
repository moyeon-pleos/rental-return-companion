package dev.pleos3.rental

import dev.pleos3.car.NaviPlace
import dev.pleos3.rental.core.Contract
import java.time.LocalDateTime
import java.time.ZoneId

object SampleContract {
    val value = Contract(
        returnAtMs = LocalDateTime.of(2026, 11, 22, 16, 30)
            .atZone(ZoneId.systemDefault())  // 디버그 now 와 같은 시간대(기기 시간대)로 맞춘다
            .toInstant()
            .toEpochMilli(),
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
        extraFeePer30Min = 5_000,
    )
}
