package com.axiom.kai

import java.util.UUID

enum class Role { USER, KAI, KAI_RECURSIVE }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val vfe: Float? = null,
    val curvature: Float? = null,
    val temp: Float? = null,
    val model: String = "qwen2.5:0.5b",
    val ts: Long = System.currentTimeMillis()
)
