package dev.pleos3.rental.core

/** 지연 알림 질문(«업체에 도착 예정 시각을 보낼까요?»)에 대한 말 답. */
enum class LateAnswer {
    YES,
    NO,
    UNCLEAR,
    ;

    companion object {
        /**
         * 들은 글을 예·아니오로 가린다. 띄어쓰기는 무시한다.
         * «보내지 마»에도 «보내»가 들어 있으므로 거절 표현을 먼저 본다.
         */
        fun parse(text: String): LateAnswer {
            val compact = text.filterNot { it.isWhitespace() }
            return when {
                listOf("보내지", "안보내", "아니", "취소", "괜찮")
                    .any { compact.contains(it) } -> NO
                compact.contains("보내") -> YES
                else -> UNCLEAR
            }
        }
    }
}

/** 말 답을 받은 뒤 할 일. */
sealed interface LateStep {
    /** 답을 알아들었다. say 는 차가 대꾸할 말(없으면 null — 보냈을 때는 세션이 말한다). */
    data class Answered(val send: Boolean, val say: String?) : LateStep

    /** 못 알아들었다 — 한 번 더 묻고 다시 듣는다. */
    data class AskAgain(val say: String) : LateStep

    /** 두 번 다 못 알아들었다 — 보내지 않는다. */
    data class GiveUp(val say: String) : LateStep
}

/**
 * 지연 질문 한 번에 대한 말 대화. 못 알아들었거나 말이 없으면 한 번만 더 묻고,
 * 그래도 모르면 보내지 않는다(팀 결정 2026-11-11 — 기획서: «보내 줘»라고 답할 때만 보낸다).
 */
class LateDialog {
    private var askedAgain = false
    private var gaveUp = false

    /** 음성 인식이 글을 돌려줬다. */
    fun onHeard(text: String): LateStep {
        if (gaveUp) return LateStep.GiveUp(GAVE_UP)

        return when (LateAnswer.parse(text)) {
            LateAnswer.YES -> LateStep.Answered(send = true, say = null)
            LateAnswer.NO -> LateStep.Answered(send = false, say = NOT_SENT)
            LateAnswer.UNCLEAR -> onNoAnswer()
        }
    }

    /** 말이 없었거나 음성 인식이 실패했다. */
    fun onNoAnswer(): LateStep {
        if (gaveUp) return LateStep.GiveUp(GAVE_UP)

        return if (!askedAgain) {
            askedAgain = true
            LateStep.AskAgain(ASK_AGAIN)
        } else {
            gaveUp = true
            LateStep.GiveUp(GAVE_UP)
        }
    }

    companion object {
        const val ASK_AGAIN = "다시 말씀해 주세요. 보내 줘, 또는 보내지 마"
        const val NOT_SENT = "보내지 않았습니다"
        const val GAVE_UP = "알아듣지 못해 보내지 않았습니다"
    }
}
