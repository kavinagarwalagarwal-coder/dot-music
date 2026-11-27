package com.dotmusic.app

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** One number of one person. Lists show each number separately, the way a dialer should. */
data class Person(
    val key: String,
    val name: String,
    val number: String,
    val photoUri: String?
)

/** One person, with all of their numbers. The People tab shows this instead of one row per number. */
data class ContactGroup(
    val key: String,
    val name: String,
    val numbers: List<String>,
    val photoUri: String?
) {
    val mainNumber: String get() = numbers.firstOrNull() ?: ""
    val mainKey: String get() = Store.key(mainNumber)
}

/** Reading the address book, plus the name behind a number. Both block, so call them off the UI. */
object Contacts {
    fun load(context: Context, limit: Int = 800): List<Person> {
        val out = ArrayList<Person>()
        runCatching {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                val seen = HashSet<String>()
                while (cursor.moveToNext() && out.size < limit) {
                    val number = cursor.getString(numberIndex).orEmpty().trim()
                    if (number.isBlank()) continue
                    if (!seen.add(number.filter { it.isDigit() })) continue
                    out += Person(
                        key = Store.key(number),
                        name = cursor.getString(nameIndex).orEmpty().trim().ifBlank { number },
                        number = number,
                        photoUri = cursor.getString(photoIndex)
                    )
                }
            }
        }
        return out
    }

    fun nameFor(context: Context, number: String): String? {
        if (number.isBlank()) return null
        return runCatching {
            context.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
    }

    fun personFor(context: Context, number: String): Person? {
        if (number.isBlank()) return null
        return runCatching {
            context.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_URI
                ),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else Person(
                    key = Store.key(number),
                    name = cursor.getString(0).orEmpty().ifBlank { number },
                    number = number,
                    photoUri = cursor.getString(1)
                )
            }
        }.getOrNull()
    }

    /** Reading the address book once, grouped by contact id. Both block, so call them off the UI. */
    fun contacts(context: Context, limit: Int = 400): List<ContactGroup> {
        val out = ArrayList<ContactGroup>()
        runCatching {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                val idToNumbers = linkedMapOf<Long, MutableList<String>>()
                val idToName = mutableMapOf<Long, String>()
                val idToPhoto = mutableMapOf<Long, String?>()
                while (cursor.moveToNext() && idToNumbers.size < limit) {
                    val id = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex).orEmpty().trim().ifBlank { " " }
                    val number = cursor.getString(numberIndex).orEmpty().trim()
                    if (number.isBlank()) continue
                    if (Store.key(number).isEmpty()) continue
                    val photo = cursor.getString(photoIndex)
                    idToNumbers.getOrPut(id) { mutableListOf() }.add(number)
                    idToName.putIfAbsent(id, name)
                    idToPhoto.putIfAbsent(id, photo?.takeUnless { it.isBlank() })
                }
                idToNumbers.forEach { (id, nums) ->
                    val mainNumber = nums.first()
                    out.add(
                        ContactGroup(
                            Store.key(mainNumber),
                            idToName[id] ?: "",
                            nums.toList(),
                            idToPhoto[id]
                        )
                    )
                }
            }
        }
        return out
    }

    /** A recently called person, as the call log knows them. */
    data class Recent(
        val name: String,
        val number: String,
        val type: Int,
        val date: Long,
        val photoUri: String?
    ) {
        val key: String get() = Store.key(number)
    }

    fun recents(context: Context, limit: Int = 80): List<Recent> {
        val out = ArrayList<Recent>()
        runCatching {
            context.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(
                    android.provider.CallLog.Calls.NUMBER,
                    android.provider.CallLog.Calls.CACHED_NAME,
                    android.provider.CallLog.Calls.TYPE,
                    android.provider.CallLog.Calls.DATE,
                    android.provider.CallLog.Calls.CACHED_PHOTO_URI
                ),
                null, null, "${android.provider.CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numberIndex = cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.NUMBER)
                val nameIndex = cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.CACHED_NAME)
                val typeIndex = cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.TYPE)
                val dateIndex = cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.DATE)
                val photoIndex = cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.CACHED_PHOTO_URI)
                while (cursor.moveToNext() && out.size < limit) {
                    val number = cursor.getString(numberIndex).orEmpty()
                    if (number.isBlank()) continue
                    out += Recent(
                        name = cursor.getString(nameIndex).orEmpty(),
                        number = number,
                        type = cursor.getInt(typeIndex),
                        date = cursor.getLong(dateIndex),
                        photoUri = cursor.getString(photoIndex)
                    )
                }
            }
        }
        return out
    }
}
