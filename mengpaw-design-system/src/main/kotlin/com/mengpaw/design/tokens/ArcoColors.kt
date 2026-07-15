package com.mengpaw.design.tokens

import androidx.compose.ui.graphics.Color

/**
 * Arco Design color palette adapted for Jetpack Compose.
 * Reference: https://arco.design/docs/spec/color
 */
object ArcoColors {
    // Primary - Brand blue
    val Blue1 = Color(0xFFE8F3FF)
    val Blue2 = Color(0xFFBEDAFF)
    val Blue3 = Color(0xFF94BFFF)
    val Blue4 = Color(0xFF6AA1FF)
    val Blue5 = Color(0xFF4080FF)
    val Blue6 = Color(0xFF165DFF)  // Primary
    val Blue7 = Color(0xFF0E42D2)
    val Blue8 = Color(0xFF072CA6)
    val Blue9 = Color(0xFF031A79)
    val Blue10 = Color(0xFF000D4D)

    // Success - Green
    val Green1 = Color(0xFFE8FFEA)
    val Green2 = Color(0xFFAFF0B5)
    val Green3 = Color(0xFF7BE188)
    val Green4 = Color(0xFF4CD263)
    val Green5 = Color(0xFF23C343)
    val Green6 = Color(0xFF00B42A)  // Success
    val Green7 = Color(0xFF008A1F)
    val Green8 = Color(0xFF006016)
    val Green9 = Color(0xFF00360C)
    val Green10 = Color(0xFF001405)

    // Warning - Orange
    val Orange1 = Color(0xFFFFF7E8)
    val Orange2 = Color(0xFFFFE4BA)
    val Orange3 = Color(0xFFFFCF8B)
    val Orange4 = Color(0xFFFFB65D)
    val Orange5 = Color(0xFFFF9A2E)
    val Orange6 = Color(0xFFFF7D00)  // Warning
    val Orange7 = Color(0xFFD25F00)
    val Orange8 = Color(0xFFA64500)
    val Orange9 = Color(0xFF792E00)
    val Orange10 = Color(0xFF4D1B00)

    // Danger - Red
    val Red1 = Color(0xFFFFECE8)
    val Red2 = Color(0xFFFDDCC5)
    val Red3 = Color(0xFFFBC4A3)
    val Red4 = Color(0xFFF9A981)
    val Red5 = Color(0xFFF78A5E)
    val Red6 = Color(0xFFF53F3F)  // Danger
    val Red7 = Color(0xFFCB272D)
    val Red8 = Color(0xFFA1151E)
    val Red9 = Color(0xFF780813)
    val Red10 = Color(0xFF4D000A)

    // Neutral / Gray
    val Gray1 = Color(0xFFF7F8FA)
    val Gray2 = Color(0xFFF2F3F5)
    val Gray3 = Color(0xFFE5E6EB)
    val Gray4 = Color(0xFFC9CDD4)
    val Gray5 = Color(0xFFA9AEB8)
    val Gray6 = Color(0xFF86909C)
    val Gray7 = Color(0xFF6B7785)
    val Gray8 = Color(0xFF4E5969)
    val Gray9 = Color(0xFF272E3B)
    val Gray10 = Color(0xFF1D2129)

    // Text colors
    val TextPrimary = Gray10
    val TextSecondary = Gray7
    val TextDisabled = Gray5
    val TextInverse = Color(0xFFFFFFFF)

    // Background
    val BgPrimary = Color(0xFFFFFFFF)
    val BgSecondary = Gray1
    val BgTertiary = Gray2

    // Border
    val BorderDefault = Gray3
    val BorderHover = Gray4
}
