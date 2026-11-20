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
     * Push-to-talk needs a local model (streaming, or offline used phrase by phrase). If it is the only button
     * configured but unavailable, fall back to the tap button so a mic is always shown.
     */
    fun visible(mode: String?, pttAvailable: Boolean): Visible = when (mode) {
        TAP -> Visible(tap = true, ptt = false)
        PTT -> if (pttAvailable) Visible(tap = false, ptt = true) else Visible(tap = true, ptt = false)
        else -> Visible(tap = true, ptt = pttAvailable)
    }

    fun label(mode: String?): String = when (mode) {
        TAP -> "Tap to start / stop"
        PTT -> "Push-to-talk (hold)"
        else -> "Both, side by side"
    }
}
