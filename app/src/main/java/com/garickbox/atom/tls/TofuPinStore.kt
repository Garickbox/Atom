package com.garickbox.atom.tls

import android.content.Context
import com.mesh.core.InAppLogger

/** P2: TOFU по SHA-256 SPKI (HPKP RFC 7469). commit() для security-critical. */
class TofuPinStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("atom_tofu_pins", Context.MODE_PRIVATE)

    fun get(peerId: String): String? = sp.getString(peerId, null)
    fun put(peerId: String, fp: String) {
        if (!sp.edit().putString(peerId, fp).commit()) {
            InAppLogger.err("TOFU", "failed to persist pin for $peerId")
        }
    }
    fun clear(peerId: String) { sp.edit().remove(peerId).commit() }
}
