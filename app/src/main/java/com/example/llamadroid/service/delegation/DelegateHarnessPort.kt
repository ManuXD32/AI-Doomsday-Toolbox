package com.example.llamadroid.service.delegation

/**
 * The subset of [DelegateHarnessDriver] calls [DelegateProjectBinder] needs, so the binder can be
 * tested with a fake. Failures are [DelegationHarnessException]s with the driver's codes.
 */
interface DelegateHarnessPort {
    suspend fun createProject(title: String): DelegateHarnessProject
    suspend fun openProject(workspaceId: String): DelegateHarnessProject
    suspend fun createThread(project: DelegateHarnessProject, title: String, suppressApprovals: Boolean): DelegateHarnessThread

    /** Registers and watches an existing thread; throws `THREAD_NOT_FOUND` when unknown, archived or in another project. */
    suspend fun adoptThread(project: DelegateHarnessProject, sessionId: String, suppressApprovals: Boolean): DelegateHarnessThread

    /**
     * Whether the harness itself still has [sessionId] (the Room row alone can outlive a wiped harness
     * data folder). Only a definite "not found" answer returns false; anything else returns true.
     */
    suspend fun sessionKnown(sessionId: String): Boolean
    suspend fun selectModel(sessionId: String, binding: HarnessModelBinding)
    suspend fun applyApprovalPolicy(sessionId: String, policy: DelegationApprovalPolicy): DelegateApprovalOutcome
}
