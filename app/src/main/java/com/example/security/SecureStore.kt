package com.example.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Hardware-backed (Android Keystore) encrypted storage for API keys. Keys never go to logs or any cloud. */
class SecureStore(context: Context) {
  private val prefs = EncryptedSharedPreferences.create(
    context.applicationContext,
    "jarvis_secure_store",
    MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
  )

  fun putSecret(name: String, value: String) { prefs.edit().putString(name, value).apply() }
  fun getSecret(name: String): String? = prefs.getString(name, null)
  fun removeSecret(name: String) { prefs.edit().remove(name).apply() }
}
