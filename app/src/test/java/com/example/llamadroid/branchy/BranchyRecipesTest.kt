package com.example.llamadroid.branchy

import com.example.llamadroid.training.CuratedDatasets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchyRecipesTest {
    @Test
    fun everyDatasetIdExistsAndIsOpen() {
        BranchyRecipes.all.forEach { recipe ->
            recipe.stages.forEach { stage ->
                stage.mix.forEach { (id, weight) ->
                    val ds = CuratedDatasets.byId(id)
                    assertNotNull("${recipe.id}: unknown dataset $id", ds)
                    assertTrue("${recipe.id}: gated dataset $id needs a token", ds!!.gated.not())
                    assertTrue("${recipe.id}: weight of $id", weight > 0.0)
                }
            }
        }
    }

    @Test
    fun weightsSumToOnePerStageAndHaveNoDuplicates() {
        BranchyRecipes.all.forEach { recipe ->
            recipe.stages.forEachIndexed { i, stage ->
                assertEquals("${recipe.id} stage $i", 1.0, stage.mix.sumOf { it.second }, 1e-9)
                assertEquals(stage.mix.size, stage.mix.map { it.first }.distinct().size)
            }
        }
    }

    @Test
    fun tokenTargetsFollowTheSizeClass() {
        val small = BranchyRecipes.byId("mini_assistant_30m")!!
        val big = BranchyRecipes.byId("mini_assistant_120m")!!
        assertEquals(3e8, small.totalTokens, 1.0)
        assertEquals(1.2e9, big.totalTokens, 1.0)
        listOf(small, big).forEach { r ->
            assertEquals(2, r.stages.size)
            assertEquals(0.15, r.stages[1].tokensTarget / r.totalTokens, 1e-9)
            assertTrue(r.stages[1].lrScale < r.stages[0].lrScale)
            assertTrue(r.vocab in listOf(8192, 16384, 32768))
        }
        assertEquals(setOf("mini_assistant_30m", "mini_assistant_120m"), BranchyRecipes.all.map { it.id }.toSet())
        assertNull(BranchyRecipes.byId("nope"))
        assertNull(BranchyRecipes.byId(null))
    }

    @Test
    fun stageShapeMatchesTheSpec() {
        val r = BranchyRecipes.byId("mini_assistant_30m")!!
        val pre = r.stages[0].mix.map { it.first }
        val sft = r.stages[1].mix.map { it.first }
        // Pretrain: general knowledge + code + stories; SFT: chat + tool use + code-instruct + Spanish.
        assertTrue("cosmopedia-100k" in pre && "wikipedia-en" in pre && "tinystories" in pre)
        assertTrue("smol-smoltalk" in sft && "hermes-function-calling" in sft)
        assertTrue("openhermes-2.5-es" in sft && "codealpaca-20k" in sft)
        assertTrue(r.datasetIds().size == r.datasetIds().distinct().size)
    }
}
