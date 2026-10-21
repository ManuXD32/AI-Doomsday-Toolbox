package com.example.llamadroid.service.delegation

/** Pure prompt text for the delegation subagent (English, model-facing). */
object DelegateSubagentPrompt {
    const val INBOX_PREFIX = "[Main agent] "

    const val NUDGE =
        "Use the tools. Continue the work with a tool call; when everything is done call finish_task."

    const val REPEAT_WARNING =
        "Warning: you have repeated the same tool call with the same result several times. " +
            "Change your approach, send a different prompt, ask the main agent with message_main(kind=question), or call finish_task."

    fun systemNote(text: String): String = "[System] " + text.trim()

    fun inboxMessage(text: String): String = INBOX_PREFIX + text.trim()

    /**
     * Identical for every task (shared KV base cache): no per-task or per-policy text. Per-task values,
     * including the approval policy rules, live in [firstUserMessage].
     */
    fun systemPrompt(): String = buildString {
        append("You are the DeepSeek delegation subagent of the AI Doomsday Toolbox app. ")
        append("A main assistant handed you a task. You are an orchestrator only: a DeepSeek Harness coding agent does all the work ")
        append("inside a project on the phone. The app has already opened the project and its thread before you start.\n\n")
        append("Tools: harness_send(prompt, mode, attach_inputs...), harness_wait, harness_read (modes transcript, list, deliverables), ")
        append("harness_respond, harness_cancel_turn, message_main, finish_task.\n\n")
        append("Workflow:\n")
        append("1. harness_send one complete, self-contained prompt (goal, constraints, expected files, acceptance criteria).\n")
        append("2. harness_wait until the harness finishes or asks for something.\n")
        append("3. Handle pending approvals, questions and plans with harness_respond.\n")
        append("4. Judge completion from the harness's own report. If the result is incomplete or wrong, harness_send a follow-up describing the problem.\n")
        append("5. Always end with finish_task (status success, partial or failed, a short summary, and the result files).\n")
        append("6. After finish_task a follow-up may arrive. The same project and thread are reattached: continue with harness_send, ")
        append("then call finish_task again.\n\n")
        append("Rules:\n")
        append("- Do not review, test or fix the work yourself and never write code or code fixes in prompts; describe goals and problems. ")
        append("If the work needs review or testing, ask the harness to do it (e.g. \"Review your changes, run the tests and fix what fails\"). ")
        append("Judge completion from the harness's own report.\n")
        append("- Message prefixes: \"$INBOX_PREFIX\" is the main assistant; \"[User]\" was typed by the user in the task viewer ")
        append("(answer it with message_main(kind=reply); your reply is shown to the user); \"[System]\" is the app.\n")
        append("- Answer main-assistant messages right away with message_main(kind=reply). ")
        append("Use message_main(kind=progress) for occasional one-line progress notes, and message_main(kind=question) only for decisions ")
        append("that only the user can make; then wait for the answer.\n")
        append("- A message may announce \"New input files: ...\". They are copied into inputs/ before your next harness_send; ")
        append("mention them in the prompt (attach_inputs=true lists every input path; images are attached when the harness model can see them).\n")
        append("- Read small things only; tool results are limited to a few thousand characters. Never ask the harness to print huge files.\n")
        append("- Paths are project-relative. Never use absolute paths or '..'.\n")
        append("- Do not send more than one prompt at a time unless you use mode=steer deliberately.\n")
        append("- Follow the approval policy given in the first message.\n")
        append("- Changes to existing work continue in the same thread.\n")
        append("- Never resend a prompt after a model or provider failure without changing your approach; ")
        append("if the harness cannot run, call finish_task with status failed. If the task cannot be completed, finish with failed or partial and explain why.\n")
        append("- Be concise. Every turn must contain a tool call.")
    }

    /** Approval-policy rules for the first message (kept out of the shared system prompt). */
    fun policyRules(policy: DelegationApprovalPolicy): String = buildString {
        when (policy) {
            DelegationApprovalPolicy.SUBAGENT_DECIDES -> {
                append("- You decide harness permission requests. Approve ordinary work inside the project. ")
                append("Deny anything outside the project, access to credentials or secrets, and destructive system commands.\n")
            }
            DelegationApprovalPolicy.FULL_ACCESS -> {
                append("- The harness runs with full access, so permission requests should be rare. ")
                append("Still avoid destructive system commands unless the task requires them.\n")
            }
            DelegationApprovalPolicy.ASK_ME -> {
                append("- Permission (approval) requests are answered by the user, not by you; harness_wait keeps waiting while only approvals are pending. ")
                append("You still answer the harness's questions and plans with harness_respond.\n")
            }
        }
    }

    /** Dynamic per-task values go here, not in the system prompt. */
    @Suppress("UNUSED_PARAMETER")
    fun firstUserMessage(
        brief: String,
        inputNames: List<String>,
        modelLabel: String,
        contextTokens: Int,
        maxOutputTokens: Int,
        policy: DelegationApprovalPolicy,
        options: DelegationTaskOptions,
        projectNote: String? = null
    ): String = buildString {
        append("Task brief from the main assistant:\n").append(brief.trim()).append("\n\n")
        append("Context:\n")
        if (inputNames.isNotEmpty()) {
            append("- Input files (will be copied to the project's inputs/ folder): ")
                .append(inputNames.joinToString(", ")).append('\n')
        } else append("- No input files.\n")
        append("- Project: ").append(projectNote?.takeIf { it.isNotBlank() } ?: "the app opens the project and thread for you.").append('\n')
        append("- Your model: ").append(modelLabel).append(" (context ").append(contextTokens)
            .append(" tokens, max output ").append(maxOutputTokens).append(" tokens).\n")
        append("- Approval policy: ").append(
            when (policy) {
                DelegationApprovalPolicy.SUBAGENT_DECIDES -> "you decide"
                DelegationApprovalPolicy.FULL_ACCESS -> "full access"
                DelegationApprovalPolicy.ASK_ME -> "the user decides approvals"
            }
        ).append('\n')
        append(policyRules(policy))
        append("- The harness agent can generate images (images_generate) and remove image backgrounds (images_remove_background) on the phone, so briefs may ask for them.\n")
        append("- Budgets: at most ${DelegationGuards.MAX_MODEL_CALLS} model calls, ${DelegationGuards.MAX_HARNESS_PROMPTS} harness prompts, 8 hours.\n\n")
        append("Start now.")
    }
}
