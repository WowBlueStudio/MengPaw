package com.mengpaw.core.security

import android.content.Context
import android.content.SharedPreferences
import android.content.Context.MODE_PRIVATE

/**
 * Secure vault for storing API keys and sensitive data.
 * Uses Android's sandboxed SharedPreferences (files are app-private).
 * For production use, add EncryptedSharedPreferences from security-crypto.
 *
 * Usage:
 *   val vault = Vault(context)
 *   vault.store("openai_key", "sk-...")
 *   val key = vault.retrieve("openai_key")
 */
class Vault(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mengpaw_vault", MODE_PRIVATE)

    fun store(key: String, value: String) {
        prefs.edit().putString(safeKey(key), value).apply()
    }

    fun retrieve(key: String): String? {
        return prefs.getString(safeKey(key), null)
    }

    fun contains(key: String): Boolean {
        return prefs.contains(safeKey(key))
    }

    fun remove(key: String) {
        prefs.edit().remove(safeKey(key)).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun safeKey(raw: String): String = "vault_$raw"
}
