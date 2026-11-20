package me.ash.reader.ui.page.home.feeds

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.translation.TranslationLanguages
import me.ash.reader.infrastructure.translation.TranslationProviderDescriptor
import me.ash.reader.ui.component.base.RYSelectionChip
import me.ash.reader.ui.component.base.Subtitle
import me.ash.reader.ui.theme.palette.alwaysLight

@Composable
fun FeedOptionView(
    modifier: Modifier = Modifier,
    link: String = "",
    groups: List<Group> = emptyList(),
    selectedAllowNotificationPreset: Boolean = false,
    selectedParseFullContentPreset: Boolean = false,
    selectedOpenInBrowserPreset: Boolean = false,
    selectedAutoTranslatePreset: Boolean = false,
    selectedOnlyNewArticlesPreset: Boolean = false,
    feedProviderOverride: String? = null,
    feedSourceLanguageOverride: String? = null,
    translationProviders: List<TranslationProviderDescriptor> = emptyList(),
    isMoveToGroup: Boolean = false,
    showGroup: Boolean = true,
    showUnsubscribe: Boolean = true,
    notSubscribeMode: Boolean = false,
    selectedGroupId: String = "",
    allowNotificationPresetOnClick: () -> Unit = {},
    parseFullContentPresetOnClick: () -> Unit = {},
    openInBrowserPresetOnClick: () -> Unit = {},
    autoTranslatePresetOnClick: () -> Unit = {},
    clearArticlesOnClick: () -> Unit = {},
    unsubscribeOnClick: () -> Unit = {},
    onGroupClick: (groupId: String) -> Unit = {},
    onAddNewGroup: () -> Unit = {},
    onFeedUrlClick: () -> Unit = {},
    onFeedUrlLongClick: () -> Unit = {},
    onlyNewArticlesPresetOnClick: () -> Unit = {},
    onFeedProviderChange: (String?) -> Unit = {},
    onFeedSourceLanguageChange: (String?) -> Unit = {},
) {

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        EditableUrl(text = link, onClick = onFeedUrlClick, onLongClick = onFeedUrlLongClick)
        Spacer(modifier = Modifier.height(26.dp))

        Preset(
            selectedAllowNotificationPreset = selectedAllowNotificationPreset,
            selectedParseFullContentPreset = selectedParseFullContentPreset,
            selectedOpenInBrowserPreset = selectedOpenInBrowserPreset,
            selectedAutoTranslatePreset = selectedAutoTranslatePreset,
            selectedOnlyNewArticlesPreset = selectedOnlyNewArticlesPreset,
            showUnsubscribe = showUnsubscribe,
            notSubscribeMode = notSubscribeMode,
            allowNotificationPresetOnClick = allowNotificationPresetOnClick,
            parseFullContentPresetOnClick = parseFullContentPresetOnClick,
            openInBrowserPresetOnClick = openInBrowserPresetOnClick,
            autoTranslatePresetOnClick = autoTranslatePresetOnClick,
            onlyNewArticlesPresetOnClick = onlyNewArticlesPresetOnClick,
            clearArticlesOnClick = clearArticlesOnClick,
            unsubscribeOnClick = unsubscribeOnClick,
        )

        if (notSubscribeMode) {
            Spacer(modifier = Modifier.height(10.dp))
            Subtitle(text = "此订阅源的翻译规则")
            TranslationOverridePicker(
                providerOverride = feedProviderOverride,
                sourceLanguageOverride = feedSourceLanguageOverride,
                providers = translationProviders,
                onProviderSelected = onFeedProviderChange,
                onSourceLanguageSelected = onFeedSourceLanguageChange,
            )
        }

        if (showGroup) {
            Spacer(modifier = Modifier.height(26.dp))

            AddToGroup(
                isMoveToGroup = isMoveToGroup,
                groups = groups,
                selectedGroupId = selectedGroupId,
                onGroupClick = onGroupClick,
                onAddNewGroup = onAddNewGroup,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditableUrl(text: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            modifier =
                Modifier.clip(MaterialTheme.shapes.small)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            text = text,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Preset(
    selectedAllowNotificationPreset: Boolean = false,
    selectedParseFullContentPreset: Boolean = false,
    selectedOpenInBrowserPreset: Boolean = false,
    selectedAutoTranslatePreset: Boolean = false,
    selectedOnlyNewArticlesPreset: Boolean = false,
    showUnsubscribe: Boolean = true,
    notSubscribeMode: Boolean = false,
    allowNotificationPresetOnClick: () -> Unit = {},
    parseFullContentPresetOnClick: () -> Unit = {},
    openInBrowserPresetOnClick: () -> Unit = {},
    autoTranslatePresetOnClick: () -> Unit = {},
    onlyNewArticlesPresetOnClick: () -> Unit = {},
    clearArticlesOnClick: () -> Unit = {},
    unsubscribeOnClick: () -> Unit = {},
) {
    Subtitle(text = stringResource(R.string.reading_page))
    Spacer(modifier = Modifier.height(10.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start)) {
        item {
            RYSelectionChip(
                modifier = Modifier,
                content = stringResource(R.string.parse_full_content),
                selected = selectedParseFullContentPreset,
                selectedIcon = {
                    Icon(
                        modifier = Modifier.padding(start = 8.dp).size(20.dp),
                        imageVector = Icons.AutoMirrored.Outlined.Article,
                        contentDescription = stringResource(R.string.parse_full_content),
                        tint = MaterialTheme.colorScheme.onSurface alwaysLight true,
                    )
                },
            ) {
                parseFullContentPresetOnClick()
            }
        }
        item {
            RYSelectionChip(
                modifier = Modifier,
                content = stringResource(R.string.open_in_browser),
                selected = selectedOpenInBrowserPreset,
                selectedIcon = {
                    Icon(
                        modifier = Modifier.padding(start = 8.dp).size(20.dp),
                        imageVector = Icons.Outlined.OpenInBrowser,
                        contentDescription = stringResource(R.string.open_in_browser),
                        tint = MaterialTheme.colorScheme.onSurface alwaysLight true,
                    )
                },
            ) {
                openInBrowserPresetOnClick()
            }
        }
        item {
            RYSelectionChip(
                modifier = Modifier,
                content = "自动翻译",
                selected = selectedAutoTranslatePreset,
            ) {
                autoTranslatePresetOnClick()
            }
        }
        if (notSubscribeMode) {
            item {
                RYSelectionChip(
                    modifier = Modifier,
                    content = "仅新文章",
                    selected = selectedOnlyNewArticlesPreset,
                ) {
                    onlyNewArticlesPresetOnClick()
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(26.dp))

    Subtitle(text = stringResource(R.string.preset))
    Spacer(modifier = Modifier.height(10.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        RYSelectionChip(
            modifier = Modifier,
            content = stringResource(R.string.allow_notification),
            selected = selectedAllowNotificationPreset,
            selectedIcon = {
                Icon(
                    modifier = Modifier.padding(start = 8.dp).size(20.dp),
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = stringResource(R.string.allow_notification),
                    tint = MaterialTheme.colorScheme.onSurface alwaysLight true,
                )
            },
        ) {
            allowNotificationPresetOnClick()
        }
        if (notSubscribeMode) {
            RYSelectionChip(
                modifier = Modifier,
                content = stringResource(R.string.clear_articles),
                selected = false,
            ) {
                clearArticlesOnClick()
            }
            if (showUnsubscribe) {
                RYSelectionChip(
                    modifier = Modifier,
                    content = stringResource(R.string.unsubscribe),
                    selected = false,
                ) {
                    unsubscribeOnClick()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddToGroup(
    isMoveToGroup: Boolean = false,
    groups: List<Group>,
    selectedGroupId: String,
    onGroupClick: (groupId: String) -> Unit = {},
    onAddNewGroup: () -> Unit = {},
) {
    Subtitle(
        text = stringResource(if (isMoveToGroup) R.string.move_to_group else R.string.add_to_group)
    )
    Spacer(modifier = Modifier.height(10.dp))

    if (groups.size > 6) {
        LazyRow(verticalAlignment = Alignment.CenterVertically) {
            items(groups) {
                RYSelectionChip(
                    modifier = Modifier,
                    content = it.name,
                    selected = it.id == selectedGroupId,
                ) {
                    onGroupClick(it.id)
                }
                Spacer(modifier = Modifier.width(10.dp))
            }
            item { NewGroupButton(onAddNewGroup, Modifier) }
        }
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            groups.forEach {
                RYSelectionChip(
                    modifier = Modifier,
                    content = it.name,
                    selected = it.id == selectedGroupId,
                ) {
                    onGroupClick(it.id)
                }
            }
            NewGroupButton(onAddNewGroup, Modifier.align(Alignment.CenterVertically))
        }
    }
}


@Composable
private fun TranslationOverridePicker(
    providerOverride: String?,
    sourceLanguageOverride: String?,
    providers: List<TranslationProviderDescriptor>,
    onProviderSelected: (String?) -> Unit,
    onSourceLanguageSelected: (String?) -> Unit,
) {
    val providerExpanded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val sourceExpanded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val providerLabel = providers.firstOrNull { it.id == providerOverride }?.displayName
        ?: "跟随全局设置"
    val sourceLabel = when (sourceLanguageOverride) {
        null -> "跟随全局设置"
        "auto" -> "自动检测"
        else -> TranslationLanguages.supported.firstOrNull { it.tag == sourceLanguageOverride }?.label
            ?: sourceLanguageOverride
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = { providerExpanded.value = true }) {
            Text("翻译引擎：$providerLabel  ▾")
        }
        DropdownMenu(
            expanded = providerExpanded.value,
            onDismissRequest = { providerExpanded.value = false },
        ) {
            DropdownMenuItem(
                text = { Text("跟随全局设置") },
                onClick = { onProviderSelected(null); providerExpanded.value = false },
            )
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.displayName) },
                    onClick = { onProviderSelected(provider.id); providerExpanded.value = false },
                )
            }
        }
        TextButton(onClick = { sourceExpanded.value = true }) {
            Text("原文语言：$sourceLabel  ▾")
        }
        DropdownMenu(
            expanded = sourceExpanded.value,
            onDismissRequest = { sourceExpanded.value = false },
        ) {
            DropdownMenuItem(
                text = { Text("跟随全局设置") },
                onClick = { onSourceLanguageSelected(null); sourceExpanded.value = false },
            )
            DropdownMenuItem(
                text = { Text("自动检测") },
                onClick = { onSourceLanguageSelected("auto"); sourceExpanded.value = false },
            )
            TranslationLanguages.supported.forEach { language ->
                DropdownMenuItem(
                    text = { Text("${language.label} (${language.tag})") },
                    onClick = { onSourceLanguageSelected(language.tag); sourceExpanded.value = false },
                )
            }
        }
        Text(
            text = "启用“仅新文章”后，只自动翻译规则启用后发布的文章；旧文章仍可手动翻译。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NewGroupButton(onAddNewGroup: () -> Unit, modifier: Modifier) {
    Box(
        modifier =
            modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onAddNewGroup() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            modifier = Modifier.size(20.dp),
            imageVector = Icons.Outlined.Add,
            contentDescription = stringResource(R.string.create_new_group),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
