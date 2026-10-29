package com.mesh.core

/**
 * Мост из com.mesh.core в com.garickbox.atom без циклических зависимостей.
 * Регистрируется из AtomApp.init() при старте приложения.
 */
object MeshBridge {

    @Volatile
    private var transferActiveProvider: (() -> Boolean)? = null

    fun register(provider: () -> Boolean) {
        transferActiveProvider = provider
        InAppLogger.d("MeshBridge", "registered transferActiveProvider")
    }

    fun isTransferActive(): Boolean {
        return try {
            transferActiveProvider?.invoke() ?: false
        } catch (_: Exception) {
            false
        }
    }
}
