package com.karinlab.mymonitor

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypt SMB credentials with a non-exportable Android Keystore key.
 * Neither passwords nor key material are stored as plain preferences.
 * Restoring preferences onto another device requires re-entering the password.
 */
object SmbCredentials {
    private const val ALIAS = "MyMonitor.SmbCredentials.v1"
    private const val PREFS = "monitor_secrets"
    private const val SECRET = "password_ciphertext"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun hasSavedPassword(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(SECRET)

    fun save(context: Context, password: CharArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        try {
            val ciphertext = cipher.doFinal(bytes)
            val packed = cipher.iv + ciphertext
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(SECRET, Base64.encodeToString(packed, Base64.NO_WRAP))
                .commit()
        } finally { bytes.fill(0) }
    }

    fun load(context: Context): CharArray? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SECRET, null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > 12 + 16) { "Invalid saved credentials" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8).toCharArray()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(SECRET).commit()
    }
}
