// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.os.Handler
import android.os.Message
import android.text.InputType
import android.view.inputmethod.*
import androidx.core.content.edit
import helium314.keyboard.ShadowBinaryDictionaryUtils
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowInputMethodService.Companion.composingEnd
import helium314.keyboard.ShadowInputMethodService.Companion.composingStart
import helium314.keyboard.ShadowInputMethodService.Companion.selectedText
import helium314.keyboard.ShadowInputMethodService.Companion.selectionEnd
import helium314.keyboard.ShadowInputMethodService.Companion.selectionStart
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.ShadowFacilitator2.Companion.lastAddedWord
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.common.StringUtils
import helium314.keyboard.latin.inputlogic.InputLogic
import helium314.keyboard.latin.inputlogic.SpaceState
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getTimestampFormatter
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLog
import java.util.*
import kotlin.math.min
import kotlin.streams.asSequence
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowLocaleManagerCompat::class,
    ShadowInputMethodManager2::class,
    ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class,
    ShadowHandler::class,
    ShadowFacilitator2::class,
    ShadowBinaryDictionaryUtils::class, // Keyboard: the bundled emoji dictionary, see its getHeader
])
class InputLogicTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val settingsValues get() = Settings.getValues()
    private val inputLogic get() = latinIME.mInputLogic
    private val connection: RichInputConnection get() = inputLogic.mConnection
    private val composerReader = InputLogic::class.java.getDeclaredField("mWordComposer").apply { isAccessible = true }
    private val composer get() = composerReader.get(inputLogic) as WordComposer
    private val spaceStateReader = InputLogic::class.java.getDeclaredField("mSpaceState").apply { isAccessible = true }
    private val spaceState get() = spaceStateReader.get(inputLogic) as Int
    private val beforeComposingReader = RichInputConnection::class.java.getDeclaredField("mCommittedTextBeforeComposingText").apply { isAccessible = true }
    private val connectionTextBeforeComposingText get() = (beforeComposingReader.get(connection) as CharSequence).toString()
    private val composingReader = RichInputConnection::class.java.getDeclaredField("mComposingText").apply { isAccessible = true }
    private val connectionComposingText get() = (composingReader.get(connection) as CharSequence).toString()

    private val textBeforeCursor get() = ShadowInputMethodService.textBeforeCursor
    private val textAfterCursor get() = ShadowInputMethodService.textAfterCursor
    private val composingText get() = ShadowInputMethodService.composingText
    private val text get() = ShadowInputMethodService.text
    private val cursor get() = ShadowInputMethodService.cursor
    private var currentInputType get() = ShadowInputMethodService.currentInputType
        set(value) { ShadowInputMethodService.currentInputType = value }

    init {
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
    }

    @Test fun inputCode() {
        input('c')
        assertEquals("c", textBeforeCursor)
        assertEquals("c", getTextFromConnection())
        assertEquals("", textAfterCursor)
        assertEquals("c", composingText)
        latinIME.mHandler.onFinishInput()
        assertEquals("", composingText)
    }

    @Test fun delete() {
        setText("hello there ")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello there", text)
        assertEquals("there", composingText)
    }

    @Test fun deleteMultiCodepointText() {
        setText("hello there \uD83E\uDF00")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello there ", text)
    }

    @Test fun deleteCombinedText() {
        setText("hello there э́")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello there ", text)

        setText("hello there H̵̛͕̞̦̰̜͍̰̥̟͆̏͂̌͑́ͅ")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello there ", text)
    }

    @Test fun deleteInsideWord() {
        setText("hello you there")
        setCursorPosition(8) // after o in you
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello yu there", text)
        assertEquals("yu", composingText)
    }

    @Test fun insertLetterIntoWord() {
        setText("hello")
        setCursorPosition(3) // after first l
        input('i')
        assertEquals("helilo", getWordAtCursor())
        assertEquals("helilo", getTextFromConnection())
        assertEquals(4, getCursorPosition())
        assertEquals(4, cursor)
        assertEquals("", composingText)
    }

    @Test fun insertLetterIntoWordWithWeirdEditor() {
        currentInputType = 180225 // should not change much, but just to be sure
        setText("hello")
        setCursorPosition(3, weirdTextField = true) // after first l
        input('i')
        assertEquals("helilo", getWordAtCursor())
        assertEquals("helilo", getTextFromConnection())
        assertEquals(4, getCursorPosition())
        assertEquals(4, cursor)
    }

    @Test fun insertLetterIntoOneOfSeveralWords() {
        setText("hello my friend")
        setCursorPosition(7) // between m and y
        input('a')
        assertEquals("may", getWordAtCursor())
        assertEquals("hello may friend", getTextFromConnection())
        assertEquals(8, getCursorPosition())
        assertEquals(8, cursor)
    }

    @Test fun combineHangul() {
        val ko = SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first()
        latinIME.switchToSubtype(ko)
        chainInput("ㅂㄱㅑ")
        assertEquals("ㅂ갸", text)
    }

    @Test fun emojiHangul() {
        val ko = SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first()
        latinIME.switchToSubtype(ko)
        input(0x1F970)
        assertEquals("\uD83E\uDD70", text)
    }

    // todo: make it work, but it might not be that simple because adding is done in combiner
    //  https://github.com/HeliBorg/HeliBoard/issues/214
    @Test fun insertLetterIntoWordHangulFails() {
        // known to fail upstream: skipped in debug too, where build.sh runs the tests
        if (BuildConfig.BUILD_TYPE == "runTests" || BuildConfig.DEBUG) return
        latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first())
        chainInput("ㅛㅎㄹㅎㅕㅛ")
        setCursorPosition(3)
        input('ㄲ') // fails, as expected from the hangul issue when processing the event in onCodeInput
        assertEquals("ㅛㅎㄹㄲ혀ㅛ", getWordAtCursor())
        assertEquals("ㅛㅎㄹㄲ혀ㅛ", getTextFromConnection())
        assertEquals("ㅛㅎㄹㄲ혀ㅛ", textBeforeCursor + textAfterCursor)
        assertEquals(4, getCursorPosition())
        assertEquals(4, cursor)
    }

    // see issue 1447
    @Test fun separatorAfterHangul() {
        latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first())
        chainInput("ㅛ.")
        assertEquals("ㅛ.", text)
    }

    @Test fun deleteHangulInDebugMode() { // issue 1551, later only happened on phone
        latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first())
        setText("ㅛㅛ ")
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
    }

    @Test fun separatorUnselectsWord() {
        setText("hello")
        assertEquals("hello", composingText)
        input('.')
        assertEquals("", composingText)
    }

    @Test fun autospace() {
        setText("hello")
        input('.')
        input('a')
        assertEquals("hello.a", textBeforeCursor)
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        setText("hello")
        input('.')
        input('a')
        assertEquals("hello. a", textBeforeCursor)
    }

    @Test fun autospaceButWithTextAfter() {
        setText("hello there")
        setCursorPosition(5) // after hello
        input('.')
        input('a')
        assertEquals("hello.a", textBeforeCursor)
        assertEquals("hello.a there", text)
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        setText("hello there")
        setCursorPosition(5) // after hello
        input('.')
        input('a')
        assertEquals("hello. a", textBeforeCursor)
        assertEquals("hello. a there", text)
    }

    @Test fun noAutospaceInUrlField() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("example.net")
        assertEquals("example. net", text)
        lastAddedWord = ""
        setText("")
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("example.net")
        assertEquals("", lastAddedWord)
        assertEquals("example.net", text)
        assertEquals("example.net", composingText)
    }

    @Test fun noAutospaceInUrlFieldWhenPickingSuggestion() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("exam")
        pickSuggestion("example")
        assertEquals("example", text)
        input('.')
        assertEquals("example.", text)
    }

    @Test fun noAutospaceForDetectedUrl() { // "light" version, should work without url detection
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("http://example.net")
        assertEquals("http://example.net", text)
        assertEquals("http", lastAddedWord)
        assertEquals("example.net", composingText)
    }

    @Test fun noAutospaceForDetectedEmail() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("mail@example.com")
        assertEquals("mail@example.com", text)
        assertEquals("mail@example", lastAddedWord) // todo: do we want this? not really nice, but don't want to be too aggressive with URL detection disabled
        assertEquals("com", composingText) // todo: maybe this should still see the whole address as a single word? or don't be too aggressive?
        setText("")
        lastAddedWord = ""
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("mail@example.com")
        assertEquals("", lastAddedWord)
        assertEquals("mail@example.com", composingText)
    }

    @Test fun urlDetectionThings() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("...h")
        assertEquals("...h", text)
        assertEquals("h", composingText)
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("bla..")
        assertEquals("bla..", text)
        assertEquals("", composingText)
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("bla.c")
        assertEquals("bla.c", text)
        assertEquals("bla.c", composingText)
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        latinIME.prefs().edit { putBoolean(Settings.PREF_SHIFT_REMOVES_AUTOSPACE, true) }
        input("bla")
        input('.')
        functionalKeyPress(KeyCode.SHIFT) // should remove the phantom space (in addition to normal effect)
        input('c')
        assertEquals("bla.c", text)
        assertEquals("bla.c", composingText)
    }

    @Test fun stripSeparatorsBeforeAddingToHistoryWithURLDetection() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("example.com.")
        assertEquals("example.com.", composingText)
        input(' ')
        assertEquals("example.com", lastAddedWord)
    }

    @Test fun dontSelectConsecutiveSeparatorsWithURLDetection() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("bla..")
        assertEquals("", composingText)
        assertEquals("bla..", text)
    }

    @Test fun selectDoesSelect() {
        setText("this is some text")
        setCursorPosition(3, 8)
        assertEquals("s is ", text.substring(3, 8))
    }

    @Test fun noComposingForPasswordFields() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        input('a')
        input('b')
        assertEquals("", composingText)
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        input('.')
        input('c')
        assertEquals("", composingText)
    }

    @Test fun `don't select whole thing as composing word if URL detection disabled`() {
        setText("http://example.com")
        setCursorPosition(13) // between l and e
        assertEquals("example", composingText)
    }

    @Test fun `select whole thing except http(s) as composing word if URL detection enabled and selecting`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setText("http://example.com")
        setCursorPosition(13) // between l and e
        assertEquals("example.com", composingText)
        setText("http://bla.com http://example.com ")
        setCursorPosition(29) // between l and e
        assertEquals("example.com", composingText)
    }

    @Test fun `select whole thing except http(s) as composing word if URL detection enabled and typing`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("http://example.com")
        assertEquals("example.com", composingText)
    }

    @Test fun `don't add partial URL to history`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setText("http:/") // just so lastAddedWord isn't set to http
        chainInput("/bla.com")
        assertEquals("", lastAddedWord)
    }

    @Test fun urlProperlySelected() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        setText("http://example.com/here")
        setCursorPosition(18) // after .com
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE) // delete com
        // todo: do we really want no composing text?
        //  probably not... try not to break composing
        assertEquals("", composingText)
        chainInput("net")
        assertEquals("example.net", composingText)
    }

    @Test fun urlProperlySelectedWhenNotDeletingFullTld() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setText("http://example.com/here")
        setCursorPosition(18) // after .com
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE) // delete om
        // todo: this is a weird difference to deleting the full TLD (see urlProperlySelected)
        //  what do we want here? (probably consistency)
        assertEquals("example.c/here", composingText)
        chainInput("z")
        assertEquals("", composingText) // todo: this is a weird difference to deleting the full TLD
