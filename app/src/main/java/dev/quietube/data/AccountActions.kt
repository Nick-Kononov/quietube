package dev.quietube.data

enum class Rating { Like, Dislike, None }
data class Engagement(val rating: Rating? = null,val likes: String? = null,val dislikes: String? = null,
    val channelId: String? = null,val subscribed: Boolean? = null,val estimatedDislikes: Boolean = false)
data class Profile(val name: String,val avatarUrl: String?)
interface WatchTicket
interface AccountActions {
    suspend fun engagement(url: String): Outcome<Engagement> = Outcome.Failure(VideoError.AuthExpired)
    suspend fun rate(url: String,rating: Rating,previous: Rating): Outcome<Unit> = Outcome.Failure(VideoError.AuthExpired)
    suspend fun subscribe(channelId: String,subscribe: Boolean): Outcome<Unit> = Outcome.Failure(VideoError.AuthExpired)
    suspend fun startWatch(url: String,position: Double): Outcome<WatchTicket> = Outcome.Failure(VideoError.AuthExpired)
    suspend fun reportWatch(ticket: WatchTicket,segments: List<Pair<Double,Double>>,position: Double,final: Boolean): Outcome<Unit> = Outcome.Failure(VideoError.AuthExpired)
}
