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

    /**
     * Inverse of [reverseXOR]: given the caller's own uid and a 1v1 room id,
     * recover the peer's uid. Mirrors server's `peerFromRoomId` from
     * shaft-api-v2/src/chat/threadId.js.
     *
     * Why this is recoverable at all: `reverseXOR(min, max)` packs
     * `min[i] ^ max[7-i]` into 8 bytes. With one of the two uids known, the
     * other is just a per-byte XOR plus a min/max disambiguation. We try
     * both orderings and accept only the one whose `reverseXOR(me, peer)`
     * round-trips back to the original room id — that gates out malformed
     * inputs (e.g. someone hand-typing a non-1v1 room id).
     *
     * Returns the peer's uid as a decimal string (matches server contract),
     * or `null` if the input is `"global"`, the row doesn't round-trip, or
     * the math degenerates to `peer == me` / `peer == 0`. The decimal-string
     * return is intentional even though pixiv uids fit in `Long`: it keeps
     * us bit-compatible with snowflake / big-id chat IDs the server may
     * issue in the future.
     */
    fun peerFromRoomId(myUid: Long, roomId: String): String? {
        if (roomId == ROOM_GLOBAL) return null
        if (myUid == 0L) return null
        if (!ONE_ON_ONE_RE.matches(roomId)) return null

        val room = runCatching { java.lang.Long.parseUnsignedLong(roomId) }.getOrNull() ?: return null
        val me = myUid

        val meB = ByteArray(8) { i -> (me ushr ((7 - i) * 8)).toByte() }
        val rB = ByteArray(8) { i -> (room ushr ((7 - i) * 8)).toByte() }

        // Case A: me is min, peer is max. result[i] = me[i] ^ peer[7-i]
        //   → peer[j] = result[7-j] ^ me[7-j]
        val peerA = ByteArray(8) { j -> (rB[7 - j].toInt() xor meB[7 - j].toInt()).toByte() }
            .toUnsignedLongBE()

        // Case B: me is max, peer is min. result[i] = peer[i] ^ me[7-i]
        //   → peer[i] = result[i] ^ me[7-i]
        val peerB = ByteArray(8) { i -> (rB[i].toInt() xor meB[7 - i].toInt()).toByte() }
            .toUnsignedLongBE()

        // Disambiguate + round-trip check. Compare unsigned because high-bit
        // uids exist in principle.
        val peer = when {
            java.lang.Long.compareUnsigned(peerA, me) > 0 &&
                reverseXOR(me, peerA) == room -> peerA
            peerB != 0L && java.lang.Long.compareUnsigned(peerB, me) < 0 &&
                reverseXOR(me, peerB) == room -> peerB
            else -> return null
        }
        if (peer == 0L || peer == me) return null
        return java.lang.Long.toUnsignedString(peer)
    }

    private fun ByteArray.toUnsignedLongBE(): Long {
        var out = 0L
        for (i in 0 until 8) out = (out shl 8) or (this[i].toLong() and 0xFF)
        return out
    }
}
