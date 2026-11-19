package com.example.data.model

enum class RomSystem(
    val shortLabel: String,
    val fullName: String,
    val screenWidth: Int,
    val screenHeight: Int
) {
    GB(
        shortLabel = "GB",
        fullName = "Game Boy",
        screenWidth = 160,
        screenHeight = 144
    ),
    GBC(
        shortLabel = "GBC",
        fullName = "Game Boy Color",
        screenWidth = 160,
        screenHeight = 144
    ),
    GBA(
        shortLabel = "GBA",
        fullName = "Game Boy Advance",
        screenWidth = 240,
        screenHeight = 160
    )
}
