package com.example.communication

import android.content.Context
import android.provider.ContactsContract

data class ContactMatch(val contactId: Long, val name: String, val phones: List<String>, val emails: List<String>)

sealed class ContactResolution {
  data class Single(val contact: ContactMatch) : ContactResolution()
  data class Ambiguous(val candidates: List<ContactMatch>) : ContactResolution()
  object NotFound : ContactResolution()
  object PermissionDenied : ContactResolution()
}

/** Real ContactsContract lookup. No default contact exists; ambiguity is surfaced to the user. */
class ContactResolver(private val context: Context) {

  fun resolve(spoken: String): ContactResolution {
    if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return ContactResolution.PermissionDenied
    val q = spoken.trim()
    if (q.isBlank()) return ContactResolution.NotFound
    val byId = linkedMapOf<Long, MutableList<String>>(); val names = mutableMapOf<Long, String>()
    context.contentResolver.query(
      ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
      arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
      "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null
    )?.use { c ->
      while (c.moveToNext()) {
        val id = c.getLong(0); names[id] = c.getString(1) ?: ""
        byId.getOrPut(id) { mutableListOf() }.let { l -> c.getString(2)?.let { n -> if (l.none { it.filter(Char::isDigit).takeLast(10) == n.filter(Char::isDigit).takeLast(10) }) l += n } }
      }
    }
    val matches = byId.map { (id, phones) -> ContactMatch(id, names[id].orEmpty(), phones, emailsFor(id)) }
    if (matches.isEmpty()) {
      // fall back to email-only contacts
      val e = emailOnly(q); if (e.isNotEmpty()) return if (e.size == 1) ContactResolution.Single(e[0]) else ContactResolution.Ambiguous(e)
      return ContactResolution.NotFound
    }
    val exact = matches.filter { it.name.equals(q, true) }
    val pick = if (exact.size == 1) exact else matches
    return if (pick.size == 1) ContactResolution.Single(pick[0]) else ContactResolution.Ambiguous(pick)
  }

  private fun emailsFor(id: Long): List<String> {
    val out = mutableListOf<String>()
    context.contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
      "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?", arrayOf(id.toString()), null)?.use { while (it.moveToNext()) it.getString(0)?.let(out::add) }
    return out.distinct()
  }

  private fun emailOnly(q: String): List<ContactMatch> {
    val out = mutableListOf<ContactMatch>()
    context.contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
      arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID, ContactsContract.CommonDataKinds.Email.DISPLAY_NAME, ContactsContract.CommonDataKinds.Email.ADDRESS),
      "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null)?.use { c ->
      while (c.moveToNext()) out += ContactMatch(c.getLong(0), c.getString(1) ?: "", emptyList(), listOfNotNull(c.getString(2)))
    }
    return out.groupBy { it.contactId }.map { (_, v) -> v.first().copy(emails = v.flatMap { it.emails }.distinct()) }
  }
}
