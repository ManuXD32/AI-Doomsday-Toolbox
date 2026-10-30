package com.example.llamadroid.screenshots.fixtures

import android.app.Application
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.data.db.OrganizerAlarmEntity
import com.example.llamadroid.data.db.OrganizerEventEntity
import com.example.llamadroid.screenshots.harness.ShotLocale
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Survival-plan notes, checklists, calendar events and alarms for the Organizer screen. */
object OrganizerFixtures {
    private fun todo(vararg items: Pair<Boolean, String>): String =
        items.joinToString("\n") { (done, text) -> "- [${if (done) "x" else " "}] $text" }

    fun seed(app: Application, l: ShotLocale) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun at(dayOffset: Long, hour: Int, minute: Int = 0): Long =
            today.plusDays(dayOffset).atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
        val now = today.atTime(LocalTime.of(9, 41)).atZone(zone).toInstant().toEpochMilli()
        runBlocking {
            val db = AppDatabase.getDatabase(app)
            val notes = db.noteDao()
            // Insert oldest first so the newest note ends up on top of the list.
            notes.insert(
                NoteEntity(
                    title = l.pick("Radio net check-in", "Registro de la red de radio"),
                    content = l.pick(
                        "Weekly net at 19:00 on 146.520 MHz. Reported: bridge out on County Road 9, spring running clear, two families need a generator.",
                        "Red semanal a las 19:00 en 146,520 MHz. Informes: puente cortado en la carretera 9, el manantial corre limpio, dos familias necesitan un generador."
                    ),
                    type = NoteType.TRANSCRIPTION, sourceFile = "net_checkin.m4a", language = l.tag,
                    createdAt = now - 5 * 86_400_000L, updatedAt = now - 5 * 86_400_000L
                )
            )
            notes.insert(
                NoteEntity(
                    title = l.pick("Field guide: edible plants", "Guía de campo: plantas comestibles"),
                    content = l.pick(
                        "Summary of the 64-page field guide. Safe starters: dandelion, plantain, chickweed. Always apply the universal edibility test and never eat anything you cannot identify with two sources.",
                        "Resumen de la guía de campo de 64 páginas. Para empezar: diente de león, llantén y pamplina. Aplica siempre la prueba universal de comestibilidad y no comas nada que no puedas identificar con dos fuentes."
                    ),
                    type = NoteType.PDF_SUMMARY, sourceFile = "field_guide_plants.pdf",
                    createdAt = now - 4 * 86_400_000L, updatedAt = now - 4 * 86_400_000L
                )
            )
            notes.insert(
                NoteEntity(
                    title = l.pick("Go-bag packing list", "Lista de la mochila de emergencia"),
                    content = todo(
                        true to l.pick("Water filter and tablets", "Filtro de agua y pastillas"),
                        true to l.pick("First-aid kit", "Botiquín de primeros auxilios"),
                        true to l.pick("Headlamp and spare batteries", "Linterna frontal y pilas de repuesto"),
                        false to l.pick("Paper maps of the region", "Mapas en papel de la región"),
                        false to l.pick("Copies of documents", "Copias de documentos")
                    ),
                    type = NoteType.TODO_LIST, isLlmWhitelisted = true,
                    createdAt = now - 3 * 86_400_000L, updatedAt = now - 3 * 86_400_000L
                )
            )
            notes.insert(
                NoteEntity(
                    title = l.pick("Rally points and contacts", "Puntos de encuentro y contactos"),
                    content = l.pick(
                        "**Primary:** the oak at the end of Mill Lane.\n**Backup:** the community hall.\n**Radio:** channel 3, check in every 12 hours.\n**Out of area contact:** Aunt Marta.",
                        "**Principal:** el roble al final de Mill Lane.\n**Alternativo:** el salón comunal.\n**Radio:** canal 3, comunicar cada 12 horas.\n**Contacto fuera de la zona:** tía Marta."
                    ),
                    type = NoteType.MANUAL, createdAt = now - 86_400_000L, updatedAt = now - 86_400_000L
                )
            )
            notes.insert(
                NoteEntity(
                    title = l.pick("Water storage plan", "Plan de almacenamiento de agua"),
                    content = l.pick(
                        "**Target:** 213 L for 4 people over 14 days.\n- 11 food-grade 20 L containers\n- Rotate every 6 months\n- Gravity filter as backup",
                        "**Objetivo:** 213 L para 4 personas durante 14 días.\n- 11 recipientes de 20 L aptos para alimentos\n- Renovar cada 6 meses\n- Filtro por gravedad de respaldo"
                    ),
                    type = NoteType.MANUAL, isLlmWhitelisted = true,
                    createdAt = now, updatedAt = now
                )
            )
            notes.insert(
                NoteEntity(
                    title = l.pick("Survival plan checklist", "Lista del plan de supervivencia"),
                    content = todo(
                        true to l.pick("Store 213 L of drinking water", "Almacenar 213 L de agua potable"),
                        true to l.pick("Stock 14 days of food", "Reservar comida para 14 días"),
                        true to l.pick("Charge the solar power bank", "Cargar la batería solar"),
                        false to l.pick("Service the wood stove", "Revisar la estufa de leña"),
                        false to l.pick("Walk the evacuation route", "Recorrer la ruta de evacuación"),
                        false to l.pick("Update the offline library", "Actualizar la biblioteca sin conexión")
                    ),
                    type = NoteType.TODO_LIST, isLlmWhitelisted = true,
                    createdAt = now - 2 * 86_400_000L - 1000L, updatedAt = now - 2 * 86_400_000L - 1000L
                )
            )
            val organizer = db.organizerDao()
            val events = listOf(
                Triple(0L, 9, l.pick("Rotate water containers", "Renovar los bidones de agua")),
                Triple(1L, 19, l.pick("Ham radio net check-in", "Registro en la red de radioaficionados")),
                Triple(3L, 10, l.pick("Seed starting day", "Día de siembra de semillas")),
                Triple(6L, 18, l.pick("First-aid refresher course", "Curso de repaso de primeros auxilios")),
                Triple(9L, 8, l.pick("Go-bag inspection", "Revisión de la mochila de emergencia")),
                Triple(13L, 11, l.pick("Neighborhood preparedness meeting", "Reunión vecinal de preparación"))
            )
            events.forEach { (offset, hour, title) ->
                val eventId = organizer.insertEvent(
                    OrganizerEventEntity(
                        title = title, startAtMillis = at(offset, hour), endAtMillis = at(offset, hour + 1),
                        location = if (offset == 13L) l.pick("Community hall", "Salón comunal") else "",
                        createdAt = now, updatedAt = now
                    )
                )
                if (offset in setOf(0L, 1L, 6L)) {
                    organizer.insertAlarm(
                        OrganizerAlarmEntity(
                            eventId = eventId, title = title, triggerAtMillis = at(offset, hour) - 30 * 60_000L,
                            createdAt = now, updatedAt = now
                        )
                    )
                }
            }
            organizer.insertAlarm(
                OrganizerAlarmEntity(
                    title = l.pick("Check the water filter", "Revisar el filtro de agua"),
                    triggerAtMillis = at(2, 7, 30), createdAt = now, updatedAt = now
                )
            )
        }
    }
}
