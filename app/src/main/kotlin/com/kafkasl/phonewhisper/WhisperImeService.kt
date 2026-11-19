package com.kafkasl.phonewhisper

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Voice keyboard. Types dictated text through the InputConnection, so it needs no
 * accessibility service or overlay. Besides the mic it offers:
 *  - smart punctuation keys that replace the mark the recognizer guessed;
 *  - a strip with the words of the last phrase: tap one to select it, then re-dictate it,
 *    ask the cleanup LLM for alternatives, flip its case, delete it or type it manually;
 *  - word-wise cursor movement and word deletion.
 */
class WhisperImeService : InputMethodService() {

    private val handler = Handler(Looper.getMainLooper())

    // --- Views ---
    private var stripScroll: HorizontalScrollView? = null
    private var stripRow: LinearLayout? = null
    private var micCircle: FrameLayout? = null
    private var micIcon: ImageView? = null
    private var pttCircle: FrameLayout? = null
    private var pttIcon: ImageView? = null
    /** The current recording was started by holding the push-to-talk button. */
    private var pttActive = false

    // --- Editor state ---
    private var selStart = -1
    private var selEnd = -1

    /** Last dictated phrase, shown as tappable words. */
    private var phrase: DictatedPhrase? = null
    /** Index of the word selected for fixing, with its absolute range in the editor. */
    private var selectedWord: Int? = null
    private var selectedRange: IntRange? = null
    /** Cursor position before a word was selected, restored after the fix. */
    private var cursorBeforeSelect = -1
    private var variants: List<String>? = null
    private var variantsLoading = false
    private var variantsRequest = 0
    /** Word index being re-dictated; null for normal dictation. */
    private var replaceTarget: Int? = null
    private var notice: String? = null
    private var lastKeyWasPunct = false

    // --- Live streaming dictation ---
    /** Text is being typed live while the user speaks (streaming model). */
    private var liveActive = false
    /** Absolute cursor position where the live dictation started. */
    private var liveStart = -1
    /** Everything typed into the editor during this live dictation (segments + punctuation). */
    private val liveText = StringBuilder()
    /** Editor context captured when the current segment started (composing text excluded). */
    private var segBefore: CharSequence? = null
    private var segAfter: CharSequence? = null
    /** Punctuation pressed mid-dictation, applied once the cut segment arrives. */
    private val pendingPunct = mutableListOf<Char>()

    // --- Palette (day / night) ---
    private var night = false
    private val panelBg get() = if (night) 0xFF16181C.toInt() else 0xFFE6E9EF.toInt()
    private val keyBg get() = if (night) 0xFF2A2D33.toInt() else 0xFFFFFFFF.toInt()
    private val keyAltBg get() = if (night) 0xFF22252A.toInt() else 0xFFD3D8E0.toInt()
    private val keyPressed get() = if (night) 0xFF3A3E45.toInt() else 0xFFBFC6D1.toInt()
    private val keyText get() = if (night) 0xFFE8EAED.toInt() else 0xFF1C1F24.toInt()
    private val hintText get() = if (night) 0xFF9AA0A6.toInt() else 0xFF5A6472.toInt()
    private val accent get() = if (night) 0xFF8AB4F8.toInt() else 0xFF1A56DB.toInt()
    private val chipAccentBg get() = if (night) 0xFF1E3A5F.toInt() else 0xFFDCE7FD.toInt()
    private val micIdle = 0xFF2563EB.toInt()
    private val pttIdle = 0xFF0D9488.toInt()
    private val micRec = 0xFFEF4444.toInt()
    private val micBusy = 0xFF8A9099.toInt()

