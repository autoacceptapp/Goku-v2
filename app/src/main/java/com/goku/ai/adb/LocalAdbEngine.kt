package com.goku.ai.adb

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.goku.ai.body.GokuAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import org.conscrypt.Conscrypt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

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

typealias AdbShellResult = AdbCommandResult

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
 * Generates and persists a 2048-bit RSA KeyPair + self-signed X.509 v3 certificate in
 * `context.filesDir/adb_keys/` using BouncyCastle, plus encodes the native Android ADB
 * RSA public key wire struct and signs ADB AUTH tokens.
 */
class AdbKeyManager(context: Context) {

    private val keyDir = File(context.filesDir, "adb_keys").apply { mkdirs() }
    private val privateKeyFile = File(keyDir, "adbkey.pk8")
    private val publicKeyFile = File(keyDir, "adbkey.pub")
    private val certFile = File(keyDir, "adbcert.der")

    private val lock = Any()
    private var cachedKeyPair: KeyPair? = null
    private var cachedCert: X509Certificate? = null

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            runCatching { Security.addProvider(BouncyCastleProvider()) }
        }
    }

    fun getOrCreateIdentity(): Pair<KeyPair, X509Certificate> = synchronized(lock) {
        val existingKp = cachedKeyPair
        val existingCert = cachedCert
        if (existingKp != null && existingCert != null) {
            return existingKp to existingCert
        }

        if (privateKeyFile.exists() && publicKeyFile.exists() && certFile.exists()) {
            val loaded = runCatching {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privateKeyFile.readBytes()))
                val pub = kf.generatePublic(X509EncodedKeySpec(publicKeyFile.readBytes()))
                val cf = CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
                KeyPair(pub, priv) to cert
            }.getOrNull()

            if (loaded != null) {
                cachedKeyPair = loaded.first
                cachedCert = loaded.second
                return loaded
            }
        }

        // Generate fresh 2048-bit RSA keypair + self-signed X.509 certificate
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86_400_000L)
        val notAfter = Date(now + 10L * 365L * 86_400_000L)
        val subject = X500Name("CN=goku-v10-adb,O=GokuAI,C=IN")
        val serial = BigInteger(64, SecureRandom()).abs()

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider())
            .build(kp.private)
        val certHolder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider())
            .getCertificate(certHolder)

        privateKeyFile.writeBytes(kp.private.encoded)
        publicKeyFile.writeBytes(kp.public.encoded)
        certFile.writeBytes(cert.encoded)

        cachedKeyPair = kp
        cachedCert = cert
        kp to cert
    }

    /**
     * Encodes the RSA 2048-bit public key into the exact 524-byte Android ADB wire struct:
     * uint32 len (64), uint32 n0inv, uint32 n[64], uint32 rr[64], uint32 exponent
     * Base64-encoded with user@host suffix and NUL terminator.
     */
    fun encodeAdbPublicKey(userHost: String = "goku@android"): ByteArray {
        val (kp, _) = getOrCreateIdentity()
        val pub = kp.public as RSAPublicKey
        val r32 = BigInteger.ZERO.setBit(32)
        val n = pub.modulus
        val r = BigInteger.ZERO.setBit(2048)
        val rr = r.modPow(BigInteger.valueOf(2), n)
        val rem = n.remainder(r32)
        val n0inv = rem.modInverse(r32).negate().and(BigInteger.valueOf(0xFFFFFFFFL)).toInt()

        val struct = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        struct.putInt(64) // 2048 / 32
        struct.putInt(n0inv)

        var nCopy = n
        for (i in 0 until 64) {
            struct.putInt(nCopy.and(BigInteger.valueOf(0xFFFFFFFFL)).toInt())
            nCopy = nCopy.shiftRight(32)
        }
        var rrCopy = rr
        for (i in 0 until 64) {
            struct.putInt(rrCopy.and(BigInteger.valueOf(0xFFFFFFFFL)).toInt())
            rrCopy = rrCopy.shiftRight(32)
        }
        struct.putInt(pub.publicExponent.toInt())

        val b64 = Base64.encodeToString(struct.array(), Base64.NO_WRAP)
        return "$b64 $userHost\u0000".toByteArray(Charsets.UTF_8)
    }

    /**
     * Signs an ADB AUTH_TOKEN (20-byte SHA-1 challenge) using raw RSA PKCS#1 v1.5
     * over the SHA-1 DigestInfo ASN.1 prefix (exact Android adbd verification format).
     */
    fun signAdbToken(token: ByteArray): ByteArray {
        val (kp, _) = getOrCreateIdentity()
        val priv = kp.private as RSAPrivateKey
        val sha1DigestInfoPrefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        val toSign = ByteArray(sha1DigestInfoPrefix.size + token.size)
        System.arraycopy(sha1DigestInfoPrefix, 0, toSign, 0, sha1DigestInfoPrefix.size)
        System.arraycopy(token, 0, toSign, sha1DigestInfoPrefix.size, token.size)

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, priv)
        return cipher.doFinal(toSign)
    }

    fun createClientSslContext(): SSLContext {
        val (kp, cert) = getOrCreateIdentity()
        val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
                arrayOf("goku-adb")
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String =
                "goku-adb"
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cert)
            override fun getPrivateKey(alias: String?): PrivateKey = kp.private
        }

        // Loopback 127.0.0.1 Wireless Debugging uses adbd's ephemeral self-signed certificate;
        // authentication is cryptographically bound via SPAKE2 (pairing) and client cert (connect).
        val loopbackTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val conscryptProvider = cachedConscryptProvider
        val sslContext = if (conscryptProvider != null) {
            runCatching { SSLContext.getInstance("TLSv1.3", conscryptProvider) }
                .getOrElse { SSLContext.getInstance("TLSv1.2", conscryptProvider) }
        } else {
            runCatching { SSLContext.getInstance("TLSv1.3") }
                .getOrElse { SSLContext.getInstance("TLSv1.2") }
        }
        sslContext.init(arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(loopbackTrustManager), SecureRandom())
        return sslContext
    }

    /**
     * Connects an SSLSocket preferring TLSv1.3 when Conscrypt/TLSv1.3 is available and
     * automatically falling back to TLSv1.2 when TLSv1.3 or Conscrypt exporter is unavailable.
     */
    fun createConnectedTlsSocket(
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): SSLSocket {
        val sslContext = createClientSslContext()
        val tryProtocols = if (cachedConscryptProvider != null) {
            listOf(arrayOf("TLSv1.3"), arrayOf("TLSv1.2"))
        } else {
            listOf(arrayOf("TLSv1.3", "TLSv1.2"), arrayOf("TLSv1.2"))
        }
        var lastError: Throwable? = null
        for (protocols in tryProtocols) {
            var candidate: SSLSocket? = null
            try {
                candidate = (sslContext.socketFactory.createSocket() as SSLSocket).apply {
                    soTimeout = readTimeoutMs
                    connect(InetSocketAddress(host, port), connectTimeoutMs)
                    val supported = supportedProtocols.toSet()
                    val filtered = protocols.filter { it in supported }.toTypedArray()
                    if (filtered.isEmpty()) {
                        throw IOException("Unsupported TLS protocols: ${protocols.joinToString()}")
                    }
                    enabledProtocols = filtered
                    startHandshake()
                }
                return candidate
            } catch (t: Throwable) {
                lastError = t
                runCatching { candidate?.close() }
            }
        }
        throw (lastError as? IOException)
            ?: IOException("TLS handshake failed on $host:$port: ${lastError?.message}", lastError)
    }

    companion object {
        val cachedConscryptProvider: Provider? by lazy {
            runCatching { Conscrypt.newProvider() }.getOrNull()
        }
    }
}

