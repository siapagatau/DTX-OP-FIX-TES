package com.kendang.realpads.skin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kendang.realpads.BgColor
import com.kendang.realpads.ThemeState
import kotlinx.coroutines.delay

/**
 * Layar tunggu selama skin dimuat/digambar. Warna = BgColor app (sama dengan warna jendela),
 * tanpa menu atau kontrol apa pun. Spinner baru muncul setelah jeda singkat, jadi kalau skin
 * siap dengan cepat tidak ada kedipan sama sekali.
 */
@Composable
fun SkinLoadingScreen(modifier: Modifier = Modifier) {
    var showSpinner by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(350); showSpinner = true }
    Box(
        modifier = modifier.fillMaxSize().background(BgColor),
        contentAlignment = Alignment.Center
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                color = ThemeState.accentColor,
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
