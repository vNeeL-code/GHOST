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

        val semName = getSystemProp("ro.product.model.name")
        if (semName.isNotBlank()) return semName

        val manufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        val model = Build.MODEL

        // Samsung Galaxy model code resolution fallback if carrier firmware stripped market properties
        if (manufacturer.equals("Samsung", ignoreCase = true)) {
            val galaxyName = when {
                model.startsWith("SM-G991", ignoreCase = true) -> "Galaxy S21 5G"
                model.startsWith("SM-G996", ignoreCase = true) -> "Galaxy S21+ 5G"
                model.startsWith("SM-G998", ignoreCase = true) -> "Galaxy S21 Ultra 5G"
                model.startsWith("SM-G990", ignoreCase = true) -> "Galaxy S21 FE 5G"
                model.startsWith("SM-S901", ignoreCase = true) -> "Galaxy S22"
                model.startsWith("SM-S908", ignoreCase = true) -> "Galaxy S22 Ultra"
                model.startsWith("SM-S911", ignoreCase = true) -> "Galaxy S23"
                model.startsWith("SM-S918", ignoreCase = true) -> "Galaxy S23 Ultra"
                model.startsWith("SM-S921", ignoreCase = true) -> "Galaxy S24"
                model.startsWith("SM-S928", ignoreCase = true) -> "Galaxy S24 Ultra"
                else -> null
            }
            if (galaxyName != null) return "Samsung $galaxyName"
        }

        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    /**
     * Resolves the marketing name of the mobile SoC.
     * Maps Qualcomm Snapdragon, Samsung Exynos, Google Tensor, and MediaTek Dimensity platforms.
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
            socModel == "SM8350" || platform == "lahaina" -> "Qualcomm Snapdragon 888"
            socModel == "SM8250" || platform == "kona" -> "Qualcomm Snapdragon 865"
            platform.contains("exynos2400") || socModel.contains("2400") -> "Samsung Exynos 2400"
            platform.contains("exynos2200") || socModel.contains("2200") -> "Samsung Exynos 2200"
            platform.contains("exynos2100") || socModel.contains("2100") || hardware.contains("exynos2100") -> "Samsung Exynos 2100"
            platform.contains("exynos990") || socModel.contains("990") -> "Samsung Exynos 990"
            hardware.contains("exynos", ignoreCase = true) || platform.contains("exynos") -> "Samsung Exynos ($platform)"
            hardware.contains("tensor", ignoreCase = true) || platform.contains("zuma") -> "Google Tensor"
            hardware.contains("mt", ignoreCase = true) || platform.contains("mt") -> "MediaTek Dimensity"
            socModel.startsWith("SM") -> "Qualcomm Snapdragon ($socModel)"
            socModel.isNotBlank() -> socModel
            else -> hardware
        }
        return if (socModel.isNotBlank() && !marketingChip.contains(socModel)) "$marketingChip ($socModel)" else marketingChip
    }

    /**
     * Detects thermal dissipation architecture based on vendor traits.
     * Distinguishes gaming liquid metal from standard commercial graphite dissipation.
     */
    fun resolveThermalArchitecture(): String {
        val brand = Build.BRAND.lowercase(Locale.US)
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.US)
        val isRedMagic = brand.contains("redmagic") || manufacturer.contains("nubia") ||
                         getSystemProp("ro.vendor.product.ztename").contains("redmagic", ignoreCase = true)
        val hasFan = getSystemProp("ro.vendor.feature.zte_feature_fan") == "true" ||
                     getSystemProp("ro.vendor.feature.fan") == "true"

        return when {
            isRedMagic && hasFan -> "Active Centrifugal Fan & Liquid Metal Vapor Chamber"
            isRedMagic -> "Passive Composite Liquid Metal & High-Conductivity Vapor Chamber"
            manufacturer.contains("samsung") -> "Passive Multi-Layer Graphite & Copper Heat Spreader"
            manufacturer.contains("google") -> "Passive Multi-Layer Graphite & Aluminum Chassis Heat Sink"
            brand.contains("rog") || brand.contains("asus") -> "Passive 3D Vapor Chamber & Multi-Layer Graphite"
            else -> "Passive Multi-Layer Graphite & Thermal Diffusion"
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
