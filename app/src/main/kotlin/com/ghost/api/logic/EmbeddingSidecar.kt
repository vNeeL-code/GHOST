package com.ghost.api.logic

import android.content.Context
import android.graphics.Bitmap
import com.ghost.api.Constants
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.sqrt

/**
 * GHOST Semantic Embedding Sidecar (Matryoshka 256-dim vector engine).
 *
 * Architecture:
 * - Default backend ([LocalSemanticHashBackend]): zero-dependency, 0 MB extra RAM, sub-ms
 *   256-dim L2-normalized word + bigram + char-ngram vector engine active on ALL tiers (4GB–24GB).
 * - Hardware-tiered EmbeddingGemma upgrade path ([Constants.resolveEmbeddingTier]):
 *   - 4GB–12GB: EmbeddingGemma Text 270M (~191 MB RAM) — semantic intent routing + memory recall
 *   - 16GB–24GB: EmbeddingGemma 2 Multimodal (~567 MB RAM) — text + device image sorting + local wiki
 * - Vectors are strictly scoped by [EmbeddingBackend.modelTag] in Room DB so checkpoints never collide.
 */
object EmbeddingSidecar {

    const val DEFAULT_DIMS = 256

    interface EmbeddingBackend {
        val modelTag: String
        val dimensions: Int
        val supportsVision: Boolean
        fun embedText(text: String): FloatArray
        fun embedImage(bitmap: Bitmap): FloatArray? = null
    }

    /**
     * Fast, zero-dependency, deterministic 256-dim L2-normalized semantic feature hash embedder.
     * Combines word unigrams (weight 2.0), word bigrams (weight 1.5), and character 3-grams (weight 0.8)
     * so paraphrases, morphological variants, and typos still land in the same cosine neighborhood.
     */
    object LocalSemanticHashBackend : EmbeddingBackend {
        override val modelTag: String = "hash-256-v1"
        override val dimensions: Int = DEFAULT_DIMS
        override val supportsVision: Boolean = false

        private val stopWords = setOf(
            "the", "a", "an", "is", "are", "was", "were", "to", "of", "in", "on", "at",
            "for", "it", "this", "that", "and", "or", "be", "do", "my", "me", "i", "you"
        )

        override fun embedText(text: String): FloatArray {
            val vec = FloatArray(dimensions)
            val normalized = text.lowercase(Locale.ROOT)
                .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (normalized.isEmpty()) return vec

            val words = normalized.split(" ").filter { it.isNotBlank() }

            // 1. Word unigrams (boost non-stopwords)
            for (w in words) {
                val weight = if (w in stopWords) 0.35f else 2.2f
                accumulateHash(vec, "w:$w", weight)
                // Lightweight stem prefix for morphological matching (e.g. "charging"/"charge", "overheating"/"heat")
                if (w.length >= 5) {
                    accumulateHash(vec, "s:${w.take(5)}", 1.1f)
                }
                // 2. Character 3-grams for typo/morphology tolerance
                if (w.length >= 4 && w !in stopWords) {
                    val padded = "<$w>"
                    for (i in 0..padded.length - 3) {
                        accumulateHash(vec, "c:${padded.substring(i, i + 3)}", 0.55f)
                    }
                }
            }

            // 3. Word bigrams for phrase semantics
            for (i in 0 until words.size - 1) {
                accumulateHash(vec, "b:${words[i]}_${words[i + 1]}", 1.6f)
            }

            return l2Normalize(vec)
        }

        private fun accumulateHash(vec: FloatArray, feature: String, weight: Float) {
            // FNV-1a 32-bit hash for deterministic, cross-JVM/Android identical vectors
            var h = 0x811c9dc5.toInt()
            for (i in feature.indices) {
                h = h xor feature[i].code
                h *= 0x01000193
            }
            val bucket = (h and 0x7FFFFFFF) % dimensions
            val sign = if ((h ushr 31) == 0) 1f else -1f
            vec[bucket] += sign * weight
        }
    }

