package com.ghost.api.logic

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Arrays

/**
 * DeepSeekWebClient
 *
 * Consults DeepSeek using the authenticated web session cookies captured via in-app WebView login.
 * Handles DeepSeek's Proof-of-Work challenge (DeepSeekHashV1) on-device before executing completions.
 */
object DeepSeekWebClient {

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val gson = Gson()

    // Multi-turn conversation stateholding
    @Volatile
    private var activeChatSessionId: String? = null
    @Volatile
    private var lastMessageId: Any? = null

    fun resetSession() {
        activeChatSessionId = null
        lastMessageId = null
        Timber.i("DeepSeekWebClient: Active chat session reset")
    }

    suspend fun queryDeepSeek(
        cookies: String,
        prompt: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (cookies.isBlank()) {
            return@withContext Pair(
                false,
                "DeepSeek is not connected. Open GHOST Settings -> AI Phonebook and tap 'Log In' to connect your account."
            )
        }

        try {
            // Extract user token if present in cookies or localStorage (with word-boundary check)
            val mToken = Regex("""(?:(?<=[;\s])|^)userToken=([^;]+)""", RegexOption.IGNORE_CASE).find(cookies)
                ?: Regex("""(?:(?<=[;\s])|^)(?:user_token|d_token|auth_token)=([^;]+)""", RegexOption.IGNORE_CASE).find(cookies)
                ?: Regex("""(?:(?<=[;\s])|^)token=([^;]+)""", RegexOption.IGNORE_CASE).find(cookies)

            val rawToken = mToken?.groupValues?.get(1)?.trim()

            val authToken = if (!rawToken.isNullOrBlank()) {
                val decoded = try { java.net.URLDecoder.decode(rawToken, "UTF-8") } catch (_: Exception) { rawToken }
                Regex(""""value"\s*:\s*"([^"]+)"""").find(decoded)?.groupValues?.get(1) ?: decoded
            } else null

            if (authToken.isNullOrBlank()) {
                return@withContext Pair(
                    false,
                    "DeepSeek login incomplete. Open GHOST Settings -> AI Phonebook, tap 'Log In', and sign into your DeepSeek account before saving the session."
                )
            }

            // Step 1: Request Proof-of-Work Challenge
            Timber.i("DeepSeek: requesting PoW challenge...")
            val powChallenge = requestPoWChallenge(authToken, cookies)
            if (powChallenge == null) {
                return@withContext Pair(
                    false,
                    "DeepSeek session expired or challenged. Please tap 'Re-login' on DeepSeek in Settings to refresh your session."
                )
            }

            // Step 2: Solve DeepSeekHashV1 on-device
            val (challenge, salt, expireAt, difficulty, signature) = powChallenge
            Timber.i("DeepSeek: solving PoW challenge (difficulty: $difficulty)...")
            val startTime = System.currentTimeMillis()
            val answer = DeepSeekHashV1.solve(challenge, salt, expireAt, difficulty)
            val elapsed = System.currentTimeMillis() - startTime
            if (answer == -1) {
                return@withContext Pair(
                    false,
                    "Could not solve DeepSeek proof-of-work challenge within difficulty limits."
                )
            }
            Timber.i("DeepSeek: solved PoW challenge in ${elapsed}ms -> answer: $answer")

            // Step 3: Construct X-DS-PoW-Response header
            val powPayload = JsonObject().apply {
                addProperty("algorithm", "DeepSeekHashV1")
                addProperty("challenge", challenge)
                addProperty("salt", salt)
                addProperty("answer", answer)
                addProperty("signature", signature)
                addProperty("target_path", "/api/v0/chat/completion")
            }
            val powHeaderVal = Base64.encodeToString(
                powPayload.toString().toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP
            )

            // Step 4: Create or reuse Chat Session ID
            var sessionId = activeChatSessionId
            if (sessionId == null) {
                sessionId = createChatSession(authToken, cookies)
                if (sessionId == null) {
                    return@withContext Pair(
                        false,
                        "DeepSeek failed to initialize chat session. Please tap 'Re-login' in Settings."
                    )
                }
                activeChatSessionId = sessionId
                lastMessageId = null
                Timber.i("DeepSeek: initialized new chat session $sessionId")
            } else {
                Timber.i("DeepSeek: continuing active chat session $sessionId (parentMsg: $lastMessageId)")
            }

            // Step 5: Send Chat Completion
            val compUrl = URL("https://chat.deepseek.com/api/v0/chat/completion")
            val connection = (compUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
                setRequestProperty("Authorization", "Bearer $authToken")
                setRequestProperty("Cookie", cookies)
                setRequestProperty("x-ds-pow-response", powHeaderVal)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Origin", "https://chat.deepseek.com")
                setRequestProperty("Referer", "https://chat.deepseek.com/")
                connectTimeout = 15000
                readTimeout = 40000
                doOutput = true
            }

            val requestBody = JsonObject().apply {
                addProperty("chat_session_id", sessionId)
                val parentId = lastMessageId
                when (parentId) {
                    is Number -> addProperty("parent_message_id", parentId)
                    is String -> {
                        val num = parentId.toLongOrNull()
                        if (num != null) addProperty("parent_message_id", num)
                        else addProperty("parent_message_id", parentId)
                    }
                    else -> add("parent_message_id", com.google.gson.JsonNull.INSTANCE)
                }
                addProperty("prompt", prompt.trim())
                add("ref_file_ids", com.google.gson.JsonArray())
                addProperty("thinking_enabled", false)
                addProperty("search_enabled", false)
            }

            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use {
                it.write(requestBody.toString())
                it.flush()
            }

            val code = connection.responseCode
            if (code in 200..299) {
                val contentSb = StringBuilder()
                connection.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("data:")) {
                            val chunkRaw = trimmed.removePrefix("data:").trim()
                            if (chunkRaw.isNotBlank() && chunkRaw != "[DONE]") {
                                try {
                                    val chunkObj = gson.fromJson(chunkRaw, JsonObject::class.java)
                                    if (chunkObj != null) {
                                        // Extract assistant message ID for multi-turn thread continuation
                                        val msgIdElem = when {
                                            chunkObj.has("message_id") -> chunkObj.get("message_id")
                                            chunkObj.has("msg_id") -> chunkObj.get("msg_id")
                                            chunkObj.has("id") -> chunkObj.get("id")
                                            chunkObj.has("v") && chunkObj.get("v").isJsonObject -> {
                                                val vObj = chunkObj.getAsJsonObject("v")
                                                vObj.get("message_id")
                                                    ?: vObj.getAsJsonObject("response")?.get("message_id")
                                                    ?: vObj.get("id")
                                            }
                                            chunkObj.has("choices") -> {
                                                val choices = chunkObj.getAsJsonArray("choices")
                                                if (choices != null && choices.size() > 0) {
                                                    val choice0 = choices[0].asJsonObject
                                                    choice0.get("id")
                                                        ?: choice0.getAsJsonObject("message")?.get("id")
                                                        ?: choice0.getAsJsonObject("delta")?.get("id")
                                                } else null
                                            }
                                            else -> null
                                        }
                                        if (msgIdElem != null && !msgIdElem.isJsonNull) {
                                            lastMessageId = if (msgIdElem.isJsonPrimitive && msgIdElem.asJsonPrimitive.isNumber) {
                                                msgIdElem.asLong
                                            } else {
                                                msgIdElem.asString
                                            }
                                        }

                                        if (chunkObj.has("v")) {
                                            val vElem = chunkObj.get("v")
                                            if (vElem != null && vElem.isJsonPrimitive) {
                                                contentSb.append(vElem.asString)
                                            }
                                        } else if (chunkObj.has("choices")) {
                                            val choices = chunkObj.getAsJsonArray("choices")
                                            if (choices != null && choices.size() > 0) {
                                                val choice0 = choices[0].asJsonObject
                                                val delta = choice0.getAsJsonObject("delta")
                                                val text = delta?.get("content")?.asString
                                                    ?: choice0.getAsJsonObject("message")?.get("content")?.asString
                                                if (!text.isNullOrEmpty()) {
                                                    contentSb.append(text)
                                                }
                                            }
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }

                val reply = contentSb.toString().trim()
                if (reply.isNotBlank()) {
                    Pair(true, reply)
                } else {
                    Pair(false, "DeepSeek returned an empty response.")
                }
            } else if (code == 400) {
                // Stale or invalid session state - reset for next query
                resetSession()
                Pair(false, "DeepSeek session expired or diverged. Resetting session context — please query again.")
            } else if (code == 429) {
                Pair(false, "DeepSeek is currently rate-limited or busy (HTTP 429). The whale is taking a breather!")
            } else if (code == 401 || code == 403) {
                resetSession()
                Pair(false, "DeepSeek session expired or challenged (HTTP $code). Tap 'Re-login' in Settings to refresh your session.")
            } else {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Pair(false, "DeepSeek returned HTTP $code: ${err.take(150)}")
            }
        } catch (e: Exception) {
            Timber.e(e, "DeepSeekWebClient error")
            Pair(false, "Network error consulting DeepSeek: ${e.message}")
        }
    }

    private data class PoWChallenge(
        val challenge: String,
        val salt: String,
        val expireAt: Long,
        val difficulty: Int,
        val signature: String
    )

    private fun requestPoWChallenge(authToken: String, cookies: String): PoWChallenge? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://chat.deepseek.com/api/v0/chat/create_pow_challenge")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
                setRequestProperty("Authorization", "Bearer $authToken")
                setRequestProperty("Cookie", cookies)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Origin", "https://chat.deepseek.com")
                setRequestProperty("Referer", "https://chat.deepseek.com/")
                connectTimeout = 10000
                readTimeout = 15000
                doOutput = true
            }

            val body = JsonObject().apply {
                addProperty("target_path", "/api/v0/chat/completion")
            }
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                it.write(body.toString())
                it.flush()
            }

            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val root = try { gson.fromJson(text, JsonObject::class.java) } catch (_: Exception) { null }
                if (root == null || root.get("code")?.asInt != 0) {
                    Timber.w("DeepSeek PoW challenge failed: $text")
                    return null
                }
                val dataObj = if (root.has("data") && root.get("data").isJsonObject) root.getAsJsonObject("data") else null
                val bizDataObj = if (dataObj != null && dataObj.has("biz_data") && dataObj.get("biz_data").isJsonObject) dataObj.getAsJsonObject("biz_data") else null
                val challengeObj = if (bizDataObj != null && bizDataObj.has("challenge") && bizDataObj.get("challenge").isJsonObject) bizDataObj.getAsJsonObject("challenge") else null

                if (challengeObj != null) {
                    val challenge = challengeObj.get("challenge")?.asString ?: return null
                    val salt = challengeObj.get("salt")?.asString ?: return null
                    val expireAt = challengeObj.get("expire_at")?.asLong ?: return null
                    val difficulty = challengeObj.get("difficulty")?.asInt ?: 144000
                    val signature = challengeObj.get("signature")?.asString ?: return null
                    PoWChallenge(challenge, salt, expireAt, difficulty, signature)
                } else null
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Timber.w("DeepSeek PoW challenge HTTP ${conn.responseCode}: $err")
                null
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to request DeepSeek PoW challenge")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun createChatSession(authToken: String, cookies: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://chat.deepseek.com/api/v0/chat_session/create")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
                setRequestProperty("Authorization", "Bearer $authToken")
                setRequestProperty("Cookie", cookies)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Origin", "https://chat.deepseek.com")
                setRequestProperty("Referer", "https://chat.deepseek.com/")
                connectTimeout = 10000
                readTimeout = 15000
                doOutput = true
            }

            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                it.write("{}")
                it.flush()
            }

            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val root = try { gson.fromJson(text, JsonObject::class.java) } catch (_: Exception) { null }
                if (root == null || root.get("code")?.asInt != 0) {
                    Timber.w("DeepSeek createChatSession failed: $text")
                    return null
                }
                val dataObj = if (root.has("data") && root.get("data").isJsonObject) root.getAsJsonObject("data") else null
                val bizDataObj = if (dataObj != null && dataObj.has("biz_data") && dataObj.get("biz_data").isJsonObject) dataObj.getAsJsonObject("biz_data") else null
                bizDataObj?.get("id")?.asString
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Timber.w("DeepSeek createChatSession HTTP ${conn.responseCode}: $err")
                null
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to create DeepSeek chat session")
            null
        } finally {
            conn?.disconnect()
        }
    }
}

