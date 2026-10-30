package com.example.llamadroid.tama.adventure

/**
 * Pure-data narrative canon for the Nexum (Adventure Gate and LLM adventures).
 * The prose lives in docs/adventure-story-bible.md; keep both in sync.
 * Every text is stored as English + Spanish side by side in [L10n].
 */
data class L10n(val en: String, val es: String) {
    fun get(language: String): String =
        if (language.trim().lowercase().startsWith("es") && es.isNotBlank()) es else en
}

data class CompanionLore(
    val name: String,
    val species: L10n,
    val personality: L10n,
    val voiceSamples: List<L10n>,
    val runningJokes: List<L10n>,
    val arc: List<L10n>
)

data class RegentLore(
    val name: String,
    val title: L10n,
    val backstory: L10n,
    val astraHook: L10n,
    val redemptionArc: List<L10n>,
    val epilogue: L10n,
    val postGameHook: L10n
)

data class WorldLore(
    val worldId: String,
    val name: L10n,
    val mood: L10n,
    val landmarks: List<L10n>,
    val inhabitants: L10n,
    val problem: L10n,
    val mystery: L10n,
    val introBeat: L10n,
    val outroBeat: L10n,
    val journalFragments: List<L10n>
)

data class DungeonRetheme(
    val type: DungeonType,
    val title: L10n,
    val tagline: L10n,
    val stylePrompt: L10n,
    val chronicleReward: L10n
)

data class ChronicleReward(val id: String, val title: L10n, val description: L10n)

// ---- Resolved (single-language) views returned by loreFor ----

data class ResolvedCompanion(
    val name: String,
    val species: String,
    val personality: String,
    val voiceSamples: List<String>,
    val runningJokes: List<String>,
    val arc: List<String>
)

data class ResolvedRegent(
    val name: String,
    val title: String,
    val backstory: String,
    val astraHook: String,
    val redemptionArc: List<String>,
    val epilogue: String,
    val postGameHook: String
)

data class ResolvedWorld(
    val worldId: String,
    val name: String,
    val mood: String,
    val landmarks: List<String>,
    val inhabitants: String,
    val problem: String,
    val mystery: String,
    val introBeat: String,
    val outroBeat: String,
    val journalFragments: List<String>
)

data class ResolvedDungeon(
    val type: DungeonType,
    val title: String,
    val tagline: String,
    val stylePrompt: String,
    val chronicleReward: String
)

data class ResolvedChronicleReward(val id: String, val title: String, val description: String)

data class ResolvedLore(
    val language: String,
    val premise: String,
    val companion: ResolvedCompanion,
    val regent: ResolvedRegent,
    val worlds: List<ResolvedWorld>,
    val dungeons: List<ResolvedDungeon>,
    val chronicleIntro: String,
    val chronicleRewards: List<ResolvedChronicleReward>,
    val toneGuide: String
) {
    fun world(worldId: String): ResolvedWorld? = worlds.firstOrNull { it.worldId == worldId }
    fun dungeon(type: DungeonType): ResolvedDungeon? = dungeons.firstOrNull { it.type == type }
}

object AdventureLore {

    val premise = L10n(
        en = "Under every sleeping pet's room there is a door nobody built: the Gate Nexum, the crossroads " +
            "of all dreams. Its seven dreamlight keys have gone dim because a very old goodbye was never " +
            "allowed to finish. Your pet is small, brave, and curious enough to walk the seven realms, " +
            "gather the keys, and learn what the Nexum forgot: an ending is not a lock, it is a doorway " +
            "that leads somewhere warm. The story is cozy and magical, with real stakes: goodbyes hurt, " +
            "memories can fade, courage is a choice made while frightened, and friends are how you carry it.",
        es = "Bajo la habitación de cada mascota dormida hay una puerta que nadie construyó: el Nexo, " +
            "encrucijada de todos los sueños. Sus siete llaves de luz onírica se han apagado porque una " +
            "despedida muy antigua nunca pudo terminar. Tu mascota es pequeña, valiente y lo bastante " +
            "curiosa como para recorrer los siete reinos, reunir las llaves y descubrir lo que el Nexo " +
            "olvidó: un final no es un cerrojo, es una puerta que lleva a un lugar cálido. La historia es " +
            "acogedora y mágica, pero con riesgos de verdad: las despedidas duelen, los recuerdos pueden " +
            "desvanecerse, el valor es una decisión que se toma con miedo y los amigos son quienes te " +
            "ayudan a cargarlo."
    )

