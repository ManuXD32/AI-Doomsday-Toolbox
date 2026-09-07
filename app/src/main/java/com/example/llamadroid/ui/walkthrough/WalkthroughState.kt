package com.example.llamadroid.ui.walkthrough

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.R
import com.example.llamadroid.data.WalkthroughPreferences
import com.example.llamadroid.ui.navigation.AppRootDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class TourStep(
    val id: String,
    val titleRes: Int,
    val bodyRes: Int,
    val route: String,
    val previewKey: String,
    val focusTarget: String? = null,
    val toolId: String? = null,
    val eventId: String? = null
)

internal object CoreTour {
    const val ID = "core"
    val steps = listOf(
        TourStep("home", R.string.tour_core_home, R.string.tour_core_home_body, "dashboard", "home", "home.summary"),
        TourStep("tools", R.string.tour_core_tools, R.string.tour_core_tools_body, "ai_hub", "tools", "tools.search"),
        TourStep("create", R.string.tour_core_create, R.string.tour_core_create_body, "image_gen", "create", "image.prompt", "image_generation"),
        TourStep("back", R.string.tour_core_back, R.string.tour_core_back_body, "ai_hub", "tools"),
        TourStep("library", R.string.tour_core_library, R.string.tour_core_library_body, "library", "library", "library.resources"),
        TourStep("tama", R.string.tour_core_tama, R.string.tour_core_tama_body, "tama", "tama"),
        TourStep("settings", R.string.tour_core_settings, R.string.tour_core_settings_body, "settings", "home"),
        TourStep("replay", R.string.tour_core_replay, R.string.tour_core_replay_body, "dashboard", "home", "shell.tour")
    )
}

internal data class TourSession(val chapterId: String, val index: Int, val originRoute: String? = null,
    val routeAfterAction: String? = null)

/** Activity-owned, deliberately not saved-state-owned: rotation retains guidance, process death does not reopen it. */
internal class WalkthroughState(val preferences: WalkthroughPreferences) : ViewModel() {
    var session by mutableStateOf<TourSession?>(null)
        private set
    var revision by mutableStateOf(0)
        private set
    var suppressSupportForLaunch by mutableStateOf(false)
        private set
    var automaticCheckFinished by mutableStateOf(false)
        private set
    private var checkingAutomatic by mutableStateOf(false)
    val awaitingAutomaticPresentation: Boolean get() = checkingAutomatic
    private var launchEligible = false
    private var launchId = 0
    private var externalLaunchIdSeen = 0
    private var manualGuideSeen = false
    var featureChooserId by mutableStateOf<String?>(null)
        private set

    fun beginLaunch(id: Int) {
        if (id != launchId) {
            launchId = id
            suppressSupportForLaunch = session != null
        }
    }

    /**
     * Interrupts a retained tour once for each new external launch. The token is activity-owned
     * and restored across rotation, so a recreated composition cannot dismiss a manually resumed
     * guide again for the same external intent.
     */
    fun interruptForExternalLaunch(id: Int) {
        if (id == externalLaunchIdSeen) return
        externalLaunchIdSeen = id
        // Close the eligibility window before the host's next composition can observe the
        // external launch. An in-flight claim may still finish, but it must be re-armed instead
        // of presenting a coach over the externally opened content.
        launchEligible = false
        featureChooserId = null
        if (session != null) dismiss()
    }

    fun steps(chapterId: String): List<TourStep> {
        if (chapterId == CoreTour.ID) return CoreTour.steps
        if (chapterId.startsWith("feature:")) {
            val recipeId = chapterId.removePrefix("feature:")
            val guide = FeatureGuideCatalog.guides.firstOrNull { guide -> guide.recipes.any { it.id == recipeId } }
                ?: return emptyList()
            val recipe = guide.recipes.first { it.id == recipeId }
            val origin = session?.takeIf { it.chapterId == chapterId }?.originRoute ?: guide.route
            var destination = origin
            return recipe.steps.mapIndexed { index, it ->
                // Explanations after a cross-surface step stay with that surface. Returning to
                // the recipe's original page here would send users back out of the tool.
                destination = it.route ?: session?.takeIf { it.chapterId == chapterId && it.index == index }
                    ?.routeAfterAction ?: destination
                TourStep(it.id, it.titleRes, it.bodyRes, destination, it.previewKey,
                    focusTarget = it.targetId, eventId = it.eventId)
            }
        }
        return WalkthroughCatalog.chapter(chapterId)?.lessons.orEmpty().map {
            TourStep(it.id, it.titleRes, it.bodyRes, it.route, it.previewKey, toolId = it.toolId)
        }
    }
    val step: TourStep? get() = session?.let { steps(it.chapterId).getOrNull(it.index) }

