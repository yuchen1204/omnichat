package com.example.workspace

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for MessageBus dead-agent behavior (Fix #1).
 *
 * Validates: Requirements REQ-1.1, REQ-1.2, REQ-1.3
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageBusDeadAgentTest {

    private lateinit var messageBus: MessageBus

    @Before
    fun setUp() {
        messageBus = MessageBus()
    }

    /**
     * Test 1: send to a removed agent must not hang.
     *
     * After removeInbox("a"), calling send("a", msg) should complete immediately
     * (the closed channel causes ClosedSendChannelException which is silently dropped),
     * rather than blocking forever on a RENDEZVOUS channel with no consumer.
     *
     * Validates: REQ-1.1, REQ-1.2
     */
    @Test
    fun testSendToRemovedAgentDoesNotHang() = runTest {
        // Arrange: create inbox then remove it (simulating agent death)
        messageBus.getOrCreateInbox("a")
        messageBus.removeInbox("a")

        val msg = TeamMessage.Text(from = "sender", content = "hello")

        // Act + Assert: send must complete within 100ms, not hang
        withTimeout(100L) {
            // MessageBus.send() catches ClosedSendChannelException internally and drops the message.
            // If getOrCreateInbox returned a RENDEZVOUS channel instead of a closed one,
            // this would hang indefinitely and the withTimeout would throw TimeoutCancellationException.
            messageBus.send("a", msg)
        }
        // If we reach here, the send completed without hanging — test passes.
    }

    /**
     * Test 2: explicitCreateInbox after removeInbox restores a normal working channel.
     *
     * After removeInbox("a"), calling explicitCreateInbox("a") should clear the
     * removedInboxes marker and create a fresh Channel. Subsequent send/receive
     * must work normally.
     *
     * Validates: REQ-1.3
     */
    @Test
    fun testExplicitCreateInboxAfterRemoveWorks() = runTest {
        // Arrange: remove the inbox first (as if the agent died)
        messageBus.removeInbox("a")

        // Act: re-create the inbox explicitly (as spawnTeammate would do)
        messageBus.explicitCreateInbox("a")

        val msg = TeamMessage.Text(from = "sender", content = "restored")

        // Assert: the channel returned by getOrCreateInbox is a normal open channel
        val channel = messageBus.getOrCreateInbox("a")
        assertFalse("Channel should be open after explicitCreateInbox", channel.isClosedForSend)

        // Assert: send and receive work normally on the restored channel
        withTimeout(1000L) {
            messageBus.send("a", msg)
            val received = messageBus.receive("a")
            assertEquals("restored", (received as TeamMessage.Text).content)
            assertEquals("sender", received.from)
        }
    }
}
