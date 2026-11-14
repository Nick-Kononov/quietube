package dev.quietube.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private val Context.thirdParty by preferencesDataStore("third_party")
private val SPONSORBLOCK = booleanPreferencesKey("sponsorblock")
private val DEARROW = booleanPreferencesKey("dearrow")
private fun modeKey(category: SegmentCategory) = stringPreferencesKey("segment_${category.key}")

/** Crowd-sourced services, all off until the viewer turns them on in Settings. */
data class ThirdPartySettings(val sponsorBlock: Boolean = false, val modes: Map<SegmentCategory, SegmentMode> = emptyMap(), val deArrow: Boolean = false) {
    fun mode(category: SegmentCategory): SegmentMode = modes[category] ?: category.default
    /** Categories to skip automatically; empty while SponsorBlock is off. */
    val skipped: Set<SegmentCategory> get() = if (!sponsorBlock) emptySet() else SegmentCategory.entries.filter { mode(it) == SegmentMode.Skip }.toSet()
    /** Categories to mark on the seek bar, skipped or not; empty while SponsorBlock is off. */
    val marked: Set<SegmentCategory> get() = if (!sponsorBlock) emptySet() else SegmentCategory.entries.filter { mode(it) != SegmentMode.Off }.toSet()
}

@Singleton
class ThirdPartyPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    val settings: Flow<ThirdPartySettings> = context.thirdParty.data.catch { emit(emptyPreferences()) }.map { prefs ->
        ThirdPartySettings(prefs[SPONSORBLOCK] ?: false,
            SegmentCategory.entries.mapNotNull { c -> prefs[modeKey(c)]?.let { saved -> SegmentMode.entries.firstOrNull { it.name == saved } }?.let { c to it } }.toMap(),
            prefs[DEARROW] ?: false)
    }.distinctUntilChanged()
    suspend fun sponsorBlock(enabled: Boolean) { context.thirdParty.edit { it[SPONSORBLOCK] = enabled } }
    suspend fun mode(category: SegmentCategory, mode: SegmentMode) { context.thirdParty.edit { it[modeKey(category)] = mode.name } }
    suspend fun deArrow(enabled: Boolean) { context.thirdParty.edit { it[DEARROW] = enabled } }
}

/** DeArrow titles for the videos on screen, fetched only while DeArrow is on. */
@Singleton
class TitleReplacements internal constructor(private val deArrow: DeArrow, enabled: Flow<Boolean>, private val scope: CoroutineScope) {
    @Inject constructor(deArrow: DeArrow, preferences: ThirdPartyPreferences) :
        this(deArrow, preferences.settings.map { it.deArrow }, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    val enabled: StateFlow<Boolean> = enabled.stateIn(scope, SharingStarted.Eagerly, false)
    private val mutable = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Replacement titles by video ID; videos without a trusted replacement are absent. */
    val titles: StateFlow<Map<String, String>> = mutable.asStateFlow()
    private val asked = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun request(videoId: String) {
        if (!enabled.value || !asked.add(videoId)) return
        scope.launch {
            val title = deArrow.title(videoId)
            if (title == null) { asked.remove(videoId); return@launch }
            // Bounded: the oldest titles go first, and are fetched again (from cache) if they come back on screen.
            mutable.update { current ->
                val all = current + (videoId to title)
                if (all.size <= 600) all else all.entries.drop(200).associate { it.key to it.value }.also { kept -> asked.retainAll(kept.keys) }
            }
        }
    }
}
