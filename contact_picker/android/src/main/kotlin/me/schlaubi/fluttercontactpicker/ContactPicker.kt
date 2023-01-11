@file:Suppress("Range")

package me.schlaubi.fluttercontactpicker

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.CursorIndexOutOfBoundsException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.io.ByteArrayOutputStream
import java.util.*

class ContactPicker private constructor(private val pickContext: PickContext, private val result: MethodChannel.Result, askForPermission: Boolean, private val requestCode: Int, private val type: Uri) : PluginRegistry.ActivityResultListener, PluginRegistry.RequestPermissionsResultListener {

    init {
        val hasPermission = PermissionUtil.hasPermission(pickContext.context) || ("xiaomi" !in Build.MANUFACTURER.lowercase(
            Locale.getDefault()
        ) /* Cool android OEMs think it's cool to not do what Android does */ && Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && requestCode != FlutterContactPickerPlugin.PICK_CONTACT) // below android 11 there is no need for permissions when only requesting email/phone number
        if (!hasPermission && askForPermission) {
            PermissionUtil.requestPermission(pickContext.activity, this)
        } else if (hasPermission) {
            requestPicker()
        } else {
            result.error("INSUFFICIENT_PERMISSIONS", "The READ_CONTACTS permission has not been granted", null)
        }
    }

