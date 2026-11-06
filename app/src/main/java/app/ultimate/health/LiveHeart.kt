package app.ultimate.health

import kotlinx.coroutines.flow.MutableStateFlow

/** Heart rate as the watch measures it right now (bpm and when), shared by the watch link and the Health screen. */
object LiveHeart {
    val bpm = MutableStateFlow<Pair<Int, Long>?>(null)
}
