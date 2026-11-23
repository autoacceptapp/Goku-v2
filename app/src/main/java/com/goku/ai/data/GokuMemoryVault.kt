package com.goku.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// ============================================================================
// 1. ROOM ENTITIES (ZERO-LIMIT MEMORY VAULT + VISUAL TIMELINE + SKILLS + LOGS)
// ============================================================================

@Entity(tableName = "memory_nodes")
data class MemoryNodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val category: String = "FACT", // FACT, CONTACT, PREFERENCE, ENTITY, RELATION
    val relationTag: String = "",
    val confidence: Float = 1.0f,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "screen_timeline")
data class ScreenTimelineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val screenTitle: String,
    val keywords: String,
    val compressedText: ByteArray,
    val summary: String = "",
    val isSummarized: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenTimelineEntity) return false
        return id == other.id &&
            packageName == other.packageName &&
            screenTitle == other.screenTitle &&
            keywords == other.keywords &&
            compressedText.contentEquals(other.compressedText) &&
            summary == other.summary &&
            isSummarized == other.isSummarized &&
            timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + packageName.hashCode()
        result = 31 * result + screenTitle.hashCode()
        result = 31 * result + keywords.hashCode()
        result = 31 * result + compressedText.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

@Entity(tableName = "learned_skills")
data class LearnedSkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val triggerPhrase: String,
    val stepsJson: String,
    val preferredEngine: String = "INTENT_ADB", // INTENT_ADB or ACCESSIBILITY
    val adbAvgMs: Long = 120L,
    val adbSuccessRate: Float = 0.95f,
    val a11yAvgMs: Long = 450L,
    val a11ySuccessRate: Float = 0.90f,
    val usageCount: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "expense_records")
data class ExpenseRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchant: String,
    val amountInr: Double,
    val category: String, // PETROL, BILL, GROCERY, OTT, EMI, OTHER
    val rawSource: String,
    val isRecurring: Boolean = false,
    val nextDueAt: Long? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "action_logs")
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agentStage: String, // PLANNER, EXECUTOR, CRITIC, REFLEX, SYSTEM
    val command: String,
    val detail: String,
    val confidence: Int = 100,
    val status: String = "OK", // OK, WARN, ERROR, STOPPED, DRY_RUN
    val latencyMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

// ============================================================================
// 2. COMPRESSION UTILITY (TEXT-ONLY ZERO-BLOAT VISUAL TIMELINE)
// ============================================================================

object TextCompressor {
    fun compress(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gzip ->
            gzip.write(input.toByteArray(Charsets.UTF_8))
        }
        return bos.toByteArray()
    }

    fun decompress(data: ByteArray): String {
        if (data.isEmpty()) return ""
        return runCatching {
            GZIPInputStream(ByteArrayInputStream(data)).bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
        }.getOrDefault("")
    }
}

// ============================================================================
// 3. ROOM DAOs
// ============================================================================

@Dao
interface MemoryNodeDao {
    @Query("SELECT * FROM memory_nodes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MemoryNodeEntity>>

    @Query("SELECT * FROM memory_nodes WHERE `key` LIKE '%' || :q || '%' OR value LIKE '%' || :q || '%' OR relationTag LIKE '%' || :q || '%' ORDER BY updatedAt DESC LIMIT 20")
    suspend fun search(q: String): List<MemoryNodeEntity>

    @Query("SELECT * FROM memory_nodes WHERE LOWER(`key`) = LOWER(:key) LIMIT 1")
    suspend fun findByKey(key: String): MemoryNodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: MemoryNodeEntity): Long

    @Query("DELETE FROM memory_nodes WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ScreenTimelineDao {
    @Query("SELECT * FROM screen_timeline ORDER BY timestamp DESC LIMIT 100")
    fun observeRecent(): Flow<List<ScreenTimelineEntity>>

    @Query("SELECT * FROM screen_timeline WHERE keywords LIKE '%' || :q || '%' OR screenTitle LIKE '%' || :q || '%' OR packageName LIKE '%' || :q || '%' OR summary LIKE '%' || :q || '%' ORDER BY timestamp DESC LIMIT 30")
    suspend fun search(q: String): List<ScreenTimelineEntity>

    @Query("SELECT * FROM screen_timeline WHERE isSummarized = 0 AND timestamp < :cutoffMillis LIMIT 200")
    suspend fun findUnsummarizedOlderThan(cutoffMillis: Long): List<ScreenTimelineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ScreenTimelineEntity): Long