    private val listener = object : DictationEngine.Listener {
        override fun onStateChanged(state: DictationEngine.State) {
            if (state == DictationEngine.State.IDLE) pttActive = false
            renderMic(state)
            renderStrip()
        }

        override fun onLevel(level: Float) {
            val s = 1f + level * 0.18f
            val active = if (pttActive) pttCircle else micCircle
            active?.scaleX = s
            active?.scaleY = s
        }

        override fun onResult(text: String, notice: DictationEngine.Notice) {
            onDictationResult(text, notice)
        }

        override fun onError(message: String) {
            replaceTarget = null
            if (liveActive) finishLive()
            showNotice(message)
        }

        override fun onLivePartial(text: String) = livePartial(text)

        override fun onLiveSegment(text: String, forced: Boolean) = liveSegment(text)
    }

    // --- Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        DictationEngine.attach(this)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        selStart = attribute.initialSelStart
        selEnd = attribute.initialSelEnd
        lastKeyWasPunct = false
        if (!restarting) {
            clearPhrase()
            notice = null
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Warm the local model while the keyboard is visible; it is freed after idling.
        DictationEngine.ensureModelLoaded()
        DictationEngine.scheduleModelRelease()
        applyMicButtons()
        renderMic(ownerState())
        renderStrip()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Never type a late result into whatever field gets focus next.
        DictationEngine.cancel(listener)
        replaceTarget = null
        if (liveActive) {
            currentInputConnection?.finishComposingText()
            liveActive = false
            pendingPunct.clear()
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        val range = selectedRange
        if (selectedWord != null && replaceTarget == null &&
            (range == null || newSelStart != range.first || newSelEnd != range.last + 1)
        ) {
            // The user moved the cursor themselves: drop the word actions, keep the word strip.
            selectedWord = null
            selectedRange = null
            variants = null
            variantsLoading = false
            renderStrip()
        }
    }

    // --- Layout ---

    override fun onCreateInputView(): View {
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panelBg)
            setPadding(dp(6), dp(6), dp(6), dp(8))
        }

        // Strip: status, word chips, or actions for the selected word.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        stripRow = row
        stripScroll = scroll
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        // Mic row: word-left · tap mic and/or push-to-talk · word-right
        val (circle, icon) = micButton(micIdle, R.string.ime_mic)
        circle.setOnClickListener { it.haptic(); onMicTap() }
        micIcon = icon
        micCircle = circle
        val (ptt, pIcon) = micButton(pttIdle, R.string.ime_ptt)
        ptt.pushToTalk()
        pttIcon = pIcon
        pttCircle = ptt
        val micWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(circle, LinearLayout.LayoutParams(dp(68), dp(68)).apply { setMargins(dp(6), 0, dp(6), 0) })
            addView(ptt, LinearLayout.LayoutParams(dp(68), dp(68)).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        val micRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(key("←", 1f, alt = true, desc = R.string.ime_word_left).repeating { moveWord(left = true) })
            addView(micWrap, LinearLayout.LayoutParams(0, dp(76), 1.4f))
            addView(key("→", 1f, alt = true, desc = R.string.ime_word_right).repeating { moveWord(left = false) })
        }
        root.addView(micRow)

