package com.e2eechat.app.ui.theme

import androidx.compose.ui.graphics.Color

// =============================================================================
// FIXED CORE THEME COLORS
// =============================================================================
val TeleXPrimary = Color(0xFF789477)        // Light forest green (Fixed)
val TeleXBackground = Color(0xFFF8F5ED)     // Soft ivory (Fixed)

// =============================================================================
// DERIVED PRIMARY TONAL PALETTE (Derived from #789477)
// =============================================================================
val TeleXPrimaryDark = Color(0xFF5E785D)     // Pressed state / deep accent
val TeleXPrimaryDeep = Color(0xFF334232)     // Ultra-deep forest for high-contrast icons & text
val TeleXPrimaryMedium = Color(0xFF6B866A)   // Medium forest shade
val TeleXPrimaryContainer = Color(0xFFE2EBE1)// Soft primary container / chips / badges
val TeleXPrimaryLight = Color(0xFFA1B7A0)    // Muted / light accent
val TeleXPrimaryMuted = Color(0x33789477)    // Translucent 20% primary fill
val TeleXPrimaryBorder = Color(0xFFC7D7C6)   // Subtle primary outline

// =============================================================================
// DERIVED BACKGROUND & SURFACE PALETTE (Derived from #F8F5ED)
// =============================================================================
val TeleXSurface = Color(0xFFF8F5ED)         // Base soft ivory surface
val TeleXSurfaceRaised = Color(0xFFFAF7F0)   // Raised clay surface
val TeleXSurfaceElevated = Color(0xFFF2ECE0) // Subtle card / secondary surface
val TeleXSurfaceInset = Color(0xFFEDE7DA)    // Sunken clay inset input field
val TeleXOutline = Color(0xFFDED8CA)         // Clean subtle border
val TeleXOutlineVariant = Color(0xFFD0C9BA)  // Darker outline for depth
val TeleXDivider = Color(0xFFE8E2D5)         // Hairline list divider

// =============================================================================
// SOFTENED CLAYMORPHISM 3D SHADOW & HIGHLIGHT TOKENS
// =============================================================================
val TeleXClayHighlight = Color(0x4DFFFFFF)   // Soft subtle top-left light reflection (30% white)
val TeleXClayShadowDark = Color(0x145C5547)  // Soft gentle ambient shadow (8% warm tint)
val TeleXClayButtonShadow = Color(0x243B4D3A)// Soft shadow under primary raised button (14% forest tint)

// =============================================================================
// ACCESSIBLE TEXT & CONTENT PALETTE
// =============================================================================
val TeleXTextPrimary = Color(0xFF222B22)     // High-contrast near-black forest (>11:1 on ivory)
val TeleXTextSecondary = Color(0xFF5A665A)   // Mid-tone forest slate (>5.5:1 on ivory)
val TeleXTextMuted = Color(0xFF889488)       // Tertiary placeholder / timestamps
val TeleXTextOnPrimary = Color(0xFFFFFFFF)   // Pure white on primary #789477
val TeleXTextOnPrimarySubtle = Color(0xFFE8F0E7) // Secondary white on primary

// =============================================================================
// MESSAGE BUBBLES
// =============================================================================
val BubbleSentBg = Color(0xFF789477)         // Sent bubble (Primary)
val BubbleSentText = Color(0xFFFFFFFF)       // Sent text
val BubbleSentTime = Color(0xCCFFFFFF)       // Sent time (80% white)
val BubbleSentTickSent = Color(0xB3FFFFFF)   // Sent single tick (70% white)
val BubbleSentTickRead = Color(0xFFE8FCE7)   // Read double tick (bright light mint/ivory)

val BubbleReceivedBg = Color(0xFFEDE7DA)     // Received bubble (Ivory surface variant)
val BubbleReceivedText = Color(0xFF222B22)   // Received text
val BubbleReceivedTime = Color(0xFF6E786E)   // Received time
val BubbleReceivedBorder = Color(0xFFDDD7C8) // Received border

// =============================================================================
// FUNCTIONAL & STATUS COLORS
// =============================================================================
val TeleXOnlineGreen = Color(0xFF4A8754)     // Online status indicator (forest-aligned)
val TeleXError = Color(0xFFBA1A1A)           // Accessible error
val TeleXOnError = Color(0xFFFFFFFF)
val TeleXErrorContainer = Color(0xFFFFDAD6)
val TeleXOnErrorContainer = Color(0xFF410002)

// =============================================================================
// BACKWARD-COMPATIBLE ALIASES
// =============================================================================
val AccentBlueViolet = TeleXPrimary
val AccentLightViolet = TeleXPrimaryLight
val AccentDarkViolet = TeleXPrimaryDark
val CaramelAccent = TeleXPrimary
val CaramelAccentLight = TeleXPrimaryContainer
val CaramelAccentDark = TeleXPrimaryDark
val OnlineGreen = TeleXOnlineGreen
val SecurityLockBadge = TeleXPrimary
val FabGrey = TeleXPrimary
val TextPrimary = TeleXTextPrimary
val TextSecondary = TeleXTextSecondary
val TextMuted = TeleXTextMuted
val TextOnAccent = TeleXTextOnPrimary
val BubbleSentCaramel = BubbleSentBg
val BubbleSentCaramelText = BubbleSentText
val BubbleReceivedGrey = BubbleReceivedBg
val BubbleReceivedGreyText = BubbleReceivedText
val DarkSurfaceSubtle = TeleXOutline