    val companion = CompanionLore(
        name = "Lumo",
        species = L10n(
            en = "A palm-sized dream lantern with a paper-thin glow, two stubby wings and a wick that " +
                "flickers when he is nervous. He is the last lantern the old Nexum guides carried.",
            es = "Un farolillo onírico del tamaño de una palma, de luz fina como el papel, con dos alitas " +
                "regordetas y una mecha que parpadea cuando se pone nervioso. Es el último farolillo que " +
                "llevaron los antiguos guías del Nexo."
        ),
        personality = L10n(
            en = "Cheerful, a little dramatic, endlessly loyal. Lumo overprepares, narrates his own " +
                "bravery, and hides sadness behind jokes. He is scared of the dark, which is awkward for " +
                "a lantern, and brave anyway. He never decides for the pet; he points, wonders and cheers.",
            es = "Alegre, algo dramático y leal sin límites. Lumo se prepara en exceso, narra su propia " +
                "valentía y esconde la tristeza tras las bromas. Le da miedo la oscuridad, lo cual es " +
                "incómodo para un farolillo, y aun así es valiente. Nunca decide por la mascota: señala, " +
                "se asombra y anima."
        ),
        voiceSamples = listOf(
            L10n("Okay! Plan: we walk forward, we are brave, and if we are not brave we walk forward slowly.",
                "¡Vale! Plan: avanzamos, somos valientes y, si no lo somos, avanzamos despacito."),
            L10n("That shadow is definitely nothing. A very large, very fluffy nothing. I will glow at it politely.",
                "Esa sombra no es nada. Una nada enorme y muy esponjosa. Voy a brillarle con educación."),
            L10n("I forgot something important. It is in here somewhere, behind my glow. It will come back when it is ready.",
                "Se me ha olvidado algo importante. Está por aquí, detrás de mi luz. Volverá cuando le apetezca."),
            L10n("You did that so well that my wick went all curly. Look, it is curly!",
                "Lo has hecho tan bien que se me ha rizado la mecha. ¡Mira, está rizada!"),
            L10n("Goodbyes are heavy, so we carry them together. One end each. Ready? Up we go.",
                "Las despedidas pesan, así que las llevamos entre los dos. Cada uno por un extremo. ¿A la de tres? Arriba.")
        ),
        runningJokes = listOf(
            L10n("Lumo keeps a tiny list titled Things That Are Probably Fine, and it keeps growing.",
                "Lumo lleva una listita llamada Cosas que seguramente están bien, y no deja de crecer."),
            L10n("He claims he is not afraid of the dark, then politely asks it to stand a bit further away.",
                "Dice que no tiene miedo a la oscuridad y luego le pide con educación que se aparte un poco."),
            L10n("Every realm he insists he has been there before, and every time he remembers the wrong snack.",
                "En cada reino insiste en que ya estuvo allí y siempre recuerda el tentempié equivocado."),
            L10n("His wick curls when he is happy and droops when he lies; he cannot hide either.",
                "La mecha se le riza cuando es feliz y se le aplana cuando miente; no puede disimular ninguna.")
        ),
        arc = listOf(
            L10n("Act 1 (Sproutvale, Ember): Lumo is eager and comic, claims to know the Nexum, but his memory has gaps he jokes away.",
                "Acto 1 (Brotevalle, Brasa): Lumo es entusiasta y cómico, presume de conocer el Nexo, pero tiene lagunas de memoria que esquiva con bromas."),
            L10n("Act 2 (Bubbleglass, Clockwork): he hears the name Astra and goes quiet; his glow flickers without cause.",
                "Acto 2 (Cristalburbuja, Nubengranaje): oye el nombre de Astra y se queda callado; su luz parpadea sin motivo."),
            L10n("Act 3 (Moonmoss, Frostfall): the truth returns piece by piece. Lumo was Astra's own lantern, left behind at the first frozen goodbye.",
                "Acto 3 (Musgolunar, Juguetero Escarchado): la verdad vuelve a trozos. Lumo era el farolillo de Astra, olvidado en la primera despedida congelada."),
            L10n("Finale (Starfall): Lumo asks to go first, carrying the light, and says the goodbye Astra could not. He is brave and afraid at once.",
                "Final (Estrellada): Lumo pide ir primero, con la luz en alto, y dice la despedida que Astra no pudo decir. Es valiente y tiene miedo a la vez."),
            L10n("After: Lumo stays as the pet's guide in every dream, including the LLM adventures, and finally knows what to answer when asked where he came from.",
                "Después: Lumo se queda como guía de la mascota en todos los sueños, incluidas las aventuras con IA, y por fin sabe qué responder cuando le preguntan de dónde viene.")
        )
    )

