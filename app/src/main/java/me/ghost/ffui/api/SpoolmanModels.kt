package me.ghost.ffui.api

import kotlinx.serialization.Serializable

/**
 * A Spoolman vendor (filament manufacturer).
 *
 * @property id Unique vendor identifier.
 * @property registered ISO-8601 datetime when the vendor was created.
 * @property name Human-readable vendor name.
 * @property comment Free-text comment about this vendor.
 * @property emptySpoolWeight Weight of an empty spool from this vendor, in grams.
 * @property externalId ID in an external database if applicable.
 */
@Serializable
data class SpoolmanVendor(
    val id: Int,
    val registered: String? = null,
    val name: String? = null,
    val comment: String? = null,
    val empty_spool_weight: Float? = null,
    val external_id: String? = null,
)

/**
 * A Spoolman filament type. Belongs to a [SpoolmanVendor] and is referenced by [SpoolmanSpool]s.
 *
 * Numeric fields are `Float?` — the same firmware-style inconsistency guard the printer models use;
 * Spoolman may return integers and decimals interchangeably.
 *
 * @property id Unique filament identifier.
 * @property registered ISO-8601 datetime when the filament was created.
 * @property name Filament name (typically includes color).
 * @property vendor The vendor of this filament, if set.
 * @property material Material type (e.g. "PLA", "PETG").
 * @property price Price in the system-configured currency.
 * @property density Density in g/cm³.
 * @property diameter Diameter in mm.
 * @property weight Net filament weight per full spool, in grams.
 * @property spoolWeight Empty spool weight, in grams.
 * @property articleNumber Vendor article number (e.g. EAN).
 * @property comment Free-text comment.
 * @property settingsExtruderTemp Recommended extruder temperature in °C.
 * @property settingsBedTemp Recommended bed temperature in °C.
 * @property colorHex Hexadecimal color code (e.g. "FF0000" for red).
 * @property multiColorHexes Multiple hex colors separated by commas.
 * @property multiColorDirection Multi-color direction ("coaxial" or "longitudinal").
 * @property externalId ID in an external database.
 */
@Serializable
data class SpoolmanFilament(
    val id: Int,
    val registered: String? = null,
    val name: String? = null,
    val vendor: SpoolmanVendor? = null,
    val material: String? = null,
    val price: Float? = null,
    val density: Float? = null,
    val diameter: Float? = null,
    val weight: Float? = null,
    val spool_weight: Float? = null,
    val article_number: String? = null,
    val comment: String? = null,
    val settings_extruder_temp: Float? = null,
    val settings_bed_temp: Float? = null,
    val color_hex: String? = null,
    val multi_color_hexes: String? = null,
    val multi_color_direction: String? = null,
    val external_id: String? = null,
)

/**
 * A Spoolman spool — a physical spool of filament.
 *
 * Numeric fields are `Float?` for the same reason as [SpoolmanFilament]: the API may return
 * integers or decimals interchangeably. `id` stays `Int`.
 *
 * @property id Unique spool identifier.
 * @property registered ISO-8601 datetime when the spool was created.
 * @property firstUsed First logged usage datetime.
 * @property lastUsed Last logged usage datetime.
 * @property filament The filament type on this spool.
 * @property price Price in the system-configured currency.
 * @property initialWeight Initial filament weight in grams.
 * @property spoolWeight Empty spool (tare) weight in grams.
 * @property remainingWeight Remaining filament weight in grams.
 * @property usedWeight Used filament weight in grams.
 * @property location Where this spool can be found.
 * @property lotNr Vendor manufacturing lot/batch number.
 * @property comment Free-text comment about this specific spool.
 * @property archived Whether this spool is archived.
 */
@Serializable
data class SpoolmanSpool(
    val id: Int,
    val registered: String? = null,
    val first_used: String? = null,
    val last_used: String? = null,
    val filament: SpoolmanFilament,
    val price: Float? = null,
    val initial_weight: Float? = null,
    val spool_weight: Float? = null,
    val remaining_weight: Float? = null,
    val used_weight: Float? = null,
    val remaining_length: Float? = null,
    val used_length: Float? = null,
    val location: String? = null,
    val lot_nr: String? = null,
    val comment: String? = null,
    val archived: Boolean = false,
) {
    /**
     * Remaining filament as a fraction 0..1. Computed as `remaining_weight / initial_weight`
     * when both are present and `initial_weight > 0`; otherwise `null` (UI shows grams-only, no bar).
     */
    val progress: Float?
        get() {
            val rem = remaining_weight ?: return null
            val init = initial_weight ?: return null
            if (init <= 0f) return null
            return (rem / init).coerceIn(0f, 1f)
        }

    /**
     * Human-readable display name for this spool: the filament name, falling back to the material
     * type, then to "Spool #id".
     */
    val displayName: String
        get() = filament.name ?: filament.material ?: "Spool #$id"
}

/**
 * Request body for `PUT /spool/{id}/use` — deduct filament usage from a spool.
 * Specify either [use_weight] or [use_length], not both.
 *
 * @property useWeight Filament weight to reduce by, in grams.
 * @property useLength Filament length to reduce by, in mm.
 */
@Serializable
data class SpoolUseBody(
    val use_weight: Float? = null,
    val use_length: Float? = null
)

/**
 * Request body for `PATCH /spool/{id}` — update spool attributes.
 * Only fields specified (non-null) will be affected.
 *
 * @property location Where this spool can be found.
 * @property lotNr Vendor manufacturing lot/batch number.
 * @property comment Free-text comment.
 * @property archived Whether this spool is archived.
 * @property remainingWeight Remaining filament weight in grams.
 */
@Serializable
data class SpoolPatchBody(
    val location: String? = null,
    val lot_nr: String? = null,
    val comment: String? = null,
    val archived: Boolean? = null,
    val remaining_weight: Float? = null
)
