package com.garickbox.atom.tls

import com.mesh.core.InAppLogger
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** P2: проверка SPKI. expectedSpki — из UDP-сигналинга (может быть null → unauthenticated TOFU). */
class TofuTrustManager(
    private val pins: TofuPinStore,
    private val peerId: String,
    private val expectedSpki: String?
) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = check(chain)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = check(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun check(chain: Array<X509Certificate>) {
        require(chain.isNotEmpty()) { "empty cert chain" }
        val observed = MessageDigest.getInstance("SHA-256")
            .digest(chain[0].publicKey.encoded).hex()
        val pinned = pins.get(peerId)
        when {
            pinned == null && expectedSpki != null -> {
                if (!observed.equals(expectedSpki, ignoreCase = true)) {
                    InAppLogger.err("TOFU", "signaling SPKI mismatch peer=$peerId")
                    throw CertificateException("TOFU: signaling mismatch")
                }
                pins.put(peerId, observed)
                InAppLogger.d("TOFU", "pinned $peerId -> $observed (signaling verified)")
            }
            pinned == null -> {
                pins.put(peerId, observed)
                InAppLogger.err("TOFU", "pinned UNVERIFIED $peerId -> $observed")
            }
            pinned != observed -> {
                InAppLogger.err("TOFU", "pin mismatch peer=$peerId pinned=$pinned got=$observed")
                throw CertificateException("TOFU: pin mismatch")
            }
        }
    }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
