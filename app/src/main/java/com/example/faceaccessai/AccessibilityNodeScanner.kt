package com.example.faceaccessai

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

class AccessibilityNodeScanner {

    companion object {
        private const val TAG = "AccessibilityScanner"
        private const val MAX_VISITED_NODES = 1000
    }

    data class ScanCandidate(
        val bounds: Rect,
        val text: String?,
        val contentDescription: String?,
        val className: String?,
        val packageName: String?,
        val clickable: Boolean,
        val enabled: Boolean,
        val depth: Int,
        val isEditable: Boolean
    )

    fun scanQuadrant(
        root: AccessibilityNodeInfo?,
        windowBounds: Rect,
        quadrantIndex: Int
    ): ScanCandidate? {
        if (quadrantIndex !in 0..3) {
            Log.w(TAG, "SCAN_UNAVAILABLE | Reason=INVALID_QUADRANT | Index=$quadrantIndex")
            return null
        }

        if (root == null || windowBounds.isEmpty) {
            Log.w(TAG, "SCAN_UNAVAILABLE | Reason=NO_ROOT_OR_INVALID_BOUNDS")
            return null
        }

        val targetRegion = calculateQuadrantRect(windowBounds, quadrantIndex)
        Log.d(TAG, "SCAN_START | Quadrant=$quadrantIndex | WindowBounds=$windowBounds | TargetRegion=$targetRegion")

        // Map để dedup: key = "left,top,right,bottom|className"
        val candidatesMap = mutableMapOf<String, ScanCandidate>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        var visitedCount = 0
        var actionableCount = 0
        var inQuadrantCount = 0

        while (queue.isNotEmpty() && visitedCount < MAX_VISITED_NODES) {
            val (node, depth) = queue.removeFirst()
            visitedCount++

            if (isActionable(node)) {
                actionableCount++
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                if (!bounds.isEmpty && targetRegion.contains(bounds.centerX(), bounds.centerY())) {
                    inQuadrantCount++
                    val candidate = createCandidate(node, bounds, depth)
                    val dedupKey = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}|${candidate.className}"
                    
                    val existing = candidatesMap[dedupKey]
                    if (existing == null || isBetterForDedup(candidate, existing)) {
                        candidatesMap[dedupKey] = candidate
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child to depth + 1)
                }
            }
        }

        val bestCandidate = selectBestCandidate(candidatesMap.values.toList(), targetRegion)
        
        if (bestCandidate != null) {
            val label = getCandidateLabel(bestCandidate)
            Log.d(TAG, "SCAN_RESULT | Quadrant=$quadrantIndex | Visited=$visitedCount | Actionable=$actionableCount | InQuadrant=${candidatesMap.size} | CandidateText=$label | Class=${bestCandidate.className} | Bounds=${bestCandidate.bounds}")
        } else {
            Log.d(TAG, "SCAN_RESULT | Quadrant=$quadrantIndex | Visited=$visitedCount | Actionable=$actionableCount | InQuadrant=0 | Candidate=NONE")
        }

