package com.example.llamadroid.tama.world.policy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.sqrt

data class PolicyArchitecture(
    val inputSize: Int = PolicySpec.INPUT_SIZE,
    val recurrentUnits: Int = PolicySpec.RECURRENT_UNITS,
    val actionCount: Int = PolicySpec.ACTION_COUNT
) {
    init {
        require(inputSize > 0)
        require(recurrentUnits > 0)
        require(actionCount == PolicySpec.ACTION_COUNT) {
            "The movement intent wire contract has exactly ${PolicySpec.ACTION_COUNT} actions"
        }
    }

    val parameterCount: Int
        get() = inputSize * recurrentUnits + recurrentUnits * recurrentUnits + recurrentUnits +
            actionCount * recurrentUnits + actionCount + recurrentUnits + 1
}

data class PpoTrainingSample(
    val observation: Observation,
    val actionIndex: Int,
    val oldLogProbability: Float,
    val oldValue: Float,
    val advantage: Float,
    val returnValue: Float
) {
    init {
        require(actionIndex in 0 until PolicySpec.ACTION_COUNT)
        require(oldLogProbability.isFinite())
        require(oldValue.isFinite())
        require(advantage.isFinite())
        require(returnValue.isFinite())
    }
}

data class PpoSequence(
    val initialState: RecurrentState = RecurrentState.zero(),
    val samples: List<PpoTrainingSample>
) {
    init {
        require(samples.isNotEmpty())
    }
}

data class PpoUpdateConfig(
    val clipEpsilon: Float = 0.2f,
    val entropyCoefficient: Float = 0.01f,
    val valueCoefficient: Float = 0.5f,
    val learningRate: Float = 3e-4f,
    val maxGradientNorm: Float = 0.5f,
    val beta1: Float = 0.9f,
    val beta2: Float = 0.999f,
    val epsilon: Float = 1e-8f
) {
    init {
        require(clipEpsilon in 0f..1f)
        require(entropyCoefficient >= 0f)
        require(valueCoefficient >= 0f)
        require(learningRate > 0f)
        require(maxGradientNorm > 0f)
        require(beta1 in 0f..1f)
        require(beta2 in 0f..1f)
        require(epsilon > 0f)
    }
}

data class PpoUpdateStats(
    val policyLoss: Float,
    val valueLoss: Float,
    val entropy: Float,
    val approximateKl: Float,
    val clippedFraction: Float,
    val gradientNorm: Float,
    val optimizerStep: Long
)

data class PolicyParameterSnapshot(
    val architecture: PolicyArchitecture,
    val inputWeights: FloatArray,
    val recurrentWeights: FloatArray,
    val recurrentBias: FloatArray,
    val actorWeights: FloatArray,
    val actorBias: FloatArray,
    val valueWeights: FloatArray,
    val valueBias: Float,
    val inputMoments1: FloatArray,
    val inputMoments2: FloatArray,
    val recurrentMoments1: FloatArray,
    val recurrentMoments2: FloatArray,
    val recurrentBiasMoments1: FloatArray,
    val recurrentBiasMoments2: FloatArray,
    val actorMoments1: FloatArray,
    val actorMoments2: FloatArray,
    val actorBiasMoments1: FloatArray,
    val actorBiasMoments2: FloatArray,
    val valueMoments1: FloatArray,
    val valueMoments2: FloatArray,
    val valueBiasMoments1: Float,
    val valueBiasMoments2: Float,
    val optimizerStep: Long
)

/**
 * A small recurrent actor-critic trained with actual PPO updates.
 *
 * The recurrent cell is an Elman tanh cell. The implementation intentionally keeps the
 * architecture simple: the deterministic compact semantic encoding feeds a 64-unit recurrent
 * state followed by separate policy and value heads. With the default observation contract it
 * stays below the specification's 250k-parameter target while retaining true temporal credit
 * assignment.
 */
