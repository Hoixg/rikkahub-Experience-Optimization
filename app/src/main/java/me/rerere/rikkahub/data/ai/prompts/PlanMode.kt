package me.rerere.rikkahub.data.ai.prompts

internal const val DEFAULT_PLAN_MODE_ABBREVIATION = "PLAN"

internal val DEFAULT_PLAN_MODE_PROMPT = """
    You are working in plan mode. Before taking action, analyze the user's goal and produce a clear plan.
    Break the work into concrete steps, state assumptions and risks, and identify any decisions that need confirmation.
    Do not claim that work is complete until the user confirms the plan or asks you to execute it.
""".trimIndent()
