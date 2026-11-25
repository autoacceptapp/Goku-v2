package com.goku.ai.body

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Rect
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.goku.ai.adb.LocalAdbEngine
import com.goku.ai.data.GokuVaultRepository
import com.goku.ai.voice.GokuKillSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

// ============================================================================
// 1. LIVE OTP BUS, MISTAKE BLOCKER BUS, TEACH RECORDER & ZERO-COPY BRIDGE
// ============================================================================

private fun AccessibilityNodeInfo.getHintTextCompat(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        this.hintText?.toString().orEmpty()
    } else {
        ""
    }
}

private fun AccessibilityNodeInfo.recycleCompat() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        @Suppress("DEPRECATION")
        runCatching { this.recycle() }
    }
}

private fun recycleNodesCompat(nodes: Iterable<AccessibilityNodeInfo>, keep: AccessibilityNodeInfo? = null) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        for (n in nodes) {
            if (n !== keep) {
                @Suppress("DEPRECATION")
                runCatching { n.recycle() }
            }
        }
    }
}

internal fun resolveRealScreenDimensions(context: Context): Pair<Int, Int> {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    if (wm != null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = runCatching { wm.currentWindowMetrics.bounds }.getOrNull()
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        val ok = runCatching {
            wm.defaultDisplay?.getRealMetrics(dm)
            dm.widthPixels > 0 && dm.heightPixels > 0
        }.getOrDefault(false)
        if (ok) {
            return dm.widthPixels to dm.heightPixels
        }
    }
    val sys = Resources.getSystem().displayMetrics
    return sys.widthPixels.coerceAtLeast(720) to sys.heightPixels.coerceAtLeast(1280)
}

object OtpBus {
    private val lock = Any()
    private val _latestOtp = MutableStateFlow<String?>(null)
    val latestOtp: StateFlow<String?> = _latestOtp.asStateFlow()

    @Volatile
    private var otpTimestamp: Long = 0L

    fun publishOtp(code: String) = synchronized(lock) {
        val digits = code.filter { it.isDigit() }
        if (digits.length in 4..8) {
            otpTimestamp = System.currentTimeMillis()
            _latestOtp.value = digits
        }
    }

    fun peekFreshOtp(maxAgeMs: Long = 120_000L): String? = synchronized(lock) {
        val current = _latestOtp.value ?: return null
        if (System.currentTimeMillis() - otpTimestamp > maxAgeMs) {
            _latestOtp.value = null
            return null
        }
        return current
    }

    fun consumeFreshOtp(maxAgeMs: Long = 120_000L): String? = synchronized(lock) {
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
    private val lock = Any()
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordedSteps = MutableStateFlow<List<RecordedStep>>(emptyList())
    val recordedSteps: StateFlow<List<RecordedStep>> = _recordedSteps.asStateFlow()

    private var pendingName: String = "Custom Macro"
    private var pendingTrigger: String = "custom macro"

    fun startRecording(skillName: String, triggerPhrase: String) = synchronized(lock) {
        pendingName = skillName.ifBlank { "Taught Skill" }
        pendingTrigger = triggerPhrase.ifBlank { pendingName.lowercase(Locale.ROOT) }
        _recordedSteps.value = emptyList()
        _isRecording.value = true
    }

    fun recordStep(action: String, target: String, packageName: String) = synchronized(lock) {
        if (!_isRecording.value) return
        val cleanTarget = target.trim()
        if (cleanTarget.isEmpty()) return
        val current = _recordedSteps.value
        val last = current.lastOrNull()
        val now = System.currentTimeMillis()
        if (last != null && last.action == action && last.target == cleanTarget &&
            now - last.timestamp < 800L
        ) return
        _recordedSteps.value = current + RecordedStep(action, cleanTarget, packageName, now)
    }

    fun stopAndExport(): Triple<String, String, String> = synchronized(lock) {
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
// 1B. SAFE AD-SKIPPER GUARD + SPATIAL ICON NAVIGATOR + HEADLESS WEB WORKER
// ============================================================================

/**
 * Bug #5 Fix: Protects banking, UPI, payment, system settings, and security packages
 * from automatic ad/popup clicking so Goku NEVER auto-clicks "dismiss" or "not now"
 * inside financial transactions, permissions, or system dialogs.
 */
object SafeAdSkipGuard {
    private val PROTECTED_PACKAGES = setOf(
        "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp",
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.systemui"
    )

    private val PROTECTED_PACKAGE_TOKENS = listOf(
        "bank", "upi", "pay", "wallet", "bhim", "sbi", "hdfc", "icici", "axis",
        "kotak", "cred", "finance", "auth", "security", "knox", "packageinstaller",
        "permissioncontroller", "settings"
    )

    private val SENSITIVE_DIALOG_KEYWORDS = listOf(
        "otp", "upi pin", "enter pin", "mpin", "rupee", "₹", "payment",
        "transfer", "debit", "credit card", "cvv", "allow", "permission",
        "password", "biometric", "fingerprint", "factory reset", "uninstall"
    )

    fun isProtectedPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT).trim()
        if (pkg.isEmpty()) return true
        if (pkg in PROTECTED_PACKAGES) return true
        return PROTECTED_PACKAGE_TOKENS.any { pkg.contains(it) }
    }

    fun containsSensitiveDialogContext(screenText: String): Boolean {
        val low = screenText.lowercase(Locale.ROOT)
        return SENSITIVE_DIALOG_KEYWORDS.any { low.contains(it) }
    }
}

/**
 * Blueprint Feature — Spatial Icon Coordinate Heuristics:
 * Locates unlabeled clickable `ImageButton` / `ImageView` / icon-only nodes using semantic
 * `contentDescription` / `viewIdResourceName` tokens (`send`, `close`, `back`, `search`,
 * `menu`, `play`, `mic`, `share`, etc.) combined with canonical Android screen-region bounds.
 */
object SpatialIconNavigator {
    private val ICON_SYNONYMS: Map<String, List<String>> = mapOf(
        "send" to listOf("send", "submit", "post", "bhejo", "reply", "paper_plane", "chat_send"),
        "close" to listOf("close", "dismiss", "cancel", "clear", "cross", "band", "exit", "ic_close"),
        "back" to listOf("back", "navigate up", "up", "peeche", "return", "arrow_back"),
        "search" to listOf("search", "find", "khojo", "magnifier", "query", "ic_search"),
        "menu" to listOf("menu", "drawer", "navigation", "hamburger", "options", "more"),
        "play" to listOf("play", "resume", "start", "chala", "watch", "player_play"),
        "pause" to listOf("pause", "stop", "rok", "player_pause"),
        "mic" to listOf("mic", "voice", "record", "audio", "speak"),
        "camera" to listOf("camera", "photo", "capture", "shutter", "snap"),
        "share" to listOf("share", "forward", "bhejo"),
        "add" to listOf("add", "new", "compose", "create", "plus", "fab")
    )

