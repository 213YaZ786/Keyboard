// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.core.net.DictionaryRepo
import helium314.keyboard.latin.core.net.DownloadException
import helium314.keyboard.latin.core.net.DownloadFailure
import helium314.keyboard.latin.core.net.KeyboardNetwork
import helium314.keyboard.latin.core.net.RepoDictionary
import helium314.keyboard.latin.ui.component.LoadingMark
import helium314.keyboard.latin.ui.component.QuietButton
import helium314.keyboard.latin.utils.getKnownDictionariesForLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private sealed interface RowState {
    data object Idle : RowState
    class Working(val progress: Float) : RowState
    data object Done : RowState
    class Failed(@StringRes val message: Int) : RowState
}

/**
 * The dictionaries of the repo for [locale] (HeliBoard's dictionaries_in_dict_repo.txt, through
 * getKnownDictionariesForLocale: main and emoji), each with a Download button; nothing when the Network switch is off.
 * Same idea as Gboard's language settings, which download a language's dictionaries on a tap
 * (https://support.google.com/gboard/answer/9136726); the rows themselves are the dictionary list of HeliBoard's
 * DictionaryDialog (createDictionaryTextAnnotated) with the link replaced by a button.
 * https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/settings/dialogs/DictionaryDialog.kt
 */
@Composable
fun AvailableDictionaries(locale: Locale) {
    val ctx = LocalContext.current
    if (!KeyboardNetwork.enabled(ctx)) return
    val rows = remember(locale) {
        // the list names a few files twice (emoji_en, emoji_fr, emoji_ru, emoji_uk): one row each
        getKnownDictionariesForLocale(locale, ctx).mapNotNull { (label, link) -> DictionaryRepo.fromLink(link)?.let { label to it } }
            .distinctBy { it.second.url }
    }
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { (label, entry) -> DictionaryRow(label, entry, locale) }
    }
}

@Composable
private fun DictionaryRow(label: String, entry: RepoDictionary, locale: Locale) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<RowState>(RowState.Idle) }
    // A downloaded dictionary that would replace one: HeliBoard's own import dialog asks first (NewDictionaryDialog)
    var replacing by remember { mutableStateOf<File?>(null) }

    fun start() {
        scope.launch {
            if (!KeyboardNetwork.online(ctx)) {
                state = RowState.Failed(R.string.dictionary_download_offline)
                return@launch
            }
            state = RowState.Working(0f)
            val file = File(ctx.cacheDir, "download_${entry.name}.dict")
            var handedOver = false
            try {
                DictionaryRepo.download(entry, file, DictionaryRepo.knownHashes(ctx)) { state = RowState.Working(it) }
                // The same checks as an imported file (NewDictionaryDialog), off the main thread: a conversion can be slow
                val (error, header) = withContext(Dispatchers.IO) { checkDict(file) }
                if (error != null || header == null) {
                    state = RowState.Failed(R.string.dictionary_download_invalid)
                } else if (dictionaryTargetFile(ctx, locale, header).exists()) {
                    replacing = file
                    handedOver = true
                    state = RowState.Idle
                } else {
                    installDictionary(ctx, file, header, locale)
                    state = RowState.Done
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadException) {
                state = RowState.Failed(
                    when (e.failure) {
                        DownloadFailure.OFFLINE -> R.string.dictionary_download_offline
                        DownloadFailure.SERVER -> R.string.dictionary_download_unavailable
                        DownloadFailure.TOO_LARGE -> R.string.dictionary_download_too_large
                        DownloadFailure.REFUSED -> R.string.dictionary_download_refused
                    }
                )
            } finally {
                if (!handedOver) file.delete()
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            when (val s = state) {
                is RowState.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoadingMark(size = 24.dp, progress = s.progress)
                    Text(stringResource(R.string.dictionary_downloading), style = MaterialTheme.typography.bodyMedium)
                }
                RowState.Done -> Text(stringResource(R.string.dictionary_installed), style = MaterialTheme.typography.bodyMedium)
                else -> QuietButton(onClick = { start() }) { Text(stringResource(R.string.dictionary_download)) }
            }
        }
        (state as? RowState.Failed)?.let {
            Text(stringResource(it.message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    replacing?.let {
        NewDictionaryDialog(onDismissRequest = { replacing = null }, cachedFile = it, mainLocale = locale)
    }
}
