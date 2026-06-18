package com.example.llamadroid.tama.data

import android.content.Context
import com.example.llamadroid.R
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable

@Serializable
data class TamaLocalizedText(
    val en: String,
    val es: String
) {
    fun resolve(locale: Locale = Locale.getDefault()): String {
        return if (locale.language.lowercase(Locale.ROOT).startsWith("es")) es else en
    }
}

@Serializable
enum class TamaParkEncounterType {
    REGULAR,
    RECYCLER,
    SELLER
}

@Serializable
enum class TamaParkEncounterPhase {
    INTRO,
    CLEANUP,
    SELLER_MARKET
}

@Serializable
data class TamaParkEncounter(
    val npcId: String,
    val type: TamaParkEncounterType,
    val phase: TamaParkEncounterPhase = TamaParkEncounterPhase.INTRO,
    val lineIndex: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val giftItemId: String? = null,
    val giftQuantity: Int = 0
)

data class TamaParkNpcDefinition(
    val id: String,
    val assetPath: String,
    val name: TamaLocalizedText,
    val lines: List<TamaLocalizedText>,
    val specialType: TamaParkEncounterType = TamaParkEncounterType.REGULAR
)

object TamaParkSocialCatalog {
    private val regularNpcs = listOf(
        TamaParkNpcDefinition(
            id = "cloud_bunny",
            assetPath = "tama/npcs/cloud_bunny.png",
            name = TamaLocalizedText("Cloud Bunny", "Conejita Nube"),
            lines = listOf(
                TamaLocalizedText("The breeze here makes every worry feel lighter.", "La brisa de aquí hace que cada preocupación pese menos."),
                TamaLocalizedText("I like counting petals instead of counting problems.", "Me gusta contar pétalos en vez de contar problemas."),
                TamaLocalizedText("If you sit still, even the birds tell you secrets.", "Si te quedas quieto, hasta los pájaros te cuentan secretos."),
                TamaLocalizedText("Today smells like grass and new beginnings.", "Hoy huele a hierba y a comienzos nuevos."),
                TamaLocalizedText("I hope your day has at least one soft moment.", "Espero que tu día tenga al menos un momento suave.")
            )
        ),
        TamaParkNpcDefinition(
            id = "puddle_duck",
            assetPath = "tama/npcs/puddle_duck.png",
            name = TamaLocalizedText("Puddle Duck", "Pato Charquito"),
            lines = listOf(
                TamaLocalizedText("A tiny puddle can still feel like an ocean adventure.", "Un charquito pequeño también puede sentirse como una aventura en el océano."),
                TamaLocalizedText("I slipped earlier, so now I walk with extra style.", "Antes me resbalé, así que ahora camino con estilo extra."),
                TamaLocalizedText("The park paths are nicer after the morning dew.", "Los caminos del parque están más bonitos después del rocío."),
                TamaLocalizedText("I would absolutely race you if I were not carrying snacks.", "Te echaría una carrera si no estuviera llevando aperitivos."),
                TamaLocalizedText("Little splashes make a day feel alive.", "Las pequeñas salpicaduras hacen que el día se sienta vivo.")
            )
        ),
        TamaParkNpcDefinition(
            id = "mint_fox",
            assetPath = "tama/npcs/mint_fox.png",
            name = TamaLocalizedText("Mint Fox", "Zorro Menta"),
            lines = listOf(
                TamaLocalizedText("I came here to think, but then the clouds distracted me.", "Vine aquí a pensar, pero luego las nubes me distrajeron."),
                TamaLocalizedText("Sometimes a quiet walk fixes more than a loud plan.", "A veces un paseo tranquilo arregla más que un plan ruidoso."),
                TamaLocalizedText("You look like someone who notices the pretty little things.", "Tienes pinta de notar las cosas bonitas y pequeñas."),
                TamaLocalizedText("I always leave the park with better ideas than I arrived with.", "Siempre salgo del parque con mejores ideas que con las que llegué."),
                TamaLocalizedText("If today felt heavy, leave a little of it under the trees.", "Si hoy se sintió pesado, deja un poquito bajo los árboles.")
            )
        ),
        TamaParkNpcDefinition(
            id = "berry_cat",
            assetPath = "tama/npcs/berry_cat.png",
            name = TamaLocalizedText("Berry Cat", "Gatita Baya"),
            lines = listOf(
                TamaLocalizedText("I brought jam sandwiches and zero regrets.", "He traído sándwiches de mermelada y cero arrepentimientos."),
                TamaLocalizedText("Parks are better when someone remembers snacks.", "Los parques son mejores cuando alguien se acuerda de los aperitivos."),
                TamaLocalizedText("The bench over there is perfect for dramatic daydreaming.", "Ese banco de allí es perfecto para soñar despierto con dramatismo."),
                TamaLocalizedText("I think flowers deserve compliments too.", "Creo que las flores también merecen cumplidos."),
                TamaLocalizedText("If your feet are tired, your heart can still stroll.", "Si tus pies están cansados, tu corazón aún puede pasear.")
            )
        ),
        TamaParkNpcDefinition(
            id = "moss_deer",
            assetPath = "tama/npcs/moss_deer.png",
            name = TamaLocalizedText("Moss Deer", "Ciervo Musgo"),
            lines = listOf(
                TamaLocalizedText("Slow days are not wasted days.", "Los días lentos no son días perdidos."),
                TamaLocalizedText("Every path here feels calmer when you stop rushing.", "Cada camino aquí se siente más tranquilo cuando dejas de correr."),
                TamaLocalizedText("I listen to the leaves when I need better advice.", "Escucho a las hojas cuando necesito mejores consejos."),
                TamaLocalizedText("The park keeps tiny bits of peace for anyone who asks nicely.", "El parque guarda trocitos de paz para quien los pida con amabilidad."),
                TamaLocalizedText("Walking gently is still moving forward.", "Caminar con suavidad también es avanzar.")
            )
        ),
        TamaParkNpcDefinition(
            id = "sun_lamb",
            assetPath = "tama/npcs/sun_lamb.png",
            name = TamaLocalizedText("Sun Lamb", "Corderita Sol"),
            lines = listOf(
                TamaLocalizedText("I hope something warm happens for you today.", "Espero que hoy te pase algo cálido."),
                TamaLocalizedText("This sunlight is too good not to share.", "Esta luz del sol es demasiado buena como para no compartirla."),
                TamaLocalizedText("Even a short rest can turn a day around.", "Incluso un descanso corto puede cambiarte el día."),
                TamaLocalizedText("I like places that feel kind before anyone speaks.", "Me gustan los lugares que se sienten amables antes de que nadie hable."),
                TamaLocalizedText("You should absolutely keep a little joy in your pocket.", "Deberías guardar un poquito de alegría en el bolsillo.")
            )
        ),
        TamaParkNpcDefinition(
            id = "acorn_mouse",
            assetPath = "tama/npcs/acorn_mouse.png",
            name = TamaLocalizedText("Acorn Mouse", "Ratón Bellota"),
            lines = listOf(
                TamaLocalizedText("I trade in tiny treasures and good moods.", "Yo comercio con tesoros diminutos y buenos humores."),
                TamaLocalizedText("Found anything interesting on the ground today?", "¿Has encontrado algo interesante en el suelo hoy?"),
                TamaLocalizedText("The best discoveries are usually small and shiny.", "Los mejores descubrimientos suelen ser pequeños y brillantes."),
                TamaLocalizedText("I always check under benches. Luck hides there.", "Siempre miro debajo de los bancos. Ahí se esconde la suerte."),
                TamaLocalizedText("A curious nose makes for a richer day.", "Una nariz curiosa hace el día más rico.")
            )
        ),
        TamaParkNpcDefinition(
            id = "ribbon_bird",
            assetPath = "tama/npcs/ribbon_bird.png",
            name = TamaLocalizedText("Ribbon Bird", "Pajarita Lazo"),
            lines = listOf(
                TamaLocalizedText("I practiced saying hello in a fancy way all morning.", "He practicado toda la mañana cómo saludar de forma elegante."),
                TamaLocalizedText("Do not underestimate the power of a cheerful greeting.", "No subestimes el poder de un saludo alegre."),
                TamaLocalizedText("A pretty day deserves a pretty little memory.", "Un día bonito merece un recuerdo bonito."),
                TamaLocalizedText("Sometimes I visit the park just to feel extra cute.", "A veces vengo al parque solo para sentirme extra mona."),
                TamaLocalizedText("If you smile first, the whole scene changes.", "Si sonríes primero, toda la escena cambia.")
            )
        ),
        TamaParkNpcDefinition(
            id = "sprout_frog",
            assetPath = "tama/npcs/sprout_frog.png",
            name = TamaLocalizedText("Sprout Frog", "Ranita Brote"),
            lines = listOf(
                TamaLocalizedText("I like places where things are still growing.", "Me gustan los lugares donde las cosas aún están creciendo."),
                TamaLocalizedText("The pond is quieter than usual today. I approve.", "El estanque está más tranquilo de lo normal hoy. Lo apruebo."),
                TamaLocalizedText("You look like someone who could grow something lovely.", "Tienes pinta de poder hacer crecer algo precioso."),
                TamaLocalizedText("A calm afternoon is a kind of treasure.", "Una tarde tranquila es una clase de tesoro."),
                TamaLocalizedText("I think patience looks really good on a day like this.", "Creo que la paciencia queda muy bien en un día como este.")
            )
        ),
        TamaParkNpcDefinition(
            id = "paper_pup",
            assetPath = "tama/npcs/paper_pup.png",
            name = TamaLocalizedText("Paper Pup", "Perrito Papel"),
            lines = listOf(
                TamaLocalizedText("I wrote down three good things about today already.", "Ya he apuntado tres cosas buenas de hoy."),
                TamaLocalizedText("If a day feels messy, I fold it into something nicer.", "Si un día se siente desordenado, lo doblo hasta convertirlo en algo más bonito."),
                TamaLocalizedText("The park is a good place to organize your heart a little.", "El parque es un buen sitio para ordenar un poco el corazón."),
                TamaLocalizedText("You do not have to solve everything before sunset.", "No tienes que resolverlo todo antes del atardecer."),
                TamaLocalizedText("I hope you leave here feeling a bit more sorted out.", "Espero que te vayas de aquí sintiéndote un poco más ordenado.")
            )
        )
    )

