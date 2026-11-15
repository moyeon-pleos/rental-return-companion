package dev.pleos3.rental.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pleos3.car.CarState
import dev.pleos3.car.Dictation
import dev.pleos3.car.DictationState
import dev.pleos3.car.Gear
import dev.pleos3.car.WindowPos
import dev.pleos3.rental.RentalScreen
import dev.pleos3.rental.RentalUiState
import dev.pleos3.rental.core.Pickup
import dev.pleos3.rental.core.Report
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Background = Color(0xFFF4F6F5)
private val Navy = Color(0xFF1F3A5F)
private val Muted = Color(0xFF52616E)
private val Soft = Color(0xFFE7EDF2)
private val Seoul = ZoneId.systemDefault()

@Composable
fun RentalApp(state: RentalUiState, onAction: (String) -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Navy,
            onPrimary = Color.White,
            background = Background,
            onBackground = Navy,
            surface = Color.White,
            onSurface = Navy,
            secondaryContainer = Soft,
            onSecondaryContainer = Navy,
        ),
        typography = Typography(
            bodyLarge = androidx.compose.ui.text.TextStyle(
                fontSize = 22.sp,
                lineHeight = 29.sp,
            ),
            bodyMedium = androidx.compose.ui.text.TextStyle(
                fontSize = 22.sp,
                lineHeight = 29.sp,
            ),
            labelLarge = androidx.compose.ui.text.TextStyle(
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold,
            ),
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            // 주행 잠금은 모든 화면보다 먼저 적용한다.
            if (state.car.gear != Gear.P) {
                DrivingContent()
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Header(state)
                    when (state.screen) {
                        RentalScreen.WAITING -> WaitingContent()
                        RentalScreen.HOME -> HomeContent(state, onAction)
                        RentalScreen.PREP -> PrepContent(state, onAction)
                        RentalScreen.REPORT -> ReportContent(state, onAction)
                        RentalScreen.CARD -> CardContent(state, onAction)
                        RentalScreen.LATE -> LateContent(state, onAction)
                        RentalScreen.DRIVING -> WaitingContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(state: RentalUiState) {
    val suffix = when (state.screen) {
        RentalScreen.PREP -> " · 반납 준비"
        RentalScreen.REPORT -> " · 말로 신고"
        RentalScreen.CARD -> " · 반납 카드"
        RentalScreen.LATE -> " · 지연 알림"
        else -> ""
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "반납 동반자$suffix",
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            color = Navy,
        )
        Surface(
            color = Soft,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = "P · ${time(state.nowMs)}",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                fontSize = 22.sp,
                color = Navy,
            )
        }
    }
}

@Composable
private fun DrivingContent() {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(
                modifier = Modifier.padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = "운행 중 — 소리로만 안내합니다",
                    fontSize = 40.sp,
                    lineHeight = 49.sp,
                    fontWeight = FontWeight.Bold,
                    color = Navy,
                )
                Text(
                    text = "화면은 기어가 P일 때 사용할 수 있습니다.",
                    fontSize = 22.sp,
                    color = Muted,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.WaitingContent() {
    Box(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Title("운전석에 앉으시면\n인수 때 상태를 기록합니다", lines = 2)
            Body("예시 계약이 연결되어 있습니다. 처음 착석한 P 상태를 한 번 기록합니다.")
        }
    }
}

@Composable
private fun ColumnScope.HomeContent(
    state: RentalUiState,
    onAction: (String) -> Unit,
) {
    val pickup = state.pickup ?: return
    Title("인수 때 상태를 기록했습니다")
    Body("반납 날, 이 기록을 기준으로 주유 목표와 출발 시각을 알려 드립니다.")

    Row(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel(modifier = Modifier.weight(1.5f).fillMaxHeight()) {
            SectionTitle("반납")
            Body(date(state.contract.returnAtMs, "M월 d일(E) HH:mm"), bold = true)
            Body("${state.contract.returnPlace.name} · 영업 ${hours(state)}")
            HorizontalDivider()
            SectionTitle("인수 때 상태 · 차가 자동으로 기록했습니다")
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Body("연료 ${percent(pickup.fuelPercent)}(눈금)")
                    Body("주행거리 ${odometer(pickup.odometerKm)}")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Body("경고등 ${warnings(pickup.telltales)}", lines = 2)
                    Body(closed(pickup))
                }
            }
            HorizontalDivider()
            Body("반납 카드 · 차 안에서 미리보기")
            Body("카드와 신고는 이 앱의 메모리에만 저장합니다.", muted = true)
        }
        Panel(modifier = Modifier.weight(1f).fillMaxHeight()) {
            SectionTitle("이번 여행에서 이 차가 하는 일")
            Job("1", "«여기까지 넣으세요»", "인수 때 연료를 기준으로")
            Job("2", "«지금 출발하세요»", "반납 시각에서 거꾸로 셈")
            Job("3", "말로 신고", "말씀과 그 순간 차 상태 기록")
            Body("늦을 때는 «보내 줘»라고 답할 때만 도착 예정을 기록합니다.", lines = 2)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Action("반납 준비", "prep", onAction, Modifier.weight(1f))
        Action("신고", "report", onAction, Modifier.weight(1f), primary = false)
        Action("반납 마치기", "finish_return", onAction, Modifier.weight(1f), primary = false)
    }
    Body("인수 기록은 한 번만 남기며 뒤에 덮어쓰지 않습니다. 시연용 · 서버 없음", muted = true)
}

@Composable
private fun ColumnScope.PrepContent(
    state: RentalUiState,
    onAction: (String) -> Unit,
) {
    val prep = state.prep
    val depart = prep?.departAtMs
    Title(if (depart != null) "${time(depart)}에는 출발하세요" else "반납 준비를 확인하세요")
    Body("${time(state.contract.returnAtMs)} 반납 · ${state.contract.returnPlace.name} · 영업 ${hours(state)}")

    Row(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel(modifier = Modifier.weight(1f).fillMaxHeight()) {
            SectionTitle("출발 시각은 이렇게 셉니다")
            ValueRow("반납 시각", time(state.contract.returnAtMs))
            ValueRow("반납지까지 이동", prep?.travelMin?.let { "− ${it}분" } ?: "내비 목적지 설정 필요")
            ValueRow(
                "주유",
                when {
                    prep?.nowPercent == null -> "현재 연료 확인 필요"
                    prep.needFuel -> "− 15분"
                    else -> "필요 없음"
                },
            )
            ValueRow("여유", "− 10분")
            HorizontalDivider()
            ValueRow("출발", depart?.let { time(it) } ?: "확인 대기", bold = true)
            Body("이동 시간은 현재 내비 목적지 기준입니다.", muted = true)
            if (prep?.travelMin == null) {
                Body("반납지를 목적지로 넣어 주세요.", bold = true)
            }
        }
        Panel(modifier = Modifier.weight(1f).fillMaxHeight()) {
            SectionTitle("연료 목표 — 인수 때와 같게")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FuelNumber("인수 때", state.pickup?.fuelPercent)
                FuelNumber("지금", state.car.fuelPercent)
                FuelNumber("목표", prep?.targetPercent)
            }
            LinearProgressIndicator(
                progress = { (state.car.fuelPercent ?: 0).coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth().height(12.dp),
                color = Navy,
                trackColor = Soft,
            )
            Body(
                when {
                    prep == null -> "인수 때 연료를 확인할 수 없습니다."
                    prep.nowPercent == null -> "현재 연료를 확인해 주세요."
                    prep.needFuel -> "${prep.targetPercent}%까지 넣으세요"
                    else -> "현재 연료가 목표에 맞습니다"
                },
                bold = true,
                lines = 2,
            )
            Body(
                prep?.litersNeeded?.let { "약 ${decimal(it)} L · 참고" }
                    ?: "필요량 확인 대기",
            )
            Body(
                "인수 때 연료에 주유소에서 반납지까지 쓸 양을 더했습니다. 차 눈금 기준 참고값입니다.",
                muted = true,
                lines = 3,
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Action("주유소 거쳐 반납지로 안내", "route_station", onAction, Modifier.weight(1.5f))
        Action("반납지를 목적지로", "route_return", onAction, Modifier.weight(1.2f), primary = false)
        Action("나중에", "home", onAction, Modifier.weight(0.6f), primary = false)
    }
    Body("달리는 동안에는 소리로만 안내합니다. 지연 알림은 답하기 전까지 보내지 않습니다.", muted = true)
}

@Composable
private fun ColumnScope.ReportContent(
    state: RentalUiState,
    onAction: (String) -> Unit,
) {
    val report = state.reports.lastOrNull()
    val dictation = state.dictation
    Title(
        when (dictation) {
            DictationState.Idle ->
                if (report == null) "말로 신고하세요" else "접수 대기(시연용, 서버 없음)"
            is DictationState.Listening -> "말로 신고하세요"
            is DictationState.Heard -> "신고할 내용을 확인하세요"
            is DictationState.Failed -> "다시 말씀해 주세요"
        },
    )
    Body(
        if (dictation != DictationState.Idle) {
            "내용을 확인한 뒤 «이대로 신고»를 누르면 기록합니다."
        } else if (report == null) {
            "말씀하신 내용과 그 순간의 차 상태를 함께 기록합니다."
        } else {
            "말씀하신 내용과 그 순간의 차 상태를 앱에 기록했습니다."
        },
    )

    Row(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel(modifier = Modifier.weight(1.6f).fillMaxHeight()) {
            when (dictation) {
                DictationState.Idle -> {
                    SectionTitle(
                        report?.let { "말씀하신 내용 · ${date(it.saidAtMs, "HH:mm:ss")}" }
                            ?: "말씀하신 내용",
                    )
                    Body(
                        report?.let { "«${it.text}»" }
                            ?: "«말하기»를 누르거나 예시 문장으로 신고 내용을 확인하세요.",
                        bold = true,
                        lines = 2,
                    )
                    HorizontalDivider()
                    SectionTitle("그 순간 차 상태 — 차가 자동으로 붙임")
                    if (report == null) {
                        Body("아직 기록한 신고가 없습니다.")
                    } else {
                        ReportSnapshot(report, state.reportCar)
                    }
                }

                is DictationState.Listening -> DictatedText(
                    title = "듣는 중…",
                    text = dictation.partial.ifBlank { "말씀하세요" },
                )

                is DictationState.Heard -> DictatedText(
                    title = "이렇게 들었습니다",
                    text = dictation.text,
                )

                is DictationState.Failed -> {
                    SectionTitle(Dictation.failureText(dictation.reason))
                    Body("«다시 말하기»를 눌러 주세요.", lines = 2)
                    HorizontalDivider()
                    Body("아직 새 신고를 기록하지 않았습니다.", muted = true, lines = 2)
                }
            }
        }
        Panel(modifier = Modifier.weight(1f).fillMaxHeight()) {
            SectionTitle("접수 대기")
            Body(state.contract.returnPlace.name, bold = true)
            ValueRow("말한 시각", report?.let { time(it.saidAtMs) } ?: "아직 없음")
            ValueRow(
                "받은 시각",
                report?.receivedAtMs?.let { time(it) } ?: "없음 · 서버 없음",
            )
            ValueRow("신고", "${state.reports.size}건")
            HorizontalDivider()
            Body("말한 시각과 받은 시각은 따로 기록합니다.", lines = 2)
            Body("신고는 반납 카드에 함께 남습니다.", lines = 2)
            Body("사진 첨부·문자 전송은 이번 시연에 포함되지 않습니다.", muted = true, lines = 2)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (dictation) {
            DictationState.Idle -> {
                Action("말하기", "speak_report", onAction, Modifier.weight(1f))
                Action(
                    "예시 문장으로 신고",
                    "report1",
                    onAction,
                    Modifier.weight(1f),
                    primary = false,
                )
            }

            is DictationState.Listening -> {
                Spacer(modifier = Modifier.weight(1f))
            }

            is DictationState.Heard -> {
                Action("이대로 신고", "confirm", onAction, Modifier.weight(1f))
                Action("다시 말하기", "retry", onAction, Modifier.weight(1f), primary = false)
            }

            is DictationState.Failed -> {
                Action("다시 말하기", "retry", onAction, Modifier.weight(1f))
            }
        }
        Action("홈으로", "home", onAction, Modifier.width(180.dp), primary = false)
    }
    Body("손상 여부와 과실은 판정하지 않고 말씀하신 내용과 차 상태를 그대로 남깁니다.", muted = true)
}

/** 신고 받아쓰기의 «듣는 중»과 «들음» — 제목과 글만 다른 같은 배치. */
@Composable
private fun ColumnScope.DictatedText(title: String, text: String) {
    SectionTitle(title)
    Body(
        text = text,
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        bold = true,
        lines = Int.MAX_VALUE,
    )
    HorizontalDivider()
    Body("확인하지 않고 나가면 기록하지 않습니다.", muted = true, lines = 2)
}

@Composable
private fun ReportSnapshot(report: Report, captured: CarState?) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Body("시각 · ${date(report.saidAtMs, "M월 d일 HH:mm")}")
            Body(
                "위치 · " + (report.location?.let {
                    "${decimal(it.lat, 4)}, ${decimal(it.lon, 4)}"
                } ?: "확인 안 됨"),
            )
            Body("연료 · ${percent(report.fuelPercent)}(눈금)")
            Body("주행거리 · ${odometer(report.odometerKm)}")
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Body(
                "문 · " + when (report.driverDoorOpen) {
                    true -> "운전석 열림"
                    false -> "운전석 닫힘"
                    null -> "확인 안 됨"
                },
            )
            Body("창문 · ${windows(captured)}")
            Body("경고등 · ${warnings(report.telltales)}", lines = 2)
            Body("기어 · ${captured?.gear?.name ?: "확인 안 됨"}")
        }
    }
}

@Composable
private fun ColumnScope.CardContent(
    state: RentalUiState,
    onAction: (String) -> Unit,
) {
    val card = state.card ?: run {
        WaitingContent()
        return
    }
    Title(if (card.final) "반납 카드를 만들었습니다" else "반납지에 도착했습니다")
    Body(
        "${date(card.createdMs, "M월 d일 HH:mm")} · ${state.contract.returnPlace.name}" +
            if (card.final) " · 확정 카드" else " · 임시 카드",
    )

    Row(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel(modifier = Modifier.weight(1.2f).fillMaxHeight()) {
            SectionTitle("차 상태 — 인수 때와 반납 때")
            ComparisonRow("", "인수 때", "반납 때", bold = true)
            HorizontalDivider()
            ComparisonRow(
                "연료(눈금)",
                percent(card.pickup.fuelPercent),
                percent(card.atReturn.fuelPercent),
            )
            ComparisonRow(
                "주행거리",
                odometer(card.pickup.odometerKm),
                odometer(card.atReturn.odometerKm),
            )
            ComparisonRow(
                "경고등",
                warnings(card.pickup.telltales),
                warnings(card.atReturn.telltales),
            )
            ComparisonRow(
                "문·창문",
                closed(card.pickup),
                closed(card.atReturn),
            )
            HorizontalDivider()
            Body(
                if (card.final) {
                    "반납 마치기를 누른 순간의 값으로 갱신했습니다."
                } else {
                    "반납 마치기를 누르면 그때의 값으로 카드를 갱신합니다."
                },
                muted = true,
                lines = 2,
            )
        }
        Panel(modifier = Modifier.weight(1f).fillMaxHeight()) {
            SectionTitle("주유 기록(눈금)")
            Body(
                card.refuel?.let { "${it.before}% → ${it.after}%" }
                    ?: "주유 기록 없음",
                bold = true,
            )
            Body("목표 ${percent(card.target)} · 넣은 양·금액은 영수증 기준")
            HorizontalDivider()
            SectionTitle("반납 시각")
            Body(
                "예정 ${time(state.contract.returnAtMs)} · 도착 " +
                    (state.arrivalAtMs?.let { time(it) } ?: "알림 없음"),
                bold = true,
            )
            Body(
                card.late?.let {
                    "도착 예정 ${time(it.etaMs)} · " +
                        if (it.sent) "전송 동의 기록(시연용)" else "미전송"
                } ?: "지연 알림 없음",
                lines = 2,
            )
            HorizontalDivider()
            SectionTitle("신고 ${card.reports.size}건")
            Body(
                card.reports.lastOrNull()?.let { "«${it.text}»" } ?: "신고 없음",
                lines = 2,
            )
            card.reports.lastOrNull()?.let {
                Body("${date(it.saidAtMs, "M월 d일 HH:mm")} 말함 · 접수 대기")
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Action(
            if (card.final) "현재 값으로 다시 기록" else "반납 마치기",
            "finish_return",
            onAction,
            Modifier.weight(1f),
        )
        Action("신고 보기", "report", onAction, Modifier.weight(0.7f), primary = false)
        Action("홈으로", "home", onAction, Modifier.weight(0.7f), primary = false)
    }
    Body("차 눈금은 참고값이며 정산을 보장하지 않습니다. 손상·과실은 판정하지 않습니다. 메모리 저장", muted = true)
}

@Composable
private fun ColumnScope.LateContent(
    state: RentalUiState,
    onAction: (String) -> Unit,
) {
    Title("도착 예정 시각을 보낼까요?")
    Body(state.lateQuestion ?: "반납이 늦을 것으로 예상됩니다.", lines = 2)
    Panel(modifier = Modifier.fillMaxWidth().weight(1f)) {
        SectionTitle("업체에 알릴 내용")
        ValueRow("반납 예정", time(state.contract.returnAtMs))
        ValueRow("도착 예정", state.lateEtaMs?.let { time(it) } ?: "확인 대기")
        state.contract.extraFeePer30Min?.let {
            ValueRow(
                "연장 요금",
                "30분 ${NumberFormat.getIntegerInstance(Locale.KOREA).format(it)}원",
            )
        }
        HorizontalDivider()
        Body("«보내 줘»라고 답하거나 아래 단추를 눌러 주세요.", bold = true)
        Body("답이 없으면 보내지 않습니다. 위치는 포함하지 않습니다.")
        Body("시연용 · 전송 동의만 앱에 기록하며 실제 서버 전송은 하지 않습니다.", muted = true, lines = 2)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Action("보내 주세요", "late_yes", onAction, Modifier.weight(1f))
        Action("보내지 않기", "late_no", onAction, Modifier.weight(1f), primary = false)
    }
}

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun Title(text: String, lines: Int = 1) {
    Text(
        text = text,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        fontWeight = FontWeight.Bold,
        color = Navy,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        color = Navy,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Body(
    text: String,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    muted: Boolean = false,
    lines: Int = 1,
) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 22.sp,
        lineHeight = 29.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = if (muted) Muted else Navy,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ValueRow(label: String, value: String, bold: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Body(label, Modifier.weight(1f), muted = !bold)
        Body(value, Modifier.weight(1.3f), bold = bold, lines = 2)
    }
}

@Composable
private fun ComparisonRow(
    label: String,
    before: String,
    after: String,
    bold: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Body(label, Modifier.weight(0.9f), bold = bold, lines = 2)
        Body(before, Modifier.weight(1f), bold = bold, lines = 2)
        Body(after, Modifier.weight(1f), bold = true, lines = 2)
    }
}

@Composable
private fun Job(number: String, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = Soft, shape = MaterialTheme.shapes.medium) {
            Text(
                text = number,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Navy,
            )
        }
        Column {
            Body(title, bold = true)
            Body(description, muted = true)
        }
    }
}

@Composable
private fun FuelNumber(label: String, value: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Body(label, muted = true)
        Text(
            text = percent(value),
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = Navy,
        )
    }
}

