package com.example.msdksample

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import dji.v5.utils.common.LogUtils
import dji.v5.utils.common.PermissionUtil
import dji.v5.utils.inner.SDKConfig
import dji.v5.ux.core.communication.DefaultGlobalPreferences
import dji.v5.ux.core.communication.GlobalPreferencesManager
import dji.v5.ux.core.util.UxSharedPreferencesUtil
import dji.v5.common.utils.GeoidManager

class MainActivity : AppCompatActivity() {

    private val TAG = this::class.simpleName
    private lateinit var btnReady: Button
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvAircraftName: TextView
    private lateinit var tvRegistrationStatus: TextView
    private lateinit var tvSdkInfo: TextView

    private val permissionArray = arrayListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    init {
        // Add storage permissions for Android versions below 13
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionArray.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissionArray.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        // Add Bluetooth permissions for Android 12+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissionArray.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionArray.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissionArray.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (checkPermissions()) {
            startSDKInit()
        } else {
            Log.e(TAG, "Permissions denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "onCreate")
        
        // Modern way to make it full screen (API 30+)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_main)
        initUI()
        updateRegistrationStatus("Initializing...")
        updateSdkInfo()
        updateConnectionStatus(false)

        if (checkPermissions()) {
            startSDKInit()
        } else {
            requestPermissionLauncher.launch(permissionArray.toTypedArray())
        }
    }

    private fun checkPermissions(): Boolean {
        return permissionArray.all {
            PermissionUtil.isPermissionGranted(this, it)
        }
    }

    private fun startSDKInit() {
        Log.i(TAG, "Starting SDKManager initialization...")

        SDKManager.getInstance().init(this, object : SDKManagerCallback {
            override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                Log.i(TAG, "onInitProcess: $event")
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    SDKManager.getInstance().registerApp()
                }
            }

            override fun onRegisterSuccess() {
                Log.i(TAG, "onRegisterSuccess")
                runOnUiThread {
                    updateRegistrationStatus("Registered")
                    
                    // Initialize UX SDK components
                    UxSharedPreferencesUtil.initialize(applicationContext)
                    GlobalPreferencesManager.initialize(DefaultGlobalPreferences(applicationContext))
                    GeoidManager.getInstance().init(applicationContext)
                    
                    // Start listeners only after successful registration
                    startSDKListeners()
                }
            }

            override fun onRegisterFailure(error: IDJIError?) {
                val errorMsg = "Registration Failed: ${error?.description()}"
                Log.e(TAG, errorMsg)
                runOnUiThread {
                    updateRegistrationStatus("Error: ${error?.errorCode()}")
                    tvConnectionStatus.text = "Check API Key/Network"
                    tvConnectionStatus.setTextColor(Color.parseColor("#FFA500"))
                }
            }

            override fun onProductConnect(productId: Int) {
                Log.i(TAG, "onProductConnect: $productId")
            }

            override fun onProductDisconnect(productId: Int) {
                Log.i(TAG, "onProductDisconnect: $productId")
            }

            override fun onProductChanged(productId: Int) {
                Log.i(TAG, "onProductChanged: $productId")
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                Log.i(TAG, "onDatabaseDownloadProgress: ${current.toFloat() / total}")
            }
        })
    }

    private fun initUI() {
        btnReady = findViewById(R.id.btn_ready)
        tvConnectionStatus = findViewById(R.id.tv_connection_status)
        tvAircraftName = findViewById(R.id.tv_aircraft_name)
        tvRegistrationStatus = findViewById(R.id.tv_registration_status)
        tvSdkInfo = findViewById(R.id.tv_sdk_info)

        btnReady.setOnClickListener {
            val intent = Intent(this, DashboardActivity::class.java)
            startActivity(intent)
        }
    }

    private fun startSDKListeners() {
        Log.i(TAG, "Starting SDK Listeners...")
        
        // Start listening to product type
        ProductKey.KeyProductType.create().listen(this) { type ->
            runOnUiThread {
                tvAircraftName.text = type?.name ?: "N/A"
            }
        }

        // Listen for connection status
        FlightControllerKey.KeyConnection.create().listen(this) { isConnected ->
            runOnUiThread {
                updateConnectionStatus(isConnected ?: false)
            }
        }
    }

    private fun updateRegistrationStatus(status: String) {
        tvRegistrationStatus.text = status
        if (status == "Registered") {
            tvRegistrationStatus.setTextColor(Color.GREEN)
        } else {
            tvRegistrationStatus.setTextColor(Color.WHITE)
        }
    }

    private fun updateSdkInfo() {
        try {
            val sdkConfig = SDKConfig.getInstance()
            val version = sdkConfig.registrationSDKVersion
            val category = sdkConfig.packageProductCategory
            val isDebug = sdkConfig.isDebug
            tvSdkInfo.text = "V: $version | Category: $category | Debug: $isDebug"
        } catch (e: Exception) {
            tvSdkInfo.text = "SDK Info Error"
        }
    }

    private fun updateConnectionStatus(isConnected: Boolean) {
        if (isConnected) {
            tvConnectionStatus.text = "Connected"
            tvConnectionStatus.setTextColor(Color.GREEN)
            
            btnReady.isEnabled = true
            btnReady.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.blue))
        } else {
            tvConnectionStatus.text = "Disconnected"
            tvConnectionStatus.setTextColor(Color.RED)
            tvAircraftName.text = "N/A"
            
            btnReady.isEnabled = false
            btnReady.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.light_grey))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        KeyManager.getInstance().cancelListen(this)
    }
}