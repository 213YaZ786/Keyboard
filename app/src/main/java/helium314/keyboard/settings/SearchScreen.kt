// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.navigation.LocalReadableInset
import helium314.keyboard.latin.navigation.ReadableScroll
import helium314.keyboard.latin.ui.component.FloatingAction
import helium314.keyboard.latin.ui.component.FloatingFrame
import helium314.keyboard.latin.ui.component.FloatingPane
import helium314.keyboard.latin.ui.component.FloatingTop
import helium314.keyboard.latin.ui.component.SearchPill
import helium314.keyboard.latin.ui.component.TopActionSize
import helium314.keyboard.latin.ui.component.ZoneSurface
import helium314.keyboard.latin.ui.icon.AppIcons
import helium314.keyboard.latin.utils.CloseIcon
import helium314.keyboard.latin.utils.SearchIcon
import helium314.keyboard.settings.preferences.PreferenceSection

// Keyboard: the settings in liquid glass, ported from Calculator's SettingsScreen (FloatingFrame, FloatingTop,
// FloatingAction, Section). Source:
// https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/feature/settings/SettingsScreen.kt

/**
 * The room the floating top takes at the top of the window: a page's own scrolling content starts below it
 * and runs under it. Provided as a CompositionLocal, https://developer.android.com/develop/ui/compose/compositionlocal
 */
val LocalFloatingPadding = compositionLocalOf { PaddingValues() }

/**
 * Keyboard: easter egg 5. Opens the screen's search field, which takes the keyboard at once (SearchPill autoFocus), for
 * what is in the page and needs the keyboard up: the version row of About on its seventh tap.
 * Provided as a CompositionLocal, https://developer.android.com/develop/ui/compose/compositionlocal
 */
val LocalOpenSearch = compositionLocalOf<() -> Unit> { {} }

@Composable
fun SearchSettingsScreen(
    onClickBack: () -> Unit,
    title: String,
    settings: List<Any?>,
    content: @Composable (ColumnScope.() -> Unit)? = null // overrides settings if not null
) {
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        content = {
            if (content != null) content()
            else {
                SettingsPage {
                    SettingsSections(settings) { SettingsActivity.settingsContainer[it]?.Preference() }
                }
                // lazyColumn has janky scroll for a while (not sure why compose gets smoother after a while)
                // maybe related to unnecessary recompositions? but even for just displaying text it's there
                // didn't manage to improve things with @Immutable list wrapper and other lazy list hints
                // so for now: just use "normal" Column
                //  even though it takes up to ~50% longer to load it's much better UX
                //  and the missing appear animations could be added
            }
        },
        filteredItems = { SettingsActivity.settingsContainer.filter(it) },
        itemContent = { it.Preference() }
    )
}

/**
 * Keyboard: the page's list (an Int is a category's title, any other item a preference, null one that is
 * hidden for now) in Calculator's Sections: each category its title, then one zone holding its rows.
 * The preferences before the first category share a zone without a title; a category with nothing
 * to show is left out. Same list as upstream's Column of PreferenceCategory and preferences.
 * Source: Section in https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/feature/settings/SettingsScreen.kt
 */
@Composable
fun SettingsSections(items: List<Any?>, preference: @Composable (Any) -> Unit) {
    val groups = mutableListOf<Pair<Int?, MutableList<Any?>>>()
    items.forEach {
        if (it is Int) groups.add(it to mutableListOf())
        else {
            if (groups.isEmpty()) groups.add(null to mutableListOf())
            groups.last().second.add(it)
        }
    }
    groups.forEach { (titleId, group) ->
        if (group.any { it != null }) {
            PreferenceSection(titleId?.let { stringResource(it) }) {
                group.forEach {
                    // this only animates appearing prefs
                    // a solution would be using a list(visible to key)
                    AnimatedVisibility(visible = it != null) {
                        if (it != null) preference(it)
                    }
                }
            }
        }
    }
}

/**
 * Keyboard: a page that fills the window and scrolls under the floating top, as the Column of Calculator's
 * SettingsScreen (verticalScroll, a Spacer of the top padding, room at the end). Source:
 * https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/feature/settings/SettingsScreen.kt
 */
@Composable
fun SettingsPage(scrollState: ScrollState = rememberScrollState(), content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = LocalReadableInset.current)
    ) {
        Spacer(Modifier.height(LocalFloatingPadding.current.calculateTopPadding()))
        content()
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
    }
}