    /**
     * Native LiteRT + SentencePiece backend powered by Google AI Edge localagents-rag
     * (EmbeddingGemma-300M seq256 mixed-precision, ~179 MB, Matryoshka-truncated to 256-dim).
     * Runs on CPU (XNNPACK) by default so the GPU delegate stays 100% dedicated to Gemma 4 E4B/E2B.
     */
    class GemmaLiteRtEmbeddingBackend(
        modelPath: String,
        tokenizerPath: String,
        useGpu: Boolean = false
    ) : EmbeddingBackend {
        override val modelTag: String = "embeddinggemma-300m-seq256-d256"
        override val dimensions: Int = DEFAULT_DIMS
        override val supportsVision: Boolean = false

        private val lock = Any()
        private val nativeModel = com.google.ai.edge.localagents.rag.models.GemmaEmbeddingModel(
            modelPath,
            tokenizerPath,
            useGpu
        )

        override fun embedText(text: String): FloatArray {
            if (text.isBlank()) return FloatArray(dimensions)
            return try {
                val embedData = com.google.ai.edge.localagents.rag.models.EmbedData.builder<String>()
                    .setData(text.take(1024))
                    .setTask(com.google.ai.edge.localagents.rag.models.EmbedData.TaskType.RETRIEVAL_QUERY)
                    .build()
                val req = com.google.ai.edge.localagents.rag.models.EmbeddingRequest.create(listOf(embedData))
                val fullList = synchronized(lock) {
                    nativeModel.getEmbeddings(req).get()
                }
                if (fullList.isNullOrEmpty()) {
                    LocalSemanticHashBackend.embedText(text)
                } else {
                    // Matryoshka truncation (768 -> 256 dims) + L2 renormalization
                    val truncated = FloatArray(dimensions) { idx ->
                        if (idx < fullList.size) fullList[idx] else 0f
                    }
                    l2Normalize(truncated)
                }
            } catch (e: Exception) {
                Timber.w(e, "EmbeddingSidecar: GemmaLiteRtEmbeddingBackend inference failed, using hash fallback")
                LocalSemanticHashBackend.embedText(text)
            }
        }
    }

    /**
     * Native LiteRT + SentencePiece backend powered by Google AI Edge localagents-rag
     * (Gecko-110M-en seq256 dynamic int8, ~109 MB, Apache 2.0 ungated, 768-dim).
     * Runs on CPU (XNNPACK: ~11 ms init, ~126 MB RSS) so GPU stays 100% dedicated to Gemma 4.
     */
    class GeckoLiteRtEmbeddingBackend(
        modelPath: String,
        tokenizerPath: String,
        useGpu: Boolean = false
    ) : EmbeddingBackend {
        override val modelTag: String = "gecko-110m-en-seq256-d768"
        override val dimensions: Int = 768
        override val supportsVision: Boolean = false

        private val lock = Any()
        private val nativeModel = com.google.ai.edge.localagents.rag.models.GeckoEmbeddingModel(
            modelPath,
            java.util.Optional.of(tokenizerPath),
            useGpu
        )

        override fun embedText(text: String): FloatArray {
            if (text.isBlank()) return FloatArray(dimensions)
            return try {
                val embedData = com.google.ai.edge.localagents.rag.models.EmbedData.builder<String>()
                    .setData(text.take(1024))
                    .setTask(com.google.ai.edge.localagents.rag.models.EmbedData.TaskType.RETRIEVAL_QUERY)
                    .build()
                val req = com.google.ai.edge.localagents.rag.models.EmbeddingRequest.create(listOf(embedData))
                val fullList = synchronized(lock) {
                    nativeModel.getEmbeddings(req).get()
                }
                if (fullList.isNullOrEmpty()) {
                    FloatArray(dimensions)
                } else {
                    val vec = FloatArray(fullList.size) { idx -> fullList[idx] }
                    l2Normalize(vec)
                }
            } catch (e: Exception) {
                Timber.w(e, "EmbeddingSidecar: GeckoLiteRtEmbeddingBackend inference failed")
                FloatArray(dimensions)
            }
        }
    }

    @Volatile
    var activeBackend: EmbeddingBackend = LocalSemanticHashBackend

    @Volatile
    var isDownloadingSidecar: Boolean = false
        private set

    @Volatile
    var lastErrorMessage: String? = null
        private set

    @Volatile
    private var cachedPrototypesModelTag: String? = null

    @Volatile
    private var cachedIntentPrototypes: Map<EchoSpineEngine.EchoIntent, List<FloatArray>> = emptyMap()

    fun isNeuralSidecarActive(): Boolean = activeBackend !is LocalSemanticHashBackend

    fun activeSidecarLabel(): String = when (activeBackend) {
        is GeckoLiteRtEmbeddingBackend -> "Gecko-110M (768-dim CPU XNNPACK)"
        is GemmaLiteRtEmbeddingBackend -> "EmbeddingGemma-300M (256-dim CPU XNNPACK)"
        else -> "Hash-256 fallback (0 MB)"
    }