    private fun requestPicker() {
        val pickerIntent = Intent(Intent.ACTION_PICK, type)
        pickContext.addActivityResultListener(this)
        pickContext.activity.startActivityForResult(pickerIntent, requestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        try {
            when (requestCode) {
                FlutterContactPickerPlugin.PICK_EMAIL -> processDisplayNamed(data, "email", ::buildEmailAddress)
                FlutterContactPickerPlugin.PICK_PHONE -> processDisplayNamed(data, "phoneNumber", ::buildPhoneNumber)
                FlutterContactPickerPlugin.PICK_CONTACT -> processContact(data, ::buildContact)
                else -> return false
            }
        } catch (e: CursorIndexOutOfBoundsException) {
            if (e.message == "Index 0 requested, with a size of 0") {
                this.result.error("PERMISSION_ERROR", "It looks like this custom ROM requires the READ_CONTACTS permission. You can see how to obtain the permission here: https://github.com/DRSchlaubi/contact_picker/blob/master/README.md#permissions", ContactPickingException(e).toString())
            } else throw e //Throw normal exception if edge case is not the case
        }
        return true
    }

    private fun processInput(intent: Intent?, block: (Uri) -> Unit) {
        val data = intent?.data
        if (data == null) {
            pickContext.removeActivityResultListener(this)
            result.error("CANCELLED", "The user cancelled the process without picking a contact", null)
            return
        }
        block(data)
    }

    private fun processDisplayNamed(intent: Intent?, dataName: String, dataProcessor: (Cursor, Activity, Uri) -> Map<String, String>?) {
        val processor = { cursor: Cursor, activity: Activity, uri: Uri ->
            buildDisplayNamed(cursor, dataName, dataProcessor(cursor, activity, uri))
        }
        return processContact(intent, processor)
    }

    private fun processContact(intent: Intent?, dataProcessor: (Cursor, Activity, Uri) -> Map<String, Any?>?) {
        processInput(intent) { data ->
            val activity = pickContext.activity
            activity.contentResolver.query(data, null, null, null, null).use {
                require(it != null) { "Cursor must not be null" }
                it.moveToFirst()
                val processedData = dataProcessor(it, activity, data)
                this.result.success(processedData)
            }
            pickContext.removeActivityResultListener(this)
        }
    }

    private fun buildContact(cursor: Cursor, activity: Activity, data: Uri): Map<String, Any?> {
        val contactId = cursor.getLong(cursor.getColumnIndex(ContactsContract.Contacts._ID))
        var name: Map<String, String>? = null
        val instantMessengers = mutableListOf<Map<String, String>>()
        val emails = mutableListOf<Map<String, String>>()
        val phones = mutableListOf<Map<String, String>>()
        val addresses = mutableListOf<Map<String, Any>>()
        var note: String? = null
        var company: String? = null
        var sip: String? = null
        val relations = mutableListOf<Map<String, String>>()
        val customFields = mutableListOf<Map<String, String>>()
        val photo = buildPhoto(activity.contentResolver, data)
        activity.contentResolver.query(ContactsContract.RawContactsEntity.CONTENT_URI, null, "${ContactsContract.Data.CONTACT_ID} = ?", arrayOf(contactId.toString()), null, null).use {
            require(it != null && it.moveToFirst()) { "Contact could not be found" }
            do {
                when (it.getString(it.getColumnIndex(ContactsContract.RawContactsEntity.MIMETYPE))) {
                    ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE -> instantMessengers += buildInstantMessenger(it, activity)
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> emails.addNotNull(buildEmailAddress(it, activity, data))
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> addresses += buildAddress(it, activity)
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> phones.addNotNull(buildPhoneNumber(it, activity, data))
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> name = buildName(it)
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> note = getNote(it)
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> company = getCompany(it)
                    ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE -> relations += buildRelation(it)
                    ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE -> sip = getSip(it)
                    "vnd.com.google.cursor.item/contact_user_defined_field" -> customFields += buildCustomField(it)
                }
            } while (it.moveToNext())
        }
        return mapOf("name" to name, "instantMessengers" to instantMessengers, "phones" to phones, "addresses" to addresses, "emails" to emails, "photo" to photo, "note" to note, "company" to company, "sip" to sip, "relations" to relations, "custom_fields" to customFields)
    }

    private fun getCompany(it: Cursor): String? = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY))

    private fun getNote(it: Cursor): String? = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Note.NOTE))

    private fun getSip(it: Cursor): String? = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.SipAddress.SIP_ADDRESS))

    private fun buildPhoto(contentResolver: ContentResolver, data: Uri): ByteArray? {
        val photoStream = ContactsContract.Contacts.openContactPhotoInputStream(contentResolver, data)
                ?: return null
        val bitmap = photoStream.use { BitmapFactory.decodeStream(photoStream) }
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
            it.toByteArray()
        }
    }

    private fun buildCustomField(cursor: Cursor): Map<String, String> {
        val field = cursor.getString(cursor.getColumnIndex("data2"))
        val label = cursor.getString(cursor.getColumnIndex("data1"))

        return mapOf("field" to field, "label" to label)
    }

    private fun buildInstantMessenger(cursor: Cursor, activity: Activity): Map<String, String> {
        val type = cursor.getInt(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Im.TYPE))
        val customLabel = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Im.LABEL))

        val label = ContactsContract.CommonDataKinds.Im.getTypeLabel(activity.resources, type, customLabel).toString()
        val im = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Im.DATA))

        val protocol = cursor.getInt(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Im.PROTOCOL))
        val customProtocol = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL))
        val actualProtocol = ContactsContract.CommonDataKinds.Im.getProtocolLabel(activity.resources, protocol, customProtocol).toString()
        return mapOf(label(label),
                "im" to im,
                "protocol" to actualProtocol
        )
    }

    private fun buildRelation(cursor: Cursor): Map<String, String> {
        val name = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Relation.NAME))
        val type = when (val typeInt = cursor.getInt(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Relation.TYPE))) {
            ContactsContract.CommonDataKinds.Relation.TYPE_CUSTOM -> cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Relation.LABEL))
            ContactsContract.CommonDataKinds.Relation.TYPE_ASSISTANT -> "assistant"
            ContactsContract.CommonDataKinds.Relation.TYPE_BROTHER -> "brother"
            ContactsContract.CommonDataKinds.Relation.TYPE_CHILD -> "child"
            ContactsContract.CommonDataKinds.Relation.TYPE_DOMESTIC_PARTNER -> "domestic_partner"
            ContactsContract.CommonDataKinds.Relation.TYPE_FATHER -> "father"
            ContactsContract.CommonDataKinds.Relation.TYPE_FRIEND -> "friend"
            ContactsContract.CommonDataKinds.Relation.TYPE_MANAGER -> "manager"
            ContactsContract.CommonDataKinds.Relation.TYPE_MOTHER -> "mother"
            ContactsContract.CommonDataKinds.Relation.TYPE_PARENT -> "parent"
            ContactsContract.CommonDataKinds.Relation.TYPE_PARTNER -> "partner"
            ContactsContract.CommonDataKinds.Relation.TYPE_REFERRED_BY -> "referred_by"
            ContactsContract.CommonDataKinds.Relation.TYPE_RELATIVE -> "relative"
            ContactsContract.CommonDataKinds.Relation.TYPE_SISTER -> "sister"
            ContactsContract.CommonDataKinds.Relation.TYPE_SPOUSE -> "spouse"
            else -> error("Unknown type: $typeInt")
        }

        return mapOf("name" to name, "type" to type)
    }

    private fun buildAddress(cursor: Cursor, activity: Activity): Map<String, Any> {
        val type = cursor.getInt(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.TYPE))
        val customLabel = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.LABEL))

        val label = ContactsContract.CommonDataKinds.StructuredPostal.getTypeLabel(activity.resources, type, customLabel).toString()
        val addressLine = listOf(cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.STREET)))
        val pobox = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.POBOX))
        val neighborhood = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.NEIGHBORHOOD))
        val city = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.CITY))
        val region = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.REGION))
        val postcode = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE))
        val country = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY))

        return mapOf(
                label(label),
                "addressLine" to addressLine,
                "pobox" to pobox,
                "neighborhood" to neighborhood,
                "city" to city,
                "region" to region,
                "postcode" to postcode,
                "country" to country
        )
    }

    private fun buildName(cursor: Cursor): Map<String, String> {
        val firstName = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME))
        val middleName = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME))
        val nickname = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Nickname.NAME))
        val lastName = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME))
        return mapOf("firstName" to firstName, "middleName" to middleName, "nickname" to nickname, "lastName" to lastName)
    }

    private fun buildDisplayNamed(cursor: Cursor, dataName: String, data: Map<String, String>?): Map<String, Any>? {
        if (data == null) {
            return null
        }
        val fullName = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Identity.DISPLAY_NAME)) ?: return null
        return mapOf("fullName" to fullName, dataName to data)
    }

    private fun buildPhoneNumber(cursor: Cursor, activity: Activity, @Suppress("UNUSED_PARAMETER") data: Uri): Map<String, String>? {
        val number = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: return null
        return buildLabeledItem(cursor, activity, ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.LABEL, "phoneNumber", number)
    }

    private fun buildEmailAddress(cursor: Cursor, activity: Activity, @Suppress("UNUSED_PARAMETER") data: Uri): Map<String, String>? {
        val address = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DATA)) ?: return null
        return buildLabeledItem(cursor, activity, ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.LABEL, "email", address)
    }

    private fun buildLabeledItem(cursor: Cursor, activity: Activity, typeContract: String, labelContract: String, dataName: String, data: String): Map<String, String> {
        val type = cursor.getInt(cursor.getColumnIndex(typeContract))
        val customLabel = cursor.getString(cursor.getColumnIndex(labelContract))
        val label = ContactsContract.CommonDataKinds.Email.getTypeLabel(activity.resources, type, customLabel) as String
        return mapOf(dataName to data, label(label))
    }

    private fun label(label: String) = "label" to label

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestPicker()
            } else {
                result.error("INSUFFICIENT_PERMISSIONS", "The READ_CONTACTS permission has not been granted", null)
            }
            PermissionUtil.remove(this)
            return true
        }
        return false
    }

    companion object {

        const val PERMISSION_REQUEST = 5498

        fun requestPicker(requestCode: Int, type: Uri, result: MethodChannel.Result, context: PickContext, askForPermission: Boolean) =
                ContactPicker(context, result, askForPermission, requestCode, type)

    }
}

private fun <T> MutableList<T>.addNotNull(nullable: T?) {
    if (nullable != null) {
        add(nullable)
    }
}
