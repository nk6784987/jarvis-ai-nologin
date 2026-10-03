package com.example.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.device.DeviceAgent
import com.example.device.ResultCode
import com.example.device.ScreenCaptureService
import com.example.device.UiNode
import com.example.device.WindowSnapshot
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

enum class ElementType { BUTTON, TEXT, ICON, IMAGE, VIDEO, INPUT, LINK, LIST, LIST_ITEM, MENU, DIALOG, TAB, SCROLL_AREA, CARD, CHECKBOX, SWITCH, SLIDER, LOADING, ERROR, OCR_TEXT }

data class VisualElement(
  val id: String,
  val type: ElementType,
  val text: String,
  val bounds: Rect,
  val source: Source,
  val clickable: Boolean,
  val enabled: Boolean = true,
  val checked: Boolean? = null,
  val nodePath: List<Int>? = null
) { enum class Source { ACCESSIBILITY, OCR } }

data class SemanticScreen(
  val currentApp: String,
  val elements: List<VisualElement>,
  val summary: String,
  val activeError: String?,
  val ocrAvailable: Boolean,
  val ocrError: String? = null,
  val screenshot: Bitmap? = null
)

/**
 * Pipeline: screenshot -> OCR (ML Kit Latin + Devanagari) -> accessibility tree -> merge/dedupe ->
 * typed semantic model. Elements are derived ONLY from the live device; nothing is canned.
 */
