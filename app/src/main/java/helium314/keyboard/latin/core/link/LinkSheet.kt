package helium314.keyboard.latin.core.link

import helium314.keyboard.latin.ui.component.BoldButton
import helium314.keyboard.latin.ui.component.DialogPane
import helium314.keyboard.latin.ui.component.QuietButton
import helium314.keyboard.latin.ui.component.ZoneSurface
import helium314.keyboard.latin.ui.component.rememberHaptics
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

/**
 * What a link tap shows before anything opens (the intermediary of URLCheck, whose idea it is:
 * https://github.com/TrianguloY/URLCheck, CC BY 4.0, ClearURLs rules LGPL-3.0 by
 * https://github.com/ClearURLs/Rules): the host in large type with its registrable domain in bold
 * (PublicSuffix), what the address may be hiding ([LinkWarnings]), the address cleaned of
 * tracking and detours ([LinkCleaner], [ClearUrls]) to read, select and copy, then Open, Copy,
 * Share and Cancel. Open is the one filled button and opens the cleaned address through [onOpen],
 * the app's own way out (the readers' SafeUriHandler.openOut, SMS's view intent).
 *
 * [expand], when the app has a way to ask a site where its link leads, adds Expand: it follows
 * the redirects on a tap and shows the address they end at (the shortener is contacted, nobody
 * else). Built as the dialogs of the base are: Dialog with DialogPane
 * (https://developer.android.com/develop/ui/compose/components/dialog) and the pane's glass buttons.
 * [extraWarnings] are the app's own findings, listed with the shared ones.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
@Composable
fun LinkSheet(
    url: String,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    extraWarnings: List<LinkWarnings.Warning> = emptyList(),
    expand: (suspend (String) -> String?)? = null
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    // The address on show: the one given, then where Expand found it leads.
    var shown by remember(url) { mutableStateOf(url) }
    var expanding by remember(url) { mutableStateOf(false) }
    var nothingMore by remember(url) { mutableStateOf(false) }
    val clean = remember(shown) { LinkCleaner.clean(shown) }
    val found = remember(shown) { ClearUrls.inspect(shown) }
    val host = remember(clean) { LinkWarnings.host(clean) }
    val warnings = remember(clean, found, extraWarnings) {
        val notes = ArrayList<LinkWarnings.Warning>()
        // What the cleaning took off, said once.
        if (clean != shown.trim()) {
            val detour = LinkWarnings.host(shown)?.encoded != host?.encoded
            notes += LinkWarnings.Warning(
                LinkWarnings.Kind.OTHER, LinkWarnings.Level.NOTE,
                if (detour) "A detour was removed" else "Tracking was removed"
            )
        }
        (extraWarnings + LinkWarnings.of(clean, found) + notes).distinctBy { it.label }
            .sortedByDescending { it.level.ordinal }
    }

    Dialog(onDismissRequest = onDismiss) {
        DialogPane {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val domain = MaterialTheme.colorScheme.onSurface
                val subdomain = MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    buildAnnotatedString {
                        if (host == null) append(clean)
                        else {
                            withStyle(SpanStyle(color = subdomain, fontWeight = FontWeight.Normal)) { append(host.subdomain) }
                            withStyle(SpanStyle(color = domain, fontWeight = FontWeight.Bold)) { append(host.domain) }
                        }
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 4
                )
                warnings.forEach { warning ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.padding(top = 7.dp).size(10.dp).background(levelColor(warning.level), CircleShape)
                        )
                        Text(
                            warning.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
                ZoneSurface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            clean,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
                if (nothingMore) {
                    Text(
                        "It leads nowhere else",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietButton(
                        onClick = {
                            haptics.tick()
                            copy(context, clean)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Copy", maxLines = 1) }
                    QuietButton(
                        onClick = {
                            haptics.tick()
                            share(context, clean)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Share", maxLines = 1) }
                    if (expand != null) {
                        QuietButton(
                            onClick = {
                                haptics.tick()
                                expanding = true
                                nothingMore = false
                                scope.launch {
                                    val end = expand(clean)
                                    expanding = false
                                    if (end != null && LinkCleaner.clean(end) != clean) shown = end else nothingMore = true
                                }
                            },
                            enabled = !expanding,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (expanding) "Expanding" else "Expand", maxLines = 1) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel", maxLines = 1) }
                    BoldButton(
                        onClick = {
                            haptics.firm()
                            onDismiss()
                            onOpen(clean)
                        },
                        filled = true,
                        modifier = Modifier.weight(1f)
                    ) { Text("Open", maxLines = 1) }
                }
            }
        }
    }
}

/** The tone of a warning: red for danger, a warm red for caution (as SMS's links), quiet for a note. */
@Composable
private fun levelColor(level: LinkWarnings.Level): Color = when (level) {
    LinkWarnings.Level.DANGER -> MaterialTheme.colorScheme.error
    LinkWarnings.Level.CAUTION -> lerp(MaterialTheme.colorScheme.error, Color(0xFFE8890C), 0.4f)
    LinkWarnings.Level.NOTE -> MaterialTheme.colorScheme.outline
}

/**
 * The address to the clipboard. From Android 13 the system confirms it itself, before that a toast
 * does (https://developer.android.com/develop/ui/views/touch-and-input/copy-paste#duplicate-notifications).
 */
private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Link", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

/** The address to Android's share sheet (https://developer.android.com/training/sharing/send). */
private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
