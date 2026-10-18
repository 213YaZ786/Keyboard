// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.core.net.KeyboardNetwork
import helium314.keyboard.latin.core.voice.SpeechModel
import helium314.keyboard.latin.core.voice.Whisper
import helium314.keyboard.latin.dictation.KeyboardSpeech
import helium314.keyboard.latin.ui.component.BoldButton
import helium314.keyboard.latin.ui.component.GlassRadio
import helium314.keyboard.latin.ui.component.LoadingMark
import helium314.keyboard.latin.ui.component.QuietButton
import helium314.keyboard.latin.ui.component.rememberHaptics
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsPage
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceSection

/**
 * Dictation's speech engine: This phone (its own recognizer, when it has the keyboard's language: nothing to download),
 * Base or Small (whisper.cpp's models, downloaded once on a tap, only while the Network switch is on), then one line
 * for where the chosen one stands: Download with its size, its progress with Stop, or on the phone with Remove.
 *
 * Ported from SMS's speech model chooser (feature/thread/Voice.kt: ModelChoices, SpeechModelPanel), which sits on the
 * same voice kit (core/voice/SpeechModel), with this app's rows (settings/preferences/Preference) and glass controls
 * (GlassRadio, BoldButton, QuietButton); the progress mark is the one of the dictionary rows (settings/dialogs/DictionaryRows.kt).
 * https://github.com/213YaZ786/SMS/blob/main/app/src/main/java/com/yaz/sms/feature/thread/Voice.kt
 */
@Composable
fun DictationScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val speech = remember { KeyboardSpeech.model(ctx) }
    val state by speech.state.collectAsState()
    val system by speech.system.collectAsState()
    val haptics = rememberHaptics()
    // The phone is asked again about the keyboard's language when it was never asked for this one
    LaunchedEffect(Unit) { if (!speech.system.value || KeyboardSpeech.languageChanged()) speech.recheck() }
    var chosen by remember(system, state is SpeechModel.State.Ready) {
        mutableStateOf(if (speech.usesSystem) SpeechModel.SYSTEM else speech.pin.name)
    }
    val metered = remember { speech.metered() }
    val network = KeyboardNetwork.enabled(ctx)
    val language = remember {
        KeyboardSpeech.language().let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } }
    }
    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.main_dictation), settings = emptyList()) {
        SettingsPage {
            PreferenceSection(null) {
                if (system) {
                    Preference(
                        name = stringResource(R.string.dictation_this_phone),
                        description = stringResource(R.string.dictation_this_phone_summary, language),
                        onClick = {
                            if (state is SpeechModel.State.Fetching) return@Preference
                            haptics.tick(); speech.chooseSystem(); chosen = SpeechModel.SYSTEM
                        }
                    ) { GlassRadio(selected = chosen == SpeechModel.SYSTEM, onClick = null) }
                }
                if (Whisper.loaded) {
                    SpeechModel.PINS.reversed().forEach { pin ->
                        Preference(
                            name = pin.label,
                            description = stringResource(R.string.dictation_model_summary, pin.quality, pin.bytes / 1_000_000),
                            onClick = {
                                if (state is SpeechModel.State.Fetching) return@Preference
                                haptics.tick(); speech.choose(pin); chosen = pin.name
                            }
                        ) { GlassRadio(selected = chosen == pin.name, onClick = null) }
                    }
                } else if (!system) {
                    // 32-bit phones carry no whisper.cpp, and this one has no recognizer of its own for this language
                    Text(
                        stringResource(R.string.dictation_unavailable),
                        Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (chosen != SpeechModel.SYSTEM && Whisper.loaded) PreferenceSection(null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (val s = state) {
                        is SpeechModel.State.Fetching -> {
                            LoadingMark(size = 24.dp, progress = s.done)
                            Text(
                                stringResource(R.string.dictation_downloading, (s.done * 100).toInt()),
                                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium
                            )
                            QuietButton(onClick = { haptics.tick(); speech.cancel() }) { Text(stringResource(R.string.dictation_stop_download)) }
                        }
                        SpeechModel.State.Ready -> {
                            Text(
                                stringResource(R.string.dictation_on_phone),
                                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium
                            )
                            QuietButton(onClick = { haptics.reject(); speech.remove() }) { Text(stringResource(R.string.dictation_remove)) }
                        }
                        else -> {
                            val failed = state == SpeechModel.State.Failed
                            Text(
                                when {
                                    !network -> stringResource(R.string.dictation_needs_network)
                                    failed -> stringResource(R.string.dictation_failed)
                                    metered -> stringResource(R.string.dictation_mobile_data)
                                    else -> ""
                                },
                                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                color = if (failed || metered || !network) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (network) BoldButton(onClick = { haptics.done(); speech.fetch() }) {
                                Text(stringResource(R.string.dictation_download, speech.pin.bytes / 1_000_000))
                            }
                        }
                    }
                }
            }
        }
    }
}