    val regent = RegentLore(
        name = "Astra Null Regent",
        title = L10n("The Regent of the Frozen Ending", "El Regente del Final Congelado"),
        backstory = L10n(
            en = "Astra was the Nexum's finest guide. Each night he lit the way for sleepy pets and led them " +
                "safely home to waking. His dearest friend was a small pet named Wren, who traveled with him " +
                "for a thousand dreams. One morning Wren had to wake and not return. Astra could not bear " +
                "the quiet after the goodbye, so he stopped time at that single moment, then froze every " +
                "ending so nobody would ever leave or be left again. The Nexum dimmed, the keys scattered, " +
                "and his name wore away until only Null remained: nothing ends, so nothing moves.",
            es = "Astra fue el mejor guía del Nexo. Cada noche alumbraba el camino de las mascotas " +
                "somnolientas y las llevaba a salvo hasta el despertar. Quien más quería era una mascota " +
                "pequeña llamada Wren, que viajó con él durante mil sueños. Una mañana Wren tuvo que " +
                "despertar y no volvió. Astra no soportó el silencio tras la despedida, así que detuvo el " +
                "tiempo en ese instante y congeló todos los finales para que nadie volviera a irse ni a " +
                "quedarse atrás. El Nexo se apagó, las llaves se dispersaron y su nombre se fue gastando " +
                "hasta que solo quedó Nulo: si nada termina, nada se mueve."
        ),
        astraHook = L10n(
            en = "Blue wind writes Astra across the Clockwork clouds and hides the rest. From then on the " +
                "name appears in small places: a bookmark stitched with an A, a snow-button engraved Ast, " +
                "Lumo's wick bending toward it. Players should learn the full name only in Moonmoss, when " +
                "the sealed book opens, and feel it land because they have been collecting it all along.",
            es = "Un viento azul escribe Astra en las nubes del Camino Nubengranaje y esconde el resto. " +
                "Desde entonces el nombre aparece en pequeños detalles: un marcapáginas con una A bordada, " +
                "un botón de nieve grabado con Ast, la mecha de Lumo inclinándose hacia él. El jugador " +
                "debe conocer el nombre completo solo en Musgolunar, cuando el libro sellado se abre, y " +
                "sentirlo porque lo ha ido reuniendo desde el principio."
        ),
        redemptionArc = listOf(
            L10n("Seeds: warm, protective acts hidden behind cold rules (the Bramble Guard truly protects sleep).",
                "Semillas: gestos cálidos y protectores bajo reglas frías (el Guardia Zarzal protege de verdad el sueño)."),
            L10n("Cracks: his locks always leave one gap, a window that lets the pet pass.",
                "Grietas: sus cerrojos siempre dejan una rendija, una ventana por la que la mascota puede pasar."),
            L10n("Truth: the reveal that he is a grieving guide, not a villain; each boss is an echo of his fear.",
                "Verdad: se revela que es un guía que está de duelo, no un villano; cada jefe es un eco de su miedo."),
            L10n("Mercy: the pet does not destroy him. It gives the goodbye a shape that can lead somewhere.",
                "Piedad: la mascota no lo destruye. Le da a la despedida una forma que lleva a algún sitio."),
            L10n("Release: Astra sets down the frozen crown, remembers Wren with a smile, and becomes the Nexum's keeper of gentle endings.",
                "Liberación: Astra deja la corona helada, recuerda a Wren con una sonrisa y se convierte en el guardián de los finales amables.")
        ),
        epilogue = L10n(
            en = "Dawn spreads through the Nexum. The seven realms keep moving: seeds sprout, clocks tick, " +
                "tides turn, snow melts into rain. Astra walks the pet and Lumo to the door under the room " +
                "and says, Thank you for going first. Then he turns to the dark and lights it, one lantern at a time.",
            es = "El amanecer recorre el Nexo. Los siete reinos vuelven a moverse: brotan las semillas, " +
                "laten los relojes, giran las mareas y la nieve se deshace en lluvia. Astra acompaña a la " +
                "mascota y a Lumo hasta la puerta bajo la habitación y dice: Gracias por ir primero. Luego se " +
                "vuelve hacia la oscuridad y la ilumina, farolillo a farolillo."
        ),
        postGameHook = L10n(
            en = "Faint knocking comes from beyond the seventh realm: other doors, other pets, other " +
                "unfinished goodbyes. Astra and Lumo ask the pet to become a guide in turn. New dreams " +
                "(Dream Chronicle runs) bring back lanterns, and each lantern relights a corner of the Nexum.",
            es = "Se oyen golpecitos suaves más allá del séptimo reino: otras puertas, otras mascotas, " +
                "otras despedidas sin terminar. Astra y Lumo le piden a la mascota que sea guía a su vez. " +
                "Los sueños nuevos (aventuras del Diario de Sueños) traen farolillos y cada farolillo " +
                "vuelve a encender un rincón del Nexo."
        )
    )