        // Punctuation row
        val punctRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
            for (mark in listOf(',', '.', '?', '!')) {
                addView(key(mark.toString(), 1f).onTap { punctuate(mark) })
            }
            addView(key(getString(R.string.ime_key_delete_word), 1.4f, alt = true, small = true).repeating { deleteWord() })
        }
        root.addView(punctRow)

        // Bottom row: switch keyboard · space · backspace · enter
        val switchKey = iconKey(R.drawable.ic_keyboard, 1f, R.string.ime_switch_keyboard).apply {
            setOnClickListener { it.haptic(); switchToOtherKeyboard() }
            setOnLongClickListener { imm().showInputMethodPicker(); true }
        }
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(switchKey)
            addView(key(getString(R.string.ime_key_space), 3f, small = true).spaceBar())
            addView(key("⌫", 1.2f, alt = true, desc = R.string.ime_backspace).repeating { backspace() })
            addView(key("⏎", 1.2f, alt = true, desc = R.string.ime_enter).onTap { enter() })
        }
        root.addView(bottomRow)

        applyMicButtons()
        renderMic(ownerState())
        renderStrip()
        return root
    }

    // --- Dictation ---

    private fun ownerState(): DictationEngine.State =
        if (DictationEngine.isOwner(listener)) DictationEngine.state else DictationEngine.State.IDLE

    private fun onMicTap() {
        when (ownerState()) {
            DictationEngine.State.IDLE -> startDictation(null)
            DictationEngine.State.RECORDING -> DictationEngine.stopAndTranscribe()
            DictationEngine.State.TRANSCRIBING -> {}
        }
    }

    /** Start dictating; with [target] the result replaces that word of the last phrase. */
    private fun startDictation(target: Int?): Boolean {
        notice = null
        replaceTarget = target
        // Live typing only for normal dictation; a re-dictated word is replaced in one go.
        when (DictationEngine.start(listener, cleanup = target == null, live = target == null)) {
            DictationEngine.StartResult.STARTED -> {
                liveActive = DictationEngine.isLive
                if (liveActive) {
                    if (selectedWord != null) deselectWord()
                    liveStart = minOf(selStart, selEnd)
                    liveText.setLength(0)
                    segBefore = null
                    segAfter = null
                    pendingPunct.clear()
                    lastKeyWasPunct = false
                }
                return true
            }
            DictationEngine.StartResult.NO_PERMISSION -> {
                replaceTarget = null
                showNotice(getString(R.string.ime_grant_mic))
                startActivity(Intent(this, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            DictationEngine.StartResult.MIC_ERROR -> {
                replaceTarget = null
                showNotice(getString(R.string.ime_mic_error))
            }
            DictationEngine.StartResult.BUSY -> {
                replaceTarget = null
                showNotice(getString(R.string.ime_busy))
            }
        }
        return false
    }

    private fun onDictationResult(text: String, result: DictationEngine.Notice) {
        if (liveActive) {
            finishLive()
            cleanupLivePhrase()
            return
        }
        val target = replaceTarget
        replaceTarget = null
        val p = phrase
        if (target != null && p != null && target < p.words.size) {
            val fitted = TextEditing.fitReplacement(text, p.words[target].text)
            if (fitted.isNotEmpty()) replaceWord(target, fitted, keepSelected = false)
            return
        }
        insertDictation(text)
        when (result) {
            DictationEngine.Notice.NONE -> {}
            DictationEngine.Notice.CLEANUP_NO_KEY -> showNotice(getString(R.string.ime_cleanup_no_key))
            DictationEngine.Notice.CLEANUP_FAILED -> showNotice(getString(R.string.ime_cleanup_failed))
        }
    }

    private fun insertDictation(text: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        val after = ic.getTextAfterCursor(16, 0) ?: ""
        val d = TextEditing.prepareDictation(text, before, after)
        if (d.insert.isEmpty()) { ic.endBatchEdit(); return }
        val start = minOf(selStart, selEnd)
        ic.commitText(d.insert, 1)
        ic.endBatchEdit()

        phrase = DictatedPhrase(d.phrase, if (start >= 0) start + d.phraseOffset else -1)
        resetWordSelection()
        lastKeyWasPunct = false
        notice = null
        renderStrip()
    }

    // --- Word fixing ---

    /** Find where the last phrase currently starts in the editor, or null if it was edited away. */
    private fun locatePhrase(ic: InputConnection, p: DictatedPhrase): Int? {
        val key = p.searchKey
        if (key.isEmpty()) return null
        val extracted = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 20000 }, 0)
        val full = extracted?.text?.toString()
        if (full != null) {
            val base = extracted.startOffset
            return TextEditing.findNearest(full, key, p.start - base)?.plus(base)
        }
        // Editors without extracted-text support: search around the cursor.
        val span = key.length + 1000
        val before = ic.getTextBeforeCursor(span, 0)?.toString() ?: ""
        val selected = ic.getSelectedText(0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(span, 0)?.toString() ?: ""
        val cursor = minOf(selStart, selEnd)
        if (cursor < 0) return null
        val base = cursor - before.length
        return TextEditing.findNearest(before + selected + after, key, p.start - base)?.plus(base)
    }

    private fun phraseLost() {
        clearPhrase()
        showNotice(getString(R.string.ime_text_changed))
    }

    private fun selectWord(index: Int) {
        if (liveActive) return
        val ic = currentInputConnection ?: return
        val p = phrase ?: return
        val base = locatePhrase(ic, p) ?: return phraseLost()
        val located = p.withStart(base)
        phrase = located
        val w = located.words[index]
        if (selectedWord == null) cursorBeforeSelect = maxOf(selStart, selEnd)
        selectedWord = index
        selectedRange = (base + w.start) until (base + w.end)
        variants = null
        variantsLoading = false
        variantsRequest++
        notice = null
        lastKeyWasPunct = false
        ic.setSelection(base + w.start, base + w.end)
        renderStrip()
    }

    /**
     * Replace word [index] of the last phrase with [replacement] (empty = delete it).
     * Afterwards the cursor returns to where it was before the word was selected, or the
     * word stays selected with [keepSelected].
     */
    private fun replaceWord(index: Int, replacement: String, keepSelected: Boolean) {
        val ic = currentInputConnection ?: return
        val p = phrase ?: return
        if (index >= p.words.size) return
        val base = locatePhrase(ic, p) ?: return phraseLost()
        val w = p.words[index]

        val removeRange = if (replacement.isEmpty()) p.deletionRange(index) else w.start until w.end
        val absStart = base + removeRange.first
        val absEnd = base + removeRange.last + 1
        val delta = replacement.length - (absEnd - absStart)
        val updated = (if (replacement.isEmpty()) p.delete(index) else p.replace(index, replacement)).withStart(base)

        ic.beginBatchEdit()
        ic.setSelection(absStart, absEnd)
        ic.commitText(replacement, 1)
        if (keepSelected && replacement.isNotEmpty()) {
            selectedRange = absStart until (absStart + replacement.length)
            ic.setSelection(absStart, absStart + replacement.length)
        } else {
            val saved = cursorBeforeSelect
            val target = when {
                saved < 0 -> absStart + replacement.length
                saved >= absEnd -> saved + delta
                saved > absStart -> absStart + replacement.length
                else -> saved
            }
            ic.setSelection(target, target)
        }
        ic.endBatchEdit()

        phrase = updated.takeIf { it.words.isNotEmpty() }
        if (keepSelected && replacement.isNotEmpty()) {
            variants = null
            variantsRequest++
        } else {
            resetWordSelection()
        }
        renderStrip()
    }

    private fun deselectWord() {
        val ic = currentInputConnection
        val saved = cursorBeforeSelect
        resetWordSelection()
        if (ic != null && saved >= 0) ic.setSelection(saved, saved)
        renderStrip()
    }

    private fun requestVariants() {
        val index = selectedWord ?: return
        val p = phrase ?: return
        val word = p.words.getOrNull(index)?.text ?: return
        val id = ++variantsRequest
        variantsLoading = true
        renderStrip()
        WordAlternatives.request(this, p.text, word) { list, error ->
            handler.post {
                if (id != variantsRequest) return@post
                variantsLoading = false
                when {
                    list == null && error == null -> notice = getString(R.string.ime_variants_need_key)
                    list == null -> notice = getString(R.string.ime_variants_error, error)
                    list.isEmpty() -> notice = getString(R.string.ime_variants_none)
                    else -> variants = list.map { TextEditing.fitReplacement(it, word) }.filter { it.isNotEmpty() }
                }
                renderStrip()
            }
        }
    }

    /** Hand over to the regular keyboard with the word still selected, so typing replaces it. */
    private fun switchToOtherKeyboard() {
        if (!switchToPreviousInputMethod()) imm().showInputMethodPicker()
    }

    private fun resetWordSelection() {
        selectedWord = null
        selectedRange = null
        cursorBeforeSelect = -1
        variants = null
        variantsLoading = false
        variantsRequest++
    }

    private fun clearPhrase() {
        phrase = null
        resetWordSelection()
    }

    // --- Keys ---

    private fun punctuate(mark: Char) {
        if (liveActive) {
            // Finalize what is being said right now, then place the mark after it.
            pendingPunct.add(mark)
            // While the tail is still decoding, the final segment will flush the marks.
            if (!DictationEngine.cutSegment() && ownerState() != DictationEngine.State.TRANSCRIBING) {
                flushPendingPunct()
            }
            return
        }
        if (selectedWord != null) deselectWord()
        notice = null
        applyPunct(mark)
        renderStrip()
    }

    private fun applyPunct(mark: Char) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(32, 0) ?: ""
        val after = ic.getTextAfterCursor(1, 0) ?: ""
        val edit = TextEditing.punctuate(before, mark, after, append = lastKeyWasPunct)
        if (edit.deleteBefore > 0) ic.deleteSurroundingText(edit.deleteBefore, 0)
        ic.commitText(edit.insert, 1)
        ic.endBatchEdit()
        lastKeyWasPunct = true
        if (liveActive) {
            liveText.setLength(maxOf(0, liveText.length - edit.deleteBefore))
            liveText.append(edit.insert)
        }
    }

    private fun flushPendingPunct() {
        val marks = pendingPunct.toList()
        pendingPunct.clear()
        marks.forEach { applyPunct(it) }
    }

    // --- Live streaming ---

    private fun captureSegmentContext(ic: InputConnection) {
        if (segBefore != null) return
        segBefore = ic.getTextBeforeCursor(64, 0) ?: ""
        segAfter = ic.getTextAfterCursor(16, 0) ?: ""
    }

    /** Show the unfinished segment as composing (underlined) text; it may still change. */
    private fun livePartial(text: String) {
        if (!liveActive) return
        val ic = currentInputConnection ?: return
        captureSegmentContext(ic)
        val d = TextEditing.prepareDictation(text, segBefore!!, segAfter!!)
        ic.setComposingText(d.insert, 1)
    }

    /** Commit a finished segment in place of the composing text, then any pending punctuation. */
    private fun liveSegment(text: String) {
        if (!liveActive) return
        val ic = currentInputConnection ?: return
        captureSegmentContext(ic)
        val d = TextEditing.prepareDictation(text, segBefore!!, segAfter!!)
        ic.commitText(d.insert, 1)
        liveText.append(d.insert)
        segBefore = null
        segAfter = null
        if (d.insert.isNotEmpty()) lastKeyWasPunct = false
        flushPendingPunct()
    }

    /** Close the live dictation and expose what was typed as the fixable phrase. */
    private fun finishLive() {
        liveActive = false
        currentInputConnection?.finishComposingText()
        flushPendingPunct()
        val typed = liveText.toString()
        val body = typed.trim()
        val lead = typed.length - typed.trimStart().length
        phrase = if (body.isNotEmpty()) DictatedPhrase(body, if (liveStart >= 0) liveStart + lead else -1) else null
        resetWordSelection()
        segBefore = null
        segAfter = null
        renderStrip()
    }

    /** With cleanup enabled, replace the live-typed phrase by its cleaned version. */
    private fun cleanupLivePhrase() {
        val p = phrase ?: return
        if (!DictationEngine.isCleanupEnabled()) return
        showNotice(getString(R.string.ime_cleaning))
        DictationEngine.cleanup(p.text) { cleaned, result ->
            handler.post {
                notice = null
                when (result) {
                    DictationEngine.Notice.CLEANUP_NO_KEY -> notice = getString(R.string.ime_cleanup_no_key)
                    DictationEngine.Notice.CLEANUP_FAILED -> notice = getString(R.string.ime_cleanup_failed)
                    DictationEngine.Notice.NONE -> if (phrase == p && cleaned.trim() != p.text) replacePhrase(p, cleaned.trim())
                }
                renderStrip()
            }
        }
    }

    private fun replacePhrase(p: DictatedPhrase, text: String) {
        val ic = currentInputConnection ?: return
        if (ownerState() != DictationEngine.State.IDLE) return
        val base = locatePhrase(ic, p) ?: return
        ic.beginBatchEdit()
        ic.setSelection(base, base + p.text.length)
        val current = ic.getSelectedText(0)?.toString()
        if (current == p.text) {
            ic.commitText(text, 1)
            phrase = DictatedPhrase(text, base)
        } else {
            val end = maxOf(selStart, selEnd)
            ic.setSelection(end, end)
        }
        ic.endBatchEdit()
    }

    /** Editing keys during live dictation stop it first (like Gboard); the key itself is dropped. */
    private fun stopLiveForKey(): Boolean {
        if (!liveActive || ownerState() != DictationEngine.State.RECORDING) return false
        DictationEngine.stopAndTranscribe()
        return true
    }

    private fun commitPlain(text: String) {
        if (stopLiveForKey()) return
        lastKeyWasPunct = false
        currentInputConnection?.commitText(text, 1)
    }

    private fun backspace() {
        if (stopLiveForKey()) return
        val ic = currentInputConnection ?: return
        lastKeyWasPunct = false
        if (selStart != selEnd && selStart >= 0) ic.commitText("", 1)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun deleteWord() {
        if (stopLiveForKey()) return
        val ic = currentInputConnection ?: return
        lastKeyWasPunct = false
        if (selStart != selEnd && selStart >= 0) { ic.commitText("", 1); return }
        val n = TextEditing.deleteWordLength(ic.getTextBeforeCursor(64, 0) ?: "")
        if (n > 0) ic.deleteSurroundingText(n, 0)
    }

    private fun moveWord(left: Boolean) {
        if (stopLiveForKey()) return
        val ic = currentInputConnection ?: return
        lastKeyWasPunct = false
        if (selStart < 0 || selEnd < 0) {
            sendDownUpKeyEvents(if (left) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
            return
        }
        val pos = if (left) {
            val from = minOf(selStart, selEnd)
            from - TextEditing.previousWordOffset(ic.getTextBeforeCursor(64, 0) ?: "")
        } else {
            val from = maxOf(selStart, selEnd)
            from + TextEditing.nextWordOffset(ic.getTextAfterCursor(64, 0) ?: "")
        }
        ic.setSelection(pos, pos)
    }

    /**
     * Move the cursor one character from absolute position [from]. Steps over surrogate
     * pairs (emoji) as a whole. Returns the new position, or null at the edge of the text.
     */
    private fun stepCursor(ic: InputConnection, from: Int, forward: Boolean): Int? {
        val n = if (forward) TextEditing.charStepForward(ic.getTextAfterCursor(2, 0) ?: "")
        else TextEditing.charStepBackward(ic.getTextBeforeCursor(2, 0) ?: "")
        if (n == 0) return null
        val to = if (forward) from + n else from - n
        ic.setSelection(to, to)
        return to
    }

    private fun enter() {
        if (stopLiveForKey()) return
        lastKeyWasPunct = false
        // Performs the field's action (send, search, …) when it has one, otherwise a newline.
        sendKeyChar('\n')
    }

    // --- Rendering ---

    /** Show the tap mic and/or push-to-talk button according to settings and the model. */
    private fun applyMicButtons() {
        val mode = getSharedPreferences("phonewhisper", MODE_PRIVATE).getString(MicButtons.PREF, MicButtons.DEFAULT)
        val visible = MicButtons.visible(mode, LocalTranscriber.isStreamingInUse(this))
        micCircle?.visibility = if (visible.tap) View.VISIBLE else View.GONE
        pttCircle?.visibility = if (visible.ptt) View.VISIBLE else View.GONE
    }

    private fun renderMic(state: DictationEngine.State) {
        renderMicButton(micCircle, micIcon, micIdle, state, active = !pttActive)
        renderMicButton(pttCircle, pttIcon, pttIdle, state, active = pttActive)
    }

    /** The button that started the recording turns red / grey; the other one stays idle. */
    private fun renderMicButton(circle: FrameLayout?, icon: ImageView?, idle: Int, state: DictationEngine.State, active: Boolean) {
        circle ?: return
        val color = when {
            !active || state == DictationEngine.State.IDLE -> idle
            state == DictationEngine.State.RECORDING -> micRec
            else -> micBusy
        }
        circle.background = oval(color)
        icon?.alpha = if (active && state == DictationEngine.State.TRANSCRIBING) 0.5f else 1f
        if (!active || state != DictationEngine.State.RECORDING) {
            circle.scaleX = 1f
            circle.scaleY = 1f
        }
    }

    private fun micButton(color: Int, desc: Int): Pair<FrameLayout, ImageView> {
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(0xFFFFFFFF.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = dp(14); setPadding(p, p, p, p)
        }
        val circle = FrameLayout(this).apply {
            background = oval(color)
            addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            isClickable = true
            contentDescription = getString(desc)
        }
        return circle to icon
    }

    /** Push-to-talk: record while held, transcribe on release. */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.pushToTalk() {
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.haptic()
                    if (ownerState() == DictationEngine.State.IDLE) {
                        pttActive = true
                        if (!startDictation(null)) pttActive = false
                        renderMic(ownerState())
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pttActive && ownerState() == DictationEngine.State.RECORDING) {
                        v.haptic()
                        DictationEngine.stopAndTranscribe()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun showNotice(message: String) {
        notice = message
        renderStrip()
    }

    private fun renderStrip() {
        val row = stripRow ?: return
        row.removeAllViews()
        val p = phrase
        when (ownerState()) {
            DictationEngine.State.RECORDING -> {
                val target = replaceTarget?.let { p?.words?.getOrNull(it)?.text }
                row.addView(label(
                    if (target != null) getString(R.string.ime_redictate_recording, target)
                    else getString(R.string.ime_recording)
                ))
            }
            DictationEngine.State.TRANSCRIBING -> row.addView(label(getString(R.string.ime_transcribing)))
            DictationEngine.State.IDLE -> {
                notice?.let { row.addView(label(it, highlight = true)) }
                val index = selectedWord
                val word = index?.let { p?.words?.getOrNull(it) }
                if (index != null && word != null) {
                    row.addView(label("«${word.text}»", bold = true))
                    variants?.forEach { v -> row.addView(chip(v, primary = true) { replaceWord(index, v, keepSelected = false) }) }
                    if (variants == null) {
                        val text = if (variantsLoading) getString(R.string.ime_variants_loading) else getString(R.string.ime_action_variants)
                        row.addView(chip(text, primary = true) { if (!variantsLoading) requestVariants() })
                    }
                    row.addView(chip(getString(R.string.ime_action_redictate)) { startDictation(index) })
                    row.addView(chip("Aa") { replaceWord(index, TextEditing.toggleFirstLetterCase(word.text), keepSelected = true) })
                    row.addView(chip(getString(R.string.ime_action_delete)) { replaceWord(index, "", keepSelected = false) })
                    row.addView(chip(getString(R.string.ime_action_type)) { switchToOtherKeyboard() })
                    row.addView(chip("✕") { deselectWord() })
                } else if (p != null && p.words.isNotEmpty()) {
                    p.words.forEachIndexed { i, w -> row.addView(chip(w.text) { selectWord(i) }) }
                    row.addView(chip("✕") { clearPhrase(); notice = null; renderStrip() })
                } else if (notice == null) {
                    row.addView(label(getString(R.string.ime_hint)))
                }
            }
        }
        stripScroll?.post { stripScroll?.scrollTo(0, 0) }
    }

    // --- View helpers ---

    private fun label(text: String, highlight: Boolean = false, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(if (highlight) accent else if (bold) keyText else hintText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        setPadding(dp(8), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun chip(text: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(if (primary) accent else keyText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(12), 0, dp(12), 0)
        background = pressable(if (primary) chipAccentBg else keyBg, radius = 16)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
        isClickable = true
        setOnClickListener { it.haptic(); onClick() }
    }

    private fun key(text: String, weight: Float, alt: Boolean = false, small: Boolean = false, desc: Int? = null) =
        TextView(this).apply {
            this.text = text
            setTextColor(keyText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 15f else 20f)
            gravity = Gravity.CENTER
            maxLines = 1
            background = pressable(if (alt) keyAltBg else keyBg, radius = 10)
            desc?.let { contentDescription = getString(it) }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), weight).apply { setMargins(dp(3), 0, dp(3), 0) }
            isClickable = true
        }

    private fun iconKey(res: Int, weight: Float, desc: Int) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(keyText)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        background = pressable(keyAltBg, radius = 10)
        contentDescription = getString(desc)
        val p = dp(12); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(0, dp(48), weight).apply { setMargins(dp(3), 0, dp(3), 0) }
        isClickable = true
    }

    private fun <T : View> T.onTap(action: () -> Unit): T = apply {
        setOnClickListener { it.haptic(); action() }
    }

    /** Fire on press and keep repeating while held (backspace, word moves). */
    /**
     * Space bar: a tap types a space; sliding the finger left/right moves the cursor one
     * character per step, like HeliBoard / Gboard.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun <T : View> T.spaceBar(): T = apply {
        val startThreshold = dp(14).toFloat()
        val stepWidth = dp(9).toFloat()
        var downX = 0f
        var anchorX = 0f
        var swiping = false
        var cursor = -1
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.haptic()
                    downX = ev.x
                    swiping = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!swiping && liveActive) return@setOnTouchListener true
                    if (!swiping && kotlin.math.abs(ev.x - downX) > startThreshold) {
                        swiping = true
                        anchorX = ev.x
                        lastKeyWasPunct = false
                        // Collapse any selection towards the swipe direction.
                        cursor = if (ev.x < downX) minOf(selStart, selEnd) else maxOf(selStart, selEnd)
                        if (selStart != selEnd && cursor >= 0) currentInputConnection?.setSelection(cursor, cursor)
                    }
                    if (swiping) {
                        val ic = currentInputConnection
                        var steps = ((ev.x - anchorX) / stepWidth).toInt()
                        if (ic != null && steps != 0) {
                            anchorX += steps * stepWidth
                            val forward = steps > 0
                            var moved = false
                            while (steps != 0) {
                                if (cursor < 0) {
                                    // Position unknown to the IME: fall back to arrow keys.
                                    sendDownUpKeyEvents(if (forward) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                                    moved = true
                                } else {
                                    val next = stepCursor(ic, cursor, forward) ?: break
                                    cursor = next
                                    moved = true
                                }
                                steps += if (forward) -1 else 1
                            }
                            if (moved) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (!swiping) commitPlain(" ")
                    swiping = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    swiping = false
                    true
                }
                else -> true
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun <T : View> T.repeating(action: () -> Unit): T = apply {
        val repeat = object : Runnable {
            override fun run() { action(); handler.postDelayed(this, 70) }
        }
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.haptic()
                    action()
                    handler.postDelayed(repeat, 420)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeat)
                    true
                }
                else -> true
            }
        }
    }

    private fun View.haptic() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun pressable(color: Int, radius: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), roundRect(keyPressed, radius))
        addState(intArrayOf(), roundRect(color, radius))
    }

    private fun roundRect(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun imm() = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
