package com.example.llamadroid.tama.world.runtime

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.WorldCommand

/** The existing farm panel issues the same saved action intent as a world plot inspector. */
object WorldFarmActions {
    suspend fun request(
        context: Context,
        engine: TamaGameEngine,
        plotId: Int,
        action: ActionId,
        arguments: Map<String, String> = emptyMap()
    ): String {
        val result = engine.world.command(WorldCommand.PerformAction(
            action = action,
            targetId = "farm_plot_$plotId",
            arguments = arguments
        ))
        return context.getString(if (result.acceptedCommand) R.string.tama_world_runtime_farm_queued
            else R.string.tama_world_runtime_action_unavailable)
    }
}
