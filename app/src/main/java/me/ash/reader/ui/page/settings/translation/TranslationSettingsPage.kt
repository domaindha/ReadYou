package me.ash.reader.ui.page.settings.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.infrastructure.translation.AzureTranslatorProvider
import me.ash.reader.infrastructure.translation.MyMemoryTranslationProvider
import me.ash.reader.infrastructure.translation.LibreTranslateProvider
import me.ash.reader.infrastructure.translation.LingvaTranslationProvider
import me.ash.reader.infrastructure.translation.TranslationApiConfiguration
import me.ash.reader.infrastructure.translation.TranslationEngine
import me.ash.reader.infrastructure.translation.TranslationLanguages
import me.ash.reader.infrastructure.translation.TranslationLanguage
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.theme.palette.onLight
import org.json.JSONObject
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun TranslationSettingsPage(
    onBack: () -> Unit,
    viewModel: TranslationSettingsViewModel = hiltViewModel(),
) {
    val selectedProviderId by viewModel.selectedProviderId.collectAsState()
    val providers = viewModel.availableProviders
    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
    val supportedLanguageTags = selectedProvider?.capabilities?.supportedLanguageTags
    val availableLanguages = remember(selectedProviderId, supportedLanguageTags) {
        supportedLanguageTags?.let { supported ->
            TranslationLanguages.supported.filter { it.tag in supported }
        } ?: TranslationLanguages.supported
    }
    val initialConfig = remember(selectedProviderId) { viewModel.loadApiConfiguration(selectedProviderId) }
    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val cacheStats by viewModel.translationCacheStats.collectAsState()
    val scope = rememberCoroutineScope()

    var targetLanguage by rememberSaveable {
        mutableStateOf(viewModel.selectedTargetLanguage())
    }
    var bilingualReading by rememberSaveable {
        mutableStateOf(viewModel.isBilingualReadingEnabled())
    }
    val articleMetadataTranslationEnabled by viewModel.articleMetadataTranslationEnabled.collectAsState()
    val autoTranslationWifiOnly by viewModel.autoTranslationWifiOnly.collectAsState()
    val autoTranslationDailyLimit by viewModel.autoTranslationDailyLimit.collectAsState()
    val autoTranslationDailyUsage by viewModel.autoTranslationDailyUsage.collectAsState()
    var dailyLimitText by rememberSaveable {
        mutableStateOf(autoTranslationDailyLimit.toString())
    }
    var endpoint by rememberSaveable(selectedProviderId) {
        mutableStateOf(
            initialConfig?.endpoint ?: when (selectedProviderId) {
                AzureTranslatorProvider.PROVIDER_ID -> AzureTranslatorProvider.DEFAULT_ENDPOINT
                LibreTranslateProvider.PROVIDER_ID -> LibreTranslateProvider.DEFAULT_ENDPOINT
                LingvaTranslationProvider.PROVIDER_ID -> LingvaTranslationProvider.DEFAULT_ENDPOINT
                else -> ""
            }
        )
    }
    // Do not place API credentials in Android saved-instance-state bundles.
    var apiKey by remember(selectedProviderId) { mutableStateOf(initialConfig?.apiKey.orEmpty()) }
    var model by rememberSaveable(selectedProviderId) { mutableStateOf(initialConfig?.model.orEmpty()) }
    var timeoutSecondsText by rememberSaveable(selectedProviderId) {
        mutableStateOf((initialConfig?.timeoutSeconds ?: 45L).toString())
    }
    var headersText by rememberSaveable(selectedProviderId) {
        mutableStateOf(
            initialConfig?.headers?.let { headers ->
                JSONObject().apply { headers.forEach { (key, value) -> put(key, value) } }.toString(2)
            } ?: "{}"
        )
    }
    var requestTemplate by remember(selectedProviderId) {
        mutableStateOf(
            initialConfig?.requestTemplate ?: TranslationApiConfiguration.DEFAULT_REQUEST_TEMPLATE
        )
    }
    var responsePath by rememberSaveable(selectedProviderId) {
        mutableStateOf(initialConfig?.responsePath ?: "choices.0.message.content")
    }
    var sourceLanguage by rememberSaveable { mutableStateOf(viewModel.selectedSourceLanguage()) }
    val targetLanguageSupported = supportedLanguageTags?.contains(targetLanguage) ?: true
    val requiresExplicitSourceLanguage = selectedProvider?.capabilities?.requiresExplicitSourceLanguage == true
    val sourceLanguageSupported = if (requiresExplicitSourceLanguage) {
        sourceLanguage != "auto" && supportedLanguageTags?.contains(sourceLanguage) != false
    } else {
        sourceLanguage == "auto" || supportedLanguageTags?.contains(sourceLanguage) != false
    }
    var status by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(selectedProviderId) {
        viewModel.refreshDownloadedModels()
    }

    fun buildConfiguration(): TranslationApiConfiguration {
        val headersObject = JSONObject(headersText)
        val headers = headersObject.keys().asSequence().associateWith {
            headersObject.getString(it)
        }
        return TranslationApiConfiguration(
            endpoint = endpoint.trim(),
            apiKey = apiKey,
            model = model.trim(),
            headers = headers,
            requestTemplate = requestTemplate,
            responsePath = responsePath.trim(),
            timeoutSeconds = timeoutSecondsText.toLongOrNull()
                ?.takeIf { it in 5L..180L }
                ?: throw IllegalArgumentException("请求超时必须设置为 5 到 180 秒。"),
        )
    }

    RYScaffold(
        containerColor = MaterialTheme.colorScheme.surface onLight MaterialTheme.colorScheme.inverseOnSurface,
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onBack,
            )
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DisplayText(text = "文章翻译", desc = "离线翻译与自定义 HTTP/JSON 接口")

                Text("免费云端快速启用", style = MaterialTheme.typography.titleMedium)
                Text(
                    "不需要注册账号、API Key 或手动填写地址。启用后直接使用 Lingva 公共实例，源语言可自动检测。公共服务可能限流或暂时不可用；文章文本会发送到该公共实例。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = !busy,
                    onClick = {
                        runCatching {
                            viewModel.enableFreeCloudProvider(LingvaTranslationProvider.PROVIDER_ID)
                        }.onSuccess {
                            endpoint = LingvaTranslationProvider.DEFAULT_ENDPOINT
                            sourceLanguage = "auto"
                            status = "Lingva 公共云端已配置并设为当前引擎"
                        }.onFailure {
                            status = it.message ?: "免费云端配置失败"
                        }
                    },
                ) { Text("一键启用免费云端翻译") }
                Text(
                    "备用：MyMemory 也无需 API Key，匿名额度约 5,000 字符/天；单次最多 500 UTF-8 字节，并且必须手动指定原文语言。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("目标语言", style = MaterialTheme.typography.titleMedium)
                LanguagePicker(
                    label = "翻译成",
                    selectedTag = targetLanguage,
                    includeAuto = false,
                    languages = availableLanguages,
                    onSelected = { targetLanguage = it },
                )
                if (!targetLanguageSupported) {
                    Text(
                        "当前引擎不支持所选目标语言，请从可用语言中重新选择。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(
                    enabled = targetLanguageSupported,
                    onClick = {
                        runCatching { viewModel.saveTargetLanguage(targetLanguage) }
                            .onSuccess { status = "目标语言已保存" }
                            .onFailure { status = it.message ?: "保存失败" }
                    }
                ) { Text("保存目标语言") }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("双语对照阅读", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "翻译后同时显示原文和译文，原文在前、译文在后。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = bilingualReading,
                        onCheckedChange = {
                            bilingualReading = it
                            viewModel.setBilingualReadingEnabled(it)
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("翻译信息流标题和摘要", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "只翻译屏幕当前可见的文章，译文使用持久化缓存。启用云端引擎会向对应服务发送标题和摘要，并消耗其额度。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = articleMetadataTranslationEnabled,
                        onCheckedChange = viewModel::setArticleMetadataTranslationEnabled,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动翻译仅使用 Wi-Fi", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "只限制自动云端翻译；手动翻译和 ML Kit 离线翻译不受影响。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoTranslationWifiOnly,
                        onCheckedChange = viewModel::setAutoTranslationWifiOnly,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("自动翻译每日上限", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "默认每天 20 篇，当前已使用 $autoTranslationDailyUsage / $autoTranslationDailyLimit 篇。按文章去重，标题摘要和正文共用额度；手动翻译不占用额度。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = dailyLimitText,
                        onValueChange = { dailyLimitText = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("每日自动翻译文章数（1–100）") },
                    )
                    Button(
                        enabled = !busy,
                        onClick = {
                            val limit = dailyLimitText.toIntOrNull()
                            if (limit == null || limit !in 1..100) {
                                status = "每日上限必须设置为 1 到 100 篇。"
                            } else {
                                viewModel.setAutoTranslationDailyLimit(limit)
                                status = "自动翻译每日上限已保存"
                            }
                        },
                    ) { Text("保存每日上限") }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("翻译缓存", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "缓存保存已翻译的文本片段。最多保留 250 条或 32 MB，并按最近使用时间淘汰旧条目。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "当前缓存：${cacheStats.entryCount} 条 · ${formatTranslationCacheSize(cacheStats.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                status = "正在清理翻译缓存…"
                                try {
                                    viewModel.clearTranslationCache()
                                    status = "翻译缓存已清理"
                                } catch (error: Throwable) {
                                    status = error.message ?: "清理缓存失败"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text("清理翻译缓存") }
                }

                Text("翻译引擎", style = MaterialTheme.typography.titleMedium)
                providers.forEach { provider ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedProviderId == provider.id,
                            onClick = { viewModel.selectProvider(provider.id) },
                        )
                        Column {
                            Text(provider.displayName)
                            Text(
                                buildString {
                                    append(
                                        when {
                                            provider.capabilities.requiresNetwork -> "云端服务 · 文章文本会发送到对应服务"
                                            provider.capabilities.supportsModelManagement -> "设备端运行"
                                            else -> "无需网络"
                                        }
                                    )
                                    if (!provider.capabilities.requiresNetwork &&
                                        provider.capabilities.supportsModelManagement
                                    ) append(" · 首次下载模型需要网络")
                                    if (provider.capabilities.supportsBatching) append(" · 支持批量")
                                    if (provider.capabilities.supportsLanguageDetection) append(" · 支持语言检测")
                                    if (provider.capabilities.supportsModelManagement) append(" · 支持模型管理")
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                if (selectedProvider?.capabilities?.requiresNetwork == true) {
                    Text(
                        "第三方数据传输提示：当前引擎需要联网，文章标题、摘要和正文会发送到该引擎配置的服务端进行翻译。切换到 ML Kit 可在设备端处理文章文本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when (selectedProviderId) {
                    LibreTranslateProvider.PROVIDER_ID -> {
                        Text(
                            "LibreTranslate 基于开源 Argos Translate。可以填写自己的服务器地址；自托管通常无需 API Key，托管实例可能要求密钥或有使用额度。文章文本会发送到所填实例。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LingvaTranslationProvider.PROVIDER_ID -> {
                        Text(
                            "Lingva 是实验性翻译前端，实例可用性和接口兼容性由运营者决定。应用优先使用 POST，旧实例不支持时回退到 GET。公共实例可能不稳定，文章文本会发送到所选实例。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AzureTranslatorProvider.PROVIDER_ID -> {
                        Text(
                            "Azure Translator F0 当前官方免费额度为每月 200 万字符；使用前需要在 Azure 创建 Translator 资源并填写密钥。区域型资源还需在请求头 JSON 中填写 Ocp-Apim-Subscription-Region。文章文本会发送到微软服务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MyMemoryTranslationProvider.PROVIDER_ID -> {
                        Text(
                            "MyMemory 无需 API Key。匿名额度为每天 5,000 字符，单次查询最多 500 UTF-8 字节；服务不支持自动检测源语言。它是公共翻译记忆服务，不保证稳定性，建议用于短文本和试用，长文章或自动翻译容易耗尽额度。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            enabled = !busy && sourceLanguageSupported && targetLanguageSupported,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    status = "正在测试 MyMemory（会消耗每日免费额度）…"
                                    try {
                                        val result = viewModel.testProvider(
                                            MyMemoryTranslationProvider.PROVIDER_ID,
                                            sourceLanguage,
                                            targetLanguage,
                                        )
                                        status = "MyMemory 测试成功：" + result
                                    } catch (error: Throwable) {
                                        status = "MyMemory 测试失败：" + (error.message ?: "未知错误")
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                        ) {
                            if (busy) CircularProgressIndicator()
                            else Text("测试 MyMemory（消耗额度）")
                        }
                    }
                }

                if (selectedProvider?.capabilities?.supportsCustomConfiguration == true) {
                val isAzureTranslator = selectedProviderId == AzureTranslatorProvider.PROVIDER_ID
                val isLibreTranslate = selectedProviderId == LibreTranslateProvider.PROVIDER_ID
                val isLingva = selectedProviderId == LingvaTranslationProvider.PROVIDER_ID
                val isFixedProtocolProvider = isAzureTranslator || isLibreTranslate || isLingva
                Text(
                    when {
                        isAzureTranslator -> "Azure Translator 配置"
                        isLibreTranslate -> "LibreTranslate 实例配置"
                        isLingva -> "Lingva 实例配置（实验性）"
                        else -> "自定义 API"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when {
                        isAzureTranslator ->
                            "填入 Azure 资源的 Endpoint 和订阅密钥。单服务 Global 资源通常不需要区域 Header；区域型或多服务资源需要在下方 Header JSON 中填写区域。密钥与 Header 使用 Android Keystore 加密保存。"
                        isLibreTranslate ->
                            "填写实例根地址或完整 /translate 地址。自建服务一般不需要 API Key；托管服务可在此填写密钥。密钥使用 Android Keystore 加密保存。"
                        isLingva ->
                            "填写 Lingva 实例根地址。无需 API Key；可以使用公共实例或自己的部署。此 Provider 处于实验阶段。"
                        else ->
                            "使用 API 时，文章文本会发送给你填写的服务。API Key 默认作为 Bearer Token 发送；配置密钥时只允许 HTTPS 地址，密钥和请求头使用 Android Keystore 加密保存。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("API Endpoint") },
                    placeholder = {
                        Text(
                            when {
                                isAzureTranslator -> AzureTranslatorProvider.DEFAULT_ENDPOINT
                                isLibreTranslate -> LibreTranslateProvider.DEFAULT_ENDPOINT
                                isLingva -> LingvaTranslationProvider.DEFAULT_ENDPOINT
                                else -> "https://example.com/v1/chat/completions"
                            }
                        )
                    },
                )
                if (isAzureTranslator || isLibreTranslate) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = {
                            Text(
                                if (isAzureTranslator) "Azure Subscription Key（必填）"
                                else "API Key（托管实例可能需要）"
                            )
                        },
                    )
                }
                if (!isFixedProtocolProvider) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("模型名称（可选）") },
                    )
                }
                OutlinedTextField(
                    value = timeoutSecondsText,
                    onValueChange = { timeoutSecondsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("请求超时（秒，5–180）") },
                    supportingText = { Text("默认 45 秒，连接、读取和总请求时间均受此限制。") },
                )
                if (!isFixedProtocolProvider || isAzureTranslator) {
                    OutlinedTextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                        label = {
                            Text(
                                if (isAzureTranslator) "附加请求头 JSON（区域资源填写 Region）"
                                else "请求头 JSON"
                            )
                        },
                        placeholder = {
                            Text(
                                if (isAzureTranslator) """{"Ocp-Apim-Subscription-Region":"eastasia"}"""
                                else """{"Authorization":"Bearer {{apiKey}}"}"""
                            )
                        },
                    )
                }
                if (!isFixedProtocolProvider) {
                    OutlinedTextField(
                        value = requestTemplate,
                        onValueChange = { requestTemplate = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        maxLines = 12,
                        label = { Text("请求 JSON 模板") },
                    )
                    Text(
                        "可用占位符：{{text}}、{{sourceLanguage}}、{{targetLanguage}}、{{model}}。占位符放在 JSON 字符串值中，内容会自动转义。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = responsePath,
                        onValueChange = { responsePath = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("响应文本 JSON 路径") },
                        placeholder = { Text("choices.0.message.content") },
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            try {
                                val config = buildConfiguration()
                                viewModel.saveApiConfiguration(config, selectedProviderId)
                                viewModel.saveTargetLanguage(targetLanguage)
                                status = "API 配置已保存"
                            } catch (error: Throwable) {
                                status = error.message ?: "配置无效"
                            }
                        },
                    ) { Text("保存 API 配置") }
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                status = "正在测试接口…"
                                try {
                                    val result = viewModel.testApi(
                                        buildConfiguration(),
                                        targetLanguage,
                                        selectedProviderId,
                                    )
                                    status = "接口测试成功：$result"
                                } catch (error: Throwable) {
                                    status = "测试失败：${error.message ?: "未知错误"}"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) {
                        if (busy) CircularProgressIndicator()
                        else Text("测试接口")
                    }
                }

                }

                Text("源语言", style = MaterialTheme.typography.titleMedium)
                LanguagePicker(
                    label = "原文语言",
                    selectedTag = sourceLanguage,
                    includeAuto = !requiresExplicitSourceLanguage,
                    languages = availableLanguages,
                    onSelected = {
                        sourceLanguage = it
                        viewModel.saveSourceLanguage(it)
                    },
                )
                Text(
                    if (requiresExplicitSourceLanguage) {
                        "MyMemory 不支持自动识别原文语言，请选择文章的实际源语言。目标语言用于文章正文和标题。"
                    } else {
                        "自动检测适合大多数文章；也可以手动指定原文语言。目标语言会用于文章正文和标题。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!sourceLanguageSupported) {
                    Text(
                        if (requiresExplicitSourceLanguage && sourceLanguage == "auto") {
                            "当前引擎不支持自动检测，请手动选择原文语言。"
                        } else {
                            "当前引擎不支持所选源语言，请选择可用语言。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (selectedProvider?.capabilities?.supportsModelManagement == true) {
                Text("本地语言模型", style = MaterialTheme.typography.titleMedium)
                Text(
                    "首次使用语言模型需要联网下载；下载完成后，翻译在设备端离线运行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "预下载前请先选择具体且受支持的源语言和目标语言。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = !busy && sourceLanguage != "auto" &&
                        sourceLanguageSupported && targetLanguageSupported,
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "正在下载语言模型…"
                            try {
                                require(sourceLanguage != "auto") { "下载离线模型前，请先选择具体的源语言。" }
                                viewModel.downloadModel(sourceLanguage, targetLanguage)
                                status = "语言模型下载完成"
                            } catch (error: Throwable) {
                                status = "下载失败：${error.message ?: "未知错误"}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("下载语言模型") }

                if (downloadedModels.isEmpty()) {
                    Text(
                        "尚未发现已下载的语言模型",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    downloadedModels.forEach { languageTag ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${Locale.forLanguageTag(languageTag).displayName}（$languageTag）",
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            viewModel.deleteModel(languageTag)
                                            status = "模型已删除"
                                        } catch (error: Throwable) {
                                            status = error.message ?: "删除失败"
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }
                }

                if (status.isNotBlank()) {
                    Text(status, color = MaterialTheme.colorScheme.primary)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        },
    )
}


@Composable
private fun LanguagePicker(
    label: String,
    selectedTag: String,
    includeAuto: Boolean,
    languages: List<TranslationLanguage> = TranslationLanguages.supported,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = if (selectedTag == "auto") "自动检测" else
        TranslationLanguages.supported.firstOrNull { it.tag == selectedTag }?.label ?: selectedTag
    Column {
        TextButton(onClick = { expanded = true }) {
            Text("$label：$selectedLabel  ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (includeAuto) {
                DropdownMenuItem(text = { Text("自动检测") }, onClick = { onSelected("auto"); expanded = false })
            }
            languages.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.label + " (" + language.tag + ")") },
                    onClick = { onSelected(language.tag); expanded = false },
                )
            }
        }
    }
}


private fun formatTranslationCacheSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}
