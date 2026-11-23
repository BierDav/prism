// The binder protocol of Prism's shell server (Server.kt), which runs as
// uid 2000 from Prism's own APK.
//
// The server's binder is Shizuku's: interface moe.shizuku.server.IShizukuService,
// API v13, with the transaction codes and parcel layouts of Shizuku-API's AIDL
// (github.com/RikkaApps/Shizuku-API, aidl/, MIT; android/shizuku/NOTICE), so
// apps built on rikka.shizuku:api use it as they would use Shizuku. Prism
// itself uses the same binder (Shell.kt), as Shizuku's manager app would, plus
// a few manager-only transactions of its own (PRISM_*), and gets the binder
// through its own provider (ShellProvider) rather than a ShizukuProvider.
//
// Both sides are always built from the same APK; Prism only talks to a server
// started from its current APK (Shell.attach), so the Prism-only part needs no
// compatibility across versions. The Shizuku part must stay as it is.

package at.quickme.prism.shell

import android.os.IBinder

internal object Protocol {
    /** The release app; the debug builds' servers find their own package from the APK path (Server.kt). */
    const val PACKAGE = "at.quickme.prism"

    /** Prism's provider (`<package>.shell`), to which the server hands its binder (ShellProvider). */
    const val AUTHORITY_SUFFIX = ".shell"
    const val METHOD_SEND_BINDER = "sendBinder"

    /** A user service's process hands its binder over through Prism's provider (UserServices.kt). */
    const val METHOD_SEND_USER_SERVICE = "sendUserService"

    /** Process name of the server (app_process --nice-name); the cluster checks it with `pidof`. */
    const val SERVER_NAME = "prism_shell"

    // Keys of the sendBinder call's extras and its result.
    const val EXTRA_BINDER = "binder"
    const val EXTRA_APK = "apk"
    const val EXTRA_PID = "pid"
    const val EXTRA_UID = "uid"
    const val EXTRA_CONTEXT = "selinux"
    const val EXTRA_STARTED = "started"
    const val RESULT_TOKEN = "token"
    const val RESULT_ALLOWED = "allowed"
    const val RESULT_DENIED = "denied"

    /** Prism's own callback binder (RESULT_TOKEN): the server asks Prism to show a permission prompt. */
    const val MANAGER_DESCRIPTOR = "at.quickme.prism.shell.IManager"

    /** oneway; in: int uid, int pid, int requestCode, String package, typed ApplicationInfo. */
    const val MANAGER_ASK = IBinder.FIRST_CALL_TRANSACTION

    // --- Shizuku (Shizuku-API v13, aidl/.../IShizukuService.aidl) ---

    const val DESCRIPTOR = "moe.shizuku.server.IShizukuService"
    const val APPLICATION_DESCRIPTOR = "moe.shizuku.server.IShizukuApplication"
    const val PROCESS_DESCRIPTOR = "moe.shizuku.server.IRemoteProcess"
    const val CONNECTION_DESCRIPTOR = "moe.shizuku.server.IShizukuServiceConnection"

    const val SERVER_VERSION = 13
    const val SERVER_PATCH_VERSION = 6

    /** What apps declare (`<uses-permission>`) to get the binder, as with Shizuku. */
    const val API_PERMISSION = "moe.shizuku.manager.permission.API_V23"

    /** The ShizukuProvider of an app: `<package>.shizuku`, method sendBinder, the binder in a BinderContainer. */
    const val CLIENT_AUTHORITY_SUFFIX = ".shizuku"
    const val CLIENT_EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"