        return bestCandidate
    }

    fun findScrollableNodeAt(
        root: AccessibilityNodeInfo?,
        cursorX: Float,
        cursorY: Float
    ): AccessibilityNodeInfo? {
        if (root == null) return null

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var bestScrollable: AccessibilityNodeInfo? = null
        var visitedCount = 0

        while (queue.isNotEmpty() && visitedCount < MAX_VISITED_NODES) {
            val node = queue.removeFirst()
            visitedCount++

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (!bounds.isEmpty && bounds.contains(cursorX.toInt(), cursorY.toInt())) {
                if (node.isScrollable) {
                    // Ưu tiên node con sâu nhất (thường là list thực sự)
                    bestScrollable = node
                }
                
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        }
        return bestScrollable
    }

    fun findNearestActionableNode(
        root: AccessibilityNodeInfo?,
        cursorX: Float,
        cursorY: Float,
        maxDistance: Float
    ): ScanCandidate? {
        if (root == null) return null

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        var bestCandidate: ScanCandidate? = null
        var bestScore = Double.MAX_VALUE
        var visitedCount = 0

        while (queue.isNotEmpty() && visitedCount < MAX_VISITED_NODES) {
            val (node, depth) = queue.removeFirst()
            visitedCount++

            if (isActionable(node)) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                if (!bounds.isEmpty) {
                    val centerX = bounds.centerX().toFloat()
                    val centerY = bounds.centerY().toFloat()
                    val dx = cursorX - centerX
                    val dy = cursorY - centerY
                    val distance = Math.sqrt((dx * dx + dy * dy).toDouble())

                    if (distance < maxDistance) {
                        // Scoring: distance is primary, but prefer smaller nodes (buttons) over large containers
                        // and prefer nodes with text/labels.
                        val area = bounds.width() * bounds.height()
                        val hasLabel = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
                        
                        // Score = Distance + (log(Area) * factor) - (LabelBonus)
                        var score = distance
                        score += Math.log10(area.toDouble().coerceAtLeast(1.0)) * 2.0
                        if (hasLabel) score -= 10.0
                        
                        if (score < bestScore) {
                            bestScore = score
                            bestCandidate = createCandidate(node, bounds, depth)
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child to depth + 1)
                }
            }
        }

        return bestCandidate
    }

    private fun calculateQuadrantRect(windowBounds: Rect, index: Int): Rect {
        val midX = windowBounds.centerX()
        val midY = windowBounds.centerY()

        return when (index) {
            0 -> Rect(windowBounds.left, windowBounds.top, midX, midY) // Top-Left
            1 -> Rect(midX, windowBounds.top, windowBounds.right, midY) // Top-Right
            2 -> Rect(windowBounds.left, midY, midX, windowBounds.bottom) // Bottom-Left
            3 -> Rect(midX, midY, windowBounds.right, windowBounds.bottom) // Bottom-Right
            else -> Rect()
        }
    }

    private fun isActionable(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        
        // Skip our own app
        if (node.packageName?.toString() == "com.example.faceaccessai") return false

        return node.isClickable || 
               node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
    }

    private fun createCandidate(node: AccessibilityNodeInfo, bounds: Rect, depth: Int): ScanCandidate {
        // FIX 1: Privacy for editable text
        val safeText = when {
            node.isPassword -> "<password>"
            node.isEditable -> null
            else -> node.text?.toString()
        }

        return ScanCandidate(
            bounds = Rect(bounds),
            text = safeText,
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            packageName = node.packageName?.toString(),
            clickable = node.isClickable,
            enabled = node.isEnabled,
            depth = depth,
            isEditable = node.isEditable
        )
    }

    // Ưu tiên candidate có text/description khi trùng bounds
    private fun isBetterForDedup(new: ScanCandidate, old: ScanCandidate): Boolean {
        val newHasInfo = !new.text.isNullOrBlank() || !new.contentDescription.isNullOrBlank()
        val oldHasInfo = !old.text.isNullOrBlank() || !old.contentDescription.isNullOrBlank()
        return newHasInfo && !oldHasInfo
    }

    private fun selectBestCandidate(candidates: List<ScanCandidate>, targetRegion: Rect): ScanCandidate? {
        if (candidates.isEmpty()) return null

        val targetCenterX = targetRegion.centerX()
        val targetCenterY = targetRegion.centerY()

        // FIX 4: Ranking deterministic
        return candidates.minWithOrNull(compareBy<ScanCandidate> { candidate ->
            val nodeCenterX = candidate.bounds.centerX()
            val nodeCenterY = candidate.bounds.centerY()
            
            // 1. Khoảng cách đến tâm quadrant
            Math.sqrt(
                Math.pow((nodeCenterX - targetCenterX).toDouble(), 2.0) +
                Math.pow((nodeCenterY - targetCenterY).toDouble(), 2.0)
            )
        }.thenBy { candidate ->
            // 2. Ưu tiên có thông tin nhãn (0 nếu có, 1 nếu không)
            if (!candidate.text.isNullOrBlank() || !candidate.contentDescription.isNullOrBlank()) 0 else 1
        }.thenBy { candidate ->
            // 3. Ưu tiên diện tích nhỏ hơn
            candidate.bounds.width() * candidate.bounds.height()
        }.thenBy { candidate ->
            // 4. Tie-break: depth hoặc thứ tự (đã ổn định trong list)
            candidate.depth
        })
    }

    private fun getCandidateLabel(candidate: ScanCandidate): String? {
        return candidate.text ?: candidate.contentDescription ?: candidate.className
    }
}
