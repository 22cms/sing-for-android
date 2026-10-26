package org.francasio.sing.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.francasio.sing.audio.StftEngine
import org.francasio.sing.provider.SingSettings
import org.francasio.sing.ui.theme.AppleMusicSingTheme
import org.francasio.sing.audio.backend.InferenceBackend
import org.francasio.sing.audio.backend.OnnxBackendFactory
import org.francasio.sing.audio.backend.OnnxInferenceBackend
import org.francasio.sing.util.ModelDownloader
import org.francasio.sing.util.ModelLoader
import org.francasio.sing.R
import org.francasio.sing.util.NativeLibDownloader
import org.francasio.sing.update.UpdateChecker
import org.francasio.sing.update.UpdateInfo
import java.util.concurrent.Executors
import kotlin.math.sin

import org.francasio.sing.util.XposedStatus

enum class AppScreen {
    MAIN,
    MODEL_SELECTION,
    INFO
}

class MainActivity : ComponentActivity() {

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val isModuleLoadedState = mutableStateOf(false)
    private val selectedModelIdState = mutableStateOf(SingSettings.DEFAULT_MODEL_ID)
    private val currentScreenState = mutableStateOf(AppScreen.MAIN)
    private val openedFromMusicState = mutableStateOf(false)
    private val preserveSideState = mutableStateOf(false)
    private val twoWaySliderState = mutableStateOf(false)
    private val allowZeroFloorState = mutableStateOf(false)
    private val forceSongEligibilityState = mutableStateOf(false)
    private val blurUnfocusedLyricsState = mutableStateOf(true)
    private val centerGroupLyricsState = mutableStateOf(true)
    private val englishTranslationFallbackState = mutableStateOf(true)
    private val ecoModeState = mutableStateOf(true)
    private val hardwareTargetState = mutableStateOf(SingSettings.TARGET_NPU)
    private val threadCountState = mutableIntStateOf(4)
    private val materialYouState = mutableStateOf(true)
    private val benchmarkState = mutableStateOf(BenchmarkUiState())
    private val isModuleVersionMismatchState = mutableStateOf(false)
    private val appVersionState = mutableStateOf("")
    private val moduleVersionState = mutableStateOf("")
    private val xposedManagerInfoState = mutableStateOf("")
    private val showRuntimeDownloadDialogState = mutableStateOf(false)
    private val runtimeDownloadLabelState = mutableStateOf("")
    private val runtimeDownloadProgressState = mutableFloatStateOf(0f)
    private var runtimeDownloadStarted = false
    private val showWhatsNewState = mutableStateOf(false)
    private val availableUpdateState = mutableStateOf<UpdateInfo?>(null)