//        assertEquals("example.cz", composingText) // fails, but probably would be better than above
    }

    @Test fun dontCommitPartialUrlBeforeFirstPeriod() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        // type http://bla. -> bla not selected, but clearly url, also means http://bla is committed which we probably don't want
        chainInput("http://bla.")
        assertEquals("bla.", composingText)
    }

    @Test fun `intermediate commits in text field without protocol`() {
        chainInput("bla.")
        assertEquals("bla", lastAddedWord)
        chainInput("com/")
        assertEquals("com", lastAddedWord)
        chainInput("img.jpg")
        assertEquals("img", lastAddedWord)
        assertEquals("jpg", composingText)
    }

    @Test fun `intermediate commit in text field without protocol and with URL detection`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("bla.com/img.jpg")
        assertEquals("bla", lastAddedWord)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `only protocol commit in text field with protocol and URL detection`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("http://bla.com/img.jpg")
        assertEquals("http", lastAddedWord)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `no intermediate commit in URL field with protocol`() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("http://bla.com/img.jpg")
        assertEquals("http", lastAddedWord) // todo: somehow avoid?
        assertEquals("http://bla.com/img.jpg", text)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `no intermediate commit in URL field with protocol and URL detection`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("http://bla.com/img.jpg")
        assertEquals("http", lastAddedWord) // todo: somehow avoid?
        assertEquals("http://bla.com/img.jpg", text)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `no intermediate commit in URL field without protocol`() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("bla.com/img.jpg")
        assertEquals("", lastAddedWord)
        assertEquals("bla.com/img.jpg", text)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `no intermediate commit in URL field without protocol and with URL detection`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("bla.com/img.jpg")
        assertEquals("", lastAddedWord)
        assertEquals("bla.com/img.jpg", text)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `don't accidentally detect some other text fields as URI`() {
        // see comment in InputLogic.textBeforeCursorMayBeUrlOrSimilar
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE)
        chainInput("Hey,why")
        assertEquals("Hey, why", text)
    }

    @Test fun `URL detection does not trigger on non-words`() {
        // first make sure it works without URL detection
        chainInput("15:50-17")
        assertEquals("15:50-17", text)
        assertEquals("", composingText)
        // then with URL detection
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("15:50-17")
        assertEquals("15:50-17", text)
        assertEquals("", composingText)
    }

    @Test fun `autospace after selecting a suggestion`() {
        pickSuggestion("this")
        input('b')
        assertEquals("this b", text)
        assertEquals("b", composingText)
    }

    @Test fun `autospace works in URL field when input isn't URL`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        pickSuggestion("this")
        input('b')
        assertEquals("this b", text)
        assertEquals("b", composingText)
    }

    // https://github.com/HeliBorg/HeliBoard/issues/215
    // https://github.com/HeliBorg/HeliBoard/issues/229
    @Test fun `autospace works in URL field when input isn't URL, also for multiple suggestions`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        pickSuggestion("this")
        pickSuggestion("is")
        assertEquals("this is", text)
        pickSuggestion("not")
        assertEquals("this is not", text)
        input('c')
        assertEquals("this is not c", text)
        assertEquals("c", composingText)
    }

    @Test fun `emoji is added to dictionary`() {
        // check both text and codepoint input
        chainInput("hello ")
        input(0x1F36D)
        assertEquals(StringUtils.newSingleCodePointString(0x1F36D), lastAddedWord)
        reset()
        chainInput("hello ")
        input("🤗")
        assertEquals("\uD83E\uDD17", lastAddedWord)

        reset()
        chainInput("hello ")
        input("why 🤗 ") // not added because it's not only emoji (input can come from pasting)
        assertEquals("hello", lastAddedWord)
    }

    @Test fun `emoji uses phantom space`() {
        // check both text and codepoint input
        pickSuggestion("hi")
        input("🤗")
        assertEquals("\uD83E\uDD17", lastAddedWord)
        assertEquals("hi \uD83E\uDD17", text)
        reset()
        pickSuggestion("hi")
        input(0x1F36D)
        assertEquals(StringUtils.newSingleCodePointString(0x1F36D), lastAddedWord)
        assertEquals("hi ${StringUtils.newSingleCodePointString(0x1F36D)}", text)
    }

    // https://github.com/HeliBorg/HeliBoard/issues/230
    @Test fun `no autospace after opening quotes`() {
        chainInput("\"Hi\" \"h")
        assertEquals("\"Hi\" \"h", text)
        assertEquals("h", composingText)
        reset()
        chainInput("\"Hi\", \"h")
        assertEquals("\"Hi\", \"h", text)
        assertEquals("h", composingText)
    }

    @Test fun `autospace works in URL field when starting with quotes`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        input("\"")
        pickSuggestion("this")
        input("i")
        assertEquals("\"this i", text)
    }

    @Test fun `double space results in period and space, and delete removes the period`() {
        chainInput("hello")
        input(' ')
        input(' ')
        assertEquals("hello. ", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello ", text)
    }

    @Test fun `no weird space inside multi-"`() {
        chainInput("\"\"\"")
        assertEquals("\"\"\"", text)

        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("\"\"\"")
        assertEquals("\"\"\"", text)
    }

    @Test fun `autospace still happens after "`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("\"hello\"you")
        assertEquals("\"hello\" you", text)
    }

    @Test fun `autospace still happens after " if next word is in quotes`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("\"hello\"\"you\"")
        assertEquals("\"hello\" \"you\"", text)
    }

    @Test fun `autospace propagates over "`() {
        input('"')
        pickSuggestion("hello")
        assertEquals(spaceState, SpaceState.PHANTOM) // picking a suggestion sets phantom space state
        chainInput("\"you")
        assertEquals("\"hello\" you", text)
    }

    @Test fun `autospace still happens after " if nex word is in " and after comma`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("\"hello\",\"you\"")
        assertEquals("\"hello\", \"you\"", text)
    }

    @Test fun `autospace in json editor`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("{\"label\":\"")
        assertEquals("{\"label\": \"", text)
        input('c')
        assertEquals("{\"label\": \"c", text)
    }

    @Test fun `text input and delete`() {
        input("hello")
        assertEquals("hello", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hell", text)

        reset()
        input("hello ")
        assertEquals("hello ", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello", text)
    }

    @Test fun `emoji text input and delete`() {
        input("🕵🏼")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("", text)

        reset()
        input("\uD83D\uDD75\uD83C\uDFFC")
        input(' ')
        assertEquals("🕵🏼 ", text)
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("", text)
    }

    // emoRegex update to unicode 16.0 was required, https://github.com/HeliBorg/HeliBoard/issues/1760
    @Test fun `emojis deleted one by one`() {
        chainInput("\uD83E\uDEC6\uD83E\uDEC6\uD83E\uDEC6")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("\uD83E\uDEC6\uD83E\uDEC6", text)
    }

    @Test fun `revert autocorrect on delete`() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        chainInput("hullo")
        getAutocorrectedWithSpaceAfter("hello", "hullo")
        assertEquals("hello ", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hullo", text)

        reset()
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        latinIME.prefs().edit { putBoolean(Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT, false) }
        chainInput("hullo")
        getAutocorrectedWithSpaceAfter("hello", "hullo")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello", text)
    }

    @Test fun `remove glide typing word on delete`() {
        glideTypingInput("hello")
        assertEquals("hello", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("", text)

        // todo: now we want some way to disable delete-all on backspace, either per setting or something else
        //  need to avoid getting into the mWordComposer.isBatchMode() part of handleBackspaceEvent
    }

    @Test fun timestamp() {
        chainInput("hello")
        functionalKeyPress(KeyCode.TIMESTAMP)
        assertEquals(Calendar.getInstance().time.time.toDouble(),
            getTimestampFormatter(latinIME).parse(text.substring(5))!!.time.toDouble(), 1000.0)
    }

    @Test fun inlineEmojiSearchStart() {
        assertEquals(true, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, ' '.code, settingsValues))
        assertEquals(false, InputLogic.isStartOfInlineEmojiSearch(' '.code, ':'.code, ' '.code, settingsValues))
        assertEquals(true, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, '.'.code, settingsValues))
        assertEquals(true, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, "🌍".codePoints().asSequence().last(), settingsValues))
        assertEquals(false, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, 't'.code, settingsValues))
        assertEquals(false, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, '3'.code, settingsValues))
    }

    @Test fun inlineEmojiSearchString() {
        assertEquals("test", InputLogic.getInlineEmojiSearchString(":test"))
        assertEquals(null, InputLogic.getInlineEmojiSearchString("test"))
        assertEquals("test", InputLogic.getInlineEmojiSearchString(" :test"))
        assertEquals(null, InputLogic.getInlineEmojiSearchString("t:test"))
        assertEquals(null, InputLogic.getInlineEmojiSearchString("6:test"))
        assertEquals("test", InputLogic.getInlineEmojiSearchString("🌍:test"))
        assertEquals("test", InputLogic.getInlineEmojiSearchString(",:test"))
        assertEquals(null, InputLogic.getInlineEmojiSearchString(":test\nt"))
        assertEquals("/48", InputLogic.getInlineEmojiSearchString("2606:127.0.0.1::/48")) // do we want this?
    }

    @Test fun moveCursorHorizontally() {
        chainInput("hello")
        assertEquals(5, cursor)
        latinIME.mKeyboardActionListener.onHorizontalSpaceSwipe(-2)
        assertEquals(3, cursor)
        latinIME.mKeyboardActionListener.onHorizontalSpaceSwipe(-5)
        assertEquals(0, cursor)
        latinIME.mKeyboardActionListener.onHorizontalSpaceSwipe(-1)
        assertEquals(0, cursor)
        latinIME.mKeyboardActionListener.onHorizontalSpaceSwipe(3)
        assertEquals(3, cursor)
        latinIME.mKeyboardActionListener.onHorizontalSpaceSwipe(3)
        assertEquals(5, cursor)
        latinIME.mKeyboardActionListener.onHorizontalSpaceSwipe(1)
        assertEquals(5, cursor)
    }

    // ------- ported from AOSP LatinIME (Apache-2.0), tests/src/com/android/inputmethod/latin/ -------
    // InputLogicTests.java, PunctuationTests.java, ShiftModeTests.java and LatinImeStressTests.java, in this file's style:
    // the fake LatinIME of the tests above, no dictionary, so an autocorrection is set the way `revert autocorrect on delete`
    // does it (getAutocorrectedWithSpaceAfter) instead of coming from "tgis" -> "this" in the main dictionary.
    // Not ported: the tests needing the English main dictionary (testAutoCorrectByUserHistory, the testPredictions* with
    // "Barack" -> "Obama", testWordThenSpaceDisplaysPredictions) and the Hindi ones (no Hindi subtype with a double space period).

    // InputLogicTests.testPickSuggestionThenBackspace
    @Test fun `AOSP pick suggestion then backspace`() {
        chainInput("this")
        pickSuggestion("this")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("thi", text)
    }

    // InputLogicTests.testPickAutoCorrectionThenBackspace
    @Test fun `AOSP pick autocorrection then backspace`() {
        chainInput("tgis")
        pickSuggestion("this")
        assertEquals("this", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("thi", text)
    }

    // InputLogicTests.testPickTypedWordOverAutoCorrectionThenBackspace
    @Test fun `AOSP pick typed word over autocorrection then backspace`() {
        chainInput("tgis")
        pickSuggestion("tgis")
        assertEquals("tgis", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("tgi", text)
    }

    // InputLogicTests.testPickDifferentSuggestionThenBackspace
    @Test fun `AOSP pick different suggestion then backspace`() {
        chainInput("tgis")
        pickSuggestion("thus")
        assertEquals("thus", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("thu", text)
    }

    // InputLogicTests.testDeleteSelection
    @Test fun `AOSP delete selection`() {
        setText("some text delete me some text")
        setCursorPosition(10, 19)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("some text  some text", text)
    }

    // InputLogicTests.testDeleteSelectionTwice
    @Test fun `AOSP delete selection twice`() {
        setText("some text delete me some text")
        setCursorPosition(10, 19)
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("some text some text", text)
    }

    // InputLogicTests.testAutoCorrect
    @Test fun `AOSP autocorrect`() {
        autocorrect("tgis", "this", ' ')
        assertEquals("this ", text)
    }

    // InputLogicTests.testAutoCorrectWithQuote
    @Test fun `AOSP autocorrect with quote`() {
        autocorrect("didn'", "didn't", ' ')
        assertEquals("didn't ", text)
    }

    // InputLogicTests.testAutoCorrectWithPeriod
    @Test fun `AOSP autocorrect with period`() {
        autocorrect("tgis", "this", '.')
        assertEquals("this.", text)
    }

    // InputLogicTests.testAutoCorrectWithPeriodThenRevert
    @Test fun `AOSP autocorrect with period then revert`() {
        autocorrect("tgis", "this", '.')
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("tgis.", text)
    }

    // InputLogicTests.testAutoCorrectWithSpaceThenRevert
    @Test fun `AOSP autocorrect with space then revert`() {
        autocorrect("tgis", "this", ' ')
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("tgis", text)
    }

    // InputLogicTests.testAutoCorrectWithSpaceThenRevertThenTypeMore: a phantom space, so one space only
    @Test fun `AOSP autocorrect with space then revert then type more`() {
        autocorrect("tgis", "this", ' ')
        functionalKeyPress(KeyCode.DELETE)
        input('a')
        assertEquals("tgis a", text)
    }

    // InputLogicTests.testAutoCorrectToSelfDoesNotRevert
    @Test fun `AOSP autocorrect to self does not revert`() {
        chainInput("this ")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("this", text)
    }

    // InputLogicTests.testDoubleSpace (English strings)
    @Test fun `AOSP double space`() {
        val typed = listOf("this   ", "a+  ", "😇  ", "..  ", ")  ", "(  ", "%  ")
        val expected = listOf("this.  ", "a+. ", "😇. ", "..  ", "). ", "(  ", "%. ")
        typed.zip(expected).forEach { (t, e) ->
            reset()
            typeText(t)
            assertEquals(e, text)
        }
    }

    // InputLogicTests.testCancelDoubleSpaceEnglish
    @Test fun `AOSP cancel double space`() {
        typeText("this  ")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("this ", text)
    }

    // InputLogicTests.testDoubleSpacePeriod: the preference off keeps both spaces
    @Test fun `AOSP double space period setting`() {
        typeText("this  ")
        assertEquals("this. ", text)
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD, false) }
        typeText("this  ")
        assertEquals("this  ", text)
    }

    // InputLogicTests.testBackspaceAtStartAfterAutocorrect
    @Test fun `AOSP backspace at start after autocorrect`() {
        autocorrect("tgis", "this", ' ')
        setCursorPosition(0)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("this ", text)
    }

    // InputLogicTests.testAutoCorrectThenMoveCursorThenBackspace
    @Test fun `AOSP autocorrect then move cursor then backspace`() {
        chainInput("and ")
        autocorrect("tgis", "this", ' ')
        setCursorPosition(4)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("andthis ", text)
    }

    // InputLogicTests.testNoSpaceAfterManualPick
    @Test fun `AOSP no space after manual pick`() {
        chainInput("this")
        pickSuggestion("this")
        assertEquals("this", text)
    }

    // InputLogicTests.testManualPickThenType
    @Test fun `AOSP manual pick then type`() {
        chainInput("this")
        pickSuggestion("this")
        chainInput("is")
        assertEquals("this is", text)
    }

    // InputLogicTests.testManualPickThenSeparator
    @Test fun `AOSP manual pick then separator`() {
        chainInput("this")
        pickSuggestion("this")
        input('!')
        assertEquals("this!", text)
    }

    // InputLogicTests.testClusteringPunctuation
    @Test fun `AOSP clustering punctuation`() {
        chainInput("test")
        pickSuggestion("test")
        chainInput("!!?!:!")
        assertEquals("test!!?!:!", text)
    }

    // InputLogicTests.testManualPickThenStripperThenPick
    @Test fun `AOSP manual pick then newline then pick`() {
        ShadowInputMethodService.currentImeOptions = EditorInfo.IME_ACTION_NONE // enter adds a line
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        chainInput("this")
        pickSuggestion("this")
        input('\n')
        chainInput("this")
        pickSuggestion("this")
        assertEquals("this\nthis", text)
    }

    // InputLogicTests.testManualPickThenSpaceThenType
    @Test fun `AOSP manual pick then space then type`() {
        chainInput("this")
        pickSuggestion("this")
        chainInput(" is")
        assertEquals("this is", text)
    }

    // InputLogicTests.testManualPickThenManualPick (a next word prediction picked)
    @Test fun `AOSP manual pick then manual pick`() {
        chainInput("this")
        pickSuggestion("this")
        pickSuggestion("is")
        assertEquals("this is", text)
    }

    // InputLogicTests.testDeleteWholeComposingWord
    @Test fun `AOSP delete whole composing word`() {
        chainInput("this")
        repeat(4) { functionalKeyPress(KeyCode.DELETE) }
        assertEquals("", text)
    }

    // InputLogicTests.testResumeSuggestionOnBackspace: after "and this " a backspace composes "this" again
    @Test fun `AOSP resume suggestion on backspace`() {
        chainInput("and this ")
        assertEquals("", composingText)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("this", composingText)
        assertEquals(4, composingStart)
        assertEquals(8, composingEnd)
    }

    // InputLogicTests.testStartComposing: composing starts on a letter, not on a quote or a dash
    @Test fun `AOSP start composing`() {
        listOf("a" to true, "'" to false, "'-" to false, "-" to false, "-'" to false, "a-" to true, "a'" to true).forEach { (word, composing) ->
            reset()
            chainInput(word)
            assertEquals(if (composing) word else "", composingText, "composing '$word'")
        }
    }

    // InputLogicTests.testComposingMultipleBackspace
    @Test fun `AOSP composing multiple backspace`() {
        val word = "radklro"
        chainInput(word)
        repeat(3) { functionalKeyPress(KeyCode.DELETE) }
        chainInput(word)
        repeat(2) { functionalKeyPress(KeyCode.DELETE) }
        chainInput(word)
        repeat(3) { functionalKeyPress(KeyCode.DELETE) }
        assertEquals(word.length * 3 - 8, text.length)
    }

    // InputLogicTests.testManySingleQuotesOneByOne and testTypingSingleQuotesOneByOne (no autocorrection without a dictionary)
    @Test fun `AOSP single quotes one by one`() {
        typeText("i" + "'".repeat(20) + " ")
        assertEquals("i" + "'".repeat(20) + " ", text)
        reset()
        typeText("it's ")
        assertEquals("it's ", text)
    }

    // InputLogicTests.testBasicGesture and testGestureGesture
    @Test fun `AOSP gesture then gesture`() {
        glideTypingInput("got")
        inputLogic.onStartBatchInput(settingsValues, KeyboardSwitcher.getInstance(), latinIME.mHandler)
        handleMessages()
        glideTypingInput("milk")
        assertEquals("got milk", text)
    }

    // InputLogicTests.testTypeWithinComposing: a space inside a word ends the composition (the delete inside a word of the
    // AOSP test is left out: HeliBoard keeps composing there, see deleteInsideWord)
    @Test fun `AOSP type within composing`() {
        chainInput("something")
        setCursorPosition(4)
        input(' ')
        assertEquals("some thing", text)
        assertEquals(-1, composingStart)
        setCursorPosition(text.length)
        input(' ')
        assertEquals("some thing ", text)
    }

    // PunctuationTests.testWordThenSpaceThenPunctuationFromKeyboardTwice
    @Test fun `AOSP word then space then punctuation from keyboard twice`() {
        typeText("this !!")
        assertEquals("this !!", text)
    }

    // PunctuationTests.testWordThenSpaceThenPunctuationFromStripTwice
    @Test fun `AOSP word then space then punctuation from strip twice`() {
        typeText("test ")
        pickPunctuation("!")
        pickPunctuation("!")
        assertEquals("test!! ", text)
    }

    // PunctuationTests.testManualPickThenPunctuationFromStripTwiceThenType
    @Test fun `AOSP manual pick then punctuation from strip twice then type`() {
        chainInput("this")
        pickSuggestion("this")
        pickPunctuation("!")
        pickPunctuation("!")
        chainInput("is")
        assertEquals("this!! is", text)
    }

    // PunctuationTests.testManualPickThenManualPickWithPunctAtStart
    @Test fun `AOSP manual pick then pick with punctuation at start`() {
        chainInput("this")
        pickSuggestion("this")
        pickSuggestion("!is")
        assertEquals("this!is", text)
    }

    // PunctuationTests.testManuallyPickedWordThenColon, ...OpenParen, ...CloseParen
    @Test fun `AOSP manually picked word then colon and parentheses`() {
        listOf(":" to "this:", "(" to "this (", ")" to "this)").forEach { (punctuation, expected) ->
            reset()
            chainInput("this")
            pickSuggestion("this")
            chainInput(punctuation)
            assertEquals(expected, text)
        }
    }

    // PunctuationTests.testManuallyPickedWordThenSmiley, ...DotCom
    @Test fun `AOSP manually picked word then smiley and dot com`() {
        listOf(":-)" to "this :-)", ".com" to "this.com").forEach { (key, expected) ->
            reset()
            chainInput("this")
            pickSuggestion("this")
            input(key)
            assertEquals(expected, text)
        }
    }

    // PunctuationTests.testTypeWordTypeDotThenPressDotCom
    @Test fun `AOSP type word type dot then dot com`() {
        chainInput("this.")
        latinIME.onTextInput(".com") // input(String) expects the whole text to be added, here the doubled dot is dropped
        handleMessages()
        assertEquals("this.com", text)
    }

    // PunctuationTests.testAutoCorrectionWithSingleQuoteInside and ...SingleQuotesAround
    @Test fun `AOSP autocorrection with single quotes`() {
        autocorrect("you'f", "you'd", ' ')
        assertEquals("you'd ", text)
        reset()
        autocorrect("'tgis'", "this'", ' ', "tgis'")
        assertEquals("'this' ", text)
    }

    // PunctuationTests.testAutoSpaceWithDoubleQuotes
    @Test fun `AOSP autospace with double quotes`() {
        // AOSP's text goes on with `Then, 5"passed. He said"bye"and left.`, expecting a space after `5"`: that needs the 5 to
        // be a composing word (the space after a quote is set only when a word was composing), and digits are not composed
        val toType = "He said\"hello\"to me. I replied,\"hi\"."
        val expected = "He said \"hello\" to me. I replied, \"hi\". \""
        for (part in toType.split("\"")) {
            for (word in part.split(Regex("(?<=\\w)(?!\\w)|(?<!\\w)(?=\\w)"))) {
                typeText(word)
                if (composingText.isNotEmpty()) pickSuggestion(word) // only a composing word can be picked (digits are not composed)
            }
            input('"')
        }
        assertEquals(expected, text)
    }

    // like InputTestsBase.type(String): one key press per code point
    private fun typeText(s: String) {
        var i = 0
        while (i < s.length) {
            val codePoint = s.codePointAt(i)
            input(codePoint)
            i += Character.charCount(codePoint)
        }
    }

    // a word typed with autocorrection on, then its separator: the engine's suggestion is set as in `revert autocorrect on delete`
    // (a leading quote is not part of the composing word, so the engine's typed word is given without it)
    private fun autocorrect(typed: String, corrected: String, separator: Char, composed: String = typed) {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        chainInput(typed)
        getAutocorrectedWithSpaceAfter(corrected, composed, separator)
    }

    // Source: AOSP PunctuationTests.testWordThenSpaceThenPunctuationFromStripTwice
    // (https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/tests/src/com/android/inputmethod/latin/PunctuationTests.java):
    // the strip right after a word and a space shows the punctuation list, which AOSP's test checks with
    // isPunctuationSuggestions; here LatinIME's own list (mPunctuationSuggestions) is set before the pick
    private fun pickPunctuation(punctuation: String) {
        latinIME.mInputLogic.setSuggestedWords(settingsValues.mPunctuationSuggestions)
        pickSuggestion(punctuation)
    }

    // ShiftModeTests: auto caps is the caps mode the keyboard asks the input logic for (KeyboardSwitcher.getKeyboardShiftMode)
    private fun capsSentences() = setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
    private fun isCapsModeAutoShifted() = inputLogic.getCurrentAutoCapsState(settingsValues) != Constants.TextUtils.CAP_MODE_OFF

    // ShiftModeTests.testTypicalSentence
    @Test fun `AOSP shift typical sentence`() {
        capsSentences()
        assertTrue(isCapsModeAutoShifted(), "initial")
        typeText("Test")
        assertFalse(isCapsModeAutoShifted(), "after letter")
        typeText(" ")
        assertFalse(isCapsModeAutoShifted(), "after space")
        typeText("some,")
        assertFalse(isCapsModeAutoShifted(), "after comma")
        typeText(" ")
        assertFalse(isCapsModeAutoShifted(), "after comma space")
        typeText("words.")
        assertFalse(isCapsModeAutoShifted(), "directly after period")
        typeText(" ")
        assertTrue(isCapsModeAutoShifted(), "after period space")
    }

    // ShiftModeTests.testBackspace
    @Test fun `AOSP shift backspace`() {
        capsSentences()
        assertTrue(isCapsModeAutoShifted())
        typeText("A")
        assertFalse(isCapsModeAutoShifted())
        functionalKeyPress(KeyCode.DELETE)
        assertTrue(isCapsModeAutoShifted())
    }

    // ShiftModeTests.testRepeatingBackspace (the delay of the key repeat is a UI timer, not ported)
    @Test fun `AOSP shift repeating backspace`() {
        capsSentences()
        typeText("Test sentence. Another.")
        assertFalse(isCapsModeAutoShifted())
        repeat("Another.".length) { functionalKeyPress(KeyCode.DELETE) }
        assertEquals("Test sentence. ", text)
        assertTrue(isCapsModeAutoShifted())
    }

    // ShiftModeTests.testAutoCapsAfterDigitsPeriod (English; French and German through their subtype)
    @Test fun `AOSP shift after digits and period`() {
        capsSentences()
        typeText("On 22.11.")
        assertFalse(isCapsModeAutoShifted(), "en after digits-period")
        typeText(" ")
        assertTrue(isCapsModeAutoShifted(), "en after digits-period-space")
        listOf("fr" to ("Le 22." to true), "de" to ("Am 22." to false)).forEach { (language, case) ->
            reset()
            capsSentences()
            latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale(language.constructLocale()).first())
            typeText(case.first)
            assertFalse(isCapsModeAutoShifted(), "$language after digits-period")
            typeText(" ")
            assertEquals(case.second, isCapsModeAutoShifted(), "$language after digits-period-space")
        }
    }

    // ShiftModeTests.testAutoCapsAfterInvertedMarks
    @Test fun `AOSP shift after inverted marks`() {
        capsSentences()
        latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale("es".constructLocale()).first())
        assertTrue(isCapsModeAutoShifted())
        typeText("Hey. ¿")
        assertTrue(isCapsModeAutoShifted())
        reset()
        capsSentences()
        typeText("¡")
        assertTrue(isCapsModeAutoShifted())
    }

    // InputLogicTests.testManualPickThenSeparatorForFrench
    @Test fun `AOSP manual pick then separator for French`() {
        latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale("fr".constructLocale()).first())
        chainInput("test")
        pickSuggestion("test")
        input('!')
        assertEquals("test !", text)
    }

    // InputLogicTests.testClusteringPunctuationForFrench
    @Test fun `AOSP clustering punctuation for French`() {
        latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale("fr".constructLocale()).first())
        chainInput("test")
        pickSuggestion("test")
        chainInput("!!?!:!")
        assertEquals("test !!?! : !", text)
    }

    // LatinImeStressTests.testSwitchLanguagesAndInputLatinRandomCodePoints and ...InputRandomCodePoints: random words
    // with language switches must not crash. The code point helpers are CodePointUtils (common/src/.../latin/common/),
    // the same file of AOSP; the seed is fixed here (AOSP uses the clock) so a failure repeats.
    @Test fun `AOSP stress switch languages and input random latin code points`() = stress(false)
    @Test fun `AOSP stress switch languages and input random code points`() = stress(true)

    private fun stress(anyCodePoint: Boolean) {
        val locales = listOf("en_US", "de", "el", "es", "fi", "it", "nl", "pt", "ru")
        val random = Random(if (anyCodePoint) 20261018L else 20261017L)
        val codePointSet = if (anyCodePoint) generateCodePointSet(30, random) else LATIN_ALPHABETS_LOWER
        repeat(50) {
            val locale = locales[random.nextInt(locales.size)].replace('_', '-').constructLocale()
            SubtypeSettings.getResourceSubtypesForLocale(locale).firstOrNull()?.let { latinIME.switchToSubtype(it) }
            repeat(random.nextInt(20)) {
                val word = generateWord(random, codePointSet)
                var i = 0
                while (i < word.length) { // like InputTestsBase.type: one event per code point, no assertion on the text
                    val codePoint = word.codePointAt(i)
                    latinIME.onEvent(Event.createEventForCodePointFromUnknownSource(codePoint))
                    handleMessages()
                    i += Character.charCount(codePoint)
                }
            }
        }
    }

    // CodePointUtils.generateCodePointSet and generateWord (AOSP LatinIME, Apache-2.0)
    private val LATIN_ALPHABETS_LOWER = (('a'..'z').map { it.code } + (0xE0..0xFF).filter { it != 0xF8 }).toIntArray()

    private fun generateCodePointSet(size: Int, random: Random): IntArray {
        val set = IntArray(size)
        var i = size - 1
        while (i >= 0) {
            val r = Math.abs(random.nextInt())
            if (r < 0) continue
            val candidate = 0x20 + r % (Character.MAX_CODE_POINT - 0x20)
            if (candidate in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) continue
            set[i--] = candidate
        }
        return set
    }

    private fun generateWord(random: Random, codePointSet: IntArray): String {
        val count = 1 + (1..8).sumOf { Math.abs(random.nextInt()) % 5 }
        val builder = StringBuilder()
        while (builder.length < count) builder.appendCodePoint(codePointSet[Math.abs(random.nextInt()) % codePointSet.size])
        return builder.toString()
    }

    // ------- helper functions ---------

    // should be called before every test, so the same state is guaranteed
    @BeforeTest
    fun reset() {
        // reset input connection & facilitator
        currentScript = ScriptUtils.SCRIPT_LATIN
        ShadowInputMethodService.reset()
        lastAddedWord = ""

        // reset settings
        latinIME.prefs().edit { clear() }

        setText("") // (re)sets selection and composing word
    }

    private fun chainInput(text: String) = text.forEach { input(it.code) }

    private fun input(char: Char) = input(char.code)

    private fun input(codePoint: Int) {
        require(codePoint > 0) { "not a codePoint: $codePoint" }
        val oldBefore = textBeforeCursor
        val oldAfter = textAfterCursor
        val insert = StringUtils.newSingleCodePointString(codePoint)
        val phantomSpaceToInsert = if (spaceState == SpaceState.PHANTOM) " " else ""
        val oldIsAtEnd = !composer.isCursorFrontOrMiddleOfComposingWord

        latinIME.onEvent(Event.createEventForCodePointFromUnknownSource(codePoint))
        handleMessages()

        if (!latinIME.prefs().getString(Settings.PREF_SELECTED_SUBTYPE, "")!!.contains("CombiningRules") // check fails if combiner merges symbols
            && !(codePoint == Constants.CODE_SPACE && oldBefore.lastOrNull() == ' ') // check fails when 2 spaces are converted into a period
            && !latinIME.mInputLogic.mSuggestedWords.mWillAutoCorrect // autocorrect obviously creates inconsistencies
            ) {
            if (phantomSpaceToInsert.isEmpty())
                assertEquals(oldBefore + insert, textBeforeCursor)
            else // in some cases autospace might be suppressed
                assert(oldBefore + phantomSpaceToInsert + insert == textBeforeCursor || oldBefore + insert == textBeforeCursor)
        }
        assertEquals(oldAfter, textAfterCursor)
        assertEquals(textBeforeCursor + textAfterCursor, getTextFromConnection())
        if (composer.isComposingWord) // if we're not composing any more cursor is always at the end
            assertEquals(oldIsAtEnd, !composer.isCursorFrontOrMiddleOfComposingWord)
        checkConnectionConsistency()
    }

    private fun functionalKeyPress(keyCode: Int) {
        require(keyCode < 0) { "not a functional key code: $keyCode" }
        latinIME.onEvent(Event.createSoftwareKeypressEvent(Event.NOT_A_CODE_POINT, keyCode, 0, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
        handleMessages()
        checkConnectionConsistency()
    }

    // almost the same as codePoint input, but calls different latinIME function
    private fun input(insert: String) {
        val oldBefore = textBeforeCursor
        val oldAfter = textAfterCursor
        val phantomSpaceToInsert = if (spaceState == SpaceState.PHANTOM) " " else ""

        latinIME.onTextInput(insert)
        handleMessages()

        if (phantomSpaceToInsert.isEmpty())
            assertEquals(oldBefore + insert, textBeforeCursor)
        else // in some cases autospace might be suppressed
            assert(oldBefore + phantomSpaceToInsert + insert == textBeforeCursor || oldBefore + insert == textBeforeCursor)
        assert(oldBefore + insert == textBeforeCursor || "$oldBefore $insert" == textBeforeCursor)
        assertEquals(oldAfter, textAfterCursor)
        assertEquals(textBeforeCursor + textAfterCursor, getTextFromConnection())
        checkConnectionConsistency()
    }

    private fun getWordAtCursor() = connection.getWordRangeAtCursor(settingsValues.mSpacingAndPunctuations, currentScript)?.mWord

    private fun setCursorPosition(start: Int, end: Int = start, weirdTextField: Boolean = false) {
        val ei = EditorInfo()
        ei.inputType = currentInputType
        ei.initialSelStart = start
        ei.initialSelEnd = end
        // imeOptions should not matter

        // adjust text in inputConnection first, otherwise fixLyingCursorPosition will move cursor
        // to the end of the text
        val fullText = textBeforeCursor + selectedText + textAfterCursor
        assertEquals(fullText, getTextFromConnection())

        // need to update ic before, otherwise when reloading text cache from ic, ric will load wrong text before cursor
        val oldStart = selectionStart
        val oldEnd = selectionEnd
        selectionStart = start
        selectionEnd = end
        assertEquals(fullText, textBeforeCursor + selectedText + textAfterCursor)

        latinIME.onUpdateSelection(oldStart, oldEnd, start, end, composingStart, composingEnd)
        handleMessages()

        if (weirdTextField) {
            latinIME.mHandler.onStartInput(ei, true) // essentially does nothing
            latinIME.mHandler.onStartInputView(ei, true) // does the thing
            handleMessages()
        }

        assertEquals(fullText, getTextFromConnection())
        assertEquals(start, selectionStart)
        assertEquals(end, selectionEnd)
        checkConnectionConsistency()
    }

    // assumes we have nothing selected
    private fun getCursorPosition(): Int {
        assertEquals(cursor, connection.expectedSelectionStart)
        assertEquals(cursor, connection.expectedSelectionEnd)
        return cursor
    }

    // just sets the text and starts input so connection it set up correctly
    private fun setText(newText: String) {
        ShadowInputMethodService.text = newText
        selectionStart = newText.length
        selectionEnd = selectionStart
        composingStart = -1
        composingStart = -1

        // we need to start input to notify that something changed
        // restarting is false, so this is seen as a new text field
        val ei = EditorInfo()
        ei.inputType = currentInputType
        latinIME.mHandler.onStartInput(ei, false)
        latinIME.mHandler.onStartInputView(ei, false)
        handleMessages() // this is important so the composing span is set correctly
        checkConnectionConsistency()
    }

    // like selecting a suggestion from strip
    private fun pickSuggestion(suggestion: String) {
        val info = SuggestedWordInfo(suggestion, "", 0, 0, null, 0, 0)
        latinIME.pickSuggestionManually(info)
        checkConnectionConsistency()
    }

    // only works when autocorrect is on, separator after word is required
    private fun getAutocorrectedWithSpaceAfter(suggestion: String, typedWord: String?, separator: Char = ' ') {
        val info = SuggestedWordInfo(suggestion, "", 0, 0, null, 0, 0)
        val typedInfo = SuggestedWordInfo(typedWord, "", 0, 0, null, 0, 0)
        val sw = SuggestedWords(ArrayList(listOf(typedInfo, info)), null, typedInfo, false, true, false, 0, 0)
        latinIME.mInputLogic.setSuggestedWords(sw) // this prepares for autocorrect
        if (separator == ' ') input(separator)
        else { // input() cannot check the text: the suggestions are reset by a separator other than space
            latinIME.onEvent(Event.createEventForCodePointFromUnknownSource(separator.code))
            handleMessages()
        }
        checkConnectionConsistency()
    }

    private fun glideTypingInput(word: String) {
        val info = SuggestedWordInfo(word, "", 0, 0, null, 0, 0)
        val sw = SuggestedWords(ArrayList(listOf(info)), null, info, true, false, false, 0, 0)
        latinIME.mInputLogic.onUpdateTailBatchInputCompleted(settingsValues, sw, KeyboardSwitcher.getInstance())
    }

    private fun checkConnectionConsistency() {
        // RichInputConnection only has composing text up to cursor, but InputConnection has full composing text
        val expectedConnectionComposingText = if (composingStart == -1 || composingEnd == -1) ""
        else text.substring(composingStart, min(composingEnd, selectionEnd))
        assert(composingText.startsWith(expectedConnectionComposingText))
        // RichInputConnection only returns text up to cursor
        val textBeforeComposingText = if (composingStart == -1) textBeforeCursor else text.substring(0, composingStart)

        println("consistency: $selectionStart, ${connection.expectedSelectionStart}, $selectionEnd, ${connection.expectedSelectionEnd}, $textBeforeComposingText, " +
                "$connectionTextBeforeComposingText, $composingText, $connectionComposingText, $textBeforeCursor, ${connection.getTextBeforeCursor(textBeforeCursor.length, 0)}" +
                ", $textAfterCursor, ${connection.getTextAfterCursor(textAfterCursor.length, 0)}")
        assertEquals(selectionStart, connection.expectedSelectionStart)
        assertEquals(selectionEnd, connection.expectedSelectionEnd)
        assertEquals(textBeforeComposingText, connectionTextBeforeComposingText)
        assertEquals(expectedConnectionComposingText, connectionComposingText)
        assertEquals(textBeforeCursor, connection.getTextBeforeCursor(textBeforeCursor.length, 0).toString())
        assertEquals(textAfterCursor, connection.getTextAfterCursor(textAfterCursor.length, 0).toString())
    }

    private fun getTextFromConnection() =
        connection.getTextBeforeCursor(100, 0).toString() + (connection.getSelectedText(0) ?: "") + connection.getTextAfterCursor(100, 0)

    private fun setInputType(inputType: Int) {
        // set text to actually apply input type
        currentInputType = inputType
        setText(text)
    }

    // always need to handle messages for proper simulation
    private fun handleMessages() {
        while (messages.isNotEmpty()) {
            latinIME.mHandler.handleMessage(messages.first())
            messages.removeAt(0)
        }
        while (delayedMessages.isNotEmpty()) {
            val msg = delayedMessages.first()
            if (msg.what != 2) // MSG_UPDATE_SUGGESTION_STRIP, we want to ignore it because it's irrelevant and has a 500 ms timeout
                latinIME.mHandler.handleMessage(delayedMessages.first())
            delayedMessages.removeAt(0)
            // delayed messages may post further messages, handle before next delayed message
            while (messages.isNotEmpty()) {
                latinIME.mHandler.handleMessage(messages.first())
                messages.removeAt(0)
            }
        }
        assertEquals(0, messages.size)
        assertEquals(0, delayedMessages.size)
    }

}

private var currentScript = ScriptUtils.SCRIPT_LATIN
private val messages = mutableListOf<Message>() // for latinIME / ShadowInputMethodService
private val delayedMessages = mutableListOf<Message>() // for latinIME / ShadowInputMethodService

// Shadows are handled by Robolectric. @Implementation overrides built-in functionality.
// This is used for avoiding crashes (LocaleManagerCompat, InputMethodManager, KeyboardSwitcher)
// and for simulating system stuff (InputMethodService for controlling the InputConnection, which
// more or less is the contents of the text field), and for setting the current script in
// KeyboardSwitcher without having to care about InputMethodSubtypes

@Implements(Handler::class)
class ShadowHandler {
    @Implementation
    fun sendMessage(message: Message) {
        messages.add(message)
    }
    @Implementation
    fun sendMessageDelayed(message: Message, delay: Long) {
        delayedMessages.add(message)
    }
}

@Implements(KeyboardSwitcher::class)
class ShadowKeyboardSwitcher {
    @Implementation
    // basically only needed for null check
    fun getMainKeyboardView(): MainKeyboardView = Mockito.mock(MainKeyboardView::class.java)
    @Implementation
    // only affects view
    fun setKeyboard(keyboardId: Int, toggleState: KeyboardSwitcher.KeyboardSwitchState) = Unit
    @Implementation
    // only affects view
    fun setOneHandedModeEnabled(enabled: Boolean, force: Boolean) = Unit
    @Implementation
    fun getCurrentKeyboardScript() = currentScript
}

@Implements(DictionaryFacilitatorImpl::class)
class ShadowFacilitator2 {
    @Implementation
    fun addToUserHistory(suggestion: String, wasAutoCapitalized: Boolean,
                         ngramContext: NgramContext, timeStampInSeconds: Long,
                         blockPotentiallyOffensive: Boolean) {
        lastAddedWord = suggestion
    }
    companion object {
        var lastAddedWord = ""
    }
}
