package com.example.llamadroid.util

import com.example.llamadroid.util.DebugLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for GGUF file metadata to extract model information like layer count.
 * 
 * GGUF format (v3):
 * - Magic: "GGUF" (4 bytes)
 * - Version: uint32
 * - Tensor count: uint64
 * - Metadata KV count: uint64
 * - Metadata KV pairs: (key, type, value)...
 */
object GGUFParser {
    
    private const val GGUF_MAGIC = 0x46554747 // "GGUF" in little-endian
    
    data class ModelInfo(
        val layerCount: Int,
        val contextLength: Int,
        val embeddingLength: Int,
        val architecture: String,
        val sizeLabel: String,
        val quantType: String
    )
    
    /**
     * Read basic model info from a GGUF file.
     * Returns null if parsing fails.
     */
    fun parse(ggufPath: String): ModelInfo? = readModelInfo(ggufPath)
    
    fun readModelInfo(ggufPath: String): ModelInfo? {
        val file = File(ggufPath)
        if (!file.exists() || !file.canRead()) {
            DebugLog.log("[GGUFParser] File not found or not readable: $ggufPath")
            return null
        }
        
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(8)
                
                // Read magic (4 bytes)
                raf.readFully(buffer, 0, 4)
                val magic = ByteBuffer.wrap(buffer, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (magic != GGUF_MAGIC) {
                    DebugLog.log("[GGUFParser] Invalid GGUF magic: ${magic.toString(16)}")
                    return null
                }
                
                // Read version (4 bytes)
                raf.readFully(buffer, 0, 4)
                ByteBuffer.wrap(buffer, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                
                // Read tensor count (8 bytes)
                raf.readFully(buffer, 0, 8)
                ByteBuffer.wrap(buffer, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
                
                // Read metadata KV count (8 bytes)
                raf.readFully(buffer, 0, 8)
                val kvCount = ByteBuffer.wrap(buffer, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
                
                // Parse KV pairs to find layer count
                var layerCount = 32 // Default
                var contextLength = 4096
                var embeddingLength = 4096
                var architecture = "unknown"
                var sizeLabel = ""
                var quantType = ""
                
                for (i in 0 until kvCount.toInt().coerceAtMost(100)) {
                    val key = readString(raf) ?: break
                    val valueType = readU32(raf)
                    
                    when {
                        // Match keys like "llama.block_count", "glm4.block_count", "qwen2.block_count", etc.
                        key.endsWith(".block_count") || key.contains("n_layer") || key.contains("num_hidden_layers") -> {
                            if (valueType == 4L) { // uint32
                                layerCount = readU32(raf).toInt()
                                // Add 1 for output layer (llama.cpp counts it separately: "offloaded N/M layers")
                                layerCount += 1
                            } else {
                                skipValue(raf, valueType)
                            }
                        }
                        key.endsWith(".context_length") || key.contains("n_ctx") -> {
                            if (valueType == 4L) {
                                contextLength = readU32(raf).toInt()
                            } else {
                                skipValue(raf, valueType)
                            }
                        }
                        key.endsWith(".embedding_length") -> {
                            if (valueType == 4L) {
                                embeddingLength = readU32(raf).toInt()
                            } else {
                                skipValue(raf, valueType)
                            }
                        }
                        key == "general.architecture" -> {
                            if (valueType == 8L) { // string
                                architecture = readString(raf) ?: "unknown"
                            } else {
                                skipValue(raf, valueType)
                            }
                        }
                        key == "general.size_label" -> {
                            if (valueType == 8L) {
                                sizeLabel = readString(raf) ?: ""
                            } else {
                                skipValue(raf, valueType)
                            }
                        }
                        key == "general.file_type" -> {
                            if (valueType == 4L) {
                                val ft = readU32(raf).toInt()
                                quantType = getQuantTypeName(ft)
                            } else {
                                skipValue(raf, valueType)
                            }
                        }
                        else -> {
                            skipValue(raf, valueType)
                        }
                    }
                }
                
                ModelInfo(layerCount, contextLength, embeddingLength, architecture, sizeLabel, quantType)
            }
        } catch (e: Exception) {
            DebugLog.log("[GGUFParser] Error parsing GGUF: ${e.message}")
            null
        }
    }
    
    private fun readString(raf: RandomAccessFile): String? {
        return try {
            val lenBuffer = ByteArray(8)
            raf.readFully(lenBuffer)
            val len = ByteBuffer.wrap(lenBuffer).order(ByteOrder.LITTLE_ENDIAN).long.toInt()
            if (len > 10000 || len < 0) return null // Sanity check
            val strBytes = ByteArray(len)
            raf.readFully(strBytes)
            String(strBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
    
    private fun readU32(raf: RandomAccessFile): Long {
        val buffer = ByteArray(4)
        raf.readFully(buffer)
        return ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }
    
    private fun readU64(raf: RandomAccessFile): Long {
        val buffer = ByteArray(8)
        raf.readFully(buffer)
        return ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).long
    }
    
    private fun skipValue(raf: RandomAccessFile, valueType: Long) {
        // Skip based on type
        val bytesToSkip = when (valueType.toInt()) {
            0 -> 1  // uint8
            1 -> 1  // int8
            2 -> 2  // uint16
            3 -> 2  // int16
            4 -> 4  // uint32
            5 -> 4  // int32
            6 -> 4  // float32
            7 -> 1  // bool
            8 -> {  // string - length is uint64 (8 bytes)
                val len = readU64(raf).toInt()
                raf.skipBytes(len)
                return
            }
            9 -> {  // array - element type is uint32, count is uint64
                val arrType = readU32(raf)
                val arrLen = readU64(raf).toInt()
                // Skip array elements
                for (j in 0 until arrLen.coerceAtMost(1000)) {
                    skipValue(raf, arrType)
                }
                return
            }
            10 -> 8 // uint64
            11 -> 8 // int64
            12 -> 8 // float64
            else -> 0
        }
        if (bytesToSkip > 0) {
            raf.skipBytes(bytesToSkip)
        }
    }
    
    private fun getQuantTypeName(fileType: Int): String {
        return when (fileType) {
            0 -> "F32"
            1 -> "F16"
            2 -> "Q4_0"
            3 -> "Q4_1"
            6 -> "Q5_0"
            7 -> "Q5_1"
            8 -> "Q8_0"
            9 -> "Q8_1"
            10 -> "Q2_K"
            11 -> "Q3_K_S"
            12 -> "Q3_K_M"
            13 -> "Q3_K_L"
            14 -> "Q4_K_S"
            15 -> "Q4_K_M"
            16 -> "Q5_K_S"
            17 -> "Q5_K_M"
            18 -> "Q6_K"
            else -> "Q${fileType}"
        }
    }
}
