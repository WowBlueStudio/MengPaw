// SPDX-FileCopyrightText: 2026 MengPaw
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure vault for storing API keys and sensitive data.
 * Uses Android EncryptedSharedPreferences with AES-256-GCM via Android Keystore.
 *
 * The master key is generated on first use and stored in Android Keystore
 * (hardware-backed when available). Data is encrypted at rest and cannot be
 * read via ADB backup or root without the Keystore key.
 *
 * Usage:
 *   val vault = Vault(context)
 *   vault.store("openai_key", "sk-...")
 *   val key = vault.retrieve("openai_key")
 */
class Vault(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

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

    companion object {
        private const val PREFS_FILE = "mengpaw_vault_encrypted"
    }
}
