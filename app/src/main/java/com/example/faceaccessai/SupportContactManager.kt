package com.example.faceaccessai

import android.content.Context

data class SupportContact(
    val name: String,
    val phone: String
)

object SupportContactManager {

    private const val PREFS_NAME = "support_contacts_prefs"
    private const val KEY_NAME_PREFIX = "support_name_"
    private const val KEY_PHONE_PREFIX = "support_phone_"
    const val MAX_CONTACTS = 3

    fun getContact(context: Context, index: Int): SupportContact {
        if (index !in 0 until MAX_CONTACTS) return SupportContact("", "")
        
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString("$KEY_NAME_PREFIX$index", "") ?: ""
        val phone = prefs.getString("$KEY_PHONE_PREFIX$index", "") ?: ""
        
        return SupportContact(name, phone)
    }

    fun getContacts(context: Context): List<SupportContact> {
        return (0 until MAX_CONTACTS).map { getContact(context, it) }
    }

    fun saveContact(context: Context, index: Int, name: String, phone: String) {
        if (index !in 0 until MAX_CONTACTS) return
        
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("$KEY_NAME_PREFIX$index", name.trim())
            .putString("$KEY_PHONE_PREFIX$index", phone.trim())
            .apply()
    }

    fun clearContact(context: Context, index: Int) {
        if (index !in 0 until MAX_CONTACTS) return
        
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("$KEY_NAME_PREFIX$index")
            .remove("$KEY_PHONE_PREFIX$index")
            .apply()
    }
}