    // AIDL: code = FIRST_CALL_TRANSACTION + the id after '='.
    private const val F = IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACT_REMOTE = 1 // ShizukuApiConstants.BINDER_TRANSACTION_transact
    const val GET_VERSION = F + 2
    const val GET_UID = F + 3
    const val CHECK_PERMISSION = F + 4
    const val NEW_PROCESS = F + 7
    const val GET_SELINUX_CONTEXT = F + 8
    const val GET_SYSTEM_PROPERTY = F + 9
    const val SET_SYSTEM_PROPERTY = F + 10
    const val ADD_USER_SERVICE = F + 11
    const val REMOVE_USER_SERVICE = F + 12
    const val ATTACH_APPLICATION_V11 = 14 // before v13
    const val REQUEST_PERMISSION = F + 14
    const val CHECK_SELF_PERMISSION = F + 15
    const val SHOULD_SHOW_RATIONALE = F + 16
    const val ATTACH_APPLICATION = F + 17
    const val EXIT = F + 100
    const val ATTACH_USER_SERVICE = F + 101
    const val DISPATCH_PACKAGE_CHANGED = F + 102
    const val IS_HIDDEN = F + 103
    const val DISPATCH_PERMISSION_RESULT = F + 104
    const val GET_FLAGS_FOR_UID = F + 105
    const val UPDATE_FLAGS_FOR_UID = F + 106

    // Prism's own, manager only.
    /** in: String package, int state (DECISION_*). out: nothing. */
    const val PRISM_SET_DECISION = 20001

    /** in: nothing. out: typed list of ApplicationInfo (apps that declare API_PERMISSION), String[] attached packages. */
    const val PRISM_LIST_APPS = 20002

    const val DECISION_NONE = 0
    const val DECISION_ALLOWED = 1
    const val DECISION_DENIED = 2

    // IShizukuApplication (oneway).
    const val APP_BIND_APPLICATION = F + 1
    const val APP_DISPATCH_PERMISSION_RESULT = F + 2

    // IShizukuServiceConnection (oneway).
    const val CONNECTION_CONNECTED = F
    const val CONNECTION_DIED = F + 1

    // IRemoteProcess.
    const val PROCESS_GET_OUTPUT_STREAM = F
    const val PROCESS_GET_INPUT_STREAM = F + 1
    const val PROCESS_GET_ERROR_STREAM = F + 2
    const val PROCESS_WAIT_FOR = F + 3
    const val PROCESS_EXIT_VALUE = F + 4
    const val PROCESS_DESTROY = F + 5
    const val PROCESS_ALIVE = F + 6
    const val PROCESS_WAIT_FOR_TIMEOUT = F + 7

    /** A user service's binder: Shizuku's destroy transaction (ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy). */
    const val USER_SERVICE_DESTROY = 16777115

    // Bundle keys (ShizukuApiConstants).
    const val BIND_SERVER_VERSION = "shizuku:attach-reply-version"
    const val BIND_SERVER_PATCH_VERSION = "shizuku:attach-reply-patch-version"
    const val BIND_SERVER_UID = "shizuku:attach-reply-uid"
    const val BIND_SERVER_SECONTEXT = "shizuku:attach-reply-secontext"
    const val BIND_PERMISSION_GRANTED = "shizuku:attach-reply-permission-granted"
    const val BIND_SHOULD_SHOW_RATIONALE = "shizuku:attach-reply-should-show-request-permission-rationale"
    const val REPLY_ALLOWED = "shizuku:request-permission-reply-allowed"
    const val REPLY_IS_ONETIME = "shizuku:request-permission-reply-is-onetime"
    const val ATTACH_PACKAGE_NAME = "shizuku:attach-package-name"
    const val ATTACH_API_VERSION = "shizuku:attach-api-version"
    const val US_TAG = "shizuku:user-service-arg-tag"
    const val US_COMPONENT = "shizuku:user-service-arg-component"
    const val US_DEBUGGABLE = "shizuku:user-service-arg-debuggable"
    const val US_VERSION_CODE = "shizuku:user-service-arg-version-code"
    const val US_PROCESS_NAME = "shizuku:user-service-arg-process-name"
    const val US_NO_CREATE = "shizuku:user-service-arg-no-create"
    const val US_DAEMON = "shizuku:user-service-arg-daemon"
    const val US_REMOVE = "shizuku:user-service-remove"
    const val US_TOKEN = "shizuku:user-service-arg-token"
}