    fun observeEligibility(eligible: Boolean) {
        launchEligible = eligible
        if (!eligible || checkingAutomatic || automaticCheckFinished) return
        checkingAutomatic = true
        viewModelScope.launch {
            val claimed = withContext(Dispatchers.IO) { preferences.claimAutomaticPresentation() }
            if (claimed) {
                revision++
                if (!manualGuideSeen && launchEligible && session == null) {
                    automaticCheckFinished = true
                    start(CoreTour.ID, resume = true)
                } else if (!manualGuideSeen && session == null) {
                    withContext(Dispatchers.IO) { preferences.deferAutomaticPresentation() }
                    // A manual guide may open while the deferred write is in flight.
                    if (manualGuideSeen) withContext(Dispatchers.IO) { preferences.consumeManualPresentation() }
                    automaticCheckFinished = manualGuideSeen
                } else {
                    automaticCheckFinished = true
                }
            } else {
                automaticCheckFinished = true
            }
            checkingAutomatic = false
            // Eligibility can return while the durable defer/manual-consume operation is in
            // flight. Reconcile it after the serialized check has fully released its guard.
            if (launchEligible && !automaticCheckFinished) observeEligibility(true)
        }
    }

    fun openGuide() {
        featureChooserId = null
        manualGuideSeen = true
        automaticCheckFinished = true
        suppressSupportForLaunch = true
        dismiss()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { preferences.consumeManualPresentation() }
        }
    }

    /** A feature chooser is a modal over its existing route; opening help never rebuilds the tool. */
    fun openFeatureGuide(guideId: String) {
        if (FeatureGuideCatalog.guides.none { it.id == guideId }) return
        openGuide()
        featureChooserId = guideId
    }

    fun closeFeatureChooser() { featureChooserId = null }

    fun startFeature(recipeId: String, resume: Boolean, currentRoute: String?) {
        featureChooserId = null
        start("feature:$recipeId", resume)
        session = session?.copy(originRoute = currentRoute?.takeUnless { routeBase(it) == "walkthrough" })
    }

    fun start(chapterId: String, resume: Boolean) {
        val steps = steps(chapterId)
        if (steps.isEmpty()) return
        val index = if (resume) steps.indexOfFirst { it.id == preferences.progress(chapterId) }.coerceAtLeast(0) else 0
        if (!resume) preferences.reset(chapterId)
        suppressSupportForLaunch = true
        session = TourSession(chapterId, index)
        persist()
    }

    fun move(delta: Int, observedRoute: String? = null) {
        val current = session ?: return
        val next = current.index + delta
        if (next >= steps(current.chapterId).size) {
            preferences.complete(current.chapterId)
            session = null
            revision++
        } else if (next >= 0) {
            val explicitDestination = FeatureGuideCatalog.recipe(current.chapterId)?.steps?.getOrNull(next)?.route
            session = current.copy(index = next, routeAfterAction =
                if (explicitDestination != null) null else observedRoute ?: current.routeAfterAction)
            persist()
        }
    }

    fun dismiss() {
        persist()
        session = null
    }

    private fun persist() {
        val current = session ?: return
        step?.let { preferences.saveProgress(current.chapterId, it.id) }
        revision++
    }
}

internal fun routeBase(route: String?) = route?.substringBefore('?')?.substringBefore('/').orEmpty()

/** The actual destination is observed; tapping Next never pretends a required navigation happened. */
internal fun tourHasArrived(step: TourStep, currentRoute: String?) = routeBase(currentRoute) == routeBase(step.route) ||
    (step.toolId == "chat" && routeBase(currentRoute) == "llama_servers")

internal fun tourTarget(step: TourStep, currentRoute: String?, drawerNavigation: Boolean, drawerOpen: Boolean): String? {
    if (tourHasArrived(step, currentRoute)) return step.focusTarget
    val current = routeBase(currentRoute)
    val root = AppRootDestination.entries.firstOrNull { it.route == current }
    if (current == "settings" && step.route in setOf("about", "logs")) return "settings.${step.route}"
    if (root == null) return "back"
    if (step.route == "settings") return if (drawerNavigation && drawerOpen) "drawer.settings" else "shell.settings"
    val nextRoot = AppRootDestination.entries.firstOrNull { it.route == routeBase(step.route) }
    if (nextRoot != null) return if (drawerNavigation) { if (drawerOpen) "drawer.root.${nextRoot.route}" else "shell.menu" } else "root.${nextRoot.route}"
    if (current == "ai_hub" && step.toolId != null) return "tool.${step.toolId}"
    if (current != "ai_hub" && step.toolId != null) return if (drawerNavigation) { if (drawerOpen) "drawer.root.ai_hub" else "shell.menu" } else "root.ai_hub"
    return null
}
