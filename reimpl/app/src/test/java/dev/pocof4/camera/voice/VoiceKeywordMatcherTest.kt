package dev.pocof4.camera.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceKeywordMatcherTest {

    private val english = VoiceKeywordMatcher(language = "en")
    private val chinese = VoiceKeywordMatcher(language = "zh-CN")

    // --- language selection -------------------------------------------------

    @Test
    fun `english language selects the english phrase set`() {
        assertEquals(VoiceKeywordMatcher.ENGLISH_PHRASES, VoiceKeywordMatcher.phrasesForLanguage("en-US"))
    }

    @Test
    fun `chinese language selects the chinese phrase set`() {
        assertEquals(VoiceKeywordMatcher.CHINESE_PHRASES, VoiceKeywordMatcher.phrasesForLanguage("zh-CN"))
        assertEquals(VoiceKeywordMatcher.CHINESE_PHRASES, VoiceKeywordMatcher.phrasesForLanguage("zh_Hant"))
        assertEquals(VoiceKeywordMatcher.ENGLISH_PHRASES, VoiceKeywordMatcher.phrasesForLanguage(null))
    }

    // --- english positive cases --------------------------------------------

    @Test
    fun `exact words match`() {
        assertEquals("cheese", english.match("cheese"))
        assertEquals("photo", english.match("photo"))
        assertEquals("shoot", english.match("shoot"))
        assertEquals("capture", english.match("capture"))
    }

    @Test
    fun `case is ignored`() {
        assertEquals("cheese", english.match("CHEESE"))
        assertEquals("capture", english.match("CaPtUrE"))
    }

    @Test
    fun `punctuation and quotes are ignored`() {
        assertEquals("cheese", english.match("Say \"Cheese!\""))
        assertEquals("cheese", english.match("cheese!!!"))
        assertEquals("photo", english.match("...photo..."))
        assertEquals("cheese", english.match("cheese\u00A0please"))
    }

    @Test
    fun `multi word phrase wins over its suffix`() {
        assertEquals("take a photo", english.match("please take a photo now"))
        assertEquals("take a photo", english.match("TAKE A PHOTO!"))
    }

    @Test
    fun `keywords with trailing words still match`() {
        assertEquals("cheese", english.match("say cheese please"))
        assertEquals("shoot", english.match("shoot now"))
        assertEquals("capture", english.match("capture the moment"))
    }

    // --- english negatives --------------------------------------------------

    @Test
    fun `word boundaries reject longer words`() {
        assertNull(english.match("cheesecake"))
        assertNull(english.match("cheeseburger"))
        assertNull(english.match("photograph"))
        assertNull(english.match("photosynthesis"))
        assertNull(english.match("shooting"))
        assertNull(english.match("captured"))
    }

    @Test
    fun `partial keyword does not match`() {
        assertNull(english.match("chees"))
        assertNull(english.match("phot"))
        assertNull(english.match("shoo"))
    }

    @Test
    fun `unrelated speech does not match`() {
        assertNull(english.match("hello world"))
        assertNull(english.match("take a break"))
        assertNull(english.match("milk and cookies"))
    }

    @Test
    fun `empty input does not match`() {
        assertNull(english.match(null))
        assertNull(english.match(""))
        assertNull(english.match("   "))
        assertNull(english.match("!!! ???"))
    }

    // --- chinese ------------------------------------------------------------

    @Test
    fun `chinese phrases match without spaces`() {
        assertEquals("茄子", chinese.match("茄子"))
        assertEquals("拍照", chinese.match("我要拍照了"))
        assertEquals("拍张照", chinese.match("请帮我拍张照吧"))
        assertEquals("照相", chinese.match("我们来照相吧"))
    }

    @Test
    fun `chinese recognizes fullwidth punctuation`() {
        assertEquals("茄子", chinese.match("茄子！"))
        assertEquals("拍照", chinese.match("“拍照”"))
    }

    @Test
    fun `chinese does not match unrelated text or other language phrases`() {
        assertNull(chinese.match("吃饭"))
        assertNull(chinese.match("番茄"))
        assertNull(chinese.match("cheese"))
        assertNull(english.match("茄子"))
    }

    // --- debounce and per-utterance consumption -----------------------------

    @Test
    fun `match ignores debounce state`() {
        val matcher = VoiceKeywordMatcher(language = "en", clock = { 0L })
        assertEquals("cheese", matcher.accept("cheese"))
        // match() is stateless, accept() is not.
        assertEquals("cheese", matcher.match("cheese"))
        assertNull(matcher.accept("cheese"))
    }

    @Test
    fun `second trigger inside debounce window is ignored`() {
        var now = 1_000L
        val matcher = VoiceKeywordMatcher(language = "en", clock = { now })

        assertEquals("cheese", matcher.accept("cheese"))

        matcher.beginUtterance()
        now += 1_000L
        assertNull(matcher.accept("cheese"))

        matcher.beginUtterance()
        now += 1_499L // 2.499 s total
        assertNull(matcher.accept("photo"))
    }

    @Test
    fun `trigger at exactly the debounce boundary is accepted`() {
        var now = 0L
        val matcher = VoiceKeywordMatcher(language = "en", clock = { now })

        assertEquals("cheese", matcher.accept("cheese"))
        now += VoiceKeywordMatcher.DEFAULT_DEBOUNCE_MS
        matcher.beginUtterance()
        assertEquals("photo", matcher.accept("photo"))
    }

    @Test
    fun `one trigger per utterance even after the debounce expires`() {
        var now = 0L
        val matcher = VoiceKeywordMatcher(language = "en", clock = { now })

        assertEquals("cheese", matcher.accept("cheese"))
        now += 60_000L
        assertNull(matcher.accept("cheese"))
        // Partial and final hypotheses of the same utterance stay blocked.
        assertNull(matcher.accept("say cheese"))
    }

    @Test
    fun `beginUtterance re-arms the per-utterance gate`() {
        var now = 0L
        val matcher = VoiceKeywordMatcher(language = "en", clock = { now })

        assertEquals("cheese", matcher.accept("cheese"))
        now += 3_000L
        matcher.beginUtterance()
        assertEquals("cheese", matcher.accept("cheese"))
    }

    @Test
    fun `reset clears debounce and consumption`() {
        var now = 0L
        val matcher = VoiceKeywordMatcher(language = "en", clock = { now })

        assertEquals("cheese", matcher.accept("cheese"))
        matcher.reset()
        assertEquals("cheese", matcher.accept("cheese"))
    }

    @Test
    fun `non matching result neither consumes nor arms the debounce`() {
        var now = 0L
        val matcher = VoiceKeywordMatcher(language = "en", clock = { now })

        assertNull(matcher.accept("cheesecake"))
        now += 100L
        assertEquals("cheese", matcher.accept("cheese"))
    }
}
