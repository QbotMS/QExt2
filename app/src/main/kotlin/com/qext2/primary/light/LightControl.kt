package com.qext2.primary.light

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.util.Log

/**
 * Sterowanie trybem lampy ANT+ przez WEWNETRZNA usluge czujnikow Karoo (nieoficjalne -- aktualizacja Karoo OS moze to zepsuc).
 * Na podstawie kxradar (github.com/itxsvv/kxradar, Apache-2.0), ktory opiera sie na karoofirefly (github.com/derstrassi/karoofirefly).
 * SensorServiceAIDL: transakcja 17 = getLightCommandConnection(); LightCommandConnectionAIDL: transakcja 3 = setLightMode(id, device, bundle).
 * Kazdy blad -> false (pole SWIATLO pokazuje wtedy tylko stan, tapniecie nic nie psuje).
 */
class LightControl(private val context: Context) {
    companion object {
        private const val TAG = "QEXT_LIGHT"
        private const val SENSOR_PKG = "io.hammerhead.sensorservice"
        private const val SENSOR_DESCRIPTOR = "io.hammerhead.sensorservice.SensorServiceAIDL"
        private const val LIGHT_CMD_DESCRIPTOR = "io.hammerhead.sensorservice.LightCommandConnectionAIDL"
        private const val TX_GET_LIGHT_CMD = 17
        private const val TX_SET_LIGHT_MODE = 3
    }

    @Volatile private var lightCmdBinder: IBinder? = null
    private var modeCreator: ((String) -> Parcelable)? = null
    private var deviceCreator: ((String) -> Parcelable)? = null
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) return
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SENSOR_DESCRIPTOR)
                service.transact(TX_GET_LIGHT_CMD, data, reply, 0)
                reply.readException()
                lightCmdBinder = reply.readStrongBinder()
            } catch (e: Exception) {
                Log.w(TAG, "light binder fail ${e.message}")
            } finally {
                data.recycle(); reply.recycle()
            }
            loadClasses()
            Log.i(TAG, "SensorService connected light=${lightCmdBinder != null} mode=${modeCreator != null}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            lightCmdBinder = null
        }
    }

    private fun loadClasses() {
        try {
            val ctx = context.createPackageContext(SENSOR_PKG, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
            val cl = ctx.classLoader
            @Suppress("UNCHECKED_CAST")
            val modeCls = cl.loadClass("io.hammerhead.datamodels.timeseriesData.models.LightMode") as Class<out Enum<*>>
            modeCreator = { n -> modeCls.enumConstants!!.first { it.name == n } as Parcelable }
            val devCls = cl.loadClass("io.hammerhead.datamodels.timeseriesData.models.Device")
            val marker = cl.loadClass("kotlin.jvm.internal.DefaultConstructorMarker")
            val ctor = devCls.constructors.find { c -> c.parameterTypes.lastOrNull() == marker && c.parameterTypes[c.parameterTypes.size - 2] == Int::class.javaPrimitiveType }
            val infoCtor = cl.loadClass("io.hammerhead.datamodels.timeseriesData.models.DeviceInfo").getDeclaredConstructor()
            if (ctor != null) deviceCreator = { uid ->
                val p = arrayOfNulls<Any>(ctor.parameterTypes.size)
                p[0] = uid; p[1] = infoCtor.newInstance(); p[4] = false; p[5] = true
                p[p.size - 2] = 0x1FFFC; p[p.size - 1] = null
                ctor.newInstance(*p) as Parcelable
            }
        } catch (e: Exception) {
            Log.w(TAG, "classes fail ${e.message}")
        }
    }

    fun bind() {
        if (bound) return
        try {
            bound = context.bindService(Intent().setComponent(ComponentName(SENSOR_PKG, "$SENSOR_PKG.service.SensorService")), conn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.w(TAG, "bind fail ${e.message}")
        }
    }

    fun ready(): Boolean = lightCmdBinder != null && modeCreator != null

    fun setMode(deviceId: String, karooMode: String): Boolean {
        val b = lightCmdBinder ?: return false
        val mode = try { modeCreator?.invoke(karooMode) } catch (e: Exception) { null } ?: return false
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(LIGHT_CMD_DESCRIPTOR)
            data.writeString(deviceId)
            val dev = try { deviceCreator?.invoke(deviceId) } catch (e: Exception) { null }
            if (dev != null) { data.writeInt(1); dev.writeToParcel(data, 0) } else data.writeInt(0)
            val bundle = Bundle(); bundle.classLoader = mode.javaClass.classLoader; bundle.putParcelable("value", mode)
            data.writeInt(1); bundle.writeToParcel(data, 0)
            b.transact(TX_SET_LIGHT_MODE, data, reply, 0)
            reply.readException()
            true
        } catch (e: Exception) {
            Log.w(TAG, "setMode fail ${e.message}")
            false
        } finally {
            data.recycle(); reply.recycle()
        }
    }
}
