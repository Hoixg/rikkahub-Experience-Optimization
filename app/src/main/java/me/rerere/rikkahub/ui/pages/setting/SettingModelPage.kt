package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.AUTO_COMPACTION_THRESHOLD_STEP_PERCENT
import me.rerere.rikkahub.utils.MAX_AUTO_COMPACTION_THRESHOLD_PERCENT
import me.rerere.rikkahub.utils.MIN_AUTO_COMPACTION_THRESHOLD_PERCENT
import me.rerere.rikkahub.utils.formatContextLength
import me.rerere.rikkahub.utils.normalizeAutoCompactionThresholdPercent
import me.rerere.rikkahub.utils.parseContextLengthInput
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_model_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor
            ) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(HugeIcons.AiBrain01, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_model)) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Icon(HugeIcons.AiEditing, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_prompt)) }
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { contentPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> ModelSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
                1 -> PromptSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
            }
        }
    }
}

@Composable
private fun ModelSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    var thresholdSliderValue by remember(settings.autoCompactionThresholdPercent) {
        mutableFloatStateOf(settings.autoCompactionThresholdPercent.toFloat())
    }
    var isThresholdSliderDragging by remember { mutableStateOf(false) }
    val settledThresholdSliderValue by animateFloatAsState(
        targetValue = thresholdSliderValue,
        animationSpec = if (isThresholdSliderDragging) {
            snap()
        } else {
            spring(dampingRatio = 0.82f, stiffness = 700f)
        },
        label = "auto_compaction_threshold_slider",
    )
    val visibleThresholdPercent = normalizeAutoCompactionThresholdPercent(
        (if (isThresholdSliderDragging) thresholdSliderValue else settledThresholdSliderValue).roundToInt()
    )
    val activeThresholdTickColor = MaterialTheme.colorScheme.surface
    val inactiveThresholdTickColor = MaterialTheme.colorScheme.onSurfaceVariant
    var showTokenLimitDialog by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_chat_model),
                description = stringResource(R.string.setting_model_page_chat_model_desc),
                modelId = settings.chatModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(chatModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_fast_model),
                description = stringResource(R.string.setting_model_page_fast_model_desc),
                modelId = settings.fastModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(fastModelId = it.id)) },
                reasoningLevel = settings.fastModelReasoningLevel,
                onUpdateReasoningLevel = {
                    vm.updateSettings(settings.copy(fastModelReasoningLevel = it))
                },
            )
        }
        item {
            SuggestionSettingItem(
                settings = settings,
                vm = vm,
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_translate_model),
                description = stringResource(R.string.setting_model_page_translate_model_desc),
                modelId = settings.translateModeId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(translateModeId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_ocr_model),
                description = stringResource(R.string.setting_model_page_ocr_model_desc),
                modelId = settings.ocrModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(ocrModelId = it.id)) },
            )
        }
        item {
            CardGroup {
                item(
                    headlineContent = {
                        Text(stringResource(R.string.setting_model_page_enable_auto_compaction))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_model_page_enable_auto_compaction_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.enableAutoCompaction,
                            onCheckedChange = {
                                vm.updateSettings(settings.copy(enableAutoCompaction = it))
                            },
                        )
                    },
                )
                if (settings.enableAutoCompaction) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_model_page_auto_compaction_threshold))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.setting_model_page_auto_compaction_threshold_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Slider(
                                        value = if (isThresholdSliderDragging) {
                                            thresholdSliderValue
                                        } else {
                                            settledThresholdSliderValue
                                        },
                                        onValueChange = { value ->
                                            isThresholdSliderDragging = true
                                            thresholdSliderValue = value
                                        },
                                        onValueChangeFinished = {
                                            val snappedPercent = normalizeAutoCompactionThresholdPercent(
                                                thresholdSliderValue.roundToInt()
                                            )
                                            isThresholdSliderDragging = false
                                            thresholdSliderValue = snappedPercent.toFloat()
                                            if (snappedPercent != settings.autoCompactionThresholdPercent) {
                                                vm.updateSettings(
                                                    settings.copy(autoCompactionThresholdPercent = snappedPercent)
                                                )
                                            }
                                        },
                                        valueRange = MIN_AUTO_COMPACTION_THRESHOLD_PERCENT.toFloat()..
                                            MAX_AUTO_COMPACTION_THRESHOLD_PERCENT.toFloat(),
                                        steps = 0,
                                        modifier = Modifier
                                            .weight(1f)
                                            .drawWithContent {
                                                drawContent()
                                                val horizontalInset = 10.dp.toPx()
                                                val trackWidth = size.width - horizontalInset * 2
                                                val range = MAX_AUTO_COMPACTION_THRESHOLD_PERCENT -
                                                    MIN_AUTO_COMPACTION_THRESHOLD_PERCENT
                                                val sliderValue = if (isThresholdSliderDragging) {
                                                    thresholdSliderValue
                                                } else {
                                                    settledThresholdSliderValue
                                                }
                                                val thumbFraction = (
                                                    (sliderValue - MIN_AUTO_COMPACTION_THRESHOLD_PERCENT) / range
                                                    ).coerceIn(0f, 1f)
                                                val thumbX = horizontalInset + trackWidth * thumbFraction
                                                val thumbExclusionRadius = 11.dp.toPx()
                                                for (step in 1 until range / AUTO_COMPACTION_THRESHOLD_STEP_PERCENT) {
                                                    val tickX = horizontalInset + trackWidth *
                                                        step / (range / AUTO_COMPACTION_THRESHOLD_STEP_PERCENT)
                                                    if (kotlin.math.abs(tickX - thumbX) > thumbExclusionRadius) {
                                                            drawCircle(
                                                            color = if (tickX < thumbX) {
                                                                activeThresholdTickColor
                                                            } else {
                                                                inactiveThresholdTickColor
                                                            },
                                                            radius = 2.dp.toPx(),
                                                            center = Offset(tickX, size.height / 2),
                                                        )
                                                    }
                                                }
                                            },
                                    )
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.padding(start = 8.dp),
                                    ) {
                                        Text(
                                            text = "$visibleThresholdPercent%",
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    listOf(50, 65, 80, 95).forEach { value ->
                                        Text(
                                            text = "$value%",
                                            color = if (value == visibleThresholdPercent) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
        if (settings.enableAutoCompaction) {
            item {
                CardGroup {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_model_page_auto_compaction_token_limit))
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.setting_model_page_auto_compaction_token_limit_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            OutlinedButton(onClick = { showTokenLimitDialog = true }) {
                                Text(
                                    settings.autoCompactionTokenLimit?.let(::formatContextLength)
                                        ?: stringResource(R.string.setting_model_page_auto_compaction_token_limit_unlimited)
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    if (showTokenLimitDialog) {
        AutoCompactionTokenLimitDialog(
            initialValue = settings.autoCompactionTokenLimit,
            onConfirm = { value ->
                vm.updateSettings(settings.copy(autoCompactionTokenLimit = value))
                showTokenLimitDialog = false
            },
            onDismiss = { showTokenLimitDialog = false },
        )
    }
}

@Composable
private fun AutoCompactionTokenLimitDialog(
    initialValue: Int?,
    onConfirm: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initialValue) { mutableStateOf(formatContextLength(initialValue)) }
    var hasError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_model_page_auto_compaction_token_limit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.setting_model_page_auto_compaction_token_limit_dialog_desc))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        hasError = false
                    },
                    placeholder = { Text(stringResource(R.string.setting_model_page_auto_compaction_token_limit_placeholder)) },
                    singleLine = true,
                    isError = hasError,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasError) {
                    Text(
                        text = stringResource(R.string.setting_model_page_auto_compaction_token_limit_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = if (text.isBlank()) null else parseContextLengthInput(text)
                    if (text.isNotBlank() && parsed == null) {
                        hasError = true
                    } else {
                        onConfirm(parsed)
                    }
                },
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun SuggestionSettingItem(
    settings: Settings,
    vm: SettingVM,
) {
    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.setting_model_page_enable_suggestion)) },
            trailingContent = {
                Switch(
                    checked = settings.enableSuggestion,
                    onCheckedChange = {
                        vm.updateSettings(settings.copy(enableSuggestion = it))
                    }
                )
            },
        )
    }
}

@Composable
private fun ModelSettingItem(
    title: String,
    description: String,
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    onSelect: (Model) -> Unit,
    reasoningLevel: ReasoningLevel? = null,
    onUpdateReasoningLevel: ((ReasoningLevel) -> Unit)? = null,
) {
    val state = rememberModelListState(
        modelId = modelId,
        providers = providers,
        type = ModelType.CHAT,
    )

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                onClick = { state.open() },
                headlineContent = { Text(title) },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = state.currentModel?.displayName
                                ?: stringResource(R.string.model_list_select_model),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            HugeIcons.ArrowRight01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
            )
            if (reasoningLevel != null && onUpdateReasoningLevel != null) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                    trailingContent = {
                        ReasoningButton(
                            reasoningLevel = reasoningLevel,
                            onUpdateReasoningLevel = onUpdateReasoningLevel,
                        )
                    },
                )
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    ModelListSheet(state = state, onSelect = onSelect)
}
