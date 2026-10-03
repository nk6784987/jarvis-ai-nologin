package com.example.permissions

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.device.JarvisAccessibilityService
import com.example.device.ScreenCaptureService

enum class Capability(val label: String, val why: String) {
  MICROPHONE("Microphone", "Aapki awaaz sunne ke liye"),
  NOTIFICATIONS("Notifications", "Reminders dikhane ke liye"),
  CONTACTS("Contacts", "Naam se contact dhoondhne ke liye"),
  PHONE("Phone", "Call lagane ke liye"),
  SMS("SMS", "SMS bhejne ke liye"),
  MEDIA("Photos/Media", "Photos/videos dhoondhne aur attach karne ke liye"),
  FILES("All files access", "Downloads mein PDF/documents dhoondhne ke liye"),
  ACCESSIBILITY("Accessibility Service", "Doosre apps ki screen padhne aur tap/type karne ke liye"),
  SCREEN_CAPTURE("Screen capture", "Screenshot/OCR (Android 11 se neeche) ke liye"),
  EXACT_ALARM("Exact alarms", "Time par exact reminder ke liye")
}

enum class PermState { GRANTED, DENIED, NEEDS_SETTINGS }

/** Single source of truth for what is granted. Request flow lives in the UI (Activity result launchers). */
class PermissionCenter(private val ctx: Context) {

  fun runtimePermissions(c: Capability): Array<String> = when (c) {
    Capability.MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
    Capability.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    Capability.CONTACTS -> arrayOf(Manifest.permission.READ_CONTACTS)
    Capability.PHONE -> arrayOf(Manifest.permission.CALL_PHONE)
    Capability.SMS -> arrayOf(Manifest.permission.SEND_SMS)
    Capability.MEDIA -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
      else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> emptyArray()
  }

  fun state(c: Capability): PermState = when (c) {
    Capability.ACCESSIBILITY -> if (JarvisAccessibilityService.instance != null) PermState.GRANTED else PermState.NEEDS_SETTINGS
    Capability.SCREEN_CAPTURE -> if (ScreenCaptureService.active.value || Build.VERSION.SDK_INT >= 30 && JarvisAccessibilityService.instance != null) PermState.GRANTED else PermState.DENIED
    Capability.FILES -> if (Build.VERSION.SDK_INT >= 30) { if (Environment.isExternalStorageManager()) PermState.GRANTED else PermState.NEEDS_SETTINGS }
      else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) PermState.GRANTED else PermState.DENIED
    Capability.EXACT_ALARM -> if (Build.VERSION.SDK_INT < 31 || (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()) PermState.GRANTED else PermState.NEEDS_SETTINGS
    else -> {
      val perms = runtimePermissions(c)
      if (perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) PermState.GRANTED else PermState.DENIED
    }
  }

  fun isGranted(c: Capability) = state(c) == PermState.GRANTED

  /** For states that can only be granted from system Settings. */
  fun settingsIntent(c: Capability): Intent = when (c) {
    Capability.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    Capability.FILES -> if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
      else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
    Capability.EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
      else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
    else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
  }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  fun snapshot(): Map<Capability, PermState> = Capability.values().associateWith { state(it) }
}
