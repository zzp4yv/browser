package com.tvbrowser.app.model

data class Bookmark(
    val name: String,
    val url: String,
    val removable: Boolean = true
)

object DefaultBookmarks {
    val list = listOf(
        Bookmark("YouTube", "https://www.youtube.com/tv", removable = false),
        Bookmark("Vimeo", "https://vimeo.com", removable = false),
        Bookmark("Twitch", "https://www.twitch.tv", removable = false),
        Bookmark("Globoplay", "https://globoplay.globo.com", removable = false),
        Bookmark("Dailymotion", "https://www.dailymotion.com", removable = false)
    )
}