    val recycler = TamaParkNpcDefinition(
        id = "recycler",
        assetPath = "tama/npcs/recycler.png",
        name = TamaLocalizedText("The Recycler", "La Recicladora"),
        lines = listOf(
            TamaLocalizedText("Would you help me clean up the park today?", "¿Me ayudarías a limpiar el parque hoy?")
        ),
        specialType = TamaParkEncounterType.RECYCLER
    )

    val seller = TamaParkNpcDefinition(
        id = "seller",
        assetPath = "tama/npcs/seller.png",
        name = TamaLocalizedText("Market Seller", "Vendedora del Mercado"),
        lines = listOf(
            TamaLocalizedText("Would you like to sell some crops at my market stall?", "¿Te gustaría vender algunos cultivos en mi puesto del mercado?")
        ),
        specialType = TamaParkEncounterType.SELLER
    )

    val regularCatalog: List<TamaParkNpcDefinition> = regularNpcs
    val allNpcs: List<TamaParkNpcDefinition> = regularCatalog + listOf(recycler, seller)

    fun npcById(id: String?): TamaParkNpcDefinition? = allNpcs.firstOrNull { it.id == id }

    fun pickEncounter(
        pet: TamaPet,
        now: Long = System.currentTimeMillis(),
        randomValue: Float = kotlin.random.Random.nextFloat()
    ): TamaParkEncounter {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val monthDay = calendar.get(Calendar.DAY_OF_MONTH)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val dateKey = parkDateKey(calendar)

        val recyclerEligible = monthDay in setOf(10, 20, 30) &&
            hour in 8..16 &&
            pet.lastRecyclerEncounterDate != dateKey
        if (recyclerEligible) {
            return TamaParkEncounter(
                npcId = recycler.id,
                type = TamaParkEncounterType.RECYCLER
            )
        }

        val sellerEligible = dayOfWeek == Calendar.THURSDAY && hour in 8..17 && randomValue < 0.5f
        if (sellerEligible) {
            return TamaParkEncounter(
                npcId = seller.id,
                type = TamaParkEncounterType.SELLER
            )
        }

        val npc = regularCatalog.random()
        return TamaParkEncounter(
            npcId = npc.id,
            type = TamaParkEncounterType.REGULAR,
            lineIndex = npc.lines.indices.random()
        )
    }