/**
 * Pure-Kotlin Ed25519 SPAKE2 (BoringSSL / AOSP `adbd` wire-compatible) implementation.
 * Matches BoringSSL `SPAKE2_CTX` over twisted Edwards curve Ed25519:
 * - `-x^2 + y^2 = 1 + d * x^2 * y^2 (mod p)` where `p = 2^255 - 19`
 * - Uses BoringSSL's exact Ed25519 `M` and `N` points, SHA-512 transcript hash,
 *   and HKDF-SHA256 -> AES-128-GCM key derivation for `PeerInfo` exchange.
 */
internal class AdbSpake2Ed25519(
    private val clientName: ByteArray = "adb pair client".toByteArray(Charsets.UTF_8),
    private val serverName: ByteArray = "adb pair server".toByteArray(Charsets.UTF_8),
    password: ByteArray
) {
    private val wScalar: BigInteger
    private val xScalar: BigInteger
    private val myMessage: ByteArray

    init {
        val pwHash = MessageDigest.getInstance("SHA-512").digest(password)
        wScalar = fromLittleEndian(pwHash).mod(L)

        val randBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        xScalar = fromLittleEndian(randBytes).mod(L).let { if (it == BigInteger.ZERO) BigInteger.ONE else it }

        // Alice (client) computes X = x*B + w*M
        val xB = scalarMult(BASE_B, xScalar)
        val wM = scalarMult(POINT_M, wScalar)
        val pointX = pointAdd(xB, wM)
        myMessage = encodePoint(pointX)
    }

    fun getClientMessage(): ByteArray = myMessage.copyOf()

    /**
     * Processes `adbd`'s 32-byte SPAKE2 message Y and returns the 64-byte shared key material.
     */
    fun processServerMessage(serverMsg: ByteArray): ByteArray {
        require(serverMsg.size == 32) { "Expected 32-byte SPAKE2 Ed25519 server message" }
        val pointY = decodePoint(serverMsg)
        // K = x * (Y - w*N)
        val wN = scalarMult(POINT_N, wScalar)
        val yMinusWN = pointAdd(pointY, pointNegate(wN))
        val pointK = scalarMult(yMinusWN, xScalar)
        val encodedK = encodePoint(pointK)
        val encodedW = toLittleEndian32(wScalar)

        // BoringSSL SPAKE2 transcript hash: SHA-512(len64_le(field) || field ...)
        val md = MessageDigest.getInstance("SHA-512")
        fun updateWithLen(bytes: ByteArray) {
            val lenBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(bytes.size.toLong()).array()
            md.update(lenBuf)
            md.update(bytes)
        }
        updateWithLen(clientName)
        updateWithLen(serverName)
        updateWithLen(myMessage)
        updateWithLen(serverMsg)
        updateWithLen(encodedK)
        updateWithLen(encodedW)
        return md.digest()
    }

    private data class EdwardsPoint(val x: BigInteger, val y: BigInteger)

    companion object {
        private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        private val L = BigInteger.ONE.shiftLeft(252)
            .add(BigInteger("27742317777372353535851937790883648493"))
        private val D = BigInteger("-121665")
            .multiply(BigInteger("121666").modInverse(P))
            .mod(P)
        private val SQRT_M1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
        private val IDENTITY = EdwardsPoint(BigInteger.ZERO, BigInteger.ONE)

        // Standard Ed25519 Base Point B
        private val BASE_B: EdwardsPoint by lazy {
            val by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
            val bx = recoverX(by, signBit = 0)
            EdwardsPoint(bx, by)
        }

        // BoringSSL SPAKE2 Ed25519 M point (compressed 32-byte little-endian)
        private val M_BYTES = hexToBytes("d048032c6ea0b6d697ddc2e86bda85a33adac920f1bf18e1b0c6d166a5cecdaf")
        // BoringSSL SPAKE2 Ed25519 N point (compressed 32-byte little-endian)
        private val N_BYTES = hexToBytes("d3bfb518f44f3430f29d0c92af503865a1ed3281dc69b35dd868ba85f886c4ab")

        private val POINT_M: EdwardsPoint by lazy { decodePoint(M_BYTES) }
        private val POINT_N: EdwardsPoint by lazy { decodePoint(N_BYTES) }

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

        private fun fromLittleEndian(bytes: ByteArray): BigInteger {
            val rev = bytes.reversedArray()
            return BigInteger(1, rev)
        }

        private fun toLittleEndian32(v: BigInteger): ByteArray {
            val out = ByteArray(32)
            val be = v.mod(P).toByteArray()
            var idx = 0
            for (i in be.size - 1 downTo 0) {
                if (idx >= 32) break
                out[idx++] = be[i]
            }
            return out
        }

        private fun recoverX(y: BigInteger, signBit: Int): BigInteger {
            val y2 = y.multiply(y).mod(P)
            val u = y2.subtract(BigInteger.ONE).mod(P)
            val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
            val vInv = v.modInverse(P)
            val x2 = u.multiply(vInv).mod(P)
            if (x2 == BigInteger.ZERO) {
                require(signBit == 0) { "Non-canonical Ed25519 point (0, y) with signBit == 1" }
                return BigInteger.ZERO
            }
            var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
            if (x.multiply(x).subtract(x2).mod(P) != BigInteger.ZERO) {
                x = x.multiply(SQRT_M1).mod(P)
            }
            require(x.multiply(x).mod(P) == x2) { "Invalid Ed25519 point: x^2 != x2 mod P" }
            if (x.testBit(0) != (signBit == 1)) {
                x = P.subtract(x).mod(P)
            }
            return x
        }

        private fun decodePoint(encoded: ByteArray): EdwardsPoint {
            require(encoded.size == 32) { "Ed25519 point must be 32 bytes" }
            val copy = encoded.copyOf(32)
            val signBit = (copy[31].toInt() ushr 7) and 1
            copy[31] = (copy[31].toInt() and 0x7F).toByte()
            val y = fromLittleEndian(copy)
            require(y < P) { "Non-canonical Ed25519 point: y >= P" }
            val x = recoverX(y, signBit)
            require(!(x == BigInteger.ZERO && signBit == 1)) { "Non-canonical Ed25519 point (0, y) with signBit == 1" }
            return EdwardsPoint(x, y)
        }

        private fun encodePoint(pt: EdwardsPoint): ByteArray {
            val out = toLittleEndian32(pt.y)
            if (pt.x.testBit(0)) {
                out[31] = (out[31].toInt() or 0x80).toByte()
            }
            return out
        }

        private fun pointNegate(pt: EdwardsPoint): EdwardsPoint =
            EdwardsPoint(if (pt.x == BigInteger.ZERO) BigInteger.ZERO else P.subtract(pt.x), pt.y)

        private fun pointAdd(a: EdwardsPoint, b: EdwardsPoint): EdwardsPoint {
            val x1y2 = a.x.multiply(b.y).mod(P)
            val y1x2 = a.y.multiply(b.x).mod(P)
            val y1y2 = a.y.multiply(b.y).mod(P)
            val x1x2 = a.x.multiply(b.x).mod(P)
            val dTerm = D.multiply(x1x2).mod(P).multiply(y1y2).mod(P)

            val xNum = x1y2.add(y1x2).mod(P)
            val xDen = BigInteger.ONE.add(dTerm).mod(P).modInverse(P)
            val yNum = y1y2.add(x1x2).mod(P)
            val yDen = BigInteger.ONE.subtract(dTerm).mod(P).modInverse(P)

            return EdwardsPoint(xNum.multiply(xDen).mod(P), yNum.multiply(yDen).mod(P))
        }

        private fun scalarMult(base: EdwardsPoint, scalar: BigInteger): EdwardsPoint {
            var res = IDENTITY
            var addend = base
            var k = scalar.mod(L)
            while (k > BigInteger.ZERO) {
                if (k.testBit(0)) {
                    res = pointAdd(res, addend)
                }
                addend = pointAdd(addend, addend)
                k = k.shiftRight(1)
            }
            return res
        }
    }
}

