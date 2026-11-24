package x.vladgba.files.ftp

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.ParcelFileDescriptor

/**
 * Contract between pFiles and the FTP module (shared source, hand-written binder so no AIDL tool is needed).
 * Every method returns a JSON status string: {"ok":true,...} or {"ok":false,"error":"..."}.
 * Bulk data (listings, file content) always travels through pipes, never through the binder buffer.
 * [cfg] is the connection config as JSON (id, host, port, user, pass, anonymous, passive, encoding).
 */
interface IFtpService : IInterface {
    fun version(): Int
    fun connect(cfg: String): String
    fun list(cfg: String, path: String, out: ParcelFileDescriptor): String
    fun download(cfg: String, path: String, out: ParcelFileDescriptor): String
    fun upload(cfg: String, path: String, src: ParcelFileDescriptor): String
    fun mkdir(cfg: String, path: String): String
    fun delete(cfg: String, path: String): String
    fun rename(cfg: String, from: String, to: String): String
    fun disconnect(id: String)
    /** Removes one file or one empty folder (no recursion), so the caller can report progress. */
    fun deleteOne(cfg: String, path: String): String

    abstract class Stub : Binder(), IFtpService {
        init { attachInterface(this, DESCRIPTOR) }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(DESCRIPTOR)
                return true
            }
            if (code !in T_VERSION..T_DELETE_ONE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            try {
                when (code) {
                    T_VERSION -> { reply?.writeNoException(); reply?.writeInt(version()) }
                    T_CONNECT -> { val r = connect(data.readString()!!); reply?.writeNoException(); reply?.writeString(r) }
                    T_LIST -> {
                        val cfg = data.readString()!!; val path = data.readString()!!
                        val r = list(cfg, path, ParcelFileDescriptor.CREATOR.createFromParcel(data))
                        reply?.writeNoException(); reply?.writeString(r)
                    }
                    T_DOWNLOAD -> {
                        val cfg = data.readString()!!; val path = data.readString()!!
                        val r = download(cfg, path, ParcelFileDescriptor.CREATOR.createFromParcel(data))
                        reply?.writeNoException(); reply?.writeString(r)
                    }
                    T_UPLOAD -> {
                        val cfg = data.readString()!!; val path = data.readString()!!
                        val r = upload(cfg, path, ParcelFileDescriptor.CREATOR.createFromParcel(data))
                        reply?.writeNoException(); reply?.writeString(r)
                    }
                    T_MKDIR -> { val r = mkdir(data.readString()!!, data.readString()!!); reply?.writeNoException(); reply?.writeString(r) }
                    T_DELETE -> { val r = delete(data.readString()!!, data.readString()!!); reply?.writeNoException(); reply?.writeString(r) }
                    T_RENAME -> {
                        val r = rename(data.readString()!!, data.readString()!!, data.readString()!!)
                        reply?.writeNoException(); reply?.writeString(r)
                    }
                    T_DELETE_ONE -> { val r = deleteOne(data.readString()!!, data.readString()!!); reply?.writeNoException(); reply?.writeString(r) }
                    T_DISCONNECT -> { disconnect(data.readString()!!); reply?.writeNoException() }
                }
            } catch (e: RuntimeException) {
                reply?.writeException(e)
            }
            return true
        }
    }

    private class Proxy(private val remote: IBinder) : IFtpService {
        override fun asBinder(): IBinder = remote

        private inline fun <T> call(code: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.write()
                remote.transact(code, data, reply, 0)
                reply.readException()
                return reply.read()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun version() = call(T_VERSION, {}, { readInt() })
        override fun connect(cfg: String) = call(T_CONNECT, { writeString(cfg) }, { readString()!! })
        override fun list(cfg: String, path: String, out: ParcelFileDescriptor) =
            call(T_LIST, { writeString(cfg); writeString(path); out.writeToParcel(this, 0) }, { readString()!! })
        override fun download(cfg: String, path: String, out: ParcelFileDescriptor) =
            call(T_DOWNLOAD, { writeString(cfg); writeString(path); out.writeToParcel(this, 0) }, { readString()!! })
        override fun upload(cfg: String, path: String, src: ParcelFileDescriptor) =
            call(T_UPLOAD, { writeString(cfg); writeString(path); src.writeToParcel(this, 0) }, { readString()!! })
        override fun mkdir(cfg: String, path: String) =
            call(T_MKDIR, { writeString(cfg); writeString(path) }, { readString()!! })
        override fun delete(cfg: String, path: String) =
            call(T_DELETE, { writeString(cfg); writeString(path) }, { readString()!! })
        override fun rename(cfg: String, from: String, to: String) =
            call(T_RENAME, { writeString(cfg); writeString(from); writeString(to) }, { readString()!! })
        override fun deleteOne(cfg: String, path: String) =
            call(T_DELETE_ONE, { writeString(cfg); writeString(path) }, { readString()!! })
        override fun disconnect(id: String) = call(T_DISCONNECT, { writeString(id) }, {})
    }

    companion object {
        const val DESCRIPTOR = "x.vladgba.files.ftp.IFtpService"
        private const val T_VERSION = 1
        private const val T_CONNECT = 2
        private const val T_LIST = 3
        private const val T_DOWNLOAD = 4
        private const val T_UPLOAD = 5
        private const val T_MKDIR = 6
        private const val T_DELETE = 7
        private const val T_RENAME = 8
        private const val T_DISCONNECT = 9
        private const val T_DELETE_ONE = 10

        fun asInterface(binder: IBinder?): IFtpService? {
            if (binder == null) return null
            return (binder.queryLocalInterface(DESCRIPTOR) as? IFtpService) ?: Proxy(binder)
        }
    }
}
