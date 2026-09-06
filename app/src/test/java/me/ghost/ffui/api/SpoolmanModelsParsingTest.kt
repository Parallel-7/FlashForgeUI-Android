package me.ghost.ffui.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Parse-fixture tests for the Spoolman wire models. Numeric fields are `Float?` as the
 * firmware-style guard against servers returning integers and decimals interchangeably; unknown
 * fields must be ignored (Spoolman grows its schema independently of this app); absent/null
 * fields must decode to null. The JSON instance mirrors [SpoolmanApi]'s configuration.
 *
 * Malformed JSON must throw [SerializationException] — that is the documented failure path the
 * API's `catch` blocks turn into `Result.failure`.
 */
class SpoolmanModelsParsingTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // ── Numeric tolerance: int and decimal literals both decode ──────────────────

    @Test
    fun `numeric fields accept integer and decimal literals interchangeably`() {
        val body = """
            {
              "id": 7,
              "price": 24,
              "initial_weight": 1000,
              "remaining_weight": 742.5,
              "used_weight": 257.5,
              "remaining_length": 285000,
              "used_length": 99000.75,
              "filament": {
                "id": 3,
                "density": 1.24,
                "diameter": 1.75,
                "weight": 1000,
                "settings_extruder_temp": 200,
                "settings_bed_temp": 60.5
              }
            }
        """.trimIndent()

        val spool = json.decodeFromString<SpoolmanSpool>(body)
        assertEquals(7, spool.id)
        assertEquals(24f, spool.price)
        assertEquals(1000f, spool.initial_weight)
        assertEquals(742.5f, spool.remaining_weight)
        assertEquals(257.5f, spool.used_weight)
        assertEquals(285000f, spool.remaining_length)
        assertEquals(99000.75f, spool.used_length)
        assertEquals(1.24f, spool.filament.density)
        assertEquals(1.75f, spool.filament.diameter)
        assertEquals(1000f, spool.filament.weight)
        assertEquals(200f, spool.filament.settings_extruder_temp)
        assertEquals(60.5f, spool.filament.settings_bed_temp)
    }

    @Test
    fun `unknown fields are ignored`() {
        // Spoolman's schema evolves server-side; the app must not break on new fields.
        val body = """
            {
              "id": 1,
              "some_future_field": {"nested": [1, 2, 3]},
              "another_one": "value",
              "filament": {"id": 2, "material": "PLA", "brand_new": 42}
            }
        """.trimIndent()
        val spool = json.decodeFromString<SpoolmanSpool>(body)
        assertEquals(1, spool.id)
        assertEquals("PLA", spool.filament.material)
    }

    @Test
    fun `absent and explicit null optional fields decode to null`() {
        val body = """
            {
              "id": 1,
              "location": null,
              "filament": {"id": 2, "name": null, "vendor": null}
            }
        """.trimIndent()
        val spool = json.decodeFromString<SpoolmanSpool>(body)
        assertNull(spool.location)
        assertNull(spool.first_used)
        assertNull(spool.filament.name)
        assertNull(spool.filament.vendor)
        assertEquals(false, spool.archived) // defaulted, not null
    }

    @Test
    fun `nested vendor object decodes`() {
        val body = """
            {
              "id": 1,
              "filament": {
                "id": 2,
                "vendor": {"id": 9, "name": "Prusa", "empty_spool_weight": 245.5}
              }
            }
        """.trimIndent()
        val vendor = json.decodeFromString<SpoolmanSpool>(body).filament.vendor
        assertEquals(9, vendor?.id)
        assertEquals("Prusa", vendor?.name)
        assertEquals(245.5f, vendor?.empty_spool_weight)
    }

    // ── Derived properties ────────────────────────────────────────────────────────

    @Test
    fun `progress is remaining over initial, clamped to 0-1`() {
        fun spool(remaining: Float?, initial: Float?) =
            SpoolmanSpool(id = 1, filament = SpoolmanFilament(id = 2), remaining_weight = remaining, initial_weight = initial)

        assertEquals(0.7425f, spool(742.5f, 1000f).progress!!, 1e-6f)
        // Over-full / negative servers must not render a >100% or negative bar.
        assertEquals(1f, spool(1200f, 1000f).progress!!, 1e-6f)
        assertEquals(0f, spool(-5f, 1000f).progress!!, 1e-6f)
        // Nothing to compute from.
        assertNull(spool(null, 1000f).progress)
        assertNull(spool(500f, null).progress)
        assertNull(spool(500f, 0f).progress) // zero initial weight — divide-by-zero guard
    }

    @Test
    fun `displayName falls back from filament name to material to spool id`() {
        fun spool(name: String?, material: String?) =
            SpoolmanSpool(id = 31, filament = SpoolmanFilament(id = 2, name = name, material = material))

        assertEquals("Galaxy Black", spool("Galaxy Black", "PLA").displayName)
        assertEquals("PETG", spool(null, "PETG").displayName)
        assertEquals("Spool #31", spool(null, null).displayName)
    }

    // ── Wire shapes the app sends ─────────────────────────────────────────────────

    @Test
    fun `use and patch bodies encode snake_case wire keys`() {
        assertEquals("""{"use_weight":12.5}""", json.encodeToString(SpoolUseBody(use_weight = 12.5f)))
        assertEquals(
            """{"location":"Shelf A","archived":true}""",
            json.encodeToString(SpoolPatchBody(location = "Shelf A", archived = true)),
        )
    }

    @Test
    fun `spool list decodes (the getSpools response shape)`() {
        val body = """
            [
              {"id": 1, "filament": {"id": 2, "material": "PLA"}},
              {"id": 2, "archived": true, "filament": {"id": 3, "material": "PETG"}}
            ]
        """.trimIndent()
        val spools = json.decodeFromString<List<SpoolmanSpool>>(body)
        assertEquals(2, spools.size)
        assertEquals(true, spools[1].archived)
    }

    // ── Documented failure path ───────────────────────────────────────────────────

    @Test
    fun `malformed json throws SerializationException`() {
        // SpoolmanApi catches exactly this and returns Result.failure — a half-written body or an
        // HTML error page must surface as a failed fetch, never a crash.
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<SpoolmanSpool>("""{"id": 1, "filament"""")
        }
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<List<SpoolmanSpool>>("<html>502 Bad Gateway</html>")
        }
    }

    @Test
    fun `missing required filament field throws`() {
        // `filament` is the one required member; a spool row without it is a decode failure.
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<SpoolmanSpool>("""{"id": 1}""")
        }
    }
}
