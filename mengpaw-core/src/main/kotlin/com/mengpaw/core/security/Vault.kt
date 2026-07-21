// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mengpaw.kernel.KernelLog
import java.security.GeneralSecurityException

/**
 * Secure vault for storing API keys and sensitive data.
 * Uses Android EncryptedSharedPreferences with AES-256-GCM via Android Keystore.
 *
 * The master key is generated on first use and stored in Android Keystore
 * (hardware-backed when available). Data is encrypted at rest and cannot be
 * read via ADB backup or root without the Keystore key.
 *
 * **Integrity-first**: If Keystore is unavailable, Vault refuses to store data
 * rather than falling back to plaintext. Callers should check [isAvailable]
 * before attempting to persist secrets.
 *
 * Usage:
 *   val vault = Vault(context)
 *   if (vault.isAvailable) {
 *       vault.store("openai_key", "sk-...")
 *   }
 *   val key = vault.retrieve("openai_key")
 */
class Vault(context: Context) {

    /** Whether the encrypted storage backend is operational. */
    val isAvailable: Boolean

    private val prefs: SharedPreferences

    init {
        val (p, ok) = tryCreateEncrypted(context)
        prefs = p
        isAvailable = ok
    }

    /**
     * Attempt to create EncryptedSharedPreferences.
     * Retries once on failure (Keystore may need a moment after OS update).
     * If both attempts fail, returns a no-op in-memory store — data will not
     * survive process death, but secrets will never be written to plaintext.
     */
    private fun tryCreateEncrypted(context: Context): Pair<SharedPreferences, Boolean> {
        // First attempt
        val first = tryBuild(context)
        if (first != null) return Pair(first, true)

        KernelLog.w("Vault", "First attempt failed, retrying after short delay...")
        // Brief pause — on some devices Keystore needs a moment after key migration
        try { Thread.sleep(200) } catch (_: Exception) {}

        // Second attempt
        val second = tryBuild(context)
        if (second != null) return Pair(second, true)

        KernelLog.e("Vault", "Keystore unavailable after retry — Vault will not persist secrets this session")
        // Use in-memory store: data is lost on restart, but NEVER written to plaintext
        return Pair(InMemoryPreferences(), false)
    }

    private fun tryBuild(context: Context): SharedPreferences? {
        return try {
            val mk = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            KernelLog.w("Vault", "Keystore error: ${e.message}")
            null
        } catch (e: Exception) {
            KernelLog.w("Vault", "Encrypted prefs creation failed: ${e.message}")
            null
        }
    }

    fun store(key: String, value: String) {
        if (!isAvailable) return
        try {
            prefs.edit().putString(safeKey(key), value).apply()
        } catch (e: Exception) {
            KernelLog.w("Vault", "Store failed for '$key': ${e.message}")
        }
    }

    fun retrieve(key: String): String? {
        return try {
            prefs.getString(safeKey(key), null)
        } catch (e: Exception) {
            KernelLog.w("Vault", "Retrieve failed for '$key': ${e.message}")
            null
        }
    }

    fun contains(key: String): Boolean {
        return try {
            prefs.contains(safeKey(key))
        } catch (e: Exception) { false }
    }

    fun remove(key: String) {
        try {
            prefs.edit().remove(safeKey(key)).apply()
        } catch (e: Exception) {
            KernelLog.w("Vault", "Remove failed for '$key': ${e.message}")
        }
    }

    fun clear() {
        try {
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            KernelLog.w("Vault", "Clear failed: ${e.message}")
        }
    }

    private fun safeKey(raw: String): String = "vault_$raw"

    companion object {
        private const val PREFS_FILE = "mengpaw_vault_encrypted"
    }
}

/**
 * A SharedPreferences implementation that stores everything in memory.
 * Used as a last-resort fallback when Keystore is unavailable — data never
 * touches disk, so secrets are never persisted in plaintext.
 */
internal class InMemoryPreferences : SharedPreferences {
    private val store = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = store.toMutableMap()

    override fun getString(key: String, defValue: String?): String? =
        store[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (store[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        (store[key] as? Int) ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        (store[key] as? Long) ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        (store[key] as? Float) ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        (store[key] as? Boolean) ?: defValue

    override fun contains(key: String): Boolean = store.containsKey(key)

    override fun edit(): SharedPreferences.Editor = InMemoryEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

    internal inner class InMemoryEditor : SharedPreferences.Editor {
        private val tmp = mutableMapOf<String, Any?>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            tmp[key] = value; return this
        }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            tmp[key] = values; return this
        }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            tmp[key] = value; return this
        }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            tmp[key] = value; return this
        }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            tmp[key] = value; return this
        }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            tmp[key] = value; return this
        }
        override fun remove(key: String): SharedPreferences.Editor {
            tmp[key] = null; return this
        }
        override fun clear(): SharedPreferences.Editor {
            tmp.clear(); return this
        }
        override fun commit(): Boolean {
            store.putAll(tmp); return true
        }
        override fun apply() { store.putAll(tmp) }
    }
}
