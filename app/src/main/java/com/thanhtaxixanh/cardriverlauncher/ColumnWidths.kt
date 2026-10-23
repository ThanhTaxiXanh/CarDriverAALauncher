package com.thanhtaxixanh.cardriverlauncher

/**
 * Integer column widths for Grid Widget. Every result sums to exactly 100.
 * Pure Kotlin so that it can be tested without an Android emulator.
 */
object ColumnWidths {
    const val MIN_PERCENT = 5
    private const val MAX_COLUMNS = 3

    fun even(count: Int): List<Int> {
        val n = count.coerceIn(1, MAX_COLUMNS)
        val base = 100 / n
        val remainder = 100 % n
        return List(n) { base + if (it < remainder) 1 else 0 }
    }

    fun normalize(widths: List<Int>, count: Int): List<Int> {
        val n = count.coerceIn(1, MAX_COLUMNS)
        if (widths.size != n) return even(n) // V1 settings migration or grid resize
        if (widths.all { it >= MIN_PERCENT } && widths.sumOf { it.toLong() } == 100L) return widths
        return distribute(widths, 100)
    }

    /** Adjust one column and automatically rebalance the others. */
    fun update(widths: List<Int>, index: Int, percent: Int): List<Int> {
        val current = normalize(widths, widths.size)
        if (index !in current.indices || current.size == 1) return current
        val chosen = percent.coerceIn(MIN_PERCENT, 100 - (current.size - 1) * MIN_PERCENT)
        val otherIndices = current.indices.filter { it != index }
        val otherWeights = otherIndices.map { (current[it] - MIN_PERCENT).coerceAtLeast(0) }
        val balanced = distribute(otherWeights, 100 - chosen)
        return MutableList(current.size) { 0 }.also { output ->
            output[index] = chosen
            otherIndices.forEachIndexed { i, column -> output[column] = balanced[i] }
        }
    }

    /** Largest remainder: exact total, stable tie-breaking, minimum 5% per column. */
    private fun distribute(weights: List<Int>, total: Int): List<Int> {
        val n = weights.size
        if (n == 0) return emptyList()
        val budget = total - MIN_PERCENT * n
        val safeWeights = weights.map { it.toLong().coerceAtLeast(0L) }
        val sum = safeWeights.sum()
        val effective = if (sum == 0L) List(n) { 1L } else safeWeights
        val divisor = effective.sum()
        val extra = effective.map { budget.toLong() * it / divisor }
        val output = extra.map { it.toInt() + MIN_PERCENT }.toMutableList()
        val missing = total - output.sum()
        val order = effective.indices.sortedWith(
            compareByDescending<Int> { budget.toLong() * effective[it] % divisor }.thenBy { it }
        )
        repeat(missing) { output[order[it]]++ }
        return output
    }
}
