package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLocalPlanSupportTest {
    @Test
    fun `rejects python web backend while allowing finite python console checks`() {
        val backend = AgentLocalPlanSupport.validateLocalSandboxPlan(
            "Use static HTML/CSS/JS with Python as backend; Backend (Python) serves the page."
        )
        assertFalse(backend.accepted)
        assertEquals(listOf("Python"), backend.issues.map { it.backend })

        val console = AgentLocalPlanSupport.validateLocalSandboxPlan(
            "Run finite Python console checks with runtime=python, ui=console."
        )
        assertTrue(console.accepted)
        assertTrue(console.issues.isEmpty())
    }

    @Test
    fun `recognizes only explicit backend declaration forms`() {
        val result = AgentLocalPlanSupport.validateLocalSandboxPlan(
            "Backend: Node.js\nBuild server.js with Express\nDjango application"
        )

        assertFalse(result.accepted)
        assertEquals(
            listOf("Node.js", "Express", "Django"),
            result.issues.map { it.backend }
        )
    }

    @Test
    fun `rejects explicit unsupported local server architectures`() {
        val result = AgentLocalPlanSupport.validateLocalSandboxPlan(
            plan = """
                Build a Node.js backend with an Express API.
                Add a Flask service for the calculation endpoint.
                A Django application will persist the run state.
            """.trimIndent(),
            summary = "Implement the server-backed version."
        )

        assertFalse(result.accepted)
        assertEquals(
            listOf("Node.js", "Express", "Flask", "Django"),
            result.issues.map { it.backend }
        )
        assertTrue(result.issues.all { it.code == AgentLocalPlanSupport.UNSUPPORTED_BACKEND_CODE })
        assertTrue(result.errorMessage.orEmpty().contains(AgentLocalPlanSupport.UNSUPPORTED_BACKEND_CODE))
    }

    @Test
    fun `permits negations comparisons static web workers and python console checks`() {
        val result = AgentLocalPlanSupport.validateLocalSandboxPlan(
            plan = """
                Use static HTML/CSS/JS with Web Workers and runtime=web, ui=web.
                Do not use Node.js, Express, Flask, or Django; those servers are unavailable here.
                Compared with a Node.js backend, the project uses browser-only workers.
                Run finite Python console checks with runtime=python and ui=console.
            """.trimIndent()
        )

        assertTrue(result.accepted)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `allows comparison target but rejects selected unsupported backend`() {
        val rejected = AgentLocalPlanSupport.validateLocalSandboxPlan(
            "Use a Node.js backend instead of a static Web Worker implementation."
        )
        assertFalse(rejected.accepted)
        assertEquals(listOf("Node.js"), rejected.issues.map { it.backend })

        val accepted = AgentLocalPlanSupport.validateLocalSandboxPlan(
            "Use a static Web Worker implementation instead of a Node.js backend."
        )
        assertTrue(accepted.accepted)
    }

    @Test
    fun `keeps later positive architecture after a negated alternative visible`() {
        val result = AgentLocalPlanSupport.validateLocalSandboxPlan(
            "Do not use Node.js, but use a Flask backend for the API."
        )

        assertFalse(result.accepted)
        assertEquals(listOf("Flask"), result.issues.map { it.backend })
    }

    @Test
    fun `keeps later positive architecture after a comparison visible`() {
        val result = AgentLocalPlanSupport.validateLocalSandboxPlan(
            "Compared with a Node.js backend, use Flask for the API."
        )

        assertFalse(result.accepted)
        assertEquals(listOf("Flask"), result.issues.map { it.backend })
    }

    @Test
    fun `recognizes based backend wording without treating plain product names as architecture`() {
        val result = AgentLocalPlanSupport.validateLocalSandboxPlan(
            "Node-based backend with a browser client."
        )

        assertFalse(result.accepted)
        assertEquals(listOf("Node.js"), result.issues.map { it.backend })
    }

    @Test
    fun `does not reject incidental words without architecture relation`() {
        val result = AgentLocalPlanSupport.validateLocalSandboxPlan(
            "Compare framework names in documentation: Node.js, Express, Flask, and Django."
        )

        assertTrue(result.accepted)
    }
}
