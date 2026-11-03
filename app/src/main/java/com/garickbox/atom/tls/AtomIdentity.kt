package com.garickbox.atom.tls

import android.content.Context
import com.mesh.core.InAppLogger
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.UUID

/** P2: identity (EC P-256 + self-signed cert) в app-private PKCS12. */
object AtomIdentity {
    private const val FILE = "atom_identity.p12"
    private const val ALIAS = "atom"
    private val PW = "atom-id-v1".toCharArray()

    data class Id(val keyStore: KeyStore, val password: CharArray, val cert: X509Certificate)

    @Synchronized
    fun load(ctx: Context): Id {
        val file = File(ctx.filesDir, FILE)
        val ks = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            file.inputStream().use { ks.load(it, PW) }
        } else {
            ks.load(null, PW)
            val kp = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            }.generateKeyPair()
            val cn = "atom-" + UUID.randomUUID().toString().take(8)
            ks.setKeyEntry(ALIAS, kp.private, PW, arrayOf(buildCert(kp, cn)))
            file.outputStream().use { ks.store(it, PW) }
        }
        return Id(ks, PW, ks.getCertificate(ALIAS) as X509Certificate)
    }

    fun spkiFingerprint(ctx: Context): String {
        val id = load(ctx)
        return MessageDigest.getInstance("SHA-256").digest(id.cert.publicKey.encoded).hex()
    }

    private fun buildCert(kp: java.security.KeyPair, cn: String): X509Certificate {
        val now = System.currentTimeMillis()
        val dn = X500Name("CN=$cn, O=Atom, OU=p2p")
        val b = JcaX509v3CertificateBuilder(
            dn, BigInteger.valueOf(now),
            Date(now - 86_400_000L), Date(now + 10L * 365 * 86_400_000L),
            dn, kp.public
        )
        b.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        b.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        b.addExtension(Extension.extendedKeyUsage, false,
            ExtendedKeyUsage(listOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth)))
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
        val holder: org.bouncycastle.cert.X509CertificateHolder = b.build(signer)
        val der: ByteArray = holder.encoded
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(java.io.ByteArrayInputStream(der)) as X509Certificate
    }

    fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
