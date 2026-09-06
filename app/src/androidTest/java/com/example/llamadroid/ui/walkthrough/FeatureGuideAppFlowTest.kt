package com.example.llamadroid.ui.walkthrough

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalFullyDrawnReporterOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.R
import com.example.llamadroid.data.WalkthroughPreferences
import com.example.llamadroid.ui.LlamaApp
import com.example.llamadroid.ui.navigation.ExternalRouteResolution
import com.example.llamadroid.ui.navigation.ExternalRouteResolver
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Connected route and escape-flow coverage for the detailed feature guides.
 *
 * The test is deliberately opt-in. It must run in the isolated secondary Android user because
 * LlamaApp owns real repositories and navigation state. It never clicks a feature target: model
 * downloads, inference, feed/collection, purchases, imports, and server submissions remain
 * manual actions. A missing target or parameterized route is recorded as BLOCKED rather than
 * being converted into a fake arrival.
 *
 * Example invocation after installing the debug and test APKs. The connected Gradle task uses
 * the adb server's current user (normally user 0), so it cannot satisfy this test's user-10
 * isolation guard just by passing `secondary_android_user_id=10`.
 *
 * ```bash
 * JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain --no-daemon
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell cmd package install-existing --user 10 com.manuxd32.aidoomsdaytoolbox
 * adb shell cmd package install-existing --user 10 com.manuxd32.aidoomsdaytoolbox.test
 * adb shell am switch-user 10
 * adb shell am instrument --user 10 -w \
 *   -e isolated_feature_qa true \
 *   -e secondary_android_user_id 10 \
 *   -e feature_guide_locale en \
 *   -e feature_guide_viewport standard \
 *   -e class com.example.llamadroid.ui.walkthrough.FeatureGuideAppFlowTest \
 *   com.manuxd32.aidoomsdaytoolbox.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * Use `feature_guide_locale=es`, `feature_guide_viewport=compact`, `phone`, `tablet`, `both`, or a
 * comma-separated list for review profiles. Use `feature_recipe_ids=models.saved_links,tama.livestock`
 * for triage. Add `feature_guide_capture=true` to write successful route and coach fragments plus
 * `manifest.json` to the app external-cache directory. Captures use the real tagged Compose nodes;
 * no synthetic tablet frame or unreviewed preview mapping is created. The model-sources recipes
 * enter the internal destination through a real model-manager shortcut because that destination is
 * intentionally outside the external route resolver.
 *
 * Screenshot review command for the two real-device profiles:
 *
 * ```bash
 * JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain --no-daemon
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell cmd package install-existing --user 10 com.manuxd32.aidoomsdaytoolbox
 * adb shell cmd package install-existing --user 10 com.manuxd32.aidoomsdaytoolbox.test
 * adb shell am switch-user 10
 * adb shell am instrument --user 10 -w \
 *   -e isolated_feature_qa true \
 *   -e secondary_android_user_id 10 \
 *   -e feature_guide_capture true \
 *   -e feature_guide_locale both \
 *   -e feature_guide_viewport phone,tablet \
 *   -e feature_recipe_ids models.llm,conversations.native_management,distributed.roles,fastsd.gallery \
 *   -e class com.example.llamadroid.ui.walkthrough.FeatureGuideAppFlowTest \
 *   com.manuxd32.aidoomsdaytoolbox.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
class FeatureGuideAppFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var settingsPrefs: SharedPreferences
    private lateinit var agentPrefs: SharedPreferences
    private lateinit var savedSettings: Map<String, Any?>
    private lateinit var savedAgent: Map<String, Any?>

    private val pendingNavigationRoute = mutableStateOf<ExternalRouteResolution>(
        ExternalRouteResolution.NoRoute
    )
    private val hostRequest = mutableStateOf(HostRequest(0L, "dashboard"))
    private val viewport = mutableStateOf(FLOW_VIEWPORTS.getValue("standard"))
    private var contentInstalled = false
    private var requestSequence = 0L
    private val blocked = mutableListOf<BlockedRecipe>()
    private val captures = mutableListOf<CaptureRecord>()
    private var captureDirectory: File? = null
    private var escapePassCount = 0
    private var targetPassCount = 0
    private var targetBlockedObservationCount = 0
    private var targetBlockedFlowCount = 0
    private var otherBlockedObservationCount = 0

    @Before
    fun snapshotFixturePreferences() {
        requireIsolatedInvocation()
        settingsPrefs = rule.activity.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        agentPrefs = rule.activity.getSharedPreferences(AGENT_PREFS, Context.MODE_PRIVATE)
        savedSettings = capture(settingsPrefs, SETTINGS_KEYS)
        savedAgent = capture(agentPrefs, AGENT_KEYS)

        val editor = settingsPrefs.edit()
            .putBoolean(HAS_COMPLETED_WELCOME, true)
            .putBoolean(AUTOMATIC_ELIGIBLE, false)
        FEATURE_RECIPE_IDS.forEach { recipeId ->
            editor.remove(progressKey(recipeId)).remove(completedKey(recipeId))
        }
        check(editor.commit()) { "Could not prepare isolated feature-guide preferences" }
        check(agentPrefs.edit().putBoolean(FIRST_RUN_SHOWN, true).commit()) {
            "Could not suppress the agent welcome dialog in the isolated fixture"
        }
    }

    @After
    fun restoreFixturePreferences() {
        if (::settingsPrefs.isInitialized && captureScreenshots()) {
            prepareCaptureDirectory()
            writeCaptureManifest()
        }
        if (::settingsPrefs.isInitialized && ::savedSettings.isInitialized) {
            restore(settingsPrefs, savedSettings)
        }
        if (::agentPrefs.isInitialized && ::savedAgent.isInitialized) {
            restore(agentPrefs, savedAgent)
        }
    }

    @Test
    fun everyFeatureRecipeFollowsRealRouteAndEscapeFlow() {
        val args = InstrumentationRegistry.getArguments()
        val profiles = requestedProfiles(args)
        val recipes = requestedRecipes(args)
        ensureHost()

        profiles.forEach { profile ->
            recipes.forEach { recipe ->
                verifyRecipe(recipe, profile)
            }
        }

        Log.i(
            TAG,
            "isolated feature-guide flow complete profiles=${profiles.joinToString()} " +
                "attempts=${profiles.size * recipes.size} recipes=${recipes.size} " +
                "escapePasses=$escapePassCount targetPasses=$targetPassCount " +
                "blockedObservations=${blocked.size} " +
                "targetBlockedObservations=$targetBlockedObservationCount " +
                "targetBlockedFlows=$targetBlockedFlowCount " +
                "otherBlockedObservations=$otherBlockedObservationCount " +
                blocked.joinToString(separator = " | ")
        )
        check(recipes.isNotEmpty()) { "No feature-guide recipes selected" }
        check(otherBlockedObservationCount == 0) {
            "Guide navigation or controls were unavailable: $blocked"
        }
    }

    private fun requireIsolatedInvocation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Set isolated_feature_qa=true to run against the real LlamaApp host",
            args.getString(ARG_ISOLATED) == "true"
        )
        assumeTrue(
            "Run this test only in secondary Android user 10",
            args.getString(ARG_SECONDARY_USER) == SECONDARY_USER_ID
        )
        assumeTrue(
            "The connected test must run under Android user 10, not only receive the argument",
            android.os.Process.myUid() / USER_ID_RANGE == SECONDARY_USER_ID.toInt()
        )
    }

    private fun requestedProfiles(args: android.os.Bundle): List<FlowViewport> {
        val locales = parseSelection(
            args.getString(ARG_LOCALE) ?: "en",
            allowed = setOf("en", "es"),
            label = ARG_LOCALE
        )
        val viewportNames = parseSelection(
            args.getString(ARG_VIEWPORT) ?: "standard",
            allowed = setOf("compact", "standard", "phone", "tablet"),
            label = ARG_VIEWPORT
        )
        return viewportNames.flatMap { name ->
            locales.map { locale ->
                FLOW_VIEWPORTS.getValue(name).copy(language = locale)
            }
        }
    }

    private fun parseSelection(raw: String?, allowed: Set<String>, label: String): List<String> {
        val values = raw.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        val expanded = if (values.contains("both")) allowed.toList() else values
        require(expanded.isNotEmpty() && expanded.all { it in allowed }) {
            "$label must contain ${allowed.joinToString()} or both; got $raw"
        }
        return expanded.distinct()
    }

    private fun requestedRecipes(args: android.os.Bundle): List<FeatureRecipe> {
        val all = FeatureGuideCatalog.guides.flatMap { it.recipes }
        val raw = args.getString(ARG_RECIPE_IDS)?.trim().orEmpty()
        if (raw.isEmpty()) return all
        val requested = raw.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        val byId = all.associateBy { it.id }
        val missing = requested - byId.keys
        require(missing.isEmpty()) { "Unknown feature_recipe_ids: ${missing.sorted().joinToString()}" }
        return requested.sorted().map { byId.getValue(it) }
    }

    /** The real app host is installed exactly once; route/profile changes are state requests. */
    private fun ensureHost() {
        if (contentInstalled) return
        contentInstalled = true
        rule.setContent {
            val request = hostRequest.value
            TestViewport(viewport.value) {
                LlamaDroidTheme(darkTheme = false, dynamicColor = false) {
                    key(request.sequence) {
                        LlamaApp(
                            pendingNavigationRoute = pendingNavigationRoute.value,
                            onNavigationHandled = {
                                pendingNavigationRoute.value = ExternalRouteResolution.NoRoute
                            },
                            allowDailySupportPrompt = false,
                            allowAutomaticWalkthrough = false
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun verifyRecipe(recipe: FeatureRecipe, profile: FlowViewport) {
        val guide = FeatureGuideCatalog.guides.first { it.recipes.any { candidate -> candidate.id == recipe.id } }
        val firstStep = recipe.steps.firstOrNull()
        val requestedRoute = firstStep?.route ?: guide.route
        val entersModelSourcesInternally = routeBase(requestedRoute) == MODEL_SOURCES_ROUTE
        val route = when {
            requestedRoute.isBlank() -> {
                recordBlocked(recipe, profile, "blank route; no synthetic arrival")
                return
            }
            '{' in requestedRoute || '}' in requestedRoute -> {
                recordBlocked(recipe, profile, "parameterized route requires a real fixture ID")
                return
            }
            entersModelSourcesInternally -> ExternalRouteResolution.Navigate(requestedRoute)
            else -> ExternalRouteResolver.resolve(requestedRoute)
        }
        val resolved = route as? ExternalRouteResolution.Navigate
        if (resolved == null) {
            recordBlocked(recipe, profile, "route rejected by ExternalRouteResolver: $requestedRoute")
            return
        }

        resetRecipe(recipe)
        val expectedBase = routeBase(resolved.route)
        val arrived = if (entersModelSourcesInternally) {
            navigateToModelSourcesFromManager(recipe, profile)
        } else {
            navigateTo(resolved.route, profile)
        }
        if (!arrived) {
            val reason = if (entersModelSourcesInternally) {
                "real model manager shortcut did not expose studio_route_$expectedBase"
            } else {
                "real NavHost did not expose studio_route_$expectedBase"
            }
            recordBlocked(recipe, profile, reason)
            return
        }
        dismissSafePrerequisiteDialogs(profile)

        if (!openFeatureChooser(guide, expectedBase, profile)) {
            recordBlocked(recipe, profile, "real FeatureGuideAction/chooser was unavailable")
            return
        }
        if (!clickRecipeAction(recipe, R.string.tour_start, profile)) {
            recordBlocked(recipe, profile, "recipe card did not expose its Start action")
            return
        }
        if (!awaitTag("tour_coach")) {
            recordBlocked(recipe, profile, "recipe Start did not open the real coach")
            return
        }
        if (!awaitTag("tour_step_${firstStep?.id}")) {
            recordBlocked(recipe, profile, "first recipe step was not rendered")
            return
        }

        var targetBlocked = false
        if (!ensureExpectedRoute(expectedBase)) {
            recordBlocked(recipe, profile, "coach could not reach the real route $expectedBase")
            closeCoachIfPresent()
            return
        }
        captureSuccessfulRoute(recipe, profile, expectedBase)
        targetBlocked = inspectStepAvailability(recipe, firstStep, profile) || targetBlocked

        // X must persist the current step without completing the recipe.
        closeCoachIfPresent()
        val sessionId = FeatureGuideCatalog.sessionId(recipe.id)
        check(WalkthroughPreferences(settingsPrefs).progress(sessionId) == firstStep?.id) {
            "${recipe.id}: close did not persist the current recipe step"
        }
        check(!WalkthroughPreferences(settingsPrefs).isCompleted(sessionId)) {
            "${recipe.id}: close unexpectedly completed the recipe"
        }

        // Resume and Replay are both manual chooser actions. The chooser itself must remain
        // attached to the current route; no screen data is created or changed by this test.
        if (!openFeatureChooser(guide, expectedBase, profile) ||
            !clickRecipeAction(recipe, R.string.tour_resume, profile) ||
            !awaitTag("tour_step_${firstStep?.id}") || !ensureExpectedRoute(expectedBase)) {
            recordBlocked(recipe, profile, "Resume action was unavailable after X")
            closeCoachIfPresent()
            return
        }
        closeCoachIfPresent()
        if (!openFeatureChooser(guide, expectedBase, profile) ||
            !clickRecipeAction(recipe, R.string.tour_replay, profile) ||
            !awaitTag("tour_step_${firstStep?.id}")) {
            recordBlocked(recipe, profile, "Replay action was unavailable after Resume")
            closeCoachIfPresent()
            return
        }
        if (!ensureExpectedRoute(expectedBase)) {
            recordBlocked(recipe, profile, "Replay did not retain the expected live route")
            closeCoachIfPresent()
            return
        }

        // Walk every step using the coach controls. Event steps are advanced with Skip; this
        // intentionally observes the unavailable/manual-only path instead of clicking a real
        // download, inference, feed, collection, purchase, import, or server action.
        recipe.steps.forEachIndexed { index, step ->
            if (!awaitTag("tour_step_${step.id}")) {
                recordBlocked(recipe, profile, "step ${step.id} was not rendered")
                return@forEachIndexed
            }
            if (!awaitTag("studio_route_$expectedBase")) {
                recordBlocked(recipe, profile, "route changed unexpectedly before ${step.id}")
                return@forEachIndexed
            }
            if (step.targetId != null) {
                targetBlocked = inspectStepAvailability(recipe, step, profile) || targetBlocked
            }
            if (step.eventId != null || targetBlocked && index == 0) assertSkipLabel(profile)

            if (index == 1) {
                // Previous must return to the first step before Next moves forward again.
                if (awaitTag("tour_previous")) {
                    onTag("tour_previous").performClick()
                    awaitTag("tour_step_${recipe.steps.first().id}")
                    onTag("tour_next").performClick()
                    awaitTag("tour_step_${step.id}")
                } else {
                    recordBlocked(recipe, profile, "Previous control was unavailable at step ${step.id}")
                }
            }
            onTag("tour_next").performClick()
            if (index < recipe.steps.lastIndex) {
                awaitTag("tour_step_${recipe.steps[index + 1].id}")
            }
        }
        awaitGone("tour_coach")
        check(WalkthroughPreferences(settingsPrefs).isCompleted(sessionId)) {
            "${recipe.id}: Next/Skip sequence did not complete the recipe"
        }
        escapePassCount++
        if (targetBlocked) {
            // Keep this outcome explicit in the test log; a missing fixture never becomes a
            // successful target arrival merely because the explanatory steps were navigable.
            targetBlockedFlowCount++
            Log.w(
                TAG,
                "BLOCKED_TARGET_FLOW ${recipe.id} locale=${profile.language} " +
                    "viewport=${profile.name}: steps used Skip for unavailable targets"
            )
        }
    }

    private fun resetRecipe(recipe: FeatureRecipe) {
        closeCoachIfPresent()
        WalkthroughPreferences(settingsPrefs).reset(FeatureGuideCatalog.sessionId(recipe.id))
    }

    /** Capture only after the real NavHost and coach have both reached a verified route. */
    private fun captureSuccessfulRoute(
        recipe: FeatureRecipe,
        profile: FlowViewport,
        expectedBase: String
    ) {
        if (!captureScreenshots()) return
        captureNode(recipe, profile, expectedBase, "route") {
            onTag("studio_route_$expectedBase")
        }
        captureNode(recipe, profile, expectedBase, "coach") {
            onTag("tour_coach")
        }
    }

    private fun captureNode(
        recipe: FeatureRecipe,
        profile: FlowViewport,
        expectedBase: String,
        stage: String,
        node: () -> androidx.compose.ui.test.SemanticsNodeInteraction
    ) {
        val directory = prepareCaptureDirectory() ?: return
        try {
            rule.waitForIdle()
            val bitmap = node().captureToImage().asAndroidBitmap()
            try {
                val deviceMetrics = rule.activity.resources.displayMetrics
                check(bitmap.width > 0 && bitmap.height > 0) {
                    "Empty guide capture for ${recipe.id} $stage"
                }
                check(bitmap.width <= deviceMetrics.widthPixels && bitmap.height <= deviceMetrics.heightPixels) {
                    "Guide capture ${recipe.id} $stage exceeds the actual device bounds " +
                        "${deviceMetrics.widthPixels}x${deviceMetrics.heightPixels}: " +
                        "${bitmap.width}x${bitmap.height}"
                }
                val fileName = buildString {
                    append("feature-guide-")
                    append(safeFilePart(recipe.id))
                    append('-').append(profile.language)
                    append('-').append(safeFilePart(profile.name))
                    append('-').append(safeFilePart(expectedBase))
                    append('-').append(stage).append(".png")
                }
                val file = File(directory, fileName)
                file.outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Could not write guide capture ${file.name}"
                    }
                }
                captures += CaptureRecord(
                    recipeId = recipe.id,
                    language = profile.language,
                    profile = profile.name,
                    route = expectedBase,
                    stage = stage,
                    file = file.name,
                    profileWidthDp = profile.widthDp,
                    profileHeightDp = profile.heightDp,
                    profileFontScale = profile.fontScale,
                    width = bitmap.width,
                    height = bitmap.height,
                    deviceWidth = deviceMetrics.widthPixels,
                    deviceHeight = deviceMetrics.heightPixels
                )
            } finally {
                bitmap.recycle()
            }
        } catch (error: Throwable) {
            // A capture problem must not turn a verified route into a fake preview. The real
            // flow result remains visible in the route log and the manifest records only files
            // that were actually written.
            Log.w(TAG, "Could not capture ${recipe.id} $stage for ${profile.name}", error)
        }
    }

    private fun prepareCaptureDirectory(): File? {
        captureDirectory?.let { return it }
        val directory = rule.activity.externalCacheDir?.resolve(CAPTURE_DIRECTORY_NAME)
            ?: run {
                Log.w(TAG, "External cache unavailable; skipping guide screenshot capture")
                return null
            }
        directory.mkdirs()
        directory.listFiles()?.forEach { file ->
            if (file.name.startsWith("feature-guide-") || file.name == "manifest.json") {
                file.delete()
            }
        }
        captureDirectory = directory
        return directory
    }

    private fun writeCaptureManifest() {
        val directory = captureDirectory ?: return
        val capturesJson = JSONArray().also { array ->
            captures.forEach { capture ->
                array.put(
                    JSONObject()
                        .put("recipe_id", capture.recipeId)
                        .put("language", capture.language)
                        .put("profile", capture.profile)
                        .put("route", capture.route)
                        .put("stage", capture.stage)
                        .put("file", capture.file)
                        .put("profile_width_dp", capture.profileWidthDp)
                        .put("profile_height_dp", capture.profileHeightDp)
                        .put("profile_font_scale", capture.profileFontScale)
                        .put("width_px", capture.width)
                        .put("height_px", capture.height)
                        .put("device_width_px", capture.deviceWidth)
                        .put("device_height_px", capture.deviceHeight)
                        .put("aspect_ratio", capture.width.toDouble() / capture.height.toDouble())
                )
            }
        }
        JSONObject()
            .put("schema", 1)
            .put("source", "real LlamaApp route and coach Compose nodes")
            .put("synthetic_frame", false)
            .put("captures", capturesJson)
            .also { manifest ->
                File(directory, "manifest.json").writeText(manifest.toString(2) + "\n")
            }
    }

    private fun captureScreenshots(): Boolean =
        InstrumentationRegistry.getArguments().getString(ARG_CAPTURE)?.equals("true", ignoreCase = true) == true

    private fun safeFilePart(value: String): String =
        value.replace(UNSAFE_FILE_CHARS, "_")

    private fun navigateTo(route: String, profile: FlowViewport): Boolean {
        requestSequence += 1
        rule.runOnUiThread {
            viewport.value = profile
            pendingNavigationRoute.value = ExternalRouteResolution.Navigate(route)
            hostRequest.value = HostRequest(requestSequence, route)
        }
        rule.waitForIdle()
        val base = routeBase(route)
        val arrived = awaitTag("studio_route_$base")
        if (arrived) {
            rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
                pendingNavigationRoute.value == ExternalRouteResolution.NoRoute
            }
        }
        return arrived
    }

    /**
     * `model_sources` is an in-app NavController destination, so it is intentionally outside the
     * external route resolver. Enter it through the real model-manager shortcut for the selected
     * source tab and assert the resulting NavHost route before opening the guide. This keeps the
     * connected check honest without widening the untrusted external-route boundary.
     */
    private fun navigateToModelSourcesFromManager(
        recipe: FeatureRecipe,
        profile: FlowViewport
    ): Boolean {
        if (!navigateTo(MODEL_MANAGER_ROUTE, profile)) return false
        val shortcutTag = modelSourceShortcutTag(recipe)
        if (!awaitTag("model_manager_shortcuts")) return false
        val shortcutRow = onTag("model_manager_shortcuts")
        try { shortcutRow.performScrollTo() } catch (_: AssertionError) { shortcutRow.assertIsDisplayed() }
        shortcutRow.performScrollToNode(hasTestTag(shortcutTag))
        if (!awaitTag(shortcutTag)) return false
        onTag(shortcutTag).performClick()
        return awaitTag("studio_route_$MODEL_SOURCES_ROUTE")
    }

    private fun modelSourceShortcutTag(recipe: FeatureRecipe): String = when (recipe.id) {
        "models.unknown" -> "model_manager_shortcut_unknown"
        "models.bundles" -> "model_manager_shortcut_my_bundles"
        "models.saved_links", "models.hf_folder" -> "model_manager_shortcut_saved_links"
        else -> "model_manager_shortcut_custom_download"
    }

    private fun openFeatureChooser(guide: FeatureGuide, currentBase: String, profile: FlowViewport): Boolean {
        // Some chapters intentionally explain a tool owned by another header (for example,
        // Video's summary recipe opens the Documents summary tool). Enter the requested
        // chapter through its own real header; the coach then follows its real Open action.
        if (currentBase != "dashboard" && FeatureGuideCatalog.forRoute(currentBase)?.id != guide.id) {
            if (!navigateTo(guide.route, profile)) return false
            return openFeatureChooser(guide, routeBase(guide.route), profile)
        }
        dismissSafePrerequisiteDialogs(profile)
        if (currentBase == "dashboard") {
            if (!awaitTag("soft_studio_tour")) return false
            onTag("soft_studio_tour").performClick()
            if (!awaitTag("studio_route_walkthrough")) return false
            val title = localized(profile, guide.titleRes)
            return try {
                onTag("tour_guide").performScrollToNode(androidx.compose.ui.test.hasText(title))
                rule.onNode(
                    androidx.compose.ui.test.hasText(title) and hasClickAction(),
                    useUnmergedTree = false
                ).performScrollTo().performClick()
                awaitTag("feature_guide_chooser")
            } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
                false
            }
        }
        if (!awaitTag("feature_guide_open")) return false
        onTag("feature_guide_open").performClick()
        return awaitTag("feature_guide_chooser")
    }

    private fun clickRecipeAction(recipe: FeatureRecipe, labelRes: Int, profile: FlowViewport): Boolean {
        val cardTag = "feature_recipe_${recipe.id}"
        val label = localized(profile, labelRes)
        return try {
            rule.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and
                    hasAnyAncestor(hasTestTag("feature_guide_chooser")),
                useUnmergedTree = true
            ).performScrollToNode(hasTestTag(cardTag))
            if (!awaitTag(cardTag)) return false
            rule.onNode(
                hasClickAction() and
                    hasAnyAncestor(hasTestTag(cardTag)) and
                    hasAnyDescendant(androidx.compose.ui.test.hasText(label)),
                useUnmergedTree = true
            ).performScrollTo().performClick()
            true
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            false
        }
    }

    private fun ensureExpectedRoute(expectedBase: String): Boolean {
        repeat(6) {
            if (awaitTag("studio_route_$expectedBase", timeoutMillis = SHORT_TIMEOUT_MS)) return true
            // While on a different route, the coach highlights navigation only (Back/root/tool entry).
            // Use that real control before looking for the missing-target recovery action.
            val focused = rule.onAllNodes(
                SemanticsMatcher.expectValue(WalkthroughFocusedTarget, true),
                useUnmergedTree = true
            )
            if (focused.fetchSemanticsNodes().isNotEmpty()) {
                focused.onFirst().performClick()
                rule.waitForIdle()
            } else if (awaitTag("tour_open_tool", timeoutMillis = SHORT_TIMEOUT_MS)) {
                onTag("tour_open_tool").performScrollTo().performClick()
            } else {
                rule.mainClock.advanceTimeBy(1_500)
                rule.waitForIdle()
            }
        }
        return awaitTag("studio_route_$expectedBase", timeoutMillis = SHORT_TIMEOUT_MS)
    }

    private fun inspectStepAvailability(
        recipe: FeatureRecipe,
        step: FeatureGuideStep?,
        profile: FlowViewport
    ): Boolean {
        if (step?.targetId == null) return false
        rule.mainClock.advanceTimeBy(TARGET_LOOKUP_DELAY_MS)
        rule.waitForIdle()
        var unavailable = hasTag("tour_retry") || hasTag("tour_open_tool")
        if (!unavailable && awaitTag("tour_preview", timeoutMillis = SHORT_TIMEOUT_MS)) {
            try {
                onTag("tour_preview").performScrollTo().performClick()
                unavailable = hasTag("tour_retry") || hasTag("tour_open_tool")
                closePreviewIfPresent()
            } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
                closePreviewIfPresent()
            }
        }
        if (unavailable) {
            recordBlocked(recipe, profile, "target ${step.targetId} unavailable; no fixture action invoked")
        } else {
            // This is only a readiness observation. The test deliberately does not click the
            // target, so this count must not be read as a successful feature action.
            targetPassCount++
        }
        return unavailable
    }

    private fun assertSkipLabel(profile: FlowViewport) {
        val label = localized(profile, R.string.tour_skip_step)
        val textVisible = rule.onAllNodesWithText(label, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        val descriptionVisible = onAllDescriptions(label)
        check(textVisible || descriptionVisible) { "Missing localized Skip step control for ${profile.language}" }
    }

    private fun dismissSafePrerequisiteDialogs(profile: FlowViewport) {
        val title = localized(profile, R.string.tama_new_pet_title)
        if (rule.onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            val cancel = localized(profile, R.string.action_cancel)
            rule.onNodeWithText(cancel, useUnmergedTree = true).performClick()
        }
    }

    private fun closeCoachIfPresent() {
        if (awaitTag("tour_coach", timeoutMillis = SHORT_TIMEOUT_MS)) {
            onTag("tour_close").performClick()
            awaitGone("tour_coach")
        }
    }

    private fun closePreviewIfPresent() {
        if (hasTag("tour_preview_close")) onTag("tour_preview_close").performClick()
    }

    private fun recordBlocked(recipe: FeatureRecipe, profile: FlowViewport, reason: String) {
        val result = BlockedRecipe(recipe.id, profile.language, profile.name, reason)
        blocked += result
        if (reason.startsWith("target ")) {
            targetBlockedObservationCount++
        } else {
            otherBlockedObservationCount++
        }
        Log.w(TAG, "BLOCKED ${result.recipeId} locale=${result.language} viewport=${result.viewport}: ${result.reason}")
    }

    private fun awaitTag(tag: String, timeoutMillis: Long = WAIT_TIMEOUT_MS): Boolean {
        return try {
            rule.waitUntil(timeoutMillis = timeoutMillis) { hasTag(tag) }
            true
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            false
        }
    }

    private fun awaitGone(tag: String) {
        try {
            rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) { !hasTag(tag) }
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            error("Timed out waiting for $tag to disappear")
        }
    }

    private fun onTag(tag: String) = rule.onAllNodesWithTag(tag).onFirst()

    private fun hasTag(tag: String): Boolean =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun onAllDescriptions(description: String): Boolean =
        rule.onAllNodesWithContentDescription(description, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private fun localized(profile: FlowViewport, resId: Int): String {
        val configuration = Configuration(rule.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(profile.language))
        }
        return rule.activity.createConfigurationContext(configuration).getString(resId)
    }

    private fun routeBase(route: String): String =
        route.substringBefore('?').substringBefore('/').trim('/')

    private fun capture(prefs: SharedPreferences, keys: List<String>): Map<String, Any?> {
        val values = prefs.all
        return keys.associateWith { key -> if (prefs.contains(key)) values[key] else MissingPreference }
    }

    private fun restore(prefs: SharedPreferences, values: Map<String, Any?>) {
        val editor = prefs.edit()
        values.forEach { (key, value) ->
            when (value) {
                MissingPreference, null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported SharedPreferences value for $key: ${value::class}")
            }
        }
        check(editor.commit()) { "Could not restore isolated feature-guide preferences" }
    }

    private fun fixtureWindow(profile: FlowViewport, density: Float) = object : WindowInfo {
        override val isWindowFocused = true
        override val containerSize = IntSize(
            (profile.widthDp * density).roundToInt(),
            (profile.heightDp * density).roundToInt()
        )
    }

    @Composable
    private fun TestViewport(profile: FlowViewport, content: @Composable () -> Unit) {
        val baseDensity = LocalDensity.current
        val physicalDensity = baseDensity.density
        val configuration = Configuration(LocalConfiguration.current).apply {
            screenWidthDp = profile.widthDp
            screenHeightDp = profile.heightDp
            fontScale = profile.fontScale
            orientation = if (profile.widthDp > profile.heightDp) {
                Configuration.ORIENTATION_LANDSCAPE
            } else {
                Configuration.ORIENTATION_PORTRAIT
            }
            setLocale(Locale.forLanguageTag(profile.language))
        }
        val localizedContext = ContextThemeWrapper(LocalContext.current, 0).apply {
            applyOverrideConfiguration(configuration)
        }
        CompositionLocalProvider(
            LocalActivity provides rule.activity,
            LocalActivityResultRegistryOwner provides rule.activity,
            LocalFullyDrawnReporterOwner provides rule.activity,
            LocalOnBackPressedDispatcherOwner provides rule.activity,
            LocalConfiguration provides configuration,
            LocalContext provides localizedContext,
            LocalResources provides localizedContext.resources,
            LocalWindowInfo provides fixtureWindow(profile, physicalDensity),
            LocalDensity provides Density(physicalDensity, profile.fontScale)
        ) {
            content()
        }
    }

    private data class HostRequest(val sequence: Long, val route: String)

    private data class FlowViewport(
        val name: String,
        val widthDp: Int,
        val heightDp: Int,
        val fontScale: Float,
        val language: String = "en"
    )

    private data class BlockedRecipe(
        val recipeId: String,
        val language: String,
        val viewport: String,
        val reason: String
    )

    private data class CaptureRecord(
        val recipeId: String,
        val language: String,
        val profile: String,
        val route: String,
        val stage: String,
        val file: String,
        val profileWidthDp: Int,
        val profileHeightDp: Int,
        val profileFontScale: Float,
        val width: Int,
        val height: Int,
        val deviceWidth: Int,
        val deviceHeight: Int
    )

    private object MissingPreference

    private companion object {
        const val TAG = "FeatureGuideAppFlow"
        const val SETTINGS_PREFS = "llamadroid_settings"
        const val AGENT_PREFS = "agent_prefs"
        const val HAS_COMPLETED_WELCOME = "has_completed_welcome"
        const val AUTOMATIC_ELIGIBLE = "walkthrough_automatic_eligible"
        const val FIRST_RUN_SHOWN = "first_run_shown"
        const val ARG_ISOLATED = "isolated_feature_qa"
        const val ARG_SECONDARY_USER = "secondary_android_user_id"
        const val ARG_LOCALE = "feature_guide_locale"
        const val ARG_VIEWPORT = "feature_guide_viewport"
        const val ARG_RECIPE_IDS = "feature_recipe_ids"
        const val ARG_CAPTURE = "feature_guide_capture"
        const val MODEL_MANAGER_ROUTE = "llm_models"
        const val MODEL_SOURCES_ROUTE = "model_sources"
        const val CAPTURE_DIRECTORY_NAME = "feature-guide-app-flow"
        const val SECONDARY_USER_ID = "10"
        const val USER_ID_RANGE = 100_000
        const val WAIT_TIMEOUT_MS = 8_000L
        const val SHORT_TIMEOUT_MS = 1_000L
        const val TARGET_LOOKUP_DELAY_MS = 2_100L
        val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")
        val FLOW_VIEWPORTS = mapOf(
            "compact" to FlowViewport("compact-320-font2", 320, 640, 2f),
            "standard" to FlowViewport("standard-411-font1", 411, 800, 1f),
            "phone" to FlowViewport("phone-411-font1", 411, 800, 1f),
            "tablet" to FlowViewport("tablet-600-font1", 600, 960, 1f)
        )

        val FEATURE_RECIPE_IDS = FeatureGuideCatalog.guides
            .flatMap { it.recipes }
            .map { it.id }
        val SETTINGS_KEYS = listOf(HAS_COMPLETED_WELCOME, AUTOMATIC_ELIGIBLE) +
            listOf(progressKey("core"), completedKey("core")) +
            FEATURE_RECIPE_IDS.flatMap { listOf(progressKey(it), completedKey(it)) }
        val AGENT_KEYS = listOf(FIRST_RUN_SHOWN)

        fun progressKey(chapterId: String) = "walkthrough_progress:$chapterId"
        fun completedKey(chapterId: String) = "walkthrough_completed:$chapterId"
    }
}
