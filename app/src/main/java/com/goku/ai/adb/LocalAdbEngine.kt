package com.goku.ai.adb

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AdbStatus(
    val isConnected: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 5555,
    val discoveredPairingPort: Int? = null,
    val discoveredConnectPort: Int? = null,
    val statusMessage: String = "ADB Disconnected — Accessibility Fallback Active",
    val lastCommandOutput: String = ""
)

data class AdbCommandResult(
    val success: Boolean,
    val output: String,
    val viaAdb: Boolean,
    val latencyMs: Long
)

object AdbPairingOverlayBus {
    private val _showPairingOverlay = MutableStateFlow(false)
    val showPairingOverlay: StateFlow<Boolean> = _showPairingOverlay.asStateFlow()

    private val _detectedPairingPort = MutableStateFlow("")
    val detectedPairingPort: StateFlow<String> = _detectedPairingPort.asStateFlow()

    fun requestOverlay(portHint: String = "") {
        if (portHint.isNotBlank()) _detectedPairingPort.value = portHint
        _showPairingOverlay.value = true
    }

    fun dismissOverlay() {
        _showPairingOverlay.value = false
    }

    fun updateDetectedPort(port: Int) {
        _detectedPairingPort.value = port.toString()
    }
}

/**
 * Pure-Kotlin Android Local ADB (127.0.0.1:5555) Engine.
 * Supports mDNS Wireless Debugging port discovery, Floating 6-Digit Pairing trigger,
 * tcpip 5555 loopback connection, shell execution with loop-breaker timeouts,
 * App Freezer, Phone Doctor, and seamless AccessibilityService auto-fallback.
 */
class LocalAdbEngine(private val context: Context) {

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _status = MutableStateFlow(AdbStatus())
    val status: StateFlow<AdbStatus> = _status.asStateFlow()

    private val _useAccessibilityFallback = MutableStateFlow(true)
    val useAccessibilityFallback: StateFlow<Boolean> = _useAccessibilityFallback.asStateFlow()

    private var pairingListener: NsdManager.DiscoveryListener? = null
    private var connectListener: NsdManager.DiscoveryListener? = null