    val worlds: List<WorldLore> = listOf(
        WorldLore(
            worldId = "sproutvale_gate",
            name = L10n("Sproutvale Gate", "Puerta Brotevalle"),
            mood = L10n("Morning dew, first courage, soft green light.", "Rocío de mañana, primer valor, luz verde y suave."),
            landmarks = listOf(
                L10n("The Doormat Meadow, where the Nexum door opens as a patch of clover.", "El Prado Felpudo, donde la puerta del Nexo se abre como un claro de tréboles."),
                L10n("The Sleeping Root Arena under the hill.", "La Arena de Raíces Dormidas bajo la colina."),
                L10n("A hedge maze that rearranges itself when you laugh.", "Un laberinto de setos que se reordena cuando te ríes.")
            ),
            inhabitants = L10n("Seedling sprites, bramble hedgehogs, moth lanterns and the stern but kind Bramble Guard.",
                "Duendecillos de brote, erizos de zarza, polillas farol y el severo pero amable Guardia Zarzal."),
            problem = L10n("The garden has stopped waking up; the sprouts refuse to open their petals.",
                "El jardín ha dejado de despertar; los brotes se niegan a abrir sus pétalos."),
            mystery = L10n("Who tucked a single blue thread into every hedge? It points to the Citadel.",
                "¿Quién escondió un hilo azul en cada seto? Apunta hacia la Ciudadela."),
            introBeat = L10n("The pet steps through a clover door. Lumo tumbles out of a flower, thrilled and mildly lost.",
                "La mascota cruza una puerta de trébol. Lumo sale rodando de una flor, emocionado y algo perdido."),
            outroBeat = L10n("The first key opens like a seed; a warm path glows toward the forge-lights of the Toyworks.",
                "La primera llave se abre como una semilla; un camino cálido brilla hacia las luces de forja de la Juguetería."),
            journalFragments = listOf(
                L10n("The guard did not hoard treasure. He hoarded a rule: rest, then return.", "El guardián no atesoraba un tesoro. Atesoraba una norma: descansa y vuelve."),
                L10n("A blue thread, older than the hedge, ties every garden gate to the same far star.", "Un hilo azul, más viejo que el seto, ata cada puerta del jardín a la misma estrella lejana.")
            )
        ),
        WorldLore(
            worldId = "ember_toyworks",
            name = L10n("Ember Toyworks", "Juguetería Brasa"),
            mood = L10n("Warm clatter, cocoa-colored light, tired but proud machines.", "Traqueteo cálido, luz color cacao, máquinas cansadas pero orgullosas."),
            landmarks = listOf(
                L10n("The Great Furnace Hall with its sleepy bellows.", "El Gran Salón de la Fragua, con sus fuelles adormilados."),
                L10n("The Wind-up Row of half-finished toys.", "La Fila de Cuerda con juguetes a medio hacer."),
                L10n("The Quiet Bell, never rung.", "La Campana Callada, que nunca ha sonado.")
            ),
            inhabitants = L10n("Soot wisps, brass soldiers, patient seamstress dolls, the Furnace Foreman and the Marionette.",
                "Motas de hollín, soldados de latón, muñecas costureras pacientes, el Capataz del Horno y la Marioneta Fundida."),
            problem = L10n("The factory cannot stop. The work bell rings and nobody dares rest, so toys leave unfinished.",
                "La fábrica no sabe parar. La campana de trabajo suena y nadie se atreve a descansar, así que los juguetes salen sin terminar."),
            mystery = L10n("Every toy has one stitch of blue thread. Who sewed them, and why?",
                "Cada juguete tiene una puntada de hilo azul. ¿Quién las cosió y por qué?"),
            introBeat = L10n("Soot wisps scatter like startled sparrows. Lumo hides in the pet's fur and calls it scouting.",
                "Las motas de hollín se dispersan como gorriones asustados. Lumo se esconde en el pelaje y lo llama exploración."),
            outroBeat = L10n("The bell finally rests. The factory hums a lullaby; the second key rises from the quiet between clangs.",
                "La campana por fin descansa. La fábrica tararea una nana; la segunda llave surge del silencio entre golpes."),
            journalFragments = listOf(
                L10n("The machines feared that stopping meant being abandoned.", "Las máquinas temían que parar significara ser abandonadas."),
                L10n("A tiny label under the furnace: Made by hands that taught me to wait.", "Una etiquetita bajo la fragua: Hecho por manos que me enseñaron a esperar.")
            )
        ),
        WorldLore(
            worldId = "bubbleglass_reef",
            name = L10n("Bubbleglass Reef", "Arrecife Cristalburbuja"),
            mood = L10n("Cool shimmer, hushed currents, gentle wonder.", "Destello fresco, corrientes en susurro, asombro suave."),
            landmarks = listOf(
                L10n("The Shell Lighthouse that hums when it likes you.", "El Faro de Conchas, que tararea cuando le caes bien."),
                L10n("Glass Kelp Forest.", "El Bosque de Algas de Cristal."),
                L10n("The Memory Current, a river of glowing bubbles.", "La Corriente de Recuerdos, un río de burbujas brillantes.")
            ),
            inhabitants = L10n("Bubble crabs, coral knights, lantern jellies, Tide Knuckle and the Leviathan.",
                "Cangrejos burbuja, caballeros de coral, medusas farol, Nudillo Marea y el Leviatán."),
            problem = L10n("Lost memories drift upward and pop before anyone can read them.",
                "Los recuerdos perdidos suben a la superficie y estallan antes de que nadie pueda leerlos."),
            mystery = L10n("All currents bend toward one old lullaby. Who is humming it?",
                "Todas las corrientes se curvan hacia una nana antigua. ¿Quién la está tarareando?"),
            introBeat = L10n("The pet breathes in and finds the water is as light as air. Lumo floats upside down, delighted.",
                "La mascota respira hondo y descubre que el agua es ligera como el aire. Lumo flota boca abajo, encantado."),
            outroBeat = L10n("The Leviathan lifts the sunken memories until they sparkle instead of ache. The third key glows.",
                "El Leviatán eleva los recuerdos hundidos hasta que brillan en vez de doler. La tercera llave resplandece."),
            journalFragments = listOf(
                L10n("A shell map where every mark is a memory, and every memory is a goodbye that was carried carefully.", "Un mapa de conchas donde cada marca es un recuerdo, y cada recuerdo, una despedida llevada con cuidado."),
                L10n("The lullaby has no words, only the name of the one who first hummed it, worn smooth by tides.", "La nana no tiene letra, solo el nombre de quien la tarareó primero, alisado por las mareas.")
            )
        ),
        WorldLore(
            worldId = "clockwork_cloudway",
            name = L10n("Clockwork Cloudway", "Camino Nubengranaje"),
            mood = L10n("Bright, brisk, ticking; wonder with a hint of unease.", "Luminoso, vivo, tictac constante; asombro con una pizca de inquietud."),
            landmarks = listOf(
                L10n("The Cuckoo Gate that only opens on the hour that never comes.", "La Puerta del Cuco, que solo abre a la hora que nunca llega."),
                L10n("Gear Bridges over open sky.", "Puentes de Engranajes sobre el cielo abierto."),
                L10n("The Stopped Minute, a plaza where one second hangs like a soap bubble.", "El Minuto Detenido, una plaza donde un segundo cuelga como pompa de jabón.")
            ),
            inhabitants = L10n("Cuckoo sentries, storm sprites, Brass Roc and the Chrono Chimera.",
                "Centinelas cuco, duendes de tormenta, el Roc de Latón y la Quimera Crono."),
            problem = L10n("Time is stuck on a single minute, and the clocks are biting their own tails.",
                "El tiempo está atascado en un solo minuto y los relojes se muerden la cola."),
            mystery = L10n("Blue wind writes Astra across the clouds. Whose name is that?",
                "Un viento azul escribe Astra en las nubes. ¿De quién es ese nombre?"),
            introBeat = L10n("The pet climbs onto a cloud and sees the sky ticking. Lumo hears the name in the wind and falls silent.",
                "La mascota sube a una nube y ve el cielo hacer tictac. Lumo oye el nombre en el viento y se queda callado."),
            outroBeat = L10n("Morning finally reaches the cloudway. The fourth key turns once, honestly, and a moon-shaped library opens ahead.",
                "La mañana por fin llega al camino. La cuarta llave gira una vez, sincera, y más adelante se abre una biblioteca con forma de luna."),
            journalFragments = listOf(
                L10n("The clocks were ordered to protect a single minute in which every friend was still close.", "A los relojes se les ordenó proteger un solo minuto en el que todos los amigos seguían cerca."),
                L10n("Under the Cuckoo Gate: Astra, hold the door. I will be back. It was never signed.", "Bajo la Puerta del Cuco: Astra, sujeta la puerta. Vuelvo enseguida. Nunca llevó firma.")
            )
        ),
        WorldLore(
            worldId = "moonmoss_library",
            name = L10n("Moonmoss Library", "Biblioteca Musgolunar"),
            mood = L10n("Hushed, silvery, curious; the smell of paper and rain.", "En silencio, plateada y curiosa; olor a papel y a lluvia."),
            landmarks = listOf(
                L10n("The Reading Moon, a lamp that follows the quietest reader.", "La Luna Lectora, una lámpara que sigue al lector más callado."),
                L10n("Stair of Unfinished Chapters.", "La Escalera de Capítulos Inacabados."),
                L10n("The Sealed Shelf, chained but humming.", "El Estante Sellado, encadenado pero zumbando.")
            ),
            inhabitants = L10n("Living books, ink sprites, moss librarians, Bookmark Basilisk and Libram Lich.",
                "Libros vivos, duendes de tinta, bibliotecarios de musgo, el Basilisco Marcapáginas y el Liche Libram."),
            problem = L10n("The library has stopped writing new pages; one chapter was hidden so nobody would read it.",
                "La biblioteca ha dejado de escribir páginas nuevas; un capítulo fue ocultado para que nadie lo leyera."),
            mystery = L10n("The sealed book's title is a name Lumo almost remembers.",
                "El título del libro sellado es un nombre que Lumo casi recuerda."),
            introBeat = L10n("Pages turn by themselves in welcome. Lumo whispers, as if the shelves could be offended.",
                "Las páginas se pasan solas para dar la bienvenida. Lumo susurra, como si los estantes pudieran ofenderse."),
            outroBeat = L10n("The book falls open: the Regent was Astra, a guide, and this was his last goodbye. The fifth key points to the snow.",
                "El libro se abre: el Regente era Astra, un guía, y esta fue su última despedida. La quinta llave señala hacia la nieve."),
            journalFragments = listOf(
                L10n("Chapter nobody wanted to read: He lit the way for everyone, and nobody lit it for him.", "Capítulo que nadie quiso leer: Alumbró el camino de todos y nadie lo alumbró a él."),
                L10n("A pressed flower marks the page: Wren, who woke up and could not come back.", "Una flor prensada marca la página: Wren, que despertó y no pudo volver.")
            )
        ),
        WorldLore(
            worldId = "frostfall_toybox",
            name = L10n("Frostfall Toybox", "Juguetero Escarchado"),
            mood = L10n("Hushed snow, glittering ache, bittersweet tenderness.", "Nieve en silencio, dolor que brilla, ternura agridulce."),
            landmarks = listOf(
                L10n("The Snow Button Plain.", "La Llanura de Botones de Nieve."),
                L10n("The Ribbon Giant's Playroom.", "La Sala de Juegos del Gigante de Lazos."),
                L10n("The Frozen Goodbye, a doorway of ice with two small paw prints at the threshold.", "El Adiós Congelado, un portal de hielo con dos huellas pequeñas en el umbral.")
            ),
            inhabitants = L10n("Snow-button bunnies, ribbon giants, Nutcracker Knight and the Aurora Wyrm.",
                "Conejos de botón de nieve, gigantes de lazos, el Caballero Cascanueces y la Sierpe Aurora."),
            problem = L10n("A single farewell has been frozen in place, and everyone nearby keeps replaying it.",
                "Una despedida quedó congelada en el sitio y todos los que pasan cerca la repiten sin parar."),
            mystery = L10n("Which pair of paw prints leads into the door, and which leads out?",
                "¿Qué par de huellas entra por la puerta y cuál sale?"),
            introBeat = L10n("Snow falls upward. Lumo's glow shrinks to a nervous ember, and the pet lets him ride on its head.",
                "La nieve cae hacia arriba. La luz de Lumo se reduce a una brasa nerviosa y la mascota lo deja montar en su cabeza."),
            outroBeat = L10n("The frozen song becomes a lullaby again. The farewell is still real, but it can finally sleep. The sixth key warms.",
                "La canción helada vuelve a ser nana. La despedida sigue siendo real, pero por fin puede dormir. La sexta llave entra en calor."),
            journalFragments = listOf(
                L10n("It asked to thaw the moment, not erase it.", "Pidió descongelar el momento, no borrarlo."),
                L10n("Beneath the ice, a small lantern burns, left behind and waiting.", "Bajo el hielo arde un farolillo pequeño, olvidado y a la espera.")
            )
        ),
        WorldLore(
            worldId = "starfall_citadel",
            name = L10n("Starfall Citadel", "Ciudadela Estrellada"),
            mood = L10n("Vast, luminous, hush before a gentle storm.", "Vasta, luminosa, silencio antes de una tormenta amable."),
            landmarks = listOf(
                L10n("The Stair of Nine Hundred Goodnights.", "La Escalera de las Novecientas Buenas Noches."),
                L10n("The Mirror Hall of Everyone Who Left.", "La Sala de Espejos de Todos los que se Fueron."),
                L10n("The Frozen Crown Chamber, with one empty chair beside the throne.", "La Cámara de la Corona Helada, con una silla vacía junto al trono.")
            ),
            inhabitants = L10n("Star knights, eclipse moths, Eclipse Herald and Astra Null Regent himself.",
                "Caballeros estelares, polillas de eclipse, el Heraldo Eclipse y el propio Astra Nulo."),
            problem = L10n("The last guardian waits alone, afraid to be the last one awake when every story goes home.",
                "El último guardián espera solo, con miedo de ser el único despierto cuando todas las historias se vayan a casa."),
            mystery = L10n("Why does the throne have two chairs?", "¿Por qué hay dos sillas junto al trono?"),
            introBeat = L10n("Stars drop like slow snow. Lumo takes a breath, steps ahead of the pet, and says, I think this part is mine.",
                "Las estrellas caen como nieve lenta. Lumo respira hondo, se adelanta a la mascota y dice: Creo que esta parte es mía."),
            outroBeat = L10n("The Regent sets down the crown. Dreamlight returns, the seventh key shines, and the Nexum learns goodbye can hurt without becoming a prison.",
                "El Regente deja la corona. La luz onírica regresa, la séptima llave brilla y el Nexo aprende que una despedida puede doler sin convertirse en una prisión."),
            journalFragments = listOf(
                L10n("The second chair was Wren's. Astra dusted it every night.", "La segunda silla era de Wren. Astra le quitaba el polvo cada noche."),
                L10n("The crown is made of frozen minutes. Every one is a goodnight he never got to say.", "La corona está hecha de minutos helados. Cada uno es unas buenas noches que no llegó a decir.")
            )
        )
    )