/**
 * Native on-device solver for DeepSeek's anti-bot Proof-of-Work challenge (DeepSeekHashV1).
 * Emulates the exact Keccak-f[1600] 64-bit word permutation and sponge padding used by chat.deepseek.com.
 */
object DeepSeekHashV1 {

    private val D = intArrayOf(
        0, 1, 0, 32898,
        0x80000000.toInt(), 32906,
        0x80000000.toInt(), 0x80008000.toInt(),
        0, 32907,
        0, 0x80000001.toInt(),
        0x80000000.toInt(), 0x80008081.toInt(),
        0x80000000.toInt(), 32777,
        0, 138,
        0, 136,
        0, 0x80008009.toInt(),
        0, 0x8000000a.toInt(),
        0, 0x8000808b.toInt(),
        0x80000000.toInt(), 139,
        0x80000000.toInt(), 32905,
        0x80000000.toInt(), 32771,
        0x80000000.toInt(), 32770,
        0x80000000.toInt(), 128,
        0, 32778,
        0x80000000.toInt(), 0x8000000a.toInt(),
        0x80000000.toInt(), 0x80008081.toInt(),
        0x80000000.toInt(), 32896,
        0, 0x80000001.toInt(),
        0x80000000.toInt(), 0x80008008.toInt()
    )

