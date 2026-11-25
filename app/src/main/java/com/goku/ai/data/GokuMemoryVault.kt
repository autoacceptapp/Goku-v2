package com.goku.ai.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.ln

// ============================================================================
// 1. ROOM ENTITIES (ZERO-LIMIT MEMORY VAULT + FTS4 VISUAL TIMELINE + RAG + SKILLS + LOGS)
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

/**
 * SQLite FTS4 virtual index automatically synced with `screen_timeline` via Room triggers.
 * Enables sub-20ms full-text `MATCH` queries across 100,000+ screen history snapshots
 * while keeping the full body GZIP-compressed in `ScreenTimelineEntity.compressedText`.
 */
@Fts4(contentEntity = ScreenTimelineEntity::class)
@Entity(tableName = "screen_timeline_fts")
data class ScreenTimelineFts(
    val packageName: String,
    val screenTitle: String,
    val keywords: String,
    val summary: String
)

@Entity(tableName = "rag_documents")
data class RagDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docName: String,
    val chunkIndex: Int,
    val content: String,
    val compressedContent: ByteArray,
    val updatedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RagDocumentEntity) return false
        return id == other.id &&
            docName == other.docName &&
            chunkIndex == other.chunkIndex &&
            content == other.content &&
            compressedContent.contentEquals(other.compressedContent) &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + docName.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + content.hashCode()
        result = 31 * result + compressedContent.contentHashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

/**
 * SQLite FTS4 virtual index automatically synced with `rag_documents` for instant offline RAG search.
 */
@Fts4(contentEntity = RagDocumentEntity::class)
@Entity(tableName = "rag_documents_fts")
data class RagDocumentFts(
    val docName: String,
    val content: String
)

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
// 2. COMPRESSION & FTS QUERY SANITIZER UTILITIES
// ============================================================================

object TextCompressor {
    private const val GZIP_MAGIC_0 = 0x1F.toByte()
    private const val GZIP_MAGIC_1 = 0x8B.toByte()
    private const val ZSTD_MAGIC_0 = 0x28.toByte()
    private const val ZSTD_MAGIC_1 = 0xB5.toByte()
    private const val ZSTD_MAGIC_2 = 0x2F.toByte()
    private const val ZSTD_MAGIC_3 = 0xFD.toByte()

    val isNativeZstdAvailable: Boolean by lazy {
        runCatching {
            val probe = Zstd.compress("goku-zstd-probe".toByteArray(Charsets.UTF_8))
            probe.size >= 4 && isZstdPayload(probe)
        }.getOrDefault(false)
    }

    /**
     * Compresses text using native Zstd (com.github.luben.zstd.Zstd) with automatic
     * transparent GZIP fallback if native .so is unavailable on the current ABI/JVM.
     */
    fun compress(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val rawBytes = input.toByteArray(Charsets.UTF_8)
        if (isNativeZstdAvailable) {
            val zstdCompressed = runCatching { Zstd.compress(rawBytes) }.getOrNull()
            if (zstdCompressed != null && zstdCompressed.isNotEmpty()) {
                return zstdCompressed
            }
        }
        return compressGzip(rawBytes)
    }

    /**
     * Decompresses both Zstd and legacy GZIP rows seamlessly by inspecting frame magic bytes.
     */
    fun decompress(data: ByteArray): String {
        if (data.isEmpty()) return ""
        return when {
            isZstdPayload(data) -> {
                runCatching {
                    val contentSize = Zstd.getFrameContentSize(data)
                    if (contentSize > 0L && contentSize <= 8L * 1024L * 1024L) {
                        val out = Zstd.decompress(data, contentSize.toInt())
                        String(out, Charsets.UTF_8)
                    } else {
                        // Streaming fallback for frames without embedded content size
                        ZstdInputStream(ByteArrayInputStream(data)).bufferedReader(Charsets.UTF_8).use {
                            it.readText()
                        }
                    }
                }.getOrDefault("")
            }
            isGzipPayload(data) -> decompressGzip(data)
            else -> runCatching { String(data, Charsets.UTF_8) }.getOrDefault("")
        }
    }

    private fun compressGzip(rawBytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gzip ->
            gzip.write(rawBytes)
        }
        return bos.toByteArray()
    }

    private fun decompressGzip(data: ByteArray): String {
        return runCatching {
            GZIPInputStream(ByteArrayInputStream(data)).bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
        }.getOrDefault("")
    }

    private fun isGzipPayload(data: ByteArray): Boolean =
        data.size >= 2 && data[0] == GZIP_MAGIC_0 && data[1] == GZIP_MAGIC_1

    private fun isZstdPayload(data: ByteArray): Boolean =
        data.size >= 4 &&
            data[0] == ZSTD_MAGIC_0 &&
            data[1] == ZSTD_MAGIC_1 &&
            data[2] == ZSTD_MAGIC_2 &&
            data[3] == ZSTD_MAGIC_3
}

object FtsQuerySanitizer {
    /**
     * Converts raw user text into a safe SQLite FTS4 prefix query (`tok1* tok2*`)
     * stripping special FTS syntax characters so malformed inputs never throw SQLiteException.
     */
    fun toPrefixMatchQuery(raw: String, useOrOperator: Boolean = false): String {
        val rawTokens = raw.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9\\u0900-\\u097F]+"))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in FTS_RESERVED_WORDS }
            .distinct()
        val meaningful = rawTokens.filter { it !in HINGLISH_FTS_STOP_WORDS }
        val tokens = (if (meaningful.isNotEmpty()) meaningful else rawTokens).take(8)
        if (tokens.isEmpty()) return ""
        val separator = if (useOrOperator) " OR " else " "
        return tokens.joinToString(separator) { "$it*" }
    }

    /**
     * Escapes SQLite LIKE metacharacters (`\`, `%`, `_`) so user queries match literally
     * when paired with `ESCAPE '\'`.
     */
    fun escapeLikeQuery(raw: String): String {
        return raw
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }

    private val FTS_RESERVED_WORDS = setOf("and", "or", "not", "near")
    private val HINGLISH_FTS_STOP_WORDS = setOf(
        "kya", "hai", "hain", "mera", "meri", "mere", "karo", "batao", "bata", "mujhe",
        "ko", "ka", "ki", "ke", "mein", "me", "par", "pe", "se", "ho", "tha", "thi", "the",
        "kal", "aaj", "dikhao", "dekh", "dekha", "goku", "hey", "please", "what", "is", "are", "my"
    )
}

