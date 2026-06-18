package com.example.llamadroid.service

import com.example.llamadroid.data.db.AiServerConfigEntity
import com.example.llamadroid.util.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class AiServerType(
    val id: String,
    val defaultPort: Int,
    val displayName: String,
    val emoji: String
) {
    IMAGE("image", 10101, "Image Studio", "🎨"),
    VIDEO("video", 10102, "Video Studio", "🎥"),
    WORKFLOWS("workflows", 10103, "Workflows", "⚙"),
    TTS("tts", 10104, "Voice Studio", "🔊"),
    VIDEO_UPSCALE("video_upscale", 10105, "Video Upscale", "🎬"),
    DOCS_DATASETS("docs_datasets", 10106, "Docs and Datasets", "📄"),
    LLAMA_CHAT("llama_chat", 10107, "Llama Chat", "🦙");

    companion object {
        fun fromId(id: String): AiServerType? = entries.firstOrNull { it.id == id }

        fun defaultConfigs(now: Long = System.currentTimeMillis()): List<AiServerConfigEntity> =
            entries.map { type ->
                AiServerConfigEntity(
                    serverType = type.id,
                    displayName = type.displayName,
                    port = type.defaultPort,
                    lanVisible = false,
                    accessMode = AiServerAccessMode.PUBLIC,
                    enabled = false,
                    createdAt = now,
                    updatedAt = now
                )
            }
    }
}

object AiServerAccessMode {
    const val PUBLIC = "PUBLIC"
    const val USERS = "USERS"
}

object AiServerArtifactTypes {
    const val IMAGE = "IMAGE"
    const val VIDEO = "VIDEO"
    const val AUDIO = "AUDIO"
    const val DOCUMENT = "DOCUMENT"
    const val DATASET = "DATASET"
}

data class AiServerRuntimeState(
    val serverType: String,
    val running: Boolean,
    val port: Int,
    val lanVisible: Boolean,
    val urls: List<Pair<String, String>> = emptyList(),
    val error: String? = null
)

data class AiServerJob(
    val id: String,
    val serverType: String,
    val title: String,
    val status: String,
    val progress: Float = 0f,
    val message: String = "",
    val ownerUserId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val artifactPath: String? = null,
    val action: String? = null,
    val paramsJson: String? = null,
    val errorMessage: String? = null
)

object AiServerNetwork {
    fun bindHost(lanVisible: Boolean): String = if (lanVisible) "0.0.0.0" else "127.0.0.1"

    fun isValidServerPort(port: Int): Boolean = port in 10_000..65_535

    fun portConflict(configs: List<AiServerConfigEntity>, serverType: String, port: Int): Boolean =
        configs.any { it.serverType != serverType && it.port == port }

    fun canBind(host: String, port: Int): Boolean = runCatching {
        ServerSocket(0).use { probe ->
            probe.reuseAddress = true
        }
        ServerSocket(port, 1, InetAddress.getByName(host)).use { true }
    }.getOrDefault(false)

    fun urlsFor(port: Int, lanVisible: Boolean): List<Pair<String, String>> {
        if (!lanVisible) return listOf("Local" to "http://127.0.0.1:$port")
        return localIpv4Addresses().map { (name, ip) -> name to "http://$ip:$port" }
            .ifEmpty { listOf("Local" to "http://127.0.0.1:$port") }
    }

    fun localIpv4Addresses(): List<Pair<String, String>> {
        val addresses = mutableListOf<Pair<String, String>>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val ifaceAddresses = iface.inetAddresses
                while (ifaceAddresses.hasMoreElements()) {
                    val address = ifaceAddresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: continue
                        if (ip.startsWith("169.254")) continue
                        val label = when {
                            iface.name.startsWith("wlan") -> "WiFi"
                            iface.name.startsWith("eth") -> "Ethernet"
                            iface.name.startsWith("tun") -> "VPN"
                            iface.name.startsWith("rmnet") -> "Mobile"
                            else -> iface.name
                        }
                        addresses += label to ip
                    }
                }
            }
        }
        return addresses.distinctBy { it.second }
    }
}

object AiServerAuth {
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_ITERATIONS = 120_000
    private const val HASH_BYTES = 32
    private const val SALT_BYTES = 16
    private val random = SecureRandom()

    fun createSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hashPassword(password: String, salt: String): String {
        val spec = PBEKeySpec(password.toCharArray(), decodeBase64(salt), HASH_ITERATIONS, HASH_BYTES * 8)
        val bytes = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun verifyPassword(password: String, salt: String, expectedHash: String): Boolean {
        val actual = hashPassword(password, salt)
        return constantTimeEquals(actual, expectedHash)
    }

    fun createSessionToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun tokenHash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun signToken(token: String, secret: String): String = "$token.${signature(token, secret)}"

    fun verifySignedToken(cookieValue: String?, secret: String): String? {
        val value = cookieValue?.trim().orEmpty()
        val token = value.substringBefore('.', missingDelimiterValue = "")
        val suppliedSignature = value.substringAfter('.', missingDelimiterValue = "")
        if (token.isBlank() || suppliedSignature.isBlank()) return null
        val expected = signature(token, secret)
        return token.takeIf { constantTimeEquals(suppliedSignature, expected) }
    }

    private fun signature(token: String, secret: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(token.toByteArray(Charsets.UTF_8)))
    }