    fun parkDateKey(calendar: Calendar): String {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    fun localizedName(context: Context, npcId: String): String {
        val fallback = npcById(npcId)?.name?.resolve(context.resources.configuration.locales[0])
            ?: context.getString(R.string.tama_park_unknown_friend)
        return TamaDialogTextCatalog.localizedText(context, "park_npc:$npcId:name", fallback)
    }

    fun localizedLine(context: Context, encounter: TamaParkEncounter): String {
        val locale = context.resources.configuration.locales[0]
        val npc = npcById(encounter.npcId) ?: return ""
        val lineIndex = if (encounter.type == TamaParkEncounterType.REGULAR) {
            encounter.lineIndex.coerceAtLeast(0)
        } else {
            0
        }
        val fallback = when (encounter.type) {
            TamaParkEncounterType.REGULAR -> npc.lines.getOrNull(lineIndex)?.resolve(locale)
                ?: npc.lines.firstOrNull()?.resolve(locale).orEmpty()
            TamaParkEncounterType.RECYCLER,
            TamaParkEncounterType.SELLER -> npc.lines.firstOrNull()?.resolve(locale).orEmpty()
        }
        return TamaDialogTextCatalog.localizedText(context, "park_npc:${encounter.npcId}:line:$lineIndex", fallback)
    }

    fun boostedSellerPrice(basePrice: Int): Long {
        return (basePrice * 1.25f).roundToLong().coerceAtLeast(1L)
    }
}
