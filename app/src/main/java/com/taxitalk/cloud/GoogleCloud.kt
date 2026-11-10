package com.taxitalk.cloud

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.taxitalk.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Google Cloud REST 공통 클라이언트 (Speech-to-Text / Translation / Text-to-Speech 를 API 키 하나로 사용).
 *
 * - 키는 local.properties 의 GOOGLE_CLOUD_API_KEY → BuildConfig 로 주입된다(깃에 올라가지 않음).
 * - Cloud Console 에서 키를 "Android 앱" 으로 제한할 수 있도록 X-Android-Package / X-Android-Cert 헤더를 보낸다.
 * - 키가 없거나 오프라인이면 [isUsable]=false → 호출부가 온디바이스(Gleo/ML Kit) 엔진으로 폴백한다.
 */
class GoogleCloud(context: Context, private val apiKey: String = BuildConfig.GOOGLE_CLOUD_API_KEY) {
    private val app = context.applicationContext
    private val packageName = app.packageName
    private val certSha1: String? by lazy { signingCertSha1() }

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /** 키가 있고 현재 인터넷에 연결돼 있으면 true. */
    val isUsable: Boolean get() = isConfigured && isOnline()

    fun isOnline(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** POST JSON → JSON. 실패 시 예외. */
    suspend fun post(url: String, body: JSONObject, timeoutMs: Int = 8_000): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL("$url?key=$apiKey").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Android-Package", packageName)
            certSha1?.let { setRequestProperty("X-Android-Cert", it) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(200))
                Log.w(TAG, "HTTP $code ${url.substringAfterLast('/')} : $msg")
                throw CloudException(code, msg)
            }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun signingCertSha1(): String? = runCatching {
        val info = app.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val sig = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
    }.getOrNull()

    companion object { private const val TAG = "TaxiTalkCloud" }
}

class CloudException(val httpCode: Int, message: String) : Exception(message)
