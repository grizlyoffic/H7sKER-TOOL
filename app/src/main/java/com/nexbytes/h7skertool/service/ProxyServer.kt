package com.nexbytes.h7skertool.service

import android.util.Log
import com.nexbytes.h7skertool.model.CapturedRequest
import com.nexbytes.h7skertool.model.CapturedResponse
import com.nexbytes.h7skertool.utils.HexUtils
import com.nexbytes.h7skertool.utils.ProtoModifier
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ProxyServer(
    private val clientBaseUrl: String,
    private val scope: CoroutineScope,
    private val savedMods: Map<String, String>,
    private val onCapture: (CapturedRequest, CapturedResponse) -> Unit,
    private val onLog: (String) -> Unit
) : NanoHTTPD("127.0.0.1", 8080) {
    private val TAG = "ProxyServer"
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()

    override fun serve(session: IHTTPSession): Response {
        val method = session.method.name
        val path = session.uri
        val endpoint = extractEndpoint(path)
        val start = System.currentTimeMillis()
        onLog("→ $method $endpoint")

        val reqHeaders = session.headers.toMutableMap()
        val bodyBytes: ByteArray? = try {
            val len = reqHeaders["content-length"]?.toLongOrNull() ?: 0L
            if (len in 1..10_000_000L) {
                val buf = ByteArray(len.toInt()); var off = 0
                while (off < buf.size) { val r = session.inputStream.read(buf, off, buf.size - off); if (r == -1) break; off += r }
                buf
            } else null
        } catch (_: Exception) { null }

        // FIX #4: Apply request body mod + header mod
        val finalBody = applyRequestMod(endpoint, bodyBytes)
        val modHeaders = applyHeaderMod(endpoint, reqHeaders)

        val capturedReq = CapturedRequest(
            method = method, url = "$clientBaseUrl$path", endpoint = endpoint,
            headers = modHeaders, body = finalBody,
            bodyText = finalBody?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() },
            bodyHex = HexUtils.toHexDump(finalBody)
        )

        return try {
            val realResp = forwardRequest(method, path, modHeaders, finalBody)
            val duration = System.currentTimeMillis() - start
            val modResult = applyResponseMod(endpoint, realResp)
            val capturedRes = CapturedResponse(
                requestId = capturedReq.id, statusCode = realResp.code, statusMessage = realResp.message,
                endpoint = endpoint, headers = modResult.headers, body = modResult.bytes,
                bodyText = modResult.text, bodyHex = modResult.hex, durationMs = duration
            )
            onLog("← ${realResp.code} $endpoint (${duration}ms)")
            scope.launch { onCapture(capturedReq, capturedRes) }
            realResp.close()
            val mime = modResult.headers["content-type"] ?: "application/octet-stream"
            val resp = newFixedLengthResponse(Response.Status.lookup(realResp.code)?:Response.Status.OK, mime, modResult.bytes?.inputStream(), (modResult.bytes?.size?:0).toLong())
            modResult.headers.forEach { (k,v) -> if (!k.equals("content-length",true) && !k.equals("transfer-encoding",true)) resp.addHeader(k,v) }
            resp
        } catch (e: IOException) {
            onLog("✗ Error $endpoint: ${e.message}")
            val errRes = CapturedResponse(requestId=capturedReq.id,statusCode=503,statusMessage="Proxy Error",endpoint=endpoint,headers=emptyMap(),body=null,bodyText=e.message,bodyHex=null,durationMs=-1L)
            scope.launch { onCapture(capturedReq, errRes) }
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"text/plain","Proxy error: ${e.message}")
        }
    }

    private fun forwardRequest(method: String, path: String, headers: Map<String, String>, body: ByteArray?): okhttp3.Response {
        val url = "$clientBaseUrl$path"
        val ct = headers["content-type"]?.toMediaTypeOrNull()
        val reqBody = when {
            body != null && method !in listOf("GET","HEAD") -> body.toRequestBody(ct)
            method !in listOf("GET","HEAD") -> ByteArray(0).toRequestBody(ct)
            else -> null
        }
        val builder = okhttp3.Request.Builder().url(url)
        val host = clientBaseUrl.removePrefix("https://").removePrefix("http://").split("/").first()
        headers.forEach { (k,v) -> if (k.lowercase() !in listOf("host","connection","transfer-encoding","content-length","keep-alive","proxy-connection")) runCatching { builder.addHeader(k,v) } }
        builder.header("Host", host)
        return http.newCall(builder.method(method, reqBody).build()).execute()
    }

    private fun applyRequestMod(endpoint: String, body: ByteArray?): ByteArray? {
        // FIX #4: Check both endpoint-specific mod AND the selected mod
        val mod = savedMods[endpoint] ?: savedMods["__selected_mod__"] ?: return body
        if (body == null) return null
        return try {
            val fields = ProtoModifier.parseModFields(mod)
            if (fields.isNotEmpty()) ProtoModifier.modifyProtoBytes(body, fields)
            else mod.toByteArray(Charsets.UTF_8)
        } catch (_: Exception) { body }
    }

    private fun applyHeaderMod(endpoint: String, headers: MutableMap<String, String>): MutableMap<String, String> {
        val mod = savedMods["${endpoint}_headers"] ?: return headers
        val modified = headers.toMutableMap()
        return try {
            val gson = com.google.gson.Gson()
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(mod, Map::class.java) as? Map<String, Any> ?: return modified
            for ((k,v) in map) {
                val value = v.toString().trim()
                if (value.isEmpty() || value == "null") modified.remove(k) else modified[k] = value
            }
            modified
        } catch (_: Exception) { modified }
    }

    private fun applyResponseMod(endpoint: String, response: okhttp3.Response): ModResult {
        val mod = savedMods["${endpoint}_response"]
        val origBytes = try { response.body?.bytes() } catch (_: Exception) { null }
        val headers = mutableMapOf<String, String>()
        response.headers.forEach { (k,v) -> headers[k] = v }
        if (origBytes == null) return ModResult(null, null, null, headers)
        if (mod.isNullOrEmpty()) return ModResult(origBytes, safeString(origBytes), HexUtils.toHexDump(origBytes), headers)
        val modBytes = try {
            val fields = ProtoModifier.parseModFields(mod)
            if (fields.isNotEmpty()) ProtoModifier.modifyProtoBytes(origBytes, fields) else mod.toByteArray(Charsets.UTF_8)
        } catch (_: Exception) { origBytes }
        headers["content-length"] = modBytes.size.toString()
        return ModResult(modBytes, safeString(modBytes), HexUtils.toHexDump(modBytes), headers)
    }

    private fun safeString(bytes: ByteArray) = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
    private fun extractEndpoint(path: String) = "/${path.split("?").first().trimStart('/').split("/").firstOrNull{it.isNotEmpty()}?:""}"
    private data class ModResult(val bytes: ByteArray?, val text: String?, val hex: String?, val headers: MutableMap<String, String>)
}