    private val V = intArrayOf(10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1)
    private val W_ROT = intArrayOf(1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44)
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    private fun copyWord(src: IntArray, srcIdx: Int, dst: IntArray, dstIdx: Int) {
        dst[2 * dstIdx] = src[2 * srcIdx]
        dst[2 * dstIdx + 1] = src[2 * srcIdx + 1]
    }

    private fun theta(a: IntArray, c: IntArray, dOut: IntArray, w: IntArray) {
        for (t in 0 until 5) {
            val n = 2 * t
            c[n] = a[n] xor a[n + 10] xor a[n + 20] xor a[n + 30] xor a[n + 40]
            c[n + 1] = a[n + 1] xor a[n + 11] xor a[n + 21] xor a[n + 31] xor a[n + 41]
        }
        for (t in 0 until 5) {
            copyWord(c, (t + 1) % 5, w, 0)
            val o = w[0]
            val f = w[1]
            w[0] = (o shl 1) or (f ushr 31)
            w[1] = (f shl 1) or (o ushr 31)
            dOut[2 * t] = c[(t + 4) % 5 * 2] xor w[0]
            dOut[2 * t + 1] = c[(t + 4) % 5 * 2 + 1] xor w[1]
            for (r in 0 until 25 step 5) {
                a[(r + t) * 2] = a[(r + t) * 2] xor dOut[2 * t]
                a[(r + t) * 2 + 1] = a[(r + t) * 2 + 1] xor dOut[2 * t + 1]
            }
        }
    }

