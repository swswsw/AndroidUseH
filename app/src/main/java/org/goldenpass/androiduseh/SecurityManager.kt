package org.goldenpass.androiduseh

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class SecurityManager(context: Context) {
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val sharedPreferences = EncryptedSharedPreferences.create(
        "secure_prefs",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveHoloApiKey(apiKey: String) {
        sharedPreferences.edit().putString("holo_api_key", apiKey).apply()
    }

    fun getHoloApiKey(): String? {
        return sharedPreferences.getString("holo_api_key", null)
    }
}
