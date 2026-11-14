package com.motioncare.deolulleong

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0E1113)
private val Card = Color(0xFF181D21)
private val Line = Color(0xFF2A3238)
private val Ink = Color(0xFF0E1113)
private val TextHi = Color(0xFFECEFF1)
private val TextLo = Color(0xFF9AA5AB)
private val Waiting = Color(0xFF3A4349)

fun Level.color() = Color(0xFF000000 or rgb.toLong())

@Composable
fun DeolApp(engine: CareEngine, onRequestSpeed: () -> Unit) {
    val s = engine.ui
    var deferred by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = darkColorScheme(primary = Level.CALM.color(), background = Bg, surface = Card)) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            when {
                s.driving -> DrivingScreen(s)
                !s.consent && !deferred -> ConsentScreen(
                    onAgree = { engine.grantConsent(); onRequestSpeed() },
                    onLater = { deferred = true },
                )
                else -> ParkedScreen(s, engine, onRequestSpeed, onOpenConsent = { deferred = false })
            }
        }
    }
}

// ---------------------------------------------------------------------- 동의 (첫 실행 한 번)

@Composable
private fun ConsentScreen(onAgree: () -> Unit, onLater: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 1100.dp).background(Card, RoundedCornerShape(28.dp)).padding(56.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("덜울렁", color = Level.CALM.color(), fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("무드등과 공조를 바꿉니다", color = TextHi, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Bullet("주행 중 흔들림 단계에 따라 실내 무드등 색을 바꿉니다. 잔잔함은 청록, 주의는 호박, 거침은 진한 주황입니다.")
            Bullet("멀미 부하가 쌓이면 한 주행에 한 번, 공조를 외기 유입으로 바꾸고 풍량을 한 단계 올립니다. 공조가 켜져 있을 때만 합니다.")
            Bullet("주행이 끝나거나 케어를 끄면 무드등과 공조를 원래 설정으로 되돌립니다.")
            Bullet("주행 기록은 이 기기의 메모리에만 두고, 앱을 닫으면 지워집니다. 서버로 보내지 않습니다.")
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BigButton("동의하고 시작", onAgree)
                BigOutlined("나중에", onLater)
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 14.dp).size(10.dp).background(Level.CALM.color(), CircleShape))
        Spacer(Modifier.width(18.dp))
        Text(text, color = TextHi, fontSize = 28.sp, lineHeight = 40.sp)
    }
}

// ---------------------------------------------------------------------- 주행 중: 단계 색과 한 단어

@Composable
private fun DrivingScreen(s: UiState) {
    val target: Color
    val word: String
    val sub: String?
    val ink: Color
    when {
        !s.signalOk -> { target = Waiting; word = "신호 확인 중"; sub = "차량 신호를 기다리고 있어요"; ink = TextHi }
        !s.careOn -> { target = Bg; word = "대기"; sub = "2열 탑승을 기다리고 있어요"; ink = TextLo }
        !s.controlling -> { target = Waiting; word = "신호 확인 중"; sub = null; ink = TextHi }
        else -> { target = s.level.color(); word = s.level.word; sub = null; ink = Ink }
    }
    val bg by animateColorAsState(target, tween(250), label = "bg")
    Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(word, color = ink, fontSize = if (word.length <= 2) 260.sp else 120.sp, fontWeight = FontWeight.Black)
            if (sub != null) Text(sub, color = ink, fontSize = 34.sp)
        }
        if (s.demoInfo) {
            Text(
                demoLine(s), color = ink.copy(alpha = 0.75f), fontSize = 24.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
            )
        }
    }
}

private fun demoLine(s: UiState) =
    "차속 %.0f km/h · 핸들 %.0f° · 앞뒤 %+.1f · 좌우 %+.1f · 흔들림 %.1f m/s² · 멀미 부하 %.1f/%.0f초".format(
        s.speedKph, s.wheelDeg, s.lon, s.lat, s.shake, s.loadSec, Spec.LOAD_TRIGGER_MS / 1000.0)

// ---------------------------------------------------------------------- 정차 중: 대기, 준비, 리포트

@Composable
private fun ParkedScreen(s: UiState, engine: CareEngine, onRequestSpeed: () -> Unit, onOpenConsent: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("덜울렁", color = TextHi, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(24.dp))
            Text("뒷좌석 멀미 케어", color = TextLo, fontSize = 26.sp)
            Spacer(Modifier.weight(1f))
            Chip("2열 탑승", when (s.rearOccupied) { true -> "있음"; false -> "없음"; null -> "확인 중" }, s.rearOccupied == true)
            Chip("차량 신호", if (s.signalOk) "정상" else "확인 중", s.signalOk)
        }
        Spacer(Modifier.height(16.dp))
        if (!s.consent) Banner("무드등과 공조를 바꾸려면 동의가 필요해요.", "동의하기", onOpenConsent)
        if (!s.speedPermission) Banner("차속을 읽으려면 권한이 필요해요.", "권한 요청", onRequestSpeed)
        if (!s.signalOk) Banner("신호 확인 중: 차량 신호가 들어오지 않아 무드등과 공조 제어를 멈췄어요.", null, null)
        if (s.demoInfo) {
            Text(demoLine(s), color = TextLo, fontSize = 22.sp, modifier = Modifier.padding(vertical = 8.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp)) {
            when {
                s.report != null -> ReportView(s.report, engine::submitFeedback)
                s.careOn -> StatusView(Level.CALM.color(), "잔잔", "케어 준비됨", "출발하면 흔들림을 무드등 색으로 알려 드려요.", null, null)
                else -> StatusView(
                    Waiting, "대기", "2열 탑승을 기다리고 있어요", "뒷좌석에 사람이 타면 케어를 시작해요.",
                    if (s.consent) "지금 케어 시작" else null, engine::startCareNow,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Setting("무드등 끄고 화면만", s.screenOnly, engine::setScreenOnly)
            Setting("시연용 수치 표시", s.demoInfo, engine::setDemoInfo)
            Spacer(Modifier.weight(1f))
            Text("무드등 ${s.ambientStatus} · 공조 ${s.hvacStatus}", color = TextLo, fontSize = 20.sp)
            if (s.careOn) BigOutlined("케어 종료", engine::endCareByUser)
            else if (s.report != null && s.consent) BigOutlined("지금 케어 시작", engine::startCareNow)
        }
    }
}

@Composable
private fun StatusView(color: Color, word: String, title: String, sub: String, action: String?, onAction: (() -> Unit)?) {
    Row(Modifier.fillMaxSize().background(Card, RoundedCornerShape(28.dp)).padding(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(300.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Text(word, color = if (color == Waiting) TextHi else Ink, fontSize = 84.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(64.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, color = TextHi, fontSize = 52.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = TextLo, fontSize = 30.sp)
            Spacer(Modifier.height(8.dp))
            LevelLegend()
            if (action != null && onAction != null) {
                Spacer(Modifier.height(8.dp))
                BigButton(action, onAction)
            }
        }
    }
}

@Composable
private fun LevelLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Level.entries.forEach { lv ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).background(lv.color(), CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(lv.word, color = TextHi, fontSize = 26.sp)
            }
        }
    }
}