/**
 * Android 11+ ADB Wireless Debugging TLS 1.3 + SPAKE2 Pairing Client.
 * Connects directly to `127.0.0.1:<pairingPort>` with the 6-digit pairing code,
 * exports TLS keying material, performs Ed25519 SPAKE2, and exchanges AES-128-GCM
 * encrypted `PeerInfo` carrying Goku's RSA ADB public key.
 */
class AdbWirelessPairingClient(private val keyManager: AdbKeyManager) {

    data class PairingOutcome(val success: Boolean, val detail: String)

    fun pair(host: String, pairingPort: Int, sixDigitCode: String): PairingOutcome {
        return runCatching {
            val sslSocket = keyManager.createConnectedTlsSocket(
                host = host,
                port = pairingPort,
                connectTimeoutMs = 3500,
                readTimeoutMs = 5000
            )

            sslSocket.use { sock ->
                val exportedKey = exportTlsKeyingMaterial(sock)
                val codeBytes = sixDigitCode.toByteArray(Charsets.UTF_8)
                val passwordMaterial = ByteArray(codeBytes.size + exportedKey.size)
                System.arraycopy(codeBytes, 0, passwordMaterial, 0, codeBytes.size)
                System.arraycopy(exportedKey, 0, passwordMaterial, codeBytes.size, exportedKey.size)

                val spake2 = AdbSpake2Ed25519(password = passwordMaterial)
                val out = DataOutputStream(sock.outputStream)
                val inp = DataInputStream(sock.inputStream)

                // 1. Exchange SPAKE2_MSG (packet type = 0)
                val ourSpakeMsg = spake2.getClientMessage()
                writePairingPacket(out, TYPE_SPAKE2_MSG, ourSpakeMsg)

                val (serverType, serverSpakeMsg) = readPairingPacket(inp)
                if (serverType != TYPE_SPAKE2_MSG || serverSpakeMsg.size != 32) {
                    return PairingOutcome(false, "Unexpected SPAKE2 response from adbd (type=$serverType, len=${serverSpakeMsg.size}).")
                }

                // 2. Derive AES-128-GCM session key via HKDF-SHA256
                val sharedKey = spake2.processServerMessage(serverSpakeMsg)
                val aesKey = hkdfSha256(
                    ikm = sharedKey,
                    salt = ByteArray(0),
                    info = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8),
                    length = 16
                )

                // 3. Build 8192-byte PeerInfo struct (type=0 ADB_RSA_PUB_KEY + RSA pub key)
                val peerInfo = ByteArray(PEER_INFO_SIZE)
                peerInfo[0] = 0 // PeerInfo.Type.ADB_RSA_PUB_KEY
                val adbPubKey = keyManager.encodeAdbPublicKey("goku@android")
                System.arraycopy(adbPubKey, 0, peerInfo, 1, adbPubKey.size.coerceAtMost(PEER_INFO_SIZE - 1))

                val encryptedPeerInfo = aesGcmEncrypt(aesKey, sequence = 0L, plaintext = peerInfo)
                writePairingPacket(out, TYPE_PEER_INFO, encryptedPeerInfo)

                // 4. Read & verify adbd's encrypted PeerInfo acknowledgement
                val (respType, respCiphertext) = readPairingPacket(inp)
                if (respType != TYPE_PEER_INFO || respCiphertext.size < 16) {
                    return PairingOutcome(false, "Pairing rejected by adbd (check 6-digit code & port).")
                }
                aesGcmDecrypt(aesKey, sequence = 0L, ciphertext = respCiphertext)
                PairingOutcome(true, "TLS 1.3 + SPAKE2 Wireless Pairing succeeded on $host:$pairingPort.")
            }
        }.getOrElse { err ->
            PairingOutcome(false, "Pairing error: ${err.message ?: err.javaClass.simpleName}")
        }
    }

    /**
     * Uses Android's built-in Conscrypt `exportKeyingMaterial(SSLSocket, label, context, length)`
     * matching AOSP `adb/pairing_connection/pairing_connection.cpp` ("adb-label\0", 64 bytes).
     */
    private fun exportTlsKeyingMaterial(socket: SSLSocket): ByteArray {
        if (!runCatching { Conscrypt.isConscrypt(socket) }.getOrDefault(false)) {
            throw IllegalStateException("Conscrypt TLS keying material exporter unavailable on fallback SSLSocket (${socket.session?.protocol ?: "unknown"})")
        }
        val exported = runCatching {
            Conscrypt.exportKeyingMaterial(socket, "adb-label\u0000", null, 64)
        }.getOrElse { err ->
            throw IllegalStateException("Conscrypt TLS keying material export failed: ${err.message}", err)
        }
        if (exported != null && exported.size == 64) {
            return exported
        }
        throw IllegalStateException("Conscrypt TLS keying material export returned invalid length (${exported?.size ?: 0})")
    }

    private fun writePairingPacket(out: DataOutputStream, type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).apply {
            put(PAIRING_VERSION)
            put(type)
            putInt(payload.size)
        }.array()
        out.write(header)
        out.write(payload)
        out.flush()
    }

    private fun readPairingPacket(inp: DataInputStream): Pair<Byte, ByteArray> {
        val header = ByteArray(6)
        inp.readFully(header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = bb.get()
        require(version == PAIRING_VERSION) { "Unsupported ADB pairing packet version: $version" }
        val type = bb.get()
        val size = bb.int
        require(size in 0..32768) { "Invalid pairing packet size: $size" }
        val payload = ByteArray(size)
        if (size > 0) inp.readFully(payload)
        return type to payload
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        hmac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        val prk = hmac.doFinal(ikm)

        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter: Byte = 1
        while (offset < length) {
            hmac.init(SecretKeySpec(prk, "HmacSHA256"))
            hmac.update(t)
            hmac.update(info)
            hmac.update(counter)
            t = hmac.doFinal()
            val toCopy = (length - offset).coerceAtMost(t.size)
            System.arraycopy(t, 0, result, offset, toCopy)
            offset += toCopy
            counter++
        }
        return result
    }

    private fun aesGcmEncrypt(key: ByteArray, sequence: Long, plaintext: ByteArray): ByteArray {
        val iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(sequence).putInt(0).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(key: ByteArray, sequence: Long, ciphertext: ByteArray): ByteArray {
        val iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(sequence).putInt(0).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val PAIRING_VERSION: Byte = 1
        private const val TYPE_SPAKE2_MSG: Byte = 0
        private const val TYPE_PEER_INFO: Byte = 1
        private const val PEER_INFO_SIZE = 8192
    }
}

/**
 * Persistent Local ADB Connection Client supporting:
 * - TLS 1.3 (`_adb-tls-connect._tcp`) authentication with saved RSA certificate
 * - Automatic `tcpip:5555` activation over TLS
 * - Persistent authenticated loopback socket (`127.0.0.1:5555`) with full ADB `AUTH` RSA signing
 * - Stream-based shell execution with loop-breaker timeouts
 */
class AdbConnectionClient(private val keyManager: AdbKeyManager) {

    private val ioMutex = Mutex()
    private var persistentSocket: Socket? = null
    private var persistentOut: DataOutputStream? = null
    private var persistentIn: DataInputStream? = null
    private var connectedPort: Int = -1
    private var connectedViaTls: Boolean = false
    private var nextStreamId: Int = 1

    data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    data class AdbServiceResult(
        val output: String,
        val receivedOkay: Boolean,
        val receivedWritePayload: Boolean,
        val streamRefused: Boolean
    )

    suspend fun connect(host: String = "127.0.0.1", port: Int = 5555, useTls: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                closeLocked()
                var sock: Socket? = null
                runCatching {
                    val activeSock: Socket = if (useTls) {
                        keyManager.createConnectedTlsSocket(
                            host = host,
                            port = port,
                            connectTimeoutMs = 2500,
                            readTimeoutMs = 3500
                        )
                    } else {
                        Socket().apply {
                            soTimeout = 3500
                            connect(InetSocketAddress(host, port), 2000)
                        }
                    }
                    sock = activeSock

                    val out = DataOutputStream(activeSock.getOutputStream())
                    val inp = DataInputStream(activeSock.getInputStream())
                    if (!performAdbHandshake(out, inp)) {
                        runCatching { activeSock.close() }
                        return@withLock false
                    }

                    persistentSocket = activeSock
                    persistentOut = out
                    persistentIn = inp
                    connectedPort = port
                    connectedViaTls = useTls
                    nextStreamId = 1
                    true
                }.onFailure {
                    runCatching { sock?.close() }
                }.getOrDefault(false)
            }
        }

    /**
     * Connects to the `_adb-tls-connect._tcp` port over TLS using our paired client
     * certificate and invokes `tcpip:5555` so loopback `127.0.0.1:5555` stays active.
     */
    suspend fun enableTcpIpOverTls(tlsConnectPort: Int, targetTcpPort: Int = 5555): Boolean {
        if (!connect("127.0.0.1", tlsConnectPort, useTls = true)) return false
        val resp = runCatching { openAndReadService("tcpip:$targetTcpPort", timeoutMs = 4000L) }.getOrNull()
        disconnect()
        return !resp.isNullOrBlank() && !resp.contains("error", ignoreCase = true)
    }

    suspend fun openAndReadService(service: String, timeoutMs: Long = 4500L): String {
        val detailed = openAndReadServiceDetailed(service, timeoutMs)
        if (detailed.streamRefused) {
            throw IOException("ADB stream refused (CMD_CLSE)")
        }
        return detailed.output
    }

    suspend fun openAndReadServiceDetailed(
        service: String,
        timeoutMs: Long = 4500L
    ): AdbServiceResult = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val sock = persistentSocket
            val out = persistentOut
            val inp = persistentIn
            if (sock == null || sock.isClosed || !sock.isConnected || out == null || inp == null) {
                throw IllegalStateException("ADB persistent socket not connected")
            }

            val localId = nextStreamId++
            var remoteId = 0
            var closedCleanly = false
            var receivedOkay = false
            var receivedWritePayload = false
            var streamRefused = false

            try {
                withTimeout(timeoutMs) {
                    val deadlineMs = System.currentTimeMillis() + timeoutMs
                    // Critical Fix #2: Unblock blocking DataInputStream.readFully() immediately on timeout or cancellation
                    val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                        if (cause != null) {
                            runCatching { sock.close() }
                        }
                    }
                    try {
                        sock.soTimeout = timeoutMs.toInt().coerceIn(500, 2500)
                        val openPayload = "$service\u0000".toByteArray(Charsets.UTF_8)
                        writeAdbPacket(out, CMD_OPEN, localId, 0, openPayload)

                        val output = ByteArrayOutputStream()
                        var packets = 0
                        while (packets < 16_384) {
                            coroutineContext.ensureActive()
                            val remainingMs = deadlineMs - System.currentTimeMillis()
                            if (remainingMs <= 0L) {
                                throw SocketTimeoutException("ADB stream '$service' timed out after ${timeoutMs}ms")
                            }
                            sock.soTimeout = remainingMs.toInt().coerceIn(250, 2000)

                            packets++
                            val pkt = readFullAdbPacket(inp)
                            when (pkt.command) {
                                CMD_OKAY -> {
                                    if (pkt.arg1 == localId) {
                                        if (pkt.arg0 != 0) receivedOkay = true
                                        remoteId = pkt.arg0
                                    }
                                }
                                CMD_WRTE -> {
                                    if (pkt.arg1 == localId) {
                                        remoteId = pkt.arg0
                                        if (pkt.payload.isNotEmpty()) {
                                            receivedWritePayload = true
                                            output.write(pkt.payload)
                                        }
                                        writeAdbPacket(out, CMD_OKAY, localId, remoteId, ByteArray(0))
                                    }
                                }
                                CMD_CLSE -> {
                                    if (pkt.arg1 == localId || pkt.arg1 == 0) {
                                        if (!receivedOkay && !receivedWritePayload) {
                                            streamRefused = true
                                        }
                                        val peerId = if (remoteId != 0) remoteId else pkt.arg0
                                        if (peerId != 0) {
                                            writeAdbPacket(out, CMD_CLSE, localId, peerId, ByteArray(0))
                                        }
                                        closedCleanly = true
                                        break
                                    }
                                }
                            }
                        }
                        if (!closedCleanly) {
                            throw IOException("ADB stream '$service' did not receive CMD_CLSE within $packets packets")
                        }
                        AdbServiceResult(
                            output = output.toString(Charsets.UTF_8.name()),
                            receivedOkay = receivedOkay,
                            receivedWritePayload = receivedWritePayload,
                            streamRefused = streamRefused
                        )
                    } finally {
                        cancelHandle?.dispose()
                    }
                }
            } catch (t: Throwable) {
                if (!closedCleanly && remoteId != 0 && !sock.isClosed) {
                    runCatching { writeAdbPacket(out, CMD_CLSE, localId, remoteId, ByteArray(0)) }
                }
                closeLocked()
                throw t
            }
        }
    }

    fun isAlive(): Boolean {
        val s = persistentSocket ?: return false
        return !s.isClosed && s.isConnected
    }

    fun activePort(): Int = connectedPort

    suspend fun disconnect() = ioMutex.withLock {
        closeLocked()
    }

    private fun closeLocked() {
        runCatching { persistentSocket?.close() }
        persistentSocket = null
        persistentOut = null
        persistentIn = null
        connectedPort = -1
        connectedViaTls = false
    }

    private fun performAdbHandshake(out: DataOutputStream, inp: DataInputStream): Boolean {
        val banner = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,sendrecv_v2,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd,sendrecv_v2_dry_run_send\u0000"
            .toByteArray(Charsets.UTF_8)
        writeAdbPacket(out, CMD_CNXN, A_VERSION, MAX_DATA, banner)

        var sentSignature = false
        var sentPublicKey = false

        repeat(6) {
            val pkt = readFullAdbPacket(inp)
            when (pkt.command) {
                CMD_CNXN -> return true
                CMD_AUTH -> {
                    if (pkt.arg0 == AUTH_TOKEN) {
                        if (!sentSignature) {
                            sentSignature = true
                            val sig = keyManager.signAdbToken(pkt.payload)
                            writeAdbPacket(out, CMD_AUTH, AUTH_SIGNATURE, 0, sig)
                        } else if (!sentPublicKey) {
                            sentPublicKey = true
                            val pubKey = keyManager.encodeAdbPublicKey("goku@android")
                            writeAdbPacket(out, CMD_AUTH, AUTH_RSAPUBLICKEY, 0, pubKey)
                        } else {
                            return false
                        }
                    }
                }
                else -> return false
            }
        }
        return false
    }

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

    private fun readFullAdbPacket(inp: DataInputStream): AdbPacket {
        val header = ByteArray(24)
        inp.readFully(header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val len = bb.int
        bb.int // crc
        val magic = bb.int
        if (magic != (cmd xor -1)) {
            throw IOException("Invalid ADB packet magic: 0x${magic.toString(16)} for cmd=0x${cmd.toString(16)}")
        }
        if (len !in 0..MAX_DATA) {
            throw IOException("Invalid ADB packet payload length: $len")
        }
        val payload = if (len > 0) {
            ByteArray(len).also { inp.readFully(it) }
        } else ByteArray(0)
        return AdbPacket(cmd, arg0, arg1, payload)
    }

    companion object {
        private const val A_VERSION = 0x01000001
        private const val MAX_DATA = 256 * 1024
        private const val CMD_CNXN = 0x4E584E43
        private const val CMD_AUTH = 0x48545541
        private const val CMD_OPEN = 0x4E45504F
        private const val CMD_OKAY = 0x59414B4F
        private const val CMD_CLSE = 0x45534C43
        private const val CMD_WRTE = 0x45545257

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
    }
}

