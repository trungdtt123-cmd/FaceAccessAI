package com.example.faceaccessai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

class SupportCallController(context: Context) {

    private val appContext = context.applicationContext
    private var selectedIndex = -1

    enum class SelectionResult {
        SUCCESS,
        NO_CONTACTS
    }

    enum class OpenResult {
        SUCCESS,
        NO_CONTACTS,
        FAILED
    }

    fun selectNext(): SelectionResult {
        val savedIndices = getSavedIndices()
        if (savedIndices.isEmpty()) return SelectionResult.NO_CONTACTS

        selectedIndex = if (selectedIndex == -1 || !savedIndices.contains(selectedIndex)) {
            savedIndices.first()
        } else {
            val currentPos = savedIndices.indexOf(selectedIndex)
            val nextPos = (currentPos + 1) % savedIndices.size
            savedIndices[nextPos]
        }
        return SelectionResult.SUCCESS
    }

    fun selectPrevious(): SelectionResult {
        val savedIndices = getSavedIndices()
        if (savedIndices.isEmpty()) return SelectionResult.NO_CONTACTS

        selectedIndex = if (selectedIndex == -1 || !savedIndices.contains(selectedIndex)) {
            savedIndices.first()
        } else {
            val currentPos = savedIndices.indexOf(selectedIndex)
            val prevPos = if (currentPos - 1 < 0) savedIndices.size - 1 else currentPos - 1
            savedIndices[prevPos]
        }
        return SelectionResult.SUCCESS
    }

    fun openSelectedDialer(): OpenResult {
        val contact = getSelectedContact() ?: return OpenResult.NO_CONTACTS
        if (contact.phone.isEmpty()) return OpenResult.NO_CONTACTS

        return try {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(contact.phone)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(dialIntent)
            OpenResult.SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open dialer", e)
            OpenResult.FAILED
        }
    }

    fun getSelectedContact(): SupportContact? {
        val savedIndices = getSavedIndices()
        if (savedIndices.isEmpty()) return null

        if (selectedIndex == -1 || !savedIndices.contains(selectedIndex)) {
            selectedIndex = savedIndices.first()
        }
        return SupportContactManager.getContact(appContext, selectedIndex)
    }

    private fun getSavedIndices(): List<Int> {
        val contacts = SupportContactManager.getContacts(appContext)
        return contacts.indices.filter { contacts[it].phone.isNotEmpty() }
    }

    companion object {
        private const val TAG = "SupportCallController"
    }
}
