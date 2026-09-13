package com.tabletgamepadbridge.adb

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

private const val TAG = "AdbKey"

/**
 * Embedded pre-authenticated PC adbkey (iyads@IYAD). Because the Android device
 * has already trusted iyads@IYAD from PC ADB connections, using this same keypair
 * allows the app to authenticate with Wireless Debugging instantly without asking
 * the user for a 6-digit pairing code.
 */
class AdbKey(private val adbKeyStore: AdbKeyStore, name: String = "iyads@IYAD") {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_KEY_ALIAS = "_adbkey_encryption_key_"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        private const val IV_SIZE_IN_BYTES = 12
        private const val TAG_SIZE_IN_BYTES = 16

        // Pre-authenticated RSA Private Key from PC (~/.android/adbkey / iyads@IYAD)
        private const val PREPAIRED_ADBKEY_BASE64 =
            "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDIAAbJxU+xZs1ecCBoBEkjqbdeCzQFnLFFVaU2h0TejXtpb/tdvgTe+cbsJvVe9WZd3PWypsoL01y0WlxH7dvTiVcnRNNmioBgfV" +
            "KP7rW1+KQzpbQHSz5a8GwosH4+zS+qrR9dyYX2Zir09gsmloTsnvfsP6B/XV34oBp5gGkHGLLFFWIraQ2fCnLvnwiurnDHFp7au44pYZeQbejwzUQTHxJiFRJVoSiF8DFBFIGi1FqtfGm/cniCyG6B" +
            "DadwFytWKxqYFufKRsXfc8r2lGAVdJ+qcnQUqxJXJTvx2Vaub3oq/Gdy4QYhTH4rezHJCdDB59x3T/kVTGkzsC/qKAi5AgMBAAECggEAFFUyW3mAkl3L87WtCb4jzGkg8AHuEkL9n7mnD/3dEc4q9Y" +
            "txr/RVTPDyWQhV6kdDFrhTz0uXH3AnzNsh5hsnveAI2QBtiI98oTKkfecMGLm0Qd7vCE3NQ1QNfu6AizRzi+PJXFDUWnpFFD3eYNgtH9xCgsVuNPyiRNhMEL2uD88z+4QbYIcJ4CLlYZ0ZZIFZOAjH" +
            "Kz58GCVx/BslPpd/EB/gTyGZY8iCf0Mqtuf4J5O1Y/GFSJGU1S7GNGTYMJ8OD3HpnyafHCx87EToskkBzz6Ye8Gk26qaYp4k7pbBFu1CCZ2vpjsRwKz/lhe6vN1ncR/pWloOLmh62EiqSiikgQKBgQ" +
            "D7fk7DWTfcwxHWmIsZcu4RJQWbLlR9Y/1jFIXSu3aTfQ3+dfg8Uid3vCgK4EJgOdxmkaKnl7d6auxk9NuTyvDg7pW22dBEdet353x9razU3d37Plv6KhOQNzwFrXaLe9BhEkNPwcYdtWa7qtf99WQr" +
            "53H9tEj3vbFAo5Tsoz6ReQKBgQDLlYASMHSgToviPXNlKGpnb/aq+V3hDgccl8II8YXspUjNflLbeV6IxS8dGx/z/iZDe+afCwsnym8VtiazT7oOj7x2ez99sV+6oxF1mhB4RlY1XhF6FkK1K4++PF" +
            "RISPrLfOHPboeTwez0NUxErlfHR2Tg7qP4RCrtDjCVogihQQKBgQDvGjL//v7hYITBJd55n48/tZcS5oVlgX8SiByDMb+WkbqQRtBvaRwk9jqLvJLesaQd0DB4bgH+3VFK2pE4fiVtdOfaJFOiAwqP" +
            "wQHW6xv6dcXqbGs9+GsJHbpvG3AtRNXktFxSo8Qb0q+NlOwtgvRt2WnC73jAMRUog12/baS64QKBgQCZzDlShHS23l/i7JWOqDeqKPVqOLTpXlWVDjix1PRd0IftZi9mSoxWOtDa5jD/fNKfTKzvHc" +
            "Kxrsa42kDmWaavdXrk7zsJ08QEFUkNVPR4SHq8GnKNjt+aSmxvRNhPO4Lr004sDM4zm99Mpi8V+7eofpEBNId++NCNAZlGkYB/gQKBgAXiJQw2j4Zr15pvsih+4IB2rGkGgEsELufKVqg7DC9UGnk4" +
            "EPGIXtiiHy04eMr+VCVOA6tqGW1/c2FqtARQPerIBkdbj83aqd4XasugQ6tU220L21Ab/mp28WeKPgyMrxffBSirfFolsQAn1n7ojhmFhppGaDjgyfh6qEi8wryn"

