package com.goku.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goku.ai.adb.LocalAdbEngine
import com.goku.ai.automation.GokuJsBridge
import com.goku.ai.automation.GokuScriptAbortError
import com.goku.ai.automation.SelfCodingPluginEngine
import com.goku.ai.body.UniversalAppPilot
import com.goku.ai.brain.DynamicContextCompressor
import com.goku.ai.brain.OFFLINE_MODEL_PRESETS
import com.goku.ai.brain.OfflineModelDownloadManager
import com.goku.ai.data.FtsQuerySanitizer
import com.goku.ai.data.GokuEncryptedSettings
import com.goku.ai.data.GokuVaultRepository
import com.goku.ai.data.LearnedSkillEntity
import com.goku.ai.data.LocalRagDocumentBrain
import com.goku.ai.data.ShortTermWorkingMemory
import com.goku.ai.data.TextCompressor
import com.goku.ai.services.SmartCallScreenerService
import com.goku.ai.voice.GokuEarAndVoiceEngine
import com.goku.ai.voice.GokuKillSwitch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
        val enteringSleepLatch = CountDownLatch(1)
        val finishedLatch = CountDownLatch(1)
        val caughtError = AtomicReference<Throwable?>(null)

        val worker = thread(name = "js-bridge-sleeper") {
            try {
                enteringSleepLatch.countDown()
                GokuJsBridge.sleepWithKillSwitch(60_000L, dryRun = false)
            } catch (t: Throwable) {
                caughtError.set(t)
            } finally {
                finishedLatch.countDown()
            }
        }

        assertTrue("Worker thread did not start in time", enteringSleepLatch.await(2L, TimeUnit.SECONDS))
        GokuKillSwitch.triggerStop("UnitTestSleepAbort")

        assertTrue(
            "Expected sleepWithKillSwitch(60_000L) to abort promptly on KillSwitch",
            finishedLatch.await(3L, TimeUnit.SECONDS)
        )
        worker.join(1000L)
        val thrown = caughtError.get()
        assertTrue(
            "Expected GokuScriptAbortError when KillSwitch triggered, got: $thrown",
            thrown is GokuScriptAbortError || thrown is SecurityException
        )
    }

    @Test
    fun testGokuJsBridgeBoundedCallTimeoutAndKillSwitchAbort() {
        // 1. Timeout returns null without hanging a 30s suspend block
        val timedOut = GokuJsBridge.runBoundedBridgeCallInternal(timeoutMs = 80L) {
            delay(30_000L)
            "never"
        }
        assertNull("Expected null on bridge timeout", timedOut)

        // 2. KillSwitch aborts mid-call deterministically once block enters
        val enteredLatch = CountDownLatch(1)
        val finishedLatch = CountDownLatch(1)
        val caughtError = AtomicReference<Throwable?>(null)

        val caller = thread(name = "js-bridge-bounded-call") {
            try {
                GokuJsBridge.runBoundedBridgeCallInternal(timeoutMs = 30_000L) {
                    enteredLatch.countDown()
                    delay(30_000L)
                    "never"
                }
            } catch (t: Throwable) {
                caughtError.set(t)
            } finally {
                finishedLatch.countDown()
            }
        }

        assertTrue("Bridge call did not enter suspend block", enteredLatch.await(2L, TimeUnit.SECONDS))
        GokuKillSwitch.triggerStop("UnitTestBridgeAbort")

        assertTrue("Expected bounded call to abort on KillSwitch", finishedLatch.await(3L, TimeUnit.SECONDS))
        caller.join(1000L)
        val thrown = caughtError.get()
        assertTrue(
            "Expected GokuScriptAbortError on KillSwitch, got: $thrown",
            thrown is GokuScriptAbortError || thrown is SecurityException
        )
    }

    @Test
    fun testGokuJsBridgeEnforcesDeadlineMs() {
        val expiredDeadline = System.currentTimeMillis() - 10_000L
        var sleepThrown: Throwable? = null
        try {
            GokuJsBridge.sleepWithKillSwitch(5000L, dryRun = false, deadlineMs = expiredDeadline)
        } catch (t: Throwable) {
            sleepThrown = t
        }
        assertTrue(
            "Expected GokuScriptAbortError when deadlineMs already expired, got: $sleepThrown",
            sleepThrown is GokuScriptAbortError || sleepThrown is SecurityException
        )

        var callThrown: Throwable? = null
        try {
            GokuJsBridge.runBoundedBridgeCallInternal(
                timeoutMs = 30_000L,
                deadlineMs = System.currentTimeMillis() + 50L
            ) {
                delay(30_000L)
                "never"
            }
        } catch (t: Throwable) {
            callThrown = t
        }
        assertTrue(
            "Expected GokuScriptAbortError when deadlineMs elapses mid-call, got: $callThrown",
            callThrown is GokuScriptAbortError || callThrown is SecurityException
        )
    }

    @Test
    fun testGokuEncryptedSettingsBatchUpdateAndPersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = GokuEncryptedSettings(context)

        settings.batchUpdate(
            geminiKey = "  gem-secret-key-123  ",
            groqKey = "groq-key-456",
            deepSeekKey = "ds-key-789",
            openRouterKey = "or-key-012",
            sosContacts = "+919876543210, 9123456789; 123",
            githubRepo = "user/goku-backup",
            githubToken = "ghp_secretToken999",
            telegramBot = "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            telegramChatId = "987654321",
            smartCallPolicy = "Gate par parcel rakh do",
            smartCallEnabled = true,
            dryRunMode = true,
            autoAdSkip = false,
            otpAutopilot = true,
            mistakeBlocker = false,
            workFocusMode = true,
            localModelPath = "/data/user/0/com.aistudio.goku.xk7m9v/files/models/goku-4bit.gguf"
        )

        assertEquals("gem-secret-key-123", settings.geminiApiKey)
        assertEquals("groq-key-456", settings.groqApiKey)
        assertEquals("ds-key-789", settings.deepSeekApiKey)
        assertEquals("or-key-012", settings.openRouterApiKey)
        assertEquals("user/goku-backup", settings.githubRepo)
        assertEquals("ghp_secretToken999", settings.githubToken)
        assertEquals("123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11", settings.telegramBotToken)
        assertEquals("987654321", settings.telegramChatId)
        assertEquals("Gate par parcel rakh do", settings.smartCallPolicy)
        assertTrue(settings.smartCallEnabled)
        assertTrue(settings.dryRunMode)
        assertFalse(settings.autoAdSkipEnabled)
        assertTrue(settings.otpAutopilotEnabled)
        assertFalse(settings.mistakeBlockerEnabled)
        assertTrue(settings.workFocusMode)
        assertEquals(listOf("+919876543210", "9123456789"), settings.getSosContactsList())
        assertTrue(
            "Legacy .gguf path should migrate to default .task path, got: ${settings.localModelPath}",
            settings.localModelPath.endsWith("goku_offline.task")
        )
    }

    @Test
    fun testGokuVaultRepositoryFindMatchingSkillExactAndSubstring() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = GokuVaultRepository(context)

        val skillId = vault.upsertSkill(
            LearnedSkillEntity(
                name = "Morning Routine",
                triggerPhrase = "morning routine",
                stepsJson = """[{"action":"HOME","target":""}]"""
            )
        )
        try {
            val exactHit = vault.findMatchingSkill("Morning Routine")
            assertNotNull("Expected exact trigger match", exactHit)
            assertEquals("Morning Routine", exactHit?.name)

            val substringHit = vault.findMatchingSkill("Hey Goku please start my morning routine now")
            assertNotNull("Expected substring trigger match", substringHit)
            assertEquals("morning routine", substringHit?.triggerPhrase)

            val noHit = vault.findMatchingSkill("open calculator app")
            assertNull("Expected null for unrelated command", noHit)

            val blankHit = vault.findMatchingSkill("   ")
            assertNull("Expected null for blank input", blankHit)
        } finally {
            vault.deleteSkill(skillId)
        }
    }

    @Test
    fun testGokuJsBridgeTryCatchCannotSwallowAbortOrKillSwitch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = GokuVaultRepository(context)
        val adb = LocalAdbEngine.getInstance(context)
        val pilot = UniversalAppPilot(context, adb)
        val voice = GokuEarAndVoiceEngine.getInstance(context)
        val engine = SelfCodingPluginEngine(context, vault, adb, pilot, voice)

        // 1. Infinite loop wrapped in JS try/catch must still abort via GokuScriptAbortError (Error, not Exception)
        val infiniteLoopScript = """
            try {
                while (true) {}
            } catch (e) {
                goku.log("SWALLOWED_QUOTA_ABORT");
            }
        """.trimIndent()
        val loopResult = engine.runLivePlugin(infiniteLoopScript, dryRun = true)
        assertTrue(
            "Expected JS Sandbox halt/abort on infinite loop inside try/catch, got: $loopResult",
            loopResult.contains("halted", ignoreCase = true) || loopResult.contains("aborted", ignoreCase = true)
        )
        assertFalse("JS catch block must not execute on GokuScriptAbortError", loopResult.contains("SWALLOWED_QUOTA_ABORT"))

        // 2. KillSwitch triggered during goku.sleep inside JS try/catch must not be swallowed
        val stopper = thread(name = "js-try-catch-kill") {
            Thread.sleep(80L)
            GokuKillSwitch.triggerStop("UnitTestJsTryCatchKill")
        }
        val sleepCatchScript = """
            try {
                goku.sleep(3000);
            } catch (e) {
                goku.log("SWALLOWED_KILL_SWITCH");
            }
        """.trimIndent()
        val killResult = try {
            engine.runLivePlugin(sleepCatchScript, dryRun = false)
        } finally {
            stopper.join(1000L)
        }
        assertTrue(
            "Expected JS Plugin abort when KillSwitch fires mid-sleep, got: $killResult",
            killResult.contains("Stopped by GokuKillSwitch", ignoreCase = true) ||
                killResult.contains("aborted", ignoreCase = true)
        )
        assertFalse("JS catch block must not swallow KillSwitch abort", killResult.contains("SWALLOWED_KILL_SWITCH"))
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