    private fun decodeBase64(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val left = a.toByteArray(Charsets.UTF_8)
        val right = b.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(left, right)
    }
}

object AiServerLogStore {
    private const val MAX_LOGS = 500
    private val _logs = MutableStateFlow<Map<String, List<LogEntry>>>(emptyMap())
    val logs: StateFlow<Map<String, List<LogEntry>>> = _logs.asStateFlow()

    fun append(serverType: String, message: String) {
        val current = _logs.value
        val updated = (current[serverType].orEmpty() + LogEntry(System.currentTimeMillis(), message))
            .takeLast(MAX_LOGS)
        _logs.value = current + (serverType to updated)
    }

    fun get(serverType: String): List<LogEntry> = _logs.value[serverType].orEmpty()

    fun clear(serverType: String) {
        _logs.value = _logs.value + (serverType to emptyList())
    }
}

object AiServerJobStore {
    private const val MAX_JOBS = 300
    private val terminalStatuses = setOf("COMPLETED", "FAILED", "CANCELLED", "READY")
    private val _jobs = MutableStateFlow<Map<String, List<AiServerJob>>>(emptyMap())
    val jobs: StateFlow<Map<String, List<AiServerJob>>> = _jobs.asStateFlow()

    fun add(job: AiServerJob) {
        val current = _jobs.value
        val updated = (current[job.serverType].orEmpty() + job).takeLast(MAX_JOBS)
        _jobs.value = current + (job.serverType to updated)
        AiServerLogStore.append(job.serverType, "${job.id}: queued ${job.title}")
    }

    fun update(job: AiServerJob) {
        val current = _jobs.value
        val previous = current[job.serverType].orEmpty().firstOrNull { it.id == job.id }
        val updated = current[job.serverType].orEmpty().map {
            if (it.id == job.id) {
                job.copy(
                    updatedAt = System.currentTimeMillis(),
                    action = job.action ?: it.action,
                    paramsJson = job.paramsJson ?: it.paramsJson,
                    errorMessage = job.errorMessage ?: it.errorMessage
                )
            } else {
                it
            }
        }
        _jobs.value = current + (job.serverType to updated)
        appendProgressLog(previous, job)
    }

    fun update(serverType: String, jobId: String, transform: (AiServerJob) -> AiServerJob) {
        val current = _jobs.value
        var previous: AiServerJob? = null
        var next: AiServerJob? = null
        val updated = current[serverType].orEmpty().map {
            if (it.id == jobId) {
                previous = it
                transform(it).copy(updatedAt = System.currentTimeMillis()).also { updatedJob ->
                    next = updatedJob
                }
            } else {
                it
            }
        }
        _jobs.value = current + (serverType to updated)
        next?.let { appendProgressLog(previous, it) }
    }

    fun get(serverType: String): List<AiServerJob> = _jobs.value[serverType].orEmpty()

    fun getJob(serverType: String, jobId: String): AiServerJob? =
        _jobs.value[serverType].orEmpty().firstOrNull { it.id == jobId }

    fun remove(serverType: String, jobId: String): Boolean {
        val current = _jobs.value
        val jobs = current[serverType].orEmpty()
        val existing = jobs.firstOrNull { it.id == jobId } ?: return false
        if (!existing.isTerminal && existing.status != "QUEUED") return false
        _jobs.value = current + (serverType to jobs.filterNot { it.id == jobId })
        AiServerLogStore.append(serverType, "$jobId: removed ${existing.status.lowercase()}")
        return true
    }

    fun clearFailed(serverType: String): Int {
        val current = _jobs.value
        val jobs = current[serverType].orEmpty()
        val failed = jobs.count { it.status == "FAILED" }
        if (failed > 0) {
            _jobs.value = current + (serverType to jobs.filterNot { it.status == "FAILED" })
            AiServerLogStore.append(serverType, "Cleared $failed failed task(s)")
        }
        return failed
    }

    fun markCancelled(serverType: String, jobId: String, message: String = "Task cancelled"): Boolean {
        val existing = getJob(serverType, jobId) ?: return false
        if (existing.isTerminal) return false
        update(serverType, jobId) {
            it.copy(status = "CANCELLED", progress = 0f, message = message)
        }
        return true
    }

    val AiServerJob.isTerminal: Boolean
        get() = status in terminalStatuses

    private fun appendProgressLog(previous: AiServerJob?, next: AiServerJob) {
        val previousBucket = previous?.progress?.times(100)?.toInt()
        val nextBucket = next.progress.times(100).toInt()
        val statusChanged = previous?.status != next.status
        val messageChanged = previous?.message != next.message
        val progressChanged = previousBucket != nextBucket && next.status == "RUNNING"
        if (!statusChanged && !messageChanged && !progressChanged) return
        val percent = "${nextBucket.coerceIn(0, 100)}%"
        val message = next.message.ifBlank { next.status }
        AiServerLogStore.append(next.serverType, "${next.id}: ${next.status.lowercase()} $percent - $message")
    }
}
