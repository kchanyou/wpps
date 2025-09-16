package com.andyha.p2p.streaming.client.app

import android.app.Application
import com.andyha.coreutils.timber.AppTree
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject // Import 추가
import com.andyha.wifidirectkit.manager.WifiDirectManager // Import 추가

@HiltAndroidApp
class ClientApp: Application() {

    @Inject
    lateinit var wifiDirectManager: WifiDirectManager // 이제 정상적으로 주입됩니다.

    override fun onCreate() {
        super.onCreate()
        Timber.plant(AppTree())
        wifiDirectManager.init() // 앱이 시작될 때 딱 한 번만 초기화
    }
}