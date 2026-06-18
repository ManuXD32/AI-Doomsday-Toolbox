package com.example.llamadroid.tama.data

import android.content.Context
import com.example.llamadroid.R
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class TamaAmbientNpcState(
    val npcId: String,
    val lineIndex: Int = 0,
    val shownAt: Long = System.currentTimeMillis()
)

data class TamaAmbientNpcDefinition(
    val id: String,
    val assetPath: String,
    val name: TamaLocalizedText,
    val lines: List<TamaLocalizedText>
)

object TamaAmbientNpcCatalog {
    private val npcs = listOf(
        TamaAmbientNpcDefinition(
            id = "farm_farmer",
            assetPath = "tama/npcs/farm_farmer.png",
            name = TamaLocalizedText("Patch", "Parche"),
            lines = listOf(
                TamaLocalizedText(
                    "The soil listens when you treat it gently.",
                    "La tierra escucha cuando la tratas con cariño."
                )
            )
        ),
        TamaAmbientNpcDefinition(
            id = "boxing_coach",
            assetPath = "tama/npcs/boxing_coach.png",
            name = TamaLocalizedText("Coach Mira", "Entrenadora Mira"),
            lines = listOf(
                TamaLocalizedText("Strong paws, soft heart. That is the trick.", "Patitas fuertes, corazón suave. Ese es el truco."),
                TamaLocalizedText("A little training turns nerves into rhythm.", "Un poco de entrenamiento convierte los nervios en ritmo."),
                TamaLocalizedText("Keep your guard up, but keep your joy higher.", "Mantén la guardia alta, pero la alegría más alta."),
                TamaLocalizedText("Every round is easier when you breathe first.", "Cada asalto es más fácil si respiras primero."),
                TamaLocalizedText("Power grows best when it still knows how to play.", "La fuerza crece mejor cuando aún sabe jugar.")
            )
        ),
        TamaAmbientNpcDefinition(
            id = "shop_seller",
            assetPath = "tama/npcs/shop_seller.png",
            name = TamaLocalizedText("Mimi", "Mimi"),
            lines = listOf(
                TamaLocalizedText(
                    "A good snack can fix half a day.",
                    "Un buen tentempié puede arreglar medio día."
                )
            )
        ),
        TamaAmbientNpcDefinition(
            id = "school_teacher",
            assetPath = "tama/npcs/school_teacher.png",
            name = TamaLocalizedText("Miss Clover", "Seño Trébol"),
            lines = listOf(
                TamaLocalizedText(
                    "Curiosity counts as homework too.",
                    "La curiosidad también cuenta como tarea."
                )
            )
        ),
        TamaAmbientNpcDefinition(
            id = "hospital_doctor",
            assetPath = "tama/npcs/hospital_doctor.png",
            name = TamaLocalizedText("Doctor Pip", "Doctora Pip"),
            lines = listOf(
                TamaLocalizedText(
                    "Rest is medicine when a day feels rough.",
                    "Descansar también es medicina cuando el día pesa."
                )
            )
        ),
        TamaAmbientNpcDefinition(
            id = "dungeon_adventurer",
            assetPath = "tama/npcs/dungeon_adventurer.png",
            name = TamaLocalizedText("Rook", "Rook"),
            lines = listOf(
                TamaLocalizedText(
                    "Bravery looks better with a plan and a snack.",
                    "La valentía luce mejor con un plan y un tentempié."
                )
            )
        ),
        TamaAmbientNpcDefinition(
            id = "arcade_host",
            assetPath = "tama/npcs/arcade_machine.png",
            name = TamaLocalizedText("Pixel Pop", "Pixel Pop"),
            lines = listOf(
                TamaLocalizedText(
                    "High scores start with confident button presses.",
                    "Los récords empiezan con botones pulsados con confianza."
                )
            )
        ),
        TamaAmbientNpcDefinition(
            id = "alchemist_keeper",
            assetPath = "tama/npcs/alchemist_keeper.png",
            name = TamaLocalizedText("Sage Wisp", "Sabia Bruma"),
            lines = listOf(
                TamaLocalizedText(
                    "Every potion is a promise to become something new.",
                    "Cada poción es una promesa de convertirse en algo nuevo."
                )
            )
        )
    )

    private val byId = npcs.associateBy { it.id }

    fun forLocation(type: LocationType): TamaAmbientNpcDefinition? = when (type) {
        LocationType.FARM -> byId["farm_farmer"]
        LocationType.SHOP -> byId["shop_seller"]
        LocationType.SCHOOL -> byId["school_teacher"]
        LocationType.BOXING_RING -> byId["boxing_coach"]
        LocationType.HOSPITAL -> byId["hospital_doctor"]
        LocationType.DUNGEON -> byId["dungeon_adventurer"]
        LocationType.ARCADE -> byId["arcade_host"]
        LocationType.ALCHEMIST -> byId["alchemist_keeper"]
        else -> null
    }

    fun byId(id: String?): TamaAmbientNpcDefinition? = id?.let(byId::get)

    fun resolveName(context: Context, npcId: String): String {
        val locale = context.resources.configuration.locales[0]
        val fallback = byId(npcId)?.name?.resolve(locale) ?: context.getString(R.string.tama_unknown_place)
        return TamaDialogTextCatalog.localizedText(context, "ambient_npc:$npcId:name", fallback)
    }

    fun resolveLine(context: Context, state: TamaAmbientNpcState): String {
        val locale = context.resources.configuration.locales[0]
        val definition = byId(state.npcId) ?: return ""
        val lineIndex = state.lineIndex.coerceAtLeast(0)
        val fallback = definition.lines.getOrElse(lineIndex) {
            definition.lines.first()
        }.resolve(locale)
        return TamaDialogTextCatalog.localizedText(context, "ambient_npc:${state.npcId}:line:$lineIndex", fallback)
    }

    fun createState(type: LocationType, now: Long = System.currentTimeMillis()): TamaAmbientNpcState? {
        val definition = forLocation(type) ?: return null
        return TamaAmbientNpcState(
            npcId = definition.id,
            lineIndex = 0,
            shownAt = now
        )
    }
}
