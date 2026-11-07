package dev.quietube.data

data class Comment(val id: String,val author: String,val avatarUrl: String?,val text: String,
    val published: String?,val likes: Int?,val pinned: Boolean = false,val creatorHeart: Boolean = false)
data class CommentPage(val comments: List<Comment>,val continuation: Continuation? = null,val disabled: Boolean = false,
    val total: Int? = null,val skippedItems: Boolean = false)