    fun getEmbeddingModelsDir(context: Context): File {
        val dir = context.getExternalFilesDir("models/embeddings")
            ?: File(context.filesDir, "models/embeddings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Checks whether a local neural sidecar model file (Gecko-110M or EmbeddingGemma-300M) is staged on disk.
     * Returns the discovered file (if any) without blocking startup.
     */
    fun discoverEmbeddingModelFile(context: Context): File? {
        val tier = Constants.resolveEmbeddingTier(context)
        val dir = getEmbeddingModelsDir(context)
        if (!dir.exists()) return null
        val candidates = dir.listFiles()?.filter {
            it.isFile && (it.name.endsWith(".tflite") || it.name.endsWith(".litertlm")) &&
                it.length() >= Constants.EMBEDDING_GECKO_MODEL_MIN_BYTES
        } ?: return null
        val preferred = if (tier == "MULTIMODAL_567M") {
            candidates.firstOrNull { it.name.contains("multimodal", ignoreCase = true) || it.name.contains("omni", ignoreCase = true) }
        } else {
            candidates.firstOrNull { it.name.contains("embeddinggemma", ignoreCase = true) || it.name.contains("gecko", ignoreCase = true) }
        }
        val found = preferred ?: candidates.firstOrNull()
        if (found != null) {
            Timber.i("EmbeddingSidecar: Discovered sidecar weights (${found.name}) for tier $tier")
        }
        return found
    }

    /**
     * Auto-mounts Gecko-110M or EmbeddingGemma-300M if both `.tflite` weights and matching tokenizer exist on disk.
     * Safe to call on a background IO thread during service startup.
     */
    fun initializeIfAvailable(context: Context): Boolean {
        if (isNeuralSidecarActive()) return true
        val dir = getEmbeddingModelsDir(context)

        // 1. Prefer EmbeddingGemma-300M if manually staged with its 4.7 MB tokenizer
        val gemmaModel = File(dir, Constants.EMBEDDING_MODEL_FILENAME_TEXT)
        val gemmaTok = File(dir, Constants.EMBEDDING_TOKENIZER_FILENAME)
        if (gemmaModel.exists() && gemmaModel.length() >= Constants.EMBEDDING_MODEL_MIN_BYTES &&
            gemmaTok.exists() && gemmaTok.length() >= Constants.EMBEDDING_TOKENIZER_MIN_BYTES
        ) {
            return try {
                val backend = GemmaLiteRtEmbeddingBackend(
                    modelPath = gemmaModel.absolutePath,
                    tokenizerPath = gemmaTok.absolutePath,
                    useGpu = false
                )
                activeBackend = backend
                cachedPrototypesModelTag = null
                Timber.i("🧠 EmbeddingSidecar: Mounted native ${backend.modelTag} (${gemmaModel.name})")
                true
            } catch (e: Throwable) {
                Timber.e(e, "EmbeddingSidecar: Failed to mount GemmaLiteRtEmbeddingBackend")
                lastErrorMessage = "Mount error: ${e.message}"
                false
            }
        }

        // 2. Default ungated sidecar: Gecko-110M-en (Apache 2.0)
        val geckoModel = File(dir, Constants.EMBEDDING_GECKO_MODEL_FILENAME)
        val geckoTok = File(dir, Constants.EMBEDDING_GECKO_TOKENIZER_FILENAME)
        if (geckoModel.exists() && geckoModel.length() >= Constants.EMBEDDING_GECKO_MODEL_MIN_BYTES &&
            geckoTok.exists() && geckoTok.length() >= Constants.EMBEDDING_GECKO_TOKENIZER_MIN_BYTES
        ) {
            return try {
                val backend = GeckoLiteRtEmbeddingBackend(
                    modelPath = geckoModel.absolutePath,
                    tokenizerPath = geckoTok.absolutePath,
                    useGpu = false
                )
                activeBackend = backend
                cachedPrototypesModelTag = null
                Timber.i("🧠 EmbeddingSidecar: Mounted native ${backend.modelTag} (${geckoModel.name})")
                true
            } catch (e: Throwable) {
                Timber.e(e, "EmbeddingSidecar: Failed to mount GeckoLiteRtEmbeddingBackend")
                lastErrorMessage = "Mount error: ${e.message}"
                false
            }
        }

        return false
    }

    /**
     * Downloads ungated Gecko-110M-en (109 MB, Apache 2.0) + SentencePiece tokenizer (0.76 MB) atomically
     * and hot-swaps [activeBackend] live without restarting GHOST.
     */
    suspend fun downloadAndActivateSidecar(
        context: Context,
        onProgress: (String) -> Unit = {}
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (isDownloadingSidecar) return@withContext false
        isDownloadingSidecar = true
        lastErrorMessage = null
        try {
            val dir = getEmbeddingModelsDir(context)
            val hfToken = try { HFTokenManager(context).getToken() } catch (_: Exception) { null }

            val tokFile = File(dir, Constants.EMBEDDING_GECKO_TOKENIZER_FILENAME)
            if (!tokFile.exists() || tokFile.length() < Constants.EMBEDDING_GECKO_TOKENIZER_MIN_BYTES) {
                onProgress("Downloading Gecko tokenizer (0.8 MB)...")
                val tokErr = downloadFileAtomic(
                    urlStr = Constants.EMBEDDING_GECKO_TOKENIZER_URL,
                    destFile = tokFile,
                    minBytes = Constants.EMBEDDING_GECKO_TOKENIZER_MIN_BYTES,
                    hfToken = hfToken,
                    onPct = { pct -> onProgress("Tokenizer: $pct%") }
                )
                if (tokErr != null) {
                    lastErrorMessage = tokErr
                    onProgress("Tokenizer failed: $tokErr")
                    return@withContext false
                }
            }

            val modelFile = File(dir, Constants.EMBEDDING_GECKO_MODEL_FILENAME)
            if (!modelFile.exists() || modelFile.length() < Constants.EMBEDDING_GECKO_MODEL_MIN_BYTES) {
                onProgress("Downloading Gecko-110M (109 MB)...")
                val modelErr = downloadFileAtomic(
                    urlStr = Constants.EMBEDDING_GECKO_MODEL_URL,
                    destFile = modelFile,
                    minBytes = Constants.EMBEDDING_GECKO_MODEL_MIN_BYTES,
                    hfToken = hfToken,
                    onPct = { pct -> onProgress("Gecko-110M: $pct%") }
                )
                if (modelErr != null) {
                    lastErrorMessage = modelErr
                    onProgress("Download failed: $modelErr")
                    return@withContext false
                }
            }

            onProgress("Mounting Gecko-110M...")
            val mounted = initializeIfAvailable(context)
            onProgress(if (mounted) "Gecko-110M active" else (lastErrorMessage ?: "Mount failed"))
            mounted
        } catch (e: Exception) {
            Timber.e(e, "EmbeddingSidecar: Sidecar download failed")
            lastErrorMessage = e.message ?: e.javaClass.simpleName
            onProgress("Download failed: $lastErrorMessage")
            false
        } finally {
            isDownloadingSidecar = false
        }
    }

    /**
     * Atomically downloads [urlStr] to [destFile] with explicit cross-host redirect handling
     * (strips Authorization header when redirected from huggingface.co to CDN LFS hosts).
     * Returns `null` on success, or a concise error string on failure.
     */
    private fun downloadFileAtomic(
        urlStr: String,
        destFile: File,
        minBytes: Long,
        hfToken: String?,
        onPct: (Int) -> Unit
    ): String? {
        val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp")
        var conn: java.net.HttpURLConnection? = null
        return try {
            var currentUrl = java.net.URL(urlStr)
            var redirects = 0
            var code: Int
            while (true) {
                conn?.disconnect()
                conn = (currentUrl.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 45_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "GHOST-Android/4.40")
                    val host = currentUrl.host.lowercase(Locale.ROOT)
                    if (!hfToken.isNullOrBlank() && (host == "huggingface.co" || host.endsWith(".huggingface.co"))) {
                        setRequestProperty("Authorization", "Bearer $hfToken")
                    }
                }
                code = conn.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val loc = conn.getHeaderField("Location") ?: return "HTTP $code (missing Location)"
                    if (++redirects > 6) return "Too many redirects"
                    val nextUrl = java.net.URL(currentUrl, loc)
                    if (!nextUrl.protocol.equals("https", ignoreCase = true)) {
                        return "Blocked non-HTTPS redirect"
                    }
                    currentUrl = nextUrl
                    continue
                }
                break
            }
            if (code !in 200..299) {
                Timber.w("EmbeddingSidecar: HTTP $code for $urlStr")
                return "HTTP $code"
            }
            val total = conn.contentLengthLong
            var downloaded = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                tmpFile.outputStream().use { output ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct && pct % 5 == 0) {
                                lastPct = pct
                                onPct(pct)
                            }
                        }
                    }
                }
            }
            if (tmpFile.length() < minBytes) {
                val actualKb = tmpFile.length() / 1024
                tmpFile.delete()
                return "Incomplete file (${actualKb} KB)"
            }
            if (destFile.exists()) destFile.delete()
            if (tmpFile.renameTo(destFile)) null else "Rename failed"
        } catch (e: Exception) {
            Timber.e(e, "EmbeddingSidecar: Failed downloading $urlStr")
            if (tmpFile.exists()) tmpFile.delete()
            e.message ?: e.javaClass.simpleName
        } finally {
            conn?.disconnect()
        }
    }

    fun embedText(text: String): FloatArray = activeBackend.embedText(text)

    fun embedImage(bitmap: Bitmap): FloatArray? = activeBackend.embedImage(bitmap)

    fun l2Normalize(vec: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq).toFloat()
        if (norm > 1e-6f) {
            for (i in vec.indices) vec[i] /= norm
        }
        return vec
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    fun packFloatArray(vec: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vec) buf.putFloat(v)
        return buf.array()
    }

    fun unpackFloatArray(blob: ByteArray): FloatArray {
        val count = blob.size / 4
        val out = FloatArray(count)
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) out[i] = buf.float
        return out
    }

    private val INTENT_PROTOTYPE_PHRASES: Map<EchoSpineEngine.EchoIntent, List<String>> = mapOf(
        EchoSpineEngine.EchoIntent.TIME_AND_DATE to listOf(
            "what time is it right now",
            "what day of the week is today",
            "tell me today's date and weekday",
            "check the clock"
        ),
        EchoSpineEngine.EchoIntent.BATTERY_STATUS to listOf(
            "how much battery juice is left",
            "check power level and charging status",
            "how many volts do we have left",
            "is the phone charging or low battery"
        ),
        EchoSpineEngine.EchoIntent.THERMAL_STATUS to listOf(
            "how hot is the phone getting",
            "check cpu temperature and thermals",
            "are we overheating or running warm"
        ),
        EchoSpineEngine.EchoIntent.SYSTEM_STATUS to listOf(
            "give me a full system status diagnostics report",
            "check device vitals ram memory and telemetry",
            "how is the chassis holding up"
        ),
        EchoSpineEngine.EchoIntent.GREETING to listOf(
            "hey there good morning",
            "hello are you awake",
            "good evening checking in"
        ),
        EchoSpineEngine.EchoIntent.WHO_ARE_YOU to listOf(
            "who are you and what is echo mode",
            "why is the cortex offline right now"
        ),
        EchoSpineEngine.EchoIntent.HELP to listOf(
            "what reflexes and commands can you run in echo mode",
            "show me what you can do offline"
        ),
        EchoSpineEngine.EchoIntent.THANKS to listOf(
            "thank you very much appreciated",
            "cheers nice job"
        )
    )

    private fun getIntentPrototypes(): Map<EchoSpineEngine.EchoIntent, List<FloatArray>> {
        val backend = activeBackend
        if (cachedPrototypesModelTag == backend.modelTag && cachedIntentPrototypes.isNotEmpty()) {
            return cachedIntentPrototypes
        }
        val built = INTENT_PROTOTYPE_PHRASES.mapValues { (_, phrases) ->
            phrases.map { backend.embedText(it) }
        }
        cachedIntentPrototypes = built
        cachedPrototypesModelTag = backend.modelTag
        return built
    }

    /**
     * Hybrid Lexical + Semantic Cosine Classifier for [EchoSpineEngine].
     * First checks fast exact lexical rules; if missed, embeds the input and compares
     * against prototype vectors keyed to [activeBackend.modelTag].
     */
    val SemanticEchoClassifier = EchoSpineEngine.EchoIntentClassifier { clean ->
        EchoSpineEngine.LexicalEchoClassifier.classify(clean)?.let { return@EchoIntentClassifier it }
        if (clean.length < 3) return@EchoIntentClassifier null

        val queryVec = activeBackend.embedText(clean)
        var bestIntent: EchoSpineEngine.EchoIntent? = null
        var bestScore = 0f

        for ((intent, prototypes) in getIntentPrototypes()) {
            for (proto in prototypes) {
                val score = cosineSimilarity(queryVec, proto)
                if (score > bestScore) {
                    bestScore = score
                    bestIntent = intent
                }
            }
        }
        if (bestScore >= 0.30f) bestIntent else null
    }
}
