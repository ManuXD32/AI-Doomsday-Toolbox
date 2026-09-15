package com.example.llamadroid.tama.world.core

import kotlin.math.max

data class NeedProfile(
    val criticalThreshold: Float,
    val uncomfortableThreshold: Float,
    val desiredThreshold: Float,
    val priorityWeight: Float,
    val decayPerMinuteAwake: Float,
    val decayPerMinuteAsleep: Float = 0f
)

object NeedsSystem {
    val profiles: Map<NeedType, NeedProfile> = mapOf(
        NeedType.HUNGER to NeedProfile(15f, 35f, 75f, 1.15f, .20f, .04f),
        NeedType.HYDRATION to NeedProfile(15f, 35f, 75f, 1.20f, .28f, .05f),
        NeedType.ENERGY to NeedProfile(10f, 30f, 70f, 1.10f, .16f, -.20f),
        NeedType.HEALTH to NeedProfile(10f, 35f, 80f, 1.35f, .01f, -.02f),
        NeedType.HYGIENE to NeedProfile(15f, 40f, 75f, .65f, .09f, .03f),
        NeedType.HAPPINESS to NeedProfile(15f, 40f, 75f, .75f, .06f, .03f),
        NeedType.SOCIAL to NeedProfile(15f, 38f, 70f, .70f, .055f, .015f),
        NeedType.CURIOSITY to NeedProfile(15f, 42f, 70f, .45f, .035f, .01f)
    )

    fun decay(
        needs: NeedsProjection,
        elapsedMillis: Long,
        sleeping: Boolean = false,
        activity: PetActivity = PetActivity.NONE,
        running: Boolean = false
    ): NeedsProjection {
        if (elapsedMillis <= 0L) return needs
        val minutes = elapsedMillis.toDouble() / 60_000.0
        var result = needs
        profiles.forEach { (need, profile) ->
            var rate = if (sleeping) profile.decayPerMinuteAsleep else profile.decayPerMinuteAwake
            if (need == NeedType.ENERGY && running) rate += .22f
            if (need == NeedType.ENERGY && activity == PetActivity.WORK) rate += .08f
            if (need == NeedType.ENERGY && activity == PetActivity.TRAINING) rate += .16f
            if (need == NeedType.HAPPINESS && activity == PetActivity.RELAXING) rate -= .08f
            result = result.plus(need, -(rate * minutes.toFloat()))
        }
        if (result.hunger < profiles.getValue(NeedType.HUNGER).criticalThreshold) {
            result = result.plus(NeedType.HEALTH, -0.12f * minutes.toFloat())
        }
        if (result.hydration < profiles.getValue(NeedType.HYDRATION).criticalThreshold) {
            result = result.plus(NeedType.HEALTH, -0.16f * minutes.toFloat())
        }
        return result
    }

    fun urgency(needs: NeedsProjection, need: NeedType): Float {
        val profile = profiles.getValue(need)
        val distanceToDesired = (profile.desiredThreshold - needs.valueOf(need)).coerceAtLeast(0f)
        return (distanceToDesired / profile.desiredThreshold).coerceIn(0f, 1f) * profile.priorityWeight
    }

    fun mostUrgent(needs: NeedsProjection): NeedType =
        NeedType.entries.maxBy { urgency(needs, it) }

    fun isCritical(needs: NeedsProjection, need: NeedType): Boolean =
        needs.valueOf(need) <= profiles.getValue(need).criticalThreshold

    fun isUncomfortable(needs: NeedsProjection, need: NeedType): Boolean =
        needs.valueOf(need) <= profiles.getValue(need).uncomfortableThreshold
}

object PersonalityModifiers {
    fun goalMultiplier(personality: Personality, goal: GoalId): Float = when (personality) {
        Personality.CHEERFUL -> when (goal) {
            GoalId.SOCIALIZE, GoalId.HELP_NPC, GoalId.VISIT_FRIEND -> 1.30f
            else -> 1f
        }
        Personality.SHY -> when (goal) {
            GoalId.SOCIALIZE, GoalId.VISIT_FRIEND -> 0.70f
            GoalId.EXPLORE -> .90f
            else -> 1f
        }
        Personality.PLAYFUL -> when (goal) {
            GoalId.PLAY, GoalId.SOCIALIZE -> 1.50f
            GoalId.VISIT_INTERESTING_PLACE -> 1.20f
            else -> 1f
        }
        Personality.LAZY -> when (goal) {
            GoalId.REST, GoalId.SLEEP -> 1.40f
            GoalId.EXPLORE, GoalId.GATHER_WOOD, GoalId.GATHER_HERBS -> .70f
            else -> 1f
        }
        Personality.CURIOUS -> when (goal) {
            GoalId.EXPLORE, GoalId.INVESTIGATE, GoalId.VISIT_INTERESTING_PLACE -> 1.50f
            else -> 1f
        }
        Personality.BRAVE -> when (goal) {
            GoalId.EXPLORE, GoalId.ENTER_DUNGEON -> 1.40f
            GoalId.RETURN_HOME -> .80f
            else -> 1f
        }
    }

    fun explorationReward(personality: Personality): Float = when (personality) {
        Personality.CURIOUS -> 1.40f
        Personality.BRAVE -> 1.25f
        Personality.SHY -> .85f
        else -> 1f
    }

