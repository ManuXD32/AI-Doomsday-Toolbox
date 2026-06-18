package com.example.llamadroid.tama.db

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
class TamaDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(TamaDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate33To34_addsAdventureGateTables() {
        helper.createDatabase(TEST_DB, 33).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            34,
            true,
            TamaMigrations.MIGRATION_33_34
        )

        assertTableExists(migratedDb, "adventure_gate_profiles")
        assertTableExists(migratedDb, "adventure_gate_world_progress")
        assertTableExists(migratedDb, "adventure_gate_battle_state")
        migratedDb.query(
            "SELECT learnedAttackIdsJson, learnedMagicIdsJson FROM adventure_gate_profiles WHERE petId = 'missing'"
        ).use { cursor ->
            assertEquals(0, cursor.count)
        }
    }

    @Test
    fun migrate34To35_addsAdventureGateV2ProfileFieldsAndRefundsSkills() {
        helper.createDatabase(TEST_DB, 34).apply {
            execSQL(
                """
                INSERT INTO adventure_gate_profiles (
                    petId, level, xp, maxHp, maxMana, attack, magic, defense, speed,
                    learnedAttackIdsJson, equippedAttackIdsJson, learnedMagicIdsJson, equippedMagicIdsJson, updatedAt
                ) VALUES (
                    'pet', 8, 12, 190, 75, 32, 28, 24, 14,
                    '["paw_strike","quick_claw","guard_break"]',
                    '["quick_claw"]',
                    '["spark","guard","heal_dew","fire_puff"]',
                    '["fire_puff"]',
                    12345
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            35,
            true,
            TamaMigrations.MIGRATION_34_35
        )

        migratedDb.query(
            """
            SELECT currentHp, currentMana, skillPoints, purchasedSkillIdsJson,
                   equippedAttackIdsJson, equippedMagicIdsJson, lastRecoveryAt
            FROM adventure_gate_profiles WHERE petId = 'pet'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(190, cursor.getInt(0))
            assertEquals(75, cursor.getInt(1))
            assertEquals(7, cursor.getInt(2))
            assertEquals("[\"paw_strike\",\"spark\",\"guard\"]", cursor.getString(3))
            assertEquals("[\"paw_strike\"]", cursor.getString(4))
            assertEquals("[\"spark\",\"guard\"]", cursor.getString(5))
            assertEquals(12345L, cursor.getLong(6))
        }
    }

    @Test
    fun migrate35To36_addsAdventureGateEquipmentSlots() {
        helper.createDatabase(TEST_DB, 35).apply {
            execSQL(
                """
                INSERT INTO adventure_gate_profiles (
                    petId, level, xp, maxHp, maxMana, attack, magic, defense, speed,
                    currentHp, currentMana, skillPoints, purchasedSkillIdsJson,
                    learnedAttackIdsJson, equippedAttackIdsJson, learnedMagicIdsJson, equippedMagicIdsJson,
                    lastRecoveryAt, updatedAt
                ) VALUES (
                    'pet', 4, 10, 150, 55, 24, 20, 16, 12,
                    99, 22, 3, '["paw_strike","spark","guard"]',
                    '["paw_strike"]', '["paw_strike"]', '["spark","guard"]', '["spark","guard"]',
                    456, 789
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            36,
            true,
            TamaMigrations.MIGRATION_35_36
        )

        migratedDb.query(
            """
            SELECT equippedWeaponId, equippedShieldId, equippedRingId
            FROM adventure_gate_profiles WHERE petId = 'pet'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }
    }

    @Test
    fun migrate36To37_addsAdventureGateRelicSlot() {
        helper.createDatabase(TEST_DB, 36).apply {
            execSQL(
                """
                INSERT INTO adventure_gate_profiles (
                    petId, level, xp, maxHp, maxMana, attack, magic, defense, speed,
                    currentHp, currentMana, skillPoints, purchasedSkillIdsJson,
                    learnedAttackIdsJson, equippedAttackIdsJson, learnedMagicIdsJson, equippedMagicIdsJson,
                    equippedWeaponId, equippedShieldId, equippedRingId, lastRecoveryAt, updatedAt
                ) VALUES (
                    'pet', 4, 10, 150, 55, 24, 20, 16, 12,
                    99, 22, 3, '["paw_strike","spark","guard"]',
                    '["paw_strike"]', '["paw_strike"]', '["spark","guard"]', '["spark","guard"]',
                    'ag_weapon_sprout_baton', 'ag_shield_leaf_shell', 'ag_relic_regent_dream_key', 456, 789
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            37,
            true,
            TamaMigrations.MIGRATION_36_37
        )

        migratedDb.query(
            """
            SELECT equippedWeaponId, equippedShieldId, equippedRingId, equippedRelicId
            FROM adventure_gate_profiles WHERE petId = 'pet'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ag_weapon_sprout_baton", cursor.getString(0))
            assertEquals("ag_shield_leaf_shell", cursor.getString(1))
            assertTrue(cursor.isNull(2))
            assertEquals("ag_relic_regent_dream_key", cursor.getString(3))
        }
    }

    @Test
    fun migrate38To39_refreshesAdventureGateProfileStats() {
        helper.createDatabase(TEST_DB, 38).apply {
            execSQL(
                """
                INSERT INTO adventure_gate_profiles (
                    petId, level, xp, maxHp, maxMana, attack, magic, defense, speed, accuracy, evasion,
                    currentHp, currentMana, skillPoints, purchasedSkillIdsJson,
                    learnedAttackIdsJson, equippedAttackIdsJson, learnedMagicIdsJson, equippedMagicIdsJson,
                    equippedWeaponId, equippedShieldId, equippedRingId, equippedRelicId, lastRecoveryAt, updatedAt
                ) VALUES (
                    'pet', 10, 10, 120, 40, 18, 14, 10, 10, 100, 5,
                    999, 999, 9, '["paw_strike","spark","guard"]',
                    '["paw_strike"]', '["paw_strike"]', '["spark","guard"]', '["spark","guard"]',
                    'ag_weapon_clockwork_clawblade', 'ag_shield_snowbutton_bulwark',
                    'ag_ring_aurora', 'ag_relic_chrono_key', 456, 789
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            39,
            true,
            TamaMigrations.MIGRATION_38_39
        )

        migratedDb.query(
            """
            SELECT maxHp, maxMana, attack, magic, defense, speed, accuracy, evasion, currentHp, currentMana
            FROM adventure_gate_profiles WHERE petId = 'pet'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(250, cursor.getInt(0))
            assertEquals(115, cursor.getInt(1))
            assertEquals(50, cursor.getInt(2))
            assertEquals(42, cursor.getInt(3))
            assertEquals(53, cursor.getInt(4))
            assertEquals(30, cursor.getInt(5))
            assertEquals(125, cursor.getInt(6))
            assertEquals(22, cursor.getInt(7))
            assertEquals(250, cursor.getInt(8))
            assertEquals(115, cursor.getInt(9))
        }
    }

    @Test
    fun migrate39To40_addsNightArenaRuns() {
        helper.createDatabase(TEST_DB, 39).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            40,
            true,
            TamaMigrations.MIGRATION_39_40
        )

        assertTableExists(migratedDb, "adventure_gate_night_arena_runs")
        migratedDb.query("PRAGMA table_info(adventure_gate_night_arena_runs)").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue(columns.contains("petId"))
            assertTrue(columns.contains("nightKey"))
            assertTrue(columns.contains("levelsJson"))
            assertTrue(columns.contains("clearedLevelIdsJson"))
            assertTrue(columns.contains("createdAt"))
            assertTrue(columns.contains("updatedAt"))
        }
    }

    @Test
    fun migrate40To41_addsIntrospectionLevel() {
        helper.createDatabase(TEST_DB, 40).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            41,
            true,
            TamaMigrations.MIGRATION_40_41
        )

        migratedDb.query("PRAGMA table_info(tama_pets)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "introspectionLevel") {
                    found = true
                    assertEquals("REAL", cursor.getString(cursor.getColumnIndexOrThrow("type")))
                    assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("notnull")))
                    assertEquals("0", cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                }
            }
            assertTrue("introspectionLevel column should exist", found)
        }
    }

    @Test
    fun migrate41To42_addsExerciseLevelAndMarketQuotes() {
        helper.createDatabase(TEST_DB, 41).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            42,
            true,
            TamaMigrations.MIGRATION_41_42
        )

        migratedDb.query("PRAGMA table_info(tama_pets)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "exerciseLevel") {
                    found = true
                    assertEquals("REAL", cursor.getString(cursor.getColumnIndexOrThrow("type")))
                    assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("notnull")))
                    assertEquals("0", cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                }
            }
            assertTrue("exerciseLevel column should exist", found)
        }
        assertTableExists(migratedDb, "tama_market_quotes")
        migratedDb.query("PRAGMA table_info(tama_market_quotes)").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue(columns.contains("petId"))
            assertTrue(columns.contains("itemId"))
            assertTrue(columns.contains("quoteWeekKey"))
            assertTrue(columns.contains("currentPrice"))
            assertTrue(columns.contains("unitsSoldSinceRefresh"))
            assertTrue(columns.contains("updatedAt"))
        }
    }

    private fun assertTableExists(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String
    ) {
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName)
        ).use { cursor ->
            assertTrue("$tableName table should exist", cursor.moveToFirst())
        }
    }

    private companion object {
        const val TEST_DB = "tama-migration-test"
    }
}
