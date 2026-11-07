package dev.quietube.data

import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.stream.Description

class CommentMapperTest {
    private fun item() = CommentsInfoItem(0,"https://www.youtube.com/watch?v=aqz-KE-bpKQ","comment").apply {
        commentId = "fixture-comment"; uploaderName = "Fixture author"
        commentText = Description("Hello\nWorld",Description.PLAIN_TEXT)
        likeCount = 0; textualUploadDate = "1 day ago"
    }
    @Test fun plainTextZeroLikesAndBadgesArePreserved() {
        val item = item().apply { setPinned(true); setHeartedByUploader(true) }
        val mapped = CommentMapper.map(item)
        assertEquals("fixture-comment",mapped.id); assertEquals("Hello\nWorld",mapped.text)
        assertEquals(0,mapped.likes); assertTrue(mapped.pinned); assertTrue(mapped.creatorHeart)
        assertEquals("Fixture author",mapped.author); assertNull(mapped.avatarUrl)
    }
    @Test fun htmlIsReadableTextAndEntitiesAreDecoded() {
        val item = item().apply { commentText = Description("Hello<br>World &amp; <b>friends</b>",Description.HTML); likeCount = -1 }
        val mapped = CommentMapper.map(item)
        assertTrue(mapped.text.contains("World & friends")); assertTrue(mapped.text.contains("\n")); assertFalse(mapped.text.contains("<b>"))
        assertNull(mapped.likes)
    }
    @Test fun missingAuthorAndIdHaveStableFallbacks() {
        val item = item().apply { commentId = ""; uploaderName = "" }
        val first = CommentMapper.map(item)
        assertEquals("YouTube viewer",first.author); assertEquals(first.id,CommentMapper.map(item).id)
        item.commentText = Description("Other comment",Description.PLAIN_TEXT)
        assertNotEquals(first.id,CommentMapper.map(item).id)
    }
}
