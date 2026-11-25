package com.goku.ai

import com.goku.ai.adb.LocalAdbEngine
import com.goku.ai.automation.GokuJsBridge
import com.goku.ai.brain.DynamicContextCompressor
import com.goku.ai.brain.OFFLINE_MODEL_PRESETS
import com.goku.ai.brain.OfflineModelDownloadManager
import com.goku.ai.data.FtsQuerySanitizer
import com.goku.ai.data.LocalRagDocumentBrain
import com.goku.ai.data.ShortTermWorkingMemory
import com.goku.ai.data.TextCompressor
import com.goku.ai.services.SmartCallScreenerService
import com.goku.ai.voice.GokuKillSwitch
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class GokuCoreUnitTest {

    @Before
    fun setUp() {
        GokuKillSwitch.reset()
    }

    @After
    fun tearDown() {
        GokuKillSwitch.reset()
    }

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
    fun testLocalRagDocumentBrainConcurrencyAndCleanup() {
        val rag = LocalRagDocumentBrain()
        val pool = Executors.newFixedThreadPool(4)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(4)
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

        repeat(4) { workerId ->
            pool.execute {
                try {
                    assertTrue(startLatch.await(2L, TimeUnit.SECONDS))
                    for (i in 0 until 25) {
                        val docName = "doc_${workerId}_$i.txt"
                        rag.indexDocument(docName, "Solar battery inverter maintenance cycle $i check")
                        rag.query("battery maintenance")
                        if (i % 2 == 0) {
                            rag.removeDocument(docName)
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("Concurrent RAG workers timed out", doneLatch.await(5L, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertTrue("Unexpected concurrency errors: $errors", errors.isEmpty())

        for (workerId in 0 until 4) {
            for (i in 0 until 25) {
                rag.removeDocument("doc_${workerId}_$i.txt")
            }
        }
        assertEquals(0, rag.indexedCount())
        assertTrue(rag.query("battery maintenance").isEmpty())
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

    @Test
    fun testSmartCallScreenerDialableNumberValidation() {
        assertTrue(SmartCallScreenerService.isValidDialableNumber("+919876543210"))
        assertTrue(SmartCallScreenerService.isValidDialableNumber("98765 43210"))
        assertTrue(SmartCallScreenerService.isValidDialableNumber("011-23456789"))

        assertFalse(SmartCallScreenerService.isValidDialableNumber(""))
        assertFalse(SmartCallScreenerService.isValidDialableNumber("   "))
        assertFalse(SmartCallScreenerService.isValidDialableNumber("Unknown"))
        assertFalse(SmartCallScreenerService.isValidDialableNumber("unknown"))
        assertFalse(SmartCallScreenerService.isValidDialableNumber("12345"))
        assertFalse(SmartCallScreenerService.isValidDialableNumber("0000000000"))
    }

    @Test
    fun testGokuJsBridgeSleepAbortsPromptlyOnKillSwitch() {
        val sleeperThread = Thread.currentThread()
        val startedLatch = CountDownLatch(1)
        val stopper = thread {
            assertTrue(startedLatch.await(1L, TimeUnit.SECONDS))
            val waitDeadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400L)
            while (sleeperThread.state != Thread.State.TIMED_WAITING && System.nanoTime() < waitDeadlineNs) {
                Thread.yield()
            }
            GokuKillSwitch.triggerStop("UnitTestSleepAbort")
        }
        val startMs = System.currentTimeMillis()
        var thrown: Throwable? = null
        try {
            startedLatch.countDown()
            GokuJsBridge.sleepWithKillSwitch(2500L, dryRun = false)
        } catch (t: Throwable) {
            thrown = t
        } finally {
            stopper.join(1000L)
        }
        val elapsedMs = System.currentTimeMillis() - startMs
        assertTrue("Expected SecurityException when KillSwitch triggered, got: $thrown", thrown is SecurityException)
        assertTrue("Expected sleep abort well under 500ms, took ${elapsedMs}ms", elapsedMs < 500L)
    }

    @Test
    fun testGokuJsBridgeBoundedCallTimeoutAndKillSwitchAbort() {
        // 1. Timeout returns null promptly without hanging
        val startTimeout = System.currentTimeMillis()
        val timedOut = GokuJsBridge.runBoundedBridgeCallInternal(timeoutMs = 150L) {
            delay(2000L)
            "never"
        }
        val timeoutElapsed = System.currentTimeMillis() - startTimeout
        assertNull("Expected null on bridge timeout", timedOut)
        assertTrue("Expected bounded timeout < 600ms, took ${timeoutElapsed}ms", timeoutElapsed < 600L)

        // 2. KillSwitch aborts mid-call deterministically once block enters
        val enteredLatch = CountDownLatch(1)
        val stopper = thread {
            assertTrue(enteredLatch.await(1L, TimeUnit.SECONDS))
            GokuKillSwitch.triggerStop("UnitTestBridgeAbort")
        }
        val startKill = System.currentTimeMillis()
        var thrown: Throwable? = null
        try {
            GokuJsBridge.runBoundedBridgeCallInternal(timeoutMs = 3000L) {
                enteredLatch.countDown()
                delay(2500L)
                "never"
            }
        } catch (t: Throwable) {
            thrown = t
        } finally {
            stopper.join(1000L)
        }
        val killElapsed = System.currentTimeMillis() - startKill
        assertTrue("Expected SecurityException on KillSwitch, got: $thrown", thrown is SecurityException)
        assertTrue("Expected kill abort < 500ms, took ${killElapsed}ms", killElapsed < 500L)
    }

    @Test
    fun testGokuJsBridgeEnforcesDeadlineMs() {
        val expiredDeadline = System.currentTimeMillis() - 10L
        var sleepThrown: Throwable? = null
        try {
            GokuJsBridge.sleepWithKillSwitch(200L, dryRun = false, deadlineMs = expiredDeadline)
        } catch (t: Throwable) {
            sleepThrown = t
        }
        assertTrue("Expected SecurityException when deadlineMs already expired", sleepThrown is SecurityException)

        var callThrown: Throwable? = null
        try {
            GokuJsBridge.runBoundedBridgeCallInternal(
                timeoutMs = 1000L,
                deadlineMs = System.currentTimeMillis() + 60L
            ) {
                delay(500L)
                "never"
            }
        } catch (t: Throwable) {
            callThrown = t
        }
        assertTrue("Expected SecurityException when deadlineMs elapses mid-call", callThrown is SecurityException)
    }

    @Test
    fun testEscapeAdbInputTextNeutralizesShellMetacharacters() {
        val raw = "hi; rm -rf / & echo \$HOME\nnext\tline"
        val escaped = LocalAdbEngine.escapeAdbInputText(raw)
        assertFalse(escaped.contains("\n"))
        assertFalse(escaped.contains("\t"))
        assertTrue(escaped.contains("%s"))
        assertTrue(escaped.contains("\\;"))
        assertTrue(escaped.contains("\\&"))
        assertTrue(escaped.contains("\\$"))
    }

    @Test
    fun testFtsQuerySanitizerEscapesLikeWildcards() {
        val escaped = FtsQuerySanitizer.escapeLikeQuery("100%_test\\path")
        assertEquals("100\\%\\_test\\\\path", escaped)
    }

    @Test
    fun testOfflineModelPresetsAnd404HinglishGuard() {
        val defaultPreset = OFFLINE_MODEL_PRESETS.first()
        assertTrue(defaultPreset.isVerifiedDefault)
        assertTrue(defaultPreset.directUrl.contains("Qwen2.5-1.5B-Instruct"))
        assertTrue(OFFLINE_MODEL_PRESETS.all { it.directUrl.startsWith("https://") && it.directUrl.endsWith(".task") })

        val msg404 = OfflineModelDownloadManager.formatHttpErrorMessage(404)
        assertTrue(msg404.contains("404"))
        assertTrue(msg404.contains(defaultPreset.name))
        assertTrue(msg404.contains(".task/.bin"))
    }
}
