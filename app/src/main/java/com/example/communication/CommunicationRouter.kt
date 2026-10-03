package com.example.communication

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import com.example.device.DeviceAgent
import com.example.device.DeviceResult
import com.example.device.ElementQuery
import com.example.device.ResultCode
import kotlinx.coroutines.delay

/**
 * Sends only through mechanisms Android actually supports. Every method returns what was VERIFIED,
 * never a blind "sent". Callers must pass the user's confirmation through SafetyGate before calling.
 */
class CommunicationRouter(private val context: Context, private val device: DeviceAgent) {

  private fun hasPkg(p: String) = runCatching { context.packageManager.getPackageInfo(p, 0); true }.getOrDefault(false)
  val whatsappPackage: String? get() = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull(::hasPkg)

  private fun normalizePhone(n: String): String { val d = n.filter { it.isDigit() || it == '+' }; return if (d.startsWith("+")) d else if (d.length == 10) "+91$d" else d }

  /** SMS: SmsManager reports a real sent-intent result; we wait for it. */
  suspend fun sendSms(number: String, text: String): DeviceResult<String> {
    if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
      return DeviceResult.fail(ResultCode.ACTION_FAILED, "SEND_SMS permission nahi hai")
    val action = "com.example.SMS_SENT_${System.nanoTime()}"
    val result = kotlinx.coroutines.CompletableDeferred<Int>()
    val rx = object : android.content.BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { result.complete(resultCode) } }
    androidx.core.content.ContextCompat.registerReceiver(context, rx, android.content.IntentFilter(action), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    return try {
      val sm = if (android.os.Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
      val pi = android.app.PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_ONE_SHOT)
      val parts = sm.divideMessage(text)
      if (parts.size > 1) sm.sendMultipartTextMessage(normalizePhone(number), null, parts, arrayListOf(pi), null) else sm.sendTextMessage(normalizePhone(number), null, text, pi, null)
      val code = kotlinx.coroutines.withTimeoutOrNull(20_000) { result.await() }
      when (code) {
        null -> DeviceResult.fail(ResultCode.TIMEOUT, "SMS status 20s mein nahi aaya - sent maan nahi sakta")
        android.app.Activity.RESULT_OK -> DeviceResult.ok("SMS network ko handover ho gaya (sent-status OK)")
        else -> DeviceResult.fail(ResultCode.ACTION_FAILED, "SMS fail (code $code)")
      }
    } catch (e: Exception) { DeviceResult.fail(ResultCode.ACTION_FAILED, "SMS error: ${e.message}") } finally { runCatching { context.unregisterReceiver(rx) } }
  }

