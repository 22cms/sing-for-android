package org.francasio.sing.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.francasio.sing.R
import org.francasio.sing.ui.components.CollapsingTopBarDefaults
import org.francasio.sing.ui.components.ExpressiveCollapsingTopBar
import org.francasio.sing.ui.components.NavigationIcon
import org.francasio.sing.ui.components.rememberCollapsingProgress
import org.francasio.sing.ui.sections.ModelFilterRow
import org.francasio.sing.ui.sections.ModelGroupCard
import org.francasio.sing.ui.theme.BackgroundDark
import org.francasio.sing.ui.theme.CardDark
import org.francasio.sing.ui.theme.Primary
import org.francasio.sing.ui.theme.TextPrimary
import org.francasio.sing.ui.theme.TextSecondary
import org.francasio.sing.util.ModelLoader

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModelSelectionScreen(
    selectedModelId: String,
    scrollToSelected: Boolean = false,
    onModelSelect: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val modelDefinitions = remember {
        mutableStateListOf<ModelLoader.ModelDefinition>().apply {
            if (ModelLoader.MODEL_DEFINITIONS.isNotEmpty()) {
                addAll(ModelLoader.MODEL_DEFINITIONS)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (modelDefinitions.isEmpty()) {
            val initial = withContext(Dispatchers.IO) { ModelLoader.loadModels(context) }
            if (initial.isNotEmpty()) {
                modelDefinitions.clear()
                modelDefinitions.addAll(initial)
            }
        }

        ModelLoader.refreshModels(context) { updated ->
            if (modelDefinitions.toList() != updated) {
                modelDefinitions.clear()
                modelDefinitions.addAll(updated)
            }
        }
    }

    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val totalPinnedHeight = CollapsingTopBarDefaults.PinnedTopBarHeight + statusBarTop
    val progress = rememberCollapsingProgress(scrollState)

    val cardPositions = remember { mutableMapOf<String, Int>() }
    val highlightPulse = remember { Animatable(0f) }

    var selectedStemFilter by remember { mutableStateOf<String?>(null) } // "vocal", "instrumental", or null
    var backingRetentionOnly by remember { mutableStateOf(false) }
    var selectedFamilyFilter by remember { mutableStateOf<String?>(null) }

    val availableFamilies by remember {
        derivedStateOf {
            modelDefinitions.map { it.resolvedFamily }.distinct().filter { it.isNotBlank() }
        }
    }

    val visibleModelDefinitions by remember {
        derivedStateOf {
            modelDefinitions.filter { def ->
                val matchesStem = selectedStemFilter == null || def.targetStem.equals(selectedStemFilter, ignoreCase = true)
                val matchesBacking = !backingRetentionOnly || (def.keepsBackingVocals || def.versions.any { it.keepsBackingVocals })
                val matchesFamily = selectedFamilyFilter == null || def.resolvedFamily.equals(selectedFamilyFilter, ignoreCase = true)
                matchesStem && matchesBacking && matchesFamily
            }
        }
    }

    var isRenderReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(350)
        isRenderReady = true
    }

    LaunchedEffect(isRenderReady, scrollToSelected, visibleModelDefinitions.size) {
        if (isRenderReady && scrollToSelected && visibleModelDefinitions.isNotEmpty()) {
            delay(100)
            val targetDef = visibleModelDefinitions.find { it.versions.any { v -> v.id == selectedModelId } || it.id == selectedModelId }
            if (targetDef != null) {
                val y = cardPositions[targetDef.id]
                if (y != null) {
                    val scrollTarget = (y - with(density) { (totalPinnedHeight + 16.dp).toPx() }.toInt()).coerceAtLeast(0)
                    scrollState.animateScrollTo(scrollTarget, tween(450, easing = FastOutSlowInEasing))
                }
            }
            delay(100)
            highlightPulse.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
            highlightPulse.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            // Space to clear the large expanded header with comfortable breathing room
            Spacer(modifier = Modifier.height(statusBarTop + 196.dp))

            // Filter Chips Bar
            ModelFilterRow(
                availableFamilies = availableFamilies,
                selectedFamilyFilter = selectedFamilyFilter,
                onFamilyFilterChange = { selectedFamilyFilter = it },
                selectedStemFilter = selectedStemFilter,
                onStemFilterChange = { selectedStemFilter = it },
                backingRetentionOnly = backingRetentionOnly,
                onBackingRetentionChange = { backingRetentionOnly = it }
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (!isRenderReady) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 72.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator(
                        color = Primary
                    )
                }
            } else {
                // Empty state if filters match no models
                if (visibleModelDefinitions.isEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardDark),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(id = R.string.filter_no_models),
                                color = TextSecondary,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Split by Models First: Render a separate Card for each Model Definition
                visibleModelDefinitions.forEach { modelDef ->
                    val isSelectedGroup = modelDef.versions.any { it.id == selectedModelId } || modelDef.id == selectedModelId
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .onGloballyPositioned { coordinates ->
                                val y = coordinates.positionInParent().y.toInt()
                                cardPositions[modelDef.id] = y
                            }
                    ) {
                        ModelGroupCard(
                            modelDef = modelDef,
                            selectedModelId = selectedModelId,
                            highlightPulse = if (isSelectedGroup) highlightPulse.value else 0f,
                            selectedStemFilter = selectedStemFilter,
                            backingRetentionOnly = backingRetentionOnly,
                            selectedFamilyFilter = selectedFamilyFilter,
                            onVersionSelect = onModelSelect
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Hardware Acceleration Info Card (AOSP Details style)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier
                                .size(22.dp)
                                .padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = stringResource(id = R.string.model_info_title),
                                color = TextPrimary,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(id = R.string.model_info_desc),
                                color = TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
            }

            // Bottom spacer with see-through navbar padding so cards glide beneath the transparent system navbar
            Spacer(modifier = Modifier.height(24.dp))
            Spacer(modifier = Modifier.navigationBarsPadding())
        }

        // Top Bar
        ExpressiveCollapsingTopBar(
            progress = progress,
            title = stringResource(id = R.string.title_model_selection),
            subtitle = stringResource(id = R.string.desc_model_selection),
            navigationIcon = NavigationIcon.Morphing(
                icon = painterResource(id = R.drawable.ic_ai_models),
                onBack = onBack
            )
        )
    }
}
