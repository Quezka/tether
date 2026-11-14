package io.github.quezka.tether.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.quezka.tether.R

private val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal), Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold), Font(R.font.manrope_bold, FontWeight.Bold),
)

/** The suite's Material 3 tonal look (tokens from suite-rs/design/tokens.json). */
@Composable
fun TetherTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) SuiteTokens.Dark else SuiteTokens.Light,
        typography = SuiteTokens.typography(Manrope), content = content)
}
