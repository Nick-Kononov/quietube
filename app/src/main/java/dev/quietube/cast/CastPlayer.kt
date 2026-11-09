package dev.quietube.cast

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * The phone's player as its controls see it: while a TV plays a direct stream ([tvPosition] is known), the seek
 * bar shows the TV's position and seeks move the TV, without the paused phone player loading anything. Playing on
 * the phone continues from where the TV was.
 */
@OptIn(UnstableApi::class)
class CastPlayer(player: Player, private val tvPosition: () -> Long?, private val seekTv: (Long) -> Unit) : ForwardingPlayer(player) {
    override fun getCurrentPosition() = tvPosition() ?: super.getCurrentPosition()
    override fun getContentPosition() = tvPosition() ?: super.getContentPosition()
    override fun getBufferedPosition() = tvPosition() ?: super.getBufferedPosition()
    override fun getContentBufferedPosition() = tvPosition() ?: super.getContentBufferedPosition()

    override fun seekTo(positionMs: Long) = seek(positionMs) { super.seekTo(positionMs) }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = seek(positionMs) { super.seekTo(mediaItemIndex, positionMs) }
    override fun seekBack() = tvPosition()?.let { seekTv((it - seekBackIncrement).coerceAtLeast(0)) } ?: super.seekBack()
    override fun seekForward() = tvPosition()?.let { seekTv(it + seekForwardIncrement) } ?: super.seekForward()

    override fun play() {
        tvPosition()?.let { super.seekTo(it) }
        super.play()
    }

    private inline fun seek(positionMs: Long, local: () -> Unit) { if (tvPosition() != null) seekTv(positionMs) else local() }
}
