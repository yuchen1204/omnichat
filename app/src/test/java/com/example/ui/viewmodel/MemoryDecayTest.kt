package com.example.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryDecayTest {

    private fun computeDecayedConfidence(
        confidence: Int,
        lastReinforcedAt: Long,
        now: Long,
        pinned: Boolean
    ): Int {
        if (pinned) return confidence
        val daysSince = ((now - lastReinforcedAt) / 86_400_000L).toInt()
        if (daysSince <= 0) return confidence
        return maxOf(1, confidence - daysSince)
    }

    @Test
    fun `no decay when same day`() {
        val now = 1700000000000L
        assertEquals(5, computeDecayedConfidence(5, now, now, false))
    }

    @Test
    fun `decays by 1 per day`() {
        val now = 1700000000000L
        val threeDaysAgo = now - 3L * 86_400_000L
        assertEquals(2, computeDecayedConfidence(5, threeDaysAgo, now, false))
    }

    @Test
    fun `floors at 1`() {
        val now = 1700000000000L
        val tenDaysAgo = now - 10L * 86_400_000L
        assertEquals(1, computeDecayedConfidence(3, tenDaysAgo, now, false))
    }

    @Test
    fun `pinned items are exempt`() {
        val now = 1700000000000L
        val thirtyDaysAgo = now - 30L * 86_400_000L
        assertEquals(5, computeDecayedConfidence(5, thirtyDaysAgo, now, true))
    }

    @Test
    fun `confidence 1 stays 1 even after many days`() {
        val now = 1700000000000L
        val hundredDaysAgo = now - 100L * 86_400_000L
        assertEquals(1, computeDecayedConfidence(1, hundredDaysAgo, now, false))
    }
}
