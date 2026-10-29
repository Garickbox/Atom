package com.mesh.core

interface MeshLogger {
    fun d(tag: String, msg: String)
}

object AndroidMeshLogger : MeshLogger {
    override fun d(tag: String, msg: String) = InAppLogger.d(tag, msg)
}