/**
 * Represents a single invisible background step executed on Goku's offscreen VirtualDisplay.
 */
sealed class VirtualDisplayStep {
    data class Tap(val x: Int, val y: Int) : VirtualDisplayStep()
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Int = 250) : VirtualDisplayStep()
    data class InputText(val text: String) : VirtualDisplayStep()
    data class KeyEvent(val keyCode: Int) : VirtualDisplayStep()
    data class Wait(val millis: Long) : VirtualDisplayStep()
    data class Shell(val command: String) : VirtualDisplayStep()
}

/**
 * V10.1 Invisible Background Task Runner via Android `DisplayManager.createVirtualDisplay`.
 * Creates an offscreen 1080x2400 @ 320dpi virtual display (`"GokuVirtualScreen"`) backed by an
 * offscreen `ImageReader` surface and routes Local ADB commands (`am start --display <displayId>`,
 * `input -d <displayId> tap/swipe/text/keyevent`) so Goku can run multi-step app tasks invisibly
 * without disturbing the user's active screen.
 */
class VirtualDisplayBackgroundRunner(
    private val context: Context,
    private val adbEngine: LocalAdbEngine
) {
    private val displayManager: DisplayManager? =
        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private val lock = Any()
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var drainThread: android.os.HandlerThread? = null
    private var drainHandler: android.os.Handler? = null

    @Volatile
    private var isOwnContentOnlyRestricted: Boolean = false

    private val _virtualDisplayId = MutableStateFlow<Int?>(null)
    val virtualDisplayId: StateFlow<Int?> = _virtualDisplayId.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    fun ensureVirtualDisplay(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        densityDpi: Int = DEFAULT_DPI
    ): Int? = synchronized(lock) {
        val currentDisplay = virtualDisplay?.display
        if (currentDisplay != null && currentDisplay.isValid) {
            val existingId = currentDisplay.displayId
            if (existingId > 0) {
                _virtualDisplayId.value = existingId
                _isActive.value = true
                return existingId
            }
        }

        releaseLocked()
        val dm = displayManager ?: return null

        var createdThread: android.os.HandlerThread? = null
        var createdReader: ImageReader? = null
        var createdVd: VirtualDisplay? = null

        return try {
            createdThread = android.os.HandlerThread("GokuVirtualDisplayDrain").apply { start() }
            val handler = android.os.Handler(createdThread.looper)
            createdReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener({ r ->
                    runCatching { r.acquireLatestImage()?.close() }
                }, handler)
            }

            // Attempt PUBLIC + OWN_CONTENT_ONLY first; fall back to OWN_CONTENT_ONLY if restricted
            var ownContentOnly = false
            createdVd = runCatching {
                dm.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    width,
                    height,
                    densityDpi,
                    createdReader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                )
            }.getOrElse {
                ownContentOnly = true
                dm.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    width,
                    height,
                    densityDpi,
                    createdReader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                )
            }

            if (createdVd == null || createdVd.display?.isValid != true) {
                // Critical Fix #3: Always clean up ImageReader & drainThread on null/invalid VirtualDisplay
                runCatching { createdVd?.release() }
                runCatching { createdReader.setOnImageAvailableListener(null, null) }
                runCatching { createdReader.close() }
                runCatching { createdThread.quitSafely() }
                releaseLocked()
                return null
            }

            isOwnContentOnlyRestricted = ownContentOnly
            drainThread = createdThread
            drainHandler = handler
            imageReader = createdReader
            virtualDisplay = createdVd
            val displayId = createdVd.display.displayId
            _virtualDisplayId.value = displayId
            _isActive.value = true
            displayId
        } catch (_: Throwable) {
            runCatching { createdVd?.release() }
            runCatching { createdReader?.setOnImageAvailableListener(null, null) }
            runCatching { createdReader?.close() }
            runCatching { createdThread?.quitSafely() }
            releaseLocked()
            null
        }
    }

    private fun isVirtualDisplayLaunchDenied(output: String): Boolean {
        val lower = output.lowercase()
        return lower.contains("securityexception") ||
            lower.contains("permission denial") ||
            lower.contains("not allowed") ||
            lower.contains("cannot start") ||
            lower.contains("unable to") ||
            lower.contains("adb stream refused") ||
            lower.contains("error:")
    }

    private fun launchViaAccessibilityIntentBridge(
        safePkg: String,
        startMs: Long,
        reason: String
    ): AdbCommandResult {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(safePkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = if (launchIntent != null) {
            runCatching {
                context.startActivity(launchIntent)
                true
            }.getOrDefault(false)
        } else false
        val a11yReady = GokuAccessibilityService.instance != null
        val latency = System.currentTimeMillis() - startMs
        return if (launched) {
            AdbCommandResult(
                success = true,
                output = "$reason Auto-routed $safePkg via ${if (a11yReady) "GokuAccessibilityService + Intent" else "Intent"} bridge.",
                viaAdb = false,
                latencyMs = latency
            )
        } else {
            AdbCommandResult(
                success = false,
                output = "$reason Could not resolve launch intent for $safePkg.",
                viaAdb = false,
                latencyMs = latency
            )
        }
    }

    suspend fun launchAppInBackground(
        packageName: String,
        activityComponent: String? = null
    ): AdbCommandResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val safePkg = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
        if (safePkg.isBlank()) {
            return@withContext AdbCommandResult(
                success = false,
                output = "Invalid package name for VirtualDisplay launch.",
                viaAdb = false,
                latencyMs = System.currentTimeMillis() - start
            )
        }

        val displayId = ensureVirtualDisplay()
            ?: return@withContext launchViaAccessibilityIntentBridge(
                safePkg = safePkg,
                startMs = start,
                reason = "GokuVirtualScreen unavailable on this device."
            )

        // Non-root Android 14–16 restriction: OWN_CONTENT_ONLY virtual displays block third-party apps
        if (isOwnContentOnlyRestricted && safePkg != context.packageName) {
            return@withContext launchViaAccessibilityIntentBridge(
                safePkg = safePkg,
                startMs = start,
                reason = "VirtualDisplay restricted by VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY on non-root Android."
            )
        }

        val component = activityComponent?.replace(Regex("[^a-zA-Z0-9._/$]"), "")
            ?: resolveLauncherComponent(safePkg)

        val cmd = if (!component.isNullOrBlank()) {
            "am start --display $displayId -n $component"
        } else {
            "am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $safePkg"
        }
        val adbRes = adbEngine.execShellCommand(cmd)
        if (!adbRes.success || !adbRes.viaAdb || isVirtualDisplayLaunchDenied(adbRes.output)) {
            return@withContext launchViaAccessibilityIntentBridge(
                safePkg = safePkg,
                startMs = start,
                reason = "am start --display $displayId blocked (${adbRes.output.take(120)})."
            )
        }
        adbRes
    }

    suspend fun tapOnVirtualDisplay(x: Int, y: Int): AdbCommandResult {
        val displayId = ensureVirtualDisplay()
            ?: return AdbCommandResult(false, "GokuVirtualScreen unavailable.", false, 0L)
        return adbEngine.execShellCommand("input -d $displayId tap $x $y")
    }

    suspend fun swipeOnVirtualDisplay(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int = 250
    ): AdbCommandResult {
        val displayId = ensureVirtualDisplay()
            ?: return AdbCommandResult(false, "GokuVirtualScreen unavailable.", false, 0L)
        return adbEngine.execShellCommand("input -d $displayId swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    suspend fun inputTextOnVirtualDisplay(text: String): AdbCommandResult {
        val displayId = ensureVirtualDisplay()
            ?: return AdbCommandResult(false, "GokuVirtualScreen unavailable.", false, 0L)
        val escaped = LocalAdbEngine.escapeAdbInputText(text)
        return adbEngine.execShellCommand("input -d $displayId text $escaped")
    }

    suspend fun pressKeyOnVirtualDisplay(keyCode: Int): AdbCommandResult {
        val displayId = ensureVirtualDisplay()
            ?: return AdbCommandResult(false, "GokuVirtualScreen unavailable.", false, 0L)
        return adbEngine.execShellCommand("input -d $displayId keyevent $keyCode")
    }

    /**
     * Runs a complete multi-step app workflow invisibly on `GokuVirtualScreen` when ADB +
     * VirtualDisplay are permitted, or seamlessly falls back to `GokuAccessibilityService`
     * on non-root Android 14–16 devices.
     */
    suspend fun runBackgroundTaskSequence(
        packageName: String,
        steps: List<VirtualDisplayStep>,
        releaseAfter: Boolean = false
    ): List<AdbCommandResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<AdbCommandResult>()
        val launchRes = launchAppInBackground(packageName)
        results.add(launchRes)
        if (!launchRes.success) {
            if (releaseAfter) releaseVirtualDisplay()
            return@withContext results
        }
        val routedViaAccessibility = !launchRes.viaAdb
        delay(650L)

        for (step in steps) {
            val res = if (routedViaAccessibility) {
                executeStepViaAccessibilityBridge(step)
            } else {
                when (step) {
                    is VirtualDisplayStep.Tap -> tapOnVirtualDisplay(step.x, step.y)
                    is VirtualDisplayStep.Swipe -> swipeOnVirtualDisplay(step.x1, step.y1, step.x2, step.y2, step.durationMs)
                    is VirtualDisplayStep.InputText -> inputTextOnVirtualDisplay(step.text)
                    is VirtualDisplayStep.KeyEvent -> pressKeyOnVirtualDisplay(step.keyCode)
                    is VirtualDisplayStep.Wait -> {
                        delay(step.millis.coerceIn(0L, 10_000L))
                        AdbCommandResult(true, "Waited ${step.millis}ms", viaAdb = true, latencyMs = step.millis)
                    }
                    is VirtualDisplayStep.Shell -> adbEngine.execShellCommand(step.command)
                }
            }
            results.add(res)
            if (!res.success) break
        }

        if (releaseAfter) releaseVirtualDisplay()
        results
    }

    private suspend fun executeStepViaAccessibilityBridge(step: VirtualDisplayStep): AdbCommandResult {
        val start = System.currentTimeMillis()
        val a11y = GokuAccessibilityService.instance
        return when (step) {
            is VirtualDisplayStep.Wait -> {
                delay(step.millis.coerceIn(0L, 10_000L))
                AdbCommandResult(true, "Waited ${step.millis}ms", viaAdb = false, latencyMs = step.millis)
            }
            is VirtualDisplayStep.Shell -> adbEngine.execShellCommand(step.command)
            is VirtualDisplayStep.Tap -> {
                val ok = a11y?.performTap(step.x.toFloat(), step.y.toFloat()) ?: false
                AdbCommandResult(ok, if (ok) "Tapped via Accessibility bridge" else "AccessibilityService unavailable for tap", false, System.currentTimeMillis() - start)
            }
            is VirtualDisplayStep.Swipe -> {
                val ok = a11y?.performSwipe(step.x1.toFloat(), step.y1.toFloat(), step.x2.toFloat(), step.y2.toFloat(), step.durationMs.toLong()) ?: false
                AdbCommandResult(ok, if (ok) "Swiped via Accessibility bridge" else "AccessibilityService unavailable for swipe", false, System.currentTimeMillis() - start)
            }
            is VirtualDisplayStep.InputText -> {
                val ok = a11y?.inputText(step.text) ?: false
                AdbCommandResult(ok, if (ok) "Typed text via Accessibility bridge" else "AccessibilityService unavailable for input", false, System.currentTimeMillis() - start)
            }
            is VirtualDisplayStep.KeyEvent -> {
                val ok = when (step.keyCode) {
                    android.view.KeyEvent.KEYCODE_BACK -> a11y?.performBack() ?: false
                    android.view.KeyEvent.KEYCODE_HOME -> a11y?.performHome() ?: false
                    else -> false
                }
                AdbCommandResult(ok, if (ok) "KeyEvent ${step.keyCode} via Accessibility bridge" else "Unsupported keyEvent ${step.keyCode} in Accessibility bridge", false, System.currentTimeMillis() - start)
            }
        }
    }

    fun releaseVirtualDisplay() = synchronized(lock) {
        releaseLocked()
    }

    private fun releaseLocked() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        runCatching { drainThread?.quitSafely() }
        virtualDisplay = null
        imageReader = null
        drainHandler = null
        drainThread = null
        isOwnContentOnlyRestricted = false
        _virtualDisplayId.value = null
        _isActive.value = false
    }

    private fun resolveLauncherComponent(packageName: String): String? {
        return runCatching {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            launchIntent?.component?.flattenToShortString()
        }.getOrNull()
    }

    companion object {
        const val VIRTUAL_DISPLAY_NAME = "GokuVirtualScreen"
        const val DEFAULT_WIDTH = 1080
        const val DEFAULT_HEIGHT = 2400
        const val DEFAULT_DPI = 320
    }
}

