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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.infrastructure.translation.TranslationApiConfiguration
import me.ash.reader.infrastructure.translation.TranslationEngine
import me.ash.reader.infrastructure.translation.TranslationLanguages
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
    val initialConfig = remember { viewModel.loadApiConfiguration() }
    val selectedEngine by viewModel.selectedEngine.collectAsState()
    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val scope = rememberCoroutineScope()

    var targetLanguage by rememberSaveable {
        mutableStateOf(viewModel.selectedTargetLanguage())
    }
    var endpoint by rememberSaveable { mutableStateOf(initialConfig?.endpoint.orEmpty()) }
    // Do not place API credentials in Android saved-instance-state bundles.
    var apiKey by remember { mutableStateOf(initialConfig?.apiKey.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(initialConfig?.model.orEmpty()) }
    var headersText by rememberSaveable {
        mutableStateOf(
            initialConfig?.headers?.let { headers ->
                JSONObject().apply { headers.forEach { (key, value) -> put(key, value) } }.toString(2)
            } ?: "{}"
        )
    }
    var requestTemplate by remember {
        mutableStateOf(
            initialConfig?.requestTemplate ?: TranslationApiConfiguration.DEFAULT_REQUEST_TEMPLATE
        )
    }
    var responsePath by rememberSaveable {
        mutableStateOf(initialConfig?.responsePath ?: "choices.0.message.content")
    }
    var sourceLanguage by rememberSaveable { mutableStateOf(viewModel.selectedSourceLanguage()) }
    var status by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
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
            timeoutSeconds = initialConfig?.timeoutSeconds ?: 45L,
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

                Text("目标语言", style = MaterialTheme.typography.titleMedium)
                LanguagePicker(
                    label = "翻译成",
                    selectedTag = targetLanguage,
                    includeAuto = false,
                    onSelected = { targetLanguage = it },
                )
                TextButton(
                    onClick = {
                        runCatching { viewModel.saveTargetLanguage(targetLanguage) }
                            .onSuccess { status = "目标语言已保存" }
                            .onFailure { status = it.message ?: "保存失败" }
                    }
                ) { Text("保存目标语言") }

                Text("翻译引擎", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedEngine == TranslationEngine.ON_DEVICE,
                        onClick = { viewModel.selectEngine(TranslationEngine.ON_DEVICE) },
                    )
                    Column {
                        Text("设备端离线翻译")
                        Text(
                            "使用 ML Kit。模型下载完成后可离线翻译。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedEngine == TranslationEngine.HTTP_JSON,
                        onClick = { viewModel.selectEngine(TranslationEngine.HTTP_JSON) },
                    )
                    Column {
                        Text("自定义 HTTP/JSON API")
                        Text(
                            "自行配置请求 JSON、请求头和响应字段路径。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Text("自定义 API", style = MaterialTheme.typography.titleMedium)
                Text(
                    "使用 API 时，文章文本会发送给你填写的服务。API Key 默认作为 Bearer Token 发送；配置密钥时只允许 HTTPS 地址，密钥和请求头使用 Android Keystore 加密保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("API Endpoint") },
                    placeholder = { Text("https://example.com/v1/chat/completions") },
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("API Key（可选）") },
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("模型名称（可选）") },
                )
                OutlinedTextField(
                    value = headersText,
                    onValueChange = { headersText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                    label = { Text("请求头 JSON") },
                    placeholder = { Text("""{"Authorization":"Bearer {{apiKey}}"}""") },
                )
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

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            try {
                                val config = buildConfiguration()
                                viewModel.saveApiConfiguration(config)
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

                Text("本地语言模型", style = MaterialTheme.typography.titleMedium)
                Text("源语言", style = MaterialTheme.typography.titleMedium)
                LanguagePicker(
                    label = "原文语言",
                    selectedTag = sourceLanguage,
                    includeAuto = true,
                    onSelected = {
                        sourceLanguage = it
                        viewModel.saveSourceLanguage(it)
                    },
                )
                Text(
                    "自动检测适合大多数文章；也可以手动指定原文语言。目标语言会用于文章正文和标题。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = !busy,
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
            TranslationLanguages.supported.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.label + " (" + language.tag + ")") },
                    onClick = { onSelected(language.tag); expanded = false },
                )
            }
        }
    }
}
