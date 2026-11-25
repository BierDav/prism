// Long-press-then-drag reorderable column that coexists with vertical page
// scrolling. Releasing a long press without dragging notifies onLongPressRelease
// instead of the row's own click (a clickable row would otherwise take that
// release as a tap).

package at.quickme.prism.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs

@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDividers: Boolean = true,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    onLongPressRelease: ((index: Int) -> Unit)? = null,
    itemContent: @Composable (index: Int, item: T, isDragging: Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val dragSlopPx = with(density) { 8.dp.toPx() }
    val dragElevationPx = with(density) { 6.dp.toPx() }

    val heights = remember { mutableStateMapOf<Int, Int>() }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var targetIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var didDrag by remember { mutableStateOf(false) }
    val longPressRelease by rememberUpdatedState(onLongPressRelease)

    fun computeTarget(from: Int, offsetY: Float): Int {
        var idx = from
        if (offsetY > 0f) {
            var remaining = offsetY
            while (idx < items.lastIndex) {
                val h = (heights[idx + 1] ?: heights[from] ?: 1).toFloat()
                if (remaining > h * 0.5f) {
                    remaining -= h
                    idx++
                } else break
            }
        } else if (offsetY < 0f) {
            var remaining = -offsetY
            while (idx > 0) {
                val h = (heights[idx - 1] ?: heights[from] ?: 1).toFloat()
                if (remaining > h * 0.5f) {
                    remaining -= h
                    idx--
                } else break
            }
        }
        return idx
    }

    val draggedHeight = (heights[draggingIndex] ?: 0).toFloat()

    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            key(key(item)) {
                val isDragging = draggingIndex == index
                val shiftTarget = when {
                    draggingIndex < 0 || isDragging -> 0f
                    draggingIndex < targetIndex && index in (draggingIndex + 1)..targetIndex -> -draggedHeight
                    draggingIndex > targetIndex && index in targetIndex until draggingIndex -> draggedHeight
                    else -> 0f
                }
                val animatedShift by animateFloatAsState(
                    targetValue = shiftTarget,
                    label = "reorderShift",
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .onGloballyPositioned { coords -> heights[index] = coords.size.height }
                        .graphicsLayer {
                            translationY = if (isDragging) dragOffsetY else animatedShift
                            shadowElevation = if (isDragging) dragElevationPx else 0f
                        }
                        // A long press released where it started: take the release in the
                        // Initial pass, before the row's clickable (a child) sees it as a tap.
                        // The drag detector below then sees it consumed and cancels.
                        .pointerInput(items, enabled) {
                            if (!enabled) return@pointerInput
                            awaitEachGesture {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.none { it.changedToUpIgnoreConsumed() }) continue
                                    val release = longPressRelease
                                    if (release != null && draggingIndex == index && !didDrag) {
                                        event.changes.forEach { it.consume() }
                                        draggingIndex = -1
                                        targetIndex = -1
                                        dragOffsetY = 0f
                                        release(index)
                                    }
                                    break
                                }
                            }
                        }
                        .pointerInput(items, enabled) {
                            if (!enabled) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingIndex = index
                                    targetIndex = index
                                    dragOffsetY = 0f
                                    didDrag = false
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetY += dragAmount.y
                                    if (!didDrag && abs(dragOffsetY) > dragSlopPx) {
                                        didDrag = true
                                    }
                                    val next = computeTarget(index, dragOffsetY)
                                    if (next != targetIndex) {
                                        targetIndex = next
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                onDragEnd = {
                                    val from = draggingIndex
                                    val to = targetIndex
                                    val dragged = didDrag
                                    draggingIndex = -1
                                    targetIndex = -1
                                    dragOffsetY = 0f
                                    didDrag = false
                                    if (dragged && from >= 0 && to >= 0 && from != to) {
                                        onReorder(from, to)
                                    } else if (!dragged && from >= 0) {
                                        longPressRelease?.invoke(from)
                                    }
                                },
                                onDragCancel = {
                                    draggingIndex = -1
                                    targetIndex = -1
                                    dragOffsetY = 0f
                                    didDrag = false
                                },
                            )
                        },
                ) {
                    itemContent(index, item, isDragging)
                }

                if (showDividers && index < items.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}
