// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import android.content.Context
import android.provider.UserDictionary
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import helium314.keyboard.latin.personalization.PersonalizationHelper
import helium314.keyboard.latin.ui.component.GlassCheck
import helium314.keyboard.settings.screens.getSortedDictionaryLocales
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keyboard: "Delete learned words", a confirmation in the app's glass dialog (ThreeButtonAlertDialog, ZoneAlertDialog) with a
 * glass check for the personal dictionary, off by default. Compared: Gboard's "Delete learned words and data" and iOS 26's
 * "Reset Keyboard Dictionary" (both ask first; iOS keeps the text replacements, so the box deletes only the plain words
 * of the personal dictionary and keeps its rows that have a shortcut).
 */
@Composable
fun DeleteLearnedWordsDialog(onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current
    var alsoPersonal by remember { mutableStateOf(false) }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = { deleteLearnedWords(ctx.applicationContext, alsoPersonal) },
        confirmButtonText = stringResource(R.string.delete),
        title = { Text(stringResource(R.string.delete_learned_words)) },
        content = {
            Column {
                Text(stringResource(R.string.delete_learned_words_message))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clickable { alsoPersonal = !alsoPersonal }
                ) {
                    GlassCheck(alsoPersonal, null)
                    Text(stringResource(R.string.delete_learned_words_personal))
                }
            }
        }
    )
}

/**
 * The learned words are HeliBoard's own way of emptying them, PersonalizationHelper.removeAllUserHistoryDictionaries (used
 * when personalized suggestions are turned off, LatinIME.refreshPersonalizationDictionarySession: the live history
 * dictionaries are cleared, then their files go), every language. The personal dictionary rows of the keyboard's languages
 * and of all languages are deleted through the ContentResolver as AOSP Settings' UserDictionarySettings.deleteWord (Apache-2.0)
 * https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/src/com/android/settings/inputmethod/UserDictionarySettings.java
 * with the selections of HeliBoard's PersonalDictionaryScreen (a locale as its string, null for all languages).
 */
private fun deleteLearnedWords(context: Context, alsoPersonal: Boolean) {
    CoroutineScope(Dispatchers.IO).launch {
        runCatching { PersonalizationHelper.removeAllUserHistoryDictionaries(context) }
        if (alsoPersonal) runCatching {
            // only the plain words: a row with a shortcut is a text replacement and stays (iOS keeps them), the shortcut
            // test in brackets as in DictionaryFacilitatorImpl.removeWord
            val noShortcut = "(" + UserDictionary.Words.SHORTCUT + " is null OR " + UserDictionary.Words.SHORTCUT + "=?)"
            val resolver = context.contentResolver
            for (locale in getSortedDictionaryLocales())
                resolver.delete(UserDictionary.Words.CONTENT_URI, UserDictionary.Words.LOCALE + "=? AND " + noShortcut, arrayOf(locale.toString(), ""))
            resolver.delete(UserDictionary.Words.CONTENT_URI, UserDictionary.Words.LOCALE + " is null AND " + noShortcut, arrayOf(""))
        }
        withContext(Dispatchers.Main) { Toast.makeText(context, R.string.learned_words_deleted, Toast.LENGTH_SHORT).show() }
    }
}
