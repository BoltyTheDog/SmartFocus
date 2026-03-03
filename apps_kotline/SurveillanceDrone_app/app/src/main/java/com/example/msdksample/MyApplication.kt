package com.example.msdksample

import android.app.Application
import android.content.Context
import com.cySdkyc.clx.Helper

class MyApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // MSDK v5.10.0 之前的版本请使用 com.secneo.sdk.Helper.install(this)
        Helper.install(this)
    }
}