package com.andyha.p2p.streaming.server.app

import android.app.Application
import com.andyha.coreutils.timber.AppTree
import com.andyha.wifidirectkit.manager.WifiDirectManager
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class ServerApp: Application() {

    @Inject
    lateinit var wifiDirectManager: WifiDirectManager // 이제 정상적으로 주입됩니다.

    override fun onCreate() {
        super.onCreate()
        Timber.plant(AppTree())
        wifiDirectManager.init()
    }
}