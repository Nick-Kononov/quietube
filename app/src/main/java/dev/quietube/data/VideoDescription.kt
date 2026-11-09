package dev.quietube.data

import org.schabi.newpipe.extractor.stream.Description

internal fun videoDescription(description: Description?): String? = description?.let {
    val text = if (it.type == Description.Type.HTML) org.jsoup.Jsoup.parse(it.content).wholeText() else it.content
    text?.trim()?.takeIf(String::isNotBlank)
}