    private fun rhoPi(a: IntArray, c: IntArray, w: IntArray) {
        copyWord(a, 1, w, 0)
        for (i in 0 until 24) {
            val t = V[i]
            val rot = W_ROT[i]
            copyWord(a, t, c, 0)
            val o = w[0]
            val f = w[1]
            val u = 32 - rot
            val s = if (rot < 32) 0 else 1
            w[s] = (o shl rot) or (f ushr u)
            w[(s + 1) % 2] = (f shl rot) or (o ushr u)
            copyWord(w, 0, a, t)
            copyWord(c, 0, w, 0)
        }
    }

    private fun chi(a: IntArray, c: IntArray) {
        for (t in 0 until 25 step 5) {
            for (n in 0 until 5) copyWord(a, t + n, c, n)
            for (n in 0 until 5) {
                val i = (t + n) * 2
                val o = (n + 1) % 5 * 2
                val f = (n + 2) % 5 * 2
                a[i] = a[i] xor (c[o].inv() and c[f])
                a[i + 1] = a[i + 1] xor (c[o + 1].inv() and c[f + 1])
            }
        }
    }

    private fun iota(a: IntArray, round: Int) {
        a[0] = a[0] xor D[2 * round]
        a[1] = a[1] xor D[2 * round + 1]
    }

    private fun keccakPermutation(state: IntArray, c: IntArray, dOut: IntArray, w: IntArray) {
        for (i in 1 until 24) {
            theta(state, c, dOut, w)
            rhoPi(state, c, w)
            chi(state, c)
            iota(state, i)
        }
    }

