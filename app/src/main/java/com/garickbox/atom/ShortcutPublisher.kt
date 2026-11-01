package com.garickbox.atom

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.mesh.core.InAppLogger

object ShortcutPublisher {
    fun update(ctx: Context, peers: List<AtomController.KnownP2pPeer>) {
        try {
            val shortcuts = peers.take(3).mapIndexed { idx, p ->
                // B83: Direct Share — intent идёт на ShareTargetActivity, систему
                // дополняет EXTRA_STREAM (share source). peer передаём через extras.
                val intent = Intent(ctx, ShareTargetActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "*/*"
                    putExtra("atom_direct_peer_mac", p.mac)
                    putExtra("atom_direct_peer_name", p.name)
                }
                ShortcutInfoCompat.Builder(ctx, "peer_" + p.mac.takeLast(8).replace(":", ""))
                    .setShortLabel(p.name.take(10))
                    .setLongLabel("Отправить на " + p.name.take(20))
                    .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
                    .setIntent(intent)
                    .setRank(idx)
                    // B83: Direct Share — категория + long-lived
                    .setCategories(setOf("com.garickbox.atom.share_target"))
                    .setLongLived(true)
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(ctx, shortcuts)
            InAppLogger.d("Shortcut", "published ${shortcuts.size} peers")
        } catch (e: Exception) {
            InAppLogger.d("Shortcut", "publish error: ${e.message}")
        }
    }
}
