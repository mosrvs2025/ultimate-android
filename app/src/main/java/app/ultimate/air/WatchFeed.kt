package app.ultimate.air

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The watch's live motion for any tool on the phone (Gesture Studio reads it), filled by the watch link in Ultimate+.
 * [request] asks the watch to start or stop streaming; it does nothing in editions without a watch link.
 */
object WatchFeed {
    val samples = MutableStateFlow<List<WristCalibration.Sample>>(emptyList())
    @Volatile var request: (Boolean) -> Unit = {}
}
