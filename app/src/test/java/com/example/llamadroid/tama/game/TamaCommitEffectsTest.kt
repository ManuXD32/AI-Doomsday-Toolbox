package com.example.llamadroid.tama.game

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaCommitEffectsTest {
    @Test fun rollbackDoesNotPublishNotificationAndNestedRequestsCoalesce() = runBlocking {
        val published = mutableListOf<String>()
        runCatching {
            TamaCommitEffects.afterCommit {
                TamaCommitEffects.deferOrRun("pet") { published += "rolled back" }
                error("database failure")
            }
        }
        assertTrue(published.isEmpty())
        TamaCommitEffects.afterCommit {
            TamaCommitEffects.deferOrRun("pet") { published += "stale" }
            TamaCommitEffects.afterCommit {
                TamaCommitEffects.deferOrRun("pet") { published += "latest" }
                assertTrue(published.isEmpty())
            }
        }
        assertEquals(listOf("latest"), published)
    }

    @Test fun deliveryFailureCannotUndoCommittedResult() = runBlocking {
        val result = TamaCommitEffects.afterCommit {
            TamaCommitEffects.deferOrRun("notification") { error("alarm unavailable") }
            42
        }
        assertEquals(42, result)
    }
}
