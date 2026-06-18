package com.example.llamadroid.tama.data

import android.content.Context

data class TamaSleepyFairyReminder(
    val lineIndex: Int,
    val shownAt: Long = System.currentTimeMillis()
)

data class TamaSleepyFairyDefinition(
    val id: String,
    val assetPath: String,
    val panelBackgroundAssetPath: String,
    val name: TamaLocalizedText,
    val lines: List<TamaLocalizedText>
)

object TamaSleepyFairyCatalog {
    val definition = TamaSleepyFairyDefinition(
        id = "sleepy_fairy",
        assetPath = "tama/npcs/sleepy_fairy.png",
        panelBackgroundAssetPath = "tama/backgrounds/sleepy_fairy_dialog.png",
        name = TamaLocalizedText("Sleepy Fairy", "Hadita del Sueño"),
        lines = listOf(
            TamaLocalizedText(
                "Your little one is getting sleepy. Try not to let bedtime slip past midnight.",
                "Tu peque se está quedando dormidito. Procura que la hora de dormir no se pase de medianoche."
            ),
            TamaLocalizedText(
                "I can feel the drowsy sparkles already. Keep an eye on bedtime before it turns into tomorrow.",
                "Ya noto las chispitas de sueño. Vigila la hora de dormir antes de que se convierta en mañana."
            ),
            TamaLocalizedText(
                "Those eyelids look heavy. It is best to tuck your pet in before the clock reaches midnight.",
                "Esos ojitos ya pesan. Lo mejor es acostar a tu mascota antes de que el reloj marque medianoche."
            ),
            TamaLocalizedText(
                "Tonight feels soft and sleepy. Do not let your pet stay up after midnight if you can help it.",
                "Esta noche se siente suave y soñolienta. No dejes que tu mascota siga despierta después de medianoche si puedes evitarlo."
            ),
            TamaLocalizedText(
                "The moon is whispering bedtime. A little rest before midnight will keep your pet happier.",
                "La luna está susurrando que ya toca dormir. Un poco de descanso antes de medianoche mantendrá más feliz a tu mascota."
            )
        )
    )

    fun resolveLine(context: Context, reminder: TamaSleepyFairyReminder): String {
        val locale = context.resources.configuration.locales[0]
        val lineIndex = reminder.lineIndex.coerceAtLeast(0)
        val fallback = definition.lines.getOrElse(lineIndex) {
            definition.lines.first()
        }.resolve(locale)
        return TamaDialogTextCatalog.localizedText(context, "sleepy_fairy:line:$lineIndex", fallback)
    }
}
