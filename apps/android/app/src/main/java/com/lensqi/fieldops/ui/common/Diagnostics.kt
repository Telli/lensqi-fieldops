package com.lensqi.fieldops.ui.common

import androidx.compose.runtime.compositionLocalOf
import com.lensqi.fieldops.data.settings.DIAGNOSTICS_DEFAULT

/** Presentation preference only; never feeds domain commands. */
val LocalDiagnostics = compositionLocalOf { DIAGNOSTICS_DEFAULT }
