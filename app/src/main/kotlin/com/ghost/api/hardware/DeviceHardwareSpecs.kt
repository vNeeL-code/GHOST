package com.ghost.api.hardware

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.ghost.api.logic.ContextManager
import org.json.JSONObject
import java.util.Locale

/**
 * Systematic hardware specification extractor.
 * Reads low-level system properties across Android OEMs (Nubia/ZTE, Samsung, Xiaomi, Pixel)
 * to ground both local Gemma and outbound cloud peer consultations in exact physical reality.
 */
object DeviceHardwareSpecs {

    fun getSystemProp(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java)
            (getMethod.invoke(null, key) as? String)?.trim().orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Resolves the commercial consumer marketing name of the device.
     * e.g., "REDMAGIC 10 Air" instead of cryptic factory model code "NX779J".
     */
    fun resolveMarketingName(): String {
        val zteName = getSystemProp("ro.vendor.product.ztename")
        if (zteName.isNotBlank()) return zteName

        val marketName = getSystemProp("ro.product.marketname")
        if (marketName.isNotBlank()) return marketName

        val oemMarket = getSystemProp("ro.config.marketing_name")
        if (oemMarket.isNotBlank()) return oemMarket

        val manufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    /**
     * Resolves the marketing name of the mobile SoC.
     * e.g., "Qualcomm Snapdragon 8 Gen 3 (SM8650)" instead of raw board "pineapple".
     */
    fun resolveChipsetName(): String {
        val socModel = getSystemProp("ro.soc.model").uppercase(Locale.US)
        val platform = getSystemProp("ro.board.platform").lowercase(Locale.US)
        val hardware = Build.HARDWARE

        val marketingChip = when {
            socModel == "SM8750" || platform == "sun" -> "Qualcomm Snapdragon 8 Elite"
            socModel == "SM8650" || platform == "pineapple" -> "Qualcomm Snapdragon 8 Gen 3"
            socModel == "SM8550" || platform == "kalama" -> "Qualcomm Snapdragon 8 Gen 2"
            socModel == "SM8475" || platform == "cape" -> "Qualcomm Snapdragon 8+ Gen 1"
            socModel == "SM8450" || platform == "taro" -> "Qualcomm Snapdragon 8 Gen 1"
            socModel.startsWith("SM") -> "Qualcomm Snapdragon ($socModel)"
            hardware.contains("tensor", ignoreCase = true) || platform.contains("zuma") -> "Google Tensor"
            hardware.contains("mt", ignoreCase = true) || platform.contains("mt") -> "MediaTek Dimensity"
            hardware.contains("exynos", ignoreCase = true) -> "Samsung Exynos"
            socModel.isNotBlank() -> socModel
            else -> hardware
        }
        return if (socModel.isNotBlank() && !marketingChip.contains(socModel)) "$marketingChip ($socModel)" else marketingChip
    }

    /**
     * Detects thermal dissipation architecture based on vendor traits.
     */
    fun resolveThermalArchitecture(): String {
        val hasFan = getSystemProp("ro.vendor.feature.zte_feature_fan") == "true" ||
                     getSystemProp("ro.vendor.feature.fan") == "true"
        return if (hasFan) {
            "Active Centrifugal Fan & Liquid Metal Vapor Chamber"
        } else {
            "Passive Composite Liquid Metal & High-Conductivity Vapor Chamber"
        }
    }

    /**
     * Returns total RAM (GB) and total internal storage (GB).
     */
    fun getMemoryStats(context: Context): Pair<String, String> {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)
        val totalRamGb = String.format(Locale.US, "%.1f", memInfo.totalMem.toDouble() / (1024 * 1024 * 1024))

        val statFs = StatFs(Environment.getDataDirectory().path)
        val totalStorageGb = String.format(Locale.US, "%.0f", statFs.totalBytes.toDouble() / (1024 * 1024 * 1024))
        return Pair(totalRamGb, totalStorageGb)
    }

    /**
     * Builds the standard systematic A2A (Agent-to-Agent) metadata packet
     * for outbound peer AI consultations (Gemini, DeepSeek, Claude, etc.).
     */
    fun buildPeerDispatchPacket(
        context: Context,
        recipientCallsign: String,
        recipientOrg: String
    ): JSONObject {
        val agentCallSign = ContextManager.resolveDeviceCallSign(context)
        val operatorAvatar = try {
            context.getSharedPreferences(com.ghost.api.Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(com.ghost.api.Constants.PREF_OPERATOR_AVATAR, "🦑") ?: "🦑"
        } catch (_: Exception) { "🦑" }

        val marketingName = resolveMarketingName()
        val modelCode = Build.MODEL
        val chipsetName = resolveChipsetName()

        return JSONObject().apply {
            put("protocol", "GHOST-A2A/UCF-1.0")
            put("sender", JSONObject().apply {
                put("agent", "✧ $agentCallSign")
                put("framework", "GHOST")
                put("local_model", "gemma-4-E2B-it")
                put("platform", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                put("device", if (marketingName != modelCode) "$marketingName ($modelCode)" else marketingName)
                put("soc", chipsetName)
                put("thermal", resolveThermalArchitecture())
                put("operator", "Δ $operatorAvatar")
            })
            put("recipient", "$recipientCallsign ($recipientOrg)")
            put("timestamp", java.time.ZonedDateTime.now().format(
                java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
            ))
        }
    }
}
