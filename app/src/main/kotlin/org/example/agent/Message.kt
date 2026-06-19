package org.example.agent

/** Who authored a message in the dialog. */
enum class Role { USER, ASSISTANT }

/** A single turn in the conversation. Pure domain model — no timestamp, no dates. */
data class Message(val role: Role, val content: String)
