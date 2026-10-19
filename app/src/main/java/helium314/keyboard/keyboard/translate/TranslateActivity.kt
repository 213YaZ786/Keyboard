// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.translate

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.keyboard.emoji.EmojiSearchActivity.PrivateImeOptions
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.core.translate.TranslationResult
import helium314.keyboard.latin.core.translate.Translator
import helium314.keyboard.latin.ui.component.BoldButton
import helium314.keyboard.latin.ui.component.LoadingMark
import helium314.keyboard.latin.ui.component.ZoneSurface
import helium314.keyboard.latin.ui.component.rememberHaptics
import helium314.keyboard.latin.ui.glass.LocalGlass
import helium314.keyboard.latin.ui.glass.glassGround
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest

/**
 * The Translate tool: a panel in the gap above the keys, with a field the keyboard types into; the translation
 * (Translator, core/translate) is shown live and handed back to LatinIME, which commits it into the app's field.
 *
 * Ported from HeliBoard's EmojiSearchActivity (GPL-3.0), same mechanism: a transparent activity whose field announces
 * its panel height in the private IME options, so the keyboard leaves that gap (LatinIME.onComputeInsets), and the
 * result goes back by a broadcast to LatinIME (TRANSLATE_DONE_ACTION, as EMOJI_SEARCH_DONE_ACTION), sent in onStop.
 * https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/keyboard/emoji/EmojiSearchActivity.kt
 * Left out of it: the emoji grid and dictionary, and the cancel on a switch to the emoji or clipboard page.
 *
 * Live translation as Translator's own screen (distinctUntilChanged, then mapLatest: an answer is shown only while
 * the input is still the one it answers), with a debounce so a burst of keys makes one request:
 * https://github.com/DavidVentura/offline-translator/blob/main/app/src/main/java/dev/davidv/translator/ui/TranslatorViewModel.kt
 *
 * The panel is made of the app's glass (ui/component) over the keyboard's own ground; its height is fixed, so the
 * private IME options never change while the panel is open (a change would restart the input).
 */
class TranslateActivity : ComponentActivity() {
    private var imeOpened = false
    private var imeVisible = false
    private var imeClosed = false
    private var inserted: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val session by lazy { Translator.Session(this) }

    // The keyboard was shown and went away (Back, or another app took the focus): the panel goes with it.
    // Ported from EmojiSearchActivity.closer
    private val closer = Runnable {
        if (!imeVisible) {
            imeClosed = true
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Theme { Panel() } }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imeOpened = false
        imeVisible = false
    }

    // The result goes to LatinIME, which commits it into the app's field. Ported from EmojiSearchActivity.onStop
    override fun onStop() {
        handler.removeCallbacks(closer)
        val intent = Intent(TRANSLATE_DONE_ACTION).setPackage(packageName).putExtra(IME_CLOSED_KEY, imeClosed)
        inserted?.let { intent.putExtra(TEXT_KEY, it) }
        inserted = null
        sendBroadcast(intent)
        super.onStop()
    }

    override fun onDestroy() {
        session.close()
        super.onDestroy()
    }

    /** What is asked of Translator, and what it answered ([result] null: nothing to translate). */
    private data class Request(val text: String, val to: String)
    private data class Answer(val request: Request, val result: TranslationResult?)

