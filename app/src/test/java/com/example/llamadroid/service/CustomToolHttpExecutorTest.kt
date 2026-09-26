package com.example.llamadroid.service

import com.example.llamadroid.data.db.CustomToolEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomToolHttpExecutorTest {
    private fun tool(template: String) = CustomToolEntity(
        name = "api_lookup",
        description = "API lookup",
        parametersJson = "{}",
        requiredParamsJson = "[]",
        commandTemplate = template,
        exampleUsage = "api_lookup"
    )

    @Test
    fun preparesCurlJsonWithoutShellExpansion() {
        val request = CustomToolHttpExecutor.prepare(
            tool("curl -s -X POST -H 'Authorization: Bearer {token}' --json '{body}' https://api.example.com/v1/items"),
            mapOf("token" to "abc", "body" to "{\"name\":\"test\"}")
        )
        assertEquals("POST", request.method)
        assertEquals("https://api.example.com/v1/items", request.url)
        assertEquals("{\"name\":\"test\"}", request.body)
        assertTrue(request.headers.contains("Authorization" to "Bearer abc"))
    }

    @Test
    fun rejectsFileUploadAndPrivateTargets() {
        assertThrows(IllegalArgumentException::class.java) {
            CustomToolHttpExecutor.prepare(tool("curl --data-binary @secret https://api.example.com"), emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CustomToolHttpExecutor.prepare(tool("curl http://127.0.0.1/private"), emptyMap())
        }
    }

    @Test
    fun appliesCurlTimeoutsAndEncodesGetData() {
        val request = CustomToolHttpExecutor.prepare(
            tool("curl -G --connect-timeout 7 --max-time 19 -d 'query=hello world' https://api.example.com/search"),
            emptyMap()
        )
        assertEquals(7, request.connectTimeoutSeconds)
        assertEquals(19, request.maxTimeSeconds)
        assertEquals("https://api.example.com/search?query=hello%20world", request.url)
        assertEquals(null, request.body)
    }
}