    private fun syncUiStatesWithSettings() {
        selectedModelIdState.value = SingSettings.selectedModelId
        preserveSideState.value = SingSettings.preserveSide
        twoWaySliderState.value = SingSettings.twoWaySlider
        allowZeroFloorState.value = SingSettings.allowZeroFloor
        forceSongEligibilityState.value = SingSettings.forceSongEligibility
        blurUnfocusedLyricsState.value = SingSettings.blurUnfocusedLyrics
        centerGroupLyricsState.value = SingSettings.centerGroupLyrics
        englishTranslationFallbackState.value = SingSettings.englishTranslationFallback
        ecoModeState.value = SingSettings.ecoMode
        hardwareTargetState.value = SingSettings.hardwareTarget
        threadCountState.intValue = SingSettings.threadCount.coerceIn(1, 12)
        materialYouState.value = SingSettings.materialYou
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        openedFromMusicState.value = intent?.getBooleanExtra("from_apple_music", false) ?: false

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val whatsNewPrefs = getSharedPreferences(WhatsNewRelease.PREFS_NAME, MODE_PRIVATE)
        val lastSeenRelease = whatsNewPrefs.getString(WhatsNewRelease.KEY_LAST_SEEN_RELEASE, null)
        showWhatsNewState.value = WhatsNewRelease.shouldShow(
            lastSeenRelease,
            packageInfo.firstInstallTime,
            packageInfo.lastUpdateTime
        )
        if (!showWhatsNewState.value && lastSeenRelease == null) {
            whatsNewPrefs.edit().putString(WhatsNewRelease.KEY_LAST_SEEN_RELEASE, WhatsNewRelease.RELEASE_MARKER).apply()
        }

        SingSettings.loadLocal(this)
        ModelLoader.loadModels(this)
        syncUiStatesWithSettings()
        bgExecutor.execute {
            ModelDownloader.prewarmDownloadStates(this, ModelLoader.AVAILABLE_MODELS)
            ModelLoader.refreshModels(this)
            val update = UpdateChecker.check(
                this,
                packageInfo.versionName ?: XposedStatus.getAppVersion(this)
            )
            if (update != null) {
                runOnUiThread { availableUpdateState.value = update }
            }
        }

        isModuleLoadedState.value = XposedStatus.isModuleActive(this)
        isModuleVersionMismatchState.value = XposedStatus.isModuleVersionMismatch(this)
        appVersionState.value = "v${XposedStatus.getAppVersion(this)}"
        moduleVersionState.value = XposedStatus.getModuleVersion(this)
        xposedManagerInfoState.value = XposedStatus.getEffectiveManagerInfo(this)

        XposedStatus.initServiceListener {
            runOnUiThread {
                isModuleLoadedState.value = XposedStatus.isModuleActive(this)
                isModuleVersionMismatchState.value = XposedStatus.isModuleVersionMismatch(this)
                moduleVersionState.value = XposedStatus.getModuleVersion(this)
                xposedManagerInfoState.value = XposedStatus.getEffectiveManagerInfo(this)
            }
        }

        setContent {
            val isModuleLoaded by isModuleLoadedState
            val selectedModelId by selectedModelIdState
            val currentScreen by currentScreenState
            val openedFromMusic by openedFromMusicState
            val preserveSide by preserveSideState
            val twoWaySlider by twoWaySliderState
            val allowZeroFloor by allowZeroFloorState
            val forceSongEligibility by forceSongEligibilityState
            val blurUnfocusedLyrics by blurUnfocusedLyricsState
            val centerGroupLyrics by centerGroupLyricsState
            val englishTranslationFallback by englishTranslationFallbackState
            val ecoMode by ecoModeState
            val hardwareTarget by hardwareTargetState
            val threadCount by threadCountState
            val materialYou by materialYouState
            val currentBenchmarkState by benchmarkState
            val isModuleVersionMismatch by isModuleVersionMismatchState
            val appVersion by appVersionState
            val moduleVersion by moduleVersionState
            val xposedManagerInfo by xposedManagerInfoState

            var scrollToSelectedOnOpen by remember { mutableStateOf(false) }
            var backProgress by remember { mutableFloatStateOf(0f) }
            var isPredictiveBackActive by remember { mutableStateOf(false) }
            var closedViaPredictiveBack by remember { mutableStateOf(false) }
            val mainScreenScale = remember { Animatable(1f) }
            val mainScreenAlpha = remember { Animatable(1f) }
            val coroutineScope = rememberCoroutineScope()

            LaunchedEffect(currentScreen) {
                if (currentScreen != AppScreen.MAIN) {
                    backProgress = 0f
                    isPredictiveBackActive = false
                    closedViaPredictiveBack = false
                    launch { mainScreenScale.animateTo(0.92f, tween(durationMillis = 280, easing = FastOutSlowInEasing)) }
                    launch { mainScreenAlpha.animateTo(0.7f, tween(durationMillis = 240, easing = FastOutSlowInEasing)) }
                } else {
                    if (!closedViaPredictiveBack) {
                        launch { mainScreenScale.animateTo(1f, tween(durationMillis = 280, easing = FastOutSlowInEasing)) }
                        launch { mainScreenAlpha.animateTo(1f, tween(durationMillis = 240, easing = FastOutSlowInEasing)) }
                    } else {
                        mainScreenScale.snapTo(1f)
                        mainScreenAlpha.snapTo(1f)
                        closedViaPredictiveBack = false
                    }
                }
            }

            AppleMusicSingTheme(dynamicColor = materialYou) {
                if (currentScreen != AppScreen.MAIN) {
                    PredictiveBackHandler(enabled = true) { progressFlow: Flow<BackEventCompat> ->
                        var receivedProgress = false
                        try {
                            progressFlow.collect { event ->
                                receivedProgress = true
                                isPredictiveBackActive = true
                                backProgress = event.progress
                            }
                            if (receivedProgress) {
                                withContext(NonCancellable) {
                                    closedViaPredictiveBack = true
                                    val anim = Animatable(backProgress)
                                    anim.animateTo(1f, animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)) {
                                        backProgress = value
                                    }
                                    mainScreenScale.snapTo(1f)
                                    mainScreenAlpha.snapTo(1f)
                                    currentScreenState.value = AppScreen.MAIN
                                    isPredictiveBackActive = false
                                    // Keep backProgress at 1f so unmounting frame does not jump back to center
                                }
                            } else {
                                closedViaPredictiveBack = false
                                currentScreenState.value = AppScreen.MAIN
                            }
                        } catch (e: CancellationException) {
                            withContext(NonCancellable) {
                                val anim = Animatable(backProgress)
                                anim.animateTo(0f, animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)) {
                                    backProgress = value
                                }
                                isPredictiveBackActive = false
                                backProgress = 0f
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    val activeModelInfo = ModelLoader.findCatalogModel(selectedModelId)
                    val canPreserveSide = activeModelInfo?.keepsBackingVocals != true

                    MainScreen(
                        isModuleLoaded = isModuleLoaded,
                        isModuleVersionMismatch = isModuleVersionMismatch,
                        appVersion = appVersion,
                        moduleVersion = moduleVersion,
                        xposedManagerInfo = xposedManagerInfo,
                        preserveSide = preserveSide,
                        canPreserveSide = canPreserveSide,
                        twoWaySlider = twoWaySlider,
                        allowZeroFloor = allowZeroFloor,
                        forceSongEligibility = forceSongEligibility,
                        blurUnfocusedLyrics = blurUnfocusedLyrics,
                        centerGroupLyrics = centerGroupLyrics,
                        englishTranslationFallback = englishTranslationFallback,
                        ecoMode = ecoMode,
                        hardwareTarget = hardwareTarget,
                        selectedModelId = selectedModelId,
                        threadCount = threadCount,
                        benchmarkState = currentBenchmarkState,
                        activeProvider = if (currentBenchmarkState.result?.provider?.isNotBlank() == true) currentBenchmarkState.result!!.provider else SingSettings.activeProvider,
                        openedFromMusic = openedFromMusic,
                        onPreserveSideChange = { isChecked ->
                            preserveSideState.value = isChecked
                            SingSettings.saveLocal(this@MainActivity, SingSettings.KEY_PRESERVE_SIDE, isChecked)
                            SingSettings.broadcastSettings(this@MainActivity)
                        },
                        onTwoWaySliderChange = { isChecked ->
                            twoWaySliderState.value = isChecked
                            SingSettings.saveLocal(this@MainActivity, SingSettings.KEY_TWO_WAY_SLIDER, isChecked)
                            SingSettings.broadcastSettings(this@MainActivity)
                        },
                        onAllowZeroFloorChange = { isChecked ->
                            allowZeroFloorState.value = isChecked
                            SingSettings.saveLocal(this@MainActivity, SingSettings.KEY_ALLOW_ZERO_FLOOR, isChecked)
                            SingSettings.broadcastSettings(this@MainActivity)
                        },
                        onForceSongEligibilityChange = { isChecked ->
                            forceSongEligibilityState.value = isChecked
                            SingSettings.saveLocal(this@MainActivity, SingSettings.KEY_FORCE_SONG_ELIGIBILITY, isChecked)
                            SingSettings.broadcastSettings(this@MainActivity)
                        },
                        onBlurUnfocusedLyricsChange = { isChecked ->
                            blurUnfocusedLyricsState.value = isChecked
                            SingSettings.saveLocal(this@MainActivity, SingSettings.KEY_BLUR_UNFOCUSED_LYRICS, isChecked)
                            SingSettings.broadcastSettings(this@MainActivity)
                        },
                        onCenterGroupLyricsChange = { isChecked ->
                            centerGroupLyricsState.value = isChecked
                            SingSettings.saveLocal(this@MainActivity, SingSettings.KEY_CENTER_GROUP_LYRICS, isChecked)
                            SingSettings.broadcastSettings(this@MainActivity)
                        },
                        onEnglishTranslationFallbackChange = { isChecked ->
                            englishTranslationFallbackState.value = isChecked
                            SingSettings.saveLocal(
                                this@MainActivity,
                                SingSettings.KEY_ENGLISH_TRANSLATION_FALLBACK,
                                isChecked
                            )
                            SingSettings.broadcastSettings(this@MainActivity)
                        },
                        onEcoModeChange = { isChecked ->
                            ecoModeState.value = isChecked
                            SingSettings.saveLocal(this@MainActivity, SingSettings.KEY_ECO_MODE, isChecked)
                            SingSettings.broadcastSettings(this@MainActivity)
                        },
                        onHardwareTargetChange = { target ->
                            hardwareTargetState.value = target
                            SingSettings.saveLocalString(this@MainActivity, SingSettings.KEY_HARDWARE_TARGET, target)
                            SingSettings.broadcastSettings(this@MainActivity)
                        },
                        onThreadCountChange = { threads ->
                            threadCountState.intValue = threads
                            SingSettings.saveLocalInt(this@MainActivity, SingSettings.KEY_THREAD_COUNT, threads)
                            SingSettings.broadcastSettings(this@MainActivity)
                        },
                        onNavigateToModelSelection = { scrollToSelected ->
                            scrollToSelectedOnOpen = scrollToSelected
                            backProgress = 0f
                            isPredictiveBackActive = false
                            closedViaPredictiveBack = false
                            currentScreenState.value = AppScreen.MODEL_SELECTION
                        },
                        onNavigateToInfo = {
                            backProgress = 0f
                            isPredictiveBackActive = false
                            closedViaPredictiveBack = false
                            currentScreenState.value = AppScreen.INFO
                        },
                        onBackFromMusic = {
                            finish()
                        },
                        onRunBenchmark = {
                            if (selectedModelId.isEmpty()) return@MainScreen
                            benchmarkState.value = benchmarkState.value.copy(isRunning = true)
                            val currentThreads = threadCount
                            val currentTarget = hardwareTarget
                            val currentModelId = selectedModelId
                            bgExecutor.execute {
                                runBenchmark(currentThreads, currentTarget, currentModelId) { rtf, totalMs, stftMs, nnMs, istftMs, provider ->
                                    runOnUiThread {
                                        benchmarkState.value = BenchmarkUiState(
                                            isRunning = false,
                                            result = BenchmarkResult(
                                                rtf = rtf,
                                                totalMs = totalMs,
                                                stftMs = stftMs,
                                                nnMs = nnMs,
                                                istftMs = istftMs,
                                                threadCount = currentThreads,
                                                provider = provider
                                            )
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.graphicsLayer {
                            val s = if (isPredictiveBackActive) (0.92f + backProgress * 0.08f) else mainScreenScale.value
                            val a = if (isPredictiveBackActive) (0.7f + backProgress * 0.3f) else mainScreenAlpha.value
                            scaleX = s
                            scaleY = s
                            alpha = a
                        }
                    )

                    if (showRuntimeDownloadDialogState.value) {
                        AlertDialog(
                            onDismissRequest = {},
                            title = { Text(text = runtimeDownloadLabelState.value) },
                            text = {
                                LinearProgressIndicator(
                                    progress = { runtimeDownloadProgressState.value },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            confirmButton = {}
                        )
                    }

                    AnimatedVisibility(
                        visible = (currentScreen == AppScreen.MODEL_SELECTION),
                        enter = slideInHorizontally(
                            initialOffsetX = { fullWidth -> fullWidth },
                            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(durationMillis = 200)),
                        exit = if (closedViaPredictiveBack || isPredictiveBackActive) {
                            ExitTransition.None
                        } else {
                            slideOutHorizontally(
                                targetOffsetX = { fullWidth -> fullWidth },
                                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                            ) + fadeOut(animationSpec = tween(durationMillis = 200))
                        }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    if (isPredictiveBackActive || backProgress > 0.001f) {
                                        val s = 1f - backProgress * 0.10f
                                        scaleX = s
                                        scaleY = s
                                        translationX = backProgress * size.width
                                        alpha = (1f - backProgress * 0.15f).coerceIn(0f, 1f)
                                        clip = true
                                        shape = RoundedCornerShape((backProgress * 28).dp)
                                        shadowElevation = (backProgress * 16).dp.toPx()
                                    }
                                }
                        ) {
                            ModelSelectionScreen(
                                selectedModelId = selectedModelId,
                                scrollToSelected = scrollToSelectedOnOpen,
                                onModelSelect = { modelId ->
                                    selectedModelIdState.value = modelId
                                    SingSettings.saveLocalString(this@MainActivity, SingSettings.KEY_SELECTED_MODEL, modelId)
                                    val info = ModelLoader.findCatalogModel(modelId)
                                    if (info?.engine?.equals(SingSettings.ENGINE_ONNX, ignoreCase = true) == true) {
                                        if (hardwareTargetState.value != SingSettings.TARGET_NPU && hardwareTargetState.value != SingSettings.TARGET_CPU) {
                                            hardwareTargetState.value = SingSettings.TARGET_NPU
                                            SingSettings.saveLocalString(this@MainActivity, SingSettings.KEY_HARDWARE_TARGET, SingSettings.TARGET_NPU)
                                        }
                                    }
                                    SingSettings.broadcastSettings(this@MainActivity)
                                },
                                onBack = {
                                    closedViaPredictiveBack = false
                                    currentScreenState.value = AppScreen.MAIN
                                }
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = (currentScreen == AppScreen.INFO),
                        enter = slideInHorizontally(
                            initialOffsetX = { fullWidth -> fullWidth },
                            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(durationMillis = 200)),
                        exit = if (closedViaPredictiveBack || isPredictiveBackActive) {
                            ExitTransition.None
                        } else {
                            slideOutHorizontally(
                                targetOffsetX = { fullWidth -> fullWidth },
                                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                            ) + fadeOut(animationSpec = tween(durationMillis = 200))
                        }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    if (isPredictiveBackActive || backProgress > 0.001f) {
                                        val s = 1f - backProgress * 0.10f
                                        scaleX = s
                                        scaleY = s
                                        translationX = backProgress * size.width
                                        alpha = (1f - backProgress * 0.15f).coerceIn(0f, 1f)
                                        clip = true
                                        shape = RoundedCornerShape((backProgress * 28).dp)
                                        shadowElevation = (backProgress * 16).dp.toPx()
                                    }
                                }
                        ) {
                            InfoScreen(
                                materialYou = materialYou,
                                onToggleMaterialYou = { enabled ->
                                    materialYouState.value = enabled
                                    SingSettings.setMaterialYou(this@MainActivity, enabled)
                                },
                                onVersionClick = {
                                    showWhatsNewState.value = true
                                },
                                onBack = {
                                    closedViaPredictiveBack = false
                                    currentScreenState.value = AppScreen.MAIN
                                }
                            )
                        }
                    }
                }

                if (showWhatsNewState.value) {
                    ReleaseHighlightsSheet(
                        mode = ReleaseSheetMode.WhatsNew,
                        onDismiss = { showWhatsNewState.value = false },
                        onPrimaryAction = {
                            whatsNewPrefs.edit()
                                .putString(WhatsNewRelease.KEY_LAST_SEEN_RELEASE, WhatsNewRelease.RELEASE_MARKER)
                                .apply()
                            showWhatsNewState.value = false
                        }
                    )
                } else {
                    availableUpdateState.value?.let { update ->
                        val deferUpdate = {
                            UpdateChecker.defer(this@MainActivity, update.tagName)
                            availableUpdateState.value = null
                        }
                        ReleaseHighlightsSheet(
                            mode = ReleaseSheetMode.UpdateAvailable(update),
                            onDismiss = deferUpdate,
                            onPrimaryAction = {
                                availableUpdateState.value = null
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
                            },
                            onSecondaryAction = deferUpdate
                        )
                    }
                }
            }
        }
    }

    private fun runBenchmark(
        threadCount: Int,
        hardwareTarget: String,
        modelId: String,
        onResult: (rtf: Double, totalMs: Long, stftMs: Long, nnMs: Long, istftMs: Long, provider: String) -> Unit
    ) {
        if (modelId.isEmpty()) return
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            android.util.Log.i("AppleSing_Bench", "Starting benchmark: threads=$threadCount, target=$hardwareTarget, modelId=$modelId")
            val backend = ModelLoader.getOrCreateBackend(this, applicationInfo.sourceDir, threadCount, hardwareTarget, modelId) ?: return
            val provider = ModelLoader.activeProviderShort
            android.util.Log.i("AppleSing_Bench", "Backend initialized: provider=${ModelLoader.activeProviderName}")

            if (backend.modelInfo.isTimeDomain) {
                val blockSize = backend.modelInfo.blockSize
                val pcmIn = FloatArray(blockSize * 2)
                val pcmOut = FloatArray(blockSize * 2)
                val twoPi = 2.0 * Math.PI
                for (i in 0 until blockSize) {
                    val s = (0.5 * sin(twoPi * 440.0 * i / 44100.0)).toFloat()
                    pcmIn[i * 2] = s
                    pcmIn[i * 2 + 1] = s
                }

                // Warmup
                val warmupT0 = System.currentTimeMillis()
                backend.runTimeDomain(pcmIn, pcmOut, blockSize)
                android.util.Log.i("AppleSing_Bench", "td warmup: ${System.currentTimeMillis() - warmupT0}ms")

                // Benchmark iterations (10 blocks)
                val iterations = 10
                var totalNnMs = 0L
                for (it in 0 until iterations) {
                    val t0 = System.currentTimeMillis()
                    backend.runTimeDomain(pcmIn, pcmOut, blockSize)
                    val t1 = System.currentTimeMillis()
                    totalNnMs += (t1 - t0)
                    android.util.Log.i("AppleSing_Bench", "td iter $it: ${t1 - t0}ms")
                }

                val avgNn = maxOf(1L, totalNnMs / iterations)
                val audioDurationMs = (blockSize * 1000.0) / 44100.0 // 92.8 ms
                val rtf = audioDurationMs / avgNn.toDouble()
                val hasNan = pcmOut.any { it.isNaN() || it.isInfinite() }

                android.util.Log.i("AppleSing_Bench", "Time-domain benchmark: avgNn=${avgNn}ms, rtf=$rtf, out[0]=${pcmOut[0]}, out[100]=${pcmOut[100]}, hasNaN=$hasNan")
                onResult(rtf, avgNn, 0L, avgNn, 0L, provider)
                exportDebugIfRequested(backend)
                return
            }

            val stftEngine = StftEngine()

            val chunkPcm = FloatArray(StftEngine.TOTAL_SAMPLES * 2)
            // Fill with synthetic audio sine wave
            val twoPi = 2.0 * Math.PI
            for (i in 0 until StftEngine.TOTAL_SAMPLES) {
                val s = (0.5 * sin(twoPi * 440.0 * i / 44100.0)).toFloat()
                chunkPcm[i * 2] = s
                chunkPcm[i * 2 + 1] = s
            }

            val bins = backend.numBins
            val tensorSize = 4 * bins * StftEngine.N_FRAMES
            val inTensor = FloatArray(tensorSize)
            val outTensor = FloatArray(tensorSize)
            val outPcm = FloatArray(StftEngine.TOTAL_SAMPLES * 2)

            // Warmup pass
            android.util.Log.i("AppleSing_Bench", "Warmup forward STFT (bins=$bins)...")
            stftEngine.forward(chunkPcm, inTensor, bins)
            android.util.Log.i("AppleSing_Bench", "Warmup backend.run...")
            backend.run(inTensor, outTensor)
            android.util.Log.i("AppleSing_Bench", "Warmup inverse STFT (bins=$bins)...")
            stftEngine.inverse(outTensor, outPcm, bins)
            android.util.Log.i("AppleSing_Bench", "Warmup completed. outTensor[0]=${outTensor[0]}, outTensor[100]=${outTensor[100]}")

            // Benchmark iterations (3 runs)
            var totalStftMs = 0L
            var totalNnMs = 0L
            var totalIstftMs = 0L

            val iterations = 3
            for (it in 0 until iterations) {
                val t0 = System.currentTimeMillis()
                stftEngine.forward(chunkPcm, inTensor, bins)
                val t1 = System.currentTimeMillis()

                backend.run(inTensor, outTensor)
                val t2 = System.currentTimeMillis()

                stftEngine.inverse(outTensor, outPcm, bins)
                val t3 = System.currentTimeMillis()

                totalStftMs += (t1 - t0)
                totalNnMs += (t2 - t1)
                totalIstftMs += (t3 - t2)
            }

            stftEngine.close()

            val avgStft = totalStftMs / iterations
            val avgNn = totalNnMs / iterations
            val avgIstft = totalIstftMs / iterations
            val avgTotal = avgStft + avgNn + avgIstft

            val audioDurationMs = (StftEngine.TOTAL_SAMPLES * 1000.0) / 44100.0 // 5944.3 ms
            val rtf = if (avgTotal > 0) audioDurationMs / avgTotal else 10.0

            android.util.Log.i("AppleSing_Bench", "Benchmark successful: avgTotal=${avgTotal}ms, rtf=$rtf")
            onResult(rtf, avgTotal, avgStft, avgNn, avgIstft, provider)
            exportDebugIfRequested(backend)

        } catch (e: Throwable) {
            android.util.Log.e("AppleSing_Bench", "Benchmark failed: ${e.javaClass.simpleName}: ${e.message}", e)
            onResult(0.0, 0L, 0L, 0L, 0L, "")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openedFromMusicState.value = intent.getBooleanExtra("from_apple_music", false)
        checkRunBenchmarkIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        SingSettings.loadLocal(this)
        syncUiStatesWithSettings()
        // On upgrade, an ONNX model may already be selected while its runtime
        // libs are missing: fetch them up front with a visible dialog.
        val sel = selectedModelIdState.value
        if (!runtimeDownloadStarted && sel.contains("onnx", ignoreCase = true) &&
            !NativeLibDownloader.isOnnxRuntimeInstalled(this)) {
            runtimeDownloadStarted = true
            showRuntimeDownloadDialogState.value = true
            runtimeDownloadLabelState.value = getString(R.string.downloading_onnx_runtime)
            bgExecutor.execute {
                NativeLibDownloader.ensureOnnxRuntime(this@MainActivity) { label, p ->
                    runOnUiThread {
                        runtimeDownloadLabelState.value = label
                        runtimeDownloadProgressState.value = p
                    }
                }
                runOnUiThread { showRuntimeDownloadDialogState.value = false }
            }
        }
        isModuleLoadedState.value = XposedStatus.isModuleActive(this)
        isModuleVersionMismatchState.value = XposedStatus.isModuleVersionMismatch(this)
        appVersionState.value = "v${XposedStatus.getAppVersion(this)}"
        moduleVersionState.value = XposedStatus.getModuleVersion(this)
        xposedManagerInfoState.value = XposedStatus.getEffectiveManagerInfo(this)
        checkRunBenchmarkIntent(intent)
    }

    private fun checkRunBenchmarkIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("run_benchmark", false) == true) {
            intent.removeExtra("run_benchmark")
            // A recents/history relaunch replays the ORIGINAL launch intent (extras
            // included) when the process was killed, which once silently re-ran an
            // MDX ONNX bench in the background and ballooned the app to ~8 GB
            // (RSS+swap) and nearly took the whole device down. Never bench on a
            // history relaunch.
            if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
                android.util.Log.i("AppleSing_Bench", "Ignoring bench intent: activity relaunched from history")
                return
            }
            val t = intent.getIntExtra("threads", SingSettings.threadCount)
            val target = intent.getStringExtra("hardware_target") ?: if (intent.getBooleanExtra("force_cpu", false)) SingSettings.TARGET_CPU else SingSettings.hardwareTarget
            val m = intent.getStringExtra("model_id") ?: SingSettings.selectedModelId
            val dbg = intent.getIntExtra("qnn_debug", 0)
            if (dbg > 0) OnnxBackendFactory.qnnDebug = dbg
            val mnnBackend = intent.getIntExtra("mnn_backend", org.francasio.sing.audio.backend.MnnInferenceBackend.targetToPreference(target))
            org.francasio.sing.audio.backend.MnnInferenceBackend.currentBackendPreference = mnnBackend
            bgExecutor.execute {
                runBenchmark(t, target, m) { rtf, totalMs, stftMs, nnMs, istftMs, provider ->
                    android.util.Log.i("AppleSing_Bench", "Benchmark intent result: rtf=$rtf, total=$totalMs, stft=$stftMs, nn=$nnMs, istft=$istftMs, provider=$provider")
                }
            }
        }
    }

    /**
     * Debug only (QNN profiler): close the session so QNN flushes its profiling
     * output, then copy artifacts somewhere adb can reach.
     */
    private fun exportDebugIfRequested(backend: InferenceBackend) {
        if (OnnxBackendFactory.qnnDebug <= 0) return
        try {
            (backend as? OnnxInferenceBackend)?.close()
        } catch (t: Throwable) {
            android.util.Log.w("AppleSing_Bench", "Debug close failed: ${t.message}")
        }
        Thread.sleep(1000)
        OnnxBackendFactory.exportDebugArtifacts(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        bgExecutor.shutdown()
    }
}
