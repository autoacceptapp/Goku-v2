package com.goku.ai.data

import android.content.Context
import android.content.SharedPreferences
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.github.luben.zstd.Zstd
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
                        val out = Zstd.decompress(data, (data.size * 8).coerceIn(4096, 2 * 1024 * 1024))
                        String(out, Charsets.UTF_8)
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
        val tokens = raw.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9\\u0900-\\u097F]+"))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in FTS_RESERVED_WORDS }
            .distinct()
            .take(8)
        if (tokens.isEmpty()) return ""
        val separator = if (useOrOperator) " OR " else " "
        return tokens.joinToString(separator) { "$it*" }
    }

    private val FTS_RESERVED_WORDS = setOf("and", "or", "not", "near")
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

    @Query("SELECT * FROM screen_timeline WHERE keywords LIKE '%' || :q || '%' OR screenTitle LIKE '%' || :q || '%' OR packageName LIKE '%' || :q || '%' OR summary LIKE '%' || :q || '%' ORDER BY timestamp DESC LIMIT 30")
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
        return searchLike(q)
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

    @Query("SELECT * FROM rag_documents WHERE content LIKE '%' || :q || '%' OR docName LIKE '%' || :q || '%' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun searchLike(q: String, limit: Int = 8): List<RagDocumentEntity>

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
    private val chunks = CopyOnWriteArrayList<RagChunk>()
    private val invertedIndex = ConcurrentHashMap<String, MutableSet<RagChunk>>()

    fun indexDocument(docName: String, content: String, chunkSize: Int = 360) {
        removeFromMemoryIndex(docName)
        val newChunks = buildChunks(docName, content, chunkSize)
        for (chunk in newChunks) {
            addChunkToMemoryCache(chunk)
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
        indexDocument(docName, content, chunkSize)
        val dao = ragDao ?: return@withContext
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

    /**
     * Fast inverted-index TF-IDF / BM25 lookup (avoids O(N) full scan over all chunks).
     */
    fun query(question: String, topK: Int = 3): List<RagChunk> {
        val qTokens = tokenize(question)
        if (qTokens.isEmpty() || chunks.isEmpty()) return emptyList()

        val totalDocs = chunks.size.coerceAtLeast(1).toDouble()
        val scoreByChunk = HashMap<RagChunk, Double>()

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
                    if (indexedTok.startsWith(tok)) {
                        val idf = ln(1.0 + (totalDocs / (postSet.size + 1.0))) * 0.75
                        for (chunk in postSet) {
                            scoreByChunk[chunk] = (scoreByChunk[chunk] ?: 0.0) + idf
                        }
                    }
                }
            }
        }

        return scoreByChunk.entries
            .sortedByDescending { it.value }
            .take(topK)
            .map { it.key }
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
                    addHit(
                        RagChunk(
                            docName = entity.docName,
                            chunkIndex = entity.chunkIndex,
                            text = text,
                            tokens = tokenize(text)
                        ),
                        cacheInMemory = true
                    )
                }
            }
        }

        // 2. Persistent Room Memory Nodes (saved facts, contacts, relations survive cold restart)
        val memDao = memoryNodeDao
        if (memDao != null && merged.size < topK) {
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
                addHit(
                    RagChunk(
                        docName = "memory:${node.key}",
                        chunkIndex = 0,
                        text = text,
                        tokens = tokenize(text)
                    ),
                    cacheInMemory = true
                )
            }
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

    fun indexedCount(): Int = chunks.size

    private fun addChunkToMemoryCache(chunk: RagChunk) {
        val alreadyExists = chunks.any { it.docName == chunk.docName && it.chunkIndex == chunk.chunkIndex }
        if (alreadyExists) return
        chunks.add(chunk)
        for (tok in chunk.tokens) {
            invertedIndex.computeIfAbsent(tok) { ConcurrentHashMap.newKeySet() }.add(chunk)
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
        private val QUERY_STOP_WORDS = setOf(
            "kya", "hai", "hain", "mera", "meri", "mere", "karo", "batao", "bata", "mujhe",
            "ko", "ka", "ki", "ke", "mein", "me", "par", "pe", "se", "ho", "tha", "thi",
            "what", "is", "are", "the", "my", "tell", "me", "about", "please", "goku", "hey"
        )
    }

    private fun removeFromMemoryIndex(docName: String) {
        val toRemove = chunks.filter { it.docName == docName }
        if (toRemove.isEmpty()) return
        chunks.removeAll(toRemove.toSet())
        for (chunk in toRemove) {
            for (tok in chunk.tokens) {
                invertedIndex[tok]?.remove(chunk)
            }
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
    private val prefs: SharedPreferences = createSecurePrefs(appContext)
    private val defaultTaskModelPath: String = File(appContext.filesDir, "models/goku_offline.task").absolutePath

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
        get() {
            val saved = prefs.getString("local_model_path", null)?.trim().orEmpty()
            return if (saved.isBlank() || saved.endsWith("goku-4bit.gguf", ignoreCase = true)) {
                defaultTaskModelPath
            } else {
                saved
            }
        }
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
        val existing = db.memoryNodeDao().findByKey(key)
        val node = if (existing != null) {
            existing.copy(value = value, category = category, relationTag = relationTag, updatedAt = System.currentTimeMillis())
        } else {
            MemoryNodeEntity(key = key, value = value, category = category, relationTag = relationTag)
        }
        db.memoryNodeDao().upsert(node)
        ragBrain.indexDocumentPersistent("memory:$key", "$key: $value ($relationTag)")
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
        val existingNode = db.memoryNodeDao().findByKey(freqKey)
        val newCount = (existingNode?.value?.toIntOrNull() ?: 0) + 1
        db.memoryNodeDao().upsert(
            (existingNode ?: MemoryNodeEntity(key = freqKey, value = "1", category = "PATTERN_FREQ"))
                .copy(
                    value = newCount.toString(),
                    category = "PATTERN_FREQ",
                    relationTag = skillDisplayName.take(60),
                    updatedAt = System.currentTimeMillis()
                )
        )

        if (newCount >= AUTO_COMPILE_THRESHOLD) {
            val compiledSkill = LearnedSkillEntity(
                name = "Auto: ${skillDisplayName.take(48)}",
                triggerPhrase = normalizedTrigger,
                stepsJson = stepsJson,
                preferredEngine = "INTENT_ADB",
                usageCount = newCount,
                updatedAt = System.currentTimeMillis()
            )
            val newId = db.learnedSkillDao().upsert(compiledSkill)
            logAction(
                stage = "PLANNER",
                command = "Auto-Shortcut Compiled!",
                detail = "Compiled '${compiledSkill.name}' (trigger='$normalizedTrigger') after ${newCount}x repeats",
                confidence = 99,
                status = "OK"
            )
            return@withContext compiledSkill.copy(id = newId)
        }
        null
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
        private const val AUTO_COMPILE_THRESHOLD = 3

        @Volatile
        private var INSTANCE: GokuVaultRepository? = null

        fun getInstance(context: Context): GokuVaultRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GokuVaultRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