        private val PADDING = byteArrayOf(
            0x00, 0x01, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0x00,
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00,
            0x04, 0x14
        )
    }

    private val encryptionKey: Key
    private val privateKey: RSAPrivateKey
    private val publicKey: RSAPublicKey
    private val certificate: X509Certificate

    init {
        this.encryptionKey = getOrCreateEncryptionKey()
        this.privateKey = getOrCreatePrivateKey()
        this.publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4)) as RSAPublicKey

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val x509Certificate = X509v3CertificateBuilder(
            X500Name("CN=00"),
            BigInteger.ONE,
            Date(0),
            Date(2461449600 * 1000),
            Locale.ROOT,
            X500Name("CN=00"),
            SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        ).build(signer)
        this.certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(x509Certificate.encoded)) as X509Certificate
    }

    val adbPublicKey: ByteArray by lazy(LazyThreadSafetyMode.NONE) {
        publicKey.adbEncoded(name)
    }

    private fun getOrCreateEncryptionKey(): Key {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        return keyStore.getKey(ENCRYPTION_KEY_ALIAS, null) ?: run {
            val parameterSpec = KeyGenParameterSpec.Builder(
                ENCRYPTION_KEY_ALIAS, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            keyGenerator.init(parameterSpec)
            keyGenerator.generateKey()
        }
    }

    private fun encrypt(plaintext: ByteArray, aad: ByteArray?): ByteArray? {
        if (plaintext.size > Int.MAX_VALUE - IV_SIZE_IN_BYTES - TAG_SIZE_IN_BYTES) return null
        val ciphertext = ByteArray(IV_SIZE_IN_BYTES + plaintext.size + TAG_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        cipher.updateAAD(aad)
        cipher.doFinal(plaintext, 0, plaintext.size, ciphertext, IV_SIZE_IN_BYTES)
        System.arraycopy(cipher.iv, 0, ciphertext, 0, IV_SIZE_IN_BYTES)
        return ciphertext
    }

    private fun decrypt(ciphertext: ByteArray, aad: ByteArray?): ByteArray? {
        if (ciphertext.size < IV_SIZE_IN_BYTES + TAG_SIZE_IN_BYTES) return null
        val params = GCMParameterSpec(8 * TAG_SIZE_IN_BYTES, ciphertext, 0, IV_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, params)
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext, IV_SIZE_IN_BYTES, ciphertext.size - IV_SIZE_IN_BYTES)
    }

    private fun getOrCreatePrivateKey(): RSAPrivateKey {
        var privateKey: RSAPrivateKey? = null

        val aad = ByteArray(16)
        "adbkey".toByteArray().copyInto(aad)

        var ciphertext = adbKeyStore.get()
        if (ciphertext != null) {
            try {
                val plaintext = decrypt(ciphertext, aad)
                val keyFactory = KeyFactory.getInstance("RSA")
                privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(plaintext)) as RSAPrivateKey
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decrypt stored key, generating a new one", e)
            }
        }
        if (privateKey == null) {
            val validKey: RSAPrivateKey = try {
                val prepairedBytes = Base64.decode(PREPAIRED_ADBKEY_BASE64, Base64.NO_WRAP)
                val keyFactory = KeyFactory.getInstance("RSA")
                keyFactory.generatePrivate(PKCS8EncodedKeySpec(prepairedBytes)) as RSAPrivateKey
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse pre-paired key, fallback to keygen", e)
                val keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
                keyPairGenerator.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                keyPairGenerator.generateKeyPair().private as RSAPrivateKey
            }

            privateKey = validKey
            ciphertext = encrypt(validKey.encoded, aad)
            if (ciphertext != null) {
                adbKeyStore.put(ciphertext)
            }
        }
        return privateKey
    }

    fun sign(data: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(PADDING)
        return cipher.doFinal(data)
    }

    private val keyManager
        get() = object : X509ExtendedKeyManager() {
            private val alias = "key"

            override fun chooseClientAlias(keyTypes: Array<out String>, issuers: Array<out Principal>?, socket: Socket?): String? {
                for (keyType in keyTypes) if (keyType == "RSA") return alias
                return null
            }

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == this.alias) arrayOf(certificate) else null

            override fun getPrivateKey(alias: String?): PrivateKey? =
                if (alias == this.alias) privateKey else null

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun getServerAliases(keyType: String, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String, issuers: Array<out Principal>?, socket: Socket?): String? = null
        }

    private val trustManager
        get() =
            @RequiresApi(Build.VERSION_CODES.R)
            object : X509ExtendedTrustManager() {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }

    @delegate:RequiresApi(Build.VERSION_CODES.R)
    val sslContext: SSLContext by lazy(LazyThreadSafetyMode.NONE) {
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        ctx
    }
}

