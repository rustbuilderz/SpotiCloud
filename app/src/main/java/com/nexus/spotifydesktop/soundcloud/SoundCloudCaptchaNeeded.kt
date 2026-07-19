package com.nexus.spotifydesktop.soundcloud

/** Thrown when SoundCloud/DataDome returns a CAPTCHA challenge URL. */
class SoundCloudCaptchaNeeded(
    val captchaUrl: String,
) : Exception("CAPTCHA required")
