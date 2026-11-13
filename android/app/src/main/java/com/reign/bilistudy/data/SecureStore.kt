package com.reign.bilistudy.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * 大模型密钥和网盘密码只存在本机加密存储（Android Keystore），不进同步文件。
 * 个别机型 Keystore 不可用时退回普通 preferences，做法和 Montage 的 SyncManager 一致。
 */
object SecureStore {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = runCatching {
            val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create("secure", alias, context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.onFailure { Log.w("SecureStore", "Encrypted storage unavailable", it) }
            .getOrElse { context.getSharedPreferences("secure_plain", Context.MODE_PRIVATE) }
    }

    fun get(key: String): String? = prefs.getString(key, null)

    fun set(key: String, value: String?) {
        prefs.edit().apply { if (value.isNullOrEmpty()) remove(key) else putString(key, value) }.apply()
    }
}
