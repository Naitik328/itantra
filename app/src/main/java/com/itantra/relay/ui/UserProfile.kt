package com.itantra.relay.ui

import android.content.Context

enum class Gender(val label: String) { MALE("Male"), FEMALE("Female"), OTHER("Other") }

data class UserProfile(
    val name: String,
    val gender: Gender,
    val avatarColorIndex: Int,
    val avatarPhotoPath: String?,
)

/** Persists the user's setup answers in SharedPreferences (fully offline). */
object ProfileStore {
    private const val PREFS = "itantra_profile"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOnboarded(c: Context) = prefs(c).getBoolean("onboarded", false)

    fun load(c: Context): UserProfile? {
        val s = prefs(c)
        if (!s.getBoolean("onboarded", false)) return null
        val gender = runCatching { Gender.valueOf(s.getString("gender", "OTHER")!!) }.getOrDefault(Gender.OTHER)
        return UserProfile(
            name = s.getString("name", "") ?: "",
            gender = gender,
            avatarColorIndex = s.getInt("colorIndex", 0),
            avatarPhotoPath = s.getString("photoPath", null),
        )
    }

    fun save(c: Context, p: UserProfile) {
        prefs(c).edit()
            .putBoolean("onboarded", true)
            .putString("name", p.name)
            .putString("gender", p.gender.name)
            .putInt("colorIndex", p.avatarColorIndex)
            .putString("photoPath", p.avatarPhotoPath)
            .apply()
    }
}
