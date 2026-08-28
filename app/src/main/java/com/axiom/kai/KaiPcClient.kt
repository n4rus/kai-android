package com.axiom.kai

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Encrypted live bridge to Kai PC (desktop).
 * Phone is a thin client: sends text/file/image to PC's opencode session,
 * PC's live terminal shows it, reply comes back.
 * Self-signed TLS + Bearer token. Also works via adb forward.
 */
object KaiPcClient {

    private const val PREFS = "kai_pc"
    private const val KEY_HOST = "pc_host" // e.g. 192.168.1.10:8443
    private const val KEY_TOKEN = "pc_token"
    private const val KEY_SCHEME = "pc_scheme" // https or http

    fun saveConfig(ctx: Context, hostPort: String, token: String, scheme: String = "https") {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, hostPort.trim())
            .putString(KEY_TOKEN, token.trim())
            .putString(KEY_SCHEME, scheme)
            .apply()
    }

    fun getHost(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOST, null)
    fun getToken(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)
    fun getScheme(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SCHEME, "https") ?: "https"

    fun isConfigured(ctx: Context): Boolean = !getHost(ctx).isNullOrBlank() && !getToken(ctx).isNullOrBlank()

    /** Try to auto-discover Kai PC on the local network or via USB forward */
    suspend fun autoDiscover(ctx: Context): String? = withContext(Dispatchers.IO) {
        // 1. Check if already configured and reachable
        val savedHost = getHost(ctx)
        val savedToken = getToken(ctx)
        if (!savedHost.isNullOrBlank() && !savedToken.isNullOrBlank()) {
            // Try saved host first
            if (tryConnect(savedHost, savedToken, getScheme(ctx))) return@withContext savedHost
        }

        // 2. Try common local network IPs and adb forward
        val candidates = listOf(
            "127.0.0.1:8443", // adb forward tcp:8443 tcp:8443
            "10.0.2.2:8443",  // emulator host
            "192.168.1.100:8443", "192.168.1.10:8443", "192.168.0.100:8443", "192.168.0.10:8443",
            "192.168.42.1:8443", "192.168.43.1:8443" // USB tethering
        )

        // Also try to get the device's gateway IP and try it
        try {
            val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val dhcp = wifi?.dhcpInfo
            if (dhcp != null) {
                val gateway = (dhcp.gateway and 0xFF).toString() + "." +
                    ((dhcp.gateway shr 8) and 0xFF) + "." +
                    ((dhcp.gateway shr 16) and 0xFF) + "." +
                    ((dhcp.gateway shr 24) and 0xFF)
                // Try gateway and gateway subnet
                for (i in 1..5) {
                    val ip = gateway.substringBeforeLast(".") + ".${i}:8443"
                    if (ip !in candidates && tryConnect(ip, savedToken ?: "kai-secret-123", "https")) {
                        if (savedToken != null) saveConfig(ctx, ip, savedToken, "https")
                        return@withContext ip
                    }
                }
            }
        } catch (_: Exception) {}

        for (host in candidates) {
            val token = savedToken ?: "kai-secret-123"
            if (tryConnect(host, token, "https") || tryConnect(host, token, "http")) {
                // Auto-save the discovered host
                saveConfig(ctx, host, token, if (tryConnect(host, token, "https")) "https" else "http")
                return@withContext host
            }
        }
        null
    }

    private suspend fun tryConnect(host: String, token: String, scheme: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$scheme://$host/health")
            val conn = (if (scheme == "https") {
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(a: Array<java.security.cert.X509Certificate>?, b: String?) {}
                    override fun checkServerTrusted(a: Array<java.security.cert.X509Certificate>?, b: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                })
                val sc = SSLContext.getInstance("SSL")
                sc.init(null, trustAll, java.security.SecureRandom())
                (url.openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = sc.socketFactory
                    hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
            } else url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 2000
                readTimeout = 3000
            }
            conn.responseCode in 200..299
        } catch (_: Exception) { false }
    }

    /** Send text/file/image to PC's live session. Returns PC's reply. */
    suspend fun send(
        ctx: Context,
        text: String,
        type: String = "text",
        filename: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val host = getHost(ctx)
        val token = getToken(ctx)
        if (host.isNullOrBlank() || token.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Kai PC not configured — open Settings → Kai PC → enter 192.168.x.x:8443 + token"))
        }
        val scheme = getScheme(ctx)
        val urlStr = "$scheme://$host/"
        try {
            val url = URL(urlStr)
            val conn = (if (scheme == "https") {
                // Trust self-signed cert from kai_pc_server.py
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(a: Array<java.security.cert.X509Certificate>?, b: String?) {}
                    override fun checkServerTrusted(a: Array<java.security.cert.X509Certificate>?, b: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                })
                val sc = SSLContext.getInstance("SSL")
                sc.init(null, trustAll, java.security.SecureRandom())
                (url.openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = sc.socketFactory
                    hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
            } else url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 30000
            }

            val body = JSONObject().apply {
                put("text", text)
                put("type", type)
                if (filename != null) put("filename", filename)
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) return@withContext Result.failure(Exception("PC $code: $resp"))
            val reply = try { JSONObject(resp).optString("reply", resp) } catch (_: Exception) { resp }
            Result.success(reply)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Poll live log from PC for Terminal tab */
    suspend fun fetchLive(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        val host = getHost(ctx) ?: return@withContext emptyList()
        val token = getToken(ctx) ?: return@withContext emptyList()
        val scheme = getScheme(ctx)
        try {
            val url = URL("$scheme://$host/live")
            val conn = (if (scheme == "https") {
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(a: Array<java.security.cert.X509Certificate>?, b: String?) {}
                    override fun checkServerTrusted(a: Array<java.security.cert.X509Certificate>?, b: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                })
                val sc = SSLContext.getInstance("SSL")
                sc.init(null, trustAll, java.security.SecureRandom())
                (url.openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = sc.socketFactory
                    hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
            } else url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 5000
                readTimeout = 10000
            }
            val resp = conn.inputStream.bufferedReader().readText()
            val arr = try { org.json.JSONArray(resp) } catch (_: Exception) { return@withContext emptyList() }
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                "[${o.optString("type")}] ${o.optString("text")}"
            }.takeLast(50)
        } catch (_: Exception) { emptyList() }
    }

    /** Send a real email via the PC server's SMTP bridge. Returns true on success. */
    suspend fun sendEmail(
        ctx: Context,
        to: String,
        subject: String,
        htmlBody: String,
        textBody: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val host = getHost(ctx)
        val token = getToken(ctx)
        if (host.isNullOrBlank() || token.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Kai PC not configured — open Settings → Kai PC → enter 192.168.x.x:8443 + token"))
        }
        val scheme = getScheme(ctx)
        try {
            val url = URL("$scheme://$host/send-email")
            val conn = (if (scheme == "https") {
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(a: Array<java.security.cert.X509Certificate>?, b: String?) {}
                    override fun checkServerTrusted(a: Array<java.security.cert.X509Certificate>?, b: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                })
                val sc = SSLContext.getInstance("SSL")
                sc.init(null, trustAll, java.security.SecureRandom())
                (url.openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = sc.socketFactory
                    hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
            } else url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 15000
            }

            val body = JSONObject().apply {
                put("to", to)
                put("subject", subject)
                put("html", htmlBody)
                put("text", textBody)
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) return@withContext Result.failure(Exception("PC email failed $code: $resp"))
            val json = try { JSONObject(resp) } catch (_: Exception) { null }
            if (json?.optBoolean("ok") == true) Result.success(true)
            else Result.failure(Exception(json?.optString("error", "unknown error") ?: resp))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Helper: encode file/image to base64 for transport */
    fun fileToBase64(ctx: Context, uri: Uri): Pair<String, String>? {
        return try {
            val name = uri.lastPathSegment ?: "file"
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            // Cap at 5MB for live transfer (larger files: use USB bundle)
            val capped = if (bytes.size > 5 * 1024 * 1024) bytes.copyOf(5 * 1024 * 1024) else bytes
            Pair(Base64.encodeToString(capped, Base64.NO_WRAP), name)
        } catch (_: Exception) { null }
    }
}
