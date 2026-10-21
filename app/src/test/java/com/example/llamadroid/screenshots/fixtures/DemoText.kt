package com.example.llamadroid.screenshots.fixtures

import com.example.llamadroid.screenshots.harness.ShotLocale

/** Picks the English or Spanish demo text for a shot locale. */
fun ShotLocale.pick(en: String, es: String): String = if (this == ShotLocale.ES) es else en

/** English/Spanish demo copy shared by the store-shot fixtures (never real user content). */
object DemoText {
    class ChatTexts(val l: ShotLocale) {
        val title = l.pick("Water plan", "Plan agua")
        val userWater = l.pick(
            "Plan drinking water for 4 people, 2 weeks",
            "Plan de agua potable: 4 personas, 2 semanas"
        )
        val introStep = l.pick(
            "I'll check an offline reference for the daily amount, then do the math.",
            "Consultaré una referencia sin conexión para la cantidad diaria y luego haré el cálculo."
        )
        val kiwixQuery = l.pick("emergency drinking water per person per day", "agua potable de emergencia por persona al día")
        val kiwixResult = l.pick(
            "Wikipedia (offline) - Emergency water supply\n\nPreparedness guides recommend storing at least 3.8 L (1 US gallon) of water per person per day: about 2 L for drinking and the rest for cooking and hygiene. Store it in food-grade containers, in a cool and dark place, and rotate it every six months.",
            "Wikipedia (sin conexión) - Suministro de agua de emergencia\n\nLas guías de preparación recomiendan almacenar al menos 3,8 L de agua por persona y día: unos 2 L para beber y el resto para cocinar e higiene. Guárdala en recipientes aptos para alimentos, en un lugar fresco y oscuro, y renuévala cada seis meses."
        )
        val calcResult = "212.8"
        val waterAnswer = l.pick(
            "You need about **213 L** (3.8 L per person per day):\n\n" +
                "- **Store:** 11 jugs of 20 L\n- **Rotate:** every 6 months\n- **Backup:** filter + tablets",
            "Necesitas unos **213 L** (3,8 L por persona y día):\n\n" +
                "- **Almacén:** 11 bidones de 20 L\n- **Rotación:** cada 6 meses\n- **Respaldo:** filtro + pastillas"
        )
        val userImage = l.pick(
            "Great. Can you draw a poster for the storage corner?",
            "Genial. ¿Puedes dibujar un cartel para el rincón de almacenamiento?"
        )
        val imagePrompt = l.pick(
            "flat illustration poster, bright aqua room with a neat stack of water containers",
            "cartel de ilustración plana, habitación aguamarina con una pila ordenada de bidones de agua"
        )
        val imageResult = l.pick("Image generated and attached to the chat.", "Imagen generada y adjuntada al chat.")
        val imageAnswer = l.pick(
            "Here is a poster for your storage corner. I can add a checklist or make a larger version if you like.",
            "Aquí tienes un cartel para tu rincón de almacenamiento. Puedo añadir una lista de comprobación o hacer una versión más grande si quieres."
        )
        val imageTitle = l.pick("Poster idea", "Cartel")
        /** Other titled chats for the chats home. */
        val otherChats = listOf(
            l.pick("Offline first-aid checklist", "Lista de primeros auxilios sin conexión"),
            l.pick("Solar panel sizing for the cabin", "Dimensionar los paneles solares de la cabaña"),
            l.pick("Translate the field guide to French", "Traducir la guía de campo al francés"),
            l.pick("Summarize the seed-saving PDF", "Resumir el PDF de conservación de semillas"),
            l.pick("Sourdough starter troubleshooting", "Problemas con la masa madre")
        )
    }
}

/** Demo copy of the DeepSeek delegation task (viewer shot). */
class AgentTexts(val l: ShotLocale) {
    val chatTitle = l.pick("Offline field guide", "Guía de campo sin conexión")
    val userAsk = l.pick(
        "Turn my notes into a small offline website and zip it for the cabin tablet.",
        "Convierte mis notas en una pequeña web sin conexión y comprímela para la tableta de la cabaña."
    )
    val taskTitle = l.pick("Build the offline field guide site", "Crear el sitio de la guía de campo sin conexión")
    val brief = l.pick(
        "Build an offline Field Guide site (Water, Food, First aid) from the chat notes.",
        "Crea la web sin conexión «Guía de campo» con las notas del chat."
    )
    val progress2 = l.pick(
        "Done: 3 pages, 14 links checked.",
        "Listo: 3 páginas, 14 enlaces."
    )
    val resultStatus = l.pick("completed", "completada")
    val resultSummary = l.pick(
        "Field Guide site ready: 3 pages, works offline.",
        "Sitio Guía de campo listo: 3 páginas, sin conexión."
    )
    val zipName = l.pick("field-guide.zip", "guia-de-campo.zip")
    val notice = l.pick("[DeepSeek task finished] The field guide site is ready.", "[Tarea de DeepSeek terminada] El sitio de la guía de campo está listo.")
}
