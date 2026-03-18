package com.openclaw.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.openclaw.assistant.R
import com.openclaw.assistant.api.OpenClawClient

import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.service.NodeForegroundService
import com.openclaw.assistant.service.HotwordService
import com.openclaw.assistant.ui.components.CollapsibleSection
import com.openclaw.assistant.ui.components.CredentialHintCard
import com.openclaw.assistant.ui.components.ConnectionState
import com.openclaw.assistant.ui.components.PairingRequiredCard
import com.openclaw.assistant.ui.components.StatusIndicator
import com.openclaw.assistant.gateway.AgentInfo
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import com.openclaw.assistant.utils.GatewayConfigUtils
import com.openclaw.assistant.utils.SystemInfoProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

// ElevenLabs Voice Options
object ElevenLabsVoiceOptions {
    data class VoiceOption(val id: String, val name: String, val description: String)
    
    // Actual ElevenLabs Voice IDs from API
    val VOICES = listOf(
        VoiceOption("", "デフォルト（API設定依存）", "API設定で指定されたデフォルト音声を使用"),
        VoiceOption("pNInz6obpgDQGcFmaJgB", "Adam", "力強い男性"),
        VoiceOption("EXAVITQu4vr4xnSDxMaL", "Bella", "明るいプロフェッショナル女性"),
        VoiceOption("nPczCjzI2devNBz1zQrb", "Brian", "深みのある安心感のある男性"),
        VoiceOption("IKne3meq5aSn9XLyUdCD", "Charlie", "低めで自信に満ちた男性"),
        VoiceOption("SOYHLrjzK2X1ezoPC6cr", "Harry", "激しい戦士系男性"),
        VoiceOption("XrExE9yKIg1WjnnlVkGX", "Matilda", "知識豊富なプロフェッショナル女性"),
        VoiceOption("cgSgspJ2msm6clMCkdW9", "Jessica", "明るく温かみのある女性"),
        VoiceOption("cjVigY5qzO86Huf0OWal", "Eric", "滑らかで信頼できる男性"),
        VoiceOption("EXAVITQu4vr4xnSDxMaL", "Sarah", "成熟した自信に満ちた女性"),
        VoiceOption("FGY2WhTYpPnrIDTdsKH5", "Laura", "熱心で独特な女性"),
        VoiceOption("JBFqnCBsd6RMkjVDRZzb", "George", "温かみのある物語り男性"),
        VoiceOption("CwhRBWXzGAHq8TQ4Fs17", "Roger", "落ち着いたカジュアル男性"),
        VoiceOption("SAz9YHcvj6GT2YYXdXww", "River", "リラックスした中性"),
        VoiceOption("bIHbv24MWmeRgasZH58o", "Will", "楽観的でリラックスした男性"),
        VoiceOption("onwK4e9ZLuTAKqWW03F9", "Daniel", "安定した放送系男性"),
        VoiceOption("pFZfaz1YfMItY4IjZDke", "Lily", "ベルベットのような女優系"),
        VoiceOption("pqHfZKP75CvOlQylNhV4", "Bill", "賢明で成熟した男性"),
        VoiceOption("Xb7hH8MSUJpSbSDYk0k2", "Alice", "明確で教育的な女性"),
        VoiceOption("TX3AE5NoiEX1lRR4gU5H", "Liam", "エネルギッシュSNSクリエイター"),
        VoiceOption("N2lVS1w4EtoT3dr4eOWO", "Callum", "ハスキーなトリックスター"),
        VoiceOption("iP95p4xoKVk53GoZ742B", "Chris", "魅力的で親しみやすい男性")
    )
}

// VoiceVox Character Data
// Style IDs follow the official VOICEVOX Core API (voicevox_vvm 0.16.x).
// Source: https://github.com/VOICEVOX/voicevox_vvm
object VoiceVoxCharacters {
    data class Character(val id: Int, val name: String, val styleName: String, val vvmFileName: String, val sizeBytes: Long)

    // VVM file mapping (actual downloaded files are 0.vvm, 3.vvm, etc.)
    // Maps official VOICEVOX style ID to VVM file number
    val VVM_FILE_MAPPING = mapOf(
        // 0.vvm - 四国めたん（ノーマル系）, ずんだもん（ノーマル系）, 春日部つむぎ, 雨晴はう
        0 to "0", 2 to "0", 4 to "0", 6 to "0",    // 四国めたん
        1 to "0", 3 to "0", 5 to "0", 7 to "0",    // ずんだもん
        8 to "0",                                    // 春日部つむぎ
        10 to "0",                                   // 雨晴はう
        // 3.vvm - 波音リツ
        9 to "3",
        // 4.vvm - 玄野武宏（ノーマル）
        11 to "4",
        // 5.vvm - 四国めたん ささやき/ヒソヒソ, ずんだもん ささやき/ヒソヒソ
        36 to "5", 37 to "5",                       // 四国めたん
        22 to "5", 38 to "5",                       // ずんだもん
        // 9.vvm - 白上虎太郎
        12 to "9", 32 to "9", 33 to "9", 34 to "9", 35 to "9",
        // 10.vvm - 玄野武宏 追加スタイル
        39 to "10", 40 to "10", 41 to "10",
        // 15.vvm - 青山龍星
        13 to "15", 81 to "15", 82 to "15", 83 to "15",
        84 to "15", 85 to "15", 86 to "15"
    )
    
    // Get VVM file name for a style ID
    fun getVvmFileName(styleId: Int): String = VVM_FILE_MAPPING[styleId] ?: "0"
    
    // Get all characters in a VVM file
    fun getCharactersInVvm(vvmFile: String): List<Character> {
        val styleIds = VVM_FILE_MAPPING.filter { it.value == vvmFile }.keys
        return CHARACTERS.filter { it.id in styleIds }
    }
    
    // Get display name for VVM file (e.g., "四国めたん, ずんだもん 他3キャラ")
    fun getVvmDisplayName(vvmFile: String, context: android.content.Context): String {
        val chars = getCharactersInVvm(vvmFile)
        if (chars.isEmpty()) return context.getString(R.string.voicevox_unknown_vvm)

        val uniqueNames = chars.map { it.name }.distinct()
        return when {
            uniqueNames.size == 1 -> uniqueNames.first()
            uniqueNames.size == 2 -> uniqueNames.joinToString("、")
            uniqueNames.size == 3 -> uniqueNames.joinToString("、") + context.getString(R.string.voicevox_other_styles, chars.size - 3)
            else -> uniqueNames.take(2).joinToString("、") + context.getString(R.string.voicevox_other_chars, uniqueNames.size - 2)
        }
    }
    
    val CHARACTERS = listOf(
        // ── 0.vvm ── 四国めたん
        Character(0,  "四国めたん", "あまあま", "0",  120_000_000),
        Character(2,  "四国めたん", "ノーマル",  "0",  120_000_000),
        Character(4,  "四国めたん", "セクシー", "0",  120_000_000),
        Character(6,  "四国めたん", "ツンツン", "0",  120_000_000),
        // ── 0.vvm ── ずんだもん
        Character(1,  "ずんだもん", "あまあま", "0",  120_000_000),
        Character(3,  "ずんだもん", "ノーマル",  "0",  120_000_000),
        Character(5,  "ずんだもん", "セクシー", "0",  120_000_000),
        Character(7,  "ずんだもん", "ツンツン", "0",  120_000_000),
        // ── 0.vvm ── 春日部つむぎ
        Character(8,  "春日部つむぎ", "ノーマル", "0", 120_000_000),
        // ── 0.vvm ── 雨晴はう
        Character(10, "雨晴はう",   "ノーマル", "0",  120_000_000),
        // ── 3.vvm ── 波音リツ
        Character(9,  "波音リツ",   "ノーマル", "3",  120_000_000),
        // ── 4.vvm ── 玄野武宏
        Character(11, "玄野武宏",   "ノーマル",  "4",  120_000_000),
        // ── 5.vvm ── 四国めたん ささやき系
        Character(36, "四国めたん", "ささやき", "5",  60_000_000),
        Character(37, "四国めたん", "ヒソヒソ", "5",  60_000_000),
        // ── 5.vvm ── ずんだもん ささやき系
        Character(22, "ずんだもん", "ささやき", "5",  60_000_000),
        Character(38, "ずんだもん", "ヒソヒソ", "5",  60_000_000),
        // ── 9.vvm ── 白上虎太郎
        Character(12, "白上虎太郎", "ふつう",   "9",  120_000_000),
        Character(32, "白上虎太郎", "わーい",   "9",  120_000_000),
        Character(33, "白上虎太郎", "びくびく", "9",  120_000_000),
        Character(34, "白上虎太郎", "おこ",     "9",  120_000_000),
        Character(35, "白上虎太郎", "びえーん", "9",  120_000_000),
        // ── 10.vvm ── 玄野武宏 追加スタイル
        Character(39, "玄野武宏",   "喜び",     "10", 120_000_000),
        Character(40, "玄野武宏",   "ツンギレ", "10", 120_000_000),
        Character(41, "玄野武宏",   "悲しみ",   "10", 120_000_000),
        // ── 15.vvm ── 青山龍星
        Character(13, "青山龍星",   "ノーマル",  "15", 120_000_000),
        Character(81, "青山龍星",   "熱血",     "15", 120_000_000),
        Character(82, "青山龍星",   "不機嫌",   "15", 120_000_000),
        Character(83, "青山龍星",   "喜び",     "15", 120_000_000),
        Character(84, "青山龍星",   "しっとり", "15", 120_000_000),
        Character(85, "青山龍星",   "かなしみ", "15", 120_000_000),
        Character(86, "青山龍星",   "囁き",     "15", 60_000_000)
    )
    
