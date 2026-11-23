// Shizuku-API's BinderContainer (github.com/RikkaApps/Shizuku-API,
// provider/src/main/java/moe/shizuku/api/BinderContainer.java, MIT; see
// android/shizuku/NOTICE), in Kotlin. It must keep this package and class
// name and this parcel layout (one strong binder): the shell server
// (at.quickme.prism.shell.Server) hands its binder to apps built on
// rikka.shizuku:provider inside one, and their ShizukuProvider unparcels it
// by this name with its own copy of the class.

package moe.shizuku.api

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

class BinderContainer(@JvmField val binder: IBinder?) : Parcelable {
    private constructor(p: Parcel) : this(p.readStrongBinder())

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeStrongBinder(binder)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<BinderContainer> = object : Parcelable.Creator<BinderContainer> {
            override fun createFromParcel(source: Parcel): BinderContainer = BinderContainer(source)

            override fun newArray(size: Int): Array<BinderContainer?> = arrayOfNulls(size)
        }
    }
}
