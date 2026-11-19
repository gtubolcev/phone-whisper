package com.kafkasl.phonewhisper

/** Which mic buttons the voice keyboard shows: tap-to-toggle, push-to-talk, or both. */
object MicButtons {
    const val PREF = "ime_mic_buttons"
    const val TAP = "tap"
    const val PTT = "ptt"
    const val BOTH = "both"
    const val DEFAULT = BOTH

    data class Visible(val tap: Boolean, val ptt: Boolean)

    /**
     * Push-to-talk is offered only with a streaming model. If it is the only button
     * configured but unavailable, fall back to the tap button so a mic is always shown.
     */
    fun visible(mode: String?, streaming: Boolean): Visible = when (mode) {
        TAP -> Visible(tap = true, ptt = false)
        PTT -> if (streaming) Visible(tap = false, ptt = true) else Visible(tap = true, ptt = false)
        else -> Visible(tap = true, ptt = streaming)
    }

    fun label(mode: String?): String = when (mode) {
        TAP -> "Tap to start / stop"
        PTT -> "Push-to-talk (hold)"
        else -> "Both, side by side"
    }
}
