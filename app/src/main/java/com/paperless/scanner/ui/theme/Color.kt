package com.paperless.scanner.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.paperless.scanner.R

// ============================================
// Theme Mode Enum
// ============================================
enum class ThemeMode(val key: String, @StringRes val displayNameRes: Int) {
    SYSTEM("system", R.string.theme_mode_system),
    LIGHT("light", R.string.theme_mode_light),
    DARK("dark", R.string.theme_mode_dark)
}

// ============================================
// Dark Tech Precision Color Palette
// ============================================

// Primary Brand Color - Neon Yellow/Green
val DarkTechPrimary = Color(0xFFE1FF8D)
val DarkTechOnPrimary = Color(0xFF000000)

// Background Colors - Deep Blacks
val DarkTechBackground = Color(0xFF0A0A0A)
val DarkTechSurface = Color(0xFF141414)
val DarkTechSurfaceVariant = Color(0xFF1F1F1F)

// Text Colors
val DarkTechOnBackground = Color(0xFFFFFFFF)
val DarkTechOnSurface = Color(0xFFFFFFFF)
val DarkTechOnSurfaceMuted = Color(0xFFA1A1AA)

// Outline/Border Colors - Improved contrast for visibility
val DarkTechOutline = Color(0xFF3F3F46)        // Lightened from #27272A for better visibility
val DarkTechOutlineVariant = Color(0xFF52525B) // Slightly lighter for variant borders

// Accent Color
val DarkTechAccentBlue = Color(0xFF2E3A59)

// Status Colors
val DarkTechSuccess = Color(0xFF10B981)
val DarkTechWarning = Color(0xFFF59E0B)
val DarkTechError = Color(0xFFEF4444)
val DarkTechInfo = Color(0xFF3B82F6)

// ============================================
// Material 3 Dark Theme Color Scheme
// ============================================

val md_theme_dark_primary = DarkTechPrimary
val md_theme_dark_onPrimary = DarkTechOnPrimary
val md_theme_dark_primaryContainer = Color(0xFF2A3310)  // Dark variant of primary
val md_theme_dark_onPrimaryContainer = DarkTechPrimary

val md_theme_dark_secondary = DarkTechPrimary
val md_theme_dark_onSecondary = DarkTechOnPrimary
val md_theme_dark_secondaryContainer = DarkTechSurfaceVariant
val md_theme_dark_onSecondaryContainer = DarkTechOnSurface

val md_theme_dark_tertiary = DarkTechAccentBlue
val md_theme_dark_onTertiary = DarkTechOnSurface
val md_theme_dark_tertiaryContainer = Color(0xFF1E293F)
val md_theme_dark_onTertiaryContainer = DarkTechAccentBlue

val md_theme_dark_error = DarkTechError
val md_theme_dark_onError = Color.White
val md_theme_dark_errorContainer = Color(0xFF4C1D1D)
val md_theme_dark_onErrorContainer = DarkTechError

val md_theme_dark_background = DarkTechBackground
val md_theme_dark_onBackground = DarkTechOnBackground

val md_theme_dark_surface = DarkTechSurface
val md_theme_dark_onSurface = DarkTechOnSurface
val md_theme_dark_surfaceVariant = DarkTechSurfaceVariant
val md_theme_dark_onSurfaceVariant = DarkTechOnSurfaceMuted

val md_theme_dark_outline = DarkTechOutline
val md_theme_dark_outlineVariant = DarkTechOutlineVariant

val md_theme_dark_inverseSurface = DarkTechOnSurface
val md_theme_dark_inverseOnSurface = DarkTechSurface
val md_theme_dark_inversePrimary = DarkTechOnPrimary

val md_theme_dark_surfaceTint = DarkTechPrimary
val md_theme_dark_scrim = Color(0xFF000000)

// ============================================
// Light Tech Precision Color Palette
// Clean White + Lime Accent - Material 3 conventions
// ============================================

// Primary Brand Color - Dark olive for primary buttons/text
val LightTechPrimary = Color(0xFF4B6B00)           // Dark olive - primary accent
val LightTechOnPrimary = Color(0xFFFFFFFF)

// Background Colors - Clean whites and light grays
val LightTechBackground = Color(0xFFFDFCF5)        // Warm off-white
val LightTechSurface = Color(0xFFFFFFFF)            // Pure white
val LightTechSurfaceVariant = Color(0xFFE2E3D1)    // Light warm gray

// Text Colors - Dark for readability
val LightTechOnBackground = Color(0xFF1A1C16)
val LightTechOnSurface = Color(0xFF1A1C16)
val LightTechOnSurfaceMuted = Color(0xFF45483B)

// Outline/Border Colors - Medium gray for subtle borders
val LightTechOutline = Color(0xFF767768)
val LightTechOutlineVariant = Color(0xFFC6C7B5)

// Accent Color
val LightTechAccentBlue = Color(0xFF2E3A59)

// ============================================
// Material 3 Light Theme Color Scheme
// Clean White + Lime Accent
// ============================================

val md_theme_light_primary = Color(0xFF4B6B00)          // Dark olive green for primary buttons/text
val md_theme_light_onPrimary = Color(0xFFFFFFFF)          // White text on primary buttons
val md_theme_light_primaryContainer = Color(0xFFE1FF8D)   // LIME as container - subtle accent
val md_theme_light_onPrimaryContainer = Color(0xFF152000) // Very dark green text on lime containers

val md_theme_light_secondary = Color(0xFF576423)          // Muted olive for secondary
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFDAEAA0) // Soft lime for secondary containers
val md_theme_light_onSecondaryContainer = Color(0xFF171E00)

val md_theme_light_tertiary = Color(0xFF2E3A59)           // Keep existing blue accent
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFDCE1F0)
val md_theme_light_onTertiaryContainer = Color(0xFF171B2E)

val md_theme_light_error = Color(0xFFBA1A1A)              // Standard Material error
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_errorContainer = Color(0xFFFFDAD6)
val md_theme_light_onErrorContainer = Color(0xFF410002)

val md_theme_light_background = Color(0xFFFDFCF5)         // Warm off-white background
val md_theme_light_onBackground = Color(0xFF1A1C16)       // Near-black text

val md_theme_light_surface = Color(0xFFFFFFFF)            // Pure white surfaces
val md_theme_light_onSurface = Color(0xFF1A1C16)          // Near-black text
val md_theme_light_surfaceVariant = Color(0xFFE2E3D1)     // Light warm gray for cards/dividers
val md_theme_light_onSurfaceVariant = Color(0xFF45483B)   // Medium gray for secondary text

val md_theme_light_outline = Color(0xFF767768)            // Medium gray borders
val md_theme_light_outlineVariant = Color(0xFFC6C7B5)     // Light gray borders

val md_theme_light_inverseSurface = Color(0xFF2F312A)     // Dark for inverse elements
val md_theme_light_inverseOnSurface = Color(0xFFF1F1E5)   // Light text on inverse
val md_theme_light_inversePrimary = Color(0xFFC5E86C)     // Brighter lime for inverse context

val md_theme_light_surfaceTint = Color(0xFF4B6B00)        // Match primary
val md_theme_light_scrim = Color(0xFF000000)

