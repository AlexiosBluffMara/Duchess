package com.duchess.glasses.model

/**
 * Controls which language(s) appear in HudRenderer's status bar and alert text.
 *
 * Alex: Construction sites in the US employ crews where Spanish is the dominant
 * language. This enum lets a foreman (via the phone companion app) configure the
 * HUD to match the crew. The selected mode is sent over BLE when the glasses
 * connect or when the foreman changes the setting.
 *
 * Default: EN_PRIMARY — English text on top, Spanish subtitle below.
 */
enum class HudLanguageMode {
    /** English primary, Spanish subtitle. Default for US sites. */
    EN_PRIMARY,

    /** Spanish primary, English subtitle. For predominantly Spanish-speaking crews. */
    ES_PRIMARY,

    /** English only — no secondary line. Maximises vertical space on the 640x360 display. */
    EN_ONLY,
}