class RecurrentPpoPolicy(
    val architecture: PolicyArchitecture = PolicyArchitecture(),
    seed: Long = 0x4f1bbcdc5e7a91d3L,
    override val policyVersion: String = "unversioned"
) : LivingPolicy {
    private val inputWeights = FloatArray(architecture.inputSize * architecture.recurrentUnits)
    private val recurrentWeights = FloatArray(architecture.recurrentUnits * architecture.recurrentUnits)
    private val recurrentBias = FloatArray(architecture.recurrentUnits)
    private val actorWeights = FloatArray(architecture.actionCount * architecture.recurrentUnits)
    private val actorBias = FloatArray(architecture.actionCount)
    private val valueWeights = FloatArray(architecture.recurrentUnits)
    private var valueBias = 0f

    private val inputMoments1 = FloatArray(inputWeights.size)
    private val inputMoments2 = FloatArray(inputWeights.size)
    private val recurrentMoments1 = FloatArray(recurrentWeights.size)
    private val recurrentMoments2 = FloatArray(recurrentWeights.size)
    private val recurrentBiasMoments1 = FloatArray(recurrentBias.size)
    private val recurrentBiasMoments2 = FloatArray(recurrentBias.size)
    private val actorMoments1 = FloatArray(actorWeights.size)
    private val actorMoments2 = FloatArray(actorWeights.size)
    private val actorBiasMoments1 = FloatArray(actorBias.size)
    private val actorBiasMoments2 = FloatArray(actorBias.size)
    private val valueMoments1 = FloatArray(valueWeights.size)
    private val valueMoments2 = FloatArray(valueWeights.size)
    private var valueBiasMoment1 = 0f
    private var valueBiasMoment2 = 0f
    private var optimizerStep = 0L

    init {
        initialise(DeterministicRng(seed))
        require(parameterCount <= 250_000) {
            "Default policy exceeds the mobile parameter budget: $parameterCount"
        }
    }

    val parameterCount: Int
        get() = architecture.parameterCount

    val parameterBytes: Int
        get() = parameterCount * Float.SIZE_BYTES

    fun forward(observation: Observation, state: RecurrentState = RecurrentState.zero()): PolicyForward {
        require(observation.actionMask.size == architecture.actionCount)
        require(state.values.size == architecture.recurrentUnits)
        return forwardFlat(observation.encodedForPolicy(), state.values).toPublic()
    }

    override fun infer(
        observation: Observation,
        state: RecurrentState,
        deterministic: Boolean,
        rng: DeterministicRng
    ): PolicyAction {
        val result = forward(observation, state)
        val actionIndex = ActionDistribution.select(result.logits, observation.actionMask, rng, deterministic)
        return PolicyAction(
            intent = MovementIntent.fromWireId(actionIndex),
            actionIndex = actionIndex,
            logProbability = ActionDistribution.logProbability(result.logits, actionIndex, observation.actionMask),
            valueEstimate = result.value,
            nextState = result.nextState,
            logits = result.logits
        )
    }

    fun infer(
        observation: Observation,
        state: RecurrentState = RecurrentState.zero(),
        deterministic: Boolean = true
    ): PolicyAction = infer(
        observation,
        state,
        deterministic,
        DeterministicRng(0x10a4d2f63c91e8b7L)
    )

    fun copyPolicy(): RecurrentPpoPolicy {
        val copy = RecurrentPpoPolicy(architecture, 1L, policyVersion)
        copy.restore(snapshot())
        return copy
    }

    /** Export only inference weights. Adam moments never cross into the living-world boundary. */
    fun inferenceArtifact(version: String = policyVersion): RecurrentPolicyInferenceArtifact =
        RecurrentPolicyInferenceArtifact(
            architecture = architecture,
            policyVersion = version,
            inputWeights = inputWeights.copyOf(),
            recurrentWeights = recurrentWeights.copyOf(),
            recurrentBias = recurrentBias.copyOf(),
            actorWeights = actorWeights.copyOf(),
            actorBias = actorBias.copyOf(),
            valueWeights = valueWeights.copyOf(),
            valueBias = valueBias
        )

    fun snapshot(): PolicyParameterSnapshot = PolicyParameterSnapshot(
        architecture = architecture,
        inputWeights = inputWeights.copyOf(),
        recurrentWeights = recurrentWeights.copyOf(),
        recurrentBias = recurrentBias.copyOf(),
        actorWeights = actorWeights.copyOf(),
        actorBias = actorBias.copyOf(),
        valueWeights = valueWeights.copyOf(),
        valueBias = valueBias,
        inputMoments1 = inputMoments1.copyOf(),
        inputMoments2 = inputMoments2.copyOf(),
        recurrentMoments1 = recurrentMoments1.copyOf(),
        recurrentMoments2 = recurrentMoments2.copyOf(),
        recurrentBiasMoments1 = recurrentBiasMoments1.copyOf(),
        recurrentBiasMoments2 = recurrentBiasMoments2.copyOf(),
        actorMoments1 = actorMoments1.copyOf(),
        actorMoments2 = actorMoments2.copyOf(),
        actorBiasMoments1 = actorBiasMoments1.copyOf(),
        actorBiasMoments2 = actorBiasMoments2.copyOf(),
        valueMoments1 = valueMoments1.copyOf(),
        valueMoments2 = valueMoments2.copyOf(),
        valueBiasMoments1 = valueBiasMoment1,
        valueBiasMoments2 = valueBiasMoment2,
        optimizerStep = optimizerStep
    )

    fun restore(snapshot: PolicyParameterSnapshot) {
        require(snapshot.architecture == architecture) { "Policy architecture does not match checkpoint" }
        copyChecked(snapshot.inputWeights, inputWeights)
        copyChecked(snapshot.recurrentWeights, recurrentWeights)
        copyChecked(snapshot.recurrentBias, recurrentBias)
        copyChecked(snapshot.actorWeights, actorWeights)
        copyChecked(snapshot.actorBias, actorBias)
        copyChecked(snapshot.valueWeights, valueWeights)
        valueBias = snapshot.valueBias
        copyChecked(snapshot.inputMoments1, inputMoments1)
        copyChecked(snapshot.inputMoments2, inputMoments2)
        copyChecked(snapshot.recurrentMoments1, recurrentMoments1)
        copyChecked(snapshot.recurrentMoments2, recurrentMoments2)
        copyChecked(snapshot.recurrentBiasMoments1, recurrentBiasMoments1)
        copyChecked(snapshot.recurrentBiasMoments2, recurrentBiasMoments2)
        copyChecked(snapshot.actorMoments1, actorMoments1)
        copyChecked(snapshot.actorMoments2, actorMoments2)
        copyChecked(snapshot.actorBiasMoments1, actorBiasMoments1)
        copyChecked(snapshot.actorBiasMoments2, actorBiasMoments2)
        copyChecked(snapshot.valueMoments1, valueMoments1)
        copyChecked(snapshot.valueMoments2, valueMoments2)
        valueBiasMoment1 = snapshot.valueBiasMoments1
        valueBiasMoment2 = snapshot.valueBiasMoments2
        optimizerStep = snapshot.optimizerStep
    }

    /**
     * Restore only inference weights into a fresh trainer policy. Optimizer moments and the
     * optimizer step are deliberately reset because they are training state, not living state.
     */
    internal fun restoreInferenceWeights(
        inputWeights: FloatArray,
        recurrentWeights: FloatArray,
        recurrentBias: FloatArray,
        actorWeights: FloatArray,
        actorBias: FloatArray,
        valueWeights: FloatArray,
        valueBias: Float
    ) {
        copyChecked(inputWeights, this.inputWeights)
        copyChecked(recurrentWeights, this.recurrentWeights)
        copyChecked(recurrentBias, this.recurrentBias)
        copyChecked(actorWeights, this.actorWeights)
        copyChecked(actorBias, this.actorBias)
        copyChecked(valueWeights, this.valueWeights)
        require(valueBias.isFinite()) { "Inference artifact contains a non-finite value bias" }
        this.valueBias = valueBias
        inputMoments1.fill(0f)
        inputMoments2.fill(0f)
        recurrentMoments1.fill(0f)
        recurrentMoments2.fill(0f)
        recurrentBiasMoments1.fill(0f)
        recurrentBiasMoments2.fill(0f)
        actorMoments1.fill(0f)
        actorMoments2.fill(0f)
        actorBiasMoments1.fill(0f)
        actorBiasMoments2.fill(0f)
        valueMoments1.fill(0f)
        valueMoments2.fill(0f)
        valueBiasMoment1 = 0f
        valueBiasMoment2 = 0f
        optimizerStep = 0L
    }

    /** Apply one or more PPO epochs over the supplied recurrent sequences. */
    fun updatePpo(
        sequences: List<PpoSequence>,
        config: PpoUpdateConfig = PpoUpdateConfig()
    ): PpoUpdateStats {
        require(sequences.isNotEmpty())
        val gradients = calculateGradients(sequences, config)
        applyAdam(gradients, config)
        val count = sequences.sumOf { it.samples.size }.toFloat().coerceAtLeast(1f)
        return PpoUpdateStats(
            policyLoss = gradients.policyLoss / count,
            valueLoss = gradients.valueLoss / count,
            entropy = gradients.entropy / count,
            approximateKl = gradients.approximateKl / count,
            clippedFraction = gradients.clippedCount / count,
            gradientNorm = gradients.gradientNorm,
            optimizerStep = optimizerStep
        )
    }

    /**
     * Exposes the analytic one-step gradient for numerical verification and diagnostics.
     * The returned order is the same order as the parameter arrays in [snapshot].
     */
    fun gradientForSingleStep(
        sample: PpoTrainingSample,
        initialState: RecurrentState = RecurrentState.zero(),
        config: PpoUpdateConfig = PpoUpdateConfig()
    ): FloatArray {
        val gradients = calculateGradients(listOf(PpoSequence(initialState, listOf(sample))), config)
        return gradients.flatten()
    }

    fun lossForSample(
        sample: PpoTrainingSample,
        initialState: RecurrentState = RecurrentState.zero(),
        clipEpsilon: Float = 0.2f
    ): Float {
        require(clipEpsilon in 0f..1f)
        val forward = forward(sample.observation, initialState)
        val newLogProbability = ActionDistribution.logProbability(
            forward.logits,
            sample.actionIndex,
            sample.observation.actionMask
        )
        val ratio = exp((newLogProbability - sample.oldLogProbability).toDouble()).toFloat()
        val clipped = ratio.coerceIn(1f - clipEpsilon, 1f + clipEpsilon)
        val surrogate = minOf(ratio * sample.advantage, clipped * sample.advantage)
        val valueLoss = 0.5f * (forward.value - sample.returnValue) * (forward.value - sample.returnValue)
        return -surrogate + valueLoss
    }

    /** Encode weights, Adam moments, and optimizer step for a training checkpoint. */
    fun toByteArray(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output -> writeTo(output) }
        return bytes.toByteArray()
    }

    fun save(file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            DataOutputStream(output).use(::writeTo)
        }
    }

    fun writeTo(output: DataOutputStream) {
        output.write(MAGIC)
        output.writeInt(FORMAT_VERSION)
        output.writeInt(PolicySpec.VERSION)
        output.writeInt(architecture.inputSize)
        output.writeInt(architecture.recurrentUnits)
        output.writeInt(architecture.actionCount)
        output.writeUTF(policyVersion)
        writeArray(output, inputWeights)
        writeArray(output, recurrentWeights)
        writeArray(output, recurrentBias)
        writeArray(output, actorWeights)
        writeArray(output, actorBias)
        writeArray(output, valueWeights)
        output.writeFloat(valueBias)
        writeArray(output, inputMoments1)
        writeArray(output, inputMoments2)
        writeArray(output, recurrentMoments1)
        writeArray(output, recurrentMoments2)
        writeArray(output, recurrentBiasMoments1)
        writeArray(output, recurrentBiasMoments2)
        writeArray(output, actorMoments1)
        writeArray(output, actorMoments2)
        writeArray(output, actorBiasMoments1)
        writeArray(output, actorBiasMoments2)
        writeArray(output, valueMoments1)
        writeArray(output, valueMoments2)
        output.writeFloat(valueBiasMoment1)
        output.writeFloat(valueBiasMoment2)
        output.writeLong(optimizerStep)
    }

    companion object {
        private val MAGIC = byteArrayOf('A'.code.toByte(), 'D'.code.toByte(), 'T'.code.toByte(), 'P'.code.toByte(), 'P'.code.toByte(), 'O'.code.toByte(), '1'.code.toByte(), 0)
        private const val FORMAT_VERSION = 1

        fun fromByteArray(bytes: ByteArray): RecurrentPpoPolicy =
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                readFrom(input).also {
                    require(input.available() == 0) { "Policy checkpoint contains trailing data" }
                }
            }

        /** Restore inference weights into a fresh trainable policy with zeroed Adam moments. */
        fun fromInferenceArtifact(bytes: ByteArray): RecurrentPpoPolicy =
            RecurrentPolicyInferenceArtifact.fromByteArray(bytes).toTrainingPolicy()

        fun load(file: File): RecurrentPpoPolicy =
            FileInputStream(file).use { input -> DataInputStream(input).use { data -> readFrom(data) } }

        fun readFrom(input: DataInputStream): RecurrentPpoPolicy {
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            require(magic.contentEquals(MAGIC)) { "Unsupported ADT policy checkpoint" }
            require(input.readInt() == FORMAT_VERSION) { "Unsupported ADT policy checkpoint version" }
            require(input.readInt() == PolicySpec.VERSION) { "Policy checkpoint semantic schema version mismatch" }
            val architecture = PolicyArchitecture(input.readInt(), input.readInt(), input.readInt())
            val policy = RecurrentPpoPolicy(architecture, 1L, input.readUTF())
            readArray(input, policy.inputWeights)
            readArray(input, policy.recurrentWeights)
            readArray(input, policy.recurrentBias)
            readArray(input, policy.actorWeights)
            readArray(input, policy.actorBias)
            readArray(input, policy.valueWeights)
            policy.valueBias = input.readFloat()
            readArray(input, policy.inputMoments1)
            readArray(input, policy.inputMoments2)
            readArray(input, policy.recurrentMoments1)
            readArray(input, policy.recurrentMoments2)
            readArray(input, policy.recurrentBiasMoments1)
            readArray(input, policy.recurrentBiasMoments2)
            readArray(input, policy.actorMoments1)
            readArray(input, policy.actorMoments2)
            readArray(input, policy.actorBiasMoments1)
            readArray(input, policy.actorBiasMoments2)
            readArray(input, policy.valueMoments1)
            readArray(input, policy.valueMoments2)
            policy.valueBiasMoment1 = input.readFloat()
            policy.valueBiasMoment2 = input.readFloat()
            policy.optimizerStep = input.readLong()
            return policy
        }

        private fun writeArray(output: DataOutputStream, values: FloatArray) {
            output.writeInt(values.size)
            values.forEach(output::writeFloat)
        }

        private fun readArray(input: DataInputStream, destination: FloatArray) {
            require(input.readInt() == destination.size) { "Policy checkpoint array shape mismatch" }
            for (index in destination.indices) destination[index] = input.readFloat()
        }
    }

    private data class ForwardCache(
        val input: FloatArray,
        val previousState: FloatArray,
        val state: FloatArray,
        val logits: FloatArray,
        val value: Float
    ) {
        fun toPublic(): PolicyForward = PolicyForward(logits.copyOf(), value, RecurrentState(state.copyOf()))
    }

    private data class GradientAccumulator(
        val inputWeights: FloatArray,
        val recurrentWeights: FloatArray,
        val recurrentBias: FloatArray,
        val actorWeights: FloatArray,
        val actorBias: FloatArray,
        val valueWeights: FloatArray,
        var valueBias: Float,
        var policyLoss: Float,
        var valueLoss: Float,
        var entropy: Float,
        var approximateKl: Float,
        var clippedCount: Float,
        var gradientNorm: Float = 0f
    ) {
        fun flatten(): FloatArray {
            val result = FloatArray(
                inputWeights.size + recurrentWeights.size + recurrentBias.size + actorWeights.size +
                    actorBias.size + valueWeights.size + 1
            )
            var offset = 0
            fun copy(values: FloatArray) {
                values.copyInto(result, offset)
                offset += values.size
            }
            copy(inputWeights)
            copy(recurrentWeights)
            copy(recurrentBias)
            copy(actorWeights)
            copy(actorBias)
            copy(valueWeights)
            result[offset] = valueBias
            return result
        }
    }

    private fun initialise(rng: DeterministicRng) {
        fun initialiseArray(values: FloatArray, fanIn: Int, fanOut: Int) {
            val limit = sqrt(6.0 / (fanIn + fanOut).toDouble()).toFloat()
            for (index in values.indices) values[index] = (rng.nextFloat() * 2f - 1f) * limit
        }
        initialiseArray(inputWeights, architecture.inputSize, architecture.recurrentUnits)
        // The artifact keeps the full semantic input shape, while the compact deterministic
        // encoder occupies its prefix. Zeroing the tail makes the compact representation explicit
        // and prevents unused high-dimensional features from influencing early exploration.
        if (architecture.inputSize == PolicySpec.INPUT_SIZE) {
            for (unit in 0 until architecture.recurrentUnits) {
                val offset = unit * architecture.inputSize
                for (feature in PolicySpec.ENCODED_INPUT_SIZE until architecture.inputSize) {
                    inputWeights[offset + feature] = 0f
                }
            }
        }
        initialiseArray(recurrentWeights, architecture.recurrentUnits, architecture.recurrentUnits)
        initialiseArray(actorWeights, architecture.recurrentUnits, architecture.actionCount)
        initialiseArray(valueWeights, architecture.recurrentUnits, 1)
        recurrentBias.fill(0f)
        actorBias.fill(0f)
        valueBias = 0f
    }

    private fun forwardFlat(input: FloatArray, previousState: FloatArray): ForwardCache {
        require(input.size == architecture.inputSize)
        require(previousState.size == architecture.recurrentUnits)
        val state = FloatArray(architecture.recurrentUnits)
        val activeInputSize = minOf(PolicySpec.ENCODED_INPUT_SIZE, input.size)
        for (unit in state.indices) {
            var preActivation = recurrentBias[unit].toDouble()
            val inputOffset = unit * architecture.inputSize
            val recurrentOffset = unit * architecture.recurrentUnits
            for (feature in 0 until activeInputSize) preActivation += inputWeights[inputOffset + feature] * input[feature]
            for (previous in previousState.indices) preActivation += recurrentWeights[recurrentOffset + previous] * previousState[previous]
            state[unit] = kotlin.math.tanh(preActivation).toFloat()
        }
        val logits = FloatArray(architecture.actionCount)
        for (action in logits.indices) {
            var logit = actorBias[action].toDouble()
            val actorOffset = action * architecture.recurrentUnits
            for (unit in state.indices) logit += actorWeights[actorOffset + unit] * state[unit]
            logits[action] = logit.toFloat()
        }
        var value = valueBias.toDouble()
        for (unit in state.indices) value += valueWeights[unit] * state[unit]
        return ForwardCache(input.copyOf(), previousState.copyOf(), state, logits, value.toFloat())
    }

    private fun calculateGradients(
        sequences: List<PpoSequence>,
        config: PpoUpdateConfig
    ): GradientAccumulator {
        val gradients = GradientAccumulator(
            inputWeights = FloatArray(inputWeights.size),
            recurrentWeights = FloatArray(recurrentWeights.size),
            recurrentBias = FloatArray(recurrentBias.size),
            actorWeights = FloatArray(actorWeights.size),
            actorBias = FloatArray(actorBias.size),
            valueWeights = FloatArray(valueWeights.size),
            valueBias = 0f,
            policyLoss = 0f,
            valueLoss = 0f,
            entropy = 0f,
            approximateKl = 0f,
            clippedCount = 0f
        )
        for (sequence in sequences) {
            val caches = ArrayList<ForwardCache>(sequence.samples.size)
            var state = sequence.initialState.values.copyOf()
            sequence.samples.forEach { sample ->
                val cache = forwardFlat(sample.observation.encodedForPolicy(), state)
                caches += cache
                state = cache.state
            }

            var futureStateGradient = FloatArray(architecture.recurrentUnits)
            for (index in sequence.samples.indices.reversed()) {
                val sample = sequence.samples[index]
                val cache = caches[index]
                val probabilities = ActionDistribution.probabilities(cache.logits, sample.observation.actionMask)
                val newLogProbability = kotlin.math.ln(probabilities[sample.actionIndex].coerceAtLeast(1.0e-12f).toDouble()).toFloat()
                val ratio = exp((newLogProbability - sample.oldLogProbability).toDouble()).toFloat().coerceIn(0f, 1.0e6f)
                val clippedRatio = ratio.coerceIn(1f - config.clipEpsilon, 1f + config.clipEpsilon)
                val unclippedSurrogate = ratio * sample.advantage
                val clippedSurrogate = clippedRatio * sample.advantage
                val useClipped = clippedSurrogate < unclippedSurrogate
                val surrogate = if (useClipped) clippedSurrogate else unclippedSurrogate
                gradients.policyLoss -= surrogate
                gradients.approximateKl += sample.oldLogProbability - newLogProbability
                if (useClipped) gradients.clippedCount += 1f

                val dLogits = FloatArray(architecture.actionCount)
                if (!useClipped) {
                    val policyCoefficient = -ratio * sample.advantage
                    for (action in dLogits.indices) {
                        val oneHotMinusProbability = (if (action == sample.actionIndex) 1f else 0f) - probabilities[action]
                        dLogits[action] = policyCoefficient * oneHotMinusProbability
                    }
                }
                val currentEntropy = ActionDistribution.entropy(cache.logits, sample.observation.actionMask)
                gradients.entropy += currentEntropy
                if (config.entropyCoefficient > 0f) {
                    // d(-H)/dz_j = p_j * (log(p_j) + H), with masked entries left at zero.
                    for (action in dLogits.indices) {
                        if (probabilities[action] > 1.0e-12f) {
                            dLogits[action] += config.entropyCoefficient * probabilities[action] *
                                (kotlin.math.ln(probabilities[action].toDouble()).toFloat() + currentEntropy)
                        }
                    }
                }

                val valueError = cache.value - sample.returnValue
                gradients.valueLoss += 0.5f * valueError * valueError
                val dValue = config.valueCoefficient * valueError
                val stateGradient = futureStateGradient.copyOf()
                for (action in dLogits.indices) {
                    val actorOffset = action * architecture.recurrentUnits
                    gradients.actorBias[action] += dLogits[action]
                    for (unit in cache.state.indices) {
                        gradients.actorWeights[actorOffset + unit] += dLogits[action] * cache.state[unit]
                        stateGradient[unit] += actorWeights[actorOffset + unit] * dLogits[action]
                    }
                }
                gradients.valueBias += dValue
                for (unit in cache.state.indices) {
                    gradients.valueWeights[unit] += dValue * cache.state[unit]
                    stateGradient[unit] += valueWeights[unit] * dValue
                }

                val preActivationGradient = FloatArray(architecture.recurrentUnits)
                for (unit in preActivationGradient.indices) {
                    preActivationGradient[unit] = stateGradient[unit] * (1f - cache.state[unit] * cache.state[unit])
                    gradients.recurrentBias[unit] += preActivationGradient[unit]
                    val inputOffset = unit * architecture.inputSize
                    val recurrentOffset = unit * architecture.recurrentUnits
                    val activeInputSize = minOf(PolicySpec.ENCODED_INPUT_SIZE, cache.input.size)
                    for (feature in 0 until activeInputSize) {
                        gradients.inputWeights[inputOffset + feature] += preActivationGradient[unit] * cache.input[feature]
                    }
                    for (previous in cache.previousState.indices) {
                        gradients.recurrentWeights[recurrentOffset + previous] += preActivationGradient[unit] * cache.previousState[previous]
                    }
                }
                futureStateGradient = FloatArray(architecture.recurrentUnits)
                for (previous in futureStateGradient.indices) {
                    var value = 0f
                    for (unit in preActivationGradient.indices) {
                        value += recurrentWeights[unit * architecture.recurrentUnits + previous] * preActivationGradient[unit]
                    }
                    futureStateGradient[previous] = value
                }
            }
        }
        var squaredNorm = 0.0
        gradients.flatten().forEach { squaredNorm += it.toDouble() * it.toDouble() }
        gradients.gradientNorm = sqrt(squaredNorm).toFloat()
        return gradients
    }

    private fun applyAdam(gradients: GradientAccumulator, config: PpoUpdateConfig) {
        val scale = if (gradients.gradientNorm > config.maxGradientNorm) {
            config.maxGradientNorm / gradients.gradientNorm
        } else {
            1f
        }
        optimizerStep += 1
        val step = optimizerStep.toDouble()
        val biasCorrection1 = 1.0 - config.beta1.toDouble().pow(step)
        val biasCorrection2 = 1.0 - config.beta2.toDouble().pow(step)

        fun update(
            parameters: FloatArray,
            gradient: FloatArray,
            moments1: FloatArray,
            moments2: FloatArray
        ) {
            for (index in parameters.indices) {
                val scaledGradient = gradient[index] * scale
                moments1[index] = config.beta1 * moments1[index] + (1f - config.beta1) * scaledGradient
                moments2[index] = config.beta2 * moments2[index] + (1f - config.beta2) * scaledGradient * scaledGradient
                val first = moments1[index] / biasCorrection1.toFloat()
                val second = moments2[index] / biasCorrection2.toFloat()
                parameters[index] -= config.learningRate * first / (sqrt(second.toDouble()).toFloat() + config.epsilon)
            }
        }

        update(inputWeights, gradients.inputWeights, inputMoments1, inputMoments2)
        update(recurrentWeights, gradients.recurrentWeights, recurrentMoments1, recurrentMoments2)
        update(recurrentBias, gradients.recurrentBias, recurrentBiasMoments1, recurrentBiasMoments2)
        update(actorWeights, gradients.actorWeights, actorMoments1, actorMoments2)
        update(actorBias, gradients.actorBias, actorBiasMoments1, actorBiasMoments2)
        update(valueWeights, gradients.valueWeights, valueMoments1, valueMoments2)
        val scaledValueGradient = gradients.valueBias * scale
        valueBiasMoment1 = config.beta1 * valueBiasMoment1 + (1f - config.beta1) * scaledValueGradient
        valueBiasMoment2 = config.beta2 * valueBiasMoment2 + (1f - config.beta2) * scaledValueGradient * scaledValueGradient
        val valueFirst = valueBiasMoment1 / biasCorrection1.toFloat()
        val valueSecond = valueBiasMoment2 / biasCorrection2.toFloat()
        valueBias -= config.learningRate * valueFirst / (sqrt(valueSecond.toDouble()).toFloat() + config.epsilon)
    }

    private fun copyChecked(source: FloatArray, destination: FloatArray) {
        require(source.size == destination.size) { "Policy checkpoint array shape mismatch" }
        source.copyInto(destination)
    }

    private fun Double.pow(exponent: Double): Double = kotlin.math.exp(exponent * kotlin.math.ln(this))
}

