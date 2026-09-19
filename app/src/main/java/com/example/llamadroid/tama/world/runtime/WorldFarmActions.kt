package com.example.llamadroid.tama.world.runtime

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.FarmActionKind
import com.example.llamadroid.tama.world.core.WorldCommand
import com.example.llamadroid.tama.world.core.WorldEffectRequest
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import com.example.llamadroid.tama.game.TamaCommitEffects
import kotlinx.coroutines.CancellationException

/** The existing farm panel issues the same saved action intent as a world plot inspector. */
object WorldFarmActions {
    suspend fun request(
        context: Context,
        engine: TamaGameEngine,
        plotId: Int,
        action: ActionId,
        arguments: Map<String, String> = emptyMap()
    ): String {
        if (!engine.isSimulatedWorldActive) {
            return try {
                engine.runClassicTransaction { database, farm ->
                    val pet = engine.pet.value ?: error("pet_missing")
                    check(!pet.cycleFrozen) { "cycle_frozen" }
                    val kind = FarmActionKind.entries.firstOrNull { it.name == action.name }
                        ?: error("action_unavailable")
                    val seed = database.worldDao().worldForPet(pet.id)?.seed ?: pet.id.hashCode().toLong()
                    CanonicalFarmTransitions.apply(context, database, farm, engine, pet.id,
                        WorldEffectRequest.FarmTransition("farm_plot_$plotId", kind, arguments["cropId"]),
                        System.currentTimeMillis(), seed)
                    TamaCommitEffects.deferOrRun("notifications:${pet.id}") {
                        TamaNotificationScheduler.scheduleForPet(context.applicationContext, pet.id)
                    }
                    context.getString(R.string.tama_classic_action_done)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                context.getString(R.string.tama_world_runtime_action_unavailable)
            }
        }
        val result = engine.world.command(WorldCommand.PerformAction(
            action = action,
            targetId = "farm_plot_$plotId",
            arguments = arguments
        ))
        return context.getString(if (result.acceptedCommand) R.string.tama_world_runtime_farm_queued
            else R.string.tama_world_runtime_action_unavailable)
    }
}