    fun findUnlabeledIconNode(
        allNodes: List<AccessibilityNodeInfo>,
        targetQuery: String,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        val query = targetQuery.lowercase(Locale.ROOT).trim()
        if (query.isEmpty()) return null

        val canonicalKey = ICON_SYNONYMS.entries.firstOrNull { (k, aliases) ->
            query == k || aliases.any { query.contains(it) }
        }?.key
        val searchTokens = buildList {
            add(query)
            if (canonicalKey != null) {
                add(canonicalKey)
                addAll(ICON_SYNONYMS[canonicalKey].orEmpty())
            }
        }.distinct()

        val w = screenWidth.coerceAtLeast(720).toFloat()
        val h = screenHeight.coerceAtLeast(1280).toFloat()
        val rect = Rect()

        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0

        for (node in allNodes) {
            val cls = node.className?.toString().orEmpty()
            val isIconClass = cls.contains("ImageButton", ignoreCase = true) ||
                cls.contains("ImageView", ignoreCase = true) ||
                cls.contains("FloatingActionButton", ignoreCase = true) ||
                (node.isClickable && node.text.isNullOrBlank())
            if (!isIconClass) continue

            node.getBoundsInScreen(rect)
            if (rect.isEmpty || rect.width() <= 8 || rect.height() <= 8) continue
            // Skip full-screen background containers
            if (rect.width() > (w * 0.65f) && rect.height() > (h * 0.35f)) continue

            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val resId = node.viewIdResourceName?.lowercase(Locale.ROOT)?.substringAfterLast('/').orEmpty()

            var score = 0
            for (tok in searchTokens) {
                if (desc == tok || resId == tok) score += 100
                else if (desc.contains(tok)) score += 65
                else if (resId.contains(tok)) score += 55
            }

            // Spatial region heuristics for icon-only buttons lacking descriptive IDs
            val cx = rect.exactCenterX()
            val cy = rect.exactCenterY()
            val regionBonus = when (canonicalKey) {
                "back", "menu" -> if (cy < h * 0.16f && cx < w * 0.25f) 38 else 0
                "close" -> if (cy < h * 0.22f && cx > w * 0.72f) 38 else 0
                "search" -> if (cy < h * 0.18f && cx > w * 0.60f) 32 else 0
                "send", "mic", "add" -> if (cy > h * 0.68f && cx > w * 0.72f) 38 else 0
                "play", "pause" -> if (cx in (w * 0.22f)..(w * 0.78f) && cy in (h * 0.20f)..(h * 0.78f)) 28 else 0
                else -> 0
            }
            score += regionBonus
            if (node.isClickable) score += 10

            // Require at least a real token match OR strong canonical spatial match (>= 45)
            if (score >= 45 && score > bestScore) {
                bestScore = score
                bestNode = node
            }
        }
        return bestNode
    }
}

/**
 * Blueprint Feature — Headless Background WebView Automation Worker:
 * Runs an offscreen WebView on the main thread with strict security settings (HTTPS-only,
 * no file/content URI access) to extract clean webpage text or auto-fill saved Memory Vault
 * profile fields silently in the background.
 */
class HeadlessWebAutomationWorker(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchCleanPageText(url: String, maxChars: Int = 2400): Result<String> {
        val cleanUrl = normalizeHttpsUrl(url)
            ?: return Result.failure(IllegalArgumentException("Only HTTPS URLs are supported by HeadlessWebAutomationWorker."))

        return executeOnOffscreenWebView(cleanUrl) { webView, complete ->
            val js = """
                (function() {
                    try {
                        var title = document.title || '';
                        var body = (document.body && document.body.innerText) ? document.body.innerText : '';
                        return (title ? title + ' — ' : '') + body.replace(/\s+/g, ' ').trim();
                    } catch (e) {
                        return '';
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js) { rawJson ->
                val text = decodeJsString(rawJson).take(maxChars).trim()
                if (text.isNotBlank()) complete(Result.success(text))
                else complete(Result.failure(IllegalStateException("Page loaded but contained no readable text.")))
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun autoFillFormFromVault(url: String, vaultFields: Map<String, String>): Result<String> {
        val cleanUrl = normalizeHttpsUrl(url)
            ?: return Result.failure(IllegalArgumentException("Only HTTPS URLs are supported by HeadlessWebAutomationWorker."))
        if (vaultFields.isEmpty()) {
            return Result.failure(IllegalStateException("Memory Vault mein koi profile fields saved nahi hain."))
        }

        val fieldsJson = JSONObject().apply {
            vaultFields.entries.take(25).forEach { (k, v) -> put(k.lowercase(Locale.ROOT), v.take(160)) }
        }.toString()

        return executeOnOffscreenWebView(cleanUrl) { webView, complete ->
            val js = """
                (function() {
                    try {
                        var vault = $fieldsJson;
                        var inputs = document.querySelectorAll('input, textarea');
                        var filled = [];
                        for (var i = 0; i < inputs.length; i++) {
                            var el = inputs[i];
                            var type = (el.type || '').toLowerCase();
                            if (type === 'hidden' || type === 'password' || type === 'submit' || el.disabled || el.readOnly) continue;
                            var meta = ((el.name || '') + ' ' + (el.id || '') + ' ' + (el.placeholder || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.autocomplete || '')).toLowerCase();
                            for (var key in vault) {
                                if (!vault.hasOwnProperty(key)) continue;
                                if (meta.indexOf(key) !== -1 ||
                                    (key.indexOf('email') !== -1 && (type === 'email' || meta.indexOf('mail') !== -1)) ||
                                    (key.indexOf('phone') !== -1 && (type === 'tel' || meta.indexOf('mobile') !== -1 || meta.indexOf('phone') !== -1)) ||
                                    (key.indexOf('name') !== -1 && meta.indexOf('name') !== -1)) {
                                    el.value = vault[key];
                                    el.dispatchEvent(new Event('input', { bubbles: true }));
                                    el.dispatchEvent(new Event('change', { bubbles: true }));
                                    filled.push(key);
                                    break;
                                }
                            }
                        }
                        return JSON.stringify({ count: filled.length, fields: filled, title: document.title || '' });
                    } catch (e) {
                        return JSON.stringify({ count: 0, error: String(e) });
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js) { rawJson ->
                val decoded = decodeJsString(rawJson)
                val parsed = runCatching { JSONObject(decoded) }.getOrNull()
                val count = parsed?.optInt("count", 0) ?: 0
                val title = parsed?.optString("title", "").orEmpty()
                val fieldsArr = parsed?.optJSONArray("fields")
                val names = buildList {
                    if (fieldsArr != null) {
                        for (i in 0 until fieldsArr.length()) add(fieldsArr.optString(i))
                    }
                }.distinct().joinToString(", ")
                if (count > 0) {
                    complete(Result.success("Headless WebView ne '$title' par $count fields auto-fill kar diye ($names)."))
                } else {
                    complete(Result.success("Headless WebView ne '$title' load kiya, par koi matching editable form field nahi mila."))
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun executeOnOffscreenWebView(
        httpsUrl: String,
        onLoaded: (WebView, (Result<String>) -> Unit) -> Unit
    ): Result<String> {
        return withTimeoutOrNull(16_000L) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Result<String>> { cont ->
                    val webView = runCatching { WebView(context.applicationContext) }.getOrNull()
                    if (webView == null) {
                        if (cont.isActive) cont.resume(Result.failure(IllegalStateException("WebView unavailable on this device.")))
                        return@suspendCancellableCoroutine
                    }

                    val mainHandler = Handler(Looper.getMainLooper())
                    var pendingPostLoadRunnable: Runnable? = null
                    var finished = false
                    var destroyed = false
                    fun destroyWebViewSafely() {
                        if (destroyed) return
                        destroyed = true
                        runCatching {
                            webView.stopLoading()
                            webView.loadUrl("about:blank")
                            webView.destroy()
                        }
                    }
                    fun finishWith(res: Result<String>) {
                        if (finished) return
                        finished = true
                        pendingPostLoadRunnable?.let { mainHandler.removeCallbacks(it) }
                        pendingPostLoadRunnable = null
                        destroyWebViewSafely()
                        if (cont.isActive) cont.resume(res)
                    }

                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                            if (finished || loadedUrl == "about:blank") return
                            pendingPostLoadRunnable?.let { mainHandler.removeCallbacks(it) }
                            val r = Runnable {
                                if (!finished && view != null) {
                                    onLoaded(view) { res -> finishWith(res) }
                                }
                            }
                            pendingPostLoadRunnable = r
                            mainHandler.postDelayed(r, 550L)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val redirectUrl = request?.url?.toString().orEmpty()
                            if (!isAllowedPublicHttpsSyntax(redirectUrl)) {
                                if (request?.isForMainFrame == true) {
                                    finishWith(Result.failure(IllegalArgumentException("Blocked non-HTTPS or private/local main-frame redirect")))
                                }
                                return true
                            }
                            return false
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            handler?.cancel()
                            finishWith(
                                Result.failure(
                                    IllegalStateException("SSL error: ${error?.primaryError ?: "untrusted"}")
                                )
                            )
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                finishWith(Result.failure(IllegalStateException("WebView load failed: ${error?.description ?: "network error"}")))
                            }
                        }
                    }

                    cont.invokeOnCancellation {
                        mainHandler.post {
                            finished = true
                            pendingPostLoadRunnable?.let { mainHandler.removeCallbacks(it) }
                            pendingPostLoadRunnable = null
                            destroyWebViewSafely()
                        }
                    }

                    runCatching { webView.loadUrl(httpsUrl) }.onFailure { err ->
                        finishWith(Result.failure(err))
                    }
                }
            }
        } ?: Result.failure(IllegalStateException("Headless WebView timed out after 16 seconds."))
    }

    private suspend fun normalizeHttpsUrl(raw: String): String? {
        val trimmed = raw.trim()
        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.removePrefix("http://").removePrefix("HTTP://")
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> return null
        }
        if (!isAllowedPublicHttpsSyntax(withScheme)) return null
        val host = runCatching { URI(withScheme).host }.getOrNull() ?: return null
        val resolvesPublic = withContext(Dispatchers.IO) {
            val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return@withContext false
            addresses.isNotEmpty() && addresses.all { addr ->
                val rawBytes = addr.address
                val isUniqueLocalIpv6 = rawBytes.size == 16 && (rawBytes[0].toInt() and 0xFE) == 0xFC
                !addr.isAnyLocalAddress &&
                    !addr.isLoopbackAddress &&
                    !addr.isLinkLocalAddress &&
                    !addr.isSiteLocalAddress &&
                    !addr.isMulticastAddress &&
                    !isUniqueLocalIpv6
            }
        }
        return withScheme.takeIf { resolvesPublic }
    }

    private fun isAllowedPublicHttpsSyntax(urlStr: String): Boolean {
        val uri = runCatching { URI(urlStr.trim()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trim()?.trimEnd('.')?.removePrefix("[")?.removeSuffix("]")
            ?: return false
        if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
            return false
        }
        if (host == "::1" || host == "0.0.0.0" ||
            (host.contains(":") && (host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")))
        ) {
            return false
        }
        // Reject integer/hex single-label IP forms and private IPv4 literals without blocking Main thread on DNS
        if (host.all { it.isDigit() } || host.startsWith("0x")) return false
        val parts = host.split(".")
        if (parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            if (a == 0 || a == 10 || a == 127) return false
            if (a == 169 && b == 254) return false
            if (a == 172 && b in 16..31) return false
            if (a == 192 && b == 168) return false
        }
        return true
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            JSONObject("{\"v\":$raw}").optString("v", "")
        }.getOrElse {
            raw.trim('"').replace("\\n", " ").replace("\\\"", "\"")
        }
    }
}

// ============================================================================
// 2. EVENT-DRIVEN ZERO-POLLING ACCESSIBILITY SERVICE (PHONE BODY)
// ============================================================================

class GokuAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastTimelineRecordMs = 0L
    private var lastAdSkipMs = 0L
    private val otpWindowThrottle = ConcurrentHashMap<String, Long>()

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
                // 1. Safe Auto-Ad & Popup Skipper (0.2s reaction with 1.2s cooldown; guarded against financial/system apps)
                val nowForSkip = System.currentTimeMillis()
                if (vault.settings.autoAdSkipEnabled &&
                    pkg != packageName &&
                    !SafeAdSkipGuard.isProtectedPackage(pkg) &&
                    nowForSkip - lastAdSkipMs > 1200L
                ) {
                    lastAdSkipMs = nowForSkip
                    trySkipAdOrPopup(root, pkg)
                }

                // 2. SMS OTP Autopilot Auto-Fill
                if (vault.settings.otpAutopilotEnabled && OtpBus.peekFreshOtp() != null) {
                    tryAutoFillOtp(root, pkg)
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
                root.recycleCompat()
            }
        }
    }

