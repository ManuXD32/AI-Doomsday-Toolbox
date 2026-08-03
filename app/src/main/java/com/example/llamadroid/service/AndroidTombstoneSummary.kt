package com.example.llamadroid.service

/**
 * A deliberately small, dependency-free reader for the stable parts of Android's tombstone
 * protobuf. It is best-effort only: the raw tombstone remains the forensic source of truth.
 *
 * The reader is bounded so a corrupt or future-format trace cannot amplify work in the app UI
 * process. Unknown fields are skipped and malformed input simply returns null.
 */
internal data class AndroidTombstoneSummary(
    val signal: String?,
    val signalCode: String?,
    val faultAddress: Long?,
    val cause: String?,
    val faultingThread: String?,
    val nativeFrames: List<NativeTombstoneFrame>
) {
    fun compactText(): String = buildList {
        signal?.let { add("signal=$it") }
        signalCode?.let { add("code=$it") }
        faultAddress?.let { add("fault=0x${it.toULong().toString(16)}") }
        faultingThread?.let { add("thread=$it") }
        cause?.let { add("cause=$it") }
        nativeFrames.forEachIndexed { index, frame ->
            add("frame[$index]=${frame.function ?: "?"} @ ${frame.library ?: "?"}" +
                (frame.buildId?.let { " buildId=$it" } ?: ""))
        }
    }.joinToString(" | ").take(MAX_SUMMARY_CHARS)
}

internal data class NativeTombstoneFrame(
    val function: String?,
    val library: String?,
    val buildId: String?
)

internal fun summarizeAndroidTombstone(
    trace: ByteArray,
    maxBytes: Int = MAX_TOMBSTONE_BYTES
): AndroidTombstoneSummary? {
    if (trace.isEmpty() || trace.size > maxBytes) return null
    val parser = TombstoneWireReader(trace, maxBytes)
    val root = parser.message(0, minOf(trace.size, maxBytes)) ?: return null
    val signalMessage = root.firstMessage(10)
    val signal = signalMessage?.string(2)
    val signalCode = signalMessage?.string(4)
    val faultAddress = signalMessage?.number(9)
    // Current Android tombstones encode the human-readable cause directly as field 15.
    // Retain the nested fallback for vendor/future variants seen in older fixtures.
    val cause = root.string(15) ?: root.messages(15).firstNotNullOfOrNull { it.string(1) }
    val crashTid = root.number(6)
    val thread = root.messages(16).firstOrNull { envelope ->
        envelope.number(1) == crashTid || envelope.firstMessage(2)?.number(1) == crashTid
    } ?: root.messages(16).firstOrNull()
    val details = thread?.firstMessage(2)
    val frames = details?.messages(4)
        ?.take(MAX_NATIVE_FRAMES)
        ?.map { frame ->
            NativeTombstoneFrame(
                function = frame.string(4),
                library = frame.string(6),
                buildId = frame.string(8)
            )
        }
        .orEmpty()
    val result = AndroidTombstoneSummary(
        signal = signal,
        signalCode = signalCode,
        faultAddress = faultAddress,
        cause = cause,
        faultingThread = details?.string(2),
        nativeFrames = frames
    )
    return result.takeIf {
        it.signal != null || it.signalCode != null || it.cause != null ||
            it.faultingThread != null || it.nativeFrames.isNotEmpty()
    }
}

internal fun summarizeAndroidTombstoneFile(file: java.io.File): AndroidTombstoneSummary? = runCatching {
    if (!file.isFile || file.length() <= 0L) return@runCatching null
    file.inputStream().use { input ->
        // Tombstones can contain enormous memory-map sections after the crash header. Reading a
        // fixed prefix preserves the relevant header while bounding memory and parsing work.
        val bounded = ByteArray(MAX_TOMBSTONE_BYTES)
        var count = 0
        while (count < bounded.size) {
            val read = input.read(bounded, count, bounded.size - count)
            if (read <= 0) break
            count += read
        }
        summarizeAndroidTombstone(bounded.copyOf(count))
    }
}.getOrNull()

