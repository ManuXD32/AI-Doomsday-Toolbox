package com.example.llamadroid.ui.walkthrough

import android.content.Context
import android.content.SharedPreferences
import com.example.llamadroid.data.WalkthroughPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WalkthroughStateTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var walkthroughPreferences: WalkthroughPreferences

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences(
            "walkthrough_state_test_${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        assertTrue(prefs.edit().clear().commit())
        walkthroughPreferences = WalkthroughPreferences(prefs)
    }

    @Test
    fun `detail guides retain real arguments and use safe parents from Home`() {
        val cases = listOf(
            Triple("agent.recovery", "agent_invocation/demo-id", "agent"),
            Triple("knowledge.chunk", "knowledge_chunk/42", "knowledge_base"),
            Triple("termux.webview", "termux_webview/https%3A%2F%2Flocalhost/Demo/tool", "termux"),
            Triple("training.progress", "quadtrix_webui/https%3A%2F%2Flocalhost", "quadtrix_trainer"),
            Triple("tama.adventure", "adventure/FOREST", "dungeon")
        )
        cases.forEach { (recipe, detail, parent) ->
            val state = WalkthroughState(walkthroughPreferences)
            state.startFeature(recipe, false, detail)
            assertEquals(detail, state.step?.route)
            state.move(1)
            assertEquals(detail, state.step?.route)
            state.dismiss()
            state.startFeature(recipe, false, "walkthrough")
            assertEquals(parent, state.step?.route)
        }
    }

    @Test
    fun `feature action result is retained for following explanations`() {
        val state = WalkthroughState(walkthroughPreferences)
        state.startFeature("models.manager", false, "model_hub")
        state.move(1, observedRoute = "model_sources?family=SD&tab=sources")
        assertEquals("model_sources?family=SD&tab=sources", state.step?.route)
        state.move(1)
        assertEquals("model_sources?family=SD&tab=sources", state.step?.route)
        state.move(-2)
        assertEquals("model_hub", state.step?.route)
    }

    @Test
    fun `core tour has the bounded eight step route`() {
        assertEquals(8, CoreTour.steps.size)
        assertEquals(
            listOf("home", "tools", "create", "back", "library", "tama", "settings", "replay"),
            CoreTour.steps.map { it.id }
        )
        assertTrue(CoreTour.steps.all { it.route.isNotBlank() && it.previewKey.isNotBlank() })
    }

    @Test
    fun `start move and dismiss persist progress without completing the chapter`() {
        val state = WalkthroughState(walkthroughPreferences)

        state.start(CoreTour.ID, resume = false)
        assertEquals(TourSession(CoreTour.ID, 0), state.session)
        assertEquals("home", walkthroughPreferences.progress(CoreTour.ID))

        state.move(1)
        assertEquals(TourSession(CoreTour.ID, 1), state.session)
        assertEquals("tools", walkthroughPreferences.progress(CoreTour.ID))

        state.dismiss()
        assertNull(state.session)
        assertEquals("tools", walkthroughPreferences.progress(CoreTour.ID))
        assertFalse(walkthroughPreferences.isCompleted(CoreTour.ID))
    }

    @Test
    fun `resume starts at the saved step and moving backward is bounded`() {
        val state = WalkthroughState(walkthroughPreferences)

        state.start(CoreTour.ID, resume = false)
        state.move(3)
        state.dismiss()

        state.start(CoreTour.ID, resume = true)
        assertEquals(TourSession(CoreTour.ID, 3), state.session)

        state.move(-1)
        assertEquals(TourSession(CoreTour.ID, 2), state.session)
        state.move(-10)
        assertEquals(TourSession(CoreTour.ID, 2), state.session)
    }

    @Test
    fun `moving past the last step completes the chapter and closes the session`() {
        val state = WalkthroughState(walkthroughPreferences)

        state.start(CoreTour.ID, resume = false)
        repeat(CoreTour.steps.lastIndex) { state.move(1) }
        assertEquals(TourSession(CoreTour.ID, CoreTour.steps.lastIndex), state.session)

        state.move(1)

        assertNull(state.session)
        assertTrue(walkthroughPreferences.isCompleted(CoreTour.ID))
        assertEquals("replay", walkthroughPreferences.progress(CoreTour.ID))
    }

    @Test
    fun `target resolution follows route arrival roots drawer and tool targets`() {
        val home = CoreTour.steps.first()
        val create = CoreTour.steps[2]
        val settings = CoreTour.steps[6]

        assertEquals("dashboard", routeBase("dashboard?from=tour"))
        assertEquals("image_gen", routeBase("image_gen/gallery"))
        assertTrue(tourHasArrived(home, "dashboard?conversationId=42"))
        assertFalse(tourHasArrived(home, "ai_hub"))

        assertEquals(home.focusTarget, tourTarget(home, "dashboard", false, false))
        assertEquals("root.dashboard", tourTarget(home, "ai_hub", false, false))
        assertEquals("shell.menu", tourTarget(home, "ai_hub", true, false))
        assertEquals("drawer.root.dashboard", tourTarget(home, "ai_hub", true, true))
        assertEquals("shell.settings", tourTarget(settings, "dashboard", false, false))
        assertEquals("drawer.settings", tourTarget(settings, "dashboard", true, true))
        assertEquals("tool.image_generation", tourTarget(create, "ai_hub", false, false))
        assertEquals("root.ai_hub", tourTarget(create, "dashboard", false, false))
        assertEquals("shell.menu", tourTarget(create, "dashboard", true, false))
        assertEquals("drawer.root.ai_hub", tourTarget(create, "dashboard", true, true))
        assertEquals("back", tourTarget(create, "unknown_route", false, false))
    }

    @Test
    fun `settings hub sibling lessons resolve to their distinct walkthrough targets`() {
        val settingsLessons = WalkthroughState(walkthroughPreferences).steps("settings_help")
        val about = settingsLessons.first { it.route == "about" }
        val logs = settingsLessons.first { it.route == "logs" }

        assertEquals("settings.about", tourTarget(about, "settings", false, false))
        assertEquals("settings.logs", tourTarget(logs, "settings", false, false))
    }

    @Test
    fun `remaining tour ime inset handles unmeasured resized and edge to edge windows`() {
        assertEquals(0, remainingTourImePx(fullHeight = 0, bottom = 0, ime = 800))
        assertEquals(800, remainingTourImePx(fullHeight = 2400, bottom = 2400, ime = 800))
        assertEquals(0, remainingTourImePx(fullHeight = 2400, bottom = 1600, ime = 800))
        assertEquals(400, remainingTourImePx(fullHeight = 2400, bottom = 2000, ime = 800))
    }

    @Test
    fun `eligible automatic presentation claims and starts the core tour`() {
        val state = WalkthroughState(walkthroughPreferences)

        state.observeEligibility(true)
        awaitState { state.automaticCheckFinished && state.session != null }

        assertEquals(TourSession(CoreTour.ID, 0), state.session)
        assertFalse(walkthroughPreferences.automaticEligible)
        assertEquals("home", walkthroughPreferences.progress(CoreTour.ID))
        assertTrue(state.suppressSupportForLaunch)
    }

    @Test
    fun `automatic presentation is consumed when a manual tour is already active`() {
        val state = WalkthroughState(walkthroughPreferences)
        state.start(CoreTour.ID, resume = false)
        val sessionBefore = state.session
        val revisionBefore = state.revision

        state.observeEligibility(true)
        awaitState {
            state.revision > revisionBefore &&
                state.automaticCheckFinished &&
                !walkthroughPreferences.automaticEligible
        }

        assertEquals(sessionBefore, state.session)
        assertTrue(state.automaticCheckFinished)
        assertFalse(walkthroughPreferences.automaticEligible)
        assertEquals("home", walkthroughPreferences.progress(CoreTour.ID))
    }

    @Test
    fun `automatic presentation defers when eligibility is revoked before launch is shown`() {
        val claimEntered = CountDownLatch(1)
        val releaseClaim = CountDownLatch(1)
        val racingPreferences = WalkthroughPreferences(
            blockingAutomaticReadPreferences(prefs, claimEntered, releaseClaim)
        )
        val state = WalkthroughState(racingPreferences)

        state.observeEligibility(true)
        awaitState { claimEntered.count == 0L }
        state.observeEligibility(false)
        releaseClaim.countDown()
        awaitState {
            state.session == null &&
                racingPreferences.progress(CoreTour.ID) != null &&
                racingPreferences.automaticEligible &&
                !state.automaticCheckFinished
        }

        assertNull(state.session)
        assertEquals("home", racingPreferences.progress(CoreTour.ID))
        assertTrue(racingPreferences.automaticEligible)
        assertFalse(state.automaticCheckFinished)
    }

    @Test
    fun `deferred automatic presentation retries when the eligible launch returns`() {
        val claimEntered = CountDownLatch(1)
        val releaseClaim = CountDownLatch(1)
        val racingPreferences = WalkthroughPreferences(
            blockingAutomaticReadPreferences(prefs, claimEntered, releaseClaim)
        )
        val state = WalkthroughState(racingPreferences)

        state.observeEligibility(true)
        awaitState { claimEntered.count == 0L }
        state.observeEligibility(false)
        releaseClaim.countDown()
        awaitState {
            racingPreferences.automaticEligible &&
                !state.automaticCheckFinished &&
                racingPreferences.progress(CoreTour.ID) != null
        }

        state.observeEligibility(true)
        awaitState { state.automaticCheckFinished && state.session != null }

        assertEquals(TourSession(CoreTour.ID, 0), state.session)
        assertFalse(racingPreferences.automaticEligible)
        assertTrue(state.suppressSupportForLaunch)
    }

    @Test
    fun `active tour remains in the retained view model but process replacement does not reopen it`() {
        val state = WalkthroughState(walkthroughPreferences)
        state.observeEligibility(true)
        awaitState { state.automaticCheckFinished && state.session != null }
        state.move(2)
        val retainedSession = state.session

        // A rotation keeps the activity-owned ViewModel instance and its live session.
        assertEquals(TourSession(CoreTour.ID, 2), retainedSession)
        assertEquals(retainedSession, state.session)

        // A process replacement creates a new ViewModel. Durable progress remains available
        // for an explicit Resume action, while the transient coach is not reopened implicitly.
        val replacement = WalkthroughState(WalkthroughPreferences(prefs))
        assertNull(replacement.session)
        assertEquals("create", replacement.preferences.progress(CoreTour.ID))
        assertFalse(replacement.preferences.automaticEligible)

        replacement.start(CoreTour.ID, resume = true)
        assertEquals(TourSession(CoreTour.ID, 2), replacement.session)
    }

    @Test
    fun `normal launch ids suppress support only while an active tour belongs to that launch`() {
        val state = WalkthroughState(walkthroughPreferences)

        state.beginLaunch(1)
        assertFalse(state.suppressSupportForLaunch)

        state.start(CoreTour.ID, resume = false)
        assertTrue(state.suppressSupportForLaunch)
        state.beginLaunch(1)
        assertTrue(state.suppressSupportForLaunch)

        state.dismiss()
        state.beginLaunch(2)
        assertFalse(state.suppressSupportForLaunch)

        state.start(CoreTour.ID, resume = false)
        state.beginLaunch(3)
        assertTrue(state.suppressSupportForLaunch)
    }

    @Test
    fun `external launch token interrupts once while rotation token retains restarted manual guidance`() {
        val state = WalkthroughState(walkthroughPreferences)
        state.start(CoreTour.ID, resume = false)
        state.move(1)

        // The initial token represents the launch that created the retained ViewModel and must
        // not interrupt a manually active tour.
        state.interruptForExternalLaunch(0)
        assertEquals(TourSession(CoreTour.ID, 1), state.session)

        // A new external intent interrupts once and persists the resumable step.
        state.interruptForExternalLaunch(1)
        assertNull(state.session)
        assertEquals("tools", walkthroughPreferences.progress(CoreTour.ID))

        // The user can reopen the guide during that same external launch. A rotation/recomposition
        // delivers the same token and must leave the resumed manual session intact.
        state.start(CoreTour.ID, resume = true)
        assertEquals(TourSession(CoreTour.ID, 1), state.session)
        state.interruptForExternalLaunch(1)
        assertEquals(TourSession(CoreTour.ID, 1), state.session)

        // A genuinely new external intent interrupts the restarted session and keeps its progress.
        state.interruptForExternalLaunch(2)
        assertNull(state.session)
        assertEquals("tools", walkthroughPreferences.progress(CoreTour.ID))
    }

    @Test
    fun `external launch token closes an in flight automatic claim and allows a later normal launch`() {
        val claimEntered = CountDownLatch(1)
        val releaseClaim = CountDownLatch(1)
        val racingPreferences = WalkthroughPreferences(
            blockingAutomaticReadPreferences(prefs, claimEntered, releaseClaim)
        )
        val state = WalkthroughState(racingPreferences)

        state.observeEligibility(true)
        awaitState { claimEntered.count == 0L }

        // The external launch arrives while the durable claim is blocked. The token must close
        // the in-memory eligibility window before the claim can return.
        state.interruptForExternalLaunch(1)
        releaseClaim.countDown()
        awaitState {
            state.revision > 0 &&
                state.automaticCheckFinished.not() &&
                state.session == null &&
                racingPreferences.progress(CoreTour.ID) != null &&
                racingPreferences.automaticEligible
        }

        // A later normal launch can retry the re-armed one-time presentation.
        state.observeEligibility(true)
        awaitState { state.automaticCheckFinished && state.session != null }
        assertEquals(TourSession(CoreTour.ID, 0), state.session)
        assertFalse(racingPreferences.automaticEligible)
    }

    @Test
    fun `eligibility returning while automatic defer is blocked is reconciled without another host event`() {
        val claimEntered = CountDownLatch(1)
        val releaseClaim = CountDownLatch(1)
        val deferEntered = CountDownLatch(1)
        val releaseDefer = CountDownLatch(1)
        val racingPreferences = WalkthroughPreferences(
            blockingAutomaticClaimAndRearmPreferences(
                prefs,
                claimEntered,
                releaseClaim,
                deferEntered,
                releaseDefer
            )
        )
        val state = WalkthroughState(racingPreferences)

        state.observeEligibility(true)
        awaitState { claimEntered.count == 0L }
        state.interruptForExternalLaunch(1)
        releaseClaim.countDown()
        awaitState { deferEntered.count == 0L }
        assertTrue(state.awaitingAutomaticPresentation)

        // The normal launch becomes eligible again while the durable re-arm is still blocked.
        // The state machine must reconcile this itself after the serialized operation finishes.
        state.observeEligibility(true)
        assertTrue(state.awaitingAutomaticPresentation)
        releaseDefer.countDown()

        awaitState {
            state.automaticCheckFinished &&
                state.session == TourSession(CoreTour.ID, 0) &&
                !racingPreferences.automaticEligible
        }
        assertFalse(state.awaitingAutomaticPresentation)
        assertEquals(TourSession(CoreTour.ID, 0), state.session)
    }

    @Test
    fun `open guide dismisses the coach without dropping progress and suppresses support`() {
        val state = WalkthroughState(walkthroughPreferences)
        state.start(CoreTour.ID, resume = false)
        state.move(1)

        state.openGuide()

        assertNull(state.session)
        assertEquals("tools", walkthroughPreferences.progress(CoreTour.ID))
        assertTrue(state.suppressSupportForLaunch)
    }

    @Test
    fun `manual guide consumes external launch eligibility and prevents a later automatic reopen`() {
        // The external launch is ineligible at the UI boundary, but the durable one-time marker
        // is still available until the user explicitly opens the guide.
        val state = WalkthroughState(walkthroughPreferences)
        state.observeEligibility(false)
        state.openGuide()
        awaitState {
            state.automaticCheckFinished && !walkthroughPreferences.automaticEligible
        }

        state.start(CoreTour.ID, resume = false)
        repeat(CoreTour.steps.size) { state.move(1) }
        assertNull(state.session)
        assertTrue(walkthroughPreferences.isCompleted(CoreTour.ID))

        val nextLaunch = WalkthroughState(WalkthroughPreferences(prefs))
        nextLaunch.observeEligibility(true)
        awaitState { nextLaunch.automaticCheckFinished }

        assertNull(nextLaunch.session)
        assertFalse(nextLaunch.preferences.automaticEligible)
    }

    @Test
    fun `opening guide while automatic claim is in flight does not rearm or auto start`() {
        val claimEntered = CountDownLatch(1)
        val releaseClaim = CountDownLatch(1)
        val racingPreferences = WalkthroughPreferences(
            blockingAutomaticReadPreferences(prefs, claimEntered, releaseClaim)
        )
        val state = WalkthroughState(racingPreferences)

        state.observeEligibility(true)
        awaitState { claimEntered.count == 0L }

        // The automatic IO operation is held before it can commit. The manual guide is now the
        // visible presentation and must win regardless of which durable operation commits first.
        state.openGuide()
        releaseClaim.countDown()
        awaitState {
            state.automaticCheckFinished &&
                state.session == null &&
                !racingPreferences.automaticEligible
        }

        assertNull(state.session)
        assertFalse(racingPreferences.automaticEligible)
    }

    @Test
    fun `core progress transitions keep the route and focus target contract`() {
        val state = WalkthroughState(walkthroughPreferences)
        state.start(CoreTour.ID, resume = false)

        assertEquals("home", state.step?.id)
        assertEquals("home.summary", tourTarget(state.step!!, "dashboard", false, false))

        state.move(1)
        assertEquals("tools", state.step?.id)
        assertEquals("root.ai_hub", tourTarget(state.step!!, "dashboard", false, false))
        assertEquals("tools.search", tourTarget(state.step!!, "ai_hub", false, false))

        state.move(1)
        assertEquals("create", state.step?.id)
        assertEquals("tool.image_generation", tourTarget(state.step!!, "ai_hub", false, false))
        assertEquals("image.prompt", tourTarget(state.step!!, "image_gen/gallery", false, false))

        state.move(1)
        assertEquals("back", state.step?.id)
        assertEquals("back", tourTarget(state.step!!, "image_gen", false, false))

        state.move(1)
        assertEquals("library", state.step?.id)
        assertEquals("root.library", tourTarget(state.step!!, "dashboard", false, false))
        assertEquals("library.resources", tourTarget(state.step!!, "library", false, false))
    }

    @Test
    fun `feature guidance keeps origin and progress without rearming core`() {
        val state = WalkthroughState(walkthroughPreferences)
        val guide = FeatureGuideCatalog.guides.first { it.recipes.any { recipe -> recipe.steps.size > 1 } }
        val recipe = guide.recipes.first { it.steps.size > 1 }
        state.openFeatureGuide(guide.id)
        assertEquals(guide.id, state.featureChooserId)
        assertNull(state.session)
        assertTrue(state.suppressSupportForLaunch)
        state.startFeature(recipe.id, false, "native_chat/17?draft=preserved")
        assertNull(state.featureChooserId)
        assertEquals("native_chat/17?draft=preserved", state.session?.originRoute)
        state.move(1)
        val progress = state.step?.id
        state.dismiss()
        assertEquals(progress, state.preferences.progress("feature:${recipe.id}"))
        val restored = WalkthroughState(walkthroughPreferences)
        assertNull(restored.session)
        restored.startFeature(recipe.id, true, "native_chat/17?draft=preserved")
        assertEquals(progress, restored.step?.id)
        restored.interruptForExternalLaunch(1)
        assertNull(restored.session)
        assertEquals(progress, restored.preferences.progress("feature:${recipe.id}"))
    }

    @Test
    fun `feature chooser dismissal and external interruption retain core progress`() {
        val state = WalkthroughState(walkthroughPreferences)
        state.start(CoreTour.ID, false)
        state.move(2)
        state.openFeatureGuide(FeatureGuideCatalog.guides.first().id)
        state.closeFeatureChooser()
        assertNull(state.featureChooserId)
        assertEquals("create", state.preferences.progress(CoreTour.ID))
        state.openFeatureGuide(FeatureGuideCatalog.guides.first().id)
        state.interruptForExternalLaunch(3)
        assertNull(state.featureChooserId)
        assertNull(state.session)
    }

    @Test
    fun `cross surface feature explanations remain at the selected tool`() {
        val state = WalkthroughState(walkthroughPreferences)
        state.startFeature("models.custom_url", false, "sd_models")
        assertEquals("model_sources", state.step?.route)
        state.move(1)
        assertEquals("model_sources", state.step?.route)
        state.move(1)
        assertEquals("model_sources", state.step?.route)
    }

    @Test
    fun `connected media recipe target event survives resume recreation and replay`() {
        val recipe = requireNotNull(FeatureGuideCatalog.recipe("image.inpaint"))
        val state = WalkthroughState(walkthroughPreferences)
        state.startFeature(recipe.id, resume = false, currentRoute = "image_gen")

        assertEquals("image.options", state.step?.focusTarget)
        assertEquals("image.options", state.step?.eventId)
        val registry = WalkthroughTargets().apply { active = true }
        registry.recordEvent(requireNotNull(state.step?.eventId))
        assertEquals(1, registry.events["image.options"])

        state.move(1)
        state.dismiss()
        val recreated = WalkthroughState(WalkthroughPreferences(prefs))
        recreated.startFeature(recipe.id, resume = true, currentRoute = "image_gen")
        assertEquals(1, recreated.session?.index)
        assertEquals("image.options", recreated.steps(recreated.session!!.chapterId).first().focusTarget)

        recreated.startFeature(recipe.id, resume = false, currentRoute = "image_gen")
        assertEquals(0, recreated.session?.index)
        assertEquals("image.options", recreated.step?.focusTarget)
    }

    private fun awaitState(timeoutMillis: Long = 2_000L, condition: () -> Boolean) {
        val scheduler = Robolectric.getForegroundThreadScheduler()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition() && System.nanoTime() < deadline) {
            if (!scheduler.runOneTask()) Thread.yield()
        }
        assertTrue("Timed out waiting for walkthrough coroutine", condition())
    }

    @Suppress("UNCHECKED_CAST")
    private fun blockingAutomaticReadPreferences(
        delegate: SharedPreferences,
        entered: CountDownLatch,
        release: CountDownLatch
    ): SharedPreferences = Proxy.newProxyInstance(
        WalkthroughStateTest::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        if (method.name == "getBoolean" && args?.getOrNull(0) == AUTOMATIC_ELIGIBLE) {
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS)) { "Timed out releasing automatic claim" }
        }
        method.invoke(delegate, *(args ?: emptyArray()))
    } as SharedPreferences

    @Suppress("UNCHECKED_CAST")
    private fun blockingAutomaticClaimAndRearmPreferences(
        delegate: SharedPreferences,
        claimEntered: CountDownLatch,
        releaseClaim: CountDownLatch,
        deferEntered: CountDownLatch,
        releaseDefer: CountDownLatch
    ): SharedPreferences = Proxy.newProxyInstance(
        WalkthroughStateTest::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        if (method.name == "getBoolean" && args?.getOrNull(0) == AUTOMATIC_ELIGIBLE) {
            claimEntered.countDown()
            check(releaseClaim.await(2, TimeUnit.SECONDS)) { "Timed out releasing automatic claim" }
        }
        if (method.name == "edit") {
            val delegateEditor = method.invoke(delegate, *(args ?: emptyArray()))
            @Suppress("UNCHECKED_CAST")
            var editorProxy: SharedPreferences.Editor? = null
            var rearmPending = false
            editorProxy = Proxy.newProxyInstance(
                WalkthroughStateTest::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { _, editorMethod, editorArgs ->
                if (editorMethod.name == "putBoolean" &&
                    editorArgs?.getOrNull(0) == AUTOMATIC_ELIGIBLE &&
                    editorArgs?.getOrNull(1) == true
                ) {
                    rearmPending = true
                }
                if (editorMethod.name == "commit" && rearmPending) {
                    rearmPending = false
                    deferEntered.countDown()
                    check(releaseDefer.await(2, TimeUnit.SECONDS)) { "Timed out releasing automatic defer" }
                }
                val result = editorMethod.invoke(delegateEditor, *(editorArgs ?: emptyArray()))
                if (result === delegateEditor) editorProxy ?: result else result
            } as SharedPreferences.Editor
            editorProxy!!
        } else {
            method.invoke(delegate, *(args ?: emptyArray()))
        }
    } as SharedPreferences

    private companion object {
        const val AUTOMATIC_ELIGIBLE = "walkthrough_automatic_eligible"
    }
}
