package com.aamaker.app.ui

import android.content.Intent
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader as AndroidShader
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aamaker.app.data.TargetAppCandidate
import com.aamaker.app.i18n.AppLanguage
import com.aamaker.app.i18n.AppStrings
import com.aamaker.app.i18n.AppTranslations
import com.aamaker.app.ui.theme.AppleGlass
import com.aamaker.core.installer.model.EnvironmentStatus
import com.aamaker.core.installer.model.InstallMode
import com.aamaker.core.patcher.model.CarAppCategory
import com.aamaker.core.patcher.model.PatchStrategy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AAMaKerAppScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val strings = remember(state.selectedLanguage) {
        AppTranslations.forLanguage(state.selectedLanguage)
    }
    val layoutDirection = if (state.selectedLanguage == AppLanguage.AR) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }

    var currentTab by remember { mutableIntStateOf(0) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var pendingDirectInstallOnPick by remember { mutableStateOf(false) }

    val contentGraphicsLayer = rememberGraphicsLayer()
    var contentOriginInRoot by remember { mutableStateOf(Offset.Zero) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importApkFromUri(uri, preferDirectInstall = pendingDirectInstallOnPick)
            currentTab = 1
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        if (showLanguageDialog) {
            LanguagePickerDialog(
                currentLanguage = state.selectedLanguage,
                strings = strings,
                onSelectLanguage = { lang ->
                    viewModel.selectLanguage(lang)
                    showLanguageDialog = false
                },
                onDismiss = { showLanguageDialog = false }
            )
        }

        Scaffold(
            containerColor = AppleGlass.PureBlack,
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0x28FFFFFF),
                                border = AppleGlass.SpecularBorder,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.DirectionsCar,
                                        contentDescription = null,
                                        tint = AppleGlass.AppleBlue,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "AAMaKer",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = AppleGlass.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = strings.appSubtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = AppleGlass.TextSecondary
                                )
                            }
                        }
                    },
                    actions = {
                        Surface(
                            onClick = { showLanguageDialog = true },
                            shape = CircleShape,
                            color = Color(0x1AFFFFFF),
                            border = AppleGlass.ThinBorder,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = strings.languageDialogTitle,
                                    tint = AppleGlass.TextSecondary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "${state.selectedLanguage.flagEmoji} ${state.selectedLanguage.code.uppercase()}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppleGlass.TextPrimary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        IconButton(onClick = {
                            viewModel.refreshEnvironment()
                            viewModel.loadInstalledApps()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = strings.refreshTooltip,
                                tint = AppleGlass.TextPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = AppleGlass.PureBlack.copy(alpha = 0.85f),
                        scrolledContainerColor = AppleGlass.FrostedDark
                    )
                )
            },
            bottomBar = {
                AppleLiquidGlassBottomBar(
                    currentTab = currentTab,
                    onTabSelected = { currentTab = it },
                    strings = strings,
                    backdropLayer = contentGraphicsLayer,
                    backdropOriginInRoot = contentOriginInRoot
                )
            }
        ) { innerPadding ->
            val bottomPadding = innerPadding.calculateBottomPadding()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
                    .background(AppleGlass.PureBlack)
                    .onGloballyPositioned { coords ->
                        contentOriginInRoot = coords.positionInRoot()
                    }
                    .drawWithContent {
                        contentGraphicsLayer.record {
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(contentGraphicsLayer)
                    }
            ) {
                // Ambient Liquid Glow refraction at top of OLED screen
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .background(AppleGlass.AmbientGlow)
                )

                // Subtle bottom ambient light pool so the Liquid Glass lens always has light to refract
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color(0xFF0A84FF).copy(alpha = 0.07f),
                                    Color(0xFF5E5CE6).copy(alpha = 0.05f)
                                )
                            )
                        )
                )

                when (currentTab) {
                    0 -> AppSelectorTab(
                        state = state,
                        strings = strings,
                        bottomPadding = bottomPadding,
                        onSearchChange = viewModel::updateSearchQuery,
                        onFilterAAChange = viewModel::toggleFilterOnlyAAApps,
                        onSystemAppsChange = viewModel::toggleIncludeSystemApps,
                        onSelectCandidate = { candidate ->
                            viewModel.selectCandidate(candidate)
                            currentTab = 1
                        }
                    )
                    1 -> PatchAndInstallTab(
                        state = state,
                        strings = strings,
                        viewModel = viewModel,
                        bottomPadding = bottomPadding,
                        onPickExternalFile = {
                            pendingDirectInstallOnPick = false
                            filePickerLauncher.launch(arrayOf("*/*"))
                        },
                        onPickExternalFileDirectInstall = {
                            pendingDirectInstallOnPick = true
                            filePickerLauncher.launch(arrayOf("*/*"))
                        }
                    )
                    2 -> EnvironmentDiagnosticTab(
                        env = state. envStatus,
                        strings = strings,
                        bottomPadding = bottomPadding,
                        onRequestShizuku = viewModel::requestShizukuPermission,
                        onRefresh = viewModel::refreshEnvironment
                    )
                }
            }
        }
    }
}