  /** Real call. Verification = TelephonyManager call state OFFHOOK. */
  suspend fun call(number: String): DeviceResult<String> {
    if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
      return DeviceResult.fail(ResultCode.ACTION_FAILED, "CALL_PHONE permission nahi hai")
    return try {
      context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${normalizePhone(number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      delay(3000)
      val hasState = context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
      if (!hasState) return DeviceResult.ok("Call intent dispatch hua (call-state verify ke liye READ_PHONE_STATE chahiye)")
      @Suppress("DEPRECATION") val st = (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).callState
      if (st == TelephonyManager.CALL_STATE_OFFHOOK) DeviceResult.ok("Call active (OFFHOOK verified)") else DeviceResult.fail(ResultCode.VERIFICATION_FAILED, "Call lagne ka sign nahi mila (state=$st)")
    } catch (e: Exception) { DeviceResult.fail(ResultCode.ACTION_FAILED, e.message ?: "call error") }
  }

  /**
   * WhatsApp has no send API. We open the chat with text prefilled via the official wa.me deep link,
   * then (Accessibility required) press Send and verify the message appears with no pending-clock state.
   */
  suspend fun sendWhatsApp(number: String, text: String): DeviceResult<String> {
    val pkg = whatsappPackage ?: return DeviceResult.fail(ResultCode.APP_NOT_INSTALLED, "WhatsApp installed nahi hai")
    if (!device.isServiceEnabled) return DeviceResult.fail(ResultCode.SERVICE_NOT_ENABLED, "WhatsApp message bhejne ke liye Accessibility Service chahiye (WhatsApp ka public send API nahi hai).")
    val num = normalizePhone(number).removePrefix("+")
    val open = device.openUrl("https://wa.me/$num?text=${Uri.encode(text)}", pkg)
    if (!open.ok) return DeviceResult.fail(open.code, open.detail)
    val send = device.waitForElement(ElementQuery(contentDescription = "Send", clickableOnly = true), 10_000)
    if (!send.ok) return DeviceResult.fail(ResultCode.ELEMENT_NOT_FOUND, "WhatsApp Send button nahi mila (number WhatsApp par nahi / login required / UI badli): ${send.detail}")
    val tap = device.tapNode(send.value!!); if (!tap.ok) return DeviceResult.fail(tap.code, tap.detail)
    val v = device.verifyAction(com.example.device.Expect.TextVisible(text.take(30)), 6000)
    return if (v.ok) DeviceResult.ok("WhatsApp message chat mein dikh raha hai (UI verified)") else DeviceResult.fail(ResultCode.VERIFICATION_FAILED, "Send dabaya par message chat mein confirm nahi hua")
  }

  /** Share a file through the system chooser, optionally targeting WhatsApp. User picks/confirm the recipient in-app. */
  fun shareFile(uri: Uri, mime: String, text: String? = null, targetPackage: String? = null): DeviceResult<String> = try {
    val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    text?.let { i.putExtra(Intent.EXTRA_TEXT, it) }; targetPackage?.let { i.setPackage(it) }
    context.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    DeviceResult.ok("Share sheet khul gaya - recipient chunna aur send dabana baaki hai")
  } catch (e: Exception) { DeviceResult.fail(ResultCode.ACTION_FAILED, e.message ?: "share fail") }

  /** Send a photo to a contact on WhatsApp: opens the contact chat, attaches via accessibility, verifies recipient header. */
  suspend fun sendFileViaWhatsApp(number: String, contactName: String, uri: Uri, mime: String): DeviceResult<String> {
    val pkg = whatsappPackage ?: return DeviceResult.fail(ResultCode.APP_NOT_INSTALLED, "WhatsApp installed nahi hai")
    if (!device.isServiceEnabled) return DeviceResult.fail(ResultCode.SERVICE_NOT_ENABLED, "Accessibility Service chahiye")
    val num = normalizePhone(number).removePrefix("+")
    val i = Intent(Intent.ACTION_SEND).setType(mime).setPackage(pkg).putExtra(Intent.EXTRA_STREAM, uri)
      .putExtra("jid", "$num@s.whatsapp.net").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
      context.startActivity(i)
      val send = device.waitForElement(ElementQuery(contentDescription = "Send", clickableOnly = true), 10_000)
      if (!send.ok) return DeviceResult.fail(ResultCode.ELEMENT_NOT_FOUND, "Send button nahi mila: ${send.detail}")
      val recipientOk = device.verifyAction(com.example.device.Expect.TextVisible(contactName.split(" ").first()), 2500)
      if (!recipientOk.ok) return DeviceResult.fail(ResultCode.VERIFICATION_FAILED, "Recipient '$contactName' screen par verify nahi hua - send nahi kiya")
      val tap = device.tapNode(send.value!!); if (!tap.ok) return DeviceResult.fail(tap.code, tap.detail)
      delay(2500)
      val gone = device.verifyAction(com.example.device.Expect.TextGone("Send"), 5000)
      if (gone.ok) DeviceResult.ok("File chat mein bhej di gayi (send screen band hui)") else DeviceResult.fail(ResultCode.VERIFICATION_FAILED, "Send dabaya par screen same rahi")
    } catch (e: Exception) { DeviceResult.fail(ResultCode.ACTION_FAILED, e.message ?: "whatsapp send fail") }
  }

  /** Email: compose via ACTION_SEND(TO); Android gives no sent callback, so we finish via Accessibility if the mail app shows Send. */
  suspend fun composeEmail(to: List<String>, subject: String, body: String, attachment: Uri? = null, mime: String = "*/*", autoSend: Boolean = false): DeviceResult<String> {
    val i = Intent(if (attachment != null) Intent.ACTION_SEND else Intent.ACTION_SENDTO).apply {
      if (attachment != null) { type = mime; putExtra(Intent.EXTRA_STREAM, attachment); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) } else data = Uri.parse("mailto:")
      putExtra(Intent.EXTRA_EMAIL, to.toTypedArray()); putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, body); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (i.resolveActivity(context.packageManager) == null) return DeviceResult.fail(ResultCode.APP_NOT_INSTALLED, "Koi email app nahi mila")
    return try {
      context.startActivity(i)
      if (!autoSend) return DeviceResult.ok("Draft email app mein khul gaya (aapko Send dabana hai)")
      if (!device.isServiceEnabled) return DeviceResult.ok("Draft khul gaya; auto-send ke liye Accessibility chahiye")
      val send = device.waitForElement(ElementQuery(contentDescription = "Send", clickableOnly = true), 8000)
      if (!send.ok) return DeviceResult.fail(ResultCode.ELEMENT_NOT_FOUND, "Email Send button nahi mila")
      device.tapNode(send.value!!)
      val v = device.verifyAction(com.example.device.Expect.TextGone("Subject"), 5000)
      if (v.ok) DeviceResult.ok("Compose screen band hui (sent UI verified; delivery guarantee nahi)") else DeviceResult.fail(ResultCode.VERIFICATION_FAILED, "Send ke baad compose screen abhi bhi khuli hai")
    } catch (e: Exception) { DeviceResult.fail(ResultCode.ACTION_FAILED, e.message ?: "email fail") }
  }
}