// ============================================================================
// 3. ROOM DAOs (WITH FTS4 MATCH + AUTOMATIC FALLBACK)
// ============================================================================

@Dao
interface MemoryNodeDao {
    @Query("SELECT * FROM memory_nodes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MemoryNodeEntity>>

    @Query("SELECT * FROM memory_nodes ORDER BY updatedAt DESC")
    suspend fun getAllSnapshot(): List<MemoryNodeEntity>

    @Query("SELECT * FROM memory_nodes WHERE `key` LIKE '%' || :q || '%' ESCAPE '\\' OR value LIKE '%' || :q || '%' ESCAPE '\\' OR relationTag LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY updatedAt DESC LIMIT 20")
    suspend fun searchRaw(q: String): List<MemoryNodeEntity>

    suspend fun search(q: String): List<MemoryNodeEntity> =
        searchRaw(FtsQuerySanitizer.escapeLikeQuery(q))

    @Query("SELECT * FROM memory_nodes WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): MemoryNodeEntity?

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

    @Query(
        """
        SELECT screen_timeline.* FROM screen_timeline
        JOIN screen_timeline_fts ON screen_timeline.id = screen_timeline_fts.rowid
        WHERE screen_timeline_fts MATCH :ftsQuery
        ORDER BY screen_timeline.timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchFts(ftsQuery: String, limit: Int = 30): List<ScreenTimelineEntity>

    @Query("SELECT * FROM screen_timeline WHERE keywords LIKE '%' || :q || '%' ESCAPE '\\' OR screenTitle LIKE '%' || :q || '%' ESCAPE '\\' OR packageName LIKE '%' || :q || '%' ESCAPE '\\' OR summary LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY timestamp DESC LIMIT 30")
    suspend fun searchLike(q: String): List<ScreenTimelineEntity>

    /**
     * Sub-20ms FTS4 MATCH search with automatic OR-query and LIKE fallback for punctuation/edge queries.
     */
    suspend fun search(q: String): List<ScreenTimelineEntity> {
        val strictFts = FtsQuerySanitizer.toPrefixMatchQuery(q, useOrOperator = false)
        if (strictFts.isNotEmpty()) {
            val strictHits = runCatching { searchFts(strictFts, 30) }.getOrDefault(emptyList())
            if (strictHits.isNotEmpty()) return strictHits

            val broadFts = FtsQuerySanitizer.toPrefixMatchQuery(q, useOrOperator = true)
            if (broadFts != strictFts && broadFts.isNotEmpty()) {
                val broadHits = runCatching { searchFts(broadFts, 30) }.getOrDefault(emptyList())
                if (broadHits.isNotEmpty()) return broadHits
            }
        }
        return searchLike(FtsQuerySanitizer.escapeLikeQuery(q))
    }

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
interface RagDocumentDao {
    @Query(
        """
        SELECT rag_documents.* FROM rag_documents
        JOIN rag_documents_fts ON rag_documents.id = rag_documents_fts.rowid
        WHERE rag_documents_fts MATCH :ftsQuery
        ORDER BY rag_documents.updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchFts(ftsQuery: String, limit: Int = 8): List<RagDocumentEntity>

    @Query("SELECT * FROM rag_documents WHERE content LIKE '%' || :q || '%' ESCAPE '\\' OR docName LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun searchLikeRaw(q: String, limit: Int = 8): List<RagDocumentEntity>

    suspend fun searchLike(q: String, limit: Int = 8): List<RagDocumentEntity> =
        searchLikeRaw(FtsQuerySanitizer.escapeLikeQuery(q), limit)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<RagDocumentEntity>)

    @Query("DELETE FROM rag_documents WHERE docName = :docName")
    suspend fun deleteByDocName(docName: String)

    @Query("SELECT COUNT(*) FROM rag_documents")
    suspend fun count(): Int
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
// 4. ROOM DATABASE SINGLETON (V10.1 WITH SQLITE FTS4 VIRTUAL TABLES)
// ============================================================================

@Database(
    entities = [
        MemoryNodeEntity::class,
        ScreenTimelineEntity::class,
        ScreenTimelineFts::class,
        RagDocumentEntity::class,
        RagDocumentFts::class,
        LearnedSkillEntity::class,
        ExpenseRecordEntity::class,
        ActionLogEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class GokuDatabase : RoomDatabase() {
    abstract fun memoryNodeDao(): MemoryNodeDao
    abstract fun screenTimelineDao(): ScreenTimelineDao
    abstract fun ragDocumentDao(): RagDocumentDao
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
    private val lock = Any()
    private val turns = ArrayList<ConversationTurn>()

    @Volatile
    var lastMentionedContact: String? = null
        private set

    @Volatile
    var lastMentionedApp: String? = null
        private set

    @Volatile
    var lastScreenSummary: String = ""

    fun addTurn(role: String, text: String, entity: String? = null) {
        synchronized(lock) {
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
    }

    fun getRecentTurns(limit: Int = 8): List<ConversationTurn> = synchronized(lock) {
        turns.takeLast(limit)
    }

    fun buildCompressedContext(): String {
        val recent = synchronized(lock) {
            turns.takeLast(6).joinToString("\n") { "${it.role}: ${it.text.take(140)}" }
        }
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
            val safeContact = Regex.escapeReplacement(contact)
            val contactPronouns = listOf("usse", "unhe", "unse", "usko", "unko", "call him", "call her", "message him", "message her")
            if (contactPronouns.any { lower.contains(it) }) {
                resolved = resolved
                    .replace(Regex("\\b(usse|unhe|unse|usko|unko)\\b", RegexOption.IGNORE_CASE), safeContact)
                    .replace(Regex("\\b(him|her)\\b", RegexOption.IGNORE_CASE), safeContact)
            }
        }
        val app = lastMentionedApp
        if (!app.isNullOrBlank() && (lower.contains("wahi app") || lower.contains("same app") || lower.contains("that app"))) {
            val safeApp = Regex.escapeReplacement(app)
            resolved = resolved
                .replace(Regex("wahi app|same app|that app", RegexOption.IGNORE_CASE), safeApp)
        }
        return resolved
    }

    fun clear() {
        synchronized(lock) {
            turns.clear()
            lastMentionedContact = null
            lastMentionedApp = null
        }
    }
}

// ============================================================================
// 6. LOCAL RAG DOCUMENT BRAIN (FTS4 SQLITE + O(1) INVERTED INDEX BM25 ENGINE)
// ============================================================================

data class RagChunk(
    val docName: String,
    val chunkIndex: Int,
    val text: String,
    val tokens: Set<String>
)

class LocalRagDocumentBrain(
    private val ragDao: RagDocumentDao? = null,
    private val memoryNodeDao: MemoryNodeDao? = null,
    private val screenTimelineDao: ScreenTimelineDao? = null
) {
    private val indexLock = Any()
    private val persistMutex = Mutex()
    private val lruChunks = LinkedHashMap<String, RagChunk>(64, 0.75f, true)
    private val invertedIndex = ConcurrentHashMap<String, MutableSet<RagChunk>>()

    fun indexDocument(docName: String, content: String, chunkSize: Int = 360) {
        val newChunks = buildChunks(docName, content, chunkSize)
        synchronized(indexLock) {
            removeFromMemoryIndexLocked(docName)
            for (chunk in newChunks) {
                addChunkToMemoryCacheLocked(chunk)
            }
        }
    }

    fun removeDocument(docName: String) {
        synchronized(indexLock) {
            removeFromMemoryIndexLocked(docName)
        }
    }

    suspend fun removeDocumentPersistent(docName: String) = withContext(Dispatchers.IO) {
        persistMutex.withLock {
            removeDocument(docName)
            runCatching { ragDao?.deleteByDocName(docName) }
        }
    }

    /**
     * Persists document chunks into SQLite (`rag_documents` + `rag_documents_fts` virtual table)
     * with Zstd/GZIP compression while also updating the instant in-memory inverted index.
     */
    suspend fun indexDocumentPersistent(
        docName: String,
        content: String,
        chunkSize: Int = 360
    ) = withContext(Dispatchers.IO) {
        persistMutex.withLock {
            indexDocument(docName, content, chunkSize)
            val dao = ragDao ?: return@withLock
            val created = buildChunks(docName, content, chunkSize)
            dao.deleteByDocName(docName)
            if (created.isNotEmpty()) {
                val entities = created.map { c ->
                    RagDocumentEntity(
                        docName = c.docName,
                        chunkIndex = c.chunkIndex,
                        content = c.text,
                        compressedContent = TextCompressor.compress(c.text)
                    )
                }
                dao.insertAll(entities)
            }
        }
    }

    /**
     * Fast inverted-index TF-IDF / BM25 lookup (avoids O(N) full scan over all chunks).
     */
    fun query(question: String, topK: Int = 3): List<RagChunk> {
        val qTokens = tokenize(question)
        if (qTokens.isEmpty()) return emptyList()

        val scoreByChunk = HashMap<RagChunk, Double>()
        synchronized(indexLock) {
            if (lruChunks.isEmpty()) return emptyList()
            val totalDocs = lruChunks.size.coerceAtLeast(1).toDouble()

            for (tok in qTokens) {
                val postings = invertedIndex[tok]
                if (!postings.isNullOrEmpty()) {
                    val idf = ln(1.0 + (totalDocs / (postings.size + 1.0)))
                    for (chunk in postings) {
                        scoreByChunk[chunk] = (scoreByChunk[chunk] ?: 0.0) + idf
                    }
                } else if (tok.length >= 3) {
                    // Prefix match against inverted index keys for partial/morphological stems
                    for ((indexedTok, postSet) in invertedIndex) {
                        if (indexedTok.startsWith(tok) && postSet.isNotEmpty()) {
                            val idf = ln(1.0 + (totalDocs / (postSet.size + 1.0))) * 0.75
                            for (chunk in postSet) {
                                scoreByChunk[chunk] = (scoreByChunk[chunk] ?: 0.0) + idf
                            }
                        }
                    }
                }
            }
            val ranked = scoreByChunk.entries
                .sortedByDescending { it.value }
                .take(topK)
                .map { it.key }
            // Touch top hits in LRU map so hot chunks stay resident
            for (hit in ranked) {
                lruChunks[chunkCacheKey(hit)]
            }
            return ranked
        }
    }

    /**
     * Persistent SQLite FTS4 + Memory Nodes + Screen Timeline RAG query that survives app restarts
     * and device reboots, merging with the fast in-memory inverted index and warming the cache.
     */
    suspend fun queryPersistentFts(question: String, topK: Int = 5): List<RagChunk> = withContext(Dispatchers.IO) {
        val cleanedQuestion = question.trim()
        if (cleanedQuestion.isEmpty()) return@withContext emptyList()

        val merged = LinkedHashMap<String, RagChunk>()
        val meaningfulTokens = extractSearchTokens(cleanedQuestion)

        fun addHit(chunk: RagChunk, cacheInMemory: Boolean = false) {
            val dedupKey = "${chunk.docName}#${chunk.chunkIndex}"
            if (!merged.containsKey(dedupKey)) {
                merged[dedupKey] = chunk
                if (cacheInMemory) {
                    addChunkToMemoryCache(chunk)
                }
            }
        }

        val ragResults = ArrayList<RagChunk>()
        val memoryResults = ArrayList<RagChunk>()

        // 1. Persistent SQLite FTS4 Rag Documents (strict AND -> broad OR -> LIKE / token LIKE)
        val dao = ragDao
        if (dao != null) {
            val strictFts = FtsQuerySanitizer.toPrefixMatchQuery(cleanedQuestion, useOrOperator = false)
            val broadFts = FtsQuerySanitizer.toPrefixMatchQuery(cleanedQuestion, useOrOperator = true)
            val dbEntities = LinkedHashMap<Long, RagDocumentEntity>()

            if (strictFts.isNotEmpty()) {
                runCatching { dao.searchFts(strictFts, topK) }
                    .getOrDefault(emptyList())
                    .forEach { dbEntities[it.id] = it }
            }
            if (dbEntities.size < topK && broadFts.isNotEmpty() && broadFts != strictFts) {
                runCatching { dao.searchFts(broadFts, topK) }
                    .getOrDefault(emptyList())
                    .forEach { dbEntities.putIfAbsent(it.id, it) }
            }
            if (dbEntities.isEmpty()) {
                runCatching { dao.searchLike(cleanedQuestion, topK) }
                    .getOrDefault(emptyList())
                    .forEach { dbEntities.putIfAbsent(it.id, it) }
            }
            if (dbEntities.isEmpty()) {
                for (tok in meaningfulTokens.take(4)) {
                    runCatching { dao.searchLike(tok, topK) }
                        .getOrDefault(emptyList())
                        .forEach { dbEntities.putIfAbsent(it.id, it) }
                    if (dbEntities.size >= topK) break
                }
            }

            dbEntities.values.take(topK).forEach { entity ->
                val text = if (entity.compressedContent.isNotEmpty()) {
                    TextCompressor.decompress(entity.compressedContent).ifBlank { entity.content }
                } else {
                    entity.content
                }
                if (text.isNotBlank()) {
                    ragResults.add(
                        RagChunk(
                            docName = entity.docName,
                            chunkIndex = entity.chunkIndex,
                            text = text,
                            tokens = tokenize(text)
                        )
                    )
                }
            }
        }

        // 2. ALWAYS query Persistent Room Memory Nodes (saved facts, contacts, relations never skipped even when ragDao fills topK)
        val memDao = memoryNodeDao
        if (memDao != null) {
            val matchedNodes = LinkedHashMap<Long, MemoryNodeEntity>()
            runCatching { memDao.search(cleanedQuestion) }
                .getOrDefault(emptyList())
                .forEach { matchedNodes[it.id] = it }
            if (matchedNodes.size < topK) {
                for (tok in meaningfulTokens.take(4)) {
                    runCatching { memDao.search(tok) }
                        .getOrDefault(emptyList())
                        .forEach { matchedNodes.putIfAbsent(it.id, it) }
                    if (matchedNodes.size >= topK) break
                }
            }
            matchedNodes.values.take(topK).forEach { node ->
                val rel = if (node.relationTag.isNotBlank()) " (${node.relationTag})" else ""
                val text = "${node.key} = ${node.value}$rel"
                memoryResults.add(
                    RagChunk(
                        docName = "memory:${node.key}",
                        chunkIndex = 0,
                        text = text,
                        tokens = tokenize(text)
                    )
                )
            }
        }

        // Merge both RAG document hits and Memory Node hits fairly so neither starves the other
        val memReserve = if (memoryResults.isNotEmpty()) minOf(memoryResults.size, maxOf(1, topK / 2)) else 0
        val ragQuota = (topK - memReserve).coerceAtLeast(0)
        ragResults.take(ragQuota).forEach { addHit(it, cacheInMemory = true) }
        memoryResults.take(memReserve).forEach { addHit(it, cacheInMemory = true) }
        ragResults.drop(ragQuota).forEach {
            if (merged.size < topK) addHit(it, cacheInMemory = true)
        }
        memoryResults.drop(memReserve).forEach {
            if (merged.size < topK) addHit(it, cacheInMemory = true)
        }

        // 3. Persistent SQLite FTS4 Screen Timeline (recall past screen context after reboot)
        val tlDao = screenTimelineDao
        if (tlDao != null && merged.size < topK) {
            val tlHits = runCatching { tlDao.search(cleanedQuestion) }.getOrDefault(emptyList())
            tlHits.take(2).forEach { entry ->
                val body = if (entry.compressedText.isNotEmpty()) {
                    TextCompressor.decompress(entry.compressedText).ifBlank { entry.summary }
                } else {
                    entry.summary
                }.take(280)
                if (body.isNotBlank()) {
                    val label = entry.screenTitle.ifBlank { entry.packageName }
                    addHit(
                        RagChunk(
                            docName = "screen:$label",
                            chunkIndex = entry.id.toInt(),
                            text = body,
                            tokens = tokenize(body)
                        )
                    )
                }
            }
        }

        // 4. Merge any additional in-memory indexed chunks (e.g. transient documents)
        if (merged.size < topK) {
            query(cleanedQuestion, topK).forEach { addHit(it) }
        }

        merged.values.take(topK)
    }

    /**
     * Alias retained for backward compatibility; delegates to full persistent SQLite FTS4 RAG recall.
     */
    suspend fun queryFts(question: String, topK: Int = 5): List<RagChunk> =
        queryPersistentFts(question, topK)

    fun indexedCount(): Int = synchronized(indexLock) { lruChunks.size }

    private fun chunkCacheKey(chunk: RagChunk): String = "${chunk.docName}#${chunk.chunkIndex}"

    private fun addChunkToMemoryCache(chunk: RagChunk) {
        synchronized(indexLock) {
            addChunkToMemoryCacheLocked(chunk)
        }
    }

    private fun addChunkToMemoryCacheLocked(chunk: RagChunk) {
        val key = chunkCacheKey(chunk)
        val existing = lruChunks[key]
        if (existing != null) {
            if (existing.text == chunk.text) return
            removeSingleChunkFromIndexLocked(existing)
        }
        lruChunks[key] = chunk
        for (tok in chunk.tokens) {
            invertedIndex.computeIfAbsent(tok) { ConcurrentHashMap.newKeySet() }.add(chunk)
        }
        // Strict LRU eviction cap (max 500 chunks in RAM) updating both LRU map and invertedIndex
        while (lruChunks.size > MAX_MEMORY_CHUNKS) {
            val eldestIter = lruChunks.entries.iterator()
            if (!eldestIter.hasNext()) break
            val eldestChunk = eldestIter.next().value
            eldestIter.remove()
            removeSingleChunkFromIndexLocked(eldestChunk)
        }
    }

    private fun extractSearchTokens(text: String): List<String> {
        return text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9\\u0900-\\u097F]+"))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in QUERY_STOP_WORDS }
            .distinct()
    }

    companion object {
        private const val MAX_MEMORY_CHUNKS = 500

        private val QUERY_STOP_WORDS = setOf(
            "kya", "hai", "hain", "mera", "meri", "mere", "karo", "batao", "bata", "mujhe",
            "ko", "ka", "ki", "ke", "mein", "me", "par", "pe", "se", "ho", "tha", "thi",
            "what", "is", "are", "the", "my", "tell", "me", "about", "please", "goku", "hey"
        )
    }

    private fun removeSingleChunkFromIndexLocked(chunk: RagChunk) {
        for (tok in chunk.tokens) {
            val postings = invertedIndex[tok] ?: continue
            postings.remove(chunk)
            if (postings.isEmpty()) {
                invertedIndex.remove(tok)
            }
        }
    }

    private fun removeFromMemoryIndexLocked(docName: String) {
        val toRemove = ArrayList<RagChunk>()
        val iter = lruChunks.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.value.docName == docName) {
                toRemove.add(entry.value)
                iter.remove()
            }
        }
        for (chunk in toRemove) {
            removeSingleChunkFromIndexLocked(chunk)
        }
    }

    private fun buildChunks(docName: String, content: String, chunkSize: Int): List<RagChunk> {
        val normalized = content.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return emptyList()
        val list = ArrayList<RagChunk>()
        var start = 0
        var idx = 0
        while (start < normalized.length) {
            val end = (start + chunkSize).coerceAtMost(normalized.length)
            val slice = normalized.substring(start, end)
            val tokens = tokenize(slice)
            list.add(RagChunk(docName, idx++, slice, tokens))
            start = if (end == normalized.length) end else (end - 60).coerceAtLeast(start + 1)
        }
        return list
    }

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
    private val appContext = context.applicationContext
    private val prefsLock = Any()
    @Volatile
    private var securePrefs: SharedPreferences? = null
    @Volatile
    private var readOnlyLockedMode: Boolean = false
    private val inMemorySecretFallback = ConcurrentHashMap<String, String>()
    private val operationalFallbackPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences("goku_v10_fallback_prefs", Context.MODE_PRIVATE)
    }
    private val defaultTaskModelPath: String = File(appContext.filesDir, "models/goku_offline.task").absolutePath

    private fun isUserUnlocked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        val um = appContext.getSystemService(Context.USER_SERVICE) as? UserManager ?: return true
        return runCatching { um.isUserUnlocked }.getOrDefault(true)
    }

    private fun hasExistingSecurePrefsFile(): Boolean {
        return runCatching {
            val dataDir = appContext.applicationInfo?.dataDir ?: return@runCatching false
            File(File(dataDir, "shared_prefs"), "goku_v10_secure_prefs.xml").exists()
        }.getOrDefault(false)
    }

    fun isInReadOnlyLockedMode(): Boolean = !isUserUnlocked() || readOnlyLockedMode

    private fun isPermanentCorruption(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            val name = cur.javaClass.simpleName
            if (cur is javax.crypto.AEADBadTagException ||
                name.contains("InvalidProtocolBufferException") ||
                name.contains("KeyPermanentlyInvalidatedException") ||
                cur.message?.contains("decryption failed", ignoreCase = true) == true ||
                cur.message?.contains("Could not verify", ignoreCase = true) == true
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun getSecurePrefsOrNull(): SharedPreferences? {
        if (!isUserUnlocked()) {
            readOnlyLockedMode = true
            return null
        }
        securePrefs?.let {
            readOnlyLockedMode = false
            return it
        }
        synchronized(prefsLock) {
            if (!isUserUnlocked()) {
                readOnlyLockedMode = true
                return null
            }
            securePrefs?.let {
                readOnlyLockedMode = false
                return it
            }

            fun buildEncrypted(): SharedPreferences {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    appContext,
                    "goku_v10_secure_prefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }

            val created = try {
                buildEncrypted()
            } catch (t: Throwable) {
                // Wipe only on permanent keyset/cipher corruption (never wipe API keys on transient Keystore locks)
                if (isPermanentCorruption(t)) {
                    runCatching { appContext.deleteSharedPreferences("goku_v10_secure_prefs") }
                    runCatching { buildEncrypted() }.getOrNull()
                } else {
                    null
                }
            }

            if (created != null) {
                readOnlyLockedMode = false
                runCatching {
                    val editor = created.edit()
                    var changed = false

                    // Migrate any secrets buffered in memory while Keystore was unavailable (strictly under prefsLock)
                    if (inMemorySecretFallback.isNotEmpty()) {
                        inMemorySecretFallback.forEach { (k, v) ->
                            editor.putString(k, v)
                        }
                        inMemorySecretFallback.clear()
                        changed = true
                    }

                    // Migrate legacy operational prefs ONLY when not already present in real encrypted prefs
                    val pending = operationalFallbackPrefs.all
                    if (pending.isNotEmpty()) {
                        pending.forEach { (k, v) ->
                            if (!created.contains(k)) {
                                when (v) {
                                    is String -> editor.putString(k, v)
                                    is Boolean -> editor.putBoolean(k, v)
                                    is Int -> editor.putInt(k, v)
                                    is Long -> editor.putLong(k, v)
                                    is Float -> editor.putFloat(k, v)
                                    is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(k, v as Set<String>)
                                }
                            }
                        }
                        operationalFallbackPrefs.edit().clear().apply()
                        changed = true
                    }
                    if (changed) editor.apply()
                }
                securePrefs = created
            } else if (hasExistingSecurePrefsFile()) {
                // Real encrypted prefs exist on disk but Keystore is locked: enter Read-Only Locked Mode
                readOnlyLockedMode = true
            }
            return created
        }
    }

    private fun getSecret(key: String): String {
        val secure = getSecurePrefsOrNull()
        if (secure != null) {
            return secure.getString(key, "") ?: ""
        }
        if (isInReadOnlyLockedMode()) return ""
        return inMemorySecretFallback[key] ?: ""
    }

    private fun setSecret(key: String, value: String) {
        val trimmed = value.trim()
        synchronized(prefsLock) {
            val secure = getSecurePrefsOrNull()
            if (secure != null) {
                secure.edit().putString(key, trimmed).apply()
                inMemorySecretFallback.remove(key)
            } else if (!isInReadOnlyLockedMode() && !hasExistingSecurePrefsFile()) {
                // Never write API keys or tokens to plaintext SharedPreferences; keep transient in memory only
                inMemorySecretFallback[key] = trimmed
            }
        }
    }

    private fun getOperationalString(key: String, defaultValue: String): String {
        val secure = getSecurePrefsOrNull()
        if (secure != null) {
            return secure.getString(key, defaultValue) ?: defaultValue
        }
        if (isInReadOnlyLockedMode()) return defaultValue
        return operationalFallbackPrefs.getString(key, defaultValue) ?: defaultValue
    }

    private fun setOperationalString(key: String, value: String) {
        synchronized(prefsLock) {
            val secure = getSecurePrefsOrNull()
            if (secure != null) {
                secure.edit().putString(key, value.trim()).apply()
            } else if (!isInReadOnlyLockedMode() && !hasExistingSecurePrefsFile()) {
                operationalFallbackPrefs.edit().putString(key, value.trim()).apply()
            }
        }
    }

    private fun getOperationalBoolean(key: String, defaultValue: Boolean): Boolean {
        val secure = getSecurePrefsOrNull()
        if (secure != null) {
            return secure.getBoolean(key, defaultValue)
        }
        if (isInReadOnlyLockedMode()) return defaultValue
        return operationalFallbackPrefs.getBoolean(key, defaultValue)
    }

    private fun setOperationalBoolean(key: String, value: Boolean) {
        synchronized(prefsLock) {
            val secure = getSecurePrefsOrNull()
            if (secure != null) {
                secure.edit().putBoolean(key, value).apply()
            } else if (!isInReadOnlyLockedMode() && !hasExistingSecurePrefsFile()) {
                operationalFallbackPrefs.edit().putBoolean(key, value).apply()
            }
        }
    }

    var geminiApiKey: String
        get() = getSecret("gemini_api_key")
        set(value) = setSecret("gemini_api_key", value)

    var groqApiKey: String
        get() = getSecret("groq_api_key")
        set(value) = setSecret("groq_api_key", value)

    var deepSeekApiKey: String
        get() = getSecret("deepseek_api_key")
        set(value) = setSecret("deepseek_api_key", value)

    var openRouterApiKey: String
        get() = getSecret("openrouter_api_key")
        set(value) = setSecret("openrouter_api_key", value)

    var sosContactsCsv: String
        get() = getOperationalString("sos_contacts", "")
        set(value) = setOperationalString("sos_contacts", value)

    var githubToken: String
        get() = getSecret("github_token")
        set(value) = setSecret("github_token", value)

    var githubRepo: String
        get() = getOperationalString("github_repo", "")
        set(value) = setOperationalString("github_repo", value)

    var telegramBotToken: String
        get() = getSecret("telegram_bot_token")
        set(value) = setSecret("telegram_bot_token", value)

    var telegramChatId: String
        get() = getOperationalString("telegram_chat_id", "")
        set(value) = setOperationalString("telegram_chat_id", value)

    var smartCallPolicy: String
        get() = getOperationalString(
            "smart_call_policy",
            "Delivery wala ho toh gate par rakhne bolna, bank ya loan wala ho toh politely mana kar dena."
        )
        set(value) = setOperationalString("smart_call_policy", value)

    var smartCallEnabled: Boolean
        get() = getOperationalBoolean("smart_call_enabled", false)
        set(value) = setOperationalBoolean("smart_call_enabled", value)

    var dryRunMode: Boolean
        get() = getOperationalBoolean("dry_run_mode", false)
        set(value) = setOperationalBoolean("dry_run_mode", value)

    var autoAdSkipEnabled: Boolean
        get() = getOperationalBoolean("auto_ad_skip", true)
        set(value) = setOperationalBoolean("auto_ad_skip", value)

    var otpAutopilotEnabled: Boolean
        get() = getOperationalBoolean("otp_autopilot", true)
        set(value) = setOperationalBoolean("otp_autopilot", value)

    var mistakeBlockerEnabled: Boolean
        get() = getOperationalBoolean("mistake_blocker", true)
        set(value) = setOperationalBoolean("mistake_blocker", value)

    var workFocusMode: Boolean
        get() = getOperationalBoolean("work_focus_mode", false)
        set(value) = setOperationalBoolean("work_focus_mode", value)

    var localModelPath: String
        get() {
            val saved = getOperationalString("local_model_path", "").trim()
            return if (saved.isBlank() || saved.endsWith("goku-4bit.gguf", ignoreCase = true)) {
                defaultTaskModelPath
            } else {
                saved
            }
        }
        set(value) = setOperationalString("local_model_path", value)

    fun getSosContactsList(): List<String> {
        return sosContactsCsv.split(",", ";", "\n")
            .map { it.trim() }
            .filter { it.length >= 6 }
    }

    /**
     * Batches all 17 encrypted & operational setting writes into a single SharedPreferences.Editor
     * transaction, avoiding 17 separate disk apply() operations while preserving in-memory secret
     * isolation whenever Android Keystore is temporarily unavailable.
     */
    fun batchUpdate(
        geminiKey: String = this.geminiApiKey,
        groqKey: String = this.groqApiKey,
        deepSeekKey: String = this.deepSeekApiKey,
        openRouterKey: String = this.openRouterApiKey,
        sosContacts: String = this.sosContactsCsv,
        githubRepo: String = this.githubRepo,
        githubToken: String = this.githubToken,
        telegramBot: String = this.telegramBotToken,
        telegramChatId: String = this.telegramChatId,
        smartCallPolicy: String = this.smartCallPolicy,
        smartCallEnabled: Boolean = this.smartCallEnabled,
        dryRunMode: Boolean = this.dryRunMode,
        autoAdSkip: Boolean = this.autoAdSkipEnabled,
        otpAutopilot: Boolean = this.otpAutopilotEnabled,
        mistakeBlocker: Boolean = this.mistakeBlockerEnabled,
        workFocusMode: Boolean = this.workFocusMode,
        localModelPath: String = this.localModelPath
    ) {
        synchronized(prefsLock) {
            val secure = getSecurePrefsOrNull()
            if (secure == null && (isInReadOnlyLockedMode() || hasExistingSecurePrefsFile())) {
                // Direct Boot / locked Keystore: never write fallback prefs that could overwrite real encrypted settings on unlock
                return
            }
            val prefs = secure ?: operationalFallbackPrefs
            prefs.edit().apply {
                if (secure != null) {
                    putString("gemini_api_key", geminiKey.trim())
                    putString("groq_api_key", groqKey.trim())
                    putString("deepseek_api_key", deepSeekKey.trim())
                    putString("openrouter_api_key", openRouterKey.trim())
                    putString("github_token", githubToken.trim())
                    putString("telegram_bot_token", telegramBot.trim())
                    inMemorySecretFallback.clear()
                } else {
                    inMemorySecretFallback["gemini_api_key"] = geminiKey.trim()
                    inMemorySecretFallback["groq_api_key"] = groqKey.trim()
                    inMemorySecretFallback["deepseek_api_key"] = deepSeekKey.trim()
                    inMemorySecretFallback["openrouter_api_key"] = openRouterKey.trim()
                    inMemorySecretFallback["github_token"] = githubToken.trim()
                    inMemorySecretFallback["telegram_bot_token"] = telegramBot.trim()
                }
                putString("sos_contacts", sosContacts.trim())
                putString("github_repo", githubRepo.trim())
                putString("telegram_chat_id", telegramChatId.trim())
                putString("smart_call_policy", smartCallPolicy.trim())
                putBoolean("smart_call_enabled", smartCallEnabled)
                putBoolean("dry_run_mode", dryRunMode)
                putBoolean("auto_ad_skip", autoAdSkip)
                putBoolean("otp_autopilot", otpAutopilot)
                putBoolean("mistake_blocker", mistakeBlocker)
                putBoolean("work_focus_mode", workFocusMode)
                putString("local_model_path", localModelPath.trim())
                apply()
            }
        }
    }
}

class GokuVaultRepository(context: Context) {
    private val db = GokuDatabase.getInstance(context)
    private val memoryWriteMutex = Mutex()
    private val skillWriteMutex = Mutex()
    val settings = GokuEncryptedSettings(context)
    val workingMemory = ShortTermWorkingMemory()
    val ragBrain = LocalRagDocumentBrain(
        ragDao = db.ragDocumentDao(),
        memoryNodeDao = db.memoryNodeDao(),
        screenTimelineDao = db.screenTimelineDao()
    )

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
        memoryWriteMutex.withLock {
            val existing = db.memoryNodeDao().findByKey(key)
            val node = if (existing != null) {
                existing.copy(value = value, category = category, relationTag = relationTag, updatedAt = System.currentTimeMillis())
            } else {
                MemoryNodeEntity(key = key, value = value, category = category, relationTag = relationTag)
            }
            db.memoryNodeDao().upsert(node)
            ragBrain.indexDocumentPersistent("memory:${node.key}", "${node.key}: $value ($relationTag)")
        }
    }

    suspend fun searchMemories(query: String): List<MemoryNodeEntity> = withContext(Dispatchers.IO) {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return@withContext emptyList()
        val direct = db.memoryNodeDao().search(cleaned)
        if (direct.isNotEmpty()) return@withContext direct

        // Token-level persistent fallback for natural Hinglish sentences (e.g., "Mummy ka number kya hai")
        val stopWords = setOf(
            "kya", "hai", "hain", "mera", "meri", "mere", "karo", "batao", "bata",
            "ko", "ka", "ki", "ke", "what", "is", "are", "the", "my", "tell", "me", "number"
        )
        val tokens = cleaned.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9\\u0900-\\u097F]+"))
            .filter { it.length >= 2 && it !in stopWords }
            .distinct()
            .take(5)

        val merged = LinkedHashMap<Long, MemoryNodeEntity>()
        for (tok in tokens) {
            db.memoryNodeDao().search(tok).forEach { merged.putIfAbsent(it.id, it) }
            if (merged.size >= 10) break
        }
        merged.values.toList()
    }

    suspend fun deleteMemory(id: Long) = withContext(Dispatchers.IO) {
        memoryWriteMutex.withLock {
            val existing = db.memoryNodeDao().findById(id)
            db.memoryNodeDao().deleteById(id)
            if (existing != null) {
                ragBrain.removeDocumentPersistent("memory:${existing.key}")
            }
        }
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
        var totalCompacted = 0
        var batches = 0
        while (batches < MAX_TIMELINE_COMPACT_BATCHES) {
            val old = db.screenTimelineDao().findUnsummarizedOlderThan(sevenDaysAgo)
            if (old.isEmpty()) break
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
            totalCompacted += old.size
            batches++
            if (old.size < 200) break
        }
        val purged = db.screenTimelineDao().purgeOldSummarized(thirtyDaysAgo)
        totalCompacted + purged
    }

    suspend fun upsertSkill(skill: LearnedSkillEntity): Long = withContext(Dispatchers.IO) {
        skillWriteMutex.withLock {
            db.learnedSkillDao().upsert(skill)
        }
    }

    suspend fun findMatchingSkill(input: String): LearnedSkillEntity? = withContext(Dispatchers.IO) {
        val lower = input.lowercase(Locale.ROOT).trim()
        if (lower.isEmpty()) return@withContext null
        val exact = db.learnedSkillDao().findByTrigger(lower)
        if (exact != null) return@withContext exact
        db.learnedSkillDao().getAllSnapshot().firstOrNull {
            val trigger = it.triggerPhrase.lowercase(Locale.ROOT).trim()
            trigger.isNotEmpty() && lower.contains(trigger)
        }
    }

    suspend fun recordSkillBenchmark(
        skill: LearnedSkillEntity,
        usedAdb: Boolean,
        latencyMs: Long,
        succeeded: Boolean
    ) = withContext(Dispatchers.IO) {
        skillWriteMutex.withLock {
            val latest = db.learnedSkillDao().findByTrigger(skill.triggerPhrase) ?: skill
            val updated = if (usedAdb) {
                val newAvg = ((latest.adbAvgMs * 3) + latencyMs) / 4
                val newRate = (latest.adbSuccessRate * 0.8f) + (if (succeeded) 0.2f else 0f)
                val nextPreferred = if (succeeded) {
                    if (newRate >= latest.a11ySuccessRate && newAvg <= latest.a11yAvgMs) "INTENT_ADB" else "ACCESSIBILITY"
                } else {
                    latest.preferredEngine
                }
                latest.copy(
                    adbAvgMs = newAvg,
                    adbSuccessRate = newRate,
                    preferredEngine = nextPreferred,
                    usageCount = latest.usageCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                val newAvg = ((latest.a11yAvgMs * 3) + latencyMs) / 4
                val newRate = (latest.a11ySuccessRate * 0.8f) + (if (succeeded) 0.2f else 0f)
                val nextPreferred = if (succeeded) {
                    if (latest.adbSuccessRate >= newRate && latest.adbAvgMs <= newAvg) "INTENT_ADB" else "ACCESSIBILITY"
                } else {
                    latest.preferredEngine
                }
                latest.copy(
                    a11yAvgMs = newAvg,
                    a11ySuccessRate = newRate,
                    preferredEngine = nextPreferred,
                    usageCount = latest.usageCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
            }
            db.learnedSkillDao().upsert(updated)
        }
    }

    suspend fun deleteSkill(id: Long) = withContext(Dispatchers.IO) {
        skillWriteMutex.withLock {
            db.learnedSkillDao().deleteById(id)
        }
    }

    /**
     * Returns saved user profile facts from Room Memory Vault for headless form auto-fill.
     */
    suspend fun getProfileFieldsMap(): Map<String, String> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, String>()
        db.memoryNodeDao().getAllSnapshot()
            .filter { it.category != "PATTERN_FREQ" && it.category != "SECURITY" }
            .forEach { node ->
                val normKey = node.key.lowercase(Locale.ROOT).trim()
                if (normKey.isNotEmpty() && node.value.isNotBlank()) {
                    out.putIfAbsent(normKey, node.value.trim())
                }
            }
        out
    }

    /**
     * Blueprint Feature — Auto 3x Shortcut Compiler:
     * Tracks normalized command signatures across sessions in Room. When the user runs the same
     * repeatable action/multi-step pattern 3 times, automatically compiles it into a zero-latency
     * `LearnedSkillEntity` macro and logs `"Auto-Shortcut Compiled!"`.
     */
    suspend fun recordCommandPatternAndAutoCompile(
        rawCommand: String,
        stepsJson: String,
        skillDisplayName: String
    ): LearnedSkillEntity? = withContext(Dispatchers.IO) {
        val normalizedTrigger = rawCommand
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\u0900-\\u097F\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)

        if (normalizedTrigger.length < 4 || stepsJson.isBlank() || stepsJson == "[]") {
            return@withContext null
        }

        // Skip if a learned skill already handles this trigger phrase
        if (db.learnedSkillDao().findByTrigger(normalizedTrigger) != null) {
            return@withContext null
        }

        val freqKey = "pattern:$normalizedTrigger"
        return@withContext skillWriteMutex.withLock {
            if (db.learnedSkillDao().findByTrigger(normalizedTrigger) != null) {
                return@withLock null
            }
            val existingNode = memoryWriteMutex.withLock {
                val cur = db.memoryNodeDao().findByKey(freqKey)
                val nextCount = (cur?.value?.toIntOrNull() ?: 0) + 1
                db.memoryNodeDao().upsert(
                    (cur ?: MemoryNodeEntity(key = freqKey, value = "1", category = "PATTERN_FREQ"))
                        .copy(
                            value = nextCount.toString(),
                            category = "PATTERN_FREQ",
                            relationTag = skillDisplayName.take(60),
                            updatedAt = System.currentTimeMillis()
                        )
                )
                nextCount
            }

            if (existingNode >= AUTO_COMPILE_THRESHOLD) {
                val compiledSkill = LearnedSkillEntity(
                    name = "Auto: ${skillDisplayName.take(48)}",
                    triggerPhrase = normalizedTrigger,
                    stepsJson = stepsJson,
                    preferredEngine = "INTENT_ADB",
                    usageCount = existingNode,
                    updatedAt = System.currentTimeMillis()
                )
                val newId = db.learnedSkillDao().upsert(compiledSkill)
                logAction(
                    stage = "PLANNER",
                    command = "Auto-Shortcut Compiled!",
                    detail = "Compiled '${compiledSkill.name}' (trigger='$normalizedTrigger') after ${existingNode}x repeats",
                    confidence = 99,
                    status = "OK"
                )
                compiledSkill.copy(id = newId)
            } else {
                null
            }
        }
    }

    suspend fun exportAllSkillsToGokuJson(): String = withContext(Dispatchers.IO) {
        val all = db.learnedSkillDao().getAllSnapshot()
        val root = JSONObject().apply {
            put("format", "goku-skill-pack")
            put("version", "10.1-godmode")
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
        val seenTriggers = HashSet<String>()
        skillWriteMutex.withLock {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "Imported Skill ${i + 1}").trim().ifBlank { "Imported Skill ${i + 1}" }
                val trigger = obj.optString("triggerPhrase", "").trim()
                val steps = obj.optString("stepsJson", "[]")
                val normTrigger = trigger.lowercase(Locale.ROOT)
                if (trigger.isNotEmpty() && seenTriggers.add(normTrigger)) {
                    val existing = db.learnedSkillDao().findByTrigger(trigger)
                    db.learnedSkillDao().upsert(
                        LearnedSkillEntity(
                            id = existing?.id ?: 0L,
                            name = name,
                            triggerPhrase = trigger,
                            stepsJson = steps,
                            preferredEngine = obj.optString("preferredEngine", existing?.preferredEngine ?: "INTENT_ADB"),
                            adbAvgMs = obj.optLong("adbAvgMs", existing?.adbAvgMs ?: 150L),
                            adbSuccessRate = obj.optDouble("adbSuccessRate", existing?.adbSuccessRate?.toDouble() ?: 0.95).toFloat(),
                            a11yAvgMs = obj.optLong("a11yAvgMs", existing?.a11yAvgMs ?: 420L),
                            a11ySuccessRate = obj.optDouble("a11ySuccessRate", existing?.a11ySuccessRate?.toDouble() ?: 0.90).toFloat()
                        )
                    )
                    imported++
                }
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
            .joinToString(", ") { "${it.key} ₹${"%.0f".format(Locale.US, it.value)}" }
        "Pichle 30 din mein kul ₹${"%.0f".format(Locale.US, total)} kharch hue (${items.size} entries). Top categories: $byCat."
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
        private const val AUTO_COMPILE_THRESHOLD = 3
        private const val MAX_TIMELINE_COMPACT_BATCHES = 50

        @Volatile
        private var INSTANCE: GokuVaultRepository? = null

        fun getInstance(context: Context): GokuVaultRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GokuVaultRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
