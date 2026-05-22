// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.activities

import android.Manifest.permission
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.camera.StillImageCameraHelper.OnFilePickerResult
import org.citra.citra_emu.contracts.OpenFileResultContract
import org.citra.citra_emu.databinding.ActivityEmulationBinding
import org.citra.citra_emu.dualdevice.DualDeviceCastHost
import org.citra.citra_emu.dualdevice.TvMainScreenCastHost
import org.citra.citra_emu.display.ScreenAdjustmentUtil
import org.citra.citra_emu.display.SecondaryDisplay
import org.citra.citra_emu.features.hotkeys.HotkeyUtility
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.SettingsViewModel
import org.citra.citra_emu.features.settings.model.view.InputBindingSetting
import org.citra.citra_emu.fragments.EmulationFragment
import org.citra.citra_emu.fragments.MessageDialogFragment
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.utils.BuildUtil
import org.citra.citra_emu.utils.ControllerMappingHelper
import org.citra.citra_emu.utils.FileBrowserHelper
import org.citra.citra_emu.utils.EmulationLifecycleUtil
import org.citra.citra_emu.utils.EmulationMenuSettings
import org.citra.citra_emu.utils.Log
import org.citra.citra_emu.utils.RefreshRateUtil
import org.citra.citra_emu.utils.ThemeUtil
import org.citra.citra_emu.viewmodel.EmulationViewModel

class EmulationActivity : AppCompatActivity() {
    private val preferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
    var isActivityRecreated = false
    private val emulationViewModel: EmulationViewModel by viewModels()
    val settingsViewModel: SettingsViewModel by viewModels()

    private lateinit var binding: ActivityEmulationBinding
    private lateinit var screenAdjustmentUtil: ScreenAdjustmentUtil
    private lateinit var hotkeyUtility: HotkeyUtility
    private lateinit var secondaryDisplay: SecondaryDisplay
    private var dualDeviceCastHost: DualDeviceCastHost? = null
    private var tvMainScreenCastHost: TvMainScreenCastHost? = null

    private val onShutdown = Runnable {
        if (intent.getBooleanExtra("launched_from_shortcut", false)) {
            finishAffinity()
        } else {
            this.finish()
        }
    }

    private val emulationFragment: EmulationFragment
        get() {
            val navHostFragment =
                supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
            return navHostFragment.getChildFragmentManager().fragments.last() as EmulationFragment
        }

    private var isRotationBlocked: Boolean = true
    private var isEmulationRunning: Boolean = false
    private var isEmulationReady: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        RefreshRateUtil.enforceRefreshRate(this, sixtyHz = true)

        ThemeUtil.setTheme(this)
        settingsViewModel.settings.loadSettings()

        screenAdjustmentUtil = ScreenAdjustmentUtil(this, windowManager, settingsViewModel.settings)

        // Block orientation until emulation is ready to prevent unneccesary
        // surface recreation until the renderer is ready.
        isRotationBlocked = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

        super.onCreate(savedInstanceState)

        secondaryDisplay = SecondaryDisplay(this)
        secondaryDisplay.updateDisplay()

        binding = ActivityEmulationBinding.inflate(layoutInflater)
        hotkeyUtility = HotkeyUtility(screenAdjustmentUtil, this)
        setContentView(binding.root)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        val navController = navHostFragment.navController

        isActivityRecreated = savedInstanceState != null

        // Set these options now so that the SurfaceView the game renders into is the right size.
        enableFullscreenImmersive()

        // Override Citra core INI with the one set by our in game menu
        NativeLibrary.swapScreens(
            EmulationMenuSettings.swapScreens,
            windowManager.defaultDisplay.rotation
        )

        EmulationLifecycleUtil.addShutdownHook(onShutdown)

