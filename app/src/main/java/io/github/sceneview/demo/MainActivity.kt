package io.github.sceneview.demo

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavType
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import io.github.sceneview.demo.fragments.GeneratedDemos
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.rememberMotionEnabled
import io.github.sceneview.demo.ui.RootScreen
import io.github.sceneview.demo.feedback.BugReportSheet
import io.github.sceneview.demo.feedback.CurrentRootScreen
import io.github.sceneview.demo.feedback.FeedbackOpenRequest
import io.github.sceneview.demo.feedback.PendingBugReport
import io.github.sceneview.demo.feedback.ReportScreen
import io.github.sceneview.demo.feedback.captureBugReportInfo
import io.github.sceneview.demo.feedback.captureBugReportScreenshot
import io.github.sceneview.demo.feedback.sweepStaleFeedbackMedia

class MainActivity : ComponentActivity() {

    /**
     * A model file another app asked this one to open through `ACTION_VIEW`, or an `ACTION_SEND`
     * from a share sheet.
     *
     * Staging copies the bytes into the cache off the main thread, so this arrives *after* the
     * first composition; the UI observes it and navigates to the Model Viewer when it lands.
     * Nulled by [consumePendingOpenedModel] once navigated, so a configuration change does not
     * re-open the same file.
     */
    private val pendingOpenedModel = MutableStateFlow<OpenedModel?>(null)
    val pendingOpenedModelFlow: StateFlow<OpenedModel?> get() = pendingOpenedModel.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Clean up any feedback recording stranded in the cache by a prior run.
        sweepStaleFeedbackMedia(this)
        
        // "Open with SceneView": a supported model file handed over by another app.
        stageOpenedModel(intent)
        