interface AdbKeyStore {
    fun put(bytes: ByteArray)
    fun get(): ByteArray?
}

class PreferenceAdbKeyStore(private val preference: SharedPreferences) : AdbKeyStore {
    private val preferenceKey = "adbkey"

    override fun put(bytes: ByteArray) {
        preference.edit { putString(preferenceKey, String(Base64.encode(bytes, Base64.NO_WRAP))) }
    }

    override fun get(): ByteArray? {
        if (!preference.contains(preferenceKey)) return null
        return Base64.decode(preference.getString(preferenceKey, null), Base64.NO_WRAP)
    }
}

const val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8
const val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4
const val RSAPublicKey_Size = 524

private fun BigInteger.toAdbEncoded(): IntArray {
    val encoded = IntArray(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    val r32 = BigInteger.ZERO.setBit(32)

    var tmp = this.add(BigInteger.ZERO)
    for (i in 0 until ANDROID_PUBKEY_MODULUS_SIZE_WORDS) {
        val out = tmp.divideAndRemainder(r32)
        tmp = out[0]
        encoded[i] = out[1].toInt()
    }
    return encoded
}

private fun RSAPublicKey.adbEncoded(name: String): ByteArray {
    val r32 = BigInteger.ZERO.setBit(32)
    val n0inv = modulus.remainder(r32).modInverse(r32).negate()
    val r = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
    val rr = r.modPow(BigInteger.valueOf(2), modulus)

    val buffer = ByteBuffer.allocate(RSAPublicKey_Size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    buffer.putInt(n0inv.toInt())
    modulus.toAdbEncoded().forEach { buffer.putInt(it) }
    rr.toAdbEncoded().forEach { buffer.putInt(it) }
    buffer.putInt(publicExponent.toInt())

    val base64Bytes = Base64.encode(buffer.array(), Base64.NO_WRAP)
    val nameBytes = " $name ".toByteArray()
    val bytes = ByteArray(base64Bytes.size + nameBytes.size)
    base64Bytes.copyInto(bytes)
    nameBytes.copyInto(bytes, base64Bytes.size)
    return bytes
}
