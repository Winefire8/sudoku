package com.sudokuassist

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import com.sudokuassist.capture.CaptureConnection
import com.sudokuassist.capture.CaptureLauncher
import com.sudokuassist.capture.ScreenCaptureService
import com.sudokuassist.databinding.ActivityMainBinding
import com.sudokuassist.overlay.OverlayService
import com.sudokuassist.util.Prefs

/**
 * Settings / launcher screen.
 *
 * Responsibilities:
 *  - request the permissions the assistant needs (overlay, notifications, battery),
 *  - obtain MediaProjection consent and start [ScreenCaptureService],
 *  - start / stop the floating [OverlayService],
 *  - expose preferences and a “test on a sample screenshot” entry point.
 *
 * The activity never captures anything itself; it only hands the one-shot projection
 * token to the service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    /** Start the overlay as soon as the capture session is up. */
    private var startOverlayAfterConsent = false

    /** The user tapped “start”, but the overlay permission was still missing. */
    private var pendingStartOverlay = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshState()
        if (canDrawOverlays() && pendingStartOverlay) {
            pendingStartOverlay = false
            onStartClicked()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshState() }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // The projection token is single-use: hand it to the service immediately.
            CaptureLauncher.markActive(true)
            val intent = ScreenCaptureService.startIntent(this, result.resultCode, data)
            runCatching { ContextCompat.startForegroundService(this, intent) }
                .onFailure {
                    CaptureLauncher.markActive(false)
                    toast(getString(R.string.error_start_capture))
                }
            if (startOverlayAfterConsent) {
                startOverlayAfterConsent = false
                startOverlay()
            }
        } else {
            CaptureLauncher.markActive(false)
            startOverlayAfterConsent = false
            toast(getString(R.string.error_no_capture))
        }
        refreshState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.swAuto.isChecked = prefs.autoRefresh
        binding.swGivens.isChecked = prefs.showGivens
        binding.swMlKit.isChecked = prefs.useMlKit
        binding.sliderScale.value = prefs.answerScale

        binding.swAuto.setOnCheckedChangeListener { _, value ->
            prefs.autoRefresh = value
            CaptureConnection.applyPrefs(this)
        }
        binding.swGivens.setOnCheckedChangeListener { _, value ->
            prefs.showGivens = value
            republish()
        }
        binding.swMlKit.setOnCheckedChangeListener { _, value ->
            prefs.useMlKit = value
            CaptureConnection.preferencesChanged()
        }
        binding.sliderScale.addOnChangeListener { _: Slider, value: Float, _: Boolean ->
            prefs.answerScale = value
            republish()
        }

        binding.btnOverlayPerm.setOnClickListener { requestOverlayPermission() }
        binding.btnNotifPerm.setOnClickListener { requestNotificationPermission() }
        binding.btnBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.btnCapture.setOnClickListener { requestProjection(andThenStart = false) }
        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener { stopEverything() }
        binding.btnTest.setOnClickListener {
            startActivity(Intent(this, TestActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind (only when a session exists — a plain bind would *create* the capture
        // service, which must never happen without a projection token) so preference
        // changes reach the running service.
        if (CaptureLauncher.hasSession()) CaptureConnection.bind(this)
    }

    override fun onStop() {
        CaptureConnection.unbind(this)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_REQUEST_CAPTURE) {
            // The floating panel asked for a capture but no projection session exists.
            startOverlayAfterConsent = CaptureLauncher.hasSession()
            requestProjection(startOverlayAfterConsent)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    // ------------------------------------------------------------------ permissions

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        if (canDrawOverlays()) {
            toast(getString(R.string.granted))
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { overlayPermissionLauncher.launch(intent) }
            .onFailure {
                // Some ROMs do not expose this screen: send the user to the app details.
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                }
                toast(getString(R.string.error_overlay_permission))
            }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toast(getString(R.string.granted))
            return
        }
        if (hasNotificationPermission()) {
            toast(getString(R.string.granted))
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) {
            toast(getString(R.string.granted))
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun refreshState() {
        val overlayOk = canDrawOverlays()
        binding.btnOverlayPerm.text =
            if (overlayOk) getString(R.string.granted) else getString(R.string.grant)
        binding.btnOverlayPerm.isEnabled = !overlayOk

        val notifOk = hasNotificationPermission()
        binding.btnNotifPerm.text =
            if (notifOk) getString(R.string.granted) else getString(R.string.grant)
        binding.btnNotifPerm.isEnabled = !notifOk

        val captureOk = CaptureLauncher.hasSession()
        binding.captureState.text =
            if (captureOk) getString(R.string.capture_granted) else getString(R.string.capture_not_granted)
        binding.captureState.setTextColor(
            ContextCompat.getColor(this, if (captureOk) R.color.ok else R.color.warn)
        )
        binding.btnStart.isEnabled = overlayOk
    }

    // ------------------------------------------------------------------ flow

    private fun onStartClicked() {
        if (!canDrawOverlays()) {
            pendingStartOverlay = true
            toast(getString(R.string.error_overlay_permission))
            requestOverlayPermission()
            return
        }
        if (!CaptureLauncher.hasSession()) {
            // Consent first, then the overlay: the overlay needs a live session to read.
            startOverlayAfterConsent = true
            requestProjection(andThenStart = true)
            return
        }
        startOverlay()
    }

    private fun requestProjection(andThenStart: Boolean) {
        startOverlayAfterConsent = andThenStart
        runCatching { projectionLauncher.launch(CaptureLauncher.consentIntent(this)) }
            .onFailure {
                startOverlayAfterConsent = false
                toast(getString(R.string.error_start_capture))
            }
    }

    private fun startOverlay() {
        if (!canDrawOverlays()) {
            toast(getString(R.string.error_overlay_permission))
            return
        }
        runCatching { OverlayService.start(this) }
            .onFailure { toast(it.message ?: getString(R.string.error_start_capture)) }
        binding.status.text = getString(R.string.status_running)
        toast(getString(R.string.status_running))
    }

    private fun stopEverything() {
        OverlayService.stop(this)
        // Stopping the service tears the projection down as well.
        stopService(Intent(this, ScreenCaptureService::class.java))
        CaptureLauncher.markActive(false)
        binding.status.text = getString(R.string.status_idle)
        refreshState()
    }

    /** Re-renders the overlay so a new answer scale / “show givens” applies at once. */
    private fun republish() {
        val last = com.sudokuassist.util.AppEvents.lastUpdate
        if (last != null) com.sudokuassist.util.AppEvents.publish(last)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** Sent by the floating panel when it needs a new projection session. */
        const val ACTION_REQUEST_CAPTURE = "com.sudokuassist.action.MAIN_REQUEST_CAPTURE"
    }
}
