package com.example.llamadroid.harness

import com.example.llamadroid.data.db.HarnessRuntimeEntity

/** A failed pre-launch runtime has no process owner. Other owned generations must be stopped. */
internal fun canRemoveHarnessProject(
    runtime: HarnessRuntimeEntity?,
    starting: Boolean,
    connected: Boolean,
): Boolean = !starting && !connected && (runtime == null || runtime.state == "STOPPED" ||
    (runtime.state == "FAILED" && runtime.brokerPid == null && runtime.childPid == null && runtime.nodePid == null))