/**
 * Pure-Kotlin Android 11+ Local ADB Engine (Zero Shizuku Required).
 *
 * Includes:
 * - `AdbKeyManager` (BouncyCastle 2048-bit RSA + X.509 cert persisted in `filesDir/adb_keys/`)
 * - `AdbWirelessPairingClient` (TLS 1.3 + Conscrypt Keying Material + Ed25519 SPAKE2 + AES-GCM PeerInfo)
 * - `AdbConnectionClient` (mDNS `_adb-tls-connect._tcp` -> `tcpip:5555` -> persistent `127.0.0.1:5555`)
 * - `VirtualDisplayBackgroundRunner` (1080x2400 @ 320dpi offscreen VirtualDisplay + `--display` / `input -d` routing)
 * - Full shell executor (`pm disable-user`, `pm enable`, `pm trim-caches`, `dumpsys deviceidle force-idle`,
 *   `input tap/swipe/text`), loop-breaker timeout, and seamless auto-fallback to `GokuAccessibilityService`.
 */
class LocalAdbEngine(private val context: Context) {

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    val keyManager = AdbKeyManager(context.applicationContext)
    val virtualDisplayRunner: VirtualDisplayBackgroundRunner by lazy {
        VirtualDisplayBackgroundRunner(context.applicationContext, this)
    }
    private val pairingClient = AdbWirelessPairingClient(keyManager)
    private val connectionClient = AdbConnectionClient(keyManager)

