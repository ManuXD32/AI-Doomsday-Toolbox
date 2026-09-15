package com.example.llamadroid.tama.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TamaActionGateTest {
    @Test fun nestedActionsAndConcurrentCommandsShareOneMutationLane() = runBlocking {
        var balance = 0
        (0 until 40).map {
            async(Dispatchers.Default) {
                TamaActionGate.run {
                    val before = balance
                    delay(1)
                    TamaActionGate.run { balance = before + 1 }
                }
            }
        }.awaitAll()
        assertEquals(40, balance)
    }
}