    @Update
    suspend fun update(entry: ScreenTimelineEntity)

    @Query("DELETE FROM screen_timeline WHERE timestamp < :cutoffMillis AND isSummarized = 1")
    suspend fun purgeOldSummarized(cutoffMillis: Long): Int
}

@Dao
interface LearnedSkillDao {
    @Query("SELECT * FROM learned_skills ORDER BY usageCount DESC, updatedAt DESC")
    fun observeAll(): Flow<List<LearnedSkillEntity>>

    @Query("SELECT * FROM learned_skills")
    suspend fun getAllSnapshot(): List<LearnedSkillEntity>

    @Query("SELECT * FROM learned_skills WHERE LOWER(triggerPhrase) = LOWER(:phrase) LIMIT 1")
    suspend fun findByTrigger(phrase: String): LearnedSkillEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: LearnedSkillEntity): Long

    @Query("DELETE FROM learned_skills WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ExpenseRecordDao {
    @Query("SELECT * FROM expense_records ORDER BY timestamp DESC LIMIT 200")
    fun observeAll(): Flow<List<ExpenseRecordEntity>>

    @Query("SELECT * FROM expense_records WHERE timestamp >= :sinceMillis ORDER BY timestamp DESC")
    suspend fun getSince(sinceMillis: Long): List<ExpenseRecordEntity>

    @Query("SELECT SUM(amountInr) FROM expense_records WHERE timestamp >= :sinceMillis")
    suspend fun getTotalSince(sinceMillis: Long): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ExpenseRecordEntity): Long
}

@Dao
interface ActionLogDao {
    @Query("SELECT * FROM action_logs ORDER BY timestamp DESC LIMIT 150")
    fun observeRecent(): Flow<List<ActionLogEntity>>

    @Query("SELECT * FROM action_logs WHERE status = 'ERROR' OR status = 'WARN' ORDER BY timestamp DESC LIMIT 50")
    suspend fun getRecentFailures(): List<ActionLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ActionLogEntity): Long

    @Query("DELETE FROM action_logs WHERE timestamp < :cutoffMillis")
    suspend fun pruneOlderThan(cutoffMillis: Long)
}

// ============================================================================
// 4. ROOM DATABASE SINGLETON
// ============================================================================

