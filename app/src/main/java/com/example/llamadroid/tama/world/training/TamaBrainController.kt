package com.example.llamadroid.tama.world.training

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.example.llamadroid.tama.world.policy.RecurrentPpoPolicy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android resource controls around the independent synthetic trainer. Adoption is
 * a narrow callback carrying inference bytes; the trainer never receives that
 * callback, a living engine, a DAO, or an adventure-event repository. The optional
 * living-policy callbacks carry only validated app-boundary DTOs for version/history UI.
 */
class TamaBrainController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val petId: () -> String?,
    private val adoptArtifact: suspend (ByteArray, String) -> String,
    private val currentArtifact: suspend () -> ByteArray?,
    private val restoreAdopted: suspend (String) -> Unit,
    private val currentPolicyReference: (suspend () -> LivingPolicyReference)? = null,
    private val adoptedPolicyHistory: suspend () -> List<LivingPolicySummary> = { emptyList() }
) : AutoCloseable {
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "TamaSyntheticPpo").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    private val gate = Mutex()
    private val _state = MutableStateFlow(BrainRuntimeState())
    val state: StateFlow<BrainRuntimeState> = _state.asStateFlow()
    private val preferences = context.getSharedPreferences("tama_synthetic_training", Context.MODE_PRIVATE)
    private var repository: TrainingRepository? = null
    private var trainer: PpoTrainer? = null
    private var activePetId: String? = null
    // A new trainer must perform actual PPO learning. Level 0 remains selectable as an explicit
    // validation-only mode and is retained when it was already persisted for a pet.
    private var curriculum = CurriculumLevel.VISIBLE_TARGET
    private var lastSaveAt = 0L
    private var evaluatedArtifactHash: String? = null
    private val closed = AtomicBoolean(false)
    /** Set from UI calls before their serialized executor job can reach an active evaluation. */
    private val evaluationCancelRequested = AtomicBoolean(false)
    private val worker: Job = scope.launch(dispatcher) {
        while (isActive) {
            guarded {
                if (_state.value.requestedRunning) {
                    ensureTrainer()
                    val reason = pauseReason()
                    if (reason != null) {
                        trainer?.pause()
                        _state.value = _state.value.copy(running = false, pauseReason = reason)
                    } else {
                        trainer?.resume()
                        val current = trainer ?: return@guarded
                        // A candidate comparison is valid only for an unchanged candidate. Any
                        // training update makes a previous approval stale.
                        invalidateEvaluation()
                        val snapshot = current.advance(current.config.environmentCount * current.config.rolloutStepsPerEnvironment)
                        val point = BrainMetricSample(
                            step = snapshot.metrics.environmentSteps,
                            reward = snapshot.metrics.meanEpisodeReturn,
                            success = snapshot.metrics.successRate,
                            loss = snapshot.metrics.lastPolicyLoss,
                            objectiveSteps = snapshot.metrics.objectiveSteps,
                            pathEfficiency = snapshot.metrics.pathEfficiency,
                            stuckRate = snapshot.metrics.stuckRate,
                            criticalNeedsRate = snapshot.metrics.criticalNeedsRate,
                            explorationScore = snapshot.metrics.explorationScore
                        )
                        _state.value = _state.value.copy(snapshot = snapshot, running = true, pauseReason = null,
                            error = null, history = (_state.value.history + point).takeLast(120))
                        if (System.currentTimeMillis() - lastSaveAt > 30_000L) saveResume()
                    }
                }
            }
            delay(if (_state.value.running) (1_000L / _state.value.speed).coerceAtLeast(50L) else 1_000L)
        }
    }

    init {
        worker.invokeOnCompletion {
            // The worker is the only owner of the synchronous trainer. Persist once after any
            // in-flight batch has returned, then close environments on this same executor.
            runCatching {
                trainer?.pause()
                saveResume()
            }
            runCatching { trainer?.close() }
            trainer = null
            dispatcher.close()
        }
    }

    /**
     * Hydrate the selected pet's controls, history, checkpoint list, and paused trainer state.
     * Call this from the owning UI/service lifecycle before [start] or [evaluate].
     */
    fun initialize(): Job {
        evaluationCancelRequested.set(true)
        return submit {
            ensureTrainer()
            trainer?.pause()
            trainer?.let { refreshCurrentComparisonPolicy(it) }
            evaluationCancelRequested.set(false)
            evaluatedArtifactHash = null
            _state.value = _state.value.copy(
                requestedRunning = false,
                running = false,
                evaluating = false,
                comparison = null,
                evaluatedCheckpointId = null,
                pauseReason = null,
                error = null,
                snapshot = trainer?.snapshot()
            )
            saveResume()
        }
    }

    fun start(): Job {
        evaluationCancelRequested.set(true)
        return submit {
            ensureTrainer()
            evaluationCancelRequested.set(false)
            invalidateEvaluation()
            _state.value = _state.value.copy(requestedRunning = true, running = false, pauseReason = null, error = null)
            persistUiState()
        }
    }
    fun resume() = start()
    fun pause(): Job {
        evaluationCancelRequested.set(true)
        return submit {
            trainer?.pause()
            _state.value = _state.value.copy(requestedRunning = false, running = false, pauseReason = null)
            saveResume()
            evaluationCancelRequested.set(false)
        }
    }
    fun setSpeed(value: Int) = submit {
        _state.value = _state.value.copy(speed = value.coerceIn(1, 64))
        persistUiState()
    }
    fun setChargingOnly(value: Boolean): Job = setResourceLimits(
        chargingOnly = value,
        minimumBatteryPercent = _state.value.minimumBatteryPercent,
        maxThreads = _state.value.maxThreads,
        environmentCount = _state.value.environmentCount
    )

    /**
     * Update the persisted training resource policy. Environment/vector changes replace only the
     * disposable rollout slots; the candidate policy, Adam state, RNG, and cumulative age are
     * restored from the resume checkpoint. The worker itself remains a single serialized owner;
     * [PpoTrainer] bounds environment transition workers using [maxThreads].
     */
    fun setResourceLimits(
        chargingOnly: Boolean,
        minimumBatteryPercent: Int,
        maxThreads: Int,
        environmentCount: Int
    ): Job {
        evaluationCancelRequested.set(true)
        return submit {
            try {
                ensureTrainer()
                val normalizedBattery = minimumBatteryPercent.coerceIn(0, 100)
                val normalizedThreads = maxThreads.coerceIn(MIN_TRAINING_THREADS, MAX_TRAINING_THREADS)
                val normalizedEnvironments = environmentCount.coerceIn(MIN_TRAINING_ENVIRONMENTS, MAX_TRAINING_ENVIRONMENTS)
                val previous = _state.value
                val nextProfile = when {
                    previous.profile == TrainerProfile.CUSTOM -> TrainerProfile.CUSTOM
                    previous.profile.resourceDefaults().maxThreads == normalizedThreads &&
                        previous.profile.resourceDefaults().environmentCount == normalizedEnvironments -> previous.profile
                    else -> TrainerProfile.CUSTOM
                }
                val needsReplacement = previous.maxThreads != normalizedThreads ||
                    previous.environmentCount != normalizedEnvironments
                if (needsReplacement) {
                    trainer?.pause()
                    val weights = trainer?.candidatePolicyCheckpoint()
                    // Commit the old configuration before replacing its vector slots. The new
                    // trainer then restores cumulative age and optimizer/RNG state.
                    saveResume()
                    invalidateEvaluation()
                    _state.value = previous.copy(
                        profile = nextProfile,
                        chargingOnly = chargingOnly,
                        minimumBatteryPercent = normalizedBattery,
                        maxThreads = normalizedThreads,
                        environmentCount = normalizedEnvironments,
                        requestedRunning = false,
                        running = false,
                        pauseReason = null,
                        error = null
                    )
                    replaceTrainer(weights)
                } else {
                    _state.value = previous.copy(
                        profile = nextProfile,
                        chargingOnly = chargingOnly,
                        minimumBatteryPercent = normalizedBattery,
                        maxThreads = normalizedThreads,
                        environmentCount = normalizedEnvironments
                    )
                    persistUiState()
                }
            } finally {
                evaluationCancelRequested.set(false)
            }
        }
    }
    fun selectProfile(value: TrainerProfile): Job {
        evaluationCancelRequested.set(true)
        return submit {
            ensureTrainer()
            if (_state.value.profile == value) {
                evaluationCancelRequested.set(false)
                return@submit
            }
            trainer?.pause()
            val weights = trainer?.candidatePolicyCheckpoint()
            // Save the old vector configuration before replacing its environments. The new trainer
            // will restore policies, optimizer, RNG, and metrics through loadReconfigured().
            saveResume()
            invalidateEvaluation()
            _state.value = _state.value.copy(
                profile = value,
                maxThreads = value.resourceDefaults().maxThreads.takeUnless { value == TrainerProfile.CUSTOM }
                    ?: _state.value.maxThreads,
                environmentCount = value.resourceDefaults().environmentCount.takeUnless { value == TrainerProfile.CUSTOM }
                    ?: _state.value.environmentCount,
                requestedRunning = false,
                running = false,
                pauseReason = null,
                error = null
            )
            replaceTrainer(weights)
            evaluationCancelRequested.set(false)
        }
    }
    fun selectCurriculum(id: Int): Job {
        evaluationCancelRequested.set(true)
        return submit {
            ensureTrainer()
            val nextCurriculum = CurriculumLevel.fromId(id)
            if (curriculum == nextCurriculum) {
                evaluationCancelRequested.set(false)
                return@submit
            }
            trainer?.pause()
            val weights = trainer?.candidatePolicyCheckpoint()
            saveResume()
            curriculum = nextCurriculum
            invalidateEvaluation()
            _state.value = _state.value.copy(
                requestedRunning = false,
                running = false,
                pauseReason = null,
                error = null
            )
            replaceTrainer(weights)
            evaluationCancelRequested.set(false)
        }
    }

    fun saveCheckpoint() = submit {
        ensureTrainer()
        val current = trainer ?: return@submit
        val saved = repository?.save(
            current,
            current.candidateInferenceArtifact(),
            parentCheckpointId = _state.value.candidateCheckpointId
        ) ?: return@submit
        _state.value = _state.value.copy(
            checkpoints = repository?.index().orEmpty(),
            candidateCheckpointId = saved.id
        )
        saveResume()
    }

    fun evaluate(checkpointId: String) = submit {
        ensureTrainer()
        val selected = checkpointId.takeIf { it.isNotBlank() && it != "candidate" }
        val current = trainer ?: return@submit
        current.pause()
        val initialPauseReason = pauseReason()
        if (evaluationCancelRequested.get() || initialPauseReason != null) {
            markEvaluationBlocked(initialPauseReason)
            return@submit
        }
        // A resume checkpoint may contain an older current policy than the living-world store.
        // Refresh immediately before freezing the holdout comparison so the UI and adoption hash
        // always describe the artifact the living adapter actually uses.
        refreshCurrentComparisonPolicy(current)
        // Evaluation is a frozen operation. requestedRunning must also be cleared, otherwise the
        // worker would mutate the candidate as soon as this callback returns.
        evaluatedArtifactHash = null
        _state.value = _state.value.copy(
            requestedRunning = false,
            running = false,
            evaluating = true,
            comparison = null,
            evaluatedCheckpointId = selected ?: "candidate",
            pauseReason = null,
            error = null
        )
        var evaluationPauseReason: String? = null
        var lastResourceCheckNanos = System.nanoTime()
        val shouldContinue: () -> Boolean = {
            if (evaluationCancelRequested.get()) {
                false
            } else {
                val now = System.nanoTime()
                if (now - lastResourceCheckNanos >= EVALUATION_RESOURCE_CHECK_NANOS) {
                    lastResourceCheckNanos = now
                    evaluationPauseReason = pauseReason()
                }
                evaluationPauseReason == null
            }
        }
        val evaluationTrainer = if (selected != null) {
            val saved = repository?.index()?.firstOrNull { it.id == selected } ?: error("checkpoint_missing")
            require(saved.curriculumId == current.config.curriculum.id) { "checkpoint_curriculum_mismatch" }
            val artifact = repository!!.artifactFile(selected).readBytes()
            // Parsing validates the immutable boundary and dimensions before evaluation.
            newTrainer(RecurrentPpoPolicy.fromInferenceArtifact(artifact).toByteArray())
        } else current
        try {
            if (evaluationTrainer !== current) refreshCurrentComparisonPolicy(evaluationTrainer)
            val comparison = evaluationTrainer.evaluateCurrentVsCandidate(
                HoldoutSeedPlan(current.config.seed).evaluationSeeds(24),
                shouldContinue
            )
            val finalPauseReason = evaluationPauseReason ?: pauseReason()
            if (evaluationCancelRequested.get() || finalPauseReason != null) {
                markEvaluationBlocked(finalPauseReason)
                return@submit
            }
            val evaluatedArtifact = if (selected == null) current.candidateInferenceArtifact()
            else repository!!.artifactFile(selected).readBytes()
            evaluatedArtifactHash = TrainingRepository.sha256(evaluatedArtifact)
            val comparedCurrentArtifact = evaluationTrainer.activeInferenceArtifact()
            val checkpointForEvaluation = if (selected != null) {
                repository!!.index().firstOrNull { it.id == selected }
            } else {
                // A live candidate may have advanced since its last save. Attach metadata only
                // when the saved candidate ID names the exact artifact just evaluated.
                repository?.checkpointForArtifact(
                    artifactHash = evaluatedArtifactHash!!,
                    preferredId = _state.value.candidateCheckpointId
                )
            }
            checkpointForEvaluation?.let { checkpoint ->
                repository!!.attachEvaluation(
                    checkpoint.id,
                    BrainCheckpointEvaluation(
                        checkpointId = checkpoint.id,
                        candidateArtifactHash = evaluatedArtifactHash!!,
                        currentArtifactHash = TrainingRepository.sha256(comparedCurrentArtifact),
                        candidate = comparison.candidate,
                        current = comparison.current,
                        evaluatedAt = System.currentTimeMillis()
                    )
                )
            }
            val currentSnapshot = current.snapshot()
            _state.value = _state.value.copy(
                comparison = comparison,
                evaluating = false,
                snapshot = currentSnapshot,
                evaluationHistory = (_state.value.evaluationHistory + BrainMetricSample(
                    step = currentSnapshot.metrics.environmentSteps,
                    reward = comparison.candidate.meanReturn.toFloat(),
                    success = comparison.candidate.successRate.toFloat(),
                    loss = currentSnapshot.metrics.lastPolicyLoss,
                    objectiveSteps = comparison.candidate.meanObjectiveSteps.toFloat(),
                    pathEfficiency = comparison.candidate.pathEfficiency.toFloat(),
                    stuckRate = comparison.candidate.stuckRate.toFloat(),
                    criticalNeedsRate = comparison.candidate.criticalNeedsRate.toFloat(),
                    explorationScore = comparison.candidate.explorationScore.toFloat()
                )).takeLast(120),
                checkpoints = repository?.index().orEmpty()
            )
            // The evaluation record is committed in the checkpoint index above; persist the
            // bounded presentation history alongside it for a cold controller reload.
            persistUiState()
        } catch (_: EvaluationAbortedException) {
            markEvaluationBlocked(evaluationPauseReason ?: pauseReason())
        } finally {
            if (evaluationTrainer !== current) evaluationTrainer.close()
        }
    }

    /** Called only by the explicit adoption button after the comparison is visible. */
    fun adoptCandidate(checkpointId: String) = submit {
        ensureTrainer()
        val current = trainer ?: return@submit
        val artifact = if (checkpointId.isBlank() || checkpointId == "candidate") current.candidateInferenceArtifact()
            else repository!!.artifactFile(checkpointId).readBytes()
        require(evaluatedArtifactHash == TrainingRepository.sha256(artifact)) { "evaluate_candidate_before_adoption" }
        val metadata = buildString {
            append("curriculum=${current.config.curriculum.id};episodes=${current.snapshot().metrics.episodes}")
            checkpointId.takeUnless { it.isBlank() || it == "candidate" }?.let {
                append(";sourceCheckpoint=").append(it)
            }
        }
        adoptArtifact(artifact, metadata)
        if (checkpointId.isBlank() || checkpointId == "candidate") current.adoptCandidate()
        refreshCurrentComparisonPolicy(current)
        evaluatedArtifactHash = null
        _state.value = _state.value.copy(comparison = null, evaluating = false, evaluatedCheckpointId = null)
        saveResume()
    }

    /** Restore a living adopted artifact or the deterministic baseline without resetting training. */
    fun restoreAdoptedPolicy(id: String): Job {
        evaluationCancelRequested.set(true)
        return submit {
            try {
                ensureTrainer()
                trainer?.pause()
                restoreAdoptedPolicyNow(id)
                saveResume()
            } finally {
                evaluationCancelRequested.set(false)
            }
        }
    }

    private suspend fun restoreAdoptedPolicyNow(id: String) {
        restoreAdopted(id)
        trainer?.let { refreshCurrentComparisonPolicy(it) }
        evaluatedArtifactHash = null
        _state.value = _state.value.copy(
            requestedRunning = false,
            running = false,
            evaluating = false,
            comparison = null,
            evaluatedCheckpointId = null,
            error = null,
            snapshot = trainer?.snapshot()
        )
    }

    fun restoreCheckpoint(id: String): Job {
        evaluationCancelRequested.set(true)
        return submit {
            ensureTrainer()
            val saved = repository!!.index().firstOrNull { it.id == id }
            if (saved != null) {
                curriculum = CurriculumLevel.fromId(saved.curriculumId)
                val restoredProfile = runCatching { TrainerProfile.valueOf(saved.profile) }.getOrDefault(TrainerProfile.ECO)
                _state.value = _state.value.copy(
                    profile = restoredProfile,
                    maxThreads = restoredProfile.resourceDefaults().maxThreads.takeUnless { restoredProfile == TrainerProfile.CUSTOM }
                        ?: _state.value.maxThreads,
                    environmentCount = restoredProfile.resourceDefaults().environmentCount.takeUnless { restoredProfile == TrainerProfile.CUSTOM }
                        ?: _state.value.environmentCount,
                    requestedRunning = false,
                    running = false,
                    evaluating = false,
                    comparison = null,
                    evaluatedCheckpointId = null,
                    candidateCheckpointId = saved.id,
                    error = null
                )
                evaluatedArtifactHash = null
                trainer?.close()
                trainer = newTrainer()
                trainer!!.loadReconfigured(repository!!.checkpointFile(id))
                trainer!!.pause()
                refreshCurrentComparisonPolicy(trainer!!)
                _state.value = _state.value.copy(snapshot = trainer!!.snapshot(), requestedRunning = false, running = false)
                saveResume()
            } else {
                restoreAdoptedPolicyNow(id)
                saveResume()
            }
            evaluationCancelRequested.set(false)
        }
    }

    private suspend fun ensureTrainer() {
        val id = petId() ?: error("pet_missing")
        if (id == activePetId && trainer != null) return
        trainer?.close()
        activePetId = id
        repository = TrainingRepository(context, id)
        val persisted = repository!!.loadState()
        curriculum = CurriculumLevel.fromId(
            (persisted?.curriculumId ?: preferences.getInt("$id.curriculum", 1)).coerceIn(0, 10)
        )
        val profile = runCatching {
            TrainerProfile.valueOf(persisted?.profile ?: preferences.getString("$id.profile", "ECO")!!)
        }.getOrDefault(TrainerProfile.ECO)
        val defaults = profile.resourceDefaults()
        val persistedBattery = persisted?.minimumBatteryPercent
            ?: preferences.getInt("$id.minimumBatteryPercent", 20)
        val persistedThreads = persisted?.maxThreads?.takeIf { it > 0 }
            ?: preferences.getInt("$id.maxThreads", defaults.maxThreads)
        val persistedEnvironments = persisted?.environmentCount?.takeIf { it > 0 }
            ?: preferences.getInt("$id.environmentCount", defaults.environmentCount)
        _state.value = _state.value.copy(
            profile = profile,
            chargingOnly = persisted?.chargingOnly ?: preferences.getBoolean("$id.chargingOnly", true),
            minimumBatteryPercent = persistedBattery.coerceIn(0, 100),
            maxThreads = persistedThreads.coerceIn(MIN_TRAINING_THREADS, MAX_TRAINING_THREADS),
            environmentCount = persistedEnvironments.coerceIn(MIN_TRAINING_ENVIRONMENTS, MAX_TRAINING_ENVIRONMENTS),
            speed = (persisted?.speed ?: preferences.getInt("$id.speed", 1)).coerceIn(1, 64),
            checkpoints = repository!!.index(),
            history = persisted?.history?.takeLast(120).orEmpty(),
            evaluationHistory = persisted?.evaluationHistory?.takeLast(120).orEmpty(),
            adoptedPolicyId = persisted?.adoptedPolicyId,
            adoptedTrainingCheckpointId = persisted?.adoptedTrainingCheckpointId,
            candidateCheckpointId = persisted?.candidateCheckpointId,
            hasAdoptedComparisonPolicy = false,
            requestedRunning = false,
            running = false,
            evaluating = false,
            comparison = null,
            evaluatedCheckpointId = null,
            pauseReason = null,
            error = null
        )
        trainer = newTrainer()
        if (repository!!.resumeFile.isFile) trainer!!.loadReconfigured(repository!!.resumeFile)
        trainer!!.pause()
        refreshCurrentComparisonPolicy(trainer!!)
        _state.value = _state.value.copy(snapshot = trainer!!.snapshot())
    }

    private suspend fun newTrainer(weights: ByteArray? = null): PpoTrainer {
        val config = TrainerConfig(curriculum = curriculum, profile = _state.value.profile,
            environmentCount = _state.value.environmentCount.coerceIn(MIN_TRAINING_ENVIRONMENTS, MAX_TRAINING_ENVIRONMENTS),
            maxThreads = _state.value.maxThreads.coerceIn(MIN_TRAINING_THREADS, MAX_TRAINING_THREADS),
            rolloutStepsPerEnvironment = 16,
            ppoEpochs = 1,
            gamma = 0.9f,
            gaeLambda = 0.8f,
            entropyCoefficient = 0.03f,
            learningRate = 0.0005f,
            allowTrainingOnBattery = !_state.value.chargingOnly)
        val livingReference = readLivingPolicyReference()
        val currentArtifactPolicy = livingReference.inferenceArtifact?.let {
            RecurrentPpoPolicy.fromInferenceArtifact(it)
        }
        // An absent living artifact is represented by a fresh untrained neural reference. It is
        // deliberately not the deterministic live-world A*/instinct controller and must be
        // labeled as an untrained model in the comparison UI.
        val current = currentArtifactPolicy
            ?: RecurrentPpoPolicy(seed = config.seed, policyVersion = UNTRAINED_POLICY_VERSION)
        val candidate = weights?.let(RecurrentPpoPolicy::fromByteArray)
            ?: currentArtifactPolicy?.copyPolicy()
            ?: RecurrentPpoPolicy(seed = config.seed)
        return PpoTrainer(GridNavigationEnvironmentFactory(fixedTargetForVisibleCurriculum = false), config, candidate, current)
    }

    private suspend fun replaceTrainer(weights: ByteArray?) {
        val resume = repository?.resumeFile
        trainer?.close()
        trainer = newTrainer(weights)
        if (resume?.isFile == true) trainer!!.loadReconfigured(resume)
        trainer!!.pause()
        refreshCurrentComparisonPolicy(trainer!!)
        _state.value = _state.value.copy(
            snapshot = trainer!!.snapshot(),
            requestedRunning = false,
            running = false,
            comparison = null,
            evaluatedCheckpointId = null,
            evaluating = false
        )
        saveResume()
    }

    /**
     * Re-read the sole living-world input and replace only the trainer's comparison reference.
     * Resume checkpoints restore an historical current policy; this method prevents that policy
     * from being presented as the currently adopted brain after a newer live adoption. A null
     * artifact intentionally selects a fresh untrained neural reference for honest diagnostics.
     */
    private suspend fun refreshCurrentComparisonPolicy(current: PpoTrainer) {
        val reference = readLivingPolicyReference()
        val artifact = reference.inferenceArtifact
        val policy = artifact?.let { RecurrentPpoPolicy.fromInferenceArtifact(it) }
            ?: RecurrentPpoPolicy(seed = current.config.seed, policyVersion = UNTRAINED_POLICY_VERSION)
        current.replaceCurrentPolicy(policy)
        val history = adoptedPolicyHistory().let { values ->
            if (values.any { it.id == BASELINE_LIVING_POLICY_ID }) values
            else listOf(LivingPolicySummary.baseline(reference.isBaseline)) + values
        }
        _state.value = _state.value.copy(
            activePolicyId = reference.id,
            activePolicyVersion = reference.version,
            adoptedPolicyId = reference.id.takeUnless { reference.isBaseline },
            adoptedTrainingCheckpointId = reference.sourceCheckpointId,
            adoptedPolicies = history,
            hasAdoptedComparisonPolicy = artifact != null
        )
    }

    private suspend fun readLivingPolicyReference(): LivingPolicyReference {
        currentPolicyReference?.invoke()?.let { return it }
        val artifact = currentArtifact()
        return if (artifact == null) {
            LivingPolicyReference.baseline()
        } else {
            LivingPolicyReference(
                id = _state.value.adoptedPolicyId ?: "adopted",
                version = _state.value.activePolicyVersion,
                modelHash = TrainingRepository.sha256(artifact),
                inferenceArtifact = artifact,
                sourceCheckpointId = _state.value.adoptedTrainingCheckpointId
            )
        }
    }

    private fun pauseReason(): String? {
        val power = context.getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= 29 && power?.currentThermalStatus?.let { it >= PowerManager.THERMAL_STATUS_SEVERE } == true) return "thermal"
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val percentage = battery?.let {
            it.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        } ?: 100
        if (percentage < _state.value.minimumBatteryPercent) return "battery"
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        if (_state.value.chargingOnly && !charging) return "charging"
        return null
    }

    private fun saveResume() {
        val current = trainer
        if (current != null) repository?.resumeFile?.let(current::save)
        persistUiState()
        lastSaveAt = System.currentTimeMillis()
    }

    private fun persistUiState() {
        savePreferences()
        repository?.saveState(
            BrainRuntimePersistence(
                curriculumId = curriculum.id,
                profile = _state.value.profile.name,
                chargingOnly = _state.value.chargingOnly,
                minimumBatteryPercent = _state.value.minimumBatteryPercent,
                maxThreads = _state.value.maxThreads,
                environmentCount = _state.value.environmentCount,
                speed = _state.value.speed,
                history = _state.value.history.takeLast(120),
                evaluationHistory = _state.value.evaluationHistory.takeLast(120),
                adoptedPolicyId = _state.value.adoptedPolicyId,
                adoptedTrainingCheckpointId = _state.value.adoptedTrainingCheckpointId,
                candidateCheckpointId = _state.value.candidateCheckpointId
            )
        )
    }

    private fun savePreferences() {
        val id = activePetId ?: return
        preferences.edit().putInt("$id.curriculum", curriculum.id).putString("$id.profile", _state.value.profile.name)
            .putBoolean("$id.chargingOnly", _state.value.chargingOnly)
            .putInt("$id.minimumBatteryPercent", _state.value.minimumBatteryPercent)
            .putInt("$id.maxThreads", _state.value.maxThreads)
            .putInt("$id.environmentCount", _state.value.environmentCount)
            .putInt("$id.speed", _state.value.speed).apply()
    }

    private fun invalidateEvaluation() {
        evaluatedArtifactHash = null
        _state.value = _state.value.copy(comparison = null, evaluating = false, evaluatedCheckpointId = null)
    }

    private fun markEvaluationBlocked(reason: String?) {
        evaluatedArtifactHash = null
        _state.value = _state.value.copy(
            requestedRunning = false,
            running = false,
            evaluating = false,
            comparison = null,
            evaluatedCheckpointId = null,
            pauseReason = reason,
            error = null
        )
    }

    private fun submit(block: suspend () -> Unit): Job {
        if (closed.get()) return Job().also { it.cancel() }
        return runCatching { scope.launch(dispatcher) { guarded(block) } }
            .getOrElse { Job().also { it.cancel() } }
    }

    private suspend fun guarded(block: suspend () -> Unit) = gate.withLock {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            evaluatedArtifactHash = null
            _state.value = _state.value.copy(running = false, requestedRunning = false, evaluating = false,
                comparison = null, evaluatedCheckpointId = null, error = failure.message ?: "training_failed")
        }
    }

    /** Lifecycle alias for owners that do not use [AutoCloseable.close] directly. */
    fun dispose() = close()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        evaluationCancelRequested.set(true)
        evaluatedArtifactHash = null
        _state.value = _state.value.copy(
            requestedRunning = false,
            running = false,
            evaluating = false,
            comparison = null,
            evaluatedCheckpointId = null
        )
        // Completion closes the trainer on the worker thread, after any in-flight advance has
        // finished. This avoids racing PpoTrainer's synchronous environment updates.
        worker.cancel()
    }

    private companion object {
        private const val EVALUATION_RESOURCE_CHECK_NANOS = 250_000_000L
        private const val MIN_TRAINING_THREADS = 1
        private const val MAX_TRAINING_THREADS = 8
        private const val MIN_TRAINING_ENVIRONMENTS = 1
        private const val MAX_TRAINING_ENVIRONMENTS = 16
        private const val UNTRAINED_POLICY_VERSION = "untrained-model"
    }
}
