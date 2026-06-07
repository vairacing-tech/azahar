package org.citra.citra_emu.moonlight.pairing

import org.citra.citra_emu.moonlight.crypto.Hex
import org.citra.citra_emu.moonlight.crypto.ServerIdentity
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

enum class PairingHash(private val algorithm: String, val hashLength: Int) {
    SHA1("SHA-1", 20),
    SHA256("SHA-256", 32);

    fun digest(data: ByteArray): ByteArray =
        MessageDigest.getInstance(algorithm).digest(data)
}

class PairingProtocol(
    private val serverIdentity: ServerIdentity,
    private val pairingStore: PairingStore,
    private val pinProvider: () -> String?,
    private val hash: PairingHash = PairingHash.SHA256,
) {
    private val sessions = mutableMapOf<String, PairSession>()
    private val random = SecureRandom()

    @Synchronized
    fun handle(uniqueId: String, args: Map<String, String>): String {
        return when {
            args["phrase"] == "getservercert" -> getServerCert(uniqueId, args)
            args["phrase"] == "pairchallenge" -> ok("pairchallenge")
            args.containsKey("clientchallenge") -> clientChallenge(uniqueId, args.getValue("clientchallenge"))
            args.containsKey("serverchallengeresp") -> serverChallengeResponse(uniqueId, args.getValue("serverchallengeresp"))
            args.containsKey("clientpairingsecret") -> clientPairingSecret(uniqueId, args.getValue("clientpairingsecret"))
            else -> error(404, "Invalid pairing request")
        }
    }

    @Synchronized
    fun unpair(uniqueId: String? = null) {
        sessions.clear()
        if (uniqueId == null) {
            pairingStore.clear()
        }
    }

    private fun getServerCert(uniqueId: String, args: Map<String, String>): String {
        val salt = Hex.decode(args["salt"] ?: return error(400, "Missing salt"))
        val clientCertText = Hex.decode(args["clientcert"] ?: return error(400, "Missing clientcert"))
        val clientCert = parseCertificate(clientCertText)
        val pin = PairingPin.normalize(pinProvider())
            ?: return error(408, "Timed out waiting for pairing PIN")
        val aesKey = hash.digest(salt + pin.toByteArray(Charsets.UTF_8)).copyOf(16)
        sessions[uniqueId] = PairSession(uniqueId, aesKey, clientCert)
        return """
              <?xml version="1.0" encoding="utf-8"?>
              <root status_code="200">
                <paired>1</paired>
                <plaincert>${Hex.encode(serverIdentity.certificatePemBytes)}</plaincert>
              </root>
        """.trimIndent()
    }

    private fun clientChallenge(uniqueId: String, encryptedChallengeHex: String): String {
        val session = sessions[uniqueId] ?: return error(400, "Invalid uniqueid")
        val clientChallenge = decryptAes(Hex.decode(encryptedChallengeHex), session.aesKey).copyOf(16)
        session.clientChallenge = clientChallenge
        session.serverSecret = randomBytes(16)
        session.serverChallenge = randomBytes(16)

        val serverResponse = hash.digest(clientChallenge + serverIdentity.certificate.signature + session.serverSecret)
        val encrypted = encryptAes(serverResponse + session.serverChallenge, session.aesKey)
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <root status_code="200">
              <paired>1</paired>
              <challengeresponse>${Hex.encode(encrypted)}</challengeresponse>
            </root>
        """.trimIndent()
    }

    private fun serverChallengeResponse(uniqueId: String, encryptedResponseHex: String): String {
        val session = sessions[uniqueId] ?: return error(400, "Invalid uniqueid")
        session.clientChallengeResponse = decryptAes(Hex.decode(encryptedResponseHex), session.aesKey)
            .copyOf(hash.hashLength)
        val signedSecret = session.serverSecret + serverIdentity.signSha256(session.serverSecret)
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <root status_code="200">
              <paired>1</paired>
              <pairingsecret>${Hex.encode(signedSecret)}</pairingsecret>
            </root>
        """.trimIndent()
    }

    private fun clientPairingSecret(uniqueId: String, pairingSecretHex: String): String {
        val session = sessions[uniqueId] ?: return error(400, "Invalid uniqueid")
        val payload = Hex.decode(pairingSecretHex)
        if (payload.size <= 16) return error(400, "Invalid client pairing secret")

        val clientSecret = payload.copyOfRange(0, 16)
        val signature = payload.copyOfRange(16, payload.size)
        if (!verifySignature(clientSecret, signature, session.clientCert)) {
            sessions.remove(uniqueId)
            return error(403, "Client certificate signature verification failed")
        }

        val expected = hash.digest(session.serverChallenge + session.clientCert.signature + clientSecret)
        if (!expected.contentEquals(session.clientChallengeResponse)) {
            sessions.remove(uniqueId)
            return """
                <?xml version="1.0" encoding="utf-8"?>
                <root status_code="200"><paired>0</paired></root>
            """.trimIndent()
        }

        pairingStore.add("Moonlight", pemFor(session.clientCert))
        sessions.remove(uniqueId)
        return ok("clientpairingsecret")
    }

    private fun ok(name: String): String =
        """
            <?xml version="1.0" encoding="utf-8"?>
            <root status_code="200"><paired>1</paired><$name>1</$name></root>
        """.trimIndent()

    private fun error(status: Int, message: String): String =
        """
            <?xml version="1.0" encoding="utf-8"?>
            <root status_code="$status" status_message="$message"><paired>0</paired></root>
        """.trimIndent()

    private fun parseCertificate(bytes: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate

    private fun verifySignature(data: ByteArray, signature: ByteArray, cert: X509Certificate): Boolean {
        val algorithm = when (cert.publicKey.algorithm) {
            "EC" -> "SHA256withECDSA"
            else -> "SHA256withRSA"
        }
        return Signature.getInstance(algorithm).run {
            initVerify(cert.publicKey)
            update(data)
            verify(signature)
        }
    }

    private fun encryptAes(data: ByteArray, key: ByteArray): ByteArray =
        aes(Cipher.ENCRYPT_MODE, data, key)

    private fun decryptAes(data: ByteArray, key: ByteArray): ByteArray =
        aes(Cipher.DECRYPT_MODE, data, key)

    private fun aes(mode: Int, data: ByteArray, key: ByteArray): ByteArray {
        val padded = data.copyOf(roundToBlock(data.size))
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"))
        }
        return cipher.doFinal(padded)
    }

    private fun roundToBlock(size: Int): Int = ((size + 15) / 16) * 16

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { random.nextBytes(it) }

    private fun pemFor(cert: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" +
            android.util.Base64.encodeToString(cert.encoded, android.util.Base64.NO_WRAP) +
            "\n-----END CERTIFICATE-----"

    private data class PairSession(
        val uniqueId: String,
        val aesKey: ByteArray,
        val clientCert: X509Certificate,
        var clientChallenge: ByteArray = ByteArray(0),
        var serverChallenge: ByteArray = ByteArray(0),
        var serverSecret: ByteArray = ByteArray(0),
        var clientChallengeResponse: ByteArray = ByteArray(0),
    )
}
