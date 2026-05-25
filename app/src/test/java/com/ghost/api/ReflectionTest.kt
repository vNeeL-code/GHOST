package com.ghost.api

import org.junit.Test
import com.google.ai.edge.litertlm.EngineConfig

class ReflectionTest {
    @Test
    fun testEngineConfig() {
        val clazz = EngineConfig::class.java
        println("=== Constructors ===")
        clazz.constructors.forEach { c ->
            println(c)
        }
        println("=== Methods ===")
        clazz.methods.forEach { m ->
            if (m.declaringClass == clazz) {
                println(m)
            }
        }
        println("=== Fields ===")
        clazz.declaredFields.forEach { f ->
            println(f)
        }
    }
}