        setContent {
            SceneViewDemoTheme {
                SceneViewDemoApp(activity = this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        stageOpenedModel(intent)
    }

    /**
     * Copies a model handed over by another app into the cache, then publishes it for the UI.
     *
     * Off the main thread, because the file is read and rewritten byte for byte and a shared print
     * can be tens of megabytes. The intent is inspected synchronously; only the copy is deferred.
     */
    private fun stageOpenedModel(intent: Intent?) {
        val uri = OpenedModelIntent.modelUri(intent) ?: return
        val mimeType = intent?.type
        lifecycleScope.launch {
            val opened = withContext(Dispatchers.IO) {
                OpenedModelIntent.stage(this@MainActivity, uri, mimeType)
            }
            if (opened == null) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.open_model_failed),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            withContext(Dispatchers.IO) {
                OpenedModelIntent.sweep(this@MainActivity, opened.file())
            }
            DemoSettings.openedModelSizeMeters = null
            pendingOpenedModel.value = opened
        }
    }

    fun consumePendingOpenedModel() {
        pendingOpenedModel.value = null
    }
}

@Composable
fun SceneViewDemoApp(activity: MainActivity? = null) {
    val navController = rememberNavController()
    val motionEnabled = rememberMotionEnabled()
    
    // "Open with SceneView" (#3482). Staging is async, so this always arrives after the first
    // composition. The Model Viewer is the target because it is the app's flagship viewer.
    val openedModel by (activity?.pendingOpenedModelFlow?.collectAsState()
        ?: remember { MutableStateFlow<OpenedModel?>(null) }.collectAsState())
        
    LaunchedEffect(openedModel) {
        val opened = openedModel ?: return@LaunchedEffect
        DemoSettings.openedModel = opened
        navController.navigate("demo/model-viewer") {
            // One viewer on the stack however many files are opened in a row.
            popUpTo("demo/model-viewer") { inclusive = true }
        }
        activity?.consumePendingOpenedModel()
    }

    CompositionLocalProvider(LocalMotionEnabled provides motionEnabled) {
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = "demo/model-viewer",
                enterTransition = {
                    slideInHorizontally(navMotion(motionEnabled)) { it / 6 } +
                        fadeIn(navMotion(motionEnabled)) +
                        scaleIn(navMotion(motionEnabled), initialScale = 0.98f)
                },
                exitTransition = {
                    slideOutHorizontally(navMotion(motionEnabled)) { -it / 6 } +
                        fadeOut(navMotion(motionEnabled)) +
                        scaleOut(navMotion(motionEnabled), targetScale = 0.98f)
                },
                popEnterTransition = {
                    slideInHorizontally(navMotion(motionEnabled)) { -it / 6 } +
                        fadeIn(navMotion(motionEnabled)) +
                        scaleIn(navMotion(motionEnabled), initialScale = 0.98f)
                },
                popExitTransition = {
                    slideOutHorizontally(navMotion(motionEnabled)) { it / 6 } +
                        fadeOut(navMotion(motionEnabled)) +
                        scaleOut(navMotion(motionEnabled), targetScale = 0.98f)
                }
            ) {
                composable("list") {
                    RootScreen(onDemoClick = { id -> navController.navigate("demo/$id") })
                }
                composable(
                    route = "demo/{id}?model={model}",
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("model") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("id") ?: return@composable
                    DemoSettings.requestedModel = backStackEntry.arguments?.getString("model")
                    val onBack: () -> Unit = { navController.popBackStack() }
                    DemoRouter(id = id, onBack = onBack)
                }
            }

            // Bug-report entry points
            val context = LocalContext.current
            val currentEntry by navController.currentBackStackEntryAsState()

            var bugReport by remember { mutableStateOf<PendingBugReport?>(null) }
            val reportScope = rememberCoroutineScope()

            fun openBugReport() {
                if (bugReport != null) return
                val entry = currentEntry
                val screen = ReportScreen(
                    demoId = entry?.arguments?.getString("id"),
                    rootScreen = CurrentRootScreen.label,
                    route = entry?.destination?.route,
                )
                reportScope.launch {
                    val screenshot = activity?.let { captureBugReportScreenshot(it) }
                    bugReport = PendingBugReport(
                        info = captureBugReportInfo(context, screen),
                        screenshot = screenshot,
                    )
                }
            }

            val feedbackRequested by FeedbackOpenRequest.requested.collectAsState()
            LaunchedEffect(feedbackRequested) {
                if (feedbackRequested) {
                    FeedbackOpenRequest.consume()
                    openBugReport()
                }
            }

            bugReport?.let { report ->
                BugReportSheet(
                    report = report,
                    onDismiss = { bugReport = null },
                )
            }
        }
    }
}

/**
 * Routes a demo [id] to the corresponding composable.
 */
@Composable
fun DemoRouter(id: String, onBack: () -> Unit) {
    val matched = GeneratedDemos.Screen(id = id, onBack = onBack)
    if (!matched) {
        check(!BuildConfig.DEBUG) {
            "DemoRouter has no fragment for demo id '$id'. Every ALL_DEMOS entry " +
                "must be backed by a *Fragment.kt under io.github.sceneview.demo.fragments."
        }
        PlaceholderDemo(id = id, onBack = onBack)
    }
}

private fun <T> navMotion(
    enabled: Boolean,
): androidx.compose.animation.core.FiniteAnimationSpec<T> = if (enabled) {
    androidx.compose.animation.core.tween(
        durationMillis = io.github.sceneview.demo.theme.SceneViewTokens.Duration.mediumMillis,
        easing = io.github.sceneview.demo.theme.SceneViewTokens.Ease.expressive,
    )
} else {
    androidx.compose.animation.core.snap()
}

@Composable
fun PlaceholderDemo(id: String, onBack: () -> Unit) {
    val entry = ALL_DEMOS.find { it.id == id }
    DemoScaffold(
        title = entry?.titleRes?.let { stringResource(it) } ?: id,
        onBack = onBack
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Text(
                text = stringResource(R.string.demo_coming_soon),
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