@Database(
    entities = [
        MemoryNodeEntity::class,
        ScreenTimelineEntity::class,
        LearnedSkillEntity::class,
        ExpenseRecordEntity::class,
        ActionLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class GokuDatabase : RoomDatabase() {
    abstract fun memoryNodeDao(): MemoryNodeDao
    abstract fun screenTimelineDao(): ScreenTimelineDao
    abstract fun learnedSkillDao(): LearnedSkillDao
    abstract fun expenseRecordDao(): ExpenseRecordDao
    abstract fun actionLogDao(): ActionLogDao

    companion object {
        @Volatile
        private var INSTANCE: GokuDatabase? = null

        fun getInstance(context: Context): GokuDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GokuDatabase::class.java,
                    "goku_v10_vault.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

// ============================================================================
// 5. SHORT-TERM WORKING MEMORY (15-TURN CONTEXT & PRONOUN/POINTER RESOLVER)
// ============================================================================

data class ConversationTurn(
    val role: String, // "USER" or "GOKU"
    val text: String,
    val referencedEntity: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

class ShortTermWorkingMemory(private val maxTurns: Int = 15) {
    private val turns = CopyOnWriteArrayList<ConversationTurn>()

    @Volatile
    var lastMentionedContact: String? = null
        private set

    @Volatile
    var lastMentionedApp: String? = null
        private set

    @Volatile
    var lastScreenSummary: String = ""

    fun addTurn(role: String, text: String, entity: String? = null) {
        turns.add(ConversationTurn(role = role, text = text, referencedEntity = entity))
        while (turns.size > maxTurns) {
            turns.removeAt(0)
        }
        if (!entity.isNullOrBlank()) {
            if (entity.startsWith("contact:")) {
                lastMentionedContact = entity.removePrefix("contact:")
            } else if (entity.startsWith("app:")) {
                lastMentionedApp = entity.removePrefix("app:")
            }
        }
    }

    fun getRecentTurns(limit: Int = 8): List<ConversationTurn> = turns.takeLast(limit)

    fun buildCompressedContext(): String {
        val recent = turns.takeLast(6).joinToString("\n") { "${it.role}: ${it.text.take(140)}" }
        val screenPart = if (lastScreenSummary.isNotBlank()) {
            "\n[SCREEN]: ${lastScreenSummary.take(220)}"
        } else ""
        return (recent + screenPart).trim()
    }

    /**
     * Resolves Hinglish & English pronouns ("usse", "unhe", "use", "him", "her", "that app")
     * against the active 15-turn working memory stack.
     */
    fun resolvePronouns(rawInput: String): String {
        var resolved = rawInput
        val lower = rawInput.lowercase(Locale.ROOT)
        val contact = lastMentionedContact
        if (!contact.isNullOrBlank()) {
            val contactPronouns = listOf("usse", "unhe", "unse", "usko", "unko", "call him", "call her", "message him", "message her")
            if (contactPronouns.any { lower.contains(it) }) {
                resolved = resolved
                    .replace(Regex("\\b(usse|unhe|unse|usko|unko)\\b", RegexOption.IGNORE_CASE), contact)
                    .replace(Regex("\\b(him|her)\\b", RegexOption.IGNORE_CASE), contact)
            }
        }
        val app = lastMentionedApp
        if (!app.isNullOrBlank() && (lower.contains("wahi app") || lower.contains("same app") || lower.contains("that app"))) {
            resolved = resolved
                .replace(Regex("wahi app|same app|that app", RegexOption.IGNORE_CASE), app)
        }
        return resolved
    }

    fun clear() {
        turns.clear()
        lastMentionedContact = null
        lastMentionedApp = null
    }
}

// ============================================================================
// 6. LOCAL RAG DOCUMENT BRAIN (OFFLINE FILE/TEXT CHUNKER & INDEXER)
// ============================================================================

data class RagChunk(
    val docName: String,
    val chunkIndex: Int,
    val text: String,
    val tokens: Set<String>
)

class LocalRagDocumentBrain {
    private val chunks = CopyOnWriteArrayList<RagChunk>()

    fun indexDocument(docName: String, content: String, chunkSize: Int = 360) {
        chunks.removeAll { it.docName == docName }
        val normalized = content.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return
        var start = 0
        var idx = 0
        while (start < normalized.length) {
            val end = (start + chunkSize).coerceAtMost(normalized.length)
            val slice = normalized.substring(start, end)
            val tokens = tokenize(slice)
            chunks.add(RagChunk(docName, idx++, slice, tokens))
            start = if (end == normalized.length) end else (end - 60).coerceAtLeast(start + 1)
        }
    }

    fun query(question: String, topK: Int = 3): List<RagChunk> {
        val qTokens = tokenize(question)
        if (qTokens.isEmpty() || chunks.isEmpty()) return emptyList()
        return chunks
            .map { chunk ->
                val overlap = qTokens.count { it in chunk.tokens }
                val score = overlap.toFloat() / (qTokens.size + 1f)
                chunk to score
            }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    fun indexedCount(): Int = chunks.size

    private fun tokenize(text: String): Set<String> {
        return text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9\\u0900-\\u097F]+"))
            .filter { it.length >= 2 }
            .toSet()
    }
}

// ============================================================================
// 7. ENCRYPTED SETTINGS & VAULT REPOSITORY
// ============================================================================

class GokuEncryptedSettings(context: Context) {
    private val prefs: SharedPreferences = createSecurePrefs(context.applicationContext)

    private fun createSecurePrefs(context: Context): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "goku_v10_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            // Safe fallback if hardware keystore is unavailable on OEM/emulator
            context.getSharedPreferences("goku_v10_fallback_prefs", Context.MODE_PRIVATE)
        }
    }

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) = prefs.edit().putString("gemini_api_key", value.trim()).apply()

    var groqApiKey: String
        get() = prefs.getString("groq_api_key", "") ?: ""
        set(value) = prefs.edit().putString("groq_api_key", value.trim()).apply()

    var deepSeekApiKey: String
        get() = prefs.getString("deepseek_api_key", "") ?: ""
        set(value) = prefs.edit().putString("deepseek_api_key", value.trim()).apply()

    var openRouterApiKey: String
        get() = prefs.getString("openrouter_api_key", "") ?: ""
        set(value) = prefs.edit().putString("openrouter_api_key", value.trim()).apply()

    var sosContactsCsv: String
        get() = prefs.getString("sos_contacts", "") ?: ""
        set(value) = prefs.edit().putString("sos_contacts", value.trim()).apply()

    var githubToken: String
        get() = prefs.getString("github_token", "") ?: ""
        set(value) = prefs.edit().putString("github_token", value.trim()).apply()

    var githubRepo: String
        get() = prefs.getString("github_repo", "") ?: ""
        set(value) = prefs.edit().putString("github_repo", value.trim()).apply()

    var telegramBotToken: String
        get() = prefs.getString("telegram_bot_token", "") ?: ""
        set(value) = prefs.edit().putString("telegram_bot_token", value.trim()).apply()

    var telegramChatId: String
        get() = prefs.getString("telegram_chat_id", "") ?: ""
        set(value) = prefs.edit().putString("telegram_chat_id", value.trim()).apply()

    var smartCallPolicy: String
        get() = prefs.getString(
            "smart_call_policy",
            "Delivery wala ho toh gate par rakhne bolna, bank ya loan wala ho toh politely mana kar dena."
        ) ?: ""
        set(value) = prefs.edit().putString("smart_call_policy", value.trim()).apply()

    var smartCallEnabled: Boolean
        get() = prefs.getBoolean("smart_call_enabled", false)
        set(value) = prefs.edit().putBoolean("smart_call_enabled", value).apply()

    var dryRunMode: Boolean
        get() = prefs.getBoolean("dry_run_mode", false)
        set(value) = prefs.edit().putBoolean("dry_run_mode", value).apply()

    var autoAdSkipEnabled: Boolean
        get() = prefs.getBoolean("auto_ad_skip", true)
        set(value) = prefs.edit().putBoolean("auto_ad_skip", value).apply()

    var otpAutopilotEnabled: Boolean
        get() = prefs.getBoolean("otp_autopilot", true)
        set(value) = prefs.edit().putBoolean("otp_autopilot", value).apply()

    var mistakeBlockerEnabled: Boolean
        get() = prefs.getBoolean("mistake_blocker", true)
        set(value) = prefs.edit().putBoolean("mistake_blocker", value).apply()

    var workFocusMode: Boolean
        get() = prefs.getBoolean("work_focus_mode", false)
        set(value) = prefs.edit().putBoolean("work_focus_mode", value).apply()

    var localModelPath: String
        get() = prefs.getString("local_model_path", "/sdcard/Goku/models/goku-4bit.gguf") ?: ""
        set(value) = prefs.edit().putString("local_model_path", value.trim()).apply()

    fun getSosContactsList(): List<String> {
        return sosContactsCsv.split(",", ";", "\n")
            .map { it.trim() }
            .filter { it.length >= 6 }
    }
}

class GokuVaultRepository(context: Context) {
    private val db = GokuDatabase.getInstance(context)
    val settings = GokuEncryptedSettings(context)
    val workingMemory = ShortTermWorkingMemory()
    val ragBrain = LocalRagDocumentBrain()

    val memories: Flow<List<MemoryNodeEntity>> = db.memoryNodeDao().observeAll()
    val screenTimeline: Flow<List<ScreenTimelineEntity>> = db.screenTimelineDao().observeRecent()
    val skills: Flow<List<LearnedSkillEntity>> = db.learnedSkillDao().observeAll()
    val expenses: Flow<List<ExpenseRecordEntity>> = db.expenseRecordDao().observeAll()
    val actionLogs: Flow<List<ActionLogEntity>> = db.actionLogDao().observeRecent()

    suspend fun saveMemory(
        key: String,
        value: String,
        category: String = "FACT",
        relationTag: String = ""
    ) = withContext(Dispatchers.IO) {
        val existing = db.memoryNodeDao().findByKey(key)
        val node = if (existing != null) {
            existing.copy(value = value, category = category, relationTag = relationTag, updatedAt = System.currentTimeMillis())
        } else {
            MemoryNodeEntity(key = key, value = value, category = category, relationTag = relationTag)
        }
        db.memoryNodeDao().upsert(node)
        ragBrain.indexDocument("memory:$key", "$key: $value ($relationTag)")
    }

    suspend fun searchMemories(query: String): List<MemoryNodeEntity> = withContext(Dispatchers.IO) {
        db.memoryNodeDao().search(query)
    }

    suspend fun deleteMemory(id: Long) = withContext(Dispatchers.IO) {
        db.memoryNodeDao().deleteById(id)
    }

    suspend fun recordScreenSnapshot(packageName: String, title: String, rawText: String) = withContext(Dispatchers.IO) {
        val clean = rawText.replace(Regex("\\s+"), " ").trim().take(4000)
        if (clean.length < 8) return@withContext
        val keywords = clean.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9\\u0900-\\u097F]+"))
            .filter { it.length >= 3 }
            .distinct()
            .take(40)
            .joinToString(" ")
        workingMemory.lastScreenSummary = "$title ($packageName): ${clean.take(240)}"
        db.screenTimelineDao().insert(
            ScreenTimelineEntity(
                packageName = packageName,
                screenTitle = title.take(120),
                keywords = keywords,
                compressedText = TextCompressor.compress(clean),
                summary = clean.take(160)
            )
        )
    }

    suspend fun searchVisualTimeline(query: String): List<Pair<ScreenTimelineEntity, String>> = withContext(Dispatchers.IO) {
        db.screenTimelineDao().search(query).map { entity ->
            entity to TextCompressor.decompress(entity.compressedText)
        }
    }

    suspend fun compactOldTimelineEntries(): Int = withContext(Dispatchers.IO) {
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
        val old = db.screenTimelineDao().findUnsummarizedOlderThan(sevenDaysAgo)
        old.forEach { entry ->
            val text = TextCompressor.decompress(entry.compressedText)
            val compactSummary = text.take(180)
            db.screenTimelineDao().update(
                entry.copy(
                    compressedText = TextCompressor.compress(compactSummary),
                    summary = compactSummary,
                    isSummarized = true
                )
            )
        }
        val purged = db.screenTimelineDao().purgeOldSummarized(thirtyDaysAgo)
        old.size + purged
    }

    suspend fun upsertSkill(skill: LearnedSkillEntity): Long = withContext(Dispatchers.IO) {
        db.learnedSkillDao().upsert(skill)
    }

    suspend fun findMatchingSkill(input: String): LearnedSkillEntity? = withContext(Dispatchers.IO) {
        val lower = input.lowercase(Locale.ROOT).trim()
        val exact = db.learnedSkillDao().findByTrigger(lower)
        if (exact != null) return@withContext exact
        db.learnedSkillDao().getAllSnapshot().firstOrNull {
            lower.contains(it.triggerPhrase.lowercase(Locale.ROOT))
        }
    }

    suspend fun recordSkillBenchmark(
        skill: LearnedSkillEntity,
        usedAdb: Boolean,
        latencyMs: Long,
        succeeded: Boolean
    ) = withContext(Dispatchers.IO) {
        val updated = if (usedAdb) {
            val newAvg = ((skill.adbAvgMs * 3) + latencyMs) / 4
            val newRate = (skill.adbSuccessRate * 0.8f) + (if (succeeded) 0.2f else 0f)
            skill.copy(
                adbAvgMs = newAvg,
                adbSuccessRate = newRate,
                preferredEngine = if (newRate >= skill.a11ySuccessRate && newAvg <= skill.a11yAvgMs) "INTENT_ADB" else "ACCESSIBILITY",
                usageCount = skill.usageCount + 1,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            val newAvg = ((skill.a11yAvgMs * 3) + latencyMs) / 4
            val newRate = (skill.a11ySuccessRate * 0.8f) + (if (succeeded) 0.2f else 0f)
            skill.copy(
                a11yAvgMs = newAvg,
                a11ySuccessRate = newRate,
                preferredEngine = if (skill.adbSuccessRate >= newRate && skill.adbAvgMs <= newAvg) "INTENT_ADB" else "ACCESSIBILITY",
                usageCount = skill.usageCount + 1,
                updatedAt = System.currentTimeMillis()
            )
        }
        db.learnedSkillDao().upsert(updated)
    }

    suspend fun deleteSkill(id: Long) = withContext(Dispatchers.IO) {
        db.learnedSkillDao().deleteById(id)
    }

    suspend fun exportAllSkillsToGokuJson(): String = withContext(Dispatchers.IO) {
        val all = db.learnedSkillDao().getAllSnapshot()
        val root = JSONObject().apply {
            put("format", "goku-skill-pack")
            put("version", 10)
            put("exportedAt", System.currentTimeMillis())
            val arr = JSONArray()
            all.forEach { s ->
                arr.put(JSONObject().apply {
                    put("name", s.name)
                    put("triggerPhrase", s.triggerPhrase)
                    put("stepsJson", s.stepsJson)
                    put("preferredEngine", s.preferredEngine)
                    put("adbAvgMs", s.adbAvgMs)
                    put("adbSuccessRate", s.adbSuccessRate)
                    put("a11yAvgMs", s.a11yAvgMs)
                    put("a11ySuccessRate", s.a11ySuccessRate)
                })
            }
            put("skills", arr)
        }
        root.toString(2)
    }

    suspend fun importSkillsFromGokuJson(jsonStr: String): Int = withContext(Dispatchers.IO) {
        val root = JSONObject(jsonStr)
        val arr = root.optJSONArray("skills") ?: JSONArray().apply {
            if (root.has("triggerPhrase")) put(root)
        }
        var imported = 0
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "Imported Skill ${i + 1}")
            val trigger = obj.optString("triggerPhrase", "").trim()
            val steps = obj.optString("stepsJson", "[]")
            if (trigger.isNotEmpty()) {
                db.learnedSkillDao().upsert(
                    LearnedSkillEntity(
                        name = name,
                        triggerPhrase = trigger,
                        stepsJson = steps,
                        preferredEngine = obj.optString("preferredEngine", "INTENT_ADB"),
                        adbAvgMs = obj.optLong("adbAvgMs", 150L),
                        adbSuccessRate = obj.optDouble("adbSuccessRate", 0.95).toFloat(),
                        a11yAvgMs = obj.optLong("a11yAvgMs", 420L),
                        a11ySuccessRate = obj.optDouble("a11ySuccessRate", 0.90).toFloat()
                    )
                )
                imported++
            }
        }
        imported
    }

    suspend fun recordExpense(
        merchant: String,
        amountInr: Double,
        category: String,
        rawSource: String,
        isRecurring: Boolean = false
    ) = withContext(Dispatchers.IO) {
        db.expenseRecordDao().insert(
            ExpenseRecordEntity(
                merchant = merchant.take(80),
                amountInr = amountInr,
                category = category,
                rawSource = rawSource.take(240),
                isRecurring = isRecurring
            )
        )
    }

    suspend fun buildMonthlyExpenseSummaryHinglish(): String = withContext(Dispatchers.IO) {
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
        val items = db.expenseRecordDao().getSince(thirtyDaysAgo)
        if (items.isEmpty()) {
            return@withContext "Pichle 30 din mein koi expense record nahi hua hai. Jaise hi bank ya bill SMS aayega, main auto-track kar loonga."
        }
        val total = items.sumOf { it.amountInr }
        val byCat = items.groupBy { it.category }
            .mapValues { (_, v) -> v.sumOf { it.amountInr } }
            .entries.sortedByDescending { it.value }
            .take(3)
            .joinToString(", ") { "${it.key} ₹${"%.0f".format(it.value)}" }
        "Pichle 30 din mein kul ₹${"%.0f".format(total)} kharch hue (${items.size} entries). Top categories: $byCat."
    }

    suspend fun logAction(
        stage: String,
        command: String,
        detail: String,
        confidence: Int = 100,
        status: String = "OK",
        latencyMs: Long = 0L
    ) = withContext(Dispatchers.IO) {
        db.actionLogDao().insert(
            ActionLogEntity(
                agentStage = stage,
                command = command.take(180),
                detail = detail.take(400),
                confidence = confidence,
                status = status,
                latencyMs = latencyMs
            )
        )
    }

    suspend fun getRecentFailures(): List<ActionLogEntity> = withContext(Dispatchers.IO) {
        db.actionLogDao().getRecentFailures()
    }

    suspend fun pruneOldLogs() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        db.actionLogDao().pruneOlderThan(cutoff)
    }

    companion object {
        @Volatile
        private var INSTANCE: GokuVaultRepository? = null

        fun getInstance(context: Context): GokuVaultRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GokuVaultRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
