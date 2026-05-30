package com.example.workspace

import org.junit.Assert.*
import org.junit.Test

class StructuredMessageTest {

    @Test
    fun testShutdownRequestToJson() {
        val request = StructuredMessage.ShutdownRequest(
            requestId = "shutdown_test_123",
            from = "orchestrator",
            reason = "Task completed",
        )

        val json = request.toJson()
        assertEquals("shutdown_request", json.getString("type"))
        assertEquals("shutdown_test_123", json.getString("requestId"))
        assertEquals("orchestrator", json.getString("from"))
        assertEquals("Task completed", json.getString("reason"))
    }

    @Test
    fun testShutdownResponseApproveToJson() {
        val response = StructuredMessage.ShutdownResponse(
            requestId = "shutdown_test_123",
            from = "worker-1",
            approve = true,
        )

        val json = response.toJson()
        assertEquals("shutdown_response", json.getString("type"))
        assertTrue(json.getBoolean("approve"))
    }

    @Test
    fun testShutdownResponseRejectToJson() {
        val response = StructuredMessage.ShutdownResponse(
            requestId = "shutdown_test_123",
            from = "worker-1",
            approve = false,
            reason = "Still working on critical task",
        )

        val json = response.toJson()
        assertFalse(json.getBoolean("approve"))
        assertEquals("Still working on critical task", json.getString("reason"))
    }

    @Test
    fun testPlanApprovalToJson() {
        val approval = StructuredMessage.PlanApprovalResponse(
            requestId = "plan_123",
            from = "orchestrator",
            approved = true,
            permissionMode = "default",
        )

        val json = approval.toJson()
        assertEquals("plan_approval_response", json.getString("type"))
        assertTrue(json.getBoolean("approved"))
    }

    @Test
    fun testParseShutdownRequestFromJson() {
        val jsonStr = """{"type":"shutdown_request","requestId":"test_1","from":"lead","reason":"timeout"}"""
        val parsed = StructuredMessage.fromJson(jsonStr)

        assertNotNull(parsed)
        assertTrue(parsed is StructuredMessage.ShutdownRequest)
        val request = parsed as StructuredMessage.ShutdownRequest
        assertEquals("test_1", request.requestId)
        assertEquals("lead", request.from)
        assertEquals("timeout", request.reason)
    }

    @Test
    fun testParseShutdownResponseFromJson() {
        val jsonStr = """{"type":"shutdown_response","requestId":"test_2","from":"worker","approve":true}"""
        val parsed = StructuredMessage.fromJson(jsonStr)

        assertNotNull(parsed)
        assertTrue(parsed is StructuredMessage.ShutdownResponse)
        val response = parsed as StructuredMessage.ShutdownResponse
        assertEquals("test_2", response.requestId)
        assertTrue(response.approve)
    }

    @Test
    fun testParsePlanApprovalFromJson() {
        val jsonStr = """{"type":"plan_approval_response","requestId":"plan_1","from":"lead","approved":false,"feedback":"Need more detail"}"""
        val parsed = StructuredMessage.fromJson(jsonStr)

        assertNotNull(parsed)
        assertTrue(parsed is StructuredMessage.PlanApprovalResponse)
        val approval = parsed as StructuredMessage.PlanApprovalResponse
        assertEquals("plan_1", approval.requestId)
        assertFalse(approval.approved)
        assertEquals("Need more detail", approval.feedback)
    }

    @Test
    fun testParseInvalidJsonReturnsNull() {
        val parsed = StructuredMessage.fromJson("not valid json")
        assertNull(parsed)
    }

    @Test
    fun testParseUnknownTypeReturnsNull() {
        val jsonStr = """{"type":"unknown_type","requestId":"test"}"""
        val parsed = StructuredMessage.fromJson(jsonStr)
        assertNull(parsed)
    }

    @Test
    fun testStructuredMessageFactory() {
        val request = StructuredMessageFactory.createShutdownRequest("orchestrator", "Task done")
        assertNotNull(request.requestId)
        assertTrue(request.requestId.contains("shutdown"))
        assertEquals("orchestrator", request.from)
        assertEquals("Task done", request.reason)

        val approved = StructuredMessageFactory.createShutdownApproved("req_1", "worker")
        assertTrue(approved.approve)
        assertEquals("req_1", approved.requestId)

        val rejected = StructuredMessageFactory.createShutdownRejected("req_2", "worker", "Still working")
        assertFalse(rejected.approve)
        assertEquals("Still working", rejected.reason)

        val planApproved = StructuredMessageFactory.createPlanApproved("plan_1", "lead", "default")
        assertTrue(planApproved.approved)
        assertEquals("default", planApproved.permissionMode)

        val planRejected = StructuredMessageFactory.createPlanRejected("plan_2", "lead", "Need details")
        assertFalse(planRejected.approved)
        assertEquals("Need details", planRejected.feedback)
    }
}
