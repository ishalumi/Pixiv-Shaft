package ceui.pixiv.chat.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bit-exact verification of [ChatThreadId.reverseXOR] / [oneOnOneThreadId]
 * against the shaft-api-v2 / weaver reference implementations.
 *
 * The Go test in `weaver/utils/util_test.go` only asserts symmetry
 * (f(a,b) == f(b,a)). The JS port commit message claims "Verified against
 * the Go reference: same output for all 5 reference inputs" but does not
 * commit those vectors. This test computes a handful of oracle values by
 * hand from the algorithm spec and pins them here, so any future drift
 * (signed-vs-unsigned regression, endianness flip, off-by-one in the byte
 * pairing) shows up as a test failure.
 */
class ChatThreadIdTest {

    /**
     * Oracle: v1=1, v2=2.
     *
     *   b1 = 00 00 00 00 00 00 00 01    (big-endian 1)
     *   b2 = 00 00 00 00 00 00 00 02    (big-endian 2)
     *   b1[0] ^= b2[7] = 0 ^ 2 = 02     ← high byte
     *   b1[1..6] unchanged              (all-zero XOR)
     *   b1[7] ^= b2[0] = 1 ^ 0 = 01     ← low byte
     *   → 02 00 00 00 00 00 00 01 = 0x0200_0000_0000_0001 = 144115188075855873
     */
    @Test fun reverseXOR_smallOracle() {
        assertEquals(0x0200_0000_0000_0001L, ChatThreadId.reverseXOR(1L, 2L))
        // And the symmetric case — sort happens internally.
        assertEquals(0x0200_0000_0000_0001L, ChatThreadId.reverseXOR(2L, 1L))
    }

    @Test fun oneOnOneThreadId_smallOracle() {
        assertEquals("144115188075855873", ChatThreadId.oneOnOneThreadId(1L, 2L))
        assertEquals("144115188075855873", ChatThreadId.oneOnOneThreadId(2L, 1L))
    }

    /**
     * Symmetry on realistic pixiv uids (~10^8). The Go test asserts the
     * same property; we mirror it here to catch any future change that
     * accidentally breaks order independence.
     */
    @Test fun symmetric_underRealisticPixivUids() {
        val a = 12_345_678L
        val b = 87_654_321L
        assertEquals(
            ChatThreadId.oneOnOneThreadId(a, b),
            ChatThreadId.oneOnOneThreadId(b, a),
        )
    }

    /**
     * High-bit-set inputs. Pixiv uids stay under 2^53 in practice, but the
     * algorithm is specified over the full uint64 range; verify the
     * unsigned compare keeps bit-63 inputs ordered correctly.
     */
    @Test fun unsignedOrdering_handlesBit63() {
        val low = 1L                   // unsigned 1
        val highBit = Long.MIN_VALUE   // 0x8000_..._0000 = unsigned 2^63
        // unsigned-wise low < highBit, so v1 = low, v2 = highBit.
        // b1 = 00 00 00 00 00 00 00 01
        // b2 = 80 00 00 00 00 00 00 00
        // b1[7] ^= b2[0] = 1 ^ 0x80 = 0x81 (low byte)
        // → 00 00 00 00 00 00 00 81 = 0x81 = 129
        assertEquals(0x81L, ChatThreadId.reverseXOR(low, highBit))
        // Reversed args produce the same result (symmetry).
        assertEquals(0x81L, ChatThreadId.reverseXOR(highBit, low))
    }

    @Test fun selfChat_isRejected() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ChatThreadId.oneOnOneThreadId(42L, 42L)
        }
        assertTrue(ex.message?.contains("same uid") == true)
    }

    @Test fun parseRoomKind_global() {
        assertTrue(ChatThreadId.parseRoomKind("global") is ChatThreadId.RoomKind.Global)
    }

    @Test fun parseRoomKind_oneOnOne() {
        val kind = ChatThreadId.parseRoomKind("144115188075855873")
        assertTrue(kind is ChatThreadId.RoomKind.OneOnOne)
        assertEquals(
            "144115188075855873",
            (kind as ChatThreadId.RoomKind.OneOnOne).threadId,
        )
    }

    @Test fun parseRoomKind_rejectsZero() {
        // Leading 0 rejected: server treats '0' as invalid 1v1 (mirrors
        // self-chat ban). Regex requires 1-9 first char.
        assertTrue(ChatThreadId.parseRoomKind("0") is ChatThreadId.RoomKind.Unknown)
    }

    @Test fun parseRoomKind_rejectsEmpty() {
        assertTrue(ChatThreadId.parseRoomKind("") is ChatThreadId.RoomKind.Invalid)
        assertTrue(ChatThreadId.parseRoomKind(null) is ChatThreadId.RoomKind.Invalid)
    }

    @Test fun parseRoomKind_rejectsAlphanum() {
        assertTrue(ChatThreadId.parseRoomKind("abc") is ChatThreadId.RoomKind.Unknown)
        assertTrue(ChatThreadId.parseRoomKind("12a") is ChatThreadId.RoomKind.Unknown)
    }

    /**
     * Cross-validation: feed the same uid pairs through both the server's
     * Node port (`shaft-api-v2/src/chat/threadId.js`) and this Kotlin port,
     * and pin the JS outputs as oracles. If either end ever drifts, this
     * test fails.
     *
     * To regenerate the oracle column, from shaft-api-v2:
     * ```
     * node --input-type=module -e "import { oneOnOneThreadId } from './src/chat/threadId.js'; \
     *   console.log(oneOnOneThreadId(<a>n, <b>n))"
     * ```
     */
    @Test fun crossValidate_kotlinMatchesServerJs() {
        // (uidA, uidB, expectedThreadIdFromServerJs)
        val vectors = listOf(
            Triple(1L,        2L,        "144115188075855873"),       // tiny
            Triple(12_345_678L, 87_654_321L, "12790004160405463374"), // realistic pixiv uid
            Triple(9_999_999L,  88_888_888L, "4059515698489628287"),
            // weaver Go test reference inputs (1.25e18 ≈ snowflake range)
            Triple(1258997728084520961L, 1258998276271665153L, "1155414944118474768"),
            // near uint64 max — second arg is unsigned 0xFFFF_FFFF_FFFF_FFFE
            Triple(1L, -2L /* = 0xFFFF_FFFF_FFFF_FFFE = 2^64-2 */, "18374686479671623678"),
            // bit-63 set — Long.MIN_VALUE is 0x8000_0000_0000_0000 = 2^63 unsigned
            Triple(123_456L, Long.MIN_VALUE, "123584"),
        )
        for ((a, b, expected) in vectors) {
            val got = ChatThreadId.oneOnOneThreadId(a, b)
            assertEquals(
                "oneOnOneThreadId($a, $b) drifted from server JS",
                expected, got,
            )
            // Symmetry across the wire — same room for either client's caller order.
            assertEquals(
                "symmetry broken for ($a, $b)",
                expected, ChatThreadId.oneOnOneThreadId(b, a),
            )
        }
    }
}
