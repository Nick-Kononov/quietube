The captured-public-* fixtures came from a signed-out https://www.youtube.com/
response fetched on 2026-11-07. Tracking fields were removed and visitor data was
replaced with a fixture value. No account cookies or private responses are saved.
renderers.json and continuation.json are synthetic regression fixtures matching
the legacy and lockup schemas in current yt-dlp source. They intentionally include
malformed, unknown and ad renderers. Replace/add sanitized real account samples
when owner-driven signed-in testing reveals additional shapes.
