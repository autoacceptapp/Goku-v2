package com.goku.ai

import com.goku.ai.brain.DynamicContextCompressor
import com.goku.ai.data.LocalRagDocumentBrain
import com.goku.ai.data.ShortTermWorkingMemory
import com.goku.ai.data.TextCompressor
import com.goku.ai.voice.GokuKillSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GokuCoreUnitTest {

    @Test
    fun testTextCompressorRoundTripAndSavings() {
        val sample = "WhatsApp Chat | Rahul: Kal subah 9 baje milte hain office ke bahar | ".repeat(20)
        val compressed = TextCompressor.compress(sample)
        assertTrue("Compressed payload should be much smaller", compressed.size < sample.length / 3)
        assertEquals(sample, TextCompressor.decompress(compressed))
    }

    @Test
    fun testShortTermWorkingMemoryResolvesHinglishPronouns() {
        val memory = ShortTermWorkingMemory(maxTurns = 15)
        memory.addTurn("USER", "Rahul ko call karo", "contact:Rahul")
        val resolved = memory.resolvePronouns("Usse WhatsApp message bhejo")
        assertTrue("Expected Rahul in resolved prompt, got: $resolved", resolved.contains("Rahul"))
    }

    @Test
    fun testLocalRagDocumentBrainIndexesAndRetrieves() {
        val rag = LocalRagDocumentBrain()
        rag.indexDocument("bike_manual.txt", "Engine oil change interval is every 3000 km for optimal mileage.")
        val hits = rag.query("engine oil interval")
        assertTrue("Expected at least 1 RAG match", hits.isNotEmpty())
        assertTrue(hits.first().text.contains("3000 km"))
    }

    @Test
    fun testKillSwitchRecognizesStopPhrasesAndResets() {
        GokuKillSwitch.reset()
        assertFalse(GokuKillSwitch.isStopped.value)
        assertTrue(GokuKillSwitch.isStopPhrase("Goku stop"))
        assertTrue(GokuKillSwitch.isStopPhrase("goku ruko"))
        assertTrue(GokuKillSwitch.isStopPhrase("गोकू रुको"))
        assertFalse(GokuKillSwitch.isStopPhrase("Hey Goku torch on"))

        GokuKillSwitch.triggerStop("UnitTest")
        assertTrue(GokuKillSwitch.isStopped.value)
        GokuKillSwitch.reset()
        assertFalse(GokuKillSwitch.isStopped.value)
    }

    @Test
    fun testDynamicContextCompressorBoundsLength() {
        val noisy = (1..100).joinToString(" | ") { "Button $it" }
        val compact = DynamicContextCompressor.compressScreenTree(noisy, maxChars = 150)
        assertTrue(compact.length <= 150)
        assertTrue(compact.contains("Button 1"))
    }
}