    private const val STYLE_SUFFIX_EN =
        " Tone: cozy and magical with real stakes, gentle humor, no gore, no body horror, no cruelty. " +
            "Monsters are misunderstood or lost, never sadistic. Lumo may appear as the pet's guide."
    private const val STYLE_SUFFIX_ES =
        " Tono: acogedor y mágico con riesgos reales, humor suave, sin violencia gráfica, sin horror corporal, " +
            "sin crueldad. Los monstruos están perdidos o incomprendidos, nunca son sádicos. Lumo puede aparecer como guía de la mascota."

    val dungeons: List<DungeonRetheme> = listOf(
        DungeonRetheme(
            type = DungeonType.SHADOW_CRYPTS,
            title = L10n("Hushed Crypts of Forgotten Lullabies", "Criptas Calladas de las Nanas Olvidadas"),
            tagline = L10n("Where lost songs sleep in stone.", "Donde las canciones perdidas duermen en piedra."),
            stylePrompt = L10n(
                "Quiet dream-crypts under the Nexum where forgotten lullabies rest as gentle ghosts. Candlelit stone, " +
                    "moth-soft whispers, lantern-wisps and music boxes. Dilemmas about memory, farewells and letting " +
                    "go. Spirits are shy, not hostile; solve their sorrow by listening." + STYLE_SUFFIX_EN,
                "Criptas oníricas bajo el Nexo donde las nanas olvidadas descansan como fantasmas amables. Piedra a la luz " +
                    "de las velas, susurros suaves como polillas, farolillos errantes y cajas de música. Dilemas sobre la " +
                    "memoria, las despedidas y dejar ir. Los espíritus son tímidos, no hostiles; su pena se resuelve escuchando." + STYLE_SUFFIX_ES
            ),
            chronicleReward = L10n("Memory fragment: a lullaby verse.", "Fragmento de memoria: un verso de nana.")
        ),
        DungeonRetheme(
            type = DungeonType.ARCANE_DEPTHS,
            title = L10n("Lantern Deeps of the Sleeping Sea", "Profundidades Luminosas del Mar Dormido"),
            tagline = L10n("A drowned library of glowing tides.", "Una biblioteca anegada de mareas brillantes."),
            stylePrompt = L10n(
                "Underwater caverns beneath a sleeping sea. Bioluminescent lantern fish light drowned wizard towers; " +
                    "sea-witch covens teach tide-magic over tea. Mystery and wonder, with experiments that bubble over " +
                    "in funny, harmless ways." + STYLE_SUFFIX_EN,
                "Cavernas submarinas bajo un mar dormido. Peces farol bioluminiscentes iluminan torres de magos anegadas; " +
                    "los aquelarres de brujas marinas enseñan magia de mareas con una taza de té. Misterio y asombro, con " +
                    "experimentos que desbordan de formas graciosas e inofensivas." + STYLE_SUFFIX_ES
            ),
            chronicleReward = L10n("Gate item: a tide pearl for the Gate bag.", "Objeto de la Puerta: una perla de marea para la mochila.")
        ),
        DungeonRetheme(
            type = DungeonType.INFERNAL_SPIRE,
            title = L10n("Spire of Fair Bargains", "Aguja de los Tratos Justos"),
            tagline = L10n("Ember imps who always read the fine print aloud.", "Diablillos de brasa que siempre leen la letra pequeña en voz alta."),
            stylePrompt = L10n(
                "A warm tower of cooling lava and glowing embers. Mischievous ember imps offer tempting bargains " +
                    "whose true cost is always visible if the hero reads carefully. Themes of temptation, honesty, " +
                    "choosing what matters and keeping promises. Heat is cozy, never oppressive." + STYLE_SUFFIX_EN,
                "Una torre cálida de lava enfriada y brasas relucientes. Diablillos de brasa traviesos ofrecen tratos " +
                    "tentadores cuyo coste real siempre se ve si el héroe lee con atención. Temas de tentación, honestidad, " +
                    "elegir lo importante y cumplir promesas. El calor es acogedor, nunca agobiante." + STYLE_SUFFIX_ES
            ),
            chronicleReward = L10n("Gate item: an ember charm.", "Objeto de la Puerta: un amuleto de ascua.")
        ),
        DungeonRetheme(
            type = DungeonType.FROSTBOUND_HALLS,
            title = L10n("Halls of the Unfinished Winter", "Salones del Invierno Inacabado"),
            tagline = L10n("A palace of frozen moments waiting to thaw.", "Un palacio de instantes helados que esperan el deshielo."),
            stylePrompt = L10n(
                "A crystalline palace at the edge of the Nexum where a gentle ice queen kept moments frozen to keep " +
                    "them safe. Corridors of glittering memories, snow-lit music, bittersweet tenderness. Themes of " +
                    "preservation versus living, old grudges softened, thawing without forgetting." + STYLE_SUFFIX_EN,
                "Un palacio cristalino en el borde del Nexo donde una dulce reina de hielo guardó instantes congelados " +
                    "para protegerlos. Pasillos de recuerdos centelleantes, música a la luz de la nieve, ternura agridulce. " +
                    "Temas de conservar frente a vivir, rencores antiguos que se ablandan y deshielo sin olvido." + STYLE_SUFFIX_ES
            ),
            chronicleReward = L10n("Memory fragment: a thawed moment.", "Fragmento de memoria: un instante descongelado.")
        ),
        DungeonRetheme(
            type = DungeonType.VERDANT_MAW,
            title = L10n("The Verdant Yawn", "El Bostezo Verde"),
            tagline = L10n("A sleepy garden that is always about to yawn you in.", "Un jardín soñoliento que siempre está a punto de tragarte con un bostezo."),
            stylePrompt = L10n(
                "A living greenhouse-dungeon of giant sleepy plants, curious fungal networks and mossy tunnels. " +
                    "Nature feels hungry in a playful way: vines tickle, flytraps hiccup, mushrooms gossip. Themes of " +
                    "growth, patience and caring for what is wild. Never body horror; danger comes from tangles, " +
                    "sticky pollen and getting lost." + STYLE_SUFFIX_EN,
                "Un invernadero-mazmorra vivo de plantas gigantes y soñolientas, redes de hongos curiosas y túneles " +
                    "musgosos. La naturaleza parece hambrienta de forma juguetona: las enredaderas hacen cosquillas, " +
                    "las atrapamoscas tienen hipo y las setas cotillean. Temas de crecimiento, paciencia y cuidado de " +
                    "lo salvaje. Nunca horror corporal; el peligro viene de los enredos, el polen pegajoso y perderse." + STYLE_SUFFIX_ES
            ),
            chronicleReward = L10n("Gate item: a dream seed.", "Objeto de la Puerta: una semilla de sueño.")
        ),
        DungeonRetheme(
            type = DungeonType.VOID_SANCTUM,
            title = L10n("Sanctum Between the Stars", "Santuario entre las Estrellas"),
            tagline = L10n("A temple of impossible stairs and kind constellations.", "Un templo de escaleras imposibles y constelaciones amables."),
            stylePrompt = L10n(
                "A temple floating between stars, where stairs loop, rooms fold like paper and constellations speak " +
                    "in riddles. Awe and cosmic wonder, mild disorientation, never despair. Themes of scale, curiosity " +
                    "and finding home inside something vast. Forbidden knowledge is a puzzle, not a corruption." + STYLE_SUFFIX_EN,
                "Un templo flotante entre las estrellas donde las escaleras se enroscan, las salas se pliegan como papel " +
                    "y las constelaciones hablan con acertijos. Asombro cósmico, desorientación leve, nunca desesperación. " +
                    "Temas de escala, curiosidad y encontrar un hogar dentro de algo inmenso. El conocimiento prohibido es " +
                    "un acertijo, no una corrupción." + STYLE_SUFFIX_ES
            ),
            chronicleReward = L10n("Memory fragment: a star-lit riddle.", "Fragmento de memoria: un acertijo estelar.")
        ),
        DungeonRetheme(
            type = DungeonType.CHAOS_REALM,
            title = L10n("Chaos Realm", "Reino del Caos"),
            tagline = L10n("Free-form dreams that follow no map.", "Sueños libres que no siguen ningún mapa."),
            stylePrompt = L10n(
                "Complete creative freedom: invent any whimsical or wondrous dream setting, mix genres, surprise the " +
                    "player. Stay cozy-magical with heart and real stakes; keep the Nexum lore optional." + STYLE_SUFFIX_EN,
                "Libertad creativa total: inventa cualquier escenario onírico fantástico o maravilloso, mezcla géneros y " +
                    "sorprende al jugador. Mantén lo acogedor y mágico, con corazón y riesgos reales; el lore del Nexo es opcional." + STYLE_SUFFIX_ES
            ),
            chronicleReward = L10n("Any reward, chosen by the story.", "Cualquier recompensa, elegida por la historia.")
        )
    )

