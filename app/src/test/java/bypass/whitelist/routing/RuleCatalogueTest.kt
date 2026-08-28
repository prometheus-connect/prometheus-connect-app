package bypass.whitelist.routing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The bundle format, which is the one piece of the catalogue this app has to
 * read for itself.
 *
 * It is written by another program on another machine, so what is guarded here
 * is the two drifting apart: a walk that loses count by one byte would hand a
 * neighbouring category's rules back under the name the user asked for, and
 * routing by the wrong list is the failure mode with no symptom.
 */
class RuleCatalogueTest {

    @Test
    fun `a bundle gives up only the categories asked for`() {
        val bundle = bundle("ads" to "one".toByteArray(), "private" to "two".toByteArray())
        val out = RuleCatalogue.extract(bundle, setOf("private"))
        assertEquals(1, out.size)
        assertArrayEquals("two".toByteArray(), out["private"])
        assertNull(out["ads"])
    }

    @Test
    fun `entries after a long one are still found`() {
        // The whole risk in a walk is the length that moves the cursor, so put a
        // fat entry in front of the one that matters.
        val bundle = bundle(
            "big" to ByteArray(70_000) { 7 },
            "small" to "here".toByteArray(),
        )
        assertArrayEquals("here".toByteArray(), RuleCatalogue.extract(bundle, setOf("small"))["small"])
    }

    @Test
    fun `a bundle that is not one is refused`() {
        val notABundle = "PCRT".toByteArray() + ByteArray(16)
        val thrown = runCatching { RuleCatalogue.extract(notABundle, setOf("anything")) }
        assertEquals("bad bundle magic", thrown.exceptionOrNull()?.message)
    }

    /** Magic, version, three pad bytes, a count, then name and payload per entry. */
    private fun bundle(vararg entries: Pair<String, ByteArray>): ByteArray {
        var out = "PCBN".toByteArray() + byteArrayOf(1, 0, 0, 0) + intLe(entries.size)
        for ((name, payload) in entries) {
            val raw = name.toByteArray()
            out += shortLe(raw.size) + raw + intLe(payload.size) + payload
        }
        return out
    }

    private fun intLe(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun shortLe(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
}