    private fun absorbQueue(queue: ByteArray, state: IntArray) {
        for (r in queue.indices step 8) {
            val n = r / 4
            val b7 = queue[r + 7].toInt() and 0xff
            val b6 = queue[r + 6].toInt() and 0xff
            val b5 = queue[r + 5].toInt() and 0xff
            val b4 = queue[r + 4].toInt() and 0xff
            val b3 = queue[r + 3].toInt() and 0xff
            val b2 = queue[r + 2].toInt() and 0xff
            val b1 = queue[r + 1].toInt() and 0xff
            val b0 = queue[r].toInt() and 0xff

            state[n] = state[n] xor ((b7 shl 24) or (b6 shl 16) or (b5 shl 8) or b4)
            state[n + 1] = state[n + 1] xor ((b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0)
        }
    }

    fun solve(challenge: String, salt: String, expireAt: Long, difficulty: Int): Int {
        val u = 136
        val prefix = "${salt}_${expireAt}_"
        val prefixBytes = prefix.toByteArray(StandardCharsets.UTF_8)

        val baseQueue = ByteArray(u)
        System.arraycopy(prefixBytes, 0, baseQueue, 0, prefixBytes.size)
        val baseOffset = prefixBytes.size

        val queue = ByteArray(u)
        val state = IntArray(50)
        val c = IntArray(10)
        val dOut = IntArray(10)
        val w = IntArray(2)
        val hexChars = CharArray(64)

        for (i in 0 until difficulty) {
            val iStr = i.toString()
            val fullLen = baseOffset + iStr.length
            if (fullLen >= u) continue

            System.arraycopy(baseQueue, 0, queue, 0, baseOffset)
            for (j in iStr.indices) {
                queue[baseOffset + j] = iStr[j].code.toByte()
            }
            Arrays.fill(queue, fullLen, u, 0.toByte())
            queue[fullLen] = (queue[fullLen].toInt() or 6).toByte()
            queue[u - 1] = (queue[u - 1].toInt() or 128).toByte()

            Arrays.fill(state, 0)
            absorbQueue(queue, state)
            keccakPermutation(state, c, dOut, w)

            var hexPos = 0
            for (r in 0 until 32 step 8) {
                val n = r / 4
                val h = state[n]
                val l = state[n + 1]
                val b0 = l and 0xff
                val b1 = (l ushr 8) and 0xff
                val b2 = (l ushr 16) and 0xff
                val b3 = (l ushr 24) and 0xff
                val b4 = h and 0xff
                val b5 = (h ushr 8) and 0xff
                val b6 = (h ushr 16) and 0xff
                val b7 = (h ushr 24) and 0xff

                hexChars[hexPos++] = HEX_DIGITS[(b0 ushr 4) and 0x0f]
                hexChars[hexPos++] = HEX_DIGITS[b0 and 0x0f]
                hexChars[hexPos++] = HEX_DIGITS[(b1 ushr 4) and 0x0f]
                hexChars[hexPos++] = HEX_DIGITS[b1 and 0x0f]
                hexChars[hexPos++] = HEX_DIGITS[(b2 ushr 4) and 0x0f]
                hexChars[hexPos++] = HEX_DIGITS[b2 and 0x0f]
                hexChars[hexPos++] = HEX_DIGITS[(b3 ushr 4) and 0x0f]
                hexChars[hexPos++] = HEX_DIGITS[b3 and 0x0f]
                hexChars[hexPos++] = HEX_DIGITS[(b4 ushr 4) and 0x0f]
                hexChars[hexPos++] = HEX_DIGITS[b4 and 0x0f]
                hexChars[hexPos++] = HEX_DIGITS[(b5 ushr 4) and 0x0f]
                hexChars[hexPos++] = HEX_DIGITS[b5 and 0x0f]
                hexChars[hexPos++] = HEX_DIGITS[(b6 ushr 4) and 0x0f]
                hexChars[hexPos++] = HEX_DIGITS[b6 and 0x0f]
                hexChars[hexPos++] = HEX_DIGITS[(b7 ushr 4) and 0x0f]
                hexChars[hexPos++] = HEX_DIGITS[b7 and 0x0f]
            }

            if (String(hexChars) == challenge) {
                return i
            }
        }
        return -1
    }
}