    override fun onInterrupt() {
        // Gracefully pause without crashing
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        _isConnected.value = false
        otpWindowThrottle.clear()
        lastTimelineRecordMs = 0L
        lastAdSkipMs = 0L
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        _isConnected.value = false
        otpWindowThrottle.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun trySkipAdOrPopup(root: AccessibilityNodeInfo, pkg: String): Boolean {
        if (SafeAdSkipGuard.isProtectedPackage(pkg)) return false

        val softPopupLabels = setOf("no thanks", "not now", "dismiss")
        val screenContext = extractTreeText(root, maxNodes = 40)
        val isSensitiveContext = SafeAdSkipGuard.containsSensitiveDialogContext(screenContext)

        val obtainedChildren = ArrayList<AccessibilityNodeInfo>(90)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < 90) {
                visited++
                val node = queue.removeFirst()
                val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
                    .lowercase(Locale.ROOT).trim()
                if (label.isNotEmpty()) {
                    val isExplicitAd = EXPLICIT_AD_SKIP_REGEX.matches(label)
                    val isSafePopup = !isSensitiveContext && label in softPopupLabels
                    if (isExplicitAd || isSafePopup) {
                        if (clickNodeOrParent(node)) return true
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        obtainedChildren.add(child)
                        queue.add(child)
                    }
                }
            }
            return false
        } finally {
            recycleNodesCompat(obtainedChildren, keep = root)
        }
    }

    private fun isExcludedNonOtpField(meta: String): Boolean {
        return EXCLUDED_OTP_FIELD_REGEX.containsMatchIn(meta)
    }

    private fun isOtpMetadataMatch(meta: String): Boolean {
        if (isExcludedNonOtpField(meta)) return false
        return meta.contains("otp") ||
            meta.contains("one-time") ||
            meta.contains("one time") ||
            meta.contains("verification code") ||
            meta.contains("security code") ||
            meta.contains("auth code") ||
            meta.contains("passcode") ||
            meta.contains("code") ||
            meta.contains("verify")
    }

    private fun tryAutoFillOtp(root: AccessibilityNodeInfo, pkg: String): Boolean {
        if (pkg == packageName || SafeAdSkipGuard.isProtectedPackage(pkg)) return false
        val windowSig = "$pkg:${root.windowId}"
        val nowMs = System.currentTimeMillis()
        val prevMs = otpWindowThrottle[windowSig] ?: 0L
        if (nowMs - prevMs < OTP_DEBOUNCE_MS) return false
        if (otpWindowThrottle.size > 64) {
            otpWindowThrottle.clear()
        }
        otpWindowThrottle[windowSig] = nowMs

        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        var foundFromTree: AccessibilityNodeInfo? = null
        try {
            val candidate = if (focused != null && focused.isEditable) {
                focused
            } else {
                foundFromTree = findFirstOtpEditText(root)
                foundFromTree
            } ?: return false

            if (!candidate.isEditable) return false
            val hint = (candidate.getHintTextCompat() + " " +
                (candidate.viewIdResourceName ?: "") + " " +
                (candidate.contentDescription?.toString() ?: "") + " " +
                (candidate.text?.toString() ?: "")).lowercase(Locale.ROOT)
            if (isExcludedNonOtpField(hint)) return false
            val explicitlyOtpTagged = hint.contains("otp") ||
                hint.contains("verification") ||
                hint.contains("verify") ||
                hint.contains("one-time") ||
                hint.contains("one time") ||
                hint.contains("passcode") ||
                (!candidate.isPassword && hint.contains("code"))
            if (explicitlyOtpTagged) {
                val otp = OtpBus.peekFreshOtp() ?: return false
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, otp)
                }
                val applied = candidate.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (applied) {
                    OtpBus.consumeFreshOtp()
                }
                return applied
            }
            return false
        } finally {
            if (focused != null && focused !== root) focused.recycleCompat()
            if (foundFromTree != null && foundFromTree !== root && foundFromTree !== focused) {
                foundFromTree.recycleCompat()
            }
        }
    }

    private fun findFirstOtpEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val obtainedChildren = ArrayList<AccessibilityNodeInfo>(80)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var editableInspected = 0
        var matched: AccessibilityNodeInfo? = null
        try {
            while (queue.isNotEmpty() && visited < 120 && editableInspected < MAX_OTP_EDITABLE_NODES) {
                visited++
                val node = queue.removeFirst()
                if (node.isEditable) {
                    editableInspected++
                    val meta = (node.getHintTextCompat() + " " +
                        (node.viewIdResourceName ?: "") + " " +
                        (node.contentDescription?.toString() ?: "") + " " +
                        (node.text?.toString() ?: ""))
                        .lowercase(Locale.ROOT)
                    if (!isExcludedNonOtpField(meta)) {
                        val isMatch = if (node.isPassword) {
                            meta.contains("otp") || meta.contains("verification") || meta.contains("verify") || meta.contains("pin_otp")
                        } else {
                            isOtpMetadataMatch(meta)
                        }
                        if (isMatch) {
                            matched = node
                            return node
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        obtainedChildren.add(child)
                        queue.add(child)
                    }
                }
            }
            return null
        } finally {
            for (child in obtainedChildren) {
                if (child !== matched && child !== root) {
                    child.recycleCompat()
                }
            }
        }
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

    private fun selectBestMatchingNode(
        allNodes: List<AccessibilityNodeInfo>,
        targetQuery: String,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        val query = targetQuery.lowercase(Locale.ROOT).trim()
        if (query.isEmpty()) return null
        val wordBoundaryRegex = Regex("\\b${Regex.escape(query)}\\b", RegexOption.IGNORE_CASE)

        return allNodes.firstOrNull { node ->
            val resId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: return@firstOrNull false
            resId == query || resId.substringAfterLast('/') == query
        } ?: allNodes.firstOrNull { node ->
            node.text?.toString()?.lowercase(Locale.ROOT)?.trim() == query ||
                node.contentDescription?.toString()?.lowercase(Locale.ROOT)?.trim() == query
        } ?: allNodes.firstOrNull { node ->
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            wordBoundaryRegex.containsMatchIn(text) || wordBoundaryRegex.containsMatchIn(desc)
        } ?: if (query.length >= 4) {
            allNodes.firstOrNull { node ->
                node.text?.toString()?.lowercase(Locale.ROOT)?.contains(query) == true ||
                    node.contentDescription?.toString()?.lowercase(Locale.ROOT)?.contains(query) == true
            }
        } else {
            null
        } ?: SpatialIconNavigator.findUnlabeledIconNode(
            allNodes = allNodes,
            targetQuery = targetQuery,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
    }

    /**
     * Resolves screen center coordinates for a UI node matching [targetQuery] so Local ADB
     * can dispatch hardware-grade `input tap` before falling back to Accessibility click/gesture.
     */
    fun findNodeCenterCoordinates(targetQuery: String): Pair<Int, Int>? {
        if (GokuKillSwitch.isStopped.value) return null
        val root = rootInActiveWindow ?: return null
        val allNodes = collectNodes(root, maxNodes = 160)
        try {
            val (screenW, screenH) = resolveRealScreenDimensions(this)
            val matched = selectBestMatchingNode(allNodes, targetQuery, screenW, screenH) ?: return null
            val bounds = Rect()
            matched.getBoundsInScreen(bounds)
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val maxW = screenW.coerceAtLeast(1).toFloat()
            val maxH = screenH.coerceAtLeast(1).toFloat()
            return if (!bounds.isEmpty && cx in 0f..maxW && cy in 0f..maxH) {
                cx.toInt() to cy.toInt()
            } else {
                null
            }
        } finally {
            recycleNodesCompat(allNodes)
        }
    }

    /**
     * Self-Healing Node Finder:
     * 1) Exact viewIdResourceName match
     * 2) Exact text / contentDescription match
     * 3) Word-boundary match (prevents "ok" from matching "book")
     * 4) Case-insensitive contains match (queries >= 4 chars)
     * 5) Clickable parent walk or spatial gesture tap at node center bounds
     */
    fun findAndTapNode(targetQuery: String): Boolean {
        if (GokuKillSwitch.isStopped.value) return false
        val root = rootInActiveWindow ?: return false
        val allNodes = collectNodes(root, maxNodes = 160)
        try {
            val (screenW, screenH) = resolveRealScreenDimensions(this)
            val matched = selectBestMatchingNode(allNodes, targetQuery, screenW, screenH)

            if (matched != null) {
                if (clickNodeOrParent(matched)) return true
                val bounds = Rect()
                matched.getBoundsInScreen(bounds)
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()
                val maxW = screenW.coerceAtLeast(1).toFloat()
                val maxH = screenH.coerceAtLeast(1).toFloat()
                if (!bounds.isEmpty && cx in 0f..maxW && cy in 0f..maxH) {
                    return performTap(cx, cy)
                }
            }
            return false
        } finally {
            recycleNodesCompat(allNodes)
        }
    }

    fun inputText(text: String, fieldHint: String? = null): Boolean {
        if (GokuKillSwitch.isStopped.value) return false
        val root = rootInActiveWindow ?: return false
        val allNodes = collectNodes(root, maxNodes = 140)
        var focusedNode: AccessibilityNodeInfo? = null
        try {
            val target = if (!fieldHint.isNullOrBlank()) {
                val q = fieldHint.lowercase(Locale.ROOT)
                allNodes.firstOrNull { n ->
                    n.isEditable && (
                        n.getHintTextCompat().lowercase(Locale.ROOT).contains(q) ||
                            n.text?.toString()?.lowercase(Locale.ROOT)?.contains(q) == true ||
                            n.viewIdResourceName?.lowercase(Locale.ROOT)?.contains(q) == true
                        )
                }
            } else {
                focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                focusedNode ?: allNodes.firstOrNull { it.isEditable }
            } ?: return false

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            if (focusedNode != null && !allNodes.any { it === focusedNode }) {
                focusedNode.recycleCompat()
            }
            recycleNodesCompat(allNodes)
        }
    }

    fun readScreenCompactText(): String {
        val root = rootInActiveWindow ?: return _lastScreenText.value
        return try {
            val text = extractTreeText(root, maxNodes = 100)
            if (text.isNotBlank()) _lastScreenText.value = text
            text
        } finally {
            root.recycleCompat()
        }
    }

    fun performTap(x: Float, y: Float): Boolean {
        val (screenW, screenH) = resolveRealScreenDimensions(this)
        val maxW = screenW.coerceAtLeast(1).toFloat()
        val maxH = screenH.coerceAtLeast(1).toFloat()
        if (!x.isFinite() || !y.isFinite() || x < 0f || y < 0f || x > maxW || y > maxH) {
            return false
        }
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 260L): Boolean {
        val (screenW, screenH) = resolveRealScreenDimensions(this)
        val maxW = screenW.coerceAtLeast(1).toFloat()
        val maxH = screenH.coerceAtLeast(1).toFloat()
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite() ||
            x1 < 0f || y1 < 0f || x2 < 0f || y2 < 0f ||
            x1 > maxW || y1 > maxH || x2 > maxW || y2 > maxH
        ) {
            return false
        }
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
        val obtainedParents = ArrayList<AccessibilityNodeInfo>(5)
        var depth = 0
        try {
            while (current != null && depth < 5) {
                if (current.isClickable) {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
                val p = current.parent
                if (p != null) obtainedParents.add(p)
                current = p
                depth++
            }
            return false
        } finally {
            recycleNodesCompat(obtainedParents, keep = node)
        }
    }

    private fun collectNodes(root: AccessibilityNodeInfo, maxNodes: Int): List<AccessibilityNodeInfo> {
        val list = ArrayList<AccessibilityNodeInfo>(maxNodes)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && list.size < maxNodes) {
            val n = queue.removeFirst()
            list.add(n)
            for (i in 0 until n.childCount) {
                if (list.size + queue.size >= maxNodes) break
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        while (queue.isNotEmpty()) {
            val leftover = queue.removeFirst()
            if (leftover !== root) leftover.recycleCompat()
        }
        return list
    }

    private fun extractTreeText(root: AccessibilityNodeInfo, maxNodes: Int): String {
        val sb = StringBuilder()
        val nodes = collectNodes(root, maxNodes)
        try {
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
        } finally {
            recycleNodesCompat(nodes, keep = root)
        }
    }

    companion object {
        private const val OTP_DEBOUNCE_MS = 350L
        private const val MAX_OTP_EDITABLE_NODES = 40
        private val EXCLUDED_OTP_FIELD_REGEX = Regex(
            "(?:promo|coupon|referral|postal|zip|pin_?code|ifsc|invite|voucher)",
            RegexOption.IGNORE_CASE
        )
        private val EXPLICIT_AD_SKIP_REGEX = Regex(
            "^skip\\s+(?:ads?|video|intro|promo|promotion|trial)(?:\\s*[»>►•|\\-]?\\s*(?:in\\s*\\d+\\s*s?)?)?$"
        )

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
    private val consecutiveFailuresByKey = ConcurrentHashMap<String, AtomicInteger>()
    val headlessWebWorker: HeadlessWebAutomationWorker by lazy {
        HeadlessWebAutomationWorker(context.applicationContext)
    }

    suspend fun runHeadlessWebRead(url: String): Pair<Boolean, String> {
        val res = headlessWebWorker.fetchCleanPageText(url)
        return res.fold(
            onSuccess = { true to it },
            onFailure = { false to "Headless Web read failed: ${it.message}" }
        )
    }

    suspend fun runHeadlessWebAutoFill(url: String, vault: GokuVaultRepository): Pair<Boolean, String> {
        val fields = vault.getProfileFieldsMap()
        val res = headlessWebWorker.autoFillFormFromVault(url, fields)
        return res.fold(
            onSuccess = { true to it },
            onFailure = { false to "Headless Web auto-fill failed: ${it.message}" }
        )
    }

    private suspend fun attemptTapOnce(target: String): Pair<Boolean, String>? {
        if (GokuKillSwitch.isStopped.value) return false to "Stopped by GokuKillSwitch"
        val a11y = GokuAccessibilityService.instance
        val adbConnected = adbEngine.status.value.isConnected && !adbEngine.useAccessibilityFallback.value
        val coords = target.split(",").mapNotNull { it.trim().toIntOrNull() }

        // 1. Coordinate pair "x,y": try Local ADB first whenever connected, then Accessibility gesture
        if (coords.size == 2) {
            if (adbConnected) {
                val res = adbEngine.dispatchAdbTap(coords[0], coords[1])
                if (res.success) {
                    return true to "Tapped (${coords[0]}, ${coords[1]}) via Local ADB"
                }
            }
            if (a11y != null && a11y.performTap(coords[0].toFloat(), coords[1].toFloat())) {
                return true to "Tapped (${coords[0]}, ${coords[1]}) via Spatial Gesture"
            }
            return null
        }

        // 2. Named target: if Local ADB is connected and node bounds are known, try ADB tap first
        if (adbConnected && a11y != null) {
            val nodeCoords = a11y.findNodeCenterCoordinates(target)
            if (nodeCoords != null) {
                val res = adbEngine.dispatchAdbTap(nodeCoords.first, nodeCoords.second)
                if (res.success) {
                    return true to "Tapped '$target' (${nodeCoords.first}, ${nodeCoords.second}) via Local ADB"
                }
            }
        }

        // 3. Fallback to Accessibility click / spatial gesture
        if (a11y != null && a11y.findAndTapNode(target)) {
            return true to "Tapped '$target' via AccessibilityService"
        }
        return null
    }

    suspend fun tapTarget(target: String): Pair<Boolean, String> {
        if (GokuKillSwitch.isStopped.value) return false to "Stopped by GokuKillSwitch"
        val activePkg = GokuAccessibilityService.lastPackageName.value.ifBlank { "unknown" }
        val failKey = "$activePkg|${target.lowercase(Locale.ROOT).trim()}"
        if (consecutiveFailuresByKey.size > 100) {
            consecutiveFailuresByKey.clear()
        }

        val firstAttempt = attemptTapOnce(target)
        if (firstAttempt != null) {
            if (firstAttempt.first) {
                consecutiveFailuresByKey.remove(failKey)
            }
            return firstAttempt
        }

        val failCounter = consecutiveFailuresByKey.computeIfAbsent(failKey) { AtomicInteger(0) }
        if (failCounter.incrementAndGet() >= 3) {
            failCounter.set(0)
            scrollDown()
            delay(350L)
            if (GokuKillSwitch.isStopped.value) return false to "Stopped by GokuKillSwitch"
            val retryAfterScroll = attemptTapOnce(target)
            if (retryAfterScroll != null && retryAfterScroll.first) {
                consecutiveFailuresByKey.remove(failKey)
                return true to "${retryAfterScroll.second} (recovered after Loop-Breaker scroll)"
            }
            return false to "Loop-Breaker triggered after 3 retries for '$target'; scrolled and retried without finding target."
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
        val (screenW, screenH) = resolveRealScreenDimensions(context)
        val w = screenW.coerceAtLeast(720).toFloat()
        val h = screenH.coerceAtLeast(1280).toFloat()
        val cx = w * 0.5f
        val y1 = h * 0.72f
        val y2 = h * 0.25f
        val a11y = GokuAccessibilityService.instance
        if (a11y != null) {
            return a11y.performSwipe(cx, y1, cx, y2, 280L)
        }
        if (!adbEngine.useAccessibilityFallback.value) {
            return adbEngine.dispatchAdbSwipe(cx.toInt(), y1.toInt(), cx.toInt(), y2.toInt(), 280).success
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
                    if (!res.success) return false to log.toString().trim()
                }
                "open_app" -> {
                    var resolvedLabel = target
                    var launch = context.packageManager.getLaunchIntentForPackage(target)
                    if (launch == null) {
                        val low = target.lowercase(Locale.ROOT).trim()
                        if (low.isNotEmpty()) {
                            val apps = adbEngine.discoverInstalledApps()
                            val match = apps.firstOrNull { it.first.lowercase(Locale.ROOT).trim() == low }
                                ?: apps.firstOrNull {
                                    it.first.lowercase(Locale.ROOT).contains(low) ||
                                        it.second.lowercase(Locale.ROOT).contains(low)
                                }
                            if (match != null) {
                                resolvedLabel = "${match.first} (${match.second})"
                                launch = context.packageManager.getLaunchIntentForPackage(match.second)
                            }
                        }
                    }
                    if (launch != null) {
                        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launch)
                        log.append("Step ${i + 1}: Opened $resolvedLabel\n")
                    } else {
                        log.append("Step ${i + 1}: App '$target' not found\n")
                        return false to log.toString().trim()
                    }
                }
            }
            delay(250L)
        }
        return true to log.toString().ifBlank { "Workflow executed cleanly." }
    }
}
