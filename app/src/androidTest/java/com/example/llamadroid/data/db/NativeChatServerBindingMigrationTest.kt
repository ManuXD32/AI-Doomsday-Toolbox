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
class NativeChatServerBindingMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(AppDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate127To128_keepsChatsUnboundWithKvCacheOff() {
        helper.createDatabase(DB, 127).apply {
            execSQL(
                "INSERT INTO llama_chats (id, title, lastModified, contextSize, pinnedToAiHub, " +
                    "isEphemeral, deleteAfterSession) VALUES (501, 'Old chat', 1, 8192, 0, 0, 0)"
            )
            close()
        }
        val migrated = helper.runMigrationsAndValidate(
            DB, 128, true, NativeChatServerBindingMigration.MIGRATION_127_128
        )
        migrated.query("SELECT serverId, persistKvCache FROM llama_chats WHERE id = 501").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertEquals(0, c.getInt(1))
        }
    }

    private companion object { const val DB = "native-chat-server-binding-migration-test" }
}
