package com.example.llamadroid.tama.data

import java.util.UUID
import java.util.Locale

/**
 * Event log entry for LLM context.
 * All events are saved with timestamps for memory/summarization.
 */
data class TamaEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val petId: String,
    val eventType: EventType,
    val details: String,
    val locationId: String? = null,
    val npcId: String? = null,
    val statsChange: Map<String, Float>? = null  // e.g., "hunger" -> +20f
) {
    /**
     * Convert to text format for LLM context.
     */
    fun toLogString(): String {
        val time = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(timestamp))
        return "[$time] ${eventType.emoji} $details"
    }
    
    fun toJsonLine(): String {
        val statsStr = statsChange?.entries?.joinToString(", ") { "${it.key}:${it.value}" } ?: ""
        return """{"ts":$timestamp,"type":"${eventType.name}","details":"$details"${if (statsStr.isNotEmpty()) ",\"stats\":\"$statsStr\"" else ""}}"""
    }
}

enum class EventType(val emoji: String, val description: String) {
    // Care events
    FED("🍖", "Pet was fed"),
    CLEANED("🛁", "Pet was cleaned"),
    PLAYED("🎮", "Played with pet"),
    SLEPT("😴", "Pet went to sleep"),
    WOKE_UP("☀️", "Pet woke up"),
    HEALED("💊", "Pet was healed"),
    STUDIED("📚", "Pet studied"),
    RELAXED("🌳", "Pet relaxed at park"),
    POOPED("💩", "Pet pooped"),
    POOP_CLEANED("🧹", "Poop was cleaned"),
    POOP_NEGLECTED("💩", "Poop was left too long"),
    
    // Growth events
    HATCHED("🥚", "Egg hatched"),
    EVOLVED("⭐", "Pet evolved to new stage"),
    BIRTHDAY("🎂", "Pet's birthday"),
    
    // Social events
    MET_NPC("👋", "Met someone new"),
    GAVE_GIFT("🎁", "Gave a gift"),
    RECEIVED_GIFT("📦", "Received a gift"),
    MADE_FRIEND("🤝", "Made a new friend"),
    MARRIED("💒", "Got married"),
    HAD_CHILD("👶", "Had a child"),
    VISITED("🏠", "Visited someone"),
    
    // Location events
    TRAVELED("🚶", "Traveled to new location"),
    DISCOVERED("🗺️", "Discovered new area"),
    ENTERED_BUILDING("🏢", "Entered a building"),
    
    // Work/School events
    WENT_TO_SCHOOL("📚", "Went to school"),
    GRADUATED("🎓", "Graduated"),
    STARTED_WORK("💼", "Started working"),
    FINISHED_WORK("💰", "Finished work"),
    GOT_PAID("💵", "Received payment"),
    
    // Farm events
    PLANTED("🌱", "Planted seeds"),
    WATERED("💧", "Watered crops"),
    HARVESTED("🌾", "Harvested crops"),
    SOLD_CROPS("📦", "Sold crops"),
    
    // RPG events
    BATTLE_START("⚔️", "Battle started"),
    BATTLE_WON("🏆", "Won battle"),
    BATTLE_LOST("💀", "Lost battle"),
    LEVEL_UP("📈", "Leveled up"),
    FOUND_ITEM("✨", "Found an item"),
    EQUIPPED("🛡️", "Equipped item"),
    QUEST_STARTED("📜", "Started quest"),
    QUEST_COMPLETED("🎯", "Completed quest"),
    
    // Shop events
    BOUGHT("🛒", "Bought item"),
    SOLD("💸", "Sold item"),
    
    // Negative events
    GOT_SICK("🤒", "Got sick"),
    NEGLECTED("😢", "Felt neglected"),
    OVERWORKED("😩", "Was overworked"),
    
    // Owner interaction
    OWNER_RETURNED("🏠", "Owner returned"),
    OWNER_TALKED("💬", "Owner talked"),
    
    // System
    SUMMARY("📝", "Daily summary"),
    OTHER("✨", "Miscellaneous activity")
}
