package dev.pocof4.camera.voice

import java.util.Locale

/**
 * Pure-Kotlin (no Android types) matcher for the localized voice-shutter phrase set.
 *
 * The stock camera delegates wake-word spotting to Xiaomi's native engine
 * (`com.xiaomi.asr.engine.MultiWakeupEngine`), whose grammar is localized by the
 * resource string `speech_shutter_open_tip`: English says "Cheese!", Chinese says
 * "拍照" / "茄子". The reimplementation runs the phrase set through an Android
 * [android.speech.SpeechRecognizer] instead, so this class performs the equivalent
 * phrase spotting plus the de-duplication that the hardware wake-word engine gave
 * us for free.
 *
 * Matching rules:
 *  - case is folded with [Locale.ROOT];
 *  - punctuation and repeated whitespace are normalized to single spaces;
 *  - Latin phrases require word boundaries ("cheese" matches "Say cheese!" but
 *    not "cheesecake", "photo" matches but "photograph" does not);
 *  - CJK phrases are matched as substrings because Chinese text has no spaces.
 *
 * [match] is stateless. [accept] adds the runtime semantics used by the
 * controller: at most one trigger per utterance, plus a debounce window.
 */
class VoiceKeywordMatcher @JvmOverloads constructor(
    language: String? = Locale.getDefault().language,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS
) {

    /** Normalized ISO-639 style language code ("zh", "en", ...); empty means English. */
    val language: String = normalizeLanguage(language)

    /** Phrases for [language], longest first so "take a photo" wins over "photo". */
    val phrases: List<String> = phrasesForLanguage(this.language)

    private var lastAcceptedAtMs: Long? = null
    private var utteranceConsumed = false

    /**
     * Stateless phrase lookup. Returns the canonical phrase that occurs in [text],
     * or null when no phrase is present. Longest phrases are tested first.
     */
    fun match(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val normalized = normalize(text)
        if (normalized.isEmpty()) return null
        for (phrase in phrases) {
            if (containsPhrase(normalized, phrase)) return phrase
        }
        return null
    }

    /**
     * Stateful variant used by the recognizer: returns a keyword at most once per
     * utterance and never more often than [debounceMs].
     *
     * The caller signals a new recognition session with [beginUtterance]. That
     * re-arms the per-utterance gate but does not bypass the debounce window.
     */
    fun accept(text: String?): String? {
        if (utteranceConsumed) return null
        val keyword = match(text) ?: return null
        val now = clock()
        val last = lastAcceptedAtMs
        if (last != null && now - last < debounceMs) return null
        lastAcceptedAtMs = now
        utteranceConsumed = true
        return keyword
    }

    /** Marks the start of a new utterance, allowing one more keyword trigger. */
    fun beginUtterance() {
        utteranceConsumed = false
    }

    /** Clears both the per-utterance and debounce state (called from `stop()`). */
    fun reset() {
        utteranceConsumed = false
        lastAcceptedAtMs = null
    }

    private fun normalize(text: String): String {
        val folded = text.lowercase(Locale.ROOT)
        return NON_WORD.replace(folded, " ").trim()
    }

    private fun containsPhrase(haystack: String, phrase: String): Boolean {
        if (phrase.any { isCjkChar(it) }) {
            // Chinese has no word delimiters; the phrase is a plain substring.
            return haystack.contains(phrase)
        }
        var index = haystack.indexOf(phrase)
        while (index >= 0) {
            val beforeOk = index == 0 || !haystack[index - 1].isLetterOrDigit()
            val end = index + phrase.length
            val afterOk = end == haystack.length || !haystack[end].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            index = haystack.indexOf(phrase, index + 1)
        }
        return false
    }

    companion object {
        /** Debounce window between two accepted keywords, in milliseconds. */
        const val DEFAULT_DEBOUNCE_MS = 2_500L

        /** English model phrase set. Order matters: longest phrase first. */
        val ENGLISH_PHRASES: List<String> = listOf(
            "take a photo",
            "capture",
            "cheese",
            "photo",
            "shoot"
        )

        /** Chinese model phrase set, matching the `values-zh-*` strings resources. */
        val CHINESE_PHRASES: List<String> = listOf(
            "拍张照",
            "拍照",
            "茄子",
            "照相"
        )

        /** Returns the phrase set for an ISO language code; `zh-*` maps to Chinese. */
        fun phrasesForLanguage(language: String?): List<String> =
            if (normalizeLanguage(language).startsWith("zh")) CHINESE_PHRASES else ENGLISH_PHRASES

        private fun normalizeLanguage(language: String?): String =
            language?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_').orEmpty()

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    }
}

private fun isCjkChar(c: Char): Boolean =
    c.code in 0x2E80..0x9FFF || // CJK radicals, Kangxi, CJK Unified Ideographs
        c.code in 0xF900..0xFAFF // CJK Compatibility Ideographs