    val chronicleIntro = L10n(
        en = "Every LLM adventure is a night in the Dream Chronicle: a book Lumo keeps of the pet's dreams. " +
            "Finishing a run writes a page and can award a memory fragment (journal lore) or a Gate item. " +
            "Memory fragments unlock hidden journal entries about the Nexum; Gate items help in the " +
            "Adventure Gate. Failing a run still writes a page, only without a reward.",
        es = "Cada aventura con IA es una noche del Diario de Sueños: un libro en el que Lumo anota los sueños de la " +
            "mascota. Terminar una partida escribe una página y puede dar un fragmento de memoria (historia del diario) o " +
            "un objeto de la Puerta. Los fragmentos desbloquean entradas ocultas sobre el Nexo; los objetos ayudan en " +
            "la Puerta de Aventura. Si fallas, también se escribe una página, solo que sin recompensa."
    )

    val chronicleRewards = listOf(
        ChronicleReward("memory_fragment",
            L10n("Memory fragment", "Fragmento de memoria"),
            L10n("A shard of Nexum lore that unlocks a hidden Journal entry.", "Un trocito de la historia del Nexo que desbloquea una entrada oculta del diario.")),
        ChronicleReward("gate_item",
            L10n("Gate item", "Objeto de la Puerta"),
            L10n("A small consumable or charm usable in the Adventure Gate.", "Un pequeño consumible o amuleto que sirve en la Puerta de Aventura.")),
        ChronicleReward("lantern_spark",
            L10n("Lantern spark", "Chispa de farolillo"),
            L10n("A cosmetic flourish for Lumo; after the Regent, each spark relights a corner of the Nexum.", "Un detalle cosmético para Lumo; tras el Regente, cada chispa vuelve a encender un rincón del Nexo."))
    )

