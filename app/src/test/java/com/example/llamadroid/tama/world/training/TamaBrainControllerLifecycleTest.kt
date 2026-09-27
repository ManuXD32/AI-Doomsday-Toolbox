package com.example.llamadroid.tama.world.training

import android.app.Application
import android.content.Context
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * Exercises the Android lifecycle boundary around one real PPO update. The fake living store
 * exposes only the narrow artifact/reference callbacks; it never constructs a living engine or
 * accesses Room. The training directory is removed after the controller scopes have terminated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TamaBrainControllerLifecycleTest {
    @Test
    fun `cold unapplied guided result without checkpoint returns to paused recovery`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val petId = "brain-guided-stale-${System.nanoTime()}"
        val repository = TrainingRepository(context, petId)
        repository.saveState(
            BrainRuntimePersistence(
                chargingOnly = false,
                maxThreads = 1,
                environmentCount = 1,
                guided = BrainGuidedRuntimeState(
                    phase = BrainGuidedPhase.RESULTS,
                    evaluationPassed = true
                )
            )
        )
        val living = FakeLivingPolicyStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = controller(context, scope, petId, living)
        try {
            await(controller.initialize())
            assertEquals(BrainGuidedPhase.PAUSED, controller.state.value.guided.phase)
            assertEquals("guided_evaluation_stale", controller.state.value.guided.gateFailure)
            assertFalse(controller.state.value.guided.evaluationPassed)
        } finally {
            closeController(controller, scope)
            repository.resumeFile.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun `guided budget finishes automatically at a batch boundary`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val petId = "brain-guided-budget-${System.nanoTime()}"
        val repository = TrainingRepository(context, petId)
        repository.saveState(
            BrainRuntimePersistence(
                chargingOnly = false,
                maxThreads = 1,
                environmentCount = 1,
                guided = BrainGuidedRuntimeState(sessionBudgetMillis = 1L)
            )
        )
        val living = FakeLivingPolicyStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = controller(context, scope, petId, living)
        try {
            await(controller.initialize())
            await(controller.startGuidedPractice())
            awaitState(controller) {
                it.guided.phase == BrainGuidedPhase.PRACTICING &&
                    (it.snapshot?.metrics?.environmentSteps ?: 0L) >= 16L
            }
            // Robolectric's elapsedRealtime is otherwise fixed while the worker advances on a
            // background dispatcher, so explicitly cross the one-millisecond test budget after
            // the first eligible batch has started the active clock.
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            awaitState(controller) {
                it.guided.phase == BrainGuidedPhase.RESULTS &&
                    it.comparison != null &&
                    it.guided.candidateCheckpointId != null
            }
            assertEquals(1L, controller.state.value.guided.sessionMillis)
            assertTrue(controller.state.value.guided.activeMillis >= 1L)
            assertTrue(controller.state.value.requestedRunning.not())
        } finally {
            closeController(controller, scope)
            repository.resumeFile.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun `resource blocked guided practice does not consume active time`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val petId = "brain-guided-resource-${System.nanoTime()}"
        val repository = TrainingRepository(context, petId)
        val living = FakeLivingPolicyStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = controller(context, scope, petId, living)
        try {
            await(controller.initialize())
            await(controller.setResourceLimits(
                chargingOnly = true,
                minimumBatteryPercent = 0,
                maxThreads = 1,
                environmentCount = 1
            ))
            await(controller.startGuidedPractice())
            awaitState(controller) { it.pauseReason != null }
            assertEquals(0L, controller.state.value.guided.sessionMillis)
            assertEquals(0L, controller.state.value.guided.activeMillis)
        } finally {
            closeController(controller, scope)
            repository.resumeFile.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun `guided lesson pauses resumes and finishes through an evaluated checkpoint`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val petId = "brain-guided-${System.nanoTime()}"
        val repository = TrainingRepository(context, petId)
        val living = FakeLivingPolicyStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = controller(context, scope, petId, living)
        try {
            await(controller.initialize())
            await(controller.setResourceLimits(
                chargingOnly = false,
                minimumBatteryPercent = 0,
                maxThreads = 1,
                environmentCount = 1
            ))
            assertEquals(CurriculumLevel.VISIBLE_TARGET.id, controller.state.value.guided.curriculumId)
            await(controller.selectGuidedLesson(CurriculumLevel.RESOURCE_GATHERING.id))
            assertEquals(CurriculumLevel.RESOURCE_GATHERING.id, controller.state.value.guided.curriculumId)
            await(controller.startGuidedPractice())
            awaitState(controller) {
                it.guided.phase == BrainGuidedPhase.PRACTICING &&
                    it.snapshot?.curriculum == CurriculumLevel.RESOURCE_GATHERING &&
                    (it.snapshot?.metrics?.environmentSteps ?: 0L) >= 16L
            }
            // Advanced evaluation pauses the guided clock and makes the old guided result
            // recoverable rather than leaving the lesson in a false PRACTICING state.
            await(controller.evaluate("candidate"))
            assertEquals(BrainGuidedPhase.PAUSED, controller.state.value.guided.phase)
            assertEquals("guided_evaluation_stale", controller.state.value.guided.gateFailure)
            await(controller.resumeGuidedPractice())
            awaitState(controller) { it.guided.phase == BrainGuidedPhase.PRACTICING }
            await(controller.pauseGuidedPractice())
            assertEquals(BrainGuidedPhase.PAUSED, controller.state.value.guided.phase)
            val pausedMillis = controller.state.value.guided.sessionMillis
            await(controller.resumeGuidedPractice())
            awaitState(controller) { it.guided.phase == BrainGuidedPhase.PRACTICING }
            await(controller.pauseGuidedPractice())
            assertTrue(controller.state.value.guided.sessionMillis >= pausedMillis)

            await(controller.finishGuidedPractice())
            awaitState(controller) {
                it.guided.phase == BrainGuidedPhase.RESULTS &&
                    it.comparison != null &&
                    it.guided.candidateCheckpointId != null
            }
            val checkpointId = requireNotNull(controller.state.value.guided.candidateCheckpointId)
            assertEquals(checkpointId, controller.state.value.candidateCheckpointId)
            assertTrue(repository.index().any { it.id == checkpointId && it.evaluation != null })

            val guidedBeforeRestart = controller.state.value.guided
            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val second = controller(context, secondScope, petId, living)
            try {
                await(second.initialize())
                assertEquals(BrainGuidedPhase.RESULTS, second.state.value.guided.phase)
                assertEquals(guidedBeforeRestart.evaluationPassed, second.state.value.guided.evaluationPassed)
                assertEquals(checkpointId, second.state.value.guided.candidateCheckpointId)
                assertTrue("completed guided evaluation should be restored", second.state.value.comparison != null)

                // Restoring the active policy invalidates an unapplied guided result, so Apply
                // cannot operate on a comparison made against a different reference.
                await(second.restoreAdoptedPolicy(BASELINE_LIVING_POLICY_ID))
                assertEquals(BrainGuidedPhase.PAUSED, second.state.value.guided.phase)
                assertEquals("guided_evaluation_stale", second.state.value.guided.gateFailure)
                assertNull(second.state.value.comparison)
            } finally {
                closeController(second, secondScope)
            }
        } finally {
            closeController(controller, scope)
            repository.resumeFile.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun `training age and candidate survive controller recreation and living policy restores`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val petId = "brain-lifecycle-${System.nanoTime()}"
        val repository = TrainingRepository(context, petId)
        val living = FakeLivingPolicyStore()
        var checkpointId: String?
        var candidateArtifactBeforeRestore: ByteArray?
        var candidateCheckpointBeforeRestore: ByteArray?
        var trainedUpdateCount: Long
        var trainedEnvironmentSteps: Long
        var trainedCandidatePolicyVersion: String

        try {
            val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val first = controller(context, firstScope, petId, living)
            try {
            await(first.initialize())
            assertEquals(CurriculumLevel.VISIBLE_TARGET, first.state.value.snapshot?.curriculum)
            await(first.setResourceLimits(
                chargingOnly = false,
                minimumBatteryPercent = 0,
                maxThreads = 1,
                environmentCount = 1
            ))
            assertEquals(1, first.state.value.maxThreads)
            assertEquals(1, first.state.value.environmentCount)
            assertFalse(first.state.value.chargingOnly)

            await(first.start())
            awaitState(first) { (it.snapshot?.metrics?.environmentSteps ?: 0L) >= 16L }
            await(first.pause())
            val trained = requireNotNull(first.state.value.snapshot)
            trainedUpdateCount = trained.metrics.updateCount
            trainedEnvironmentSteps = trained.metrics.environmentSteps
            trainedCandidatePolicyVersion = trained.candidatePolicyVersion
            assertTrue("one real PPO update expected", trainedUpdateCount > 0L)
            assertTrue(trainedEnvironmentSteps >= 16L)
            assertTrue(trainedCandidatePolicyVersion != "candidate-0")

            await(first.saveCheckpoint())
            val savedId = requireNotNull(first.state.value.candidateCheckpointId)
            checkpointId = savedId
            val savedMetadata = first.state.value.checkpoints.first { it.id == savedId }
            assertNull(savedMetadata.parentCheckpointId)
            assertNull(savedMetadata.evaluation)
            assertEquals(
                "decomposed-v1;catalog-v1;curriculum=1;guard.socialCooldownSteps=12;" +
                    "terms=objective,needs,exploration,social,efficiency,-invalid,-danger,-repetition,-stuck",
                savedMetadata.rewardConfiguration
            )
            candidateArtifactBeforeRestore = repository.artifactFile(savedId).readBytes()
            candidateCheckpointBeforeRestore = repository.checkpointFile(savedId).readBytes()

            await(first.evaluate("candidate"))
            awaitState(first) { it.comparison != null && !it.evaluating && it.error == null }
            val evaluatedMetadata = first.state.value.checkpoints.first { it.id == savedId }
            val evaluation = requireNotNull(evaluatedMetadata.evaluation)
            assertEquals(savedId, evaluation.checkpointId)
            assertEquals(evaluatedMetadata.modelHash, evaluation.candidateArtifactHash)
            assertEquals(24, evaluation.candidate.seedCount)
            assertEquals(24, evaluation.current.seedCount)
            assertEquals("evaluation-high-bit", evaluation.holdoutDomain)
            assertNotEquals(evaluation.candidateArtifactHash, evaluation.currentArtifactHash)
            assertThrows(IllegalArgumentException::class.java) {
                repository.attachEvaluation(
                    savedId,
                    evaluation.copy(candidateArtifactHash = "stale-artifact-hash")
                )
            }
            assertEquals(evaluation, repository.index().first { it.id == savedId }.evaluation)

            // Changing the active living reference after evaluation invalidates Apply even when
            // the candidate bytes are unchanged.
            living.replaceReferenceForTest(requireNotNull(candidateArtifactBeforeRestore))
            await(first.adoptCandidate("candidate"))
            assertEquals("living_reference_changed_since_evaluation", first.state.value.error)
            await(first.restoreAdoptedPolicy(BASELINE_LIVING_POLICY_ID))
            await(first.evaluate("candidate"))
            awaitState(first) { it.comparison != null && !it.evaluating && it.error == null }
            if (!first.state.value.hasAdoptedComparisonPolicy) {
                await(first.adoptCandidate("candidate"))
                assertTrue(first.state.value.error?.startsWith("guided_gate_") == true)
                // The lifecycle test also covers restoring a living artifact without bypassing the
                // controller's new Apply gate. A real passing candidate is covered by the pure gate
                // tests; this one-batch fixture is intentionally too small to promise adoption.
                living.adopt(
                    requireNotNull(candidateArtifactBeforeRestore),
                    "curriculum=1;episodes=${trainedUpdateCount}"
                )
                await(first.restoreAdoptedPolicy("policy-v1"))
            } else {
                await(first.adoptCandidate("candidate"))
            }
            assertEquals("policy-v1", first.state.value.activePolicyId)
            assertEquals(1, first.state.value.activePolicyVersion)
            assertTrue(first.state.value.hasAdoptedComparisonPolicy)
            assertTrue(first.state.value.adoptedPolicies.any { it.id == "policy-v1" && it.active })
            } finally {
                closeController(first, firstScope)
            }

            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val second = controller(context, secondScope, petId, living)
            try {
            await(second.initialize())
            val restored = requireNotNull(second.state.value.snapshot)
            assertEquals(trainedUpdateCount, restored.metrics.updateCount)
            assertEquals(trainedEnvironmentSteps, restored.metrics.environmentSteps)
            assertEquals(trainedCandidatePolicyVersion, restored.candidatePolicyVersion)
            assertEquals("policy-v1", second.state.value.activePolicyId)
            assertEquals(1, second.state.value.activePolicyVersion)
            assertTrue(second.state.value.hasAdoptedComparisonPolicy)
            assertTrue(second.state.value.adoptedPolicies.any { it.id == "policy-v1" })

            val id = requireNotNull(checkpointId)
            assertEquals(id, second.state.value.candidateCheckpointId)
            val artifactBefore = requireNotNull(candidateArtifactBeforeRestore)
            val checkpointBefore = requireNotNull(candidateCheckpointBeforeRestore)

            await(second.restoreAdoptedPolicy(BASELINE_LIVING_POLICY_ID))
            assertEquals(BASELINE_LIVING_POLICY_ID, second.state.value.activePolicyId)
            assertEquals(0, second.state.value.activePolicyVersion)
            assertFalse(second.state.value.hasAdoptedComparisonPolicy)
            assertTrue(second.state.value.adoptedPolicies.any { it.id == BASELINE_LIVING_POLICY_ID && it.active })
            assertTrue(second.state.value.adoptedPolicies.any { it.id == "policy-v1" && !it.active })
            val baselineRestored = requireNotNull(second.state.value.snapshot)
            assertEquals(trainedUpdateCount, baselineRestored.metrics.updateCount)
            assertEquals(trainedEnvironmentSteps, baselineRestored.metrics.environmentSteps)
            assertEquals(trainedCandidatePolicyVersion, baselineRestored.candidatePolicyVersion)
            assertArrayEquals(artifactBefore, repository.artifactFile(id).readBytes())
            assertArrayEquals(checkpointBefore, repository.checkpointFile(id).readBytes())

            await(second.restoreAdoptedPolicy("policy-v1"))
            assertEquals("policy-v1", second.state.value.activePolicyId)
            assertEquals(1, second.state.value.activePolicyVersion)
            assertTrue(second.state.value.hasAdoptedComparisonPolicy)
            assertTrue(second.state.value.adoptedPolicies.any { it.id == "policy-v1" && it.active })
            val policyRestored = requireNotNull(second.state.value.snapshot)
            assertEquals(trainedUpdateCount, policyRestored.metrics.updateCount)
            assertEquals(trainedEnvironmentSteps, policyRestored.metrics.environmentSteps)
            assertEquals(trainedCandidatePolicyVersion, policyRestored.candidatePolicyVersion)
            assertArrayEquals(artifactBefore, repository.artifactFile(id).readBytes())
            assertArrayEquals(checkpointBefore, repository.checkpointFile(id).readBytes())

            // Restoring a saved checkpoint creates a new branch whose parent is that exact
            // restored ID. The relation is read back from the durable index, not inferred from
            // checkpoint ordering.
            await(second.restoreCheckpoint(id))
            assertEquals(id, second.state.value.candidateCheckpointId)
            await(second.start())
            awaitState(second) { (it.snapshot?.metrics?.environmentSteps ?: 0L) >= trainedEnvironmentSteps + 16L }
            await(second.pause())
            await(second.saveCheckpoint())
            val branchedId = requireNotNull(second.state.value.candidateCheckpointId)
            assertNotEquals(id, branchedId)
            val branched = TrainingRepository(context, petId).index().first { it.id == branchedId }
            assertEquals(id, branched.parentCheckpointId)
            } finally {
                closeController(second, secondScope)
            }
        } finally {
            repository.resumeFile.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun `legacy checkpoint indexes decode without lineage or evaluation fields`() {
        val context = RuntimeEnvironment.getApplication()
        val petId = "brain-legacy-${System.nanoTime()}"
        val repository = TrainingRepository(context, petId)
        try {
            val legacyId = "11111111-1111-1111-1111-111111111111"
            File(repository.resumeFile.parentFile, "index.json").writeText(
                "[{\"id\":\"$legacyId\",\"createdAt\":1,\"episodes\":0," +
                    "\"curriculumId\":1,\"modelHash\":\"legacy\",\"profile\":\"ECO\"}]"
            )
            val decoded = repository.index().single()
            assertEquals(legacyId, decoded.id)
            assertNull(decoded.parentCheckpointId)
            assertNull(decoded.evaluation)
        } finally {
            repository.resumeFile.parentFile?.deleteRecursively()
        }
    }

    private fun controller(
        context: Context,
        scope: CoroutineScope,
        petId: String,
        living: FakeLivingPolicyStore
    ): TamaBrainController = TamaBrainController(
        context = context,
        scope = scope,
        petId = { petId },
        adoptArtifact = living::adopt,
        currentArtifact = living::currentArtifact,
        restoreAdopted = living::restore,
        currentPolicyReference = living::reference,
        adoptedPolicyHistory = living::history
    )

    private suspend fun awaitState(
        controller: TamaBrainController,
        predicate: (BrainRuntimeState) -> Boolean
    ): BrainRuntimeState = withTimeout(60_000L) {
        controller.state.filter(predicate).first()
    }

    private suspend fun await(job: Job) {
        withTimeout(60_000L) { job.join() }
    }

    private suspend fun closeController(controller: TamaBrainController, scope: CoroutineScope) {
        val scopeJob = scope.coroutineContext[Job]
        controller.dispose()
        scope.cancel()
        if (scopeJob != null) withTimeout(5_000L) { scopeJob.join() }
    }

    private class FakeLivingPolicyStore {
        private var active = LivingPolicyReference.baseline()
        private val adopted = LinkedHashMap<String, AdoptedArtifact>()

        suspend fun adopt(bytes: ByteArray, metadata: String): String {
            require(metadata.startsWith("curriculum="))
            val version = adopted.values.maxOfOrNull { it.summary.version }?.plus(1) ?: 1
            val id = "policy-v$version"
            val copy = bytes.copyOf()
            val summary = LivingPolicySummary(
                id = id,
                version = version,
                parentId = active.id,
                modelHash = TrainingRepository.sha256(copy),
                createdAt = version.toLong(),
                active = true
            )
            adopted[id] = AdoptedArtifact(copy, summary)
            active = LivingPolicyReference(
                id = id,
                version = version,
                modelHash = summary.modelHash,
                inferenceArtifact = copy.copyOf(),
                sourceCheckpointId = null
            )
            return id
        }

        suspend fun currentArtifact(): ByteArray? = active.inferenceArtifact?.copyOf()

        suspend fun replaceReferenceForTest(bytes: ByteArray) {
            val copy = bytes.copyOf()
            active = LivingPolicyReference(
                id = "external-policy",
                version = 99,
                modelHash = TrainingRepository.sha256(copy),
                inferenceArtifact = copy
            )
        }

        suspend fun reference(): LivingPolicyReference = active.copy(
            inferenceArtifact = active.inferenceArtifact?.copyOf()
        )

        suspend fun restore(id: String) {
            if (id == BASELINE_LIVING_POLICY_ID) {
                active = LivingPolicyReference.baseline()
                return
            }
            val saved = requireNotNull(adopted[id]) { "unknown_fake_policy" }
            active = LivingPolicyReference(
                id = saved.summary.id,
                version = saved.summary.version,
                modelHash = saved.summary.modelHash,
                inferenceArtifact = saved.bytes.copyOf(),
                sourceCheckpointId = saved.summary.sourceCheckpointId
            )
        }

        suspend fun history(): List<LivingPolicySummary> = buildList {
            add(LivingPolicySummary.baseline(active.isBaseline))
            adopted.values.map { it.summary }.forEach { summary ->
                add(summary.copy(active = summary.id == active.id))
            }
        }

        private data class AdoptedArtifact(
            val bytes: ByteArray,
            val summary: LivingPolicySummary
        )
    }
}
