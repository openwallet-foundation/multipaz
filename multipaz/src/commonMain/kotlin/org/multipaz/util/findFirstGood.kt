package org.multipaz.util

/**
 * Flexible binary search implementation.
 *
 * Function [good] is defined for ints from `0` to `high-1`. Find i such that
 * `(i == 0 || !good(i-1)) && (i == h || good(i))`. In other words, good(i) is
 * the "first" good = true.
 */
fun findFirstGood(high: Int, good: (n: Int) -> Boolean): Int {
    var l = 0
    var h = high;
    while (true) {
        if (l == h) {
            return l
        }
        val m = (l + h) shr 1
        if (good.invoke(m)) {
            h = m
        } else {
            l = m + 1
        }
    }
}