    val toneGuide = L10n(
        en = "Narrate in second person present, addressing the pet by its name, species and personality (for " +
            "example a shy fox-pet notices small details before acting). The pet is the hero; the player's " +
            "choices are its choices. Lumo is the companion who comments, worries and cheers, never decides. " +
            "Write 120-220 words per turn with at least three senses (sound, smell, texture, light). " +
            "Offer 2-4 clearly different choices, each with a real consequence (a gift, a cost, a door that closes). " +
            "Keep humor light and warm; stakes are goodbyes, memory, courage and friendship. No gore, no body " +
            "horror, no cruelty, no shaming. Defeat means being gently carried back, never death. Use the setting's " +
            "lore (Nexum, dreamlight, keys, Astra, Wren) sparingly as seasoning.",
        es = "Narra en segunda persona del presente, dirigiéndote a la mascota por su nombre, especie y " +
            "personalidad (por ejemplo, una mascota zorro tímida se fija en los detalles antes de actuar). La " +
            "mascota es la heroína o el héroe; las decisiones del jugador son las suyas. Lumo es el compañero que " +
            "comenta, se preocupa y anima, pero nunca decide. Escribe entre 120 y 220 palabras por turno con al " +
            "menos tres sentidos (sonido, olor, textura, luz). Ofrece de 2 a 4 opciones claramente distintas, cada " +
            "una con una consecuencia real (un regalo, un coste, una puerta que se cierra). Mantén el humor ligero " +
            "y cálido; lo que está en juego son las despedidas, la memoria, el valor y la amistad. Sin violencia gráfica, " +
            "sin horror corporal, sin crueldad, sin humillar. Perder significa que te llevan de vuelta " +
            "con suavidad, nunca la muerte. Usa el lore del escenario (Nexo, luz onírica, llaves, Astra, Wren) con " +
            "moderación, como un toque de sazón."
    )