@Composable
private fun ReportView(r: TripReport, onFeedback: (String) -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        // 점수
        Column(
            Modifier.weight(0.9f).fillMaxHeight().background(Card, RoundedCornerShape(28.dp)).padding(44.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("주행 리포트", color = TextLo, fontSize = 28.sp)
            Text("승차감 점수", color = TextHi, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${r.score}", color = scoreColor(r.score), fontSize = 150.sp, fontWeight = FontWeight.Black, lineHeight = 150.sp)
                Text("점", color = TextHi, fontSize = 44.sp, modifier = Modifier.padding(bottom = 26.dp, start = 8.dp))
            }
            Text("거침 ${r.roughCount}회 · 주의 ${r.cautionCount}회 · 주행 ${mmss(r.durationSec)}", color = TextHi, fontSize = 28.sp)
            Text("100점에서 거침 1회당 5점, 주의 1회당 2점을 뺀 고정 기준 점수예요.", color = TextLo, fontSize = 22.sp, lineHeight = 30.sp)
        }
        // 급조작, 공조, 피드백
        Column(
            Modifier.weight(1.4f).fillMaxHeight().background(Card, RoundedCornerShape(28.dp)).padding(44.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                EventKind.entries.forEach { k -> CountBox(k.label, r.events.count { it.kind == k }) }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.events.isEmpty()) Text("급가속, 급제동, 급조향이 없었어요.", color = TextLo, fontSize = 26.sp)
                r.events.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(mmss(e.atSec), color = TextLo, fontSize = 26.sp, modifier = Modifier.width(110.dp))
                        Box(Modifier.size(18.dp).background(e.peakLevel.color(), CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Text("${e.kind.label} · ${e.peakLevel.word} · 최대 %.1f m/s²".format(e.peakShake), color = TextHi, fontSize = 26.sp)
                    }
                }
                r.hvacNote?.let { Text("공조 케어: $it", color = TextHi, fontSize = 26.sp) }
            }
            Text("동승자에게 물어봐 주세요. 이번 주행 어땠나요?", color = TextHi, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FEEDBACK_OPTIONS.forEach { label ->
                    if (r.feedback == label) BigButton(label) { onFeedback(label) } else BigOutlined(label) { onFeedback(label) }
                }
            }
            if (r.feedback != null) Text("기록했어요: ${r.feedback}. 1차 버전은 기록만 하고 알림 기준은 바꾸지 않아요.", color = TextLo, fontSize = 22.sp)
        }
    }
}

private fun scoreColor(score: Int) = when {
    score >= 90 -> Level.CALM.color()
    score >= 70 -> Level.CAUTION.color()
    else -> Level.ROUGH.color()
}

@Composable
private fun CountBox(label: String, n: Int) {
    Column(Modifier.border(2.dp, Line, RoundedCornerShape(18.dp)).padding(horizontal = 26.dp, vertical = 14.dp)) {
        Text(label, color = TextLo, fontSize = 24.sp)
        Text("${n}회", color = TextHi, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------- 작은 부품

@Composable
private fun Chip(label: String, value: String, ok: Boolean) {
    Row(
        Modifier.padding(start = 14.dp).border(2.dp, if (ok) Level.CALM.color() else Line, RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label ", color = TextLo, fontSize = 22.sp)
        Text(value, color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Banner(text: String, action: String?, onAction: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Color(0xFF2B2416), RoundedCornerShape(16.dp)).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Level.CAUTION.color(), fontSize = 24.sp, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) BigOutlined(action, onAction)
    }
}

@Composable
private fun Setting(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.toggleable(value = checked, onValueChange = onChange, role = Role.Switch).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked, null, colors = SwitchDefaults.colors(checkedTrackColor = Level.CALM.color(), checkedThumbColor = Ink))
        Spacer(Modifier.width(14.dp))
        Text(label, color = TextHi, fontSize = 24.sp)
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit) {
    Button(
        onClick, shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Level.CALM.color(), contentColor = Ink),
        contentPadding = PaddingValues(horizontal = 36.dp, vertical = 18.dp),
    ) { Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun BigOutlined(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick, shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
    ) { Text(text, color = TextHi, fontSize = 26.sp) }
}