        val game = gameFromIntent(intent) ?: return
        when {
            intent.getBooleanExtra(EXTRA_DUAL_DEVICE_CAST_ON_START, false) ->
                prepareDualDeviceCastBeforeGame(game, navController)
            intent.getBooleanExtra(EXTRA_TV_MAIN_SCREEN_CAST_ON_START, false) ->
                prepareTvMainScreenCastBeforeGame(game, navController)
            else -> startEmulationGraph(game, navController)
        }
    }

    private fun gameFromIntent(intent: Intent): Game? {
        return try {
            intent.extras?.let { extras ->
                BundleCompat.getParcelable(extras, "game", Game::class.java)
            } ?: run {
                Log.error("[EmulationActivity] Missing game data in intent extras")
                null
            }
        } catch (e: Exception) {
            Log.error("[EmulationActivity] Failed to retrieve game data: ${e.message}")
            null
        }
    }

    private fun startEmulationGraph(game: Game, navController: NavController) {
        if (isEmulationRunning) {
            return
        }

        navController.setGraph(R.navigation.emulation_navigation, intent.extras)
        isEmulationRunning = true
        instance = this
        NativeLibrary.playTimeManagerStart(game.titleId)
    }

    private fun prepareDualDeviceCastBeforeGame(
        game: Game,
        navController: NavController,
    ) {
        try {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            val host = createDualDeviceCastHost(protectNativeSurface = isVulkanRendererSelected())
            host.start()
            dualDeviceCastHost = host
            host.showPairingDialog(
                onStartGame = {
                    startEmulationGraph(game, navController)
                },
                onCancelBeforeGame = {
                    finish()
                }
            )
        } catch (e: Exception) {
            applyOrientationSettings()
            Toast.makeText(
                this,
                getString(R.string.dual_device_cast_error, e.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
            startEmulationGraph(game, navController)
        }
    }

    private fun prepareTvMainScreenCastBeforeGame(
        game: Game,
        navController: NavController,
    ) {
        try {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            val host = createTvMainScreenCastHost(protectNativeSurface = isVulkanRendererSelected())
            host.start()
            tvMainScreenCastHost = host
            host.showPairingDialog(
                onStartGame = {
                    startEmulationGraph(game, navController)
                },
                onCancelBeforeGame = {
                    finish()
                }
            )
        } catch (e: Exception) {
            applyOrientationSettings()
            Toast.makeText(
                this,
                getString(R.string.tv_main_screen_cast_error, e.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
            startEmulationGraph(game, navController)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        NativeLibrary.stopEmulation()
        NativeLibrary.playTimeManagerStop()

        isEmulationReady = false
        isRotationBlocked = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        emulationViewModel.setEmulationStarted(false)

        val game = intent.extras?.let { extras ->
            BundleCompat.getParcelable(extras, "game", Game::class.java)
        }
        if (game != null) {
            NativeLibrary.playTimeManagerStart(game.titleId)
        }

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        navHostFragment.navController.setGraph(R.navigation.emulation_navigation, intent.extras)
    }

    // On some devices, the system bars will not disappear on first boot or after some
    // rotations. Here we set full screen immersive repeatedly in onResume and in
    // onWindowFocusChanged to prevent the unwanted status bar state.
    override fun onResume() {
        enableFullscreenImmersive()
        if (isEmulationReady) {
            // If emulation is ready then unblock rotation
            isRotationBlocked = false
            applyOrientationSettings()
            emulationViewModel.setEmulationStarted(true)
        } else {
            if (!isRotationBlocked) {
                applyOrientationSettings()
            }
        }
        super.onResume()
    }

    override fun onStop() {
        if (!isSecondaryCastRunning()) {
            secondaryDisplay.releasePresentation()
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        enableFullscreenImmersive()
        super.onWindowFocusChanged(hasFocus)
    }

    public override fun onRestart() {
        super.onRestart()
        if (!isSecondaryCastRunning()) {
            secondaryDisplay.updateDisplay()
        }
        NativeLibrary.reloadCameraDevices()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isEmulationRunning", isEmulationRunning)
        outState.putBoolean("isEmulationReady", isEmulationReady)
        outState.putBoolean("isRotationBlocked", isRotationBlocked)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        isEmulationRunning = savedInstanceState.getBoolean("isEmulationRunning", false)
        isEmulationReady = savedInstanceState.getBoolean("isEmulationReady", false)
        isRotationBlocked = savedInstanceState.getBoolean("isRotationBlocked", isRotationBlocked)
    }

    override fun onDestroy() {
        EmulationLifecycleUtil.removeHook(onShutdown)
        NativeLibrary.playTimeManagerStop()
        dualDeviceCastHost?.close()
        dualDeviceCastHost = null
        tvMainScreenCastHost?.close()
        tvMainScreenCastHost = null
        isEmulationRunning = false
        instance = null
        secondaryDisplay.releasePresentation()
        secondaryDisplay.releaseVD()

        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            NativeLibrary.REQUEST_CODE_NATIVE_CAMERA -> {
                if (grantResults[0] != PackageManager.PERMISSION_GRANTED &&
                    shouldShowRequestPermissionRationale(permission.CAMERA)
                ) {
                    MessageDialogFragment.newInstance(
                        R.string.camera,
                        R.string.camera_permission_needed
                    ).show(supportFragmentManager, MessageDialogFragment.TAG)
                }
                NativeLibrary.cameraPermissionResult(
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                )
            }

            NativeLibrary.REQUEST_CODE_NATIVE_MIC -> {
                if (grantResults[0] != PackageManager.PERMISSION_GRANTED &&
                    shouldShowRequestPermissionRationale(permission.RECORD_AUDIO)
                ) {
                    MessageDialogFragment.newInstance(
                        R.string.microphone,
                        R.string.microphone_permission_needed
                    ).show(supportFragmentManager, MessageDialogFragment.TAG)
                }
                NativeLibrary.micPermissionResult(
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                )
            }

            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    fun onEmulationStarted() {
        emulationViewModel.setEmulationStarted(true)
        isEmulationReady = true
        dualDeviceCastHost?.onEmulationStarted()
        tvMainScreenCastHost?.onEmulationStarted()
        if (isRotationBlocked) {
            isRotationBlocked = false
            applyOrientationSettings()
        }
        Toast.makeText(
            applicationContext,
            getString(R.string.emulation_menu_help),
            Toast.LENGTH_LONG
        ).show()
    }

    fun toggleDualDeviceCast() {
        val currentHost = dualDeviceCastHost
        if (currentHost?.isRunning == true) {
            if (currentHost.requestStop()) {
                dualDeviceCastHost = null
            }
            return
        }
        tvMainScreenCastHost?.close()
        tvMainScreenCastHost = null

        if (isVulkanRendererSelected()) {
            Toast.makeText(
                this,
                R.string.dual_device_cast_vulkan_start_before_game,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            val host = createDualDeviceCastHost(protectNativeSurface = false)
            host.start()
            dualDeviceCastHost = host
            host.showPairingDialog()
        } catch (e: Exception) {
            applyOrientationSettings()
            Toast.makeText(
                this,
                getString(R.string.dual_device_cast_error, e.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun isDualDeviceCastRunning(): Boolean = dualDeviceCastHost?.isRunning == true

    fun toggleTvMainScreenCast() {
        val currentHost = tvMainScreenCastHost
        if (currentHost?.isRunning == true) {
            if (currentHost.requestStop()) {
                tvMainScreenCastHost = null
            }
            return
        }
        dualDeviceCastHost?.close()
        dualDeviceCastHost = null

        if (isVulkanRendererSelected()) {
            Toast.makeText(
                this,
                R.string.tv_main_screen_cast_vulkan_start_before_game,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            val host = createTvMainScreenCastHost(protectNativeSurface = false)
            host.start()
            tvMainScreenCastHost = host
            host.showPairingDialog()
        } catch (e: Exception) {
            applyOrientationSettings()
            Toast.makeText(
                this,
                getString(R.string.tv_main_screen_cast_error, e.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun isTvMainScreenCastRunning(): Boolean = tvMainScreenCastHost?.isRunning == true

    private fun isSecondaryCastRunning(): Boolean =
        dualDeviceCastHost?.isRunning == true || tvMainScreenCastHost?.isRunning == true

    private fun createDualDeviceCastHost(protectNativeSurface: Boolean): DualDeviceCastHost =
        DualDeviceCastHost(
            activity = this,
            settings = settingsViewModel.settings,
            releaseSecondaryDisplay = { secondaryDisplay.releasePresentation() },
            restoreSecondaryDisplay = { secondaryDisplay.updateDisplay() },
            protectNativeSurface = protectNativeSurface,
            onStopped = {
                if (!isSecondaryCastRunning()) {
                    applyOrientationSettings()
                }
            }
        )

    private fun createTvMainScreenCastHost(protectNativeSurface: Boolean): TvMainScreenCastHost =
        TvMainScreenCastHost(
            activity = this,
            settings = settingsViewModel.settings,
            releaseSecondaryDisplay = { secondaryDisplay.releasePresentation() },
            restoreSecondaryDisplay = { secondaryDisplay.updateDisplay() },
            protectNativeSurface = protectNativeSurface,
            onStopped = {
                if (!isSecondaryCastRunning()) {
                    applyOrientationSettings()
                }
            }
        )

    private fun isVulkanRendererSelected(): Boolean =
        IntSetting.GRAPHICS_API.int == GRAPHICS_API_VULKAN

    private fun enableFullscreenImmersive() {
        val attributes = window.attributes

        attributes.layoutInDisplayCutoutMode =
            if (BooleanSetting.EXPAND_TO_CUTOUT_AREA.boolean) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }

        window.attributes = attributes

        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun applyOrientationSettings() {
        if (isSecondaryCastRunning()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            return
        }
        val orientationOption = IntSetting.ORIENTATION_OPTION.int
        screenAdjustmentUtil.changeActivityOrientation(orientationOption)
    }

    // Gets button presses
    @Suppress("DEPRECATION")
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // TODO: Move this check into native code - prevents crash if input pressed before starting emulation
        if (!NativeLibrary.isRunning()) {
            return false
        }

        if (emulationFragment.isDrawerOpen()) {
            return super.dispatchKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // On some devices, the back gesture / button press is not intercepted by androidx
                // and fails to open the emulation menu. So we're stuck running deprecated code to
                // cover for either a fault on androidx's side or in OEM skins (MIUI at least)

                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    // If the hotkey is pressed, we don't want to open the drawer
                    if (!hotkeyUtility.hotkeyIsPressed) {
                        onBackPressed()
                        return true
                    }
                }
                return hotkeyUtility.handleKeyPress(event)
            }
            KeyEvent.ACTION_UP -> {
                return hotkeyUtility.handleKeyRelease(event)
            }
            else -> {
                return false;
            }
        }
    }

    private fun onAmiiboSelected(selectedFile: String) {
        val success = NativeLibrary.loadAmiibo(selectedFile)
        if (!success) {
            Log.error("[EmulationActivity] Failed to load Amiibo file: $selectedFile")
            MessageDialogFragment.newInstance(
                R.string.amiibo_load_error,
                R.string.amiibo_load_error_message
            ).show(supportFragmentManager, MessageDialogFragment.TAG)
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // TODO: Move this check into native code - prevents crash if input pressed before starting emulation
        if (!NativeLibrary.isRunning() ||
            (event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0) ||
            emulationFragment.isDrawerOpen()) {
            return super.dispatchGenericMotionEvent(event)
        }

        // Don't attempt to do anything if we are disconnecting a device.
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            return true
        }
        val input = event.device
        val motions = input.motionRanges
        val axisValuesCirclePad = floatArrayOf(0.0f, 0.0f)
        val axisValuesCStick = floatArrayOf(0.0f, 0.0f)
        val axisValuesDPad = floatArrayOf(0.0f, 0.0f)
        var isTriggerPressedLMapped = false
        var isTriggerPressedRMapped = false
        var isTriggerPressedZLMapped = false
        var isTriggerPressedZRMapped = false
        var isTriggerPressedL = false
        var isTriggerPressedR = false
        var isTriggerPressedZL = false
        var isTriggerPressedZR = false
        for (range in motions) {
            val axis = range.axis
            val origValue = event.getAxisValue(axis)
            var value = ControllerMappingHelper.scaleAxis(input, axis, origValue)
            val nextMapping =
                preferences.getInt(InputBindingSetting.getInputAxisButtonKey(axis), -1)
            val guestOrientation =
                preferences.getInt(InputBindingSetting.getInputAxisOrientationKey(axis), -1)
            val inverted = preferences.getBoolean(InputBindingSetting.getInputAxisInvertedKey(axis),false);
            if (nextMapping == -1 || guestOrientation == -1) {
                // Axis is unmapped
                continue
            }
            if (value > 0f && value < 0.1f || value < 0f && value > -0.1f) {
                // Skip joystick wobble
                value = 0f
            }
            if (inverted) value = -value;

            when (nextMapping) {
                NativeLibrary.ButtonType.STICK_LEFT -> {
                    axisValuesCirclePad[guestOrientation] = value
                }

                NativeLibrary.ButtonType.STICK_C -> {
                    axisValuesCStick[guestOrientation] = value
                }

                NativeLibrary.ButtonType.DPAD -> {
                    axisValuesDPad[guestOrientation] = value
                }

                NativeLibrary.ButtonType.TRIGGER_L -> {
                    isTriggerPressedLMapped = true
                    isTriggerPressedL = value != 0f
                }

                NativeLibrary.ButtonType.TRIGGER_R -> {
                    isTriggerPressedRMapped = true
                    isTriggerPressedR = value != 0f
                }

                NativeLibrary.ButtonType.BUTTON_ZL -> {
                    isTriggerPressedZLMapped = true
                    isTriggerPressedZL = value != 0f
                }

                NativeLibrary.ButtonType.BUTTON_ZR -> {
                    isTriggerPressedZRMapped = true
                    isTriggerPressedZR = value != 0f
                }
            }
        }

        // Circle-Pad and C-Stick status
        NativeLibrary.onGamePadMoveEvent(
            input.descriptor,
            NativeLibrary.ButtonType.STICK_LEFT,
            axisValuesCirclePad[0],
            axisValuesCirclePad[1]
        )
        NativeLibrary.onGamePadMoveEvent(
            input.descriptor,
            NativeLibrary.ButtonType.STICK_C,
            axisValuesCStick[0],
            axisValuesCStick[1]
        )

        // Triggers L/R and ZL/ZR
        if (isTriggerPressedLMapped) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.TRIGGER_L,
                if (isTriggerPressedL) {
                    NativeLibrary.ButtonState.PRESSED
                } else {
                    NativeLibrary.ButtonState.RELEASED
                }
            )
        }
        if (isTriggerPressedRMapped) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.TRIGGER_R,
                if (isTriggerPressedR) {
                    NativeLibrary.ButtonState.PRESSED
                } else {
                    NativeLibrary.ButtonState.RELEASED
                }
            )
        }
        if (isTriggerPressedZLMapped) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.BUTTON_ZL,
                if (isTriggerPressedZL) {
                    NativeLibrary.ButtonState.PRESSED
                } else {
                    NativeLibrary.ButtonState.RELEASED
                }
            )
        }
        if (isTriggerPressedZRMapped) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.BUTTON_ZR,
                if (isTriggerPressedZR) {
                    NativeLibrary.ButtonState.PRESSED
                } else {
                    NativeLibrary.ButtonState.RELEASED
                }
            )
        }

        // Work-around to allow D-pad axis to be bound to emulated buttons
        if (axisValuesDPad[0] == 0f) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_LEFT,
                NativeLibrary.ButtonState.RELEASED
            )
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_RIGHT,
                NativeLibrary.ButtonState.RELEASED
            )
        }
        if (axisValuesDPad[0] < 0f) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_LEFT,
                NativeLibrary.ButtonState.PRESSED
            )
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_RIGHT,
                NativeLibrary.ButtonState.RELEASED
            )
        }
        if (axisValuesDPad[0] > 0f) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_LEFT,
                NativeLibrary.ButtonState.RELEASED
            )
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_RIGHT,
                NativeLibrary.ButtonState.PRESSED
            )
        }
        if (axisValuesDPad[1] == 0f) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_UP,
                NativeLibrary.ButtonState.RELEASED
            )
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_DOWN,
                NativeLibrary.ButtonState.RELEASED
            )
        }
        if (axisValuesDPad[1] < 0f) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_UP,
                NativeLibrary.ButtonState.PRESSED
            )
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_DOWN,
                NativeLibrary.ButtonState.RELEASED
            )
        }
        if (axisValuesDPad[1] > 0f) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_UP,
                NativeLibrary.ButtonState.RELEASED
            )
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_DOWN,
                NativeLibrary.ButtonState.PRESSED
            )
        }
        return true
    }

    val openAmiiboFileLauncher =
        registerForActivityResult(OpenFileResultContract()) { result: Intent? ->
            if (result == null) return@registerForActivityResult
            val selectedFiles = FileBrowserHelper.getSelectedFiles(
                result, applicationContext, listOf<String>("bin")
            ) ?: return@registerForActivityResult
            if (BuildUtil.isGooglePlayBuild) {
                onAmiiboSelected(selectedFiles[0])
            } else {
                val fileUri = selectedFiles[0].toUri()
                val nativePath = "!" + NativeLibrary.getNativePath(fileUri)
                onAmiiboSelected(nativePath)
            }
        }

    val openImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { result: Uri? ->
            if (result == null) {
                return@registerForActivityResult
            }

            OnFilePickerResult(result.toString())
        }

    companion object {
        const val EXTRA_DUAL_DEVICE_CAST_ON_START = "dual_device_cast_on_start"
        const val EXTRA_TV_MAIN_SCREEN_CAST_ON_START = "tv_main_screen_cast_on_start"
        private const val GRAPHICS_API_VULKAN = 2
        private var instance: EmulationActivity? = null

        fun isRunning(): Boolean {
            return instance?.isEmulationRunning ?: false
        }
    }
}
