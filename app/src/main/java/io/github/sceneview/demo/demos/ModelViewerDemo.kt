package io.github.sceneview.demo.demos

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.sceneview.SceneView
import io.github.sceneview.createDefaultCameraManipulator
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.viewer.AnimationBar
import io.github.sceneview.demo.ui.viewer.EnvironmentSheet
import io.github.sceneview.demo.ui.viewer.ViewerEnvironment
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * Single-mode GLB Viewer.
 *
 * Flow:
 *
 * Open APK
 *     ↓
 * Empty viewer
 *     ↓
 * Open GLB
 *     ↓
 * Android file picker
 *     ↓
 * User selects a GLB
 *     ↓
 * Selected GLB is displayed
 *
 * Features:
 * - SceneView rendering
 * - Rotate / Zoom / Pan
 * - Recenter
 * - Lighting / Environment controls
 * - Animation playback
 * - Native Android file picker
 *
 * No bundled model is required.
 */
@Composable
fun ModelViewerDemo(onBack: () -> Unit) {
    val context = LocalContext.current

    // -------------------------------------------------------------------------
    // SceneView engine / loaders
    // -------------------------------------------------------------------------

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // -------------------------------------------------------------------------
    // Native Android GLB picker
    // -------------------------------------------------------------------------

    var selectedUri by remember {
        mutableStateOf<Uri?>(null)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        selectedUri = uri
    }

    // -------------------------------------------------------------------------
    // Selected GLB model
    //
    // No bundled/demo model is used.
    // The viewer remains empty until the user selects a GLB.
    // key(selectedUri) creates a fresh loading scope whenever the selected
    // file changes.
    // -------------------------------------------------------------------------

    val activeModelInstance = selectedUri?.let { uri ->
        key(uri) {
            rememberModelInstance(
                modelLoader = modelLoader,
                uri = uri
            )
        }
    }

    // -------------------------------------------------------------------------
    // First-frame state
    // -------------------------------------------------------------------------

    val firstFrame = rememberFirstFrameState()

    // -------------------------------------------------------------------------
    // Animation
    // -------------------------------------------------------------------------

    val animationNames = remember(activeModelInstance) {
        val animator = activeModelInstance?.animator
            ?: return@remember emptyList()

        (0 until animator.animationCount).map { index ->
            animator
                .getAnimationName(index)
                .takeIf { it.isNotBlank() }
                ?: "Clip ${index + 1}"
        }
    }

    var animationBarOpen by remember {
        mutableStateOf(false)
    }

    var animationPlaying by remember {
        mutableStateOf(true)
    }

    var selectedAnimation by remember {
        mutableStateOf(0)
    }

    var animationProgress by remember {
        mutableStateOf(0f)
    }

    LaunchedEffect(
        activeModelInstance,
        selectedAnimation,
        animationPlaying
    ) {
        val animator = activeModelInstance?.animator
            ?: return@LaunchedEffect

        if (
            selectedAnimation !in
            0 until animator.animationCount
        ) {
            return@LaunchedEffect
        }

        val duration = animator
            .getAnimationDuration(selectedAnimation)
            .takeIf { it > 0f }
            ?: return@LaunchedEffect

        var startTime = 0L

        while (animationPlaying) {
            withFrameNanos { now ->
                if (startTime == 0L) {
                    startTime =
                        now -
                            (
                                animationProgress *
                                    duration *
                                    1_000_000_000L
                            ).toLong()
                }

                val seconds =
                    (now - startTime) / 1_000_000_000f

                animationProgress =
                    (seconds % duration) / duration

                animator.applyAnimation(
                    selectedAnimation,
                    animationProgress * duration
                )

                animator.updateBoneMatrices()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Environment / lighting
    // -------------------------------------------------------------------------

    val viewerEnvironments = remember {
        listOf(
            ViewerEnvironment(
                "environments/chinese_garden_2k.hdr",
                "Chinese Garden"
            ),
            ViewerEnvironment(
                "environments/studio_2k.hdr",
                "Studio"
            ),
            ViewerEnvironment(
                "environments/sunset_2k.hdr",
                "Sunset"
            ),
            ViewerEnvironment(
                "environments/outdoor_cloudy_2k.hdr",
                "Outdoor Cloudy"
            )
        )
    }

    var requestedEnvironment by remember {
        mutableStateOf(viewerEnvironments.first())
    }

    var iblIntensity by remember {
        mutableStateOf(1f)
    }

    var showEnvironment by remember {
        mutableStateOf(false)
    }

    var environmentSheetOpen by remember {
        mutableStateOf(false)
    }

    val fallbackEnvironment =
        rememberEnvironment(environmentLoader)

    val loadedEnvironment = key(
        requestedEnvironment.assetPath,
        showEnvironment
    ) {
        rememberHDREnvironment(
            environmentLoader = environmentLoader,
            assetFileLocation = requestedEnvironment.assetPath,
            createSkybox = showEnvironment
        )
    }

    val viewerEnvironment =
        loadedEnvironment ?: fallbackEnvironment

    LaunchedEffect(
        viewerEnvironment,
        iblIntensity
    ) {
        viewerEnvironment
            .indirectLight
            ?.intensity = 30_000f * iblIntensity
    }

    // -------------------------------------------------------------------------
    // Camera
    // -------------------------------------------------------------------------

    var recenterGeneration by remember {
        mutableStateOf(0)
    }

    val cameraManipulator = remember(
        recenterGeneration
    ) {
        createDefaultCameraManipulator()
    }

    // -------------------------------------------------------------------------
    // Back handling for sheets / animation controls
    // -------------------------------------------------------------------------

    BackHandler(
        enabled = environmentSheetOpen || animationBarOpen
    ) {
        environmentSheetOpen = false
        animationBarOpen = false
    }

    // -------------------------------------------------------------------------
    // Clean up camera state when leaving the demo
    // -------------------------------------------------------------------------

    DisposableEffect(Unit) {
        onDispose {
            recenterGeneration++
        }
    }

    // -------------------------------------------------------------------------
    // Main UI
    // -------------------------------------------------------------------------

    DemoScaffold(
        title = if (selectedUri != null) {
            "Local GLB"
        } else {
            stringResource(
                R.string.demo_model_viewer_screen_title
            )
        },

        onBack = {
            if (selectedUri != null) {
                selectedUri = null
                animationBarOpen = false
                animationProgress = 0f
                selectedAnimation = 0
            } else {
                onBack()
            }
        },

        firstFrameRendered = firstFrame.rendered,

        dock = buildList {
            add(
                DockItem(
                    Icons.Filled.FolderOpen,
                    "Open GLB",
                    {
                        filePickerLauncher.launch("*/*")
                    }
                )
            )

            add(
                DockItem(
                    Icons.Outlined.WbSunny,
                    "Lighting",
                    {
                        environmentSheetOpen = true
                    }
                )
            )

            if (animationNames.isNotEmpty()) {
                add(
                    DockItem(
                        Icons.Outlined.Animation,
                        "Animate",
                        {
                            animationBarOpen =
                                !animationBarOpen
                        },
                        selected = animationBarOpen
                    )
                )
            }

            add(
                DockItem(
                    Icons.Outlined.RestartAlt,
                    "Recenter",
                    {
                        recenterGeneration++
                    }
                )
            )
        },

        bottomOverlay = {
            AnimatedVisibility(
                visible =
                    animationBarOpen &&
                        animationNames.isNotEmpty(),

                enter =
                    fadeIn(
                        SceneViewTokens.Motion.fade()
                    ) +
                        expandVertically(
                            SceneViewTokens.Motion.spring(),
                            expandFrom = Alignment.Bottom
                        ),

                exit =
                    fadeOut(
                        SceneViewTokens.Motion.fade()
                    ) +
                        shrinkVertically(
                            SceneViewTokens.Motion.spring(),
                            shrinkTowards = Alignment.Bottom
                        )
            ) {
                AnimationBar(
                    animationNames = animationNames,
                    selectedAnimation = selectedAnimation,
                    animationPlaying = animationPlaying,
                    animationProgress = animationProgress,

                    onPlayingChange = {
                        animationPlaying = it
                    },

                    onClipChange = {
                        selectedAnimation = it
                        animationProgress = 0f
                    },

                    onProgressChange = { progress ->
                        animationProgress = progress

                        activeModelInstance
                            ?.animator
                            ?.takeIf {
                                selectedAnimation in
                                    0 until it.animationCount
                            }
                            ?.let { animator ->
                                animator.applyAnimation(
                                    selectedAnimation,
                                    progress *
                                        animator.getAnimationDuration(
                                            selectedAnimation
                                        )
                                )

                                animator.updateBoneMatrices()
                            }
                    }
                )
            }
        },

        chromeToggleOnTap = true
    ) {
        // ---------------------------------------------------------------------
        // SceneView
        // ---------------------------------------------------------------------

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            SceneView(
                modifier = Modifier.fillMaxSize(),

                onFrame = firstFrame.onFrame,

                engine = engine,

                modelLoader = modelLoader,

                environmentLoader =
                    environmentLoader,

                environment =
                    viewerEnvironment,

                cameraManipulator =
                    cameraManipulator
            ) {
                activeModelInstance?.let { instance ->
                    io.github.sceneview.node.ModelNode(
                        modelInstance = instance,
                        autoAnimate = false
                    )
                }
            }

            LoadingScrim(
                loading =
                    selectedUri != null &&
                        activeModelInstance == null,

                label =
                    stringResource(
                        R.string.demo_model_viewer_loading
                    )
            )
        }
    }

    // -------------------------------------------------------------------------
    // Lighting sheet
    // -------------------------------------------------------------------------

    if (environmentSheetOpen) {
        EnvironmentSheet(
            environments = viewerEnvironments,

            selectedPath =
                requestedEnvironment.assetPath,

            intensity = iblIntensity,

            showEnvironment =
                showEnvironment,

            onSelect = {
                requestedEnvironment = it
            },

            onIntensity = {
                iblIntensity = it
            },

            onShowEnvironment = {
                showEnvironment = it
            },

            onReset = {
                requestedEnvironment =
                    viewerEnvironments.first()

                iblIntensity = 1f

                showEnvironment = false
            },

            onDismiss = {
                environmentSheetOpen = false
            }
        )
    }
}