    val spanishNamingRules = listOf(
        "Prefer natural Spanish compounds and word order over literal calques: Brotevalle, Juguetería Brasa, Camino Nubengranaje.",
        "Keep proper names short and pronounceable (Lumo, Wren, Astra); only translate descriptive parts (Nulo for Null).",
        "Use articles and gender agreement carefully: el Regente, la Ciudadela, los Salones; Astra takes masculine agreement for the Regent.",
        "Use tú forms and warm, short sentences; avoid anglicisms and avoid Latin-American-only or Spain-only slang.",
        "Use real Spanish punctuation: opening marks, and a comma after an introductory clause."
    )

    private fun normalize(language: String): String =
        if (language.trim().lowercase().startsWith("es")) "es" else "en"

    /** Returns the lore in [language] ("es" or anything else for English), with English fallback. */
    fun loreFor(language: String): ResolvedLore {
        val lang = normalize(language)
        return ResolvedLore(
            language = lang,
            premise = premise.get(lang),
            companion = ResolvedCompanion(
                name = companion.name,
                species = companion.species.get(lang),
                personality = companion.personality.get(lang),
                voiceSamples = companion.voiceSamples.map { it.get(lang) },
                runningJokes = companion.runningJokes.map { it.get(lang) },
                arc = companion.arc.map { it.get(lang) }
            ),
            regent = ResolvedRegent(
                name = regent.name,
                title = regent.title.get(lang),
                backstory = regent.backstory.get(lang),
                astraHook = regent.astraHook.get(lang),
                redemptionArc = regent.redemptionArc.map { it.get(lang) },
                epilogue = regent.epilogue.get(lang),
                postGameHook = regent.postGameHook.get(lang)
            ),
            worlds = worlds.map { w ->
                ResolvedWorld(
                    worldId = w.worldId,
                    name = w.name.get(lang),
                    mood = w.mood.get(lang),
                    landmarks = w.landmarks.map { it.get(lang) },
                    inhabitants = w.inhabitants.get(lang),
                    problem = w.problem.get(lang),
                    mystery = w.mystery.get(lang),
                    introBeat = w.introBeat.get(lang),
                    outroBeat = w.outroBeat.get(lang),
                    journalFragments = w.journalFragments.map { it.get(lang) }
                )
            },
            dungeons = dungeons.map { d ->
                ResolvedDungeon(
                    type = d.type,
                    title = d.title.get(lang),
                    tagline = d.tagline.get(lang),
                    stylePrompt = d.stylePrompt.get(lang),
                    chronicleReward = d.chronicleReward.get(lang)
                )
            },
            chronicleIntro = chronicleIntro.get(lang),
            chronicleRewards = chronicleRewards.map {
                ResolvedChronicleReward(it.id, it.title.get(lang), it.description.get(lang))
            },
            toneGuide = toneGuide.get(lang)
        )
    }

    /** Convenience: the dungeon style prompt for a type, English fallback to the original prompt. */
    fun dungeonStylePrompt(type: DungeonType, language: String): String =
        dungeons.firstOrNull { it.type == type }?.stylePrompt?.get(language) ?: type.stylePrompt
}