    private val _status = MutableStateFlow(AdbStatus())
    val status: StateFlow<AdbStatus> = _status.asStateFlow()

    private val _useAccessibilityFallback = MutableStateFlow(true)
    val useAccessibilityFallback: StateFlow<Boolean> = _useAccessibilityFallback.asStateFlow()

    private val nsdLock = Any()
    private val connectLoopbackMutex = Mutex()
    private val resolveQueue = ArrayDeque<Pair<NsdServiceInfo, Boolean>>()
    private var isResolvingNsd = false
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
        _status.update {
            it.copy(statusMessage = "Scanning mDNS for Wireless Debugging ports...")
        }
        val pListener = createDiscoveryListener(isPairing = true)
        val cListener = createDiscoveryListener(isPairing = false)
        synchronized(nsdLock) {
            pairingListener = pListener
            connectListener = cListener
        }

        runCatching {
            manager.discoverServices("_adb-tls-pairing._tcp.", NsdManager.PROTOCOL_DNS_SD, pListener)
        }.onFailure {
            synchronized(nsdLock) {
                if (pairingListener === pListener) pairingListener = null
            }
        }
        runCatching {
            manager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, cListener)
        }.onFailure {
            synchronized(nsdLock) {
                if (connectListener === cListener) connectListener = null
            }
        }
    }

    fun stopMdnsDiscovery() {
        val manager = nsdManager ?: return
        val pListener: NsdManager.DiscoveryListener?
        val cListener: NsdManager.DiscoveryListener?
        synchronized(nsdLock) {
            resolveQueue.clear()
            isResolvingNsd = false
            pListener = pairingListener
            cListener = connectListener
            pairingListener = null
            connectListener = null
        }
        pListener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        cListener?.let { runCatching { manager.stopServiceDiscovery(it) } }
    }

    private fun createDiscoveryListener(isPairing: Boolean): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveNsdService(serviceInfo, isPairing)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                val manager = nsdManager ?: return
                runCatching { manager.stopServiceDiscovery(this) }
                synchronized(nsdLock) {
                    if (isPairing && pairingListener === this) pairingListener = null
                    if (!isPairing && connectListener === this) connectListener = null
                }
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                val manager = nsdManager ?: return
                runCatching { manager.stopServiceDiscovery(this) }
                synchronized(nsdLock) {
                    if (isPairing && pairingListener === this) pairingListener = null
                    if (!isPairing && connectListener === this) connectListener = null
                }
            }
        }
    }

    private fun isLocalDeviceHost(host: java.net.InetAddress?): Boolean {
        if (host == null) return true
        return host.isLoopbackAddress || host.isSiteLocalAddress || host.isLinkLocalAddress
    }

    private fun resolveNsdService(serviceInfo: NsdServiceInfo, isPairing: Boolean) {
        val manager = nsdManager ?: return
        synchronized(nsdLock) {
            resolveQueue.addLast(serviceInfo to isPairing)
            if (!isResolvingNsd) {
                dispatchNextResolveLocked(manager)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun dispatchNextResolveLocked(manager: NsdManager) {
        val next = resolveQueue.removeFirstOrNull()
        if (next == null) {
            isResolvingNsd = false
            return
        }
        isResolvingNsd = true
        val (serviceInfo, isPairing) = next
        val dispatched = runCatching {
            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                    synchronized(nsdLock) {
                        isResolvingNsd = false
                        dispatchNextResolveLocked(manager)
                    }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val port = info.port
                    val host = info.host
                    if (port in 1024..65535 && isLocalDeviceHost(host)) {
                        if (isPairing) {
                            AdbPairingOverlayBus.updateDetectedPort(port)
                            _status.update {
                                it.copy(
                                    discoveredPairingPort = port,
                                    statusMessage = "Detected Wireless Pairing port $port — Enter 6-digit code"
                                )
                            }
                        } else {
                            _status.update {
                                it.copy(
                                    discoveredConnectPort = port,
                                    statusMessage = "Detected Wireless Connect port $port"
                                )
                            }
                        }
                    }
                    synchronized(nsdLock) {
                        isResolvingNsd = false
                        dispatchNextResolveLocked(manager)
                    }
                }
            })
            true
        }.getOrDefault(false)
        if (!dispatched) {
            isResolvingNsd = false
            dispatchNextResolveLocked(manager)
        }
    }

    /**
     * Connects to local ADB daemon on 127.0.0.1:5555 (plain TCP with RSA AUTH) or
     * falls back to the mDNS-discovered TLS connect port (`_adb-tls-connect._tcp`).
     */
    suspend fun connectLoopback(port: Int = 5555): Boolean = withContext(Dispatchers.IO) {
        connectLoopbackMutex.withLock {
            // 1. Try plain loopback TCP (5555)
            var ok = connectionClient.connect("127.0.0.1", port, useTls = false)
            var activePort = port

            // 2. If 5555 isn't open yet, check mDNS-discovered TLS connect port
            if (!ok) {
                val tlsPort = _status.value.discoveredConnectPort
                if (tlsPort != null && tlsPort in 1024..65535) {
                    if (connectionClient.enableTcpIpOverTls(tlsPort, 5555)) {
                        delay(700L)
                        ok = connectionClient.connect("127.0.0.1", 5555, useTls = false)
                        activePort = 5555
                    }
                    if (!ok) {
                        ok = connectionClient.connect("127.0.0.1", tlsPort, useTls = true)
                        activePort = tlsPort
                    }
                }
            }

            if (ok) {
                stopMdnsDiscovery()
                _status.update {
                    it.copy(
                        isConnected = true,
                        port = activePort,
                        statusMessage = "Local ADB Active on 127.0.0.1:$activePort"
                    )
                }
                _useAccessibilityFallback.value = false
            } else {
                _status.update {
                    it.copy(
                        isConnected = false,
                        statusMessage = "127.0.0.1:$port unreachable — Accessibility Fallback Active"
                    )
                }
                _useAccessibilityFallback.value = true
            }
            ok
        }
    }

    /**
     * Full Android 11+ Wireless Debugging Pair -> TLS Connect -> `tcpip:5555` -> Persistent Loopback flow.
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

        _status.update {
            it.copy(statusMessage = "Running TLS 1.3 + SPAKE2 pairing on 127.0.0.1:$pairingPort...")
        }

        // Ensure mDNS is discovering _adb-tls-connect._tcp while we pair
        if (_status.value.discoveredConnectPort == null) {
            startMdnsDiscovery()
        }

        val outcome = pairingClient.pair("127.0.0.1", pairingPort, cleanCode)
        if (!outcome.success) {
            // Check if already paired/listening on 5555
            if (connectLoopback(5555)) {
                AdbPairingOverlayBus.dismissOverlay()
                return@withContext AdbCommandResult(
                    success = true,
                    output = "Local ADB already active on 127.0.0.1:${_status.value.port}.",
                    viaAdb = true,
                    latencyMs = System.currentTimeMillis() - start
                )
            }
            _useAccessibilityFallback.value = true
            _status.update {
                it.copy(
                    isConnected = false,
                    statusMessage = "${outcome.detail} — Accessibility Fallback Active"
                )
            }
            return@withContext AdbCommandResult(
                success = false,
                output = outcome.detail,
                viaAdb = false,
                latencyMs = System.currentTimeMillis() - start
            )
        }
        AdbPairingOverlayBus.dismissOverlay()

        // Wait briefly for _adb-tls-connect._tcp mDNS resolution after successful pairing
        var tlsConnectPort = _status.value.discoveredConnectPort
        var waited = 0
        while (tlsConnectPort == null && waited < 6) {
            delay(350L)
            tlsConnectPort = _status.value.discoveredConnectPort
            waited++
        }

        // Authenticate over TLS connect port and activate tcpip:5555
        if (tlsConnectPort != null) {
            _status.update {
                it.copy(statusMessage = "Paired! Activating tcpip:5555 via TLS port $tlsConnectPort...")
            }
            val switched = connectionClient.enableTcpIpOverTls(tlsConnectPort, 5555)
            if (switched) {
                delay(800L)
            }
        }

        val connected = connectLoopback(5555) ||
            (tlsConnectPort != null && connectionClient.connect("127.0.0.1", tlsConnectPort, useTls = true).also {
                if (it) {
                    _status.update { cur ->
                        cur.copy(
                            isConnected = true,
                            port = tlsConnectPort,
                            statusMessage = "Local ADB TLS Active on 127.0.0.1:$tlsConnectPort"
                        )
                    }
                    _useAccessibilityFallback.value = false
                }
            })

        AdbPairingOverlayBus.dismissOverlay()
        if (connected) {
            stopMdnsDiscovery()
            AdbCommandResult(
                success = true,
                output = "Wireless Debugging paired & Local ADB active on 127.0.0.1:${_status.value.port}.",
                viaAdb = true,
                latencyMs = System.currentTimeMillis() - start
            )
        } else {
            _useAccessibilityFallback.value = true
            val msg = "Paired successfully with RSA key, waiting for Wireless Debugging connect port. Accessibility Fallback remains active."
            _status.update { it.copy(isConnected = false, statusMessage = msg) }
            AdbCommandResult(
                success = false,
                output = msg,
                viaAdb = false,
                latencyMs = System.currentTimeMillis() - start
            )
        }
    }

    /**
     * Executes a shell command over persistent Local ADB (`127.0.0.1:5555` or TLS port)
     * with strict loop-breaker timeout. Auto-flips `useAccessibilityFallback = true` on disconnect.
     */
    suspend fun execShellCommand(
        command: String,
        timeoutMs: Long = 4500L
    ): AdbCommandResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        // Word-boundary guardrail against destructive system wipe commands without blocking "information"/"platform"
        if (isBlockedDestructiveShellCommand(command)) {
            return@withContext AdbCommandResult(
                success = false,
                output = "Blocked unsafe shell command by Goku Guardrail.",
                viaAdb = false,
                latencyMs = System.currentTimeMillis() - start
            )
        }

        // Ensure persistent socket is connected (lazy auto-reconnect)
        if (!connectionClient.isAlive()) {
            connectLoopback(_status.value.port)
        }

        val adbAttempt = if (connectionClient.isAlive()) {
            runCatching {
                connectionClient.openAndReadServiceDetailed("shell:$command", timeoutMs)
            }
        } else {
            Result.failure(IllegalStateException("ADB not connected"))
        }

        if (adbAttempt.isSuccess) {
            val serviceRes = adbAttempt.getOrThrow()
            if (serviceRes.streamRefused) {
                _status.update {
                    it.copy(lastCommandOutput = "ADB stream refused (CMD_CLSE)")
                }
                return@withContext AdbCommandResult(
                    success = false,
                    output = "ADB stream refused (CMD_CLSE)",
                    viaAdb = false,
                    latencyMs = System.currentTimeMillis() - start
                )
            }
            val out = serviceRes.output.trim()
            _status.update {
                it.copy(
                    isConnected = true,
                    lastCommandOutput = out.take(400)
                )
            }
            _useAccessibilityFallback.value = false
            return@withContext AdbCommandResult(
                success = true,
                output = out.ifEmpty { "OK" },
                viaAdb = true,
                latencyMs = System.currentTimeMillis() - start
            )
        }

        // Socket broke or timed out -> clean disconnect & auto-fallback to GokuAccessibilityService
        connectionClient.disconnect()
        _useAccessibilityFallback.value = true
        _status.update {
            it.copy(
                isConnected = false,
                statusMessage = "ADB offline — Switched to AccessibilityService fallback"
            )
        }

        // Safe unprivileged local shell fallback for read-only diagnostics
        val localFallback = runCatching {
            withTimeout(2000L) {
                val proc = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
                try {
                    val outputFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                        proc.inputStream.bufferedReader().use { it.readText() }
                    }
                    val finished = waitForProcessCompat(proc, 1800L)
                    if (!finished) {
                        destroyProcessCompat(proc)
                        throw IOException("Local shell command timed out")
                    }
                    val output = runCatching { outputFuture.get(300L, TimeUnit.MILLISECONDS) }.getOrDefault("")
                    proc.exitValue() to output.trim()
                } finally {
                    runCatching { destroyProcessCompat(proc) }
                }
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

    private fun waitForProcessCompat(proc: Process, timeoutMs: Long): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                proc.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(25L)
            }
        }
        return try {
            proc.exitValue()
            true
        } catch (_: IllegalThreadStateException) {
            false
        }
    }

    private fun destroyProcessCompat(proc: Process) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (proc.isAlive) proc.destroyForcibly()
        } else {
            proc.destroy()
        }
    }

    suspend fun freezeApp(packageName: String): AdbCommandResult {
        val safePkg = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
        if (safePkg.isBlank()) return AdbCommandResult(false, "Invalid package name", false, 0L)
        return execShellCommand("pm disable-user --user 0 $safePkg")
    }

    suspend fun unfreezeApp(packageName: String): AdbCommandResult {
        val safePkg = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
        if (safePkg.isBlank()) return AdbCommandResult(false, "Invalid package name", false, 0L)
        return execShellCommand("pm enable $safePkg")
    }

    suspend fun runPhoneDoctor(): String = withContext(Dispatchers.IO) {
        val trim = execShellCommand("pm trim-caches 2G")
        val doze = execShellCommand("dumpsys deviceidle force-idle")
        var clearedBytes = 0L
        context.cacheDir?.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("goku_tmp_")) {
                clearedBytes += file.length()
                file.delete()
            }
        }
        val cacheKb = clearedBytes / 1024
        if (trim.viaAdb) {
            "Phone Doctor complete (ADB): Cache trimmed, Aggressive Doze ${if (doze.success) "enabled" else "requested"}, local temp ${cacheKb}KB purged."
        } else {
            "Phone Doctor complete (Local mode): Local temp ${cacheKb}KB purged. Pair Wireless Debugging for system-wide pm trim-caches & Aggressive Doze."
        }
    }

    suspend fun dispatchAdbTap(x: Int, y: Int): AdbCommandResult =
        execShellCommand("input tap $x $y")

    suspend fun dispatchAdbSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 250): AdbCommandResult =
        execShellCommand("input swipe $x1 $y1 $x2 $y2 $durationMs")

    suspend fun dispatchAdbText(text: String): AdbCommandResult {
        val escaped = escapeAdbInputText(text)
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
            val label = ri.loadLabel(pm).toString().trim()
            val pkg = ri.activityInfo?.packageName.orEmpty()
            if (label.isNotEmpty() && pkg.isNotEmpty()) label to pkg else null
        }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
    }

    companion object {
        @Volatile
        private var INSTANCE: LocalAdbEngine? = null

        private val BLOCKED_SHELL_PATTERNS = listOf(
            Regex("""\brm\s+-[a-z]*r[a-z]*f[a-z]*\s+/(?:\s|$|\*|[;&|])""", RegexOption.IGNORE_CASE),
            Regex("""\brm\s+-[a-z]*f[a-z]*r[a-z]*\s+/(?:\s|$|\*|[;&|])""", RegexOption.IGNORE_CASE),
            Regex("""\bmkfs(?:\.[a-z0-9_+-]+)?\b""", RegexOption.IGNORE_CASE),
            Regex("""\bdd\s+if=""", RegexOption.IGNORE_CASE),
            Regex("""\breboot\s+bootloader\b""", RegexOption.IGNORE_CASE),
            Regex("""\bwipe[\s_]+data\b""", RegexOption.IGNORE_CASE),
            Regex("""\bfactory[\s_]+reset\b""", RegexOption.IGNORE_CASE),
            Regex("""\bformat\s+(?:/[a-z0-9_/-]+|userdata|system|data|cache|sd\w*)\b""", RegexOption.IGNORE_CASE)
        )

        fun isBlockedDestructiveShellCommand(command: String): Boolean {
            return BLOCKED_SHELL_PATTERNS.any { it.containsMatchIn(command) }
        }

        private val SHELL_METACHARS = setOf(
            '\\', ';', '&', '|', '$', '`', '"', '\'', '(', ')',
            '<', '>', '*', '?', '[', ']', '{', '}', '~', '!', '#'
        )

        fun escapeAdbInputText(text: String): String {
            val normalized = text
                .replace("\r\n", " ")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
            val sb = StringBuilder(normalized.length + 16)
            for (ch in normalized) {
                when {
                    ch == ' ' -> sb.append("%s")
                    ch in SHELL_METACHARS -> sb.append('\\').append(ch)
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        fun getInstance(context: Context): LocalAdbEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocalAdbEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
