package com.lensqi.fieldops.ui.task

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The check photos whose verdict has been read out, shared by every screen that holds a task: the plan and the step
 * screen each have their own view model and both follow the queue, so without one owner a check that synced while both
 * were alive would be announced twice.
 */
@Singleton
class SpokenChecks @Inject constructor() {
    private val ids = HashSet<String>()
    /** Checks already in the record when a screen opened are known, not news. */
    @Synchronized fun seed(photoIds: Collection<String>) { ids += photoIds }
    /** True once per photo: the first screen to see the verdict reads it. */
    @Synchronized fun firstTime(photoId: String): Boolean = ids.add(photoId)
}
