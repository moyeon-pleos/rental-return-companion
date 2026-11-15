package dev.pleos3.rental.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 지연 알림의 말 답 — 예·아니오 판정과 «한 번 더 묻기». */
class LateAnswerTest {
    @Test
    fun `보내 달라는 말은 예다 — 띄어쓰기와 말끝이 달라도`() {
        // 뒤의 둘은 음성 인식이 실제로 돌려준 글이다(2026-11-11 시험).
        for (text in listOf("보내 줘", "보내줘", "보내 주세요", "네 보내 줘", "보내죠?", "보내줘.")) {
            assertEquals(text, LateAnswer.YES, LateAnswer.parse(text))
        }
    }

    @Test
    fun `보내지 말라는 말은 아니오다 — 보내 가 들어 있어도`() {
        for (text in listOf("보내지 마", "보내지마", "보내지 마세요", "안 보내", "안보내도 돼", "아니", "아니요", "취소", "괜찮아요")) {
            assertEquals(text, LateAnswer.NO, LateAnswer.parse(text))
        }
    }

    @Test
    fun `엉뚱한 말과 빈 글은 모름이다`() {
        for (text in listOf("지하 이층 씨 구역", "오늘 날씨 어때", "", "   ")) {
            assertEquals(text, LateAnswer.UNCLEAR, LateAnswer.parse(text))
        }
    }

    @Test
    fun `보내 달라고 하면 보낸다 — 대꾸는 세션이 한다`() {
        assertEquals(LateStep.Answered(send = true, say = null), LateDialog().onHeard("보내 줘"))
    }

    @Test
    fun `보내지 말라고 하면 보내지 않고 그렇게 말한다`() {
        assertEquals(
            LateStep.Answered(send = false, say = LateDialog.NOT_SENT),
            LateDialog().onHeard("보내지 마"),
        )
    }

    @Test
    fun `처음 못 알아들으면 한 번 더 묻는다`() {
        assertEquals(LateStep.AskAgain(LateDialog.ASK_AGAIN), LateDialog().onHeard("지하 이층"))
        assertEquals(LateStep.AskAgain(LateDialog.ASK_AGAIN), LateDialog().onNoAnswer())
    }

    @Test
    fun `두 번째에 알아들으면 그 답을 따른다`() {
        val yes = LateDialog()
        yes.onHeard("음")
        assertEquals(LateStep.Answered(send = true, say = null), yes.onHeard("보내줘"))

        val no = LateDialog()
        no.onNoAnswer()
        assertEquals(LateStep.Answered(send = false, say = LateDialog.NOT_SENT), no.onHeard("보내지마"))
    }

    @Test
    fun `두 번 다 못 알아들으면 보내지 않는다`() {
        val words = LateDialog()
        words.onHeard("음")
        assertEquals(LateStep.GiveUp(LateDialog.GAVE_UP), words.onHeard("글쎄"))

        val silent = LateDialog()
        silent.onNoAnswer()
        assertEquals(LateStep.GiveUp(LateDialog.GAVE_UP), silent.onNoAnswer())

        val mixed = LateDialog()
        mixed.onHeard("뭐라고")
        assertEquals(LateStep.GiveUp(LateDialog.GAVE_UP), mixed.onNoAnswer())
    }

    @Test
    fun `포기한 뒤에 온 말은 더 묻지 않고 포기로 둔다`() {
        val d = LateDialog()
        d.onNoAnswer()
        d.onNoAnswer()
        assertEquals(LateStep.GiveUp(LateDialog.GAVE_UP), d.onHeard("음"))
    }
}