@Composable
private fun Action(
    label: String,
    action: String,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
) {
    val buttonModifier = modifier.heightIn(min = 72.dp)
    if (primary) {
        Button(
            onClick = { onAction(action) },
            modifier = buttonModifier,
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = label,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        OutlinedButton(
            onClick = { onAction(action) },
            modifier = buttonModifier,
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = label,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun date(ms: Long, pattern: String): String =
    Instant.ofEpochMilli(ms).atZone(Seoul)
        .format(DateTimeFormatter.ofPattern(pattern, Locale.KOREAN))

private fun time(ms: Long): String = date(ms, "HH:mm")

private fun hours(state: RentalUiState): String =
    String.format(
        Locale.KOREA,
        "%02d:00~%02d:00",
        state.contract.openHour,
        state.contract.closeHour,
    )

private fun percent(value: Int?): String = value?.let { "$it%" } ?: "확인 안 됨"

private fun odometer(value: Float?): String =
    value?.let { "${NumberFormat.getIntegerInstance(Locale.KOREA).format(it.toDouble())} km" }
        ?: "확인 안 됨"

private fun decimal(value: Double, digits: Int = 1): String =
    String.format(Locale.KOREA, "%.${digits}f", value)

private fun warnings(values: Set<String>): String =
    if (values.isEmpty()) "없음" else values.sorted().joinToString(" · ")

private fun closed(pickup: Pickup): String =
    if (pickup.allClosed) "모두 닫힘" else "열림 또는 확인 안 됨"

private fun windows(state: CarState?): String = when {
    state == null -> "확인 안 됨"
    state.windowOpen.values.any { it } -> "열림"
    WindowPos.entries.all { state.windowOpen[it] == false } -> "모두 닫힘"
    else -> "확인 안 됨"
}