    fun getById(id: Int): Character? = CHARACTERS.find { it.id == id }
    fun getDisplayName(id: Int): String = getById(id)?.let { "${it.name}（${it.styleName}）" } ?: "Unknown"
}

class SettingsActivity : ComponentActivity() {

    private lateinit var settings: SettingsRepository

    override fun attachBaseContext(newBase: Context) {
        val tag = try {
            SettingsRepository.getInstance(newBase).appLanguage.trim()
        } catch (e: Exception) { "" }
        if (tag.isNotBlank()) {
            val locale = java.util.Locale.forLanguageTag(tag)
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository.getInstance(this)

        setContent {
            OpenClawAssistantTheme {
                var showCredits by remember { mutableStateOf(false) }
                if (showCredits) {
                    com.openclaw.assistant.ui.settings.CreditsScreen(
                        settings = settings,
                        onBack = { showCredits = false }
                    )
                } else {
                    SettingsScreen(
                        settings = settings,
                        onSave = {
                            Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
                            finish()
                        },
                        onBack = { finish() },
                        onCredits = { showCredits = true }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onCredits: () -> Unit = {}
) {
    var httpUrl by rememberSaveable { mutableStateOf(settings.httpUrl) }
    var authToken by rememberSaveable { mutableStateOf(settings.authToken) }
    var defaultAgentId by rememberSaveable { mutableStateOf(settings.defaultAgentId) }
    var ttsEnabled by rememberSaveable { mutableStateOf(settings.ttsEnabled) }
    var ttsSpeed by rememberSaveable { mutableStateOf(settings.ttsSpeed) }
    var continuousMode by rememberSaveable { mutableStateOf(settings.continuousMode) }
    var resumeLatestSession by rememberSaveable { mutableStateOf(settings.resumeLatestSession) }
    var wakeWordPreset by rememberSaveable { mutableStateOf(settings.wakeWordPreset) }
    var customWakeWord by rememberSaveable { mutableStateOf(settings.customWakeWord) }
    var wakeWordSensitivity by rememberSaveable { mutableStateOf(settings.wakeWordSensitivity) }
    var speechSilenceTimeout by rememberSaveable { mutableStateOf(settings.speechSilenceTimeout.toFloat().coerceIn(5000f, 30000f)) }
    var speechLanguage by rememberSaveable { mutableStateOf(settings.speechLanguage) }
    var appLanguage by rememberSaveable { mutableStateOf(settings.appLanguage) }
    var thinkingSoundEnabled by rememberSaveable { mutableStateOf(settings.thinkingSoundEnabled) }
    var fillerPhrasesEnabled by rememberSaveable { mutableStateOf(settings.fillerPhrasesEnabled) }
    var ttsBargeInEnabled by rememberSaveable { mutableStateOf(settings.ttsBargeInEnabled) }
    var wakeWordDebugEnabled by rememberSaveable { mutableStateOf(settings.wakeWordDebugEnabled) }
    var mediaButtonEnabled by rememberSaveable { mutableStateOf(settings.mediaButtonEnabled) }

    var showAuthToken by rememberSaveable { mutableStateOf(false) }
    var showWakeWordMenu by rememberSaveable { mutableStateOf(false) }
    var showLanguageMenu by rememberSaveable { mutableStateOf(false) }
    var showDisplayLanguageMenu by rememberSaveable { mutableStateOf(false) }
    var httpIgnoreSslErrors by rememberSaveable { mutableStateOf(settings.httpIgnoreSslErrors) }
    var wakewordConnectionType by rememberSaveable { mutableStateOf(settings.wakewordConnectionType) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val runtime = remember(context.applicationContext) {
        (context.applicationContext as OpenClawApplication).nodeRuntime
    }
    val apiClient = remember(httpIgnoreSslErrors) { OpenClawClient(ignoreSslErrors = httpIgnoreSslErrors) }
    
    var isTesting by rememberSaveable { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }

    // Agent list from NodeRuntime
    val isPairingRequired by runtime.isPairingRequired.collectAsState()
    val deviceId = runtime.deviceId

    val agentListState by runtime.agentList.collectAsState()
    val availableAgents = remember(agentListState) {
        agentListState?.agents?.distinctBy { it.id } ?: emptyList()
    }
    var isFetchingAgents by rememberSaveable { mutableStateOf(false) }
    var showAgentMenu by rememberSaveable { mutableStateOf(false) }

    // TTS Engines
    var ttsEngine by rememberSaveable { mutableStateOf(settings.ttsEngine) }
    
    // TTS Settings
    var ttsType by rememberSaveable { mutableStateOf(settings.ttsType) }
    var showTtsTypeMenu by rememberSaveable { mutableStateOf(false) }
    
    // ElevenLabs
    var elevenLabsApiKey by rememberSaveable { mutableStateOf(settings.elevenLabsApiKey) }
    var elevenLabsVoiceId by rememberSaveable { mutableStateOf(settings.elevenLabsVoiceId) }
    var elevenLabsSpeed by rememberSaveable { mutableStateOf(settings.elevenLabsSpeed) }
    var showElevenLabsApiKey by rememberSaveable { mutableStateOf(false) }
    
    // OpenAI
    var openAiApiKey by rememberSaveable { mutableStateOf(settings.openAiApiKey) }
    var openAiVoice by rememberSaveable { mutableStateOf(settings.openAiVoice) }
    var showOpenAiApiKey by rememberSaveable { mutableStateOf(false) }
    
    // VOICEVOX
    var voiceVoxStyleId by rememberSaveable { mutableStateOf(settings.voiceVoxStyleId) }
    var voiceVoxTermsAccepted by rememberSaveable { mutableStateOf(settings.voiceVoxTermsAccepted) }
    
    var availableEngines by remember { mutableStateOf<List<com.openclaw.assistant.speech.TTSEngineUtils.EngineInfo>>(emptyList()) }
    var showEngineMenu by rememberSaveable { mutableStateOf(false) }
    var showNodeToken by rememberSaveable { mutableStateOf(false) }

    val nodeConnected by runtime.isConnected.collectAsState()
    val nodeStatus by runtime.statusText.collectAsState()
    val nodeForeground by runtime.isForeground.collectAsState()

    val manualEnabledState by runtime.manualEnabled.collectAsState()
    val manualHostState by runtime.manualHost.collectAsState()
    val manualPortState by runtime.manualPort.collectAsState()
    val manualTlsState by runtime.manualTls.collectAsState()
    val gatewayTokenState by runtime.gatewayToken.collectAsState()

    // Connection Type
    var connectionType by rememberSaveable { mutableStateOf(settings.connectionType) }

    // Gateway inputs
    var gatewayHost by rememberSaveable { mutableStateOf(manualHostState) }
    var gatewayPort by rememberSaveable { mutableStateOf(manualPortState.toString()) }
    var gatewayTls by rememberSaveable { mutableStateOf(manualTlsState) }
    var gatewayToken by rememberSaveable { mutableStateOf(gatewayTokenState) }
    var gatewayPassword by rememberSaveable { mutableStateOf(runtime.getGatewayPassword() ?: "") }
    var gatewayBootstrapToken by rememberSaveable { mutableStateOf(runtime.getGatewayBootstrapToken() ?: "") }
    var showGatewayPassword by rememberSaveable { mutableStateOf(false) }
    var usePasswordAuth by rememberSaveable { mutableStateOf(runtime.getGatewayPassword()?.isNotEmpty() == true) }

    // Setup code (quick-config from `openclaw qr --setup-code-only`)
    var setupCode by rememberSaveable { mutableStateOf("") }
    var setupCodeApplied by rememberSaveable { mutableStateOf(false) }
    var setupCodeError by rememberSaveable { mutableStateOf(false) }
    // True when the applied setup code had only bootstrapToken (no password/token in QR)
    var setupCodeHasBootstrapOnly by rememberSaveable { mutableStateOf(false) }

    // HTTP inputs
    var httpInputUrl by rememberSaveable { mutableStateOf(httpUrl) }
    var httpToken by rememberSaveable { mutableStateOf(authToken) }

    // Update local state if runtime state changes behind the scenes
    LaunchedEffect(manualHostState, manualPortState, manualTlsState, gatewayTokenState) {
        gatewayHost = manualHostState
        gatewayPort = manualPortState.toString()
        gatewayTls = manualTlsState
        gatewayToken = gatewayTokenState
    }
    LaunchedEffect(httpUrl, authToken) {
        httpInputUrl = httpUrl
        httpToken = authToken
    }

    LaunchedEffect(Unit) {
        availableEngines = com.openclaw.assistant.speech.TTSEngineUtils.getAvailableEngines(context)
    }

    // Fetch agent list on screen open if already connected
    LaunchedEffect(Unit) {
        if (runtime.isConnected.value) {
            runtime.refreshAgentList()
        }
    }

    // Speech recognition language options - loaded dynamically from device
    var speechLanguageOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var isLoadingLanguages by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoadingLanguages = true
        val deviceLanguages = com.openclaw.assistant.speech.SpeechLanguageUtils
            .getAvailableLanguages(context)

        val mergedLanguages = linkedMapOf<String, String>()
        deviceLanguages?.forEach { info ->
            mergedLanguages[info.tag] = info.displayName
        }
        FALLBACK_SPEECH_LANGUAGES.forEach { (tag, label) ->
            mergedLanguages.putIfAbsent(tag, label)
        }

        speechLanguageOptions = buildList {
            add("" to context.getString(R.string.speech_language_system_default))
            addAll(
                mergedLanguages
                    .map { it.key to it.value }
                    .sortedBy { it.second }
            )
        }
        isLoadingLanguages = false
    }

    // Wake word options
    val wakeWordOptions = listOf(
        SettingsRepository.WAKE_WORD_OPEN_CLAW to stringResource(R.string.wake_word_openclaw),
        SettingsRepository.WAKE_WORD_HEY_ASSISTANT to stringResource(R.string.wake_word_hey_assistant),
        SettingsRepository.WAKE_WORD_JARVIS to stringResource(R.string.wake_word_jarvis),
        SettingsRepository.WAKE_WORD_COMPUTER to stringResource(R.string.wake_word_computer),
        SettingsRepository.WAKE_WORD_CUSTOM to stringResource(R.string.wake_word_custom)
    )

    var selectedTabIndex by remember {
        mutableStateOf(if (connectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) 0 else 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_button))
                    }
                },
                actions = {
                    IconButton(onClick = onCredits) {
                        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.credits_title))
                    }
                    TextButton(
                        onClick = {
                            settings.connectionType = if (selectedTabIndex == 0) {
                                SettingsRepository.CONNECTION_TYPE_GATEWAY
                            } else {
                                SettingsRepository.CONNECTION_TYPE_HTTP
                            }

                            // Save Gateway Settings
                            runtime.setManualEnabled(true)
                            runtime.setManualHost(gatewayHost.trim())
                            runtime.setManualPort(gatewayPort.toIntOrNull() ?: 18789)
                            runtime.setManualTls(gatewayTls)
                            if (gatewayBootstrapToken.isNotBlank()) {
                                runtime.setGatewayBootstrapToken(gatewayBootstrapToken.trim())
                                runtime.setGatewayToken("")
                                runtime.setGatewayPassword("")
                            } else if (usePasswordAuth) {
                                runtime.setGatewayBootstrapToken("")
                                runtime.setGatewayToken("")
                                runtime.setGatewayPassword(gatewayPassword.trim())
                            } else {
                                runtime.setGatewayBootstrapToken("")
                                runtime.setGatewayToken(gatewayToken.trim())
                                runtime.setGatewayPassword("")
                            }

                            // Save HTTP Settings
                            if (httpInputUrl.trim().isNotBlank() && !com.openclaw.assistant.shared.utils.NetworkUtils.isUrlSecure(httpInputUrl.trim())) {
                                testResult = TestResult(success = false, message = "Insecure URL: Only HTTPS or local HTTP allowed.")
                            } else {
                                settings.httpUrl = httpInputUrl.trim()
                            }
                            settings.authToken = httpToken.trim()
                            settings.httpIgnoreSslErrors = httpIgnoreSslErrors

                            settings.defaultAgentId = defaultAgentId
                            settings.ttsEnabled = ttsEnabled
                            settings.ttsSpeed = ttsSpeed
                            settings.ttsEngine = ttsEngine
                            settings.ttsType = ttsType
                            settings.elevenLabsApiKey = elevenLabsApiKey
                            settings.elevenLabsVoiceId = elevenLabsVoiceId
                            settings.elevenLabsSpeed = elevenLabsSpeed
                            settings.openAiApiKey = openAiApiKey
                            settings.openAiVoice = openAiVoice
                            settings.voiceVoxStyleId = voiceVoxStyleId
                            settings.voiceVoxTermsAccepted = voiceVoxTermsAccepted
                            settings.continuousMode = continuousMode
                            settings.resumeLatestSession = resumeLatestSession
                            settings.wakeWordPreset = wakeWordPreset
                            settings.customWakeWord = customWakeWord
                            settings.wakeWordSensitivity = wakeWordSensitivity
                            settings.wakewordConnectionType = wakewordConnectionType
                            settings.speechSilenceTimeout = speechSilenceTimeout.toLong()
                            settings.speechLanguage = speechLanguage
                            settings.appLanguage = appLanguage
                            settings.thinkingSoundEnabled = thinkingSoundEnabled
                            settings.fillerPhrasesEnabled = fillerPhrasesEnabled
                            settings.ttsBargeInEnabled = ttsBargeInEnabled
                            settings.wakeWordDebugEnabled = wakeWordDebugEnabled
                            settings.mediaButtonEnabled = mediaButtonEnabled
                            applyAppLanguage(appLanguage)

                            // Stop/Restart services
                            HotwordService.stop(context)

                            if (settings.connectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) {
                                runtime.connectManual()
                            }

                            if (settings.hotwordEnabled) {
                                HotwordService.start(context)
                            }
                            onSave()
                        },
                        enabled = gatewayHost.isNotBlank() || httpInputUrl.isNotBlank()
                    ) {
                        Text(stringResource(R.string.save_button))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
                // Show pairing required banner if needed
                if (isPairingRequired && deviceId != null) {
                    PairingRequiredCard(deviceId = deviceId)
                    Spacer(modifier = Modifier.height(24.dp))
                }

            // === UNIFIED CONNECTION SECTION ===
            CollapsibleSection(
                title = stringResource(R.string.connection),
                subtitle = if (connectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY && nodeConnected) nodeStatus.ifBlank { stringResource(R.string.connected) } else "",
                initiallyExpanded = true
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {

                        // Connection Configuration Tabs
                        Text(stringResource(R.string.connection_settings), style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(8.dp))

                        TabRow(
                            selectedTabIndex = selectedTabIndex,
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = Color.Transparent,
                        ) {
                            Tab(
                                selected = selectedTabIndex == 0,
                                onClick = { selectedTabIndex = 0 },
                                text = { Text(stringResource(R.string.tab_gateway)) }
                            )
                            Tab(
                                selected = selectedTabIndex == 1,
                                onClick = { selectedTabIndex = 1 },
                                text = { Text(stringResource(R.string.tab_http)) }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (selectedTabIndex == 0) {
                            // Setup Code quick-config (from `openclaw qr --setup-code-only`)
                            OutlinedTextField(
                                value = setupCode,
                                onValueChange = {
                                    setupCode = it
                                    setupCodeApplied = false
                                    setupCodeError = false
                                },
                                label = { Text(stringResource(R.string.setup_guide_setup_code_label)) },
                                placeholder = { Text(stringResource(R.string.setup_guide_setup_code_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                trailingIcon = {
                                    if (setupCode.isNotBlank()) {
                                        TextButton(
                                            onClick = {
                                                val decoded = GatewayConfigUtils.decodeGatewaySetupCode(setupCode)
                                                if (decoded != null) {
                                                    val parsed = GatewayConfigUtils.parseGatewayEndpoint(decoded.url)
                                                    if (parsed != null) {
                                                        gatewayHost = parsed.host
                                                        gatewayPort = parsed.port.toString()
                                                        gatewayTls = parsed.tls
                                                        if (decoded.bootstrapToken != null) {
                                                            gatewayBootstrapToken = decoded.bootstrapToken
                                                            gatewayToken = ""
                                                            gatewayPassword = ""
                                                            usePasswordAuth = false
                                                            setupCodeHasBootstrapOnly = decoded.password == null && decoded.token == null
                                                        } else if (decoded.password != null) {
                                                            gatewayBootstrapToken = ""
                                                            usePasswordAuth = true
                                                            gatewayPassword = decoded.password
                                                            setupCodeHasBootstrapOnly = false
                                                        } else if (decoded.token != null) {
                                                            gatewayBootstrapToken = ""
                                                            usePasswordAuth = false
                                                            gatewayToken = decoded.token
                                                            setupCodeHasBootstrapOnly = false
                                                        }
                                                        setupCodeApplied = true
                                                        setupCodeError = false
                                                        testResult = null
                                                    } else {
                                                        setupCodeError = true
                                                    }
                                                } else {
                                                    setupCodeError = true
                                                }
                                            }
                                        ) { Text(stringResource(R.string.apply)) }
                                    }
                                },
                                isError = setupCodeError,
                                supportingText = when {
                                    setupCodeApplied -> { { Text(stringResource(R.string.setup_code_applied), color = MaterialTheme.colorScheme.primary) } }
                                    setupCodeError -> { { Text(stringResource(R.string.setup_code_invalid_code), color = MaterialTheme.colorScheme.error) } }
                                    else -> null
                                }
                            )

                            if (setupCodeApplied && setupCodeHasBootstrapOnly) {
                                Spacer(modifier = Modifier.height(12.dp))
                                CredentialHintCard()
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(stringResource(R.string.gateway_configuration), style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = gatewayHost,
                                    onValueChange = { gatewayHost = it; testResult = null },
                                    label = { Text(stringResource(R.string.gateway_host)) },
                                    modifier = Modifier.weight(2f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                                )
                                OutlinedTextField(
                                    value = gatewayPort,
                                    onValueChange = { gatewayPort = it.filter { char -> char.isDigit() }; testResult = null },
                                    label = { Text(stringResource(R.string.gateway_port)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable {
                                        gatewayBootstrapToken = ""
                                        usePasswordAuth = false
                                        testResult = null
                                    }
                                ) {
                                    RadioButton(
                                        selected = !usePasswordAuth,
                                        onClick = {
                                            gatewayBootstrapToken = ""
                                            usePasswordAuth = false
                                            testResult = null
                                        }
                                    )
                                    Text(stringResource(R.string.gateway_token))
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable {
                                        gatewayBootstrapToken = ""
                                        usePasswordAuth = true
                                        testResult = null
                                    }
                                ) {
                                    RadioButton(
                                        selected = usePasswordAuth,
                                        onClick = {
                                            gatewayBootstrapToken = ""
                                            usePasswordAuth = true
                                            testResult = null
                                        }
                                    )
                                    Text(stringResource(R.string.gateway_password))
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            if (!usePasswordAuth) {
                                OutlinedTextField(
                                    value = gatewayToken,
                                    onValueChange = {
                                        gatewayBootstrapToken = ""
                                        gatewayToken = it
                                        testResult = null
                                    },
                                    label = { Text(stringResource(R.string.gateway_token)) },
                                    trailingIcon = {
                                        IconButton(onClick = { showNodeToken = !showNodeToken }) {
                                            Icon(
                                                if (showNodeToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = if (showNodeToken) "Hide token" else "Show token"
                                            )
                                        }
                                    },
                                    visualTransformation = if (showNodeToken) VisualTransformation.None else PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            } else {
                                OutlinedTextField(
                                    value = gatewayPassword,
                                    onValueChange = {
                                        gatewayBootstrapToken = ""
                                        gatewayPassword = it
                                        testResult = null
                                    },
                                    label = { Text(stringResource(R.string.gateway_password)) },
                                    trailingIcon = {
                                        IconButton(onClick = { showGatewayPassword = !showGatewayPassword }) {
                                            Icon(
                                                if (showGatewayPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = if (showGatewayPassword) "Hide password" else "Show password"
                                            )
                                        }
                                    },
                                    visualTransformation = if (showGatewayPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.gateway_use_tls), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.gateway_use_tls_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                Switch(
                                    checked = gatewayTls,
                                    onCheckedChange = { gatewayTls = it; testResult = null }
                                )
                            }
                        } else if (selectedTabIndex == 1) {
                            Text(stringResource(R.string.http_api_configuration), style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = httpInputUrl,
                                onValueChange = { httpInputUrl = it; testResult = null },
                                label = { Text(stringResource(R.string.webhook_url_label)) },
                                placeholder = { Text(stringResource(R.string.webhook_url_hint)) },
                                leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = httpToken,
                                onValueChange = { httpToken = it.trim(); testResult = null },
                                label = { Text(stringResource(R.string.auth_token_label)) },
                                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                                trailingIcon = {
                                    IconButton(onClick = { showNodeToken = !showNodeToken }) {
                                        Icon(
                                            if (showNodeToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (showNodeToken) "Hide token" else "Show token"
                                        )
                                    }
                                },
                                visualTransformation = if (showNodeToken) VisualTransformation.None else PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        
                        // Gateway Specific Settings
                        if (selectedTabIndex == 0) {
                            Spacer(modifier = Modifier.height(16.dp))

                            // Foreground Service Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.gateway_foreground_service), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.gateway_foreground_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                Switch(
                                    checked = nodeForeground,
                                    onCheckedChange = { enabled ->
                                        runtime.setForeground(enabled)
                                        if (enabled) NodeForegroundService.start(context) else NodeForegroundService.stop(context)
                                    }
                                )
                            }
                        }

                        // HTTP Specific Settings
                        if (selectedTabIndex == 1) {
                            Text(
                                text = stringResource(R.string.settings_legacy_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 12.dp, top = 12.dp)
                            )
                            
                            // Default Agent
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (availableAgents.isNotEmpty()) {
                            // Dropdown when agents are loaded
                            ExposedDropdownMenuBox(
                                expanded = showAgentMenu,
                                onExpandedChange = { showAgentMenu = it }
                            ) {
                                val agentLabel = availableAgents.find { it.id == defaultAgentId }?.name ?: defaultAgentId
                                OutlinedTextField(
                                    value = agentLabel,
                                    onValueChange = { defaultAgentId = it },
                                    label = { Text(stringResource(R.string.default_agent_label)) },
                                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                                    trailingIcon = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isFetchingAgents) {
                                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                            } else {
                                                IconButton(onClick = {
                                                    scope.launch {
                                                        isFetchingAgents = true
                                                        if (runtime.isConnected.value) {
                                                            runtime.refreshAgentList()
                                                        } else {
                                                            Toast.makeText(context, context.getString(R.string.gateway_not_connected), Toast.LENGTH_SHORT).show()
                                                        }
                                                        isFetchingAgents = false
                                                    }
                                                }) {
                                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Agents")
                                                }
                                            }
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showAgentMenu)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = showAgentMenu,
                                    onDismissRequest = { showAgentMenu = false }
                                ) {
                                    availableAgents.forEach { agent ->
                                        DropdownMenuItem(
                                            text = { Text(agent.name) },
                                            onClick = {
                                                defaultAgentId = agent.id
                                                showAgentMenu = false
                                            },
                                            leadingIcon = {
                                                if (defaultAgentId == agent.id) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            // Text field fallback before connection or if no agents found
                            OutlinedTextField(
                                value = defaultAgentId,
                                onValueChange = { defaultAgentId = it },
                                label = { Text(stringResource(R.string.default_agent_label)) },
                                placeholder = { Text(stringResource(R.string.default_agent_hint)) },
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                                trailingIcon = {
                                    if (isFetchingAgents) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        IconButton(onClick = {
                                            scope.launch {
                                                isFetchingAgents = true
                                                if (runtime.isConnected.value) {
                                                    runtime.refreshAgentList()
                                                } else {
                                                    Toast.makeText(context, context.getString(R.string.gateway_not_connected), Toast.LENGTH_SHORT).show()
                                                }
                                                isFetchingAgents = false
                                            }
                                        }) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Agents")
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Ignore SSL Errors Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.http_ignore_ssl_errors), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.http_ignore_ssl_errors_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                Switch(
                                    checked = httpIgnoreSslErrors,
                                    onCheckedChange = { httpIgnoreSslErrors = it; testResult = null }
                                )
                            }

                            if (httpIgnoreSslErrors) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    )
                                ) {
                                    Text(
                                        text = stringResource(R.string.http_ignore_ssl_errors_warning),
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Test Connection Button
                            Button(
                                onClick = {
                                    if (httpInputUrl.isBlank()) return@Button
                                    if (!com.openclaw.assistant.shared.utils.NetworkUtils.isUrlSecure(httpInputUrl.trim())) {
                                        testResult = TestResult(success = false, message = "Insecure URL: Only HTTPS or local HTTP allowed.")
                                        return@Button
                                    }
                                    scope.launch {
                                        try {
                                            isTesting = true
                                            testResult = null
                                            val testUrl = httpInputUrl.trimEnd('/').let { url ->
                                                if (url.contains("/v1/")) url else "$url/v1/chat/completions"
                                            }
                                            val result = apiClient.testConnection(testUrl, httpToken.trim())
                                            result.fold(
                                                onSuccess = {
                                                    testResult = TestResult(success = true, message = context.getString(R.string.connected))
                                                    settings.httpUrl = httpInputUrl.trim()
                                                    settings.authToken = httpToken.trim()
                                                    settings.httpIgnoreSslErrors = httpIgnoreSslErrors
                                                    settings.isVerified = true
                                                },
                                                onFailure = {
                                                    testResult = TestResult(success = false, message = context.getString(R.string.failed, it.message ?: ""))
                                                }
                                            )
                                        } catch (e: Exception) {
                                            testResult = TestResult(success = false, message = context.getString(R.string.error, e.message ?: ""))
                                        } finally {
                                            isTesting = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        testResult?.success == true -> Color(0xFF4CAF50)
                                        testResult?.success == false -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                ),
                                enabled = httpInputUrl.isNotBlank() && !isTesting
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.testing))
                                } else {
                                    Icon(
                                        when {
                                            testResult?.success == true -> Icons.Default.Check
                                            testResult?.success == false -> Icons.Default.Error
                                            else -> Icons.Default.NetworkCheck
                                        },
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(testResult?.message ?: stringResource(R.string.test_connection_button))
                                }
                            }
                }
                        Spacer(modifier = Modifier.height(24.dp))
                    } // end Column
                } // end Card
            } // end CollapsibleSection for Unified Connection

            Spacer(modifier = Modifier.height(24.dp))

            // === VOICE SECTION ===
            CollapsibleSection(title = stringResource(R.string.voice)) {

            // --- Voice Output card ---
            Text(
                text = stringResource(R.string.voice_output),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.read_ai_responses), style = MaterialTheme.typography.bodyLarge)
                        }
                        Switch(checked = ttsEnabled, onCheckedChange = { ttsEnabled = it })
                    }

                    if (ttsEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                        // TTS Provider Selection
                        ExposedDropdownMenuBox(
                            expanded = showTtsTypeMenu,
                            onExpandedChange = { showTtsTypeMenu = it }
                        ) {
                            val ttsTypeLabel = when (ttsType) {
                                SettingsRepository.TTS_TYPE_LOCAL -> "System TTS"
                                SettingsRepository.TTS_TYPE_ELEVENLABS -> "ElevenLabs"
                                SettingsRepository.TTS_TYPE_OPENAI -> "OpenAI"
                                SettingsRepository.TTS_TYPE_VOICEVOX -> "VOICEVOX"
                                else -> "System TTS"
                            }
                            
                            OutlinedTextField(
                                value = ttsTypeLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("TTS種別") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showTtsTypeMenu) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )

                            ExposedDropdownMenu(
                                expanded = showTtsTypeMenu,
                                onDismissRequest = { showTtsTypeMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("System TTS") },
                                    onClick = {
                                        ttsType = SettingsRepository.TTS_TYPE_LOCAL
                                        showTtsTypeMenu = false
                                    },
                                    leadingIcon = {
                                        if (ttsType == SettingsRepository.TTS_TYPE_LOCAL) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("ElevenLabs") },
                                    onClick = {
                                        ttsType = SettingsRepository.TTS_TYPE_ELEVENLABS
                                        showTtsTypeMenu = false
                                    },
                                    leadingIcon = {
                                        if (ttsType == SettingsRepository.TTS_TYPE_ELEVENLABS) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("OpenAI") },
                                    onClick = {
                                        ttsType = SettingsRepository.TTS_TYPE_OPENAI
                                        showTtsTypeMenu = false
                                    },
                                    leadingIcon = {
                                        if (ttsType == SettingsRepository.TTS_TYPE_OPENAI) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                )
                                if (BuildConfig.VOICEVOX_ENABLED) {
                                    DropdownMenuItem(
                                        text = { Text("VOICEVOX") },
                                        onClick = {
                                            ttsType = SettingsRepository.TTS_TYPE_VOICEVOX
                                            showTtsTypeMenu = false
                                        },
                                        leadingIcon = {
                                            if (ttsType == SettingsRepository.TTS_TYPE_VOICEVOX) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // Provider-specific settings
                        when (ttsType) {
                            SettingsRepository.TTS_TYPE_ELEVENLABS -> {
                                Spacer(modifier = Modifier.height(16.dp))
                                ElevenLabsSettingsCard(
                                    apiKey = elevenLabsApiKey,
                                    onApiKeyChange = { elevenLabsApiKey = it },
                                    showApiKey = showElevenLabsApiKey,
                                    onShowApiKeyChange = { showElevenLabsApiKey = it },
                                    voiceId = elevenLabsVoiceId,
                                    onVoiceIdChange = { elevenLabsVoiceId = it },
                                    speed = elevenLabsSpeed,
                                    onSpeedChange = { elevenLabsSpeed = it }
                                )
                            }
                            SettingsRepository.TTS_TYPE_OPENAI -> {
                                Spacer(modifier = Modifier.height(16.dp))
                                OpenAISettingsCard(
                                    apiKey = openAiApiKey,
                                    onApiKeyChange = { openAiApiKey = it },
                                    showApiKey = showOpenAiApiKey,
                                    onShowApiKeyChange = { showOpenAiApiKey = it },
                                    voice = openAiVoice,
                                    onVoiceChange = { openAiVoice = it }
                                )
                            }
                            SettingsRepository.TTS_TYPE_VOICEVOX -> {
                                if (BuildConfig.VOICEVOX_ENABLED) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    VoiceVoxSettingsCard(
                                        styleId = voiceVoxStyleId,
                                        onStyleIdChange = { voiceVoxStyleId = it },
                                        termsAccepted = voiceVoxTermsAccepted,
                                        onTermsAcceptedChange = { voiceVoxTermsAccepted = it }
                                    )
                                }
                            }
                            else -> {
                                // System TTS - show engine selection
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                ExposedDropdownMenuBox(
                                    expanded = showEngineMenu,
                                    onExpandedChange = { showEngineMenu = it }
                                ) {
                                    val currentLabel = if (ttsEngine.isEmpty()) {
                                        stringResource(R.string.tts_engine_auto)
                                    } else {
                                        availableEngines.find { it.name == ttsEngine }?.label ?: ttsEngine
                                    }

                                    OutlinedTextField(
                                        value = currentLabel,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text(stringResource(R.string.tts_engine_label)) },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showEngineMenu) },
                                        modifier = Modifier.fillMaxWidth().menuAnchor()
                                    )

                                    ExposedDropdownMenu(
                                        expanded = showEngineMenu,
                                        onDismissRequest = { showEngineMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.tts_engine_auto)) },
                                            onClick = {
                                                ttsEngine = ""
                                                showEngineMenu = false
                                            },
                                            leadingIcon = {
                                                if (ttsEngine.isEmpty()) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        )

                                        availableEngines.forEach { engine ->
                                            DropdownMenuItem(
                                                text = { Text(engine.label) },
                                                onClick = {
                                                    ttsEngine = engine.name
                                                    showEngineMenu = false
                                                },
                                                leadingIcon = {
                                                    if (ttsEngine == engine.name) {
                                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Voice Speed (All providers except ElevenLabs which has its own speed setting)
                        if (ttsType != SettingsRepository.TTS_TYPE_ELEVENLABS) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.voice_speed), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "%.1fx".format(ttsSpeed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = stringResource(R.string.voice_speed_range, "0.5", "3.0"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Slider(
                                value = ttsSpeed,
                                onValueChange = { ttsSpeed = it },
                                valueRange = 0.5f..3.0f,
                                steps = 24,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                    // Thinking sound
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.thinking_sound), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.thinking_sound_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(checked = thinkingSoundEnabled, onCheckedChange = { thinkingSoundEnabled = it })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                    // Filler Phrases
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.filler_phrases), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.filler_phrases_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(checked = fillerPhrasesEnabled, onCheckedChange = { fillerPhrasesEnabled = it })
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- Conversation card ---
            Text(
                text = stringResource(R.string.conversation_section),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.continuous_conversation), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.auto_start_mic), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(checked = continuousMode, onCheckedChange = { continuousMode = it })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.tts_barge_in), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.tts_barge_in_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(checked = ttsBargeInEnabled, onCheckedChange = { ttsBargeInEnabled = it })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                    // Speech silence timeout
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.speech_silence_timeout), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.speech_silence_timeout_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Text(
                            text = "%.1fs".format(speechSilenceTimeout / 1000f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Slider(
                        value = speechSilenceTimeout,
                        onValueChange = { speechSilenceTimeout = it },
                        valueRange = 5000f..30000f,
                        steps = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            } // end CollapsibleSection for Voice

            Spacer(modifier = Modifier.height(24.dp))

            // === WAKE WORD SECTION ===
            CollapsibleSection(title = stringResource(R.string.wake_word)) {



                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.wake_word_classic_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            ExposedDropdownMenuBox(
                                expanded = showWakeWordMenu,
                                onExpandedChange = { showWakeWordMenu = it }
                            ) {
                                OutlinedTextField(
                                    value = wakeWordOptions.find { it.first == wakeWordPreset }?.second ?: stringResource(R.string.wake_word_openclaw),
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.activation_phrase)) },
                                    leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showWakeWordMenu) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor()
                                )
                                
                                ExposedDropdownMenu(
                                    expanded = showWakeWordMenu,
                                    onDismissRequest = { showWakeWordMenu = false }
                                ) {
                                    wakeWordOptions.forEach { (value, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                wakeWordPreset = value
                                                showWakeWordMenu = false
                                            },
                                            leadingIcon = {
                                                if (wakeWordPreset == value) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                            
                            if (wakeWordPreset == SettingsRepository.WAKE_WORD_CUSTOM) {
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = customWakeWord,
                                    onValueChange = { customWakeWord = it.lowercase() },
                                    label = { Text(stringResource(R.string.custom_wake_word)) },
                                    placeholder = { Text(stringResource(R.string.custom_wake_word_hint)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    supportingText = {
                                        Text(stringResource(R.string.custom_wake_word_help), color = Color.Gray, fontSize = 12.sp)
                                    }
                                )
                            }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.wake_word_sensitivity), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = "%.2f".format(wakeWordSensitivity),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                stringResource(R.string.wake_word_sensitivity_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                            Slider(
                                value = wakeWordSensitivity,
                                onValueChange = { wakeWordSensitivity = it },
                                valueRange = 0.0f..1.0f,
                                steps = 19,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.resume_latest_session), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(R.string.resume_latest_session_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            Switch(checked = resumeLatestSession, onCheckedChange = { resumeLatestSession = it })
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                        // Voice session connection type selector
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.wakeword_connection_type), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.wakeword_connection_type_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    SettingsRepository.CONNECTION_TYPE_GATEWAY to stringResource(R.string.wakeword_use_gateway),
                                    SettingsRepository.CONNECTION_TYPE_HTTP to stringResource(R.string.wakeword_use_http)
                                ).forEach { (type, label) ->
                                    FilterChip(
                                        selected = wakewordConnectionType == type,
                                        onClick = { wakewordConnectionType = type },
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                        // Media button trigger
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.media_button_enabled), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(R.string.media_button_enabled_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            Switch(checked = mediaButtonEnabled, onCheckedChange = { mediaButtonEnabled = it })
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                        // Wake word debug logging
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.wake_word_debug_enabled), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(R.string.wake_word_debug_enabled_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            Switch(checked = wakeWordDebugEnabled, onCheckedChange = { wakeWordDebugEnabled = it })
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === LANGUAGE SECTION ===
            CollapsibleSection(title = stringResource(R.string.language_section)) {

            // --- Display Language card ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.display_language_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    ExposedDropdownMenuBox(
                        expanded = showDisplayLanguageMenu,
                        onExpandedChange = { showDisplayLanguageMenu = it }
                    ) {
                        val currentLabel = if (appLanguage.isEmpty()) {
                            stringResource(R.string.display_language_system_default)
                        } else {
                            DISPLAY_LANGUAGE_OPTIONS.find { it.first == appLanguage }?.second ?: appLanguage
                        }

                        OutlinedTextField(
                            value = currentLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.display_language_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showDisplayLanguageMenu) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )

                        ExposedDropdownMenu(
                            expanded = showDisplayLanguageMenu,
                            onDismissRequest = { showDisplayLanguageMenu = false }
                        ) {
                            DISPLAY_LANGUAGE_OPTIONS.forEach { (tag, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        appLanguage = tag
                                        showDisplayLanguageMenu = false
                                    },
                                    leadingIcon = {
                                        if (appLanguage == tag) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- Speech Language card ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.speech_language_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLoadingLanguages) {
                        OutlinedTextField(
                            value = stringResource(R.string.speech_language_loading),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.speech_language_label)) },
                            trailingIcon = {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        ExposedDropdownMenuBox(
                            expanded = showLanguageMenu,
                            onExpandedChange = { showLanguageMenu = it }
                        ) {
                            val currentLabel = if (speechLanguage.isEmpty()) {
                                stringResource(R.string.speech_language_system_default)
                            } else {
                                speechLanguageOptions.find { it.first == speechLanguage }?.second
                                    ?: speechLanguage
                            }

                            OutlinedTextField(
                                value = currentLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.speech_language_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showLanguageMenu) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )

                            ExposedDropdownMenu(
                                expanded = showLanguageMenu,
                                onDismissRequest = { showLanguageMenu = false }
                            ) {
                                speechLanguageOptions.forEach { (tag, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            speechLanguage = tag
                                            showLanguageMenu = false
                                        },
                                        leadingIcon = {
                                            if (speechLanguage == tag) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            } // end CollapsibleSection for Language

            Spacer(modifier = Modifier.height(24.dp))

            // === SUPPORT SECTION ===
            CollapsibleSection(title = stringResource(R.string.support_section), collapsible = false) {

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.report_issue),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.report_issue_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val openClawVersion = runtime.serverVersion.value
                            Log.d("SettingsActivity", "Report Issue clicked. serverVersion: $openClawVersion")
                            val systemInfo = SystemInfoProvider.getSystemInfoReport(context, settings, openClawVersion)
                            val body = "\n\n$systemInfo"
                            val uri = Uri.parse("https://github.com/yuga-hashimoto/openclaw-assistant/issues/new")
                                .buildUpon()
                                .appendQueryParameter("body", body)
                                .build()
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.report_issue))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/R5R51S97C4"))
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                // No browser available; ignore
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.credits_support_kofi_button))
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    val versionName = remember {
                        runCatching {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        }.getOrNull() ?: ""
                    }
                    var isCheckingUpdate by remember { mutableStateOf(false) }

                    Button(
                        onClick = {
                            isCheckingUpdate = true
                            scope.launch {
                                val info = com.openclaw.assistant.utils.UpdateChecker.checkUpdate(versionName)
                                isCheckingUpdate = false
                                if (info != null && info.hasUpdate) {
                                    Toast.makeText(context, context.getString(R.string.update_available, info.latestVersion), Toast.LENGTH_LONG).show()
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
                                    context.startActivity(intent)
                                } else if (info != null) {
                                    Toast.makeText(context, context.getString(R.string.up_to_date), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.error_network), Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.checking_update))
                        } else {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.check_for_updates))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stringResource(R.string.app_version, versionName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            } // end CollapsibleSection for Support

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

}

data class TestResult(
    val success: Boolean,
    val message: String
)

private fun applyAppLanguage(languageTag: String) {
    val locales = if (languageTag.isBlank()) {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(languageTag)
    }
    AppCompatDelegate.setApplicationLocales(locales)
}

private val DISPLAY_LANGUAGE_OPTIONS = listOf(
    "" to "System Default",
    "en" to "English",
    "ja-JP" to "Japan（日本）",
    "zh-CN" to "China（中国）",
    "zh-TW" to "Taiwan（台灣）",
    "hi-IN" to "India（भारत）",
    "de-DE" to "Germany（Deutschland）",
    "de-AT" to "Austria（Österreich）",
    "ru-RU" to "Russia（Россия）",
    "fr" to "Français",
    "es" to "Español"
)

// Fallback language list used when device query fails
private val FALLBACK_SPEECH_LANGUAGES = listOf(
    "en-US" to "English (US)",
    "en-GB" to "English (UK)",
    "en-AU" to "English (Australia)",
    "en-CA" to "English (Canada)",
    "en-IN" to "English (India)",
    "ja-JP" to "日本語",
    "zh-CN" to "中文 (简体)",
    "zh-TW" to "中文 (繁體)",
    "zh-HK" to "中文 (香港)",
    "hi-IN" to "हिन्दी",
    "de-DE" to "Deutsch (Deutschland)",
    "de-AT" to "Deutsch (Österreich)",
    "ru-RU" to "Русский",
    "fr-FR" to "Français (France)",
    "fr-CA" to "Français (Canada)",
    "es-ES" to "Español (España)",
    "es-MX" to "Español (México)",
    "pt-BR" to "Português (Brasil)",
    "pt-PT" to "Português (Portugal)",
    "it-IT" to "Italiano (Italia)",
    "ko-KR" to "한국어",
    "ar-SA" to "العربية (السعودية)",
    "tr-TR" to "Türkçe",
    "nl-NL" to "Nederlands",
    "pl-PL" to "Polski",
    "sv-SE" to "Svenska",
    "no-NO" to "Norsk",
    "da-DK" to "Dansk",
    "fi-FI" to "Suomi",
    "cs-CZ" to "Čeština",
    "el-GR" to "Ελληνικά",
    "he-IL" to "עברית",
    "id-ID" to "Bahasa Indonesia",
    "ms-MY" to "Bahasa Melayu",
    "uk-UA" to "Українська",
    "ro-RO" to "Română",
    "hu-HU" to "Magyar",
    "th-TH" to "ไทย",
    "vi-VN" to "Tiếng Việt"
)

// TTS Provider Settings Cards

@Composable
fun ElevenLabsSettingsCard(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    voiceId: String,
    onVoiceIdChange: (String) -> Unit,
    showApiKey: Boolean,
    onShowApiKeyChange: (Boolean) -> Unit,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // Ensure voiceId is never null
    val safeVoiceId = voiceId ?: ""
    
    var usePresetVoice by rememberSaveable { mutableStateOf(safeVoiceId.isEmpty() || ElevenLabsVoiceOptions.VOICES.any { it.id == safeVoiceId }) }
    var customVoiceId by rememberSaveable { mutableStateOf(if (usePresetVoice) "" else safeVoiceId) }
    var showVoiceDropdown by rememberSaveable { mutableStateOf(false) }
    
    val context = LocalContext.current
    
    // Find selected voice name (safely)
    val selectedVoice: ElevenLabsVoiceOptions.VoiceOption = remember(safeVoiceId) {
        ElevenLabsVoiceOptions.VOICES.find { it.id == safeVoiceId } 
            ?: ElevenLabsVoiceOptions.VOICES.firstOrNull()
            ?: ElevenLabsVoiceOptions.VoiceOption("", "デフォルト", "")
    }
    
    // Ensure we have a valid voice name for display
    val selectedVoiceName = selectedVoice.name
    val selectedVoiceDescription = selectedVoice.description
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "ElevenLabs Settings",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            // API Key input with show/hide toggle
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text(stringResource(R.string.elevenlabs_api_key_label)) },
                trailingIcon = {
                    IconButton(onClick = { onShowApiKeyChange(!showApiKey) }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "Hide API Key" else "Show API Key"
                        )
                    }
                },
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            // API Key link button
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://elevenlabs.io/app/developers/api-keys"))
                    context.startActivity(intent)
                },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    stringResource(R.string.get_api_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Filter chips to toggle between preset and custom voice
            Text(
                stringResource(R.string.select_voice),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = usePresetVoice,
                    onClick = { 
                        usePresetVoice = true
                        if (safeVoiceId.isNotEmpty() && ElevenLabsVoiceOptions.VOICES.none { it.id == safeVoiceId }) {
                            onVoiceIdChange("")
                        }
                    },
                    label = { Text(stringResource(R.string.preset_voice)) }
                )
                FilterChip(
                    selected = !usePresetVoice,
                    onClick = { 
                        usePresetVoice = false
                        if (customVoiceId.isNotEmpty()) {
                            onVoiceIdChange(customVoiceId)
                        }
                    },
                    label = { Text(stringResource(R.string.custom_voice)) }
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (usePresetVoice) {
                // Preset voice dropdown
                ExposedDropdownMenuBox(
                    expanded = showVoiceDropdown,
                    onExpandedChange = { showVoiceDropdown = it }
                ) {
                    OutlinedTextField(
                        value = selectedVoiceName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.select_voice)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showVoiceDropdown) },
                        supportingText = { 
                            if (selectedVoiceDescription.isNotEmpty()) {
                                Text(selectedVoiceDescription, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = showVoiceDropdown,
                        onDismissRequest = { showVoiceDropdown = false }
                    ) {
                        ElevenLabsVoiceOptions.VOICES.forEach { voice ->
                            DropdownMenuItem(
                                text = { 
                                    Column {
                                        Text(voice.name, style = MaterialTheme.typography.bodyMedium)
                                        if (voice.description.isNotEmpty()) {
                                            Text(
                                                voice.description, 
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    onVoiceIdChange(voice.id)
                                    showVoiceDropdown = false
                                },
                                leadingIcon = {
                                    if (safeVoiceId == voice.id || (safeVoiceId.isEmpty() && voice.id.isEmpty())) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            )
                        }
                    }
                }
            } else {
                // Custom Voice ID input
                OutlinedTextField(
                    value = customVoiceId,
                    onValueChange = { 
                        customVoiceId = it
                        onVoiceIdChange(it)
                    },
                    label = { Text(stringResource(R.string.custom_voice_id)) },
                    supportingText = { Text(stringResource(R.string.elevenlabs_custom_voice_help)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Speed setting (ElevenLabs API limitation: 0.7 to 1.2)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.voice_speed), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "%.2fx".format(speed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = stringResource(R.string.voice_speed_range, "0.70", "1.20"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Slider(
                value = speed,
                onValueChange = onSpeedChange,
                valueRange = 0.7f..1.2f,
                steps = 4, // 0.7, 0.8, 0.9, 1.0, 1.1, 1.2
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun OpenAISettingsCard(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    voice: String,
    onVoiceChange: (String) -> Unit,
    showApiKey: Boolean,
    onShowApiKeyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val voiceOptions = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "OpenAI Settings",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("API Key") },
                trailingIcon = {
                    IconButton(onClick = { onShowApiKeyChange(!showApiKey) }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "Hide API Key" else "Show API Key"
                        )
                    }
                },
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            // API Key link button
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.openai.com/api-keys"))
                    context.startActivity(intent)
                },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    stringResource(R.string.get_api_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = voice,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Voice") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    voiceOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                onVoiceChange(option)
                                expanded = false
                            },
                            leadingIcon = {
                                if (voice == option) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceVoxSettingsCard(
    styleId: Int,
    onStyleIdChange: (Int) -> Unit,
    termsAccepted: Boolean,
    onTermsAcceptedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showTermsDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var vvmToDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var showCharacterDropdown by rememberSaveable { mutableStateOf(false) }
    var showSetupDialog by rememberSaveable { mutableStateOf(false) }

    // Use VoiceVoxModelManager to check actual download status
    val modelManager = remember { com.openclaw.assistant.speech.VoiceVoxModelManager(context) }

    // Get list of downloaded VVM files (not characters)
    var downloadedVvmFiles by remember { mutableStateOf(listOf<String>()) }

    // Function to refresh status
    fun refreshStatus() {
        downloadedVvmFiles = modelManager.getDownloadedVvmFiles()
    }

    // Refresh whenever termsAccepted changes
    LaunchedEffect(termsAccepted) {
        refreshStatus()
    }
    
    val selectedCharacter = VoiceVoxCharacters.getById(styleId)
    val selectedVvmFile = selectedCharacter?.vvmFileName ?: "0"
    val isReady = modelManager.isVvmModelReady(selectedVvmFile) && modelManager.isDictionaryReady()
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "VOICEVOX Settings",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            // Download status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isReady) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        if (isReady) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isReady) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Text(
                        if (isReady) {
                            stringResource(R.string.voicevox_downloaded)
                        } else {
                            stringResource(R.string.voicevox_download_required)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isReady) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Character/Style Selection dropdown
            Text(
                stringResource(R.string.character_style),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            ExposedDropdownMenuBox(
                expanded = showCharacterDropdown,
                onExpandedChange = { showCharacterDropdown = it }
            ) {
                OutlinedTextField(
                    value = selectedCharacter?.let { "${it.name}（${it.styleName}）" } ?: "Unknown",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.select_character)) },
                    trailingIcon = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selectedCharacter != null && downloadedVvmFiles.contains(selectedCharacter.vvmFileName)) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Downloaded",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCharacterDropdown)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                
                ExposedDropdownMenu(
                    expanded = showCharacterDropdown,
                    onDismissRequest = { showCharacterDropdown = false }
                ) {
                    VoiceVoxCharacters.CHARACTERS.forEach { character ->
                        val isVvmReady = modelManager.isVvmModelReady(character.vvmFileName)
                        DropdownMenuItem(
                            text = { 
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("${character.name}（${character.styleName}）")
                                    if (isVvmReady) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Downloaded",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onStyleIdChange(character.id)
                                showCharacterDropdown = false
                            },
                            leadingIcon = {
                                if (styleId == character.id) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                    }
                }
            }
            
            // Selected character info
            if (selectedCharacter != null) {
                val isVvmReady = modelManager.isVvmModelReady(selectedCharacter.vvmFileName)
                Text(
                    stringResource(R.string.voicevox_selected_character, 
                        "${selectedCharacter.name}（${selectedCharacter.styleName}）", 
                        modelManager.getVvmFileSizeMB(selectedCharacter.vvmFileName) +
                        if (isVvmReady) " - ${context.getString(R.string.voicevox_downloaded)}" else ""
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )

                // Credit info for the selected character
                val creditData = com.openclaw.assistant.speech.voicevox.VoiceVoxCharacters
                    .getCharacterByStyleId(styleId)
                if (creditData != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                                Text(
                                    "クレジット表記",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                creditData.creditNotation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                creditData.copyright,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            TextButton(
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(creditData.termsUrl))
                                        context.startActivity(intent)
                                    } catch (e: ActivityNotFoundException) {
                                        // ignore
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    Icons.Default.OpenInBrowser,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "利用規約を確認",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Terms acceptance card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.voicevox_terms_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.voicevox_terms_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    TextButton(
                        onClick = { showTermsDialog = true },
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(stringResource(R.string.voicevox_view_terms))
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.voicevox_accept_terms),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = termsAccepted,
                            onCheckedChange = onTermsAcceptedChange
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Downloaded VVM files list
            if (downloadedVvmFiles.isNotEmpty()) {
                Text(
                    stringResource(R.string.voicevox_downloaded_files),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                downloadedVvmFiles.forEach { vvmFile ->
                    // Get display name for this VVM file
                    val ctx = LocalContext.current
                    val vvmDisplayName = VoiceVoxCharacters.getVvmDisplayName(vvmFile, ctx)
                    val styleCount = VoiceVoxCharacters.getCharactersInVvm(vvmFile).size
                    
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    vvmDisplayName,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "${vvmFile}.vvm · ${modelManager.getVvmFileSizeMB(vvmFile)} · ${styleCount}スタイル",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (vvmFile == selectedVvmFile) {
                                        vvmToDelete = vvmFile
                                        showDeleteConfirmDialog = true
                                    } else {
                                        modelManager.deleteVvmModel(vvmFile)
                                        refreshStatus()
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            // Download/Redownload button
            Button(
                onClick = { showSetupDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isReady) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            ) {
                Icon(
                    if (isReady) Icons.Default.Refresh else Icons.Default.Download,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isReady) {
                        stringResource(R.string.voicevox_redownload)
                    } else {
                        stringResource(R.string.voicevox_download)
                    }
                )
            }
        }
    }
    
    // Terms Dialog
    if (showTermsDialog) {
        AlertDialog(
            onDismissRequest = { showTermsDialog = false },
            title = { Text(stringResource(R.string.voicevox_terms_dialog_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(stringResource(R.string.voicevox_terms_full))
                }
            },
            confirmButton = {
                TextButton(onClick = { showTermsDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
    
    // Delete Confirmation Dialog
    if (showDeleteConfirmDialog && vvmToDelete != null) {
        AlertDialog(
            onDismissRequest = { 
                showDeleteConfirmDialog = false
                vvmToDelete = null
            },
            title = { Text(stringResource(R.string.voicevox_delete_confirm_title)) },
            text = { 
                val charsInVvm = VoiceVoxCharacters.CHARACTERS.filter { it.vvmFileName == vvmToDelete }
                Text(stringResource(R.string.voicevox_delete_confirm_message, 
                    "${vvmToDelete}.vvm (${charsInVvm.joinToString(", ") { it.name }})")) 
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        modelManager.deleteVvmModel(vvmToDelete!!)
                        refreshStatus()
                        showDeleteConfirmDialog = false
                        vvmToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteConfirmDialog = false
                    vvmToDelete = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Setup Dialog - managed internally so refreshStatus() can be called on completion
    if (showSetupDialog) {
        VoiceVoxSetupDialog(
            vvmFileName = selectedVvmFile,
            onDismiss = { showSetupDialog = false },
            onComplete = {
                onTermsAcceptedChange(true)
                showSetupDialog = false
                refreshStatus()
            }
        )
    }
}

@Composable
fun VoiceVoxSetupDialog(
    vvmFileName: String,
    onDismiss: () -> Unit,
    onComplete: () -> Unit
) {
    var currentStep by rememberSaveable { mutableStateOf(0) }
    var dictionaryProgress by rememberSaveable { mutableStateOf(0f) }
    var modelProgress by rememberSaveable { mutableStateOf(0f) }
    var hasError by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf("") }
    var isComplete by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val modelManager = remember { com.openclaw.assistant.speech.VoiceVoxModelManager(context) }

    // Actual download progress
    LaunchedEffect(currentStep) {
        when (currentStep) {
            1 -> {
                // Copy OpenJTalk dictionary from assets
                hasError = false
                dictionaryProgress = 0f
                modelManager.copyDictionaryFromAssets().collect { progress ->
                    when (progress) {
                        is com.openclaw.assistant.speech.VoiceVoxModelManager.CopyProgress.Copying ->
                            dictionaryProgress = progress.percent / 100f
                        is com.openclaw.assistant.speech.VoiceVoxModelManager.CopyProgress.Success -> {
                            dictionaryProgress = 1f
                            currentStep = 2
                        }
                        is com.openclaw.assistant.speech.VoiceVoxModelManager.CopyProgress.Error -> {
                            hasError = true
                            errorMessage = progress.message
                        }
                    }
                }
            }
            2 -> {
                // Download VVM model file
                modelProgress = 0f
                modelManager.downloadVvmModel(vvmFileName).collect { progress ->
                    when (progress) {
                        is com.openclaw.assistant.speech.VoiceVoxModelManager.DownloadProgress.Downloading ->
                            modelProgress = progress.percent / 100f
                        is com.openclaw.assistant.speech.VoiceVoxModelManager.DownloadProgress.Success -> {
                            modelProgress = 1f
                            isComplete = true
                        }
                        is com.openclaw.assistant.speech.VoiceVoxModelManager.DownloadProgress.Error -> {
                            hasError = true
                            errorMessage = progress.message
                        }
                    }
                }
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = { if (isComplete || hasError || currentStep == 0) onDismiss() },
        title = { 
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isComplete && !hasError && currentStep > 0) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(stringResource(R.string.voicevox_setup_title))
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when {
                    hasError -> {
                        // Error display
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    errorMessage,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                    isComplete -> {
                        // Completion display
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    stringResource(R.string.voicevox_setup_complete),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    currentStep == 0 -> {
                        // Initial state
                        Text(stringResource(R.string.voicevox_setup_description))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.voicevox_setup_steps),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        // Progress display
                        // OpenJTalk dictionary progress
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.voicevox_step_dictionary))
                                Text("${(dictionaryProgress * 100).toInt()}%")
                            }
                            LinearProgressIndicator(
                                progress = { dictionaryProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        
                        // VVM model download progress
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.voicevox_step_model))
                                Text("${(modelProgress * 100).toInt()}%")
                            }
                            LinearProgressIndicator(
                                progress = { modelProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                hasError -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = {
                                hasError = false
                                currentStep = 1
                            }
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                isComplete -> {
                    Button(onClick = onComplete) {
                        Text(stringResource(R.string.done))
                    }
                }
                currentStep == 0 -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(onClick = { currentStep = 1 }) {
                            Text(stringResource(R.string.voicevox_start_download))
                        }
                    }
                }
                else -> {
                    // Cancel button during download
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    )
}
