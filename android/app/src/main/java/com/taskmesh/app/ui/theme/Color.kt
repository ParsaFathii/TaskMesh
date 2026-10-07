package com.taskmesh.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.taskmesh.app.model.Tone

// TaskMesh identity palette (dark graphite / amber / emerald / rose).
val TaskMeshBackground = Color(0xFF101418)
val TaskMeshPanel = Color(0xFF161B22)
val TaskMeshPanelRaised = Color(0xFF1C242E)
val TaskMeshAmber = Color(0xFFF5A623)
val TaskMeshEmerald = Color(0xFF2EA043)
val TaskMeshRose = Color(0xFFF85149)
val TaskMeshGray = Color(0xFF8B949E)
val TaskMeshSlate = Color(0xFF6E7681)
val TaskMeshTextPrimary = Color(0xFFE6EDF3)
val TaskMeshTextSecondary = Color(0xFF9DA7B3)
val TaskMeshOutline = Color(0xFF30363D)

/** Semantic tone -> concrete color (kept in the UI layer so models stay pure). */
val Tone.color: Color
    get() = when (this) {
        Tone.AMBER -> TaskMeshAmber
        Tone.EMERALD -> TaskMeshEmerald
        Tone.ROSE -> TaskMeshRose
        Tone.GRAY -> TaskMeshGray
        Tone.SLATE -> TaskMeshSlate
    }