    fun socialReward(personality: Personality, knownFriend: Boolean): Float = when (personality) {
        Personality.SHY -> if (knownFriend) 1.20f else .70f
        Personality.CHEERFUL -> 1.30f
        Personality.PLAYFUL -> 1.20f
        else -> 1f
    }
}

object GoalSelector {
    fun choose(
        state: WorldState,
        pet: CanonicalPetSnapshot,
        current: GoalId = state.actor.goal
    ): GoalId {
        if (pet.cycleFrozen || pet.isEgg || pet.sleeping) return GoalId.IDLE
        if (NeedsSystem.isCritical(pet.needs, NeedType.HEALTH)) return GoalId.RETURN_HOME
        if (NeedsSystem.isCritical(pet.needs, NeedType.HUNGER)) return GoalId.FIND_FOOD
        if (NeedsSystem.isCritical(pet.needs, NeedType.HYDRATION)) return GoalId.FIND_WATER
        if (NeedsSystem.isCritical(pet.needs, NeedType.ENERGY)) return GoalId.RETURN_HOME

        val candidates = listOf(
            GoalId.FIND_FOOD,
            GoalId.FIND_WATER,
            GoalId.REST,
            GoalId.SOCIALIZE,
            GoalId.PLAY,
            GoalId.EXPLORE,
            GoalId.GATHER_WOOD,
            GoalId.GATHER_HERBS,
            GoalId.FARM,
            GoalId.STUDY,
            GoalId.WORK,
            GoalId.VISIT_INTERESTING_PLACE
        )
        val best = candidates.maxBy { score(state, pet, it) }
        // Keep a valid, useful goal when scores are equal or the current action
        // is already making progress. This prevents rapid goal oscillation.
        val currentScore = score(state, pet, current)
        return if (current != GoalId.IDLE && currentScore >= bestScoreWithTie(state, pet, best) - .08f) current else best
    }

    fun score(state: WorldState, pet: CanonicalPetSnapshot, goal: GoalId): Float {
        val needs = pet.needs
        val urgency = when (goal) {
            GoalId.FIND_FOOD -> NeedsSystem.urgency(needs, NeedType.HUNGER)
            GoalId.FIND_WATER -> NeedsSystem.urgency(needs, NeedType.HYDRATION)
            GoalId.REST, GoalId.SLEEP, GoalId.RETURN_HOME -> NeedsSystem.urgency(needs, NeedType.ENERGY)
            GoalId.SOCIALIZE, GoalId.VISIT_FRIEND -> NeedsSystem.urgency(needs, NeedType.SOCIAL)
            GoalId.PLAY -> NeedsSystem.urgency(needs, NeedType.HAPPINESS)
            GoalId.EXPLORE, GoalId.INVESTIGATE, GoalId.VISIT_INTERESTING_PLACE -> NeedsSystem.urgency(needs, NeedType.CURIOSITY)
            GoalId.FARM -> if (pet.autonomy.allowFarming) .25f else 0f
            GoalId.STUDY -> if (pet.autonomy.allowStudy) .18f else 0f
            GoalId.WORK -> if (pet.autonomy.allowWork) .18f else 0f
            GoalId.GATHER_WOOD, GoalId.GATHER_HERBS -> .12f
            else -> 0f
        }
        val opportunity = when (goal) {
            GoalId.FIND_FOOD -> if (state.knownResources.any { it.kind == WorldObjectType.BERRY_PATCH }) 1.15f else .65f
            GoalId.FIND_WATER -> if (state.knownPlaces.any { it.kind == "WATER" }) 1.15f else .65f
            GoalId.SOCIALIZE, GoalId.VISIT_FRIEND -> if (state.knownNpcs.isNotEmpty()) 1.10f else .55f
            GoalId.EXPLORE -> if (WorldKnowledge.nextUnknownTarget(state) != null) 1.05f else .35f
            else -> 1f
        }
        val cost = when (goal) {
            GoalId.EXPLORE, GoalId.GATHER_WOOD, GoalId.GATHER_HERBS -> .10f
            GoalId.WORK, GoalId.STUDY, GoalId.FARM -> .05f
            else -> 0f
        }
        return max(0f, urgency * PersonalityModifiers.goalMultiplier(pet.personality, goal) * opportunity - cost)
    }

    private fun bestScoreWithTie(state: WorldState, pet: CanonicalPetSnapshot, goal: GoalId): Float =
        score(state, pet, goal)
}

object SafetyInstincts {
    fun canLeaveHome(pet: CanonicalPetSnapshot): Boolean =
        !pet.isEgg && !pet.cycleFrozen && !pet.sleeping && pet.activity == PetActivity.NONE

    fun canRun(needs: NeedsProjection): Boolean =
        needs.energy > NeedsSystem.profiles.getValue(NeedType.ENERGY).criticalThreshold + 5f

    fun canEnterStructure(pet: CanonicalPetSnapshot, structure: WorldStructure): Boolean = when (structure.type) {
        StructureType.DUNGEON_A, StructureType.DUNGEON_B -> pet.autonomy.level == AutonomyLevel.FULL || pet.autonomy.allowDungeonEntry
        StructureType.ADVENTURE_GATE -> pet.autonomy.level == AutonomyLevel.FULL || pet.autonomy.allowAdventureGate
        else -> true
    }

    fun validDestination(state: WorldState, destination: WorldCoordinate, knownOnly: Boolean = false): Boolean =
        Pathfinder.walkable(state, destination, knownOnly)
}