private class TombstoneWireReader(private val bytes: ByteArray, private val cap: Int) {
    fun message(start: Int, end: Int, depth: Int = 0): WireMessage? {
        if (depth > MAX_NESTING || start < 0 || end < start || end > cap) return null
        var cursor = start
        val fields = ArrayList<WireField>()
        fun partial(): WireMessage? = fields.takeIf { it.isNotEmpty() }?.let { WireMessage(it, depth, this) }
        while (cursor < end && fields.size < MAX_FIELDS) {
            val key = readVarint(cursor, end) ?: return partial()
            cursor = key.next
            val number = (key.value ushr 3).toInt()
            if (number <= 0) return null
            when ((key.value and 7).toInt()) {
                0 -> {
                    val value = readVarint(cursor, end) ?: return partial()
                    fields += WireField(number, value.value, null)
                    cursor = value.next
                }
                1 -> {
                    if (cursor + 8 > end) return partial()
                    cursor += 8
                }
                2 -> {
                    val length = readVarint(cursor, end) ?: return partial()
                    cursor = length.next
                    val size = length.value.toInt()
                    if (size < 0 || cursor + size > end) return partial()
                    // A large unknown payload (for example memory maps) must not prevent us
                    // from reading later compact fields such as the faulting thread.
                    if (size > MAX_FIELD_BYTES) {
                        cursor += size
                        continue
                    }
                    val payload = bytes.copyOfRange(cursor, cursor + size)
                    fields += WireField(number, null, payload)
                    cursor += size
                }
                5 -> {
                    if (cursor + 4 > end) return partial()
                    cursor += 4
                }
                else -> return null
            }
        }
        return if (cursor == end) WireMessage(fields, depth, this) else null
    }

    private fun readVarint(start: Int, end: Int): Varint? {
        var cursor = start
        var shift = 0
        var value = 0L
        while (cursor < end && shift < 64) {
            val byte = bytes[cursor++].toInt() and 0xff
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return Varint(value, cursor)
            shift += 7
        }
        return null
    }
}

private data class Varint(val value: Long, val next: Int)
private data class WireField(val number: Int, val numberValue: Long?, val bytesValue: ByteArray?)
private class WireMessage(
    private val fields: List<WireField>,
    private val depth: Int,
    private val reader: TombstoneWireReader
) {
    fun number(number: Int): Long? = fields.firstOrNull { it.number == number }?.numberValue
    fun string(number: Int): String? = fields.firstOrNull { it.number == number }?.bytesValue
        ?.toString(Charsets.UTF_8)
        ?.replace(Regex("[^\\p{Print}\\s]"), "")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.take(MAX_STRING_CHARS)

    fun messages(number: Int): List<WireMessage> = fields.asSequence()
        .filter { it.number == number }
        .mapNotNull { field -> field.bytesValue?.let { reader.messageFromPayload(it, depth + 1) } }
        .toList()

    fun firstMessage(number: Int): WireMessage? = messages(number).firstOrNull()
}

private fun TombstoneWireReader.messageFromPayload(payload: ByteArray, depth: Int): WireMessage? =
    TombstoneWireReader(payload, minOf(payload.size, MAX_FIELD_BYTES)).message(0, payload.size, depth)

// Match the raw trace capture limit so a complete captured tombstone is never discarded merely
// because its later memory-map section is large. Per-field, depth, frame, and string limits still
// bound parsing and presentation.
private const val MAX_TOMBSTONE_BYTES = 8 * 1024 * 1024
private const val MAX_FIELD_BYTES = 64 * 1024
private const val MAX_FIELDS = 2_048
private const val MAX_NESTING = 16
private const val MAX_NATIVE_FRAMES = 16
private const val MAX_STRING_CHARS = 240
private const val MAX_SUMMARY_CHARS = 2_048
