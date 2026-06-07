package org.citra.citra_emu.moonlight.crypto

import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.util.Base64

object CertificateEncoding {
    fun pemBytesFor(certificate: X509Certificate): ByteArray =
        pemBytesForDer(certificate.encoded)

    fun pemBytesForDer(derBytes: ByteArray): ByteArray {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray(StandardCharsets.US_ASCII))
            .encodeToString(derBytes)
        return (
            "-----BEGIN CERTIFICATE-----\n" +
                base64 +
                "\n-----END CERTIFICATE-----\n"
            ).toByteArray(StandardCharsets.US_ASCII)
    }
}
