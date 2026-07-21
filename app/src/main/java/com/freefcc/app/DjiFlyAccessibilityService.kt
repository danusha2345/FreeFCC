package com.freefcc.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Locale

internal object DjiFlyHomePointMatcher {
    private val whitespace = Regex("\\s+")

    fun normalize(value: CharSequence): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(whitespace, " ")
            .trim()
            .trimEnd('.', '!', '?', '\u3002', '\uff01', '\uff1f')

    fun matches(value: CharSequence, phrases: Set<String>): Boolean =
        normalize(value) in phrases
}

private data class DjiFlyPhraseCatalog(
    val phrases: Set<String>,
    val localeCount: Int
)

/**
 * Reads text emitted by the original DJI Fly app. The accessibility service
 * never opens DUML itself; an armed Home Point match signals Auto FCC once.
 */
class DjiFlyAccessibilityService : AccessibilityService() {

    companion object {
        private const val DJI_FLY_PACKAGE = "dji.go.v5"
        private val HOME_POINT_RESOURCE_NAMES = listOf(
            "fpv_tips_smart_rth_homepoint_update",
            "fpv_setting_shortcut_update_return_point_succeed_toast",
            "fpv_setting_safe_return_point_update_window_current_beacon_location_note",
            "fpv_setting_safe_return_point_update_window_current_control_location_note",
            "fpv_setting_safe_return_point_update_window_current_drone_location_note",
            "fpv_tips_target_location_lost_rth"
        )
    }

    private var homePointPhrases: Set<String> = emptySet()
    private var lastLoggedSignature = ""
    private var lastLoggedAtMs = 0L
    private var lastUiSnapshot = ""
    private var lastUiScanAtMs = 0L
    private var lastUiHomePointMatch = ""
    private var lastUiHomePointMatchAtMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        val catalog = loadPhraseCatalog()
        homePointPhrases = catalog.phrases
        FccViewModel.logServiceEvent(
            "DJI FLY ACCESSIBILITY TEST: connected; " +
                "phrases=${catalog.phrases.size} locales=${catalog.localeCount}; " +
                "no DUML reads while waiting"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != DJI_FLY_PACKAGE) return

        logVisibleUiSnapshot()

        val values = buildSet {
            event.text.filterNotNull().forEach { if (it.isNotBlank()) add(it.toString()) }
            event.contentDescription?.takeIf { it.isNotBlank() }?.let { add(it.toString()) }
        }

        values.forEach { value ->
            val normalized = DjiFlyHomePointMatcher.normalize(value)
            if (normalized.isEmpty()) return@forEach
            val matched = DjiFlyHomePointMatcher.matches(value, homePointPhrases)
            val signature = "${event.eventType}:$normalized:$matched"
            val now = System.currentTimeMillis()
            if (signature == lastLoggedSignature && now - lastLoggedAtMs < 1_000L) return@forEach
            lastLoggedSignature = signature
            lastLoggedAtMs = now

            val safeText = value.replace(Regex("\\s+"), " ").take(240)
            FccViewModel.logServiceEvent(
                "DJI FLY ACCESSIBILITY EVENT: " +
                    "type=${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                    "home_point_match=$matched text=$safeText"
            )
            if (matched) {
                handleHomePointMatch("event", value)
            }
        }
    }

    private fun logVisibleUiSnapshot() {
        val now = System.currentTimeMillis()
        if (now - lastUiScanAtMs < 1_000L) return
        lastUiScanAtMs = now

        val root = rootInActiveWindow ?: return
        val labels = collectVisibleLabels(root)
        if (labels.isEmpty()) return
        val homePointText = labels.firstOrNull { value ->
            DjiFlyHomePointMatcher.matches(value, homePointPhrases)
        }
        val snapshot = labels.joinToString(" | ").take(1_500)
        if (snapshot == lastUiSnapshot) return
        lastUiSnapshot = snapshot
        FccViewModel.logServiceEvent(
            "DJI FLY ACCESSIBILITY UI: home_point_match=${homePointText != null} text=$snapshot"
        )
        if (homePointText != null) {
            val normalized = DjiFlyHomePointMatcher.normalize(homePointText)
            if (normalized != lastUiHomePointMatch || now - lastUiHomePointMatchAtMs >= 10_000L) {
                lastUiHomePointMatch = normalized
                lastUiHomePointMatchAtMs = now
                handleHomePointMatch("visible_ui", homePointText)
            }
        }
    }

    private fun handleHomePointMatch(source: String, value: CharSequence) {
        val accepted = FccKeepaliveService.notifyHomePointDetected()
        FccViewModel.logServiceEvent(
            "DJI FLY ACCESSIBILITY TEST: HOME POINT MATCH source=$source " +
                "auto_fcc_trigger_accepted=$accepted " +
                "text=${value.toString().replace(Regex("\\s+"), " ").take(240)}"
        )
    }

    private fun collectVisibleLabels(root: AccessibilityNodeInfo): Set<String> {
        val labels = linkedSetOf<String>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited < 300 && labels.size < 80) {
            val node = pending.removeFirst()
            visited += 1
            node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(labels::add)
            node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(labels::add)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(pending::addLast)
            }
        }
        return labels
    }

    override fun onInterrupt() {
        FccViewModel.logServiceEvent("DJI FLY ACCESSIBILITY TEST: interrupted")
    }

    @SuppressLint("AppBundleLocaleChanges", "DiscouragedApi")
    private fun loadPhraseCatalog(): DjiFlyPhraseCatalog {
        val packageContext = try {
            createPackageContext(DJI_FLY_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        } catch (e: Exception) {
            FccViewModel.logServiceEvent(
                "DJI FLY ACCESSIBILITY TEST: DJI Fly resources unavailable: ${e.message}"
            )
            return DjiFlyPhraseCatalog(emptySet(), 0)
        }

        val baseResources = packageContext.resources
        val localeTags = buildSet {
            baseResources.assets.locales
                .mapNotNull { it.takeIf(String::isNotBlank) }
                .forEach(::add)
            baseResources.configuration.locales.let { locales ->
                for (index in 0 until locales.size()) add(locales[index].toLanguageTag())
            }
            add(Locale.ENGLISH.toLanguageTag())
        }

        val phrases = buildSet {
            localeTags.forEach { languageTag ->
                val locale = Locale.forLanguageTag(languageTag)
                val configuration = Configuration(baseResources.configuration).apply {
                    setLocale(locale)
                }
                val localizedResources = packageContext
                    .createConfigurationContext(configuration)
                    .resources
                HOME_POINT_RESOURCE_NAMES.forEach { name ->
                    val id = localizedResources.getIdentifier(name, "string", DJI_FLY_PACKAGE)
                    if (id != 0) {
                        runCatching { localizedResources.getText(id) }
                            .getOrNull()
                            ?.let(DjiFlyHomePointMatcher::normalize)
                            ?.takeIf(String::isNotEmpty)
                            ?.let(::add)
                    }
                }
            }
        }
        return DjiFlyPhraseCatalog(phrases, localeTags.size)
    }
}
