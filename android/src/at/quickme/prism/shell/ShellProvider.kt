// Where the shell server hands Prism its binder, as Shizuku's server does
// with its manager's provider ("sendBinder"), and where a user service's
// process hands over its own ("sendUserService", UserServices.kt). Only the
// shell user may call it: the manifest requires INTERACT_ACROSS_USERS_FULL to
// reach it at all (the shell has it, apps don't), and call() checks the uid.
//
// A provider is created when Prism's process starts, before anything else
// runs, so it also starts watching for the server (Shell.init).

package at.quickme.prism.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

class ShellProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        Shell.init(context!!)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (Binder.getCallingUid() != Process.SHELL_UID) {
            throw SecurityException("only the shell user may hand over the shell server")
        }
        if (extras == null) return null
        val id = Binder.clearCallingIdentity()
        try {
            return when (method) {
                Protocol.METHOD_SEND_BINDER -> Shell.attach(extras)
                Protocol.METHOD_SEND_USER_SERVICE -> Shell.attachUserService(extras)
                else -> null
            }
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
