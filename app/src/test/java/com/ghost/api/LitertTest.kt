package com.ghost.api

import org.junit.Test
import com.google.ai.edge.litertlm.EngineConfig

class LitertTest {
    @Test
    fun dumpEngineConfig() {
        println("--- DUMPING EngineConfig FIELDS ---")
        EngineConfig::class.java.declaredFields.forEach { println("Field: ${it.name} - ${it.type.name}") }
    }
}
