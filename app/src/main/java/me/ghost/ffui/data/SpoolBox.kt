package me.ghost.ffui.data

import me.ghost.ffui.api.SpoolmanSpool

/**
 * A box == a distinct non-blank Spoolman [SpoolmanSpool.location], with its member spools.
 * Derived client-side by grouping the loaded spool list on `location`. No new API endpoint
 * or Room table is needed — the box is purely a view over existing spool data.
 *
 * @property location The location string (trimmed). Acts as the box identity.
 * @property spools All spools that share this location.
 */
data class SpoolBox(
    val location: String,
    val spools: List<SpoolmanSpool>
)

/**
 * Derives a list of [SpoolBox]es from the flat spool list. Spools with null/blank
 * [SpoolmanSpool.location] are excluded. Boxes are sorted alphabetically by location
 * (case-insensitive).
 */
fun boxesFrom(spools: List<SpoolmanSpool>): List<SpoolBox> =
    spools
        .filter { !it.location.isNullOrBlank() }
        .groupBy { it.location!!.trim() }
        .map { (loc, members) -> SpoolBox(loc, members) }
        .sortedBy { it.location.lowercase() }