private const val LIQUID_GLASS_AGSL_SHADER = """
    uniform shader content;
    uniform float2 resolution;
    uniform float dropletLeft;
    uniform float dropletRight;

    half4 main(float2 fragCoord) {
        float2 res = max(resolution, float2(1.0, 1.0));
        float r = res.y * 0.5;
        float halfW = max(res.x * 0.5 - r, 0.0);
        float2 p = fragCoord - res * 0.5;

        // Signed distance & normal from capsule spine
        float spineX = clamp(p.x, -halfW, halfW);
        float2 delta = p - float2(spineX, 0.0);
        float dist = length(delta);
        float normDist = clamp(dist / max(r, 1.0), 0.0, 1.0);
        float2 dir = dist > 0.001 ? (delta / dist) : float2(0.0, 0.0);

        // Curved 3D meniscus lens refraction at the outer capsule rim
        float edgeBevel = smoothstep(0.48, 1.0, normDist);
        float lensDisplacement = edgeBevel * edgeBevel * r * 0.46;

        // Secondary moving liquid droplet lens magnification
        float dropCenterX = (dropletLeft + dropletRight) * 0.5;
        float dropHalfW = max((dropletRight - dropletLeft) * 0.5 - r * 0.72, 0.0);
        float2 dp = fragCoord - float2(dropCenterX, res.y * 0.5);
        float dSpineX = clamp(dp.x, -dropHalfW, dropHalfW);
        float2 dDelta = dp - float2(dSpineX, 0.0);
        float dDist = length(dDelta);
        float dNorm = clamp(dDist / max(r * 0.84, 1.0), 0.0, 1.5);
        float2 dDir = dDist > 0.001 ? (dDelta / dDist) : float2(0.0, 0.0);
        float inDroplet = 1.0 - smoothstep(0.84, 1.04, dNorm);
        float dropletBevel = smoothstep(0.38, 0.96, dNorm) * inDroplet;

        float2 refractOffset = -dir * lensDisplacement - dDir * (dropletBevel * r * 0.26);

        // Optical chromatic dispersion (RGB refraction splitting at glass bevel)
        float dispersion = edgeBevel * 0.16 + dropletBevel * 0.12;
        float2 uvR = clamp(fragCoord + refractOffset * (1.0 + dispersion), float2(0.0, 0.0), res);
        float2 uvG = clamp(fragCoord + refractOffset, float2(0.0, 0.0), res);
        float2 uvB = clamp(fragCoord + refractOffset * (1.0 - dispersion), float2(0.0, 0.0), res);

        half4 colR = content.eval(uvR);
        half4 colG = content.eval(uvG);
        half4 colB = content.eval(uvB);
        half4 color = half4(colR.r, colG.g, colB.b, 1.0);

        // Apple Vibrancy saturation boost + meniscus caustic concentration
        half luma = dot(color.rgb, half3(0.299, 0.587, 0.114));
        color.rgb = mix(half3(luma, luma, luma), color.rgb, half(1.38));
        float caustic = edgeBevel * edgeBevel * edgeBevel * 0.14 + dropletBevel * dropletBevel * 0.12;
        color.rgb += half3(caustic, caustic, caustic);

        return color;
    }
"""

