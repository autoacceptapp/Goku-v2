package com.goku.ai.body

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.goku.ai.adb.LocalAdbEngine
import com.goku.ai.data.GokuVaultRepository
import com.goku.ai.voice.GokuKillSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ============================================================================
// 1. LIVE OTP BUS, MISTAKE BLOCKER BUS, TEACH RECORDER & ZERO-COPY BRIDGE
// ============================================================================

object OtpBus {
    private val _latestOtp = MutableStateFlow<String?>(null)
    val latestOtp: StateFlow<String?> = _latestOtp.asStateFlow()

    @Volatile
    private var otpTimestamp: Long = 0L

    fun publishOtp(code: String) {
        val digits = code.filter { it.isDigit() }
        if (digits.length in 4..8) {
            _latestOtp.value = digits
            otpTimestamp = System.currentTimeMillis()
        }
    }

    fun consumeFreshOtp(maxAgeMs: Long = 120_000L): String? {
        val current = _latestOtp.value ?: return null
        if (System.currentTimeMillis() - otpTimestamp > maxAgeMs) {
            _latestOtp.value = null
            return null
        }
        _latestOtp.value = null
        return current
    }
}

object MistakeBlockerBus {
    private val _activeWarning = MutableStateFlow<String?>(null)
    val activeWarning: StateFlow<String?> = _activeWarning.asStateFlow()

    fun raiseWarning(message: String) {
        _activeWarning.value = message
    }

    fun dismiss() {
        _activeWarning.value = null
    }
}

data class RecordedStep(
    val action: String,
    val target: String,
    val packageName: String,
    val timestamp: Long = System.currentTimeMillis()
)

object TeachModeRecorder {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordedSteps = MutableStateFlow<List<RecordedStep>>(emptyList())
    val recordedSteps: StateFlow<List<RecordedStep>> = _recordedSteps.asStateFlow()

    private var pendingName: String = "Custom Macro"
    private var pendingTrigger: String = "custom macro"

    fun startRecording(skillName: String, triggerPhrase: String) {
        pendingName = skillName.ifBlank { "Taught Skill" }
        pendingTrigger = triggerPhrase.ifBlank { pendingName.lowercase(Locale.ROOT) }
        _recordedSteps.value = emptyList()
        _isRecording.value = true
    }

    fun recordStep(action: String, target: String, packageName: String) {
        if (!_isRecording.value) return
        val cleanTarget = target.trim()
        if (cleanTarget.isEmpty()) return
        val current = _recordedSteps.value
        val last = current.lastOrNull()
        if (last != null && last.action == action && last.target == cleanTarget &&
            System.currentTimeMillis() - last.timestamp < 800L
        ) return
        _recordedSteps.value = current + RecordedStep(action, cleanTarget, packageName)
    }

    fun stopAndExport(): Triple<String, String, String> {
        _isRecording.value = false
        val arr = JSONArray()
        _recordedSteps.value.forEach { step ->
            arr.put(JSONObject().apply {
                put("action", step.action)
                put("target", step.target)
                put("packageName", step.packageName)
            })
        }
        val result = Triple(pendingName, pendingTrigger, arr.toString())
        _recordedSteps.value = emptyList()
        return result
    }
}

/**
 * Zero-Copy App Bridge: Extracts structured values (emails, phones, tracking IDs, OTPs, amounts)
 * from one app screen so Goku can fill another app directly without touching the system clipboard.
 */
object ZeroCopyAppBridge {
    private val _capturedFields = MutableStateFlow<Map<String, String>>(emptyMap())
    val capturedFields: StateFlow<Map<String, String>> = _capturedFields.asStateFlow()

    private val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    private val phoneRegex = Regex("(?:\\+91[-\\s]?)?[6-9]\\d{9}")

    fun captureFromScreen(packageName: String, rawText: String) {
        if (rawText.isBlank()) return
        val updated = _capturedFields.value.toMutableMap()
        updated["last_package"] = packageName
        emailRegex.find(rawText)?.value?.let { updated["email"] = it }
        phoneRegex.find(rawText)?.value?.let { updated["phone"] = it }
        if (rawText.length > 10) {
            updated["last_snippet"] = rawText.take(280)
        }
        _capturedFields.value = updated
    }

    fun getField(key: String): String? = _capturedFields.value[key]
}

// ============================================================================
// 2. EVENT-DRIVEN ZERO-POLLING ACCESSIBILITY SERVICE (PHONE BODY)
// ============================================================================

class GokuAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastTimelineRecordMs = 0L
    private var lastAdSkipMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (GokuKillSwitch.isStopped.value) return

        // Zero-Polling Battery Rule: Immediately do 0% CPU work when screen is OFF
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) return

        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && pkg != packageName) {
            _lastPackageName.value = pkg
        }

        val vault = GokuVaultRepository.getInstance(applicationContext)

        // Teach-by-Example step capture on user clicks
        if (TeachModeRecorder.isRecording.value && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedLabel = event.text?.joinToString(" ")?.trim()
                ?.ifEmpty { event.contentDescription?.toString()?.trim().orEmpty() }
                .orEmpty()
            if (clickedLabel.isNotEmpty()) {
                TeachModeRecorder.recordStep("tap", clickedLabel, pkg)
            }
        }

        // Only inspect full UI tree on window state / meaningful content updates
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val root = rootInActiveWindow ?: return
            try {
                // 1. Auto-Ad & Popup Skipper (0.2s reaction with 1.2s cooldown)
                if (vault.settings.autoAdSkipEnabled &&
                    pkg != packageName &&
                    System.currentTimeMillis() - lastAdSkipMs > 1200L
                ) {
                    if (trySkipAdOrPopup(root)) {
                        lastAdSkipMs = System.currentTimeMillis()
                    }
                }

                // 2. SMS OTP Autopilot Auto-Fill
                if (vault.settings.otpAutopilotEnabled && OtpBus.latestOtp.value != null) {
                    tryAutoFillOtp(root)
                }

                // 3. Event-Driven Visual Memory + Mistake Blocker (throttled to every 4 seconds)
                val now = System.currentTimeMillis()
                if (now - lastTimelineRecordMs > 4000L && pkg.isNotEmpty() && pkg != packageName) {
                    lastTimelineRecordMs = now
                    val compactText = extractTreeText(root, maxNodes = 80)
                    if (compactText.length >= 12) {
                        _lastScreenText.value = compactText
                        ZeroCopyAppBridge.captureFromScreen(pkg, compactText)

                        if (vault.settings.mistakeBlockerEnabled) {
                            checkForRiskyScreen(compactText)
                        }

                        val title = event.className?.toString()?.substringAfterLast('.') ?: pkg
                        serviceScope.launch {
                            vault.recordScreenSnapshot(pkg, title, compactText)
                        }
                    }
                }
            } finally {
                @Suppress("DEPRECATION")
                runCatching { root.recycle() }
            }
        }
    }

    override fun onInterrupt() {
        // Gracefully pause without crashing
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        _isConnected.value = false
        super.onDestroy()
    }

    private fun trySkipAdOrPopup(root: AccessibilityNodeInfo): Boolean {
        val skipLabels = listOf("skip ad", "skip ads", "skip video", "no thanks", "not now", "dismiss")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 90) {
            visited++
            val node = queue.removeFirst()
            val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
                .lowercase(Locale.ROOT).trim()
            if (label.isNotEmpty() && skipLabels.any { label == it || label.startsWith("skip ad") }) {
                if (clickNodeOrParent(node)) return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun tryAutoFillOtp(root: AccessibilityNodeInfo): Boolean {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val candidate = focused ?: findFirstOtpEditText(root) ?: return false
        val hint = ((candidate.hintText?.toString() ?: "") + " " +
            (candidate.viewIdResourceName ?: "") + " " +
            (candidate.contentDescription?.toString() ?: "")).lowercase(Locale.ROOT)
        if (hint.contains("otp") || hint.contains("code") || hint.contains("verify") || hint.contains("pin")) {
            val otp = OtpBus.consumeFreshOtp() ?: return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, otp)
            }
            return candidate.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    private fun findFirstOtpEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 80) {
            visited++
            val node = queue.removeFirst()
            if (node.isEditable) {
                val meta = ((node.hintText?.toString() ?: "") + " " + (node.viewIdResourceName ?: ""))
                    .lowercase(Locale.ROOT)
                if (meta.contains("otp") || meta.contains("code") || meta.contains("verify")) {
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun checkForRiskyScreen(screenText: String) {
        val lower = screenText.lowercase(Locale.ROOT)
        val riskTrigger = when {
            lower.contains("factory reset") || lower.contains("erase all data") ->
                "Mistake Blocker: Factory Reset screen detected! Double-check before tapping confirm."
            lower.contains("delete permanently") || lower.contains("permanently delete") ->
                "Mistake Blocker: Permanent Delete confirmation detected!"
            lower.contains("http://") && (lower.contains("kyc") || lower.contains("claim prize") || lower.contains("account blocked")) ->
                "Mistake Blocker: Suspicious phishing/scam link detected on screen!"
            else -> null
        }
        if (riskTrigger != null) {
            MistakeBlockerBus.raiseWarning(riskTrigger)
        }
    }

    /**
     * Self-Healing Node Finder:
     * 1) Exact viewIdResourceName match
     * 2) Exact text / contentDescription match
     * 3) Case-insensitive contains match
     * 4) Clickable parent walk or spatial gesture tap at node center bounds
     */
    fun findAndTapNode(targetQuery: String): Boolean {
        if (GokuKillSwitch.isStopped.value) return false
        val root = rootInActiveWindow ?: return false
        val query = targetQuery.lowercase(Locale.ROOT).trim()
        val allNodes = collectNodes(root, maxNodes = 160)

        val matched = allNodes.firstOrNull {
            it.viewIdResourceName?.lowercase(Locale.ROOT)?.endsWith(query) == true
        } ?: allNodes.firstOrNull {
            it.text?.toString()?.lowercase(Locale.ROOT)?.trim() == query ||
                it.contentDescription?.toString()?.lowercase(Locale.ROOT)?.trim() == query
        } ?: allNodes.firstOrNull {
            it.text?.toString()?.lowercase(Locale.ROOT)?.contains(query) == true ||
                it.contentDescription?.toString()?.lowercase(Locale.ROOT)?.contains(query) == true
        }

        if (matched != null) {
            if (clickNodeOrParent(matched)) return true
            val bounds = Rect()
            matched.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                return performTap(bounds.exactCenterX(), bounds.exactCenterY())
            }
        }
        return false
    }

    fun inputText(text: String, fieldHint: String? = null): Boolean {
        if (GokuKillSwitch.isStopped.value) return false
        val root = rootInActiveWindow ?: return false
        val allNodes = collectNodes(root, maxNodes = 140)
        val target = if (!fieldHint.isNullOrBlank()) {
            val q = fieldHint.lowercase(Locale.ROOT)
            allNodes.firstOrNull { n ->
                n.isEditable && (
                    n.hintText?.toString()?.lowercase(Locale.ROOT)?.contains(q) == true ||
                        n.text?.toString()?.lowercase(Locale.ROOT)?.contains(q) == true ||
                        n.viewIdResourceName?.lowercase(Locale.ROOT)?.contains(q) == true
                    )
            }
        } else {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: allNodes.firstOrNull { it.isEditable }
        } ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun readScreenCompactText(): String {
        val root = rootInActiveWindow ?: return _lastScreenText.value
        val text = extractTreeText(root, maxNodes = 100)
        if (text.isNotBlank()) _lastScreenText.value = text
        return text
    }

    fun performTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 260L): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(80L, 1200L))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun collectNodes(root: AccessibilityNodeInfo, maxNodes: Int): List<AccessibilityNodeInfo> {
        val list = ArrayList<AccessibilityNodeInfo>(maxNodes)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && list.size < maxNodes) {
            val n = queue.removeFirst()
            list.add(n)
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return list
    }

    private fun extractTreeText(root: AccessibilityNodeInfo, maxNodes: Int): String {
        val sb = StringBuilder()
        val nodes = collectNodes(root, maxNodes)
        for (n in nodes) {
            val t = n.text?.toString()?.trim()
            val d = n.contentDescription?.toString()?.trim()
            val chosen = when {
                !t.isNullOrEmpty() -> t
                !d.isNullOrEmpty() -> d
                else -> null
            }
            if (chosen != null) {
                if (sb.isNotEmpty()) sb.append(" | ")
                sb.append(chosen)
                if (sb.length >= 1600) break
            }
        }
        return sb.toString()
    }

    companion object {
        @Volatile
        var instance: GokuAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _lastPackageName = MutableStateFlow("")
        val lastPackageName: StateFlow<String> = _lastPackageName.asStateFlow()

        private val _lastScreenText = MutableStateFlow("")
        val lastScreenText: StateFlow<String> = _lastScreenText.asStateFlow()
    }
}

// ============================================================================
// 3. UNIVERSAL APP PILOT (TRIPLE-ENGINE COORDINATOR + LOOP BREAKER)
// ============================================================================

class UniversalAppPilot(
    private val context: Context,
    private val adbEngine: LocalAdbEngine
) {
    private var consecutiveFailures = 0

    suspend fun tapTarget(target: String): Pair<Boolean, String> {
        if (GokuKillSwitch.isStopped.value) return false to "Stopped by GokuKillSwitch"
        val a11y = GokuAccessibilityService.instance
        if (a11y != null) {
            val ok = a11y.findAndTapNode(target)
            if (ok) {
                consecutiveFailures = 0
                return true to "Tapped '$target' via AccessibilityService"
            }
        }
        // Plan-B fallback: if coordinate pair "x,y" provided, use ADB tap or gesture
        val coords = target.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (coords.size == 2) {
            if (!adbEngine.useAccessibilityFallback.value) {
                val res = adbEngine.dispatchAdbTap(coords[0], coords[1])
                if (res.success) {
                    consecutiveFailures = 0
                    return true to "Tapped (${coords[0]}, ${coords[1]}) via Local ADB"
                }
            }
            if (a11y != null && a11y.performTap(coords[0].toFloat(), coords[1].toFloat())) {
                consecutiveFailures = 0
                return true to "Tapped (${coords[0]}, ${coords[1]}) via Spatial Gesture"
            }
        }
        consecutiveFailures++
        if (consecutiveFailures >= 3) {
            consecutiveFailures = 0
            return false to "Loop-Breaker triggered after 3 retries for '$target'; switched strategy."
        }
        return false to "Could not locate '$target' on active screen."
    }

    suspend fun typeText(text: String, fieldHint: String? = null): Pair<Boolean, String> {
        if (GokuKillSwitch.isStopped.value) return false to "Stopped by GokuKillSwitch"
        val a11y = GokuAccessibilityService.instance
        if (a11y != null && a11y.inputText(text, fieldHint)) {
            return true to "Typed text via AccessibilityService"
        }
        if (!adbEngine.useAccessibilityFallback.value) {
            val res = adbEngine.dispatchAdbText(text)
            if (res.success) return true to "Typed text via Local ADB"
        }
        return false to "Enable Goku Accessibility Service or Local ADB to type automatically."
    }

    suspend fun scrollDown(): Boolean {
        val a11y = GokuAccessibilityService.instance
        if (a11y != null) {
            return a11y.performSwipe(540f, 1650f, 540f, 550f, 280L)
        }
        if (!adbEngine.useAccessibilityFallback.value) {
            return adbEngine.dispatchAdbSwipe(540, 1650, 540, 550, 280).success
        }
        return false
    }

    suspend fun navigateHome(): Boolean {
        val a11y = GokuAccessibilityService.instance
        if (a11y != null) return a11y.performHome()
        if (!adbEngine.useAccessibilityFallback.value) {
            return adbEngine.execShellCommand("input keyevent 3").success
        }
        return false
    }

    suspend fun navigateBack(): Boolean {
        val a11y = GokuAccessibilityService.instance
        if (a11y != null) return a11y.performBack()
        if (!adbEngine.useAccessibilityFallback.value) {
            return adbEngine.execShellCommand("input keyevent 4").success
        }
        return false
    }

    suspend fun openNotificationsShade(): Boolean {
        val a11y = GokuAccessibilityService.instance
        if (a11y != null) return a11y.performNotifications()
        if (!adbEngine.useAccessibilityFallback.value) {
            return adbEngine.execShellCommand("cmd statusbar expand-notifications").success
        }
        return false
    }

    fun captureCurrentScreenSummary(): String {
        val a11y = GokuAccessibilityService.instance
        val text = a11y?.readScreenCompactText().orEmpty()
        return text.ifBlank { GokuAccessibilityService.lastScreenText.value }
    }

    suspend fun executeStepsJson(stepsJson: String, dryRun: Boolean = false): Pair<Boolean, String> {
        val arr = runCatching { JSONArray(stepsJson) }.getOrNull() ?: return false to "Invalid steps JSON"
        val log = StringBuilder()
        for (i in 0 until arr.length()) {
            if (GokuKillSwitch.isStopped.value) return false to "Aborted by GokuKillSwitch at step ${i + 1}"
            val obj = arr.optJSONObject(i) ?: continue
            val action = obj.optString("action").lowercase(Locale.ROOT)
            val target = obj.optString("target")
            if (dryRun) {
                log.append("Step ${i + 1} [DRY]: $action -> $target\n")
                continue
            }
            when (action) {
                "tap" -> {
                    val (ok, msg) = tapTarget(target)
                    log.append("Step ${i + 1}: $msg\n")
                    if (!ok) return false to log.toString().trim()
                }
                "type" -> {
                    val (ok, msg) = typeText(target, obj.optString("hint").ifBlank { null })
                    log.append("Step ${i + 1}: $msg\n")
                    if (!ok) return false to log.toString().trim()
                }
                "wait" -> delay(target.toLongOrNull()?.coerceIn(100L, 5000L) ?: 600L)
                "scroll" -> scrollDown()
                "home" -> navigateHome()
                "back" -> navigateBack()
                "adb_shell" -> {
                    val res = adbEngine.execShellCommand(target)
                    log.append("Step ${i + 1} [ADB]: ${res.output}\n")
                }
                "open_app" -> {
                    val launch = context.packageManager.getLaunchIntentForPackage(target)
                    if (launch != null) {
                        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launch)
                        log.append("Step ${i + 1}: Opened $target\n")
                    }
                }
            }
            delay(250L)
        }
        return true to log.toString().ifBlank { "Workflow executed cleanly." }
    }
}