/**
 * Immutable inference-only policy artifact. It contains weights and schema dimensions, but no
 * optimizer state or mutable training buffers, keeping the living artifact below one megabyte.
 */
class RecurrentPolicyInferenceArtifact internal constructor(
    override val policyVersion: String,
    val architecture: PolicyArchitecture,
    inputWeights: FloatArray,
    recurrentWeights: FloatArray,
    recurrentBias: FloatArray,
    actorWeights: FloatArray,
    actorBias: FloatArray,
    valueWeights: FloatArray,
    private val valueBias: Float
) : LivingPolicy {
    private val inputWeights = inputWeights.copyOf()
    private val recurrentWeights = recurrentWeights.copyOf()
    private val recurrentBias = recurrentBias.copyOf()
    private val actorWeights = actorWeights.copyOf()
    private val actorBias = actorBias.copyOf()
    private val valueWeights = valueWeights.copyOf()

    init {
        require(architecture == PolicyArchitecture()) { "Inference artifact dimensions do not match the semantic policy contract" }
        require(policyVersion.length <= MAX_POLICY_VERSION_LENGTH)
        require(inferenceParameterCount <= 250_000) { "Inference policy exceeds the mobile parameter budget" }
        require(inputWeights.size == architecture.inputSize * architecture.recurrentUnits)
        require(recurrentWeights.size == architecture.recurrentUnits * architecture.recurrentUnits)
        require(recurrentBias.size == architecture.recurrentUnits)
        require(actorWeights.size == architecture.actionCount * architecture.recurrentUnits)
        require(actorBias.size == architecture.actionCount)
        require(valueWeights.size == architecture.recurrentUnits)
        require((inputWeights + recurrentWeights + recurrentBias + actorWeights + actorBias + valueWeights).all(Float::isFinite)) {
            "Inference artifact contains non-finite weights"
        }
        require(valueBias.isFinite()) { "Inference artifact contains a non-finite value bias" }
        require(serializedSizeBytes < 1_000_000) { "Inference artifact exceeds the one megabyte boundary" }
    }

    val inferenceParameterCount: Int
        get() = architecture.parameterCount

    val serializedSizeBytes: Int
        get() = toByteArray().size

    override fun infer(
        observation: Observation,
        state: RecurrentState,
        deterministic: Boolean,
        rng: DeterministicRng
    ): PolicyAction {
        require(observation.actionMask.size == architecture.actionCount)
        require(state.values.size == architecture.recurrentUnits)
        val (nextState, logits, value) = forward(observation.encodedForPolicy(), state.values)
        val actionIndex = ActionDistribution.select(logits, observation.actionMask, rng, deterministic)
        return PolicyAction(
            intent = MovementIntent.fromWireId(actionIndex),
            actionIndex = actionIndex,
            logProbability = ActionDistribution.logProbability(logits, actionIndex, observation.actionMask),
            valueEstimate = value,
            nextState = RecurrentState(nextState),
            logits = logits
        )
    }

    fun infer(
        observation: Observation,
        state: RecurrentState = RecurrentState.zero(),
        deterministic: Boolean = true
    ): PolicyAction = infer(observation, state, deterministic, DeterministicRng(0x10a4d2f63c91e8b7L))

    fun toTrainingPolicy(): RecurrentPpoPolicy {
        val policy = RecurrentPpoPolicy(architecture, 1L, policyVersion)
        policy.restoreInferenceWeights(
            inputWeights,
            recurrentWeights,
            recurrentBias,
            actorWeights,
            actorBias,
            valueWeights,
            valueBias
        )
        return policy
    }

    fun toByteArray(): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { output ->
            output.write(INFERENCE_MAGIC)
            output.writeInt(INFERENCE_FORMAT_VERSION)
            output.writeInt(PolicySpec.VERSION)
            output.writeInt(architecture.inputSize)
            output.writeInt(architecture.recurrentUnits)
            output.writeInt(architecture.actionCount)
            output.writeUTF(policyVersion)
            writeArray(output, inputWeights)
            writeArray(output, recurrentWeights)
            writeArray(output, recurrentBias)
            writeArray(output, actorWeights)
            writeArray(output, actorBias)
            writeArray(output, valueWeights)
            output.writeFloat(valueBias)
        }
        val payload = body.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return payload + digest
    }

    private fun forward(input: FloatArray, previousState: FloatArray): Triple<FloatArray, FloatArray, Float> {
        val nextState = FloatArray(architecture.recurrentUnits)
        for (unit in nextState.indices) {
            var preActivation = recurrentBias[unit].toDouble()
            val inputOffset = unit * architecture.inputSize
            val recurrentOffset = unit * architecture.recurrentUnits
            for (feature in input.indices) preActivation += inputWeights[inputOffset + feature] * input[feature]
            for (previous in previousState.indices) preActivation += recurrentWeights[recurrentOffset + previous] * previousState[previous]
            nextState[unit] = kotlin.math.tanh(preActivation).toFloat()
        }
        val logits = FloatArray(architecture.actionCount)
        for (action in logits.indices) {
            var logit = actorBias[action].toDouble()
            val actorOffset = action * architecture.recurrentUnits
            for (unit in nextState.indices) logit += actorWeights[actorOffset + unit] * nextState[unit]
            logits[action] = logit.toFloat()
        }
        var value = valueBias.toDouble()
        for (unit in nextState.indices) value += valueWeights[unit] * nextState[unit]
        return Triple(nextState, logits, value.toFloat())
    }

    companion object {
        private val INFERENCE_MAGIC = byteArrayOf('A'.code.toByte(), 'D'.code.toByte(), 'T'.code.toByte(), 'I'.code.toByte(), 'N'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte(), 0)
        private const val INFERENCE_FORMAT_VERSION = 1
        private const val MAX_POLICY_VERSION_LENGTH = 128

        fun fromByteArray(bytes: ByteArray): RecurrentPolicyInferenceArtifact {
            require(bytes.size > INFERENCE_MAGIC.size + 32) { "Inference artifact is truncated" }
            val payloadSize = bytes.size - 32
            val payload = bytes.copyOfRange(0, payloadSize)
            val expectedDigest = MessageDigest.getInstance("SHA-256").digest(payload)
            val actualDigest = bytes.copyOfRange(payloadSize, bytes.size)
            require(expectedDigest.contentEquals(actualDigest)) { "Inference artifact checksum mismatch" }
            return DataInputStream(ByteArrayInputStream(payload)).use { input ->
                val magic = ByteArray(INFERENCE_MAGIC.size)
                input.readFully(magic)
                require(magic.contentEquals(INFERENCE_MAGIC)) { "Unsupported ADT inference artifact" }
                require(input.readInt() == INFERENCE_FORMAT_VERSION) { "Unsupported ADT inference artifact version" }
                require(input.readInt() == PolicySpec.VERSION) { "Inference artifact semantic schema version mismatch" }
                val architecture = PolicyArchitecture(input.readInt(), input.readInt(), input.readInt())
                require(architecture == PolicyArchitecture()) { "Inference artifact dimensions do not match the semantic policy contract" }
                val version = input.readUTF()
                require(version.length <= MAX_POLICY_VERSION_LENGTH)
                val inputWeights = readArray(input, architecture.inputSize * architecture.recurrentUnits)
                val recurrentWeights = readArray(input, architecture.recurrentUnits * architecture.recurrentUnits)
                val recurrentBias = readArray(input, architecture.recurrentUnits)
                val actorWeights = readArray(input, architecture.actionCount * architecture.recurrentUnits)
                val actorBias = readArray(input, architecture.actionCount)
                val valueWeights = readArray(input, architecture.recurrentUnits)
                val valueBias = input.readFloat()
                require(input.available() == 0) { "Inference artifact contains trailing data" }
                RecurrentPolicyInferenceArtifact(
                    policyVersion = version,
                    architecture = architecture,
                    inputWeights = inputWeights,
                    recurrentWeights = recurrentWeights,
                    recurrentBias = recurrentBias,
                    actorWeights = actorWeights,
                    actorBias = actorBias,
                    valueWeights = valueWeights,
                    valueBias = valueBias
                )
            }
        }

        private fun writeArray(output: DataOutputStream, values: FloatArray) {
            output.writeInt(values.size)
            values.forEach(output::writeFloat)
        }

        private fun readArray(input: DataInputStream, expectedSize: Int): FloatArray {
            require(input.readInt() == expectedSize) { "Inference artifact array shape mismatch" }
            return FloatArray(expectedSize) { input.readFloat() }
        }
    }
}
