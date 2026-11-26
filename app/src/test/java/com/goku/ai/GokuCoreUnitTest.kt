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
import com.goku.ai.persona.GokuEmotionPersonaEngine
import com.goku.ai.persona.GokuFeeling
import com.goku.ai.persona.GokuPersonaConfig
import com.goku.ai.persona.PersonaPreferencesRepository
import com.goku.ai.persona.PersonaPresetId
import com.goku.ai.persona.SpeechProsodyMetrics
import com.goku.ai.persona.UserMood
import com.goku.ai.services.SmartCallScreenerService
import com.goku.ai.voice.ActiveVoicePackDescriptor
import com.goku.ai.voice.CustomVoiceAudioSink
import com.goku.ai.voice.CustomVoiceSynthesizerBackend
import com.goku.ai.voice.GokuCustomVoiceEngine
import com.goku.ai.voice.GokuEarAndVoiceEngine
import com.goku.ai.voice.GokuKillSwitch
import com.goku.ai.voice.ModelDownloadStatus
import com.goku.ai.voice.OnnxModelDownloader
import com.goku.ai.voice.SynthesizedAudioChunk
import com.goku.ai.voice.VoiceBundleCategory
import com.goku.ai.voice.VoiceModelCatalog
import com.goku.ai.voice.VoicePackManager
import com.goku.ai.voice.VoicePackMetadata
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
            nvidiaNimKey = "nvapi-free-345",
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
        assertEquals("nvapi-free-345", settings.nvidiaNimApiKey)
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

    @Test
    fun testHabitPredictorAndRecurringBillsReminder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = GokuVaultRepository(context)
        val now = System.currentTimeMillis()

        // Seed 2 recurring commands around current hour so Habit Predictor detects the routine
        vault.logAction("EXECUTOR", "Torch on", "Flashlight enabled", 100, "OK", 5L)
        vault.logAction("EXECUTOR", "Torch on", "Flashlight enabled", 100, "OK", 5L)
        val habits = vault.predictHabitsForCurrentHour(now)
        assertTrue(
            "Expected 'Torch on' habit prediction, got: $habits",
            habits.any { it.first.equals("Torch on", ignoreCase = true) && it.second >= 2 }
        )

        // Seed a recurring OTT bill and verify 24h monthly auto-debit window reminder
        vault.recordExpense(
            merchant = "Netflix India",
            amountInr = 499.0,
            category = "OTT",
            rawSource = "AutoPay mandate Netflix Rs.499",
            isRecurring = true
        )
        val dayMs = 24L * 60L * 60L * 1000L
        val billSummary = vault.buildRecurringBillsReminderHinglish(now + 29L * dayMs)
        assertTrue(
            "Expected Auto-Debit Alert (24h Window) in recurring bills summary, got: $billSummary",
            billSummary.contains("Auto-Debit Alert", ignoreCase = true)
        )
        assertTrue(
            "Expected Netflix India in recurring bills summary, got: $billSummary",
            billSummary.contains("Netflix India", ignoreCase = true)
        )
        assertTrue(
            "Expected amount 499 in recurring bills summary, got: $billSummary",
            billSummary.contains("499")
        )
    }

    @Test
    fun testEmotionPersonaEngineMoodProsodyTagsAndPersistence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = GokuEmotionPersonaEngine.getInstance(context)

        // 1. Offline mood classification across all 5 states (including prosody metrics)
        val urgentRes = engine.analyzeUserMood(
            "Jaldi turant Mom ko call karo urgent hai",
            SpeechProsodyMetrics(speakingWordsPerSec = 4.2f, peakEnergyDb = 6.0)
        )
        assertEquals(UserMood.URGENT, urgentRes.mood)

        val tiredRes = engine.analyzeUserMood(
            "Yaar aaj bahut thak gaya hoon, bahut sad aur stressed feel ho raha hai",
            SpeechProsodyMetrics(speakingWordsPerSec = 1.1f)
        )
        assertEquals(UserMood.TIRED_SAD, tiredRes.mood)
        val tiredProsody = engine.computeProsody(GokuFeeling.CARING, UserMood.TIRED_SAD, PersonaPreferencesRepository.presetConfig(PersonaPresetId.CLASSIC_ENERGETIC))
        assertTrue("Expected calm lower speech rate for TIRED_SAD, got: ${tiredProsody.speechRate}", tiredProsody.speechRate <= 1.0f)

        val angryRes = engine.analyzeUserMood(
            "Kya bakwas hai YEH KAAM NAHI KAR RAHA frustrated ho gaya",
            SpeechProsodyMetrics(peakEnergyDb = 10.5)
        )
        assertEquals(UserMood.ANGRY, angryRes.mood)

        val excitedRes = engine.analyzeUserMood("Awesome!! Aaj maza aa gaya mast jeet gaye!")
        assertEquals(UserMood.EXCITED, excitedRes.mood)

        val neutralRes = engine.analyzeUserMood("Kal ka weather batao")
        assertEquals(UserMood.NEUTRAL, neutralRes.mood)

        // 2. Emotion tag extraction & clean stripping before UI / speech
        val parsed = engine.parseAndStripEmotionTag("[CARING] Fikar mat karo Boss, main hoon na!")
        assertEquals(GokuFeeling.CARING, parsed.detectedFeeling)
        assertTrue(parsed.hadExplicitTag)
        assertFalse("Emotion tag must be stripped before display/TTS", parsed.cleanText.contains("[CARING]"))
        assertEquals("Fikar mat karo Boss, main hoon na!", parsed.cleanText)

        // 3. Zero-limit custom catchphrases & DataStore persistence round-trip
        val manyCatchphrases = (1..15).map { "Custom Saiyan Line #$it!" }
        val customConfig = GokuPersonaConfig(
            presetId = PersonaPresetId.CUSTOM,
            energyLevel = 0.92f,
            humorPlayfulness = 0.85f,
            caringEmpathy = 0.88f,
            seriousFocus = 0.40f,
            userNickname = "Captain",
            customCatchphrases = manyCatchphrases,
            customVoicePitch = 1.15f,
            customSpeechRate = 1.10f,
            customSystemRules = "Always speak in energetic Hinglish and motivate workouts."
        )
        assertTrue(engine.savePersonaConfig(customConfig))
        val reloaded = engine.repository.getCurrentConfig()
        assertEquals("Captain", reloaded.userNickname)
        assertEquals(15, reloaded.customCatchphrases.size)
        assertEquals(customConfig.customSystemRules, reloaded.customSystemRules)

        // 4. Prompt directive includes nickname, mood, custom catchphrases, and rules
        val directive = engine.buildPersonaPromptDirective(excitedRes, reloaded)
        assertTrue(directive.contains("Captain"))
        assertTrue(directive.contains("EXCITED"))
        assertTrue(directive.contains("Custom Saiyan Line #15!"))
        assertTrue(directive.contains("motivate workouts"))
    }

    @Test
    fun testOnnxModelDownloaderRangeResumeAtomicWriteAndDelete() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Catalog pre-configured Hugging Face resolve/main/ bundles & URL normalization
        assertTrue(VoiceModelCatalog.PRECONFIGURED_BUNDLES.size >= 4)
        assertTrue(
            VoiceModelCatalog.PRECONFIGURED_BUNDLES.all { bundle ->
                bundle.files.all { it.downloadUrl.contains("/resolve/main/") }
            }
        )
        assertEquals(
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/hi/model.onnx",
            VoiceModelCatalog.normalizeHuggingFaceUrl("https://huggingface.co/rhasspy/piper-voices/blob/main/hi/model.onnx")
        )
        assertTrue(VoiceModelCatalog.createCustomUrlBundle("ftp://bad.host/evil.onnx").isFailure)
        assertEquals("safe_model.onnx", VoiceModelCatalog.sanitizeFileName("../../etc/safe_model.onnx"))

        // 2. Spin up deterministic local HTTP Range server (supports 206 Partial Content, 200 OK, and 416)
        val onnxPayload = ByteArray(4096) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        val tokensPayload = "0 <blk>\n1 ▁HEY\n2 ▁GOKU\n3 ▁STOP\n".toByteArray(Charsets.UTF_8)
        val receivedRangeHeaders = java.util.Collections.synchronizedList(mutableListOf<String>())
        val forceHttp200Once = java.util.concurrent.atomic.AtomicBoolean(false)

        val serverSocket = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val port = serverSocket.localPort
        val serverRunning = java.util.concurrent.atomic.AtomicBoolean(true)
        val serverThread = thread(isDaemon = true) {
            while (serverRunning.get()) {
                val client = try { serverSocket.accept() } catch (_: Throwable) { break }
                try {
                    client.soTimeout = 3000
                    val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val requestLine = reader.readLine() ?: continue
                    val path = requestLine.split(" ").getOrNull(1).orEmpty()
                    var rangeHeader: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) {
                            rangeHeader = line.substringAfter(':').trim()
                            receivedRangeHeaders.add(rangeHeader)
                        }
                    }
                    val payload = if (path.endsWith("tokens.txt")) tokensPayload else onnxPayload
                    val out = client.getOutputStream()
                    val startByte = rangeHeader
                        ?.removePrefix("bytes=")
                        ?.substringBefore('-')
                        ?.toIntOrNull()

                    when {
                        startByte != null && startByte >= payload.size -> {
                            val hdr = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                                "Content-Range: bytes */${payload.size}\r\n" +
                                "Content-Length: 0\r\n" +
                                "Connection: close\r\n\r\n"
                            out.write(hdr.toByteArray(Charsets.US_ASCII))
                            out.flush()
                        }
                        startByte != null && startByte > 0 && !forceHttp200Once.getAndSet(false) -> {
                            val slice = payload.copyOfRange(startByte, payload.size)
                            val hdr = "HTTP/1.1 206 Partial Content\r\n" +
                                "Content-Range: bytes $startByte-${payload.size - 1}/${payload.size}\r\n" +
                                "Content-Length: ${slice.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            out.write(hdr.toByteArray(Charsets.US_ASCII))
                            out.write(slice)
                            out.flush()
                        }
                        else -> {
                            val hdr = "HTTP/1.1 200 OK\r\n" +
                                "Content-Length: ${payload.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            out.write(hdr.toByteArray(Charsets.US_ASCII))
                            out.write(payload)
                            out.flush()
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    runCatching { client.close() }
                }
            }
        }

        try {
            val downloader = OnnxModelDownloader(context)
            val bundleRes = downloader.addCustomUrlBundle(
                primaryUrl = "http://127.0.0.1:$port/goku_voice.onnx",
                displayName = "Test Goku ONNX Pack",
                companionUrl = "http://127.0.0.1:$port/tokens.txt",
                autoStartDownload = false
            )
            assertTrue("Expected valid local test bundle", bundleRes.isSuccess)
            val bundle = bundleRes.getOrThrow()

            // Pre-seed first 1500 bytes as a paused `.part` file to verify HTTP 206 Range Resume
            val modelDir = downloader.getModelDirectory(bundle.id).apply { mkdirs() }
            assertTrue(modelDir.absolutePath.contains("voice_models"))
            val partFile = java.io.File(modelDir, "goku_voice.onnx.part")
            partFile.writeBytes(onnxPayload.copyOfRange(0, 1500))

            val ok206 = downloader.downloadBundleBlocking(bundle.id)
            assertTrue("Expected HTTP 206 resume + multi-file tokens.txt download to succeed", ok206)
            assertTrue("Expected Range: bytes=1500- header", receivedRangeHeaders.any { it == "bytes=1500-" })
            assertEquals(ModelDownloadStatus.READY, downloader.progressMap.value[bundle.id]?.status)

            val finalOnnx = java.io.File(modelDir, "goku_voice.onnx")
            val finalTokens = java.io.File(modelDir, "tokens.txt")
            assertTrue(finalOnnx.exists() && !partFile.exists())
            assertTrue(finalTokens.exists())
            assertTrue("Reconstructed ONNX bytes must match byte-for-byte after 206 resume", finalOnnx.readBytes().contentEquals(onnxPayload))
            assertTrue("Companion tokens.txt bytes must match", finalTokens.readBytes().contentEquals(tokensPayload))

            // 3. Activate model -> wired to VoicePackManager + GokuEarAndVoiceEngine
            assertTrue(downloader.activateModel(bundle.id))
            assertEquals(bundle.id, downloader.activeModelId.value)
            assertEquals(bundle.displayName, VoicePackManager.getInstance(context).activeVoicePack.value?.displayName)
            assertEquals(bundle.displayName, GokuEarAndVoiceEngine.getInstance(context).state.value.activeVoicePackName)

            // 4. Test HTTP 416 recovery (stale oversized .part file -> clean restart from byte 0)
            downloader.deleteInstalledModel(bundle.id)
            assertEquals(ModelDownloadStatus.NOT_DOWNLOADED, downloader.progressMap.value[bundle.id]?.status)
            assertNull(downloader.activeModelId.value)
            modelDir.mkdirs()
            partFile.writeBytes(ByteArray(onnxPayload.size + 512) { 0x55 })
            val ok416 = downloader.downloadBundleBlocking(bundle.id)
            assertTrue("Expected HTTP 416 oversized .part recovery to succeed", ok416)
            assertTrue(finalOnnx.readBytes().contentEquals(onnxPayload))

            // 5. Test HTTP 200 OK restart when server ignores Range header
            downloader.deleteInstalledModel(bundle.id)
            modelDir.mkdirs()
            partFile.writeBytes(ByteArray(500) { 0x11 })
            forceHttp200Once.set(true)
            val ok200 = downloader.downloadBundleBlocking(bundle.id)
            assertTrue("Expected HTTP 200 fallback restart to overwrite partial file cleanly", ok200)
            assertTrue(finalOnnx.readBytes().contentEquals(onnxPayload))

            // 6. Final 1-tap delete cleans storage completely
            assertTrue(downloader.deleteInstalledModel(bundle.id))
            assertFalse(modelDir.exists())
        } finally {
            serverRunning.set(false)
            runCatching { serverSocket.close() }
            serverThread.join(1000L)
        }
    }

    @Test
    fun testVoicePackManagerAndCustomVoiceEngineStreamingAndInstantStop() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packManager = VoicePackManager.getInstance(context)

        fun buildTestPcmWavBytes(sampleRateHz: Int, channels: Int, bitsPerSample: Int, dataBytes: Int): ByteArray {
            val totalSize = 44 + dataBytes
            val bb = java.nio.ByteBuffer.allocate(totalSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray(Charsets.US_ASCII))
            bb.putInt(totalSize - 8)
            bb.put("WAVE".toByteArray(Charsets.US_ASCII))
            bb.put("fmt ".toByteArray(Charsets.US_ASCII))
            bb.putInt(16)
            bb.putShort(1.toShort()) // PCM
            bb.putShort(channels.toShort())
            bb.putInt(sampleRateHz)
            bb.putInt(sampleRateHz * channels * (bitsPerSample / 8))
            bb.putShort((channels * (bitsPerSample / 8)).toShort())
            bb.putShort(bitsPerSample.toShort())
            bb.put("data".toByteArray(Charsets.US_ASCII))
            bb.putInt(dataBytes)
            for (i in 0 until dataBytes) bb.put((i and 0x7F).toByte())
            return bb.array()
        }

        // 1. Validate 16kHz & 24kHz mono WAV accepted; stereo / 48kHz / corrupt rejected cleanly
        val valid16k = java.io.File(packManager.voicePackDir, "test_valid_16k.wav").apply {
            writeBytes(buildTestPcmWavBytes(16000, 1, 16, 3200))
        }
        val valid24k = java.io.File(packManager.voicePackDir, "test_valid_24k.wav").apply {
            writeBytes(buildTestPcmWavBytes(24000, 1, 16, 4800))
        }
        val invalidStereo = java.io.File(packManager.voicePackDir, "test_stereo.wav").apply {
            writeBytes(buildTestPcmWavBytes(16000, 2, 16, 3200))
        }
        val invalid48k = java.io.File(packManager.voicePackDir, "test_48k.wav").apply {
            writeBytes(buildTestPcmWavBytes(48000, 1, 16, 4800))
        }
        assertTrue(VoicePackManager.validateReferenceWavFile(valid16k).isSuccess)
        assertTrue(VoicePackManager.validateReferenceWavFile(valid24k).isSuccess)
        assertTrue(VoicePackManager.validateReferenceWavFile(invalidStereo).isFailure)
        assertTrue(VoicePackManager.validateReferenceWavFile(invalid48k).isFailure)

        // 2. Verify sentence & phrase chunk splitting for Hinglish / Devanagari / long clauses
        val chunks = GokuCustomVoiceEngine.splitIntoSpeechChunks(
            "Yo Boss! Wi-Fi on kar diya hai। Agla command batao, main full power mein ready hoon!"
        )
        assertEquals(3, chunks.size)

        // 3. Test concurrent Channel producer-consumer streaming with pluggable backend + AudioSink
        val streamedChunks = java.util.Collections.synchronizedList(mutableListOf<String>())
        val stopFlushCount = java.util.concurrent.atomic.AtomicInteger(0)
        val fallbackSpoken = java.util.Collections.synchronizedList(mutableListOf<String>())
        val fallbackStopped = java.util.concurrent.atomic.AtomicInteger(0)
        val slowChunkDelayMs = java.util.concurrent.atomic.AtomicLong(0L)
        val backendReadyFlag = java.util.concurrent.atomic.AtomicBoolean(true)

        val testBackend = object : CustomVoiceSynthesizerBackend {
            override val backendName: String = "Test-ONNX-VITS"
            override fun isReady(): Boolean = backendReadyFlag.get()
            override fun initialize(descriptor: ActiveVoicePackDescriptor, metadata: VoicePackMetadata?): Boolean = backendReadyFlag.get()
            override fun synthesizeChunk(text: String, speechRate: Float, pitch: Float): SynthesizedAudioChunk? {
                val delayMs = slowChunkDelayMs.get()
                if (delayMs > 0L) Thread.sleep(delayMs)
                return SynthesizedAudioChunk(
                    chunkIndex = 0,
                    sourceText = text,
                    pcm16 = ShortArray(1600) { 1200 },
                    sampleRateHz = 16000
                )
            }
            override fun release() {}
        }

        val testSink = object : CustomVoiceAudioSink {
            override fun writeChunk(chunk: SynthesizedAudioChunk, volumeGain: Float, isCancelled: () -> Boolean): Boolean {
                if (isCancelled()) return false
                streamedChunks.add(chunk.sourceText)
                val stepDelay = slowChunkDelayMs.get()
                if (stepDelay > 0L) {
                    val end = System.currentTimeMillis() + stepDelay
                    while (System.currentTimeMillis() < end) {
                        if (isCancelled()) return false
                        Thread.sleep(5L)
                    }
                }
                return !isCancelled()
            }
            override fun stopAndFlushImmediately() {
                stopFlushCount.incrementAndGet()
            }
        }

        val customEngine = GokuCustomVoiceEngine(context, testBackend, testSink)
        customEngine.attachFallbackTtsBridge(
            speaker = { text, _, _, _ -> fallbackSpoken.add(text) },
            stopper = { fallbackStopped.incrementAndGet() },
            onPlaybackChanged = { _, _ -> }
        )

        val descriptor = ActiveVoicePackDescriptor(
            modelId = VoicePackManager.LOCAL_VOICE_PACK_ID,
            displayName = "Test Custom Voice",
            category = VoiceBundleCategory.CUSTOM_VOICE_SAMPLE,
            installDirPath = packManager.voicePackDir.absolutePath,
            onnxFilePaths = emptyList(),
            tokenizerOrConfigPaths = emptyList(),
            wavSamplePaths = listOf(valid16k.absolutePath)
        )
        customEngine.onVoicePackUpdated(descriptor, VoicePackMetadata(
            packId = descriptor.modelId,
            displayName = descriptor.displayName,
            category = descriptor.category,
            installDirPath = descriptor.installDirPath,
            referenceWavPath = valid16k.absolutePath,
            referenceSampleRateHz = 16000
        ))

        GokuKillSwitch.reset()
        customEngine.speak("Pehla sentence ready hai! Doosra sentence stream hoga. Teesra bhi complete!")
        customEngine.awaitCurrentPlayback()

        assertEquals(3, streamedChunks.size)
        assertEquals(GokuCustomVoiceEngine.VoiceEngineState.IDLE, customEngine.engineState.value)

        // 4. Test <0.1s stopImmediately() cancellation during active multi-chunk streaming
        streamedChunks.clear()
        slowChunkDelayMs.set(400L)
        customEngine.speak("First slow chunk! Second slow chunk! Third slow chunk! Fourth slow chunk!")
        delay(60L)
        val stopStartMs = System.currentTimeMillis()
        customEngine.stopImmediately()
        val stopElapsedMs = System.currentTimeMillis() - stopStartMs
        assertTrue("Expected stopImmediately() < 100ms, took ${stopElapsedMs}ms", stopElapsedMs < 100L)
        assertTrue("Expected AudioTrack stopAndFlushImmediately() invoked", stopFlushCount.get() > 0)
        assertTrue("Expected fallback TTS stopper invoked", fallbackStopped.get() > 0)
        assertEquals(GokuCustomVoiceEngine.VoiceEngineState.IDLE, customEngine.engineState.value)

        // 5. Test graceful FALLBACK_TTS when native ONNX runtime is unavailable (zero unhandled exception)
        slowChunkDelayMs.set(0L)
        backendReadyFlag.set(false)
        customEngine.speak("Fallback Hinglish TTS check!")
        customEngine.awaitCurrentPlayback()
        assertEquals(GokuCustomVoiceEngine.VoiceEngineState.FALLBACK_TTS, customEngine.engineState.value)
        assertTrue(fallbackSpoken.contains("Fallback Hinglish TTS check!"))

        customEngine.release()
        packManager.deleteLocalVoicePackFiles()
        Unit
    }

    @Test
    fun testTtsPendingUtteranceQueuesSafelyBeforeInitAndClearsOnKillSwitch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val voice = GokuEarAndVoiceEngine(context)
        try {
            GokuKillSwitch.reset()
            // Under Robolectric, TextToSpeech binder onInit does not complete immediately;
            // speak() must queue safely without throwing or silently losing the utterance.
            voice.speak("Namaste Boss, Goku online hai!")
            assertTrue(
                "Expected pending TTS utterance to be queued while TTS init is pending",
                voice.hasPendingTtsUtteranceForTest()
            )

            // Instant kill-switch must clear queued pending speech immediately without exception
            GokuKillSwitch.triggerStop("UnitTestTtsPendingClear")
            assertFalse(
                "Expected pending TTS utterance to be cleared immediately on GokuKillSwitch",
                voice.hasPendingTtsUtteranceForTest()
            )

            // Non-urgent speech while stopped must be ignored and NOT re-queued
            voice.speak("Should not speak while stopped", urgent = false)
            assertFalse(
                "Non-urgent speech must not queue while GokuKillSwitch is active",
                voice.hasPendingTtsUtteranceForTest()
            )

            // Urgent stop confirmation speech is allowed even while stopped
            voice.speak("Ruk gaya, sab band kar diya.", urgent = true)
            assertTrue(
                "Urgent confirmation speech should queue/attempt TTS even while stopped",
                voice.hasPendingTtsUtteranceForTest()
            )

            // Explicit stopSpeaking() clears pending utterance cleanly
            voice.stopSpeaking()
            assertFalse(
                "stopSpeaking() must clear pending TTS utterance",
                voice.hasPendingTtsUtteranceForTest()
            )
        } finally {
            voice.release()
        }
    }
}