@Composable
fun <T: Any?> SearchScreen(
    onClickBack: () -> Unit,
    title: @Composable () -> Unit,
    filteredItems: (String) -> List<T>,
    itemContent: @Composable (T) -> Unit,
    icon: @Composable (() -> Unit)? = null,
    menu: List<Pair<String, () -> Unit>>? = null,
    content: @Composable (ColumnScope.() -> Unit)? = null,
) {
    // searchText and showSearch should have the same remember or rememberSaveable
    // saveable survives orientation changes and switching between screens, but shows the
    // keyboard in unexpected situations such as going back from another screen, which is rather annoying
    var searchText by remember { mutableStateOf(TextFieldValue()) }
    var showSearch by remember { mutableStateOf(false) }
    fun setShowSearch(value: Boolean) {
        showSearch = value
        if (!value) searchText = TextFieldValue()
    }
    BackHandler {
        if (showSearch || searchText.text.isNotEmpty()) setShowSearch(false)
        else onClickBack()
    }
    val backLabel = stringResource(R.string.spoken_description_action_previous)
    val searchLabel = stringResource(R.string.label_search_key)
    // Keyboard: no bar. The content takes the whole window and scrolls under a FloatingTop (the title in a pill,
    // back and search as round panes of glass), inside a FloatingFrame, as Calculator's SettingsScreen.
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        ReadableScroll {
            FloatingFrame(
                bottom = 0.dp,
                top = {
                    val back: @Composable () -> Unit = {
                        FloatingAction(AppIcons.ArrowBack, backLabel, { if (showSearch) setShowSearch(false) else onClickBack() })
                    }
                    val action: @Composable () -> Unit = {
                        // a screen's own action takes the place of the search, as a FloatingAction of its own
                        if (icon == null) FloatingAction(AppIcons.Search, searchLabel, { setShowSearch(!showSearch) })
                        else icon()
                    }
                    if (menu == null)
                        FloatingTop(title = null, center = { TitleChip(title) }, leading = back, trailing = action)
                    else
                        TopWithMenu(title = { TitleChip(title) }, leading = back, action = action, menu = menu)
                    // the search field, once opened, takes the keyboard at once
                    AnimatedVisibility(visible = showSearch, modifier = Modifier.fillMaxWidth()) {
                        SearchPill(
                            value = searchText.text,
                            onChange = { searchText = TextFieldValue(it) },
                            hint = stringResource(R.string.search_field_placeholder),
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            floating = true,
                            autoFocus = true,
                            onClose = { setShowSearch(false) }
                        )
                    }
                }
            ) { padding ->
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.bodyLarge,
                    LocalFloatingPadding provides padding,
                    LocalOpenSearch provides { setShowSearch(true) }
                ) {
                    if (searchText.text.isBlank() && content != null) {
                        Column(Modifier.fillMaxSize()) {
                            content()
                        }
                    } else {
                        val items = filteredItems(searchText.text)
                        // A long list stays lazy: its rows are zones side by side, the first and last rounded
                        // like the ends of one zone (segmented list, Material 3 Expressive).
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp + LocalReadableInset.current,
                                end = 16.dp + LocalReadableInset.current,
                                top = padding.calculateTopPadding() + 8.dp,
                                bottom = 24.dp + WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
                            ),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            itemsIndexed(items) { index, item ->
                                ZoneSurface(shape = segmentShape(index, items.size), modifier = Modifier.fillMaxWidth()) {
                                    itemContent(item)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The screen's name on a small pill of glass, whatever it holds (a text, two lines, a field to type in).
 * Same pane and type as TitlePill in latin/ui/component/FloatingPane.kt.
 */
@Composable
private fun TitleChip(title: @Composable () -> Unit) {
    FloatingPane(shape = CircleShape) {
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.titleMedium) {
            Box(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) { title() }
        }
    }
}

/**
 * The shape of the row [index] of [count] in a list of rows side by side: its outer corners round, its inner
 * ones small. Material 3's segmented list, androidx.compose.material3 ListItemDefaults.segmentedShapes
 * (https://developer.android.com/reference/kotlin/androidx/compose/material3/ListItemDefaults).
 */
private fun segmentShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 24.dp
    val inner = 6.dp
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/**
 * FloatingTop with a menu pane beside the first action: same row, with room for two round panes on
 * each side so the name stays centred (FloatingTop in latin/ui/component/FloatingPane.kt).
 */
@Composable
private fun TopWithMenu(
    title: @Composable () -> Unit,
    leading: @Composable () -> Unit,
    action: @Composable () -> Unit,
    menu: List<Pair<String, () -> Unit>>
) {
    val side = TopActionSize * 2 + 8.dp
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(side).height(TopActionSize), contentAlignment = Alignment.CenterStart) { leading() }
        Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { title() }
        Row(Modifier.width(side), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            action()
            Box {
                var showMenu by remember { mutableStateOf(false) }
                FloatingAction(AppIcons.More, "menu", { showMenu = true })
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    menu.forEach {
                        DropdownMenuItem(
                            text = { Text(it.first) },
                            onClick = { showMenu = false; it.second() }
                        )
                    }
                }
            }
        }
    }
}

// from StreetComplete
/** Expandable text field that can be dismissed and requests focus when it is expanded */
@Composable
fun ExpandableSearchField(
    expanded: Boolean,
    onDismiss: () -> Unit,
    search: TextFieldValue,
    onSearchChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    colors: TextFieldColors = TextFieldDefaults.colors(),
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(expanded) {
        if (expanded) focusRequester.requestFocus()
    }
    AnimatedVisibility(visible = expanded, modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = modifier.focusRequester(focusRequester),
            leadingIcon = { SearchIcon() },
            trailingIcon = { IconButton(onClick = {
                if (search.text.isBlank()) onDismiss()
                else onSearchChange(TextFieldValue())
            }) { CloseIcon(android.R.string.cancel) } },
            singleLine = true,
            colors = colors,
            textStyle = contentTextDirectionStyle,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
    }
}
