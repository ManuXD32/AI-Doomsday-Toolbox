package com.example.llamadroid.qa

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertTextContains
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.example.llamadroid.ui.agent.AgentPlanEditorDialog
import com.example.llamadroid.ui.agent.AgentNewProjectDialog
import com.example.llamadroid.service.AgentWorkspaceBackendType
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.navigation.compose.rememberNavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SharedFileHolder
import com.example.llamadroid.data.SharedFileTarget
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.service.PdfSummaryStateHolder
import com.example.llamadroid.ui.notes.NotesManagerScreen
import com.example.llamadroid.ui.pdf.PDFSummaryScreen
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real feature UI, Android provider/PDF extraction and Room persistence; no backend mocks. */
class SoftStudioFeatureFlowsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var pdfFixture: File? = null
    private val marker = "Soft Studio UI fixture — "

    @After fun cleanOwnedFixtures() {
        SharedFileHolder.clear()
        PdfSummaryStateHolder.reset()
        pdfFixture?.delete()
        runBlocking {
            val dao = AppDatabase.getDatabase(rule.activity).noteDao()
            dao.getAllNotesOnce().filter { it.title.startsWith(marker) }.forEach { dao.delete(it) }
        }
    }

    @Test fun importedPdfKeepsExtractionActionVisibleAndExtractsRealText() {
        PdfSummaryStateHolder.reset()
        val fixtureText = "Soft Studio verifies document extraction."
        val file = File(rule.activity.cacheDir, "studio-ui-informe-ñ-long-document-name-2026.pdf")
        pdfFixture = file
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            page.canvas.drawText(fixtureText, 30f, 60f, Paint().apply { textSize = 18f })
            pdf.finishPage(page)
            file.outputStream().use(pdf::writeTo)
        } finally {
            pdf.close()
        }
        val uri = FileProvider.getUriForFile(rule.activity, "${rule.activity.packageName}.fileprovider", file)
        SharedFileHolder.setPendingFile(uri, "application/pdf", SharedFileTarget.PDF_SUMMARY)
        rule.setContent { LlamaDroidTheme { PDFSummaryScreen(rememberNavController()) } }
        rule.waitUntil(15_000) { PdfSummaryStateHolder.selectedPdfName.value == file.name }
        rule.onNodeWithText(rule.activity.getString(R.string.pdf_extract_text_step))
            .assertIsDisplayed().performClick()
        rule.waitUntil(30_000) {
            PdfSummaryStateHolder.extractedText.value.contains(fixtureText) || PdfSummaryStateHolder.error.value != null
        }
        assertTrue(PdfSummaryStateHolder.error.value.orEmpty(), PdfSummaryStateHolder.extractedText.value.contains(fixtureText))
        rule.onNodeWithText(rule.activity.getString(R.string.pdf_generate_summary_step_remote)).assertIsDisplayed()
    }

    @Test fun longNoteCanBeSavedFromTheKeyboardWithoutLosingItsContent() {
        val title = marker + "a long project title for the compact organizer"
        val body = (1..75).joinToString("\n") { "Line $it: drafts remain intact when the editor scrolls." }
        rule.setContent { LlamaDroidTheme { NotesManagerScreen(rememberNavController()) } }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.notes_new)).performClick()
        rule.onNode(hasSetTextAction() and hasText(rule.activity.getString(R.string.notes_field_title)))
            .performTextInput(title)
        rule.onNode(hasSetTextAction() and hasText(rule.activity.getString(R.string.notes_field_content)))
            .performScrollTo().performTextInput(body)
        rule.onNodeWithText(rule.activity.getString(R.string.action_save)).assertIsDisplayed().performClick()
        val dao = AppDatabase.getDatabase(rule.activity).noteDao()
        rule.waitUntil(10_000) { runBlocking { dao.getAllNotesOnce().any { it.title == title } } }
        assertEquals(body, runBlocking { dao.getAllNotesOnce().single { it.title == title }.content })
    }
    private fun largeTextDialog(content: @Composable () -> Unit) {
        val configuration = Configuration(rule.activity.resources.configuration).apply { fontScale = 2f }
        val context = ContextThemeWrapper(rule.activity, rule.activity.theme).apply {
            applyOverrideConfiguration(configuration)
        }
        rule.runOnUiThread {
            rule.activity.setContentView(ComposeView(context).apply {
                setContent { LlamaDroidTheme(darkTheme = false, dynamicColor = false) { content() } }
            })
        }
    }

    private fun captureEditorWithKeyboard(name: String) {
        rule.waitForIdle()
        // Platform IME/window movement is not driven by the Compose test clock.
        SystemClock.sleep(600)
        rule.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val directory = File(rule.activity.getExternalFilesDir(null), "soft-studio-editor-qa").apply { mkdirs() }
            File(directory, "$name-font2-ime.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun agentPlanCanBeEditedAndSavedWithLargeTextAndTheKeyboard() {
        val original = (1..60).joinToString("\n") { "Step $it: review the implementation." }
        var text by mutableStateOf(original)
        var saved: String? = null
        largeTextDialog {
            AgentPlanEditorDialog(text, { text = it }, false, text != original,
                onSave = { saved = text }, onDismiss = {})
        }
        rule.onNode(hasSetTextAction()).performTextInput("Updated plan. ")
        rule.onNode(hasSetTextAction()).assertTextContains("Updated plan. ", substring = true)
        captureEditorWithKeyboard("agent-plan")
        rule.onNodeWithText(rule.activity.getString(R.string.studio_plan_save)).assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertTrue("Save callback must retain the edit", saved.orEmpty().contains("Updated plan. "))
            assertTrue("Save callback must retain the last original step", saved.orEmpty().contains("Step 60:"))
        }
    }

    @Test fun projectCreateRemainsVisibleWithTheKeyboardAtLargeText() {
        var name by mutableStateOf("")
        var backend by mutableStateOf(AgentWorkspaceBackendType.LOCAL_SANDBOX)
        var created: String? = null
        largeTextDialog {
            AgentNewProjectDialog(name, { name = it }, backend, { backend = it },
                onCreate = { created = name }, onDismiss = {})
        }
        rule.onNode(hasSetTextAction()).performTextInput("Studio keyboard project")
        captureEditorWithKeyboard("agent-project")
        rule.onNodeWithText(rule.activity.getString(R.string.action_create)).assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals("Studio keyboard project", created)
            assertEquals(AgentWorkspaceBackendType.LOCAL_SANDBOX, backend)
        }
    }

}
