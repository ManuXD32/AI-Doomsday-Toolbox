package com.example.llamadroid.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeChatDelegationMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(AppDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate128To129_keepsMessagesAndValidatesSchema() {
        helper.createDatabase(DB, 128).apply {
            execSQL(
                "INSERT INTO llama_chats (id, title, lastModified, contextSize, pinnedToAiHub, " +
                    "isEphemeral, deleteAfterSession, persistKvCache) VALUES (601, 'Old', 1, 8192, 0, 0, 0, 0)"
            )
            execSQL(
                "INSERT INTO llama_messages (id, chatId, role, content, timestamp, isError, isTruncated, " +
                    "promptTokens, completionTokens, tps, generationTimeMs) " +
                    "VALUES (701, 601, 'user', 'hi', 1, 0, 0, 0, 0, 0, 0)"
            )
            close()
        }
        val migrated = helper.runMigrationsAndValidate(
            DB, 129, true, NativeChatDelegationMigration.MIGRATION_128_129
        )
        migrated.query("SELECT content, kind FROM llama_messages WHERE id = 701").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("hi", c.getString(0))
            assertTrue(c.isNull(1))
        }
    }

    private companion object { const val DB = "native-chat-delegation-migration-test" }
}
