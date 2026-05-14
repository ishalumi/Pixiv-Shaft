package ceui.pixiv.chat.api

/**
 * Kotlin port of shaft-api-v2 `src/chat/threadId.js`, which is itself a
 * bit-exact port of weaver `utils.ReverseXOR` (`/Users/ceuilisa/Desktop/code/weaver/utils/util.go:122`).
 *
 * Two `uint64` user uids derive one symmetric, deterministic `uint64` thread
 * id without a server-assigned room number. Client A and client B
 * independently compute the same id and subscribe to the same room.
 *
 * ## Algorithm
 *
 * 1. sort:  `v1 = min(m,n)`, `v2 = max(m,n)`        — guarantees `f(a,b) == f(b,a)`
 * 2. encode v1, v2 to 8-byte big-endian buffers
 * 3. `b1[i] ^= b2[7 - i]`                           — XOR with byte-reversed counterpart
 * 4. decode b1 as big-endian uint64
 *
 * The byte-reverse mixing is inherited from weaver: it spreads snowflake
 * uids' timestamp-high-bit collisions across the full output range. Pixiv
 * uids aren't snowflakes (current ones are ≈ 10^8) but the mixing is
 * zero-cost and keeps bit-exact parity with weaver / shaft-api-v2 for
 * future cross-system interop.
 *
 * ## Sanity check
 *
 * `oneOnOneThreadId(1, 2)` = `"144115188075855873"`
 * (`0x0200_0000_0000_0001`). Matches JS / Go reference impls.
 */
object ChatThreadId {

    /** Server's literal `room_id` for the public broadcast room. */
    const val ROOM_GLOBAL = "global"

    /**
     * Bit-exact port of `weaver utils.ReverseXOR(m, n)`. Returns the result
     * as a [Long] holding the raw `uint64` bit pattern — read it as unsigned
     * via [java.lang.Long.toUnsignedString] / [java.lang.Long.compareUnsigned]
     * when the high bit is set.
     *
     * Inputs are treated as unsigned: ordering uses
     * [java.lang.Long.compareUnsigned] so any uid with bit 63 set still
     * sorts correctly. Pixiv uids today are ≈ 10^8 (well under 2^53) so the
     * sign concern is theoretical, but we keep parity with weaver / server.
     */
    fun reverseXOR(uidA: Long, uidB: Long): Long {
        val v1: Long
        val v2: Long
        if (java.lang.Long.compareUnsigned(uidA, uidB) <= 0) {
            v1 = uidA; v2 = uidB
        } else {
            v1 = uidB; v2 = uidA
        }
        // Pack v1 / v2 into 8 big-endian bytes (b1[0] = MSB).
        val b1 = ByteArray(8) { i -> (v1 ushr ((7 - i) * 8)).toByte() }
        val b2 = ByteArray(8) { i -> (v2 ushr ((7 - i) * 8)).toByte() }
        for (i in 0 until 8) {
            b1[i] = (b1[i].toInt() xor b2[7 - i].toInt()).toByte()
        }
        // Decode big-endian back into a Long.
        var out = 0L
        for (i in 0 until 8) {
            out = (out shl 8) or (b1[i].toLong() and 0xFF)
        }
        return out
    }

    /**
     * 1v1 chat room id for two pixiv uids.
     *
     * @return uint64 decimal string (≤ 20 chars). Drops straight into the
     *   server's `?room=<threadId>` query param and into `chat_messages.room_id`.
     * @throws IllegalArgumentException if `uidA == uidB`. Self-chat is
     *   rejected at the server handshake (`self_chat_not_allowed`); failing
     *   loudly on the client too saves a wasted round trip and a confusing
     *   401 log line.
     */
    fun oneOnOneThreadId(uidA: Long, uidB: Long): String {
        require(uidA != uidB) { "oneOnOneThreadId: same uid (self chat not allowed)" }
        return java.lang.Long.toUnsignedString(reverseXOR(uidA, uidB))
    }

    /**
     * Mirrors server-side `parseRoomKind(roomId)`. Useful when a room id
     * arrives from a route argument and the caller needs to dispatch:
     * `Global` (open subscription), `OneOnOne` (auth peer must match),
     * `Invalid` (refuse), `Unknown` (future kinds).
     */
    sealed class RoomKind {
        object Global : RoomKind()
        data class OneOnOne(val threadId: String) : RoomKind()
        object Invalid : RoomKind()
        object Unknown : RoomKind()
    }

    /** 1-9 leading, 1 to 20 digits total — covers 1..uint64 max (excludes 0). */
    private val ONE_ON_ONE_RE = Regex("^[1-9][0-9]{0,19}$")

    fun parseRoomKind(roomId: String?): RoomKind = when {
        roomId.isNullOrEmpty() -> RoomKind.Invalid
        roomId == ROOM_GLOBAL -> RoomKind.Global
        ONE_ON_ONE_RE.matches(roomId) -> RoomKind.OneOnOne(roomId)
        else -> RoomKind.Unknown
    }
}