@Composable
private fun AppleLiquidGlassBottomBar(
    currentTab: Int,
    onTabSelected: (Int) -> Unit,
    strings: AppStrings,
    backdropLayer: GraphicsLayer,
    backdropOriginInRoot: Offset,
    modifier: Modifier = Modifier
) {
    val tabs = remember(strings) {
        listOf(
            Triple(0, Icons.Default.DirectionsCar, strings.navSelectApp),
            Triple(1, Icons.Default.Build, strings.navPatchInstall),
            Triple(2, Icons.Default.Security, strings.navEnvironment)
        )
    }

    var barOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }

    // Dual-spring fluid physics for the sliding Liquid Glass droplet indicator:
    // Fast leading spring + slightly lagging trailing spring creates authentic liquid stretch & bounce
    val targetTabFloat = currentTab.toFloat()
    val fastSpringPos by animateFloatAsState(
        targetValue = targetTabFloat,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 410f
        ),
        label = "liquidLeadPos"
    )
    val slowSpringPos by animateFloatAsState(
        targetValue = targetTabFloat,
        animationSpec = spring(
            dampingRatio = 0.64f,
            stiffness = 185f
        ),
        label = "liquidTrailPos"
    )

    val dropletStartSlot = min(fastSpringPos, slowSpringPos)
    val dropletEndSlot = max(fastSpringPos, slowSpringPos)
    val liquidStretch = abs(fastSpringPos - slowSpringPos)

    val runtimeShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                RuntimeShader(LIQUID_GLASS_AGSL_SHADER)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .height(68.dp)
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    barOriginInRoot = coords.positionInRoot()
                    barSize = coords.size
                }
                // Soft optical elevation shadow beneath the floating glass capsule
                .drawBehind {
                    val cornerRadius = size.height / 2f
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.62f),
                        topLeft = Offset(0f, 8.dp.toPx()),
                        size = Size(size.width, size.height),
                        cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                    )
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                AppleGlass.AppleBlue.copy(alpha = 0.14f),
                                Color.Transparent
                            ),
                            center = Offset(
                                x = size.width * ((dropletStartSlot + dropletEndSlot + 1f) / (tabs.size * 2f)),
                                y = size.height * 0.65f
                            ),
                            radius = size.width * 0.45f
                        ),
                        topLeft = Offset(0f, 4.dp.toPx()),
                        size = Size(size.width, size.height),
                        cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                    )
                }
                .clip(CircleShape)
        ) {
            // LAYER 1: Live Refracted & Blurred Backdrop (iOS 26 Lensing Material)
            val horizontalPadPx = 6.dp
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val w = barSize.width.toFloat().coerceAtLeast(1f)
                        val h = barSize.height.toFloat().coerceAtLeast(1f)
                        val innerPad = horizontalPadPx.toPx()
                        val slotW = (w - innerPad * 2f) / tabs.size.toFloat()
                        val dLeft = innerPad + dropletStartSlot * slotW
                        val dRight = innerPad + (dropletEndSlot + 1f) * slotW

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && runtimeShader != null) {
                            try {
                                runtimeShader.setFloatUniform("resolution", w, h)
                                runtimeShader.setFloatUniform("dropletLeft", dLeft)
                                runtimeShader.setFloatUniform("dropletRight", dRight)
                                val shaderEffect = AndroidRenderEffect.createRuntimeShaderEffect(
                                    runtimeShader,
                                    "content"
                                )
                                val blurEffect = AndroidRenderEffect.createBlurEffect(
                                    32f,
                                    32f,
                                    AndroidShader.TileMode.CLAMP
                                )
                                renderEffect = AndroidRenderEffect
                                    .createChainEffect(shaderEffect, blurEffect)
                                    .asComposeRenderEffect()
                            } catch (_: Throwable) {
                                renderEffect = BlurEffect(32f, 32f, TileMode.Clamp)
                            }
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            renderEffect = BlurEffect(32f, 32f, TileMode.Clamp)
                        }
                    }
                    .drawBehind {
                        drawRect(AppleGlass.PureBlack)
                        val dx = backdropOriginInRoot.x - barOriginInRoot.x
                        val dy = backdropOriginInRoot.y - barOriginInRoot.y
                        translate(left = dx, top = dy) {
                            drawLayer(backdropLayer)
                        }
                    }
            )

            // LAYER 2: 3D Curved Liquid Glass Meniscus, Fresnel Rim & Morphing Active Droplet
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        val w = size.width
                        val h = size.height
                        val r = h / 2f

                        // 2a. Translucent Liquid Glass body tint (keeps background refraction visible)
                        drawRoundRect(
                            color = Color(0x5414141C),
                            cornerRadius = CornerRadius(r, r)
                        )

                        // 2b. Top convex meniscus specular dome reflection
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                0.0f to Color(0x3BFFFFFF),
                                0.28f to Color(0x12FFFFFF),
                                0.55f to Color.Transparent,
                                0.85f to Color(0x08FFFFFF),
                                1.0f to Color(0x22FFFFFF)
                            ),
                            cornerRadius = CornerRadius(r, r)
                        )

                        // 2c. Sliding & Elastically Morphing Liquid Droplet Pill (Active Tab Indicator)
                        val padX = 6.dp.toPx()
                        val padY = 6.dp.toPx()
                        val slotWidth = (w - padX * 2f) / tabs.size.toFloat()

                        val rawLeft = padX + dropletStartSlot * slotWidth
                        val rawRight = padX + (dropletEndSlot + 1f) * slotWidth
                        val pillWidth = (rawRight - rawLeft).coerceAtLeast(slotWidth * 0.5f)

                        // Conservation of liquid volume: slightly pinch vertical height when stretching mid-flight
                        val pinchFactor = (1f - (liquidStretch * 0.09f).coerceAtMost(0.15f))
                        val basePillHeight = h - padY * 2f
                        val pillHeight = basePillHeight * pinchFactor
                        val pillTop = (h - pillHeight) / 2f
                        val pillRadius = pillHeight / 2f

                        // Droplet internal ambient glow
                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0x450A84FF),
                                    Color(0x180A84FF),
                                    Color.Transparent
                                ),
                                center = Offset((rawLeft + rawRight) * 0.5f, h * 0.5f),
                                radius = pillWidth * 0.72f
                            ),
                            topLeft = Offset(rawLeft, pillTop),
                            size = Size(pillWidth, pillHeight),
                            cornerRadius = CornerRadius(pillRadius, pillRadius)
                        )

                        // Droplet 3D convex liquid lens body
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                0.0f to Color(0x42FFFFFF),
                                0.35f to Color(0x1EFFFFFF),
                                0.70f to Color(0x14FFFFFF),
                                1.0f to Color(0x2E82C2FF),
                                startY = pillTop,
                                endY = pillTop + pillHeight
                            ),
                            topLeft = Offset(rawLeft, pillTop),
                            size = Size(pillWidth, pillHeight),
                            cornerRadius = CornerRadius(pillRadius, pillRadius)
                        )

                        // Droplet diagonal Fresnel specular rim (top-left bright, mid soft, bottom-right caustic)
                        val dropletStroke = 1.15.dp.toPx()
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                0.0f to Color(0xCCFFFFFF),
                                0.35f to Color(0x38FFFFFF),
                                0.65f to Color(0x220A84FF),
                                1.0f to Color(0x9982C2FF),
                                start = Offset(rawLeft, pillTop),
                                end = Offset(rawRight, pillTop + pillHeight)
                            ),
                            topLeft = Offset(rawLeft + dropletStroke / 2f, pillTop + dropletStroke / 2f),
                            size = Size(pillWidth - dropletStroke, pillHeight - dropletStroke),
                            cornerRadius = CornerRadius(pillRadius, pillRadius),
                            style = Stroke(width = dropletStroke)
                        )

                        // 2d. Outer Capsule Inner Meniscus Thickness Ring (1.5dp inset bevel)
                        val innerInset = 1.6.dp.toPx()
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                0.0f to Color(0x28FFFFFF),
                                0.5f to Color(0x08000000),
                                1.0f to Color(0x1EFFFFFF)
                            ),
                            topLeft = Offset(innerInset, innerInset),
                            size = Size(w - innerInset * 2f, h - innerInset * 2f),
                            cornerRadius = CornerRadius(r - innerInset, r - innerInset),
                            style = Stroke(width = 1.dp.toPx())
                        )

                        // 2e. Outer Capsule 135° Fresnel Specular Rim Highlight
                        val outerStroke = 1.25.dp.toPx()
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                0.0f to Color(0xD9FFFFFF),
                                0.25f to Color(0x55FFFFFF),
                                0.50f to Color(0x18FFFFFF),
                                0.78f to Color(0x38FFFFFF),
                                1.0f to Color(0x88FFFFFF),
                                start = Offset(0f, 0f),
                                end = Offset(w, h)
                            ),
                            topLeft = Offset(outerStroke / 2f, outerStroke / 2f),
                            size = Size(w - outerStroke, h - outerStroke),
                            cornerRadius = CornerRadius(r, r),
                            style = Stroke(width = outerStroke)
                        )
                    }
            )

            // LAYER 3: Interactive Tab Items with Spring Scale Physics
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadPx, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { (index, icon, label) ->
                    val isSelected = currentTab == index
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()

                    val itemScale by animateFloatAsState(
                        targetValue = when {
                            isPressed -> 0.90f
                            isSelected -> 1.05f
                            else -> 0.98f
                        },
                        animationSpec = spring(
                            dampingRatio = 0.62f,
                            stiffness = 480f
                        ),
                        label = "tabScale_$index"
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { onTabSelected(index) }
                            )
                            .graphicsLayer {
                                scaleX = itemScale
                                scaleY = itemScale
                            }
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = if (isSelected) Color.White else AppleGlass.TextSecondary,
                                modifier = Modifier.size(21.dp)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else AppleGlass.TextTertiary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguagePickerDialog(
    currentLanguage: AppLanguage,
    strings: AppStrings,
    onSelectLanguage: (AppLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        containerColor = Color(0xFF141419),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = AppleGlass.AppleBlue.copy(alpha = 0.20f),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = AppleGlass.AppleBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = strings.languageDialogTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppleGlass.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(AppLanguage.entries, key = { it.code }) { lang ->
                    val isSelected = lang == currentLanguage
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) AppleGlass.AppleBlue.copy(alpha = 0.18f) else Color(0x14FFFFFF),
                        border = if (isSelected) AppleGlass.ActiveBorder else AppleGlass.ThinBorder,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelectLanguage(lang) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = lang.flagEmoji, fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = lang.nativeName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = AppleGlass.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${lang.englishName} (${lang.code.uppercase()})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppleGlass.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelectLanguage(lang) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AppleGlass.AppleBlue,
                                    unselectedColor = AppleGlass.TextTertiary
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.closeButton, color = AppleGlass.AppleBlue, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppSelectorTab(
    state: MainUiState,
    strings: AppStrings,
    bottomPadding: Dp = 16.dp,
    onSearchChange: (String) -> Unit,
    onFilterAAChange: (Boolean) -> Unit,
    onSystemAppsChange: (Boolean) -> Unit,
    onSelectCandidate: (TargetAppCandidate) -> Unit
) {
    val filteredApps = remember(state.installedApps, state.searchQuery, state.filterOnlyAAApps) {
        state.installedApps.filter { app ->
            val matchesQuery = state.searchQuery.isBlank() ||
                app.appName.contains(state.searchQuery, ignoreCase = true) ||
                app.packageName.contains(state.searchQuery, ignoreCase = true)
            val matchesAA = !state.filterOnlyAAApps || app.hasExistingCarMeta
            matchesQuery && matchesAA
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Apple iOS Style Frosted Capsule Search Bar
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0x22FFFFFF),
                unfocusedContainerColor = Color(0x14FFFFFF),
                focusedBorderColor = AppleGlass.AppleBlue,
                unfocusedBorderColor = Color(0x28FFFFFF),
                focusedTextColor = AppleGlass.TextPrimary,
                unfocusedTextColor = AppleGlass.TextPrimary,
                cursorColor = AppleGlass.AppleBlue
            ),
            placeholder = {
                Text(
                    text = strings.searchPlaceholder,
                    color = AppleGlass.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = AppleGlass.TextSecondary
                )
            },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = strings.clearSearch,
                            tint = AppleGlass.TextSecondary
                        )
                    }
                }
            },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Apple Liquid Glass Capsules
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = state.filterOnlyAAApps,
                onClick = { onFilterAAChange(!state.filterOnlyAAApps) },
                shape = CircleShape,
                border = if (state.filterOnlyAAApps) AppleGlass.ActiveBorder else AppleGlass.ThinBorder,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppleGlass.AppleBlue.copy(alpha = 0.22f),
                    selectedLabelColor = AppleGlass.AppleBlue,
                    containerColor = Color(0x14FFFFFF),
                    labelColor = AppleGlass.TextSecondary
                ),
                label = {
                    Text(
                        text = strings.filterExistingAAChip,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (state.filterOnlyAAApps) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
            FilterChip(
                selected = state.includeSystemApps,
                onClick = { onSystemAppsChange(!state.includeSystemApps) },
                shape = CircleShape,
                border = if (state.includeSystemApps) AppleGlass.ActiveBorder else AppleGlass.ThinBorder,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppleGlass.AppleBlue.copy(alpha = 0.22f),
                    selectedLabelColor = AppleGlass.AppleBlue,
                    containerColor = Color(0x14FFFFFF),
                    labelColor = AppleGlass.TextSecondary
                ),
                label = {
                    Text(
                        text = strings.filterSystemAppsChip,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (state.includeSystemApps) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (state.isLoadingApps) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppleGlass.AppleBlue)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = bottomPadding + 16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredApps, key = { it.packageName }) { candidate ->
                    CandidateAppItem(
                        candidate = candidate,
                        strings = strings,
                        onClick = { onSelectCandidate(candidate) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CandidateAppItem(
    candidate: TargetAppCandidate,
    strings: AppStrings,
    onClick: () -> Unit
) {
    val isPlaySource = candidate.currentInstallerSource == "com.android.vending"
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = AppleGlass.GlassCard,
        border = AppleGlass.SpecularBorder
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = candidate.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AppleGlass.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${candidate.packageName} • v${candidate.versionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleGlass.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (candidate.apkFiles.size > 1) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = Color(0x1EFFFFFF),
                        border = AppleGlass.ThinBorder
                    ) {
                        Text(
                            text = strings.splitsBadgeFormat(candidate.apkFiles.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = AppleGlass.TextPrimary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (candidate.hasExistingCarMeta) {
                        AppleGlass.AppleGreenGlass
                    } else {
                        Color(0x14FFFFFF)
                    },
                    border = if (candidate.hasExistingCarMeta) {
                        AppleGlass.GreenBorder
                    } else {
                        AppleGlass.ThinBorder
                    }
                ) {
                    Text(
                        text = if (candidate.hasExistingCarMeta) strings.badgeExistingAA else strings.badgeNeedInjectAA,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (candidate.hasExistingCarMeta) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (candidate.hasExistingCarMeta) {
                            AppleGlass.AppleGreen
                        } else {
                            AppleGlass.TextSecondary
                        },
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = Color(0x14FFFFFF),
                    border = AppleGlass.ThinBorder
                ) {
                    Text(
                        text = if (isPlaySource) {
                            strings.badgeSourcePlayStore
                        } else {
                            "${strings.badgeSourceOtherPrefix}${candidate.currentInstallerSource ?: strings.badgeSourceSideload}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AppleGlass.TextSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportApkHeroCard(
    strings: AppStrings,
    onPickExternalFile: () -> Unit,
    onPickExternalFileDirectInstall: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = AppleGlass.GlassCard,
        border = AppleGlass.SpecularBorder
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = AppleGlass.AppleBlue.copy(alpha = 0.22f),
                    border = BorderStroke(1.dp, AppleGlass.AppleBlue.copy(alpha = 0.5f)),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = AppleGlass.AppleBlue,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.bannerExternalTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = AppleGlass.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = strings.bannerExternalSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleGlass.TextSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onPickExternalFile,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppleGlass.AppleBlue,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.chooseFileButton,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    onClick = onPickExternalFileDirectInstall,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0x1FFFFFFF),
                    border = AppleGlass.ThinBorder
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = AppleGlass.AppleBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.chooseFileDirectInstallButton,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AppleGlass.TextPrimary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatchAndInstallTab(
    state: MainUiState,
    strings: AppStrings,
    viewModel: MainViewModel,
    bottomPadding: Dp = 16.dp,
    onPickExternalFile: () -> Unit,
    onPickExternalFileDirectInstall: () -> Unit
) {
    val candidate = state.selectedCandidate
    val saveApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { targetUri ->
        if (targetUri != null) {
            viewModel.exportLastPatchedApkToUri(targetUri)
        }
    }

    if (candidate == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomPadding + 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ImportApkHeroCard(
                strings = strings,
                onPickExternalFile = onPickExternalFile,
                onPickExternalFileDirectInstall = onPickExternalFileDirectInstall
            )

            Surface(
                shape = RoundedCornerShape(26.dp),
                color = AppleGlass.GlassCard,
                border = AppleGlass.SpecularBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = AppleGlass.AppleBlue.copy(alpha = 0.20f),
                        border = BorderStroke(1.dp, AppleGlass.AppleBlue.copy(alpha = 0.5f)),
                        modifier = Modifier.size(68.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                modifier = Modifier.size(34.dp),
                                tint = AppleGlass.AppleBlue
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = strings.emptySelectionTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppleGlass.TextPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomPadding + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ImportApkHeroCard(
            strings = strings,
            onPickExternalFile = onPickExternalFile,
            onPickExternalFileDirectInstall = onPickExternalFileDirectInstall
        )

        // Target app header card
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = AppleGlass.GlassCard,
            border = AppleGlass.SpecularBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = AppleGlass.AppleBlue.copy(alpha = 0.22f),
                    border = BorderStroke(1.dp, AppleGlass.AppleBlue.copy(alpha = 0.5f)),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.DirectionsCar,
                            contentDescription = null,
                            tint = AppleGlass.AppleBlue,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${strings.targetLabelPrefix}${candidate.appName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppleGlass.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = strings.originalPackageFormat(candidate.packageName, candidate.apkFiles.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleGlass.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Section 1: Patch Strategy
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = AppleGlass.GlassCard,
            border = AppleGlass.SpecularBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = strings.section1PatchStrategyTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AppleGlass.AppleBlue
                )

                PatchStrategy.entries.forEach { strategy ->
                    val selected = state.selectedStrategy == strategy
                    Surface(
                        onClick = { viewModel.updateStrategy(strategy) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) AppleGlass.AppleBlue.copy(alpha = 0.16f) else Color(0x10FFFFFF),
                        border = if (selected) AppleGlass.ActiveBorder else AppleGlass.ThinBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AppleGlass.AppleBlue,
                                    unselectedColor = AppleGlass.TextTertiary
                                )
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = strings.strategyTitle(strategy),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppleGlass.TextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = strings.strategyDescription(strategy),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppleGlass.TextSecondary
                                )
                            }
                        }
                    }
                }

                if (state.selectedStrategy != PatchStrategy.DIRECT_INSTALL_NO_PATCH) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color(0x20FFFFFF))

                    OutlinedTextField(
                        value = state.customPackageName,
                        onValueChange = viewModel::updateCustomPackageName,
                        label = {
                            Text(
                                text = strings.outputPackageLabel,
                                color = AppleGlass.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0x22FFFFFF),
                            unfocusedContainerColor = Color(0x14FFFFFF),
                            focusedBorderColor = AppleGlass.AppleBlue,
                            unfocusedBorderColor = Color(0x28FFFFFF),
                            focusedTextColor = AppleGlass.TextPrimary,
                            unfocusedTextColor = AppleGlass.TextPrimary,
                            cursorColor = AppleGlass.AppleBlue
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = viewModel::randomizePackageNameSuffix) {
                                Icon(Icons.Default.Shuffle, contentDescription = strings.randomizeTooltip, tint = AppleGlass.AppleBlue)
                            }
                        },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = state.customAppName,
                        onValueChange = viewModel::updateCustomAppName,
                        label = {
                            Text(
                                text = strings.carDisplayNameLabel,
                                color = AppleGlass.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0x22FFFFFF),
                            unfocusedContainerColor = Color(0x14FFFFFF),
                            focusedBorderColor = AppleGlass.AppleBlue,
                            unfocusedBorderColor = Color(0x28FFFFFF),
                            focusedTextColor = AppleGlass.TextPrimary,
                            unfocusedTextColor = AppleGlass.TextPrimary,
                            cursorColor = AppleGlass.AppleBlue
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (state.selectedStrategy != PatchStrategy.PARKED_APP_MODE) {
                        Text(
                            text = strings.automotiveDescConfigLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = AppleGlass.TextSecondary
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilterChip(
                                selected = state.injectProjection,
                                onClick = {
                                    viewModel.updateUsesFlags(!state.injectProjection, state.injectTemplate, state.injectMedia)
                                },
                                shape = CircleShape,
                                border = if (state.injectProjection) AppleGlass.ActiveBorder else AppleGlass.ThinBorder,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AppleGlass.AppleBlue.copy(alpha = 0.22f),
                                    selectedLabelColor = AppleGlass.AppleBlue,
                                    containerColor = Color(0x14FFFFFF),
                                    labelColor = AppleGlass.TextSecondary
                                ),
                                label = {
                                    Text(
                                        text = strings.projectionFullScreenLabel,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                            FilterChip(
                                selected = state.injectTemplate,
                                onClick = {
                                    viewModel.updateUsesFlags(state.injectProjection, !state.injectTemplate, state.injectMedia)
                                },
                                shape = CircleShape,
                                border = if (state.injectTemplate) AppleGlass.ActiveBorder else AppleGlass.ThinBorder,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AppleGlass.AppleBlue.copy(alpha = 0.22f),
                                    selectedLabelColor = AppleGlass.AppleBlue,
                                    containerColor = Color(0x14FFFFFF),
                                    labelColor = AppleGlass.TextSecondary
                                ),
                                label = {
                                    Text(
                                        text = strings.carTemplatesLabel,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }

                    Text(
                        text = strings.compatOptionsHeaderLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = AppleGlass.TextSecondary
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = state.selectedStrategy == PatchStrategy.PARKED_APP_MODE || state.injectParkedGameManifest,
                            enabled = state.selectedStrategy != PatchStrategy.PARKED_APP_MODE,
                            onClick = {
                                viewModel.updateCompatFlags(parkedGameManifest = !state.injectParkedGameManifest)
                            },
                            shape = CircleShape,
                            border = if (state.selectedStrategy == PatchStrategy.PARKED_APP_MODE || state.injectParkedGameManifest) AppleGlass.ActiveBorder else AppleGlass.ThinBorder,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppleGlass.AppleBlue.copy(alpha = 0.22f),
                                selectedLabelColor = AppleGlass.AppleBlue,
                                containerColor = Color(0x14FFFFFF),
                                labelColor = AppleGlass.TextSecondary
                            ),
                            label = {
                                Text(
                                    text = strings.parkedGameManifestChip,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                        FilterChip(
                            selected = state.bypassDrivingRestriction,
                            onClick = {
                                viewModel.updateCompatFlags(bypassDriving = !state.bypassDrivingRestriction)
                            },
                            shape = CircleShape,
                            border = if (state.bypassDrivingRestriction) AppleGlass.ActiveBorder else AppleGlass.ThinBorder,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppleGlass.AppleBlue.copy(alpha = 0.22f),
                                selectedLabelColor = AppleGlass.AppleBlue,
                                containerColor = Color(0x14FFFFFF),
                                labelColor = AppleGlass.TextSecondary
                            ),
                            label = {
                                Text(
                                    text = strings.bypassDrivingRestrictionChip,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                        FilterChip(
                            selected = state.enableGamepadSupport,
                            onClick = {
                                viewModel.updateCompatFlags(gamepadSupport = !state.enableGamepadSupport)
                            },
                            shape = CircleShape,
                            border = if (state.enableGamepadSupport) AppleGlass.ActiveBorder else AppleGlass.ThinBorder,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppleGlass.AppleBlue.copy(alpha = 0.22f),
                                selectedLabelColor = AppleGlass.AppleBlue,
                                containerColor = Color(0x14FFFFFF),
                                labelColor = AppleGlass.TextSecondary
                            ),
                            label = {
                                Text(
                                    text = strings.gamepadSupportChip,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                        FilterChip(
                            selected = state.unlockScreenOrientation,
                            onClick = {
                                viewModel.updateCompatFlags(unlockOrientation = !state.unlockScreenOrientation)
                            },
                            shape = CircleShape,
                            border = if (state.unlockScreenOrientation) AppleGlass.ActiveBorder else AppleGlass.ThinBorder,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppleGlass.AppleBlue.copy(alpha = 0.22f),
                                selectedLabelColor = AppleGlass.AppleBlue,
                                containerColor = Color(0x14FFFFFF),
                                labelColor = AppleGlass.TextSecondary
                            ),
                            label = {
                                Text(
                                    text = strings.unlockOrientationChip,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }

                    if (state.selectedStrategy == PatchStrategy.UNIVERSAL_INJECTION) {
                        Text(
                            text = strings.carLauncherCategoryLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = AppleGlass.TextSecondary
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CarAppCategory.entries.forEach { cat ->
                                val isCatSelected = state.selectedCategory == cat
                                Surface(
                                    onClick = { viewModel.updateCategory(cat) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isCatSelected) AppleGlass.AppleBlue.copy(alpha = 0.16f) else Color(0x10FFFFFF),
                                    border = if (isCatSelected) AppleGlass.ActiveBorder else AppleGlass.ThinBorder,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                    ) {
                                        RadioButton(
                                            selected = isCatSelected,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(
                                                selectedColor = AppleGlass.AppleBlue,
                                                unselectedColor = AppleGlass.TextTertiary
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = strings.categoryName(cat),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = AppleGlass.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color(0x20FFFFFF))

                        Surface(
                            onClick = { viewModel.updateUseRootShellForTouch(!state.useRootShellForTouch) },
                            shape = RoundedCornerShape(16.dp),
                            color = if (state.useRootShellForTouch) AppleGlass.AppleBlue.copy(alpha = 0.14f) else Color(0x10FFFFFF),
                            border = if (state.useRootShellForTouch) AppleGlass.ActiveBorder else AppleGlass.ThinBorder,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = strings.touchModeRootLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppleGlass.TextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = strings.touchModeRootDesc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AppleGlass.TextSecondary
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Switch(
                                    checked = state.useRootShellForTouch,
                                    onCheckedChange = viewModel::updateUseRootShellForTouch,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = AppleGlass.AppleGreen,
                                        uncheckedThumbColor = Color(0xFFE0E0E0),
                                        uncheckedTrackColor = Color(0x33FFFFFF)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section 2: Install Mode
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = AppleGlass.GlassCard,
            border = AppleGlass.SpecularBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = strings.section2InstallModeTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AppleGlass.AppleBlue
                )

                InstallMode.entries.forEach { mode ->
                    val selected = state.selectedInstallMode == mode
                    Surface(
                        onClick = { viewModel.updateInstallMode(mode) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) AppleGlass.AppleBlue.copy(alpha = 0.16f) else Color(0x10FFFFFF),
                        border = if (selected) AppleGlass.ActiveBorder else AppleGlass.ThinBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AppleGlass.AppleBlue,
                                    unselectedColor = AppleGlass.TextTertiary
                                )
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = strings.installModeTitle(mode),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppleGlass.TextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = strings.installModeSubtitle(mode),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppleGlass.TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Status banner
        state.statusBannerEntry?.let { bannerEntry ->
            val bannerText = remember(bannerEntry, strings) {
                bannerEntry(strings)
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0x1FFFFFFF),
                border = AppleGlass.SpecularBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = AppleGlass.AppleBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = bannerText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = AppleGlass.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Action Buttons
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val isDirectNoPatch = state.selectedStrategy == PatchStrategy.DIRECT_INSTALL_NO_PATCH
            Button(
                onClick = { viewModel.startPatchAndInstall(installImmediately = true) },
                enabled = !state.isWorking,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppleGlass.AppleBlue,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(vertical = 14.dp, horizontal = 18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = strings.processingButton,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isDirectNoPatch) strings.directInstallNoPatchButton else strings.patchAndInstallButton,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!isDirectNoPatch) {
                Surface(
                    onClick = { viewModel.startPatchAndInstall(installImmediately = false) },
                    enabled = !state.isWorking,
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0x20FFFFFF),
                    border = AppleGlass.ThinBorder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 13.dp, horizontal = 18.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, tint = AppleGlass.AppleBlue, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.patchAndSaveButton,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AppleGlass.TextPrimary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    onClick = viewModel::startDirectInstallOriginalApk,
                    enabled = !state.isWorking,
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0x12FFFFFF),
                    border = AppleGlass.ThinBorder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 18.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = AppleGlass.AppleBlue, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.directInstallNoPatchButton,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AppleGlass.TextPrimary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Saved storage card
        state.lastSavedStoragePath?.let { savedPath ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = AppleGlass.GlassCard,
                border = AppleGlass.SpecularBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = AppleGlass.AppleGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.savedInStorageLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = AppleGlass.TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = savedPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleGlass.TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        if (state.lastPatchedApk != null && !state.isWorking) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    onClick = viewModel::saveLastPatchedApkToDownloads,
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0x18FFFFFF),
                    border = AppleGlass.ThinBorder,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, tint = AppleGlass.AppleBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.saveToDownloadButton,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = AppleGlass.TextPrimary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    onClick = {
                        val pkg = state.lastPatchedPackageName ?: candidate.packageName
                        saveApkLauncher.launch("${pkg}_AA_Patched.apk")
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0x18FFFFFF),
                    border = AppleGlass.ThinBorder,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = AppleGlass.AppleBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.chooseSaveFolderButton,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = AppleGlass.TextPrimary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Surface(
                onClick = viewModel::installLastPatchedApk,
                shape = RoundedCornerShape(14.dp),
                color = Color(0x18FFFFFF),
                border = AppleGlass.ThinBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = strings.reinstallLastPatchedFormat(state.lastPatchedApk.name),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AppleGlass.AppleBlue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Apple Dark Terminal Console Box
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF0C0C10),
            border = AppleGlass.ThinBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFFF5F56)))
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFFFBD2E)))
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF27C93F)))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = strings.consoleTitle,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = AppleGlass.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        onClick = { viewModel.clearLogs() },
                        shape = CircleShape,
                        color = Color(0x18FFFFFF),
                        border = AppleGlass.ThinBorder
                    ) {
                        Text(
                            text = strings.clearLogsButton,
                            style = MaterialTheme.typography.labelSmall,
                            color = AppleGlass.TextSecondary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF060609),
                    border = BorderStroke(1.dp, Color(0x18FFFFFF)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.padding(12.dp)) {
                        if (state.logEntries.isEmpty()) {
                            Text(
                                text = strings.readyToExecute,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = AppleGlass.TextTertiary
                            )
                        } else {
                            val logs = remember(state.logEntries, strings) {
                                state.logEntries.map { entry ->
                                    "[${entry.time}] ${entry.render(strings)}"
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                logs.forEach { logLine ->
                                    Text(
                                        text = logLine,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFD4D4D8),
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentDiagnosticTab(
    env: EnvironmentStatus?,
    strings: AppStrings,
    bottomPadding: Dp = 16.dp,
    onRequestShizuku: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomPadding + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = strings.envHeaderTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = AppleGlass.TextPrimary
        )

        if (env == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AppleGlass.AppleBlue)
            }
            return@Column
        }

        DiagRow(
            title = strings.diagAndroidOsTitle,
            value = "Android ${env.androidReleaseName} (API ${env.androidVersion})",
            isOk = true
        )

        DiagRow(
            title = strings.diagGearheadTitle,
            value = if (env.isAndroidAutoInstalled) {
                strings.diagGearheadInstalledFormat(env.androidAutoVersion)
            } else {
                strings.diagGearheadNotInstalled
            },
            isOk = env.isAndroidAutoInstalled
        )

        DiagRow(
            title = strings.diagShizukuTitle,
            value = when {
                env.isShizukuPermissionGranted -> strings.diagShizukuGranted
                env.isShizukuAvailable -> strings.diagShizukuRunningNotGranted
                else -> strings.diagShizukuNotRunning
            },
            isOk = env.isShizukuPermissionGranted,
            actionLabel = if (env.isShizukuAvailable && !env.isShizukuPermissionGranted) strings.diagGrantShizukuButton else null,
            onAction = onRequestShizuku
        )

        DiagRow(
            title = strings.diagRootTitle,
            value = if (env.isRootAvailable) strings.diagRootAvailable else strings.diagRootNotDetected,
            isOk = env.isRootAvailable
        )

        DiagRow(
            title = strings.diagXposedTitle,
            value = if (env.isXposedModuleActive) strings.diagXposedActive else strings.diagXposedInactive,
            isOk = env.isXposedModuleActive
        )

        DiagRow(
            title = strings.drivingHookDiagTitle,
            value = if (env.isXposedModuleActive) strings.drivingHookDiagActive else strings.drivingHookDiagManifestOnly,
            isOk = true
        )

        // Apple Liquid Glass Info / Guide Card
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = AppleGlass.GlassCard,
            border = AppleGlass.SpecularBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = AppleGlass.AppleBlue.copy(alpha = 0.20f),
                        border = BorderStroke(1.dp, AppleGlass.AppleBlue.copy(alpha = 0.5f)),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = AppleGlass.AppleBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = strings.unknownSourcesGuideTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = AppleGlass.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = strings.unknownSourcesGuideSteps,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppleGlass.TextSecondary
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_MAIN).apply {
                                    setClassName(
                                        "com.google.android.projection.gearhead",
                                        "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
                                    )
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                val fallback = context.packageManager.getLaunchIntentForPackage(
                                    "com.google.android.projection.gearhead"
                                )
                                if (fallback != null) context.startActivity(fallback)
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppleGlass.AppleBlue,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.openAASettingsButton,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Surface(
                        onClick = onRefresh,
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0x18FFFFFF),
                        border = AppleGlass.ThinBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = AppleGlass.AppleBlue, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.recheckButton,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = AppleGlass.TextPrimary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagRow(
    title: String,
    value: String,
    isOk: Boolean,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = AppleGlass.GlassCard,
        border = AppleGlass.SpecularBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isOk) AppleGlass.AppleGreenGlass else AppleGlass.AppleRedGlass,
                    border = if (isOk) AppleGlass.GreenBorder else BorderStroke(1.dp, AppleGlass.AppleRed.copy(alpha = 0.5f)),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isOk) AppleGlass.AppleGreen else AppleGlass.AppleRed,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = AppleGlass.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleGlass.TextSecondary
                    )
                }
            }

            if (actionLabel != null && onAction != null) {
                Surface(
                    onClick = onAction,
                    shape = RoundedCornerShape(12.dp),
                    color = AppleGlass.AppleBlue.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, AppleGlass.AppleBlue.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AppleGlass.AppleBlue,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
            }
        }
    }
}