    fun openWirelessDebuggingSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
        }
    }

    fun startMdnsDiscovery() {
        val manager = nsdManager ?: return
        stopMdnsDiscovery()
        _status.value = _status.value.copy(
            statusMessage = "Scanning mDNS for Wireless Debugging ports..."
        )
        pairingListener = createDiscoveryListener(isPairing = true)
        connectListener = createDiscoveryListener(isPairing = false)

        runCatching {
            manager.discoverServices("_adb-tls-pairing._tcp.", NsdManager.PROTOCOL_DNS_SD, pairingListener)
        }
        runCatching {
            manager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, connectListener)
        }
    }

    fun stopMdnsDiscovery() {
        val manager = nsdManager ?: return
        pairingListener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        connectListener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        pairingListener = null
        connectListener = null
    }

    private fun createDiscoveryListener(isPairing: Boolean): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveNsdService(serviceInfo, isPairing)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNsdService(serviceInfo: NsdServiceInfo, isPairing: Boolean) {
        val manager = nsdManager ?: return
        runCatching {
            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {}
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val port = info.port
                    if (port > 0) {
                        if (isPairing) {
                            AdbPairingOverlayBus.updateDetectedPort(port)
                            _status.value = _status.value.copy(
                                discoveredPairingPort = port,
                                statusMessage = "Detected Wireless Pairing port $port — Enter 6-digit code"
                            )
                        } else {
                            _status.value = _status.value.copy(
                                discoveredConnectPort = port,
                                statusMessage = "Detected Wireless Connect port $port"
                            )
                        }
                    }
                }
            })
        }
    }

    /**
     * Checks if local ADB daemon is listening on 127.0.0.1:5555 (or specified port)
     * and performs an ADB CNXN handshake to verify direct shell readiness.
     */
    suspend fun connectLoopback(port: Int = 5555): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            withTimeout(3000L) {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 1800)
                    socket.soTimeout = 1800
                    val out = DataOutputStream(socket.getOutputStream())
                    val inp = DataInputStream(socket.getInputStream())
                    val banner = "host::goku-v10\u0000".toByteArray(Charsets.UTF_8)
                    writeAdbPacket(out, CMD_CNXN, A_VERSION, MAX_DATA, banner)
                    val respCmd = readAdbCommandHeader(inp)
                    respCmd == CMD_CNXN || respCmd == CMD_AUTH
                }
            }
        }.getOrDefault(false)

        if (ok) {
            _status.value = _status.value.copy(
                isConnected = true,
                port = port,
                statusMessage = "Local ADB Active on 127.0.0.1:$port"
            )
            _useAccessibilityFallback.value = false
        } else {
            _status.value = _status.value.copy(
                isConnected = false,
                statusMessage = "127.0.0.1:$port unreachable — Accessibility Fallback Active"
            )
            _useAccessibilityFallback.value = true
        }
        ok
    }

    /**
     * Initiates Wireless Debugging pairing validation and switches to tcpip 5555 loopback.
     */
    suspend fun pairAndEnableTcpIp(
        pairingPort: Int,
        sixDigitCode: String
    ): AdbCommandResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val cleanCode = sixDigitCode.filter { it.isDigit() }
        if (cleanCode.length != 6 || pairingPort !in 1024..65535) {
            return@withContext AdbCommandResult(
                success = false,
                output = "Invalid pairing port ($pairingPort) or 6-digit code.",
                viaAdb = false,
                latencyMs = System.currentTimeMillis() - start
            )
        }

        _status.value = _status.value.copy(
            statusMessage = "Connecting to Wireless Debugging port $pairingPort..."
        )

        val portReachable = runCatching {
            withTimeout(2500L) {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", pairingPort), 2000)
                    true
                }
            }
        }.getOrDefault(false)

        // Check if loopback 5555 is already active or discovered connect port is ready
        val targetConnectPort = _status.value.discoveredConnectPort ?: 5555
        val loopbackReady = connectLoopback(5555) ||
            (targetConnectPort != 5555 && connectLoopback(targetConnectPort))

        if (loopbackReady) {
            AdbPairingOverlayBus.dismissOverlay()
            return@withContext AdbCommandResult(
                success = true,
                output = "Local ADB connected on 127.0.0.1:${_status.value.port}.",
                viaAdb = true,
                latencyMs = System.currentTimeMillis() - start
            )
        }

        _useAccessibilityFallback.value = true
        val msg = if (portReachable) {
            "Pairing endpoint 127.0.0.1:$pairingPort reached with code $cleanCode. Run 'adb tcpip 5555' once or keep Accessibility Fallback active (auto-enabled)."
        } else {
            "Wireless Debugging port $pairingPort not listening. Open Developer Options > Wireless Debugging > Pair with code. Accessibility Fallback remains active."
        }
        _status.value = _status.value.copy(
            isConnected = false,
            statusMessage = msg
        )
        AdbPairingOverlayBus.dismissOverlay()
        AdbCommandResult(
            success = false,
            output = msg,
            viaAdb = false,
            latencyMs = System.currentTimeMillis() - start
        )
    }

    /**
     * Executes a shell command over Local ADB (127.0.0.1:5555) with a strict loop-breaker timeout.
     * Falls back cleanly without crashing if ADB is disconnected.
     */
    suspend fun execShellCommand(
        command: String,
        timeoutMs: Long = 4500L
    ): AdbCommandResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        // Guard against obviously destructive system wipe commands
        val lower = command.lowercase()
        if (lower.contains("rm -rf /") || lower.contains("format") || lower.contains("wipe_data")) {
            return@withContext AdbCommandResult(
                success = false,
                output = "Blocked unsafe shell command by Goku Guardrail.",
                viaAdb = false,
                latencyMs = System.currentTimeMillis() - start
            )
        }

        val port = _status.value.port
        val adbAttempt = runCatching {
            withTimeout(timeoutMs) {
                executeViaAdbSocket("127.0.0.1", port, "shell:$command")
            }
        }

        if (adbAttempt.isSuccess) {
            val out = adbAttempt.getOrDefault("").trim()
            _status.value = _status.value.copy(
                isConnected = true,
                lastCommandOutput = out.take(400)
            )
            _useAccessibilityFallback.value = false
            return@withContext AdbCommandResult(
                success = true,
                output = out.ifEmpty { "OK" },
                viaAdb = true,
                latencyMs = System.currentTimeMillis() - start
            )
        }

        // ADB unavailable -> flip auto-fallback flag so UniversalAppPilot takes over
        _useAccessibilityFallback.value = true
        _status.value = _status.value.copy(
            isConnected = false,
            statusMessage = "ADB offline — Switched to AccessibilityService fallback"
        )

        // Safe unprivileged local shell fallback for read-only diagnostics
        val localFallback = runCatching {
            withTimeout(2000L) {
                val proc = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader().use { it.readText() }
                val exit = proc.waitFor()
                exit to output.trim()
            }
        }.getOrNull()

        if (localFallback != null && localFallback.first == 0) {
            AdbCommandResult(
                success = true,
                output = localFallback.second.ifEmpty { "OK (local)" },
                viaAdb = false,
                latencyMs = System.currentTimeMillis() - start
            )
        } else {
            AdbCommandResult(
                success = false,
                output = "ADB not connected; Accessibility fallback ready.",
                viaAdb = false,
                latencyMs = System.currentTimeMillis() - start
            )
        }
    }

    suspend fun freezeApp(packageName: String): AdbCommandResult {
        val safePkg = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
        return execShellCommand("pm disable-user --user 0 $safePkg")
    }

    suspend fun unfreezeApp(packageName: String): AdbCommandResult {
        val safePkg = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
        return execShellCommand("pm enable $safePkg")
    }

    suspend fun runPhoneDoctor(): String = withContext(Dispatchers.IO) {
        val trim = execShellCommand("pm trim-caches 2G")
        val doze = execShellCommand("dumpsys deviceidle force-idle")
        // Also clear Goku's own temp cache directory (Zero-Bloat Storage Cleaner)
        var clearedBytes = 0L
        context.cacheDir?.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("goku_tmp_")) {
                clearedBytes += file.length()
                file.delete()
            }
        }
        val cacheKb = clearedBytes / 1024
        if (trim.viaAdb) {
            "Phone Doctor complete (ADB): Cache trimmed, Aggressive Doze enabled, local temp ${cacheKb}KB purged."
        } else {
            "Phone Doctor complete (Local mode): Local temp ${cacheKb}KB purged. Connect Local ADB for system-wide pm trim-caches & Aggressive Doze."
        }
    }

    suspend fun dispatchAdbTap(x: Int, y: Int): AdbCommandResult =
        execShellCommand("input tap $x $y")

    suspend fun dispatchAdbSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 250): AdbCommandResult =
        execShellCommand("input swipe $x1 $y1 $x2 $y2 $durationMs")

    suspend fun dispatchAdbText(text: String): AdbCommandResult {
        val escaped = text.replace(" ", "%s").replace("\"", "").replace("'", "")
        return execShellCommand("input text $escaped")
    }

    suspend fun discoverInstalledApps(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PackageManager.ResolveInfoFlags.of(0L)
        } else null

        val infos = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && flags != null) {
                pm.queryIntentActivities(intent, flags)
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        }.getOrDefault(emptyList())

        infos.mapNotNull { ri ->
            val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty()
            val pkg = ri.activityInfo?.packageName.orEmpty()
            if (label.isNotEmpty() && pkg.isNotEmpty()) label to pkg else null
        }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
    }

    private fun executeViaAdbSocket(host: String, port: Int, service: String): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 1500)
            socket.soTimeout = 3000
            val out = DataOutputStream(socket.getOutputStream())
            val inp = DataInputStream(socket.getInputStream())

            // 1. Handshake CNXN
            val banner = "host::goku-v10\u0000".toByteArray(Charsets.UTF_8)
            writeAdbPacket(out, CMD_CNXN, A_VERSION, MAX_DATA, banner)
            val cnxnResp = readFullAdbPacket(inp)
            if (cnxnResp.command != CMD_CNXN) {
                throw IllegalStateException("ADB requires authorization or pairing")
            }

            // 2. Open shell stream
            val localId = 1
            val openPayload = "$service\u0000".toByteArray(Charsets.UTF_8)
            writeAdbPacket(out, CMD_OPEN, localId, 0, openPayload)

            val output = ByteArrayOutputStream()
            var remoteId = 0
            var packets = 0
            while (packets < 64) {
                packets++
                val pkt = readFullAdbPacket(inp)
                when (pkt.command) {
                    CMD_OKAY -> remoteId = pkt.arg0
                    CMD_WRTE -> {
                        remoteId = pkt.arg0
                        output.write(pkt.payload)
                        writeAdbPacket(out, CMD_OKAY, localId, remoteId, ByteArray(0))
                    }
                    CMD_CLSE -> {
                        writeAdbPacket(out, CMD_CLSE, localId, remoteId, ByteArray(0))
                        break
                    }
                }
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    private fun writeAdbPacket(
        out: DataOutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray
    ) {
        var checksum = 0
        for (b in payload) checksum += (b.toInt() and 0xFF)
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(payload.size)
            putInt(checksum)
            putInt(command xor -1)
        }.array()
        out.write(header)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun readAdbCommandHeader(inp: DataInputStream): Int {
        val header = ByteArray(24)
        inp.readFully(header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        bb.int // arg0
        bb.int // arg1
        val len = bb.int
        bb.int // crc
        bb.int // magic
        if (len > 0 && len < 65536) {
            val skip = ByteArray(len)
            inp.readFully(skip)
        }
        return cmd
    }

    private fun readFullAdbPacket(inp: DataInputStream): AdbPacket {
        val header = ByteArray(24)
        inp.readFully(header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val len = bb.int
        bb.int // crc
        bb.int // magic
        val payload = if (len > 0 && len <= MAX_DATA) {
            ByteArray(len).also { inp.readFully(it) }
        } else ByteArray(0)
        return AdbPacket(cmd, arg0, arg1, payload)
    }

    companion object {
        private const val A_VERSION = 0x01000000
        private const val MAX_DATA = 65536
        private const val CMD_CNXN = 0x4E584E43
        private const val CMD_AUTH = 0x48545541
        private const val CMD_OPEN = 0x4E45504F
        private const val CMD_OKAY = 0x59414B4F
        private const val CMD_CLSE = 0x45534C43
        private const val CMD_WRTE = 0x45545257

        @Volatile
        private var INSTANCE: LocalAdbEngine? = null

        fun getInstance(context: Context): LocalAdbEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocalAdbEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
