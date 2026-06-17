package com.nexbytes.h7skertool.utils

import android.util.Log

/** Protobuf field parsed from raw bytes */
data class ProtoField(
    val fieldNum: Int,
    val wireType: Int,
    val wireTypeName: String,
    val rawValue: String,       // original decoded value for display
    val bytesHex: String = ""   // raw bytes for wire types 1/2/5
)

object ProtoModifier {
    private const val TAG = "ProtoModifier"

    // ── Encoding ─────────────────────────────────────────────────────────────

    fun encodeVarint(value: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (v > 127L) { out.add(((v and 0x7F) or 0x80).toByte()); v = v ushr 7 }
        out.add((v and 0x7F).toByte())
        return out.toByteArray()
    }

    fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var value = 0L; var shift = 0; var i = offset
        while (i < data.size) {
            val b = data[i].toLong() and 0xFF; i++
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0L) break
            shift += 7; if (shift > 63) break
        }
        return Pair(value, i)
    }

    // ── Parse raw protobuf bytes → list of ProtoFields for the UI ────────────

    fun parseFields(data: ByteArray): List<ProtoField> {
        val result = mutableListOf<ProtoField>()
        if (data.isEmpty()) return result
        var i = 0
        try {
            while (i < data.size) {
                val (tag, afterTag) = readVarint(data, i)
                if (tag == 0L) break
                val fieldNumber = (tag shr 3).toInt()
                val wireType = (tag and 0x07).toInt()
                val (wireName, rawVal, nextI, rawHex) = when (wireType) {
                    0 -> { // varint
                        val (v, end) = readVarint(data, afterTag)
                        Quad("varint", v.toString(), end, "")
                    }
                    1 -> { // 64-bit
                        val end = afterTag + 8
                        if (end > data.size) break
                        val hex = data.slice(afterTag until end).joinToString("") { String.format("%02x", it) }
                        var v = 0L; for (x in 0 until 8) v = v or ((data[afterTag + x].toLong() and 0xFF) shl (x * 8))
                        Quad("fixed64", v.toString(), end, hex)
                    }
                    2 -> { // length-delimited
                        val (length, afterLen) = readVarint(data, afterTag)
                        val end = afterLen + length.toInt()
                        if (end > data.size) break
                        val bytes = data.slice(afterLen until end).toByteArray()
                        val hex = bytes.joinToString("") { String.format("%02x", it) }
                        val str = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { null }
                        val isPrintable = str != null && str.length < 200 && str.all { it.code in 32..126 || it.isWhitespace() }
                        val disp = if (isPrintable && str != null) "\"$str\"" else "<bytes:${bytes.size}>"
                        Quad("bytes/str", disp, end, hex)
                    }
                    5 -> { // 32-bit
                        val end = afterTag + 4
                        if (end > data.size) break
                        val hex = data.slice(afterTag until end).joinToString("") { String.format("%02x", it) }
                        var v = 0L; for (x in 0 until 4) v = v or ((data[afterTag + x].toLong() and 0xFF) shl (x * 8))
                        Quad("fixed32", v.toString(), end, hex)
                    }
                    else -> break
                }
                result.add(ProtoField(fieldNumber, wireType, wireName, rawVal, rawHex))
                i = nextI
            }
        } catch (e: Exception) { Log.w(TAG, "parseFields stopped: ${e.message}") }
        return result
    }

    private data class Quad(val a: String, val b: String, val c: Int, val d: String)

    // ── Apply field overrides to bytes ────────────────────────────────────────

    fun modifyProtoBytes(data: ByteArray, modFields: Map<Int, String>): ByteArray {
        if (data.isEmpty() || modFields.isEmpty()) return data
        return try {
            val out = mutableListOf<Byte>(); var i = 0
            while (i < data.size) {
                val (tag, afterTag) = readVarint(data, i)
                val fieldNumber = (tag shr 3).toInt()
                val wireType = (tag and 0x07).toInt()
                val tagBytes = encodeVarint(tag)
                val modValue = modFields[fieldNumber]
                when (wireType) {
                    0 -> {
                        val (orig, end) = readVarint(data, afterTag)
                        val nv = if (modValue != null) modValue.trim().toLongOrNull() ?: orig else orig
                        out.addAll(tagBytes.toList()); out.addAll(encodeVarint(nv).toList()); i = end
                    }
                    1 -> {
                        if (afterTag + 8 <= data.size) {
                            out.addAll(tagBytes.toList())
                            val b = ByteArray(8)
                            if (modValue != null) { val nv = modValue.trim().toLongOrNull(); if (nv != null) for (x in 0..7) b[x] = (nv ushr (x * 8)).toByte() else data.copyInto(b, 0, afterTag, afterTag + 8) }
                            else data.copyInto(b, 0, afterTag, afterTag + 8)
                            out.addAll(b.toList()); i = afterTag + 8
                        } else { out.addAll(data.slice(i until data.size)); break }
                    }
                    2 -> {
                        val (length, afterLen) = readVarint(data, afterTag); val len = length.toInt(); val end2 = afterLen + len
                        if (end2 <= data.size) {
                            out.addAll(tagBytes.toList())
                            if (modValue != null) {
                                val nb = modValue.trimSurrounding('"').toByteArray(Charsets.UTF_8)
                                out.addAll(encodeVarint(nb.size.toLong()).toList()); out.addAll(nb.toList())
                            } else {
                                out.addAll(encodeVarint(length).toList()); out.addAll(data.slice(afterLen until end2))
                            }
                            i = end2
                        } else { out.addAll(data.slice(i until data.size)); break }
                    }
                    5 -> {
                        if (afterTag + 4 <= data.size) { out.addAll(tagBytes.toList()); out.addAll(data.slice(afterTag until afterTag + 4)); i = afterTag + 4 }
                        else { out.addAll(data.slice(i until data.size)); break }
                    }
                    else -> { out.addAll(data.slice(i until data.size)); break }
                }
            }
            out.toByteArray()
        } catch (e: Exception) { Log.e(TAG, "modifyProtoBytes: ${e.message}"); data }
    }

    fun parseModFields(modJson: String): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        return try {
            val gson = com.google.gson.Gson()
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(modJson, Map::class.java) as? Map<String, Any> ?: return result
            for ((k, v) in map) { val fn = k.trim().toIntOrNull() ?: continue; result[fn] = v.toString() }
            result
        } catch (_: Exception) { result }
    }

    private fun String.trimSurrounding(c: Char): String {
        return if (length >= 2 && first() == c && last() == c) substring(1, length - 1) else this
    }
}
