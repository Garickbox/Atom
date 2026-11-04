package com.garickbox.atom

data class NfcHandover(val ssid: String, val passphrase: String, val ip: String) {
    fun toUri(): String = "atom://wfd?ssid=${enc(ssid)}&pwd=${enc(passphrase)}&ip=$ip"
    companion object {
        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = java.net.URLDecoder.decode(s, "UTF-8")
        fun parse(uri: String): NfcHandover? {
            if (!uri.startsWith("atom://wfd?")) return null
            val q = uri.substringAfter("?", "")
            val map = q.split("&").mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }.toMap()
            val ssid = map["ssid"]?.let { dec(it) } ?: return null
            val pwd = map["pwd"]?.let { dec(it) } ?: return null
            val ip = map["ip"] ?: return null
            return NfcHandover(ssid, pwd, ip)
        }
    }
}
