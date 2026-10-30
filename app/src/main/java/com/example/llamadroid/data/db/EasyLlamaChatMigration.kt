package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Nullable links preserve every existing manual server and native-chat connection. */
object EasyLlamaChatMigration {
    val MIGRATION_125_126 = object : Migration(125, 126) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE llama_server_cards ADD COLUMN easyModelId TEXT DEFAULT NULL")
            db.execSQL("CREATE UNIQUE INDEX index_llama_server_cards_easyModelId ON llama_server_cards(easyModelId)")
            db.execSQL("ALTER TABLE llama_servers ADD COLUMN managedServerCardId INTEGER DEFAULT NULL")
            db.execSQL("CREATE UNIQUE INDEX index_llama_servers_managedServerCardId ON llama_servers(managedServerCardId)")
        }
    }
}
