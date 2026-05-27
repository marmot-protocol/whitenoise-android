package dev.ipf.darkmatter.core

object ForensicsExportFileName {
    fun build(groupIdHex: String, mode: String, nowMillis: Long): String {
        val group = groupIdHex
            .filter { it.isLetterOrDigit() }
            .take(12)
            .ifBlank { "group" }
        val modeSlug = mode
            .filter { it.isLetterOrDigit() || it == '-' }
            .ifBlank { "dump" }
        return "darkmatter-forensics-$modeSlug-$group-$nowMillis.json"
    }
}
