package com.pam.recap.testing

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.junit.Assert.assertTrue

/**
 * Accessibility smoke check: every semantics node that can be clicked must expose a human
 * readable label, either through visible text or a content description. This is the automated
 * part of the S4 accessibility check; TalkBack, font scale and switch access remain manual.
 *
 * The merged semantics tree is used, so a clickable card whose label lives on a child text node
 * still passes. A hit means a control would be announced without a name.
 */
fun ComposeTestRule.assertEveryClickableControlIsLabeled(screen: String) {
    waitForIdle()
    val clickable = onAllNodes(hasClickAction()).fetchSemanticsNodes()
        .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
    assertTrue("$screen: tidak ada kontrol yang bisa diklik untuk diaudit", clickable.isNotEmpty())
    val unlabeled = clickable.filterNot { it.hasAccessibleLabel() }
    assertTrue(
        "$screen: ${unlabeled.size} kontrol yang bisa diklik tanpa teks/deskripsi: " +
            unlabeled.map { node -> "${node.id}@${node.boundsInRoot} keys=${node.config.joinToString(",") { it.key.name }}" },
        unlabeled.isEmpty(),
    )
}

/**
 * A control is announceable when it, or something it wraps, exposes text or a content
 * description. Clickable containers without merged semantics still pass when their visible
 * label is a child text, which is how TalkBack reads such cards.
 */
private fun SemanticsNode.hasAccessibleLabel(): Boolean {
    val text = config.getOrNull(SemanticsProperties.Text)
        ?.joinToString(" ") { it.text }
        .orEmpty()
        .ifBlank { config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty() }
    val description = config.getOrNull(SemanticsProperties.ContentDescription)
        ?.joinToString(" ")
        .orEmpty()
    if (text.isNotBlank() || description.isNotBlank()) return true
    return children.any { it.hasAccessibleLabel() }
}
