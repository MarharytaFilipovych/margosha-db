package com.margosha.ring;

import static org.assertj.core.api.Assertions.assertThat;
import static com.margosha.ring.RingTestConstants.SHARD_A;
import static com.margosha.ring.RingTestConstants.SHARD_B;
import static com.margosha.ring.RingTestConstants.SHARD_C;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ShardRoutingPropertiesTest {

    private static final int KEY_COUNT = 100_000;

    private static List<String> keys;

    @BeforeAll
    static void createKeys() {
        keys = IntStream.range(0, KEY_COUNT).mapToObj(ShardRoutingPropertiesTest::ycsbKey).toList();
    }

    /** YCSB's default key (insertorder=hashed): "user" + |FNV-1a 64 of the record number|, as Utils.fnvhash64. */
    private static String ycsbKey(long recordNumber) {
        long hash = 0xCBF29CE484222325L;
        long value = recordNumber;
        for (int i = 0; i < 8; i++) {
            hash ^= value & 0xFF;
            value >>>= 8;
            hash *= 1099511628211L;
        }
        return "user" + Math.abs(hash);
    }

    private static HashRing ring() {
        HashRing ring = RingSettings.load().newRing();
        for (String shardId : List.of(SHARD_A, SHARD_B)) {
            ring.addShard(shardId);
        }
        return ring;
    }

    private static List<String> owners(HashRing ring) {
        return keys.stream().map(key -> ring.shardFor(key).orElseThrow()).toList();
    }

    @Test
    void keysAreSpreadAcrossTwoShards() {
        // Arrange
        HashRing ring = ring();

        // Act
        List<String> owners = owners(ring);

        // Assert
        for (String shard : List.of(SHARD_A, SHARD_B)) {
            double share = owners.stream().filter(shard::equals).count() / (double) KEY_COUNT;
            assertThat(share).as("share of %s", shard).isBetween(0.40, 0.60);
        }
    }

    @Test
    void addingThirdShardMovesOnlyKeysToTheNewShard() {
        // Arrange
        HashRing ring = ring();
        List<String> before = owners(ring);

        // Act
        ring.addShard(SHARD_C);
        List<String> after = owners(ring);

        // Assert
        int moved = 0;
        for (int i = 0; i < KEY_COUNT; i++) {
            if (!before.get(i).equals(after.get(i))) {
                assertThat(after.get(i)).as("new owner of %s", keys.get(i)).isEqualTo(SHARD_C);
                moved++;
            }
        }
        assertThat(moved / (double) KEY_COUNT).as("share of keys moved").isPositive().isLessThanOrEqualTo(0.45);
    }
}
