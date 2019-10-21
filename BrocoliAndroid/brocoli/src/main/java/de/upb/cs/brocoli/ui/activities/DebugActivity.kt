package de.upb.cs.brocoli.ui.activities

import android.arch.lifecycle.LifecycleOwner
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.support.v7.app.AppCompatActivity
import android.util.Log
import com.github.salomonbrys.kodein.Kodein
import de.upb.cs.brocoli.R
import de.upb.cs.brocoli.library.NetworkService
import de.upb.cs.brocoli.library.ServiceId
import de.upb.cs.brocoli.ui.adapters.DebugPagerAdapter
import kotlinx.android.synthetic.main.activity_debug.*

class DebugActivity : AppCompatActivity(), LifecycleOwner {

    companion object {
        private val TAG = DebugActivity::class.java.simpleName
    }

    private var serviceBinding: NetworkService.NetworkServiceBinder? = null
    private var serviceConnection: ServiceConnection? = null
    var kodein: Kodein? = null

    var kodeinFlag: LiveData<Boolean> = MutableLiveData()


    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_debug)

        val fragmentAdapter = DebugPagerAdapter(supportFragmentManager)
        viewpager_main.adapter = fragmentAdapter

        tabs_main.setupWithViewPager(viewpager_main)

        (kodeinFlag as MutableLiveData).value = false

        super.onCreate(savedInstanceState)
    }


    override fun onStart() {
        super.onStart()

        serviceConnection = object : ServiceConnection {

            override fun onServiceConnected(className: ComponentName,
                                            service: IBinder) {
                Log.d(TAG, "Connected to the service.")
                serviceBinding = service as NetworkService.NetworkServiceBinder

                kodein = serviceBinding!!.service.getKodein()
                (kodeinFlag as MutableLiveData).value = true
            }

            override fun onServiceDisconnected(className: ComponentName) {
            }
        }
        bindService(Intent(this, NetworkService::class.java), serviceConnection,
                Context.BIND_AUTO_CREATE)
    }

    override fun onPause() {
        super.onPause()
        val conn = serviceConnection
        (kodeinFlag as MutableLiveData).value = false

        if (conn != null)
            unbindService(conn)
    }

    fun getServiceName(serviceId: ServiceId):String{
        return serviceBinding?.service?.getServiceNameFromServiceID(serviceId)!!
    }
}
