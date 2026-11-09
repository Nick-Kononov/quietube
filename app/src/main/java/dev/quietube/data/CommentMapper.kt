package dev.quietube.data

import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.stream.Description

internal object CommentMapper {
    fun map(item: CommentsInfoItem): Comment {
        val content = item.commentText
        val text = if (content.type == Description.Type.HTML) org.jsoup.Jsoup.parse(content.content).wholeText() else content.content
        val author = item.uploaderName.orEmpty().ifBlank { "YouTube viewer" }
        val id = item.commentId?.takeIf { it.isNotBlank() } ?: java.security.MessageDigest.getInstance("SHA-256")
            .digest("$author\u0000$text".toByteArray()).joinToString("") { "%02x".format(it) }
        return Comment(id,author,item.uploaderAvatars.maxByOrNull { it.height }?.url,text,
            item.textualUploadDate,item.likeCount.takeIf { it >= 0 },item.isPinned,item.isHeartedByUploader)
    }
}
