package com.example.llamadroid.ui.theme.sprites

import com.example.llamadroid.data.PixelThemeVariant
import com.example.llamadroid.service.AgentService.Companion.AgentRole
import com.example.llamadroid.ui.theme.pixelSpritePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelSpritesTest {
    @Test
    fun everySpriteIs16x16WithAllowedCharacters() {
        PixelSprites.all.forEach { sprite ->
            assertEquals("${sprite.key} rows", PixelSprite.SIZE, sprite.rows.size)
            sprite.rows.forEachIndexed { y, row ->
                assertEquals("${sprite.key} row $y width", PixelSprite.SIZE, row.length)
                row.forEach { ch ->
                    assertTrue("${sprite.key} row $y has '$ch'", ch in PixelSprite.PALETTE_CHARS)
                }
            }
        }
    }

    @Test
    fun everyKeyHasExactlyOneSprite() {
        val keys = PixelSprites.all.map { it.key }
        assertEquals("duplicate sprites", keys.size, keys.toSet().size)
        assertEquals(AppSpriteKey.entries.toSet(), keys.toSet())
    }

    @Test
    fun everySpriteDrawsSomething() {
        PixelSprites.all.forEach { sprite ->
            assertTrue("${sprite.key} is empty", sprite.rows.any { row -> row.any { it != '.' } })
        }
    }

    @Test
    fun vectorNameMapPointsToExistingSprites() {
        SpriteMappings.vectorSprites.values.forEach { key ->
            assertNotNull(PixelSprites.get(key))
        }
        assertEquals(AppSpriteKey.HOME, SpriteMappings.forVectorName("Filled.Home"))
        assertEquals(AppSpriteKey.CHATS, SpriteMappings.forVectorName("AutoMirrored.Filled.Chat"))
        assertEquals(null, SpriteMappings.forVectorName("Filled.NoSuchIcon"))
    }

    @Test
    fun agentRoleMappingIsCompleteAndDistinct() {
        val sprites = AgentRole.entries.map { SpriteMappings.forAgentRole(it) }
        assertEquals(AgentRole.entries.size, sprites.toSet().size)
        AgentRole.entries.forEach { role ->
            assertEquals(SpriteMappings.forAgentRole(role), SpriteMappings.forAgentEmoji(role.emoji))
        }
        assertEquals(AppSpriteKey.AGENT_ROBOT, SpriteMappings.forAgentEmoji("🦄"))
    }

    @Test
    fun spritePixelsUsePaletteAndKeepTransparency() {
        val palette = pixelSpritePalette(PixelThemeVariant.CANDY_POP, dark = false)
        val sprite = PixelSprites.get(AppSpriteKey.AGENT_ROBOT)
        val pixels = spritePixels(sprite, palette)
        assertEquals(PixelSprite.SIZE * PixelSprite.SIZE, pixels.size)
        assertEquals(0, pixels[0])
        assertTrue(pixels.any { it == spriteArgb('o', palette) })
    }

    @Test
    fun accentFallsBackToShadeWhenItMatchesOutline() {
        val palette = pixelSpritePalette(PixelThemeVariant.POCKET_LCD, dark = false)
        assertEquals(palette.accent, palette.outline)
        assertNotEquals(spriteArgb('o', palette), spriteArgb('a', palette))
    }
}