class VisualIntelligenceEngine(private val device: DeviceAgent) {
  private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
  private val deva by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }

  suspend fun analyzeCurrentScreen(withOcr: Boolean = true): com.example.device.DeviceResult<SemanticScreen> {
    val st = device.getScreenState()
    val snap: WindowSnapshot? = st.value
    if (snap == null && !withOcr) return com.example.device.DeviceResult.fail(st.code, st.detail)

    val elements = mutableListOf<VisualElement>()
    snap?.nodes?.filter { it.visible && it.bounds.width() > 4 && it.bounds.height() > 4 }?.forEach { n ->
      classify(n)?.let { elements += it }
    }

    var ocrError: String? = null
    var bmp: Bitmap? = null
    var ocrOk = false
    if (withOcr) {
      val shot = device.takeScreenshot()
      bmp = if (shot.ok) shot.value else ScreenCaptureService.capture()
      if (bmp == null) ocrError = "Screenshot unavailable (${shot.detail})"
      else try {
        val pre = preprocess(bmp)
        val img = InputImage.fromBitmap(pre, 0)
        val scale = pre.width.toFloat() / bmp.width.toFloat()  // OCR boxes -> real screen pixels
        val blocks = (latin.process(img).await().textBlocks + deva.process(img).await().textBlocks)
        ocrOk = true
        blocks.flatMap { it.lines }.forEachIndexed { i, line ->
          val b = line.boundingBox ?: return@forEachIndexed
          val r = Rect((b.left / scale).toInt(), (b.top / scale).toInt(), (b.right / scale).toInt(), (b.bottom / scale).toInt())
          val dup = elements.any { it.text.equals(line.text, true) || (it.bounds.contains(r.centerX(), r.centerY()) && it.text.isNotBlank() && it.text.contains(line.text, true)) }
          if (!dup) elements += VisualElement("ocr$i", ElementType.OCR_TEXT, line.text, r, VisualElement.Source.OCR, clickable = false)
        }
      } catch (e: Exception) { ocrError = "OCR fail: ${e.message}" }
    }
    val ordered = elements.sortedWith(compareBy({ it.bounds.top / 40 }, { it.bounds.left }))
    val appPkg = snap?.packageName ?: "unknown"
    val err = ordered.firstOrNull { it.type == ElementType.ERROR }?.text
    return com.example.device.DeviceResult.ok(SemanticScreen(appPkg, ordered, summarize(appPkg, ordered), err, ocrOk, ocrError, bmp))
  }

  private fun preprocess(src: Bitmap): Bitmap {
    // Cap size to bound memory/latency; ML Kit works best below ~2000px on long edge.
    val longEdge = maxOf(src.width, src.height)
    if (longEdge <= 2000) return src
    val s = 2000f / longEdge
    return Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
  }

  private fun classify(n: UiNode): VisualElement? {
    val type = when (n.role) {
      UiNode.Role.BUTTON -> ElementType.BUTTON
      UiNode.Role.INPUT -> ElementType.INPUT
      UiNode.Role.IMAGE -> if (n.clickable) ElementType.ICON else ElementType.IMAGE
      UiNode.Role.LIST -> ElementType.LIST
      UiNode.Role.CHECKBOX -> ElementType.CHECKBOX
      UiNode.Role.SWITCH -> ElementType.SWITCH
      UiNode.Role.SLIDER -> ElementType.SLIDER
      UiNode.Role.TAB -> ElementType.TAB
      UiNode.Role.MENU -> ElementType.MENU
      UiNode.Role.DIALOG -> ElementType.DIALOG
      UiNode.Role.PROGRESS -> ElementType.LOADING
      UiNode.Role.SCROLL_AREA -> ElementType.SCROLL_AREA
      UiNode.Role.TEXT -> when {
        n.clickable -> ElementType.LINK
        n.label.contains("error", true) || n.label.contains("failed", true) || n.label.contains("couldn't", true) -> ElementType.ERROR
        else -> ElementType.TEXT
      }
      UiNode.Role.CONTAINER -> if (n.clickable && n.label.isNotBlank()) ElementType.CARD else return null
      else -> if (n.clickable || n.label.isNotBlank()) ElementType.CARD else return null
    }
    val vid = n.viewId.lowercase()
    val t = if (type == ElementType.CARD && (vid.contains("video") || n.contentDescription.contains("video", true))) ElementType.VIDEO else type
    return VisualElement("a${n.id}", t, n.label, n.bounds, VisualElement.Source.ACCESSIBILITY, n.clickable || n.editable || n.checkable, n.enabled, if (n.checkable) n.checked else null, n.path)
  }

  private fun summarize(app: String, els: List<VisualElement>): String {
    val counts = els.groupingBy { it.type }.eachCount().entries.joinToString { "${it.value} ${it.key.name.lowercase()}" }
    val top = els.filter { it.text.isNotBlank() }.take(8).joinToString(" | ") { it.text.take(30) }
    return "App=$app; $counts. Top text: $top"
  }

  /** Cheap, deterministic resolution for ordinals/positions; ambiguous ones go to the AI with the element list. */
  fun resolveSimpleReference(ref: String, screen: SemanticScreen, interactiveOnly: Boolean = true): VisualElement? {
    val r = ref.lowercase()
    val pool = screen.elements.filter { !interactiveOnly || it.clickable }.let { l ->
      when {
        listOf("button", "बटन").any { r.contains(it) } -> l.filter { it.type == ElementType.BUTTON }
        listOf("photo", "image", "pic", "तस्वीर", "फोटो").any { r.contains(it) } -> l.filter { it.type == ElementType.IMAGE || it.type == ElementType.CARD }
        listOf("result", "video", "नतीजा", "रिजल्ट").any { r.contains(it) } -> l.filter { it.type == ElementType.CARD || it.type == ElementType.VIDEO || it.type == ElementType.LINK }
        else -> l
      }
    }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    val ordinals = mapOf("pehla" to 1, "pehle" to 1, "first" to 1, "पहला" to 1, "पहले" to 1, "doosra" to 2, "dusra" to 2, "second" to 2, "दूसरा" to 2, "दूसरे" to 2,
      "teesra" to 3, "third" to 3, "तीसरा" to 3, "chautha" to 4, "fourth" to 4, "चौथा" to 4)
    val n = ordinals.entries.firstOrNull { r.contains(it.key) }?.value
    return when {
      r.contains("last") || r.contains("aakhri") || r.contains("आखिरी") -> pool.lastOrNull()
      n != null -> pool.getOrNull(n - 1)
      r.contains("upar") || r.contains("ऊपर") || r.contains("top") -> pool.firstOrNull()
      r.contains("neeche") || r.contains("नीचे") || r.contains("bottom") -> pool.lastOrNull()
      else -> null
    }
  }
}