    @OptIn(ExperimentalLayoutApi::class, FlowPreview::class, ExperimentalCoroutinesApi::class)
    @Composable
    private fun Panel() {
        val scheme = MaterialTheme.colorScheme
        val haptics = rememberHaptics()
        val density = LocalDensity.current
        val configuration = LocalConfiguration.current
        // Fixed, in dp: 55 % of the height, at most 340 dp. A phone on its side (under 480 dp high) has a gap of about a
        // quarter of its height above the keys: one row of pills, then the field, the translation and Insert side by side
        val compact = configuration.screenHeightDp < 480
        val panelHeight = if (compact) (configuration.screenHeightDp * 0.26f).dp else minOf(PANEL_MAX_HEIGHT, (configuration.screenHeightDp * 0.55f).dp)
        val panelHeightPx = with(density) { panelHeight.roundToPx() }

        val uiLocale = Locale.getDefault()
        val codes = remember { targetCodes() }
        var target by remember { mutableStateOf(initialTarget(this, codes)) }
        var recents by remember { mutableStateOf(readRecents(this)) }
        var expanded by remember { mutableStateOf(false) }
        // the pills: the keyboard's languages, the last chosen, the target if it is neither (kept in a former session)
        val shownCodes = (codes + recents + target).distinct()
        // More: every other language of Translator, by name in the language of the screen
        // (java.text.Collator, https://developer.android.com/reference/java/text/Collator)
        val others = remember(shownCodes) {
            Translator.catalogue.filter { it !in shownCodes }
                .sortedWith(compareBy(Collator.getInstance(uiLocale)) { languageName(it, uiLocale) })
        }
        var text by remember { mutableStateOf(TextFieldValue()) }
        var answer by remember { mutableStateOf<Answer?>(null) }
        var wantInsert by remember { mutableStateOf(false) }

        val request = Request(text.text.trim(), target)
        val shown = answer?.takeIf { it.request == request }?.result // null while Translator works
        val ready = (shown as? TranslationResult.Ok)?.text

        // Translator's TranslatorViewModel flow: distinctUntilChanged then mapLatest
        LaunchedEffect(Unit) {
            snapshotFlow { Request(text.text.trim(), target) }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .mapLatest { Answer(it, if (it.text.isEmpty()) null else session.translate(it.text, it.to)) }
                .collect { answer = it }
        }

        val insert = { translation: String ->
            haptics.done()
            inserted = translation
            finish()
        }
        // The keyboard's enter key does what Insert does; if the translation is still on its way, it goes in when it comes
        LaunchedEffect(wantInsert, shown) {
            if (!wantInsert || shown == null) return@LaunchedEffect
            wantInsert = false
            (shown as? TranslationResult.Ok)?.let { insert(it.text) }
        }

        // Is the keyboard shown? Its inset in this window, https://developer.android.com/develop/ui/compose/system/insets
        val keyboardUp = WindowInsets.ime.getBottom(density) > 0
        LaunchedEffect(keyboardUp) {
            imeVisible = keyboardUp
            if (keyboardUp) {
                imeOpened = true
                handler.removeCallbacks(closer)
            } else if (imeOpened) {
                handler.postDelayed(closer, 200)
            }
        }

        Box(
            modifier = Modifier.fillMaxSize().background(Color(0x80000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() }
                .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets(bottom = panelHeight))),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(panelHeight)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .glassGround(LocalGlass.current, scheme.background)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxSize()
                        .padding(start = 16.dp, end = 16.dp, top = if (compact) 8.dp else 16.dp, bottom = if (compact) 6.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)
                ) {
                    val pill = if (compact) 40.dp else 48.dp
                    val focusRequester = remember { FocusRequester() }
                    // the field the keyboard types into
                    val fieldZone: @Composable (Modifier) -> Unit = { modifier ->
                        ZoneSurface(shape = RoundedCornerShape(20.dp), modifier = modifier) {
                            Box(Modifier.padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 12.dp)) {
                                if (text.text.isEmpty())
                                    Text(stringResource(R.string.translate_hint), color = scheme.onSurface.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyLarge)
                                BasicTextField(
                                    value = text,
                                    onValueChange = {
                                        text = it
                                        wantInsert = false
                                    },
                                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface, textDirection = TextDirection.Content),
                                    cursorBrush = SolidColor(scheme.primary),
                                    maxLines = if (compact) 1 else 2,
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Sentences,
                                        keyboardType = KeyboardType.Text,
                                        imeAction = ImeAction.Done,
                                        platformImeOptions = PlatformImeOptions(encodePrivateImeOptions(PrivateImeOptions(panelHeightPx)))
                                    ),
                                    keyboardActions = KeyboardActions(onDone = {
                                        if (ready != null) insert(ready)
                                        else if (request.text.isNotEmpty()) wantInsert = true
                                    })
                                )
                            }
                        }
                    }
                    // the live translation
                    val resultZone: @Composable (Modifier) -> Unit = { modifier ->
                        ZoneSurface(shape = RoundedCornerShape(20.dp), modifier = modifier) {
                            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 12.dp)) {
                                Result(request.text.isEmpty(), shown)
                            }
                        }
                    }
                    val insertButton: @Composable () -> Unit = {
                        BoldButton(onClick = { ready?.let { insert(it) } }, enabled = ready != null, filled = true) {
                            Text(stringResource(R.string.translate_insert))
                        }
                    }
                    // A pick: the target, kept in prefs; one outside the keyboard's languages joins the last chosen
                    // (three at most), shown after them next time. The list of every language closes.
                    val choose = { code: String ->
                        if (code != target) {
                            haptics.tick()
                            target = code
                            wantInsert = false
                            prefs().edit { putString(PREF_TARGET, code) }
                            if (code !in codes) {
                                recents = (listOf(code) + recents).distinct().take(MAX_RECENT)
                                prefs().edit { putString(PREF_RECENT, recents.joinToString(",")) }
                            }
                        }
                        expanded = false
                    }
                    // the target language, as pills: the keyboard's languages, the last chosen, then More
                    // (the whole flow in place; two lines at most and they scroll, one line when compact)
                    val chipsModifier = when {
                        expanded && !compact -> Modifier.weight(1f)
                        compact -> Modifier.heightIn(max = pill)
                        else -> Modifier.heightIn(max = CHIPS_MAX_HEIGHT)
                    }
                    Box(chipsModifier.verticalScroll(rememberScrollState())) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            shownCodes.forEach { code -> Pill(languageName(code, uiLocale), code == target, pill) { choose(code) } }
                            Pill(stringResource(R.string.translate_more), expanded, pill) { expanded = !expanded }
                            if (expanded && !compact) others.forEach { code -> Pill(languageName(code, uiLocale), code == target, pill) { choose(code) } }
                        }
                    }
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    if (compact) {
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            fieldZone(Modifier.weight(1f).fillMaxHeight())
                            if (expanded) {
                                // over the translation: every other language, in place of it and of Insert
                                Box(Modifier.weight(2f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        others.forEach { code -> Pill(languageName(code, uiLocale), code == target, pill) { choose(code) } }
                                    }
                                }
                            } else {
                                resultZone(Modifier.weight(1f).fillMaxHeight())
                                insertButton()
                            }
                        }
                    } else {
                        fieldZone(Modifier.fillMaxWidth())
                        if (!expanded) {
                            // the live translation, and Insert beside it
                            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                                resultZone(Modifier.weight(1f).fillMaxHeight())
                                insertButton()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * A language pill: a glass zone (ZoneSurface) with the chosen one on the accent, 48 dp high (40 when compact).
     * The selected state and its semantics as Material 3's FilterChip, drawn in glass instead
     * https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Chip.kt
     */
    @Composable
    private fun Pill(label: String, chosen: Boolean, height: Dp, onClick: () -> Unit) {
        ZoneSurface(shape = CircleShape, accent = chosen, modifier = Modifier.semantics { selected = chosen }, onClick = onClick) {
            Box(Modifier.heightIn(min = height).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    /** Under the field: nothing to translate, Translator at work, the translation, or why there is none. */
    @Composable
    private fun Result(empty: Boolean, shown: TranslationResult?) {
        if (empty) return
        when (shown) {
            null -> LoadingMark(size = 24.dp)
            is TranslationResult.Ok -> Text(
                shown.text,
                modifier = Modifier.verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content)
            )
            TranslationResult.NotDetected -> Text(stringResource(R.string.translate_not_detected), style = MaterialTheme.typography.bodyLarge)
            is TranslationResult.NotDownloaded -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.translate_not_downloaded, shown.language), style = MaterialTheme.typography.bodyLarge)
                val open = remember { Translator.launchIntent(this@TranslateActivity) }
                if (open != null)
                    BoldButton(onClick = {
                        startActivity(open)
                        finish()
                    }) { Text(stringResource(R.string.translate_open)) }
            }
            TranslationResult.Failed -> Text(
                stringResource(R.string.translate_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }

    companion object {
        const val TRANSLATE_DONE_ACTION: String = "TRANSLATE_DONE"
        const val IME_CLOSED_KEY: String = "IME_CLOSED"
        const val TEXT_KEY: String = "TEXT"
        private const val PREF_TARGET = "translate_target"
        private const val PREF_RECENT = "translate_recent"
        private const val MAX_RECENT = 3
        private const val PRIVATE_IME_OPTIONS_PREFIX: String = "helium314.keyboard.keyboard.translate.panel"
        private val PANEL_MAX_HEIGHT: Dp = 340.dp
        private val CHIPS_MAX_HEIGHT: Dp = 104.dp
        private const val DEBOUNCE_MS = 300L

        // Ported from EmojiSearchActivity.decodePrivateImeOptions / encodePrivateImeOptions, with this panel's own prefix
        fun decodePrivateImeOptions(editorInfo: EditorInfo?): PrivateImeOptions = PrivateImeOptions(
            editorInfo?.privateImeOptions?.takeIf { it.startsWith(PRIVATE_IME_OPTIONS_PREFIX) }
                ?.let { it.substring(PRIVATE_IME_OPTIONS_PREFIX.length + 1, it.indexOf(',')) }?.toIntOrNull() ?: 0)

        private fun encodePrivateImeOptions(privateImeOptions: PrivateImeOptions) =
            "$PRIVATE_IME_OPTIONS_PREFIX.${privateImeOptions.height},"

        /**
         * The target languages offered: the keyboard's enabled languages, the system language and English, each
         * once, only those Translator knows. Order as Gboard's translate bar lists the user's own languages first.
         */
        private fun targetCodes(): List<String> {
            val locales = SubtypeSettings.getEnabledSubtypes(true).map { it.locale() } +
                Resources.getSystem().configuration.locales[0] + Locale.ENGLISH
            return locales.mapNotNull { Translator.codeFor(it) }.distinct()
        }

        /**
         * The last chosen languages outside the keyboard's, newest first, read from the app's preferences
         * https://developer.android.com/training/data-storage/shared-preferences
         */
        private fun readRecents(context: Context): List<String> =
            context.prefs().getString(PREF_RECENT, "")!!.split(',').filter { it in Translator.catalogue }.take(MAX_RECENT)

        /** The last one chosen, else the first that is not the language being typed. */
        private fun initialTarget(context: Context, codes: List<String>): String {
            context.prefs().getString(PREF_TARGET, null)?.takeIf { it in Translator.catalogue }?.let { return it }
            val typing = Translator.codeFor(RichInputMethodManager.getInstance().currentSubtype.locale)
            return codes.firstOrNull { it != typing } ?: codes.first()
        }

        private fun languageName(code: String, uiLocale: Locale): String =
            Translator.localeOf(code).getDisplayName(uiLocale).replaceFirstChar { it.titlecase(uiLocale) }
    }
}
