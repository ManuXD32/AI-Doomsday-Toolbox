package com.example.llamadroid.tama.data

/** New world tools appear beside the established tools in the canonical farm store. */
object WorldToolCatalog {
    data class Tool(val id: String, val price: Int, val assetPath: String)
    val tools = listOf(
        Tool("axe", 200, "tama/world/tools/tool_axe.png"),
        Tool("pickaxe", 250, "tama/world/tools/tool_pickaxe.png")
    )
    fun price(id: String): Int? = tools.firstOrNull { it.id == id }?.price
}
