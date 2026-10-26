package com.margosha.ring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.margosha.ring.RingTestConstants.ADR_0005_VIRTUAL_NODES;
import static com.margosha.ring.RingTestConstants.SHARD_A;
import static com.margosha.ring.RingTestConstants.SHARD_B;
import static com.margosha.ring.RingTestConstants.SHARD_C;
import static com.margosha.ring.RingTestConstants.YCSB_KEY;

import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class HashRingTest {

    private static final int VIRTUAL_NODES = ADR_0005_VIRTUAL_NODES;
    private static final int SAMPLE_KEY_COUNT = 1_000;

    private static final long SHARD_A_POSITION = 100L;
    private static final long SHARD_B_POSITION = 200L;
    private static final String KEY_BEFORE_A = "key-at-50";
    private static final String KEY_ON_A = "key-at-100";
    private static final String KEY_BETWEEN_A_AND_B = "key-at-150";
    private static final String KEY_AFTER_B = "key-at-250";

    private static HashRing murmurRing(String... shardIds) {
        HashRing ring = new HashRing(new Murmur3HashFunction(), VIRTUAL_NODES);
        for (String shardId : shardIds) {
            ring.addShard(shardId);
        }
        return ring;
    }

    private static HashFunction fixed(Map<String, Long> positions) {
        return value -> {
            Long position = positions.get(value);
            if (position == null) {
                throw new IllegalArgumentException("No position for " + value);
            }
            return position;
        };
    }

    private static String sampleKey(int i) {
        return "user" + i;
    }

    @Test
    void emptyRingHasNoOwner() {
        // Arrange
        HashRing ring = murmurRing();

        // Act & Assert
        assertThat(ring.shardFor(YCSB_KEY)).isEmpty();
        assertThat(ring.shards()).isEmpty();
    }

    @Test
    void singleShardOwnsEveryKey() {
        // Arrange
        HashRing ring = murmurRing(SHARD_A);

        // Act & Assert
        for (int i = 0; i < SAMPLE_KEY_COUNT; i++) {
            assertThat(ring.shardFor(sampleKey(i))).contains(SHARD_A);
        }
    }

    @Test
    void keyBelongsToFirstPositionClockwise() {
        // Arrange
        HashRing ring = new HashRing(fixed(Map.of(
                SHARD_A + "#0", SHARD_A_POSITION,
                SHARD_B + "#0", SHARD_B_POSITION,
                KEY_BEFORE_A, 50L,
                KEY_ON_A, SHARD_A_POSITION,
                KEY_BETWEEN_A_AND_B, 150L,
                KEY_AFTER_B, 250L)), 1);
        ring.addShard(SHARD_A);
        ring.addShard(SHARD_B);

        // Act & Assert
        assertThat(ring.shardFor(KEY_BEFORE_A)).contains(SHARD_A);
        // A key exactly on a position goes to the next one.
        assertThat(ring.shardFor(KEY_ON_A)).contains(SHARD_B);
        assertThat(ring.shardFor(KEY_BETWEEN_A_AND_B)).contains(SHARD_B);
        // Past the last position the ring wraps around to the first one.
        assertThat(ring.shardFor(KEY_AFTER_B)).contains(SHARD_A);
    }

    @Test
    void positionsComeFromShardIdAndVirtualNodeIndex() {
        // Arrange
        // fixed() throws for any other input, so this also checks that exactly shard-a#0..#2 are hashed
        HashRing ring = new HashRing(fixed(Map.of(
                SHARD_A + "#0", 0L,
                SHARD_A + "#1", 100L,
                SHARD_A + "#2", 200L)), 3);

        // Act
        ring.addShard(SHARD_A);

        // Assert
        assertThat(ring.size()).isEqualTo(3);
    }

    @Test
    void addShardAddsVirtualNodesForEachShard() {
        // Arrange
        HashRing ring = murmurRing();

        // Act
        boolean addedA = ring.addShard(SHARD_A);
        boolean addedB = ring.addShard(SHARD_B);

        // Assert
        assertThat(addedA).isTrue();
        assertThat(addedB).isTrue();
        assertThat(ring.shards()).containsExactly(SHARD_A, SHARD_B);
        assertThat(ring.size()).isEqualTo(2 * VIRTUAL_NODES);
    }

    @Test
    void addingSameShardTwiceChangesNothing() {
        // Arrange
        HashRing ring = murmurRing(SHARD_A, SHARD_B);
        String owner = ring.shardFor(YCSB_KEY).orElseThrow();

        // Act
        boolean added = ring.addShard(SHARD_A);

        // Assert
        assertThat(added).isFalse();
        assertThat(ring.size()).isEqualTo(2 * VIRTUAL_NODES);
        assertThat(ring.shardFor(YCSB_KEY)).contains(owner);
    }

    @Test
    void addedShardTakesOverSomeKeys() {
        // Arrange
        HashRing ring = murmurRing(SHARD_A, SHARD_B);

        // Act
        ring.addShard(SHARD_C);

        // Assert
        long ownedByC = IntStream.range(0, SAMPLE_KEY_COUNT)
                .mapToObj(i -> ring.shardFor(sampleKey(i)).orElseThrow())
                .filter(SHARD_C::equals)
                .count();
        assertThat(ownedByC).isPositive();
    }

    @Test
    void sameKeyGoesToSameShardOnEveryRingBuiltTheSameWay() {
        // Arrange
        HashRing ring = murmurRing(SHARD_A, SHARD_B);
        HashRing rebuilt = murmurRing(SHARD_A, SHARD_B);

        // Act & Assert
        for (int i = 0; i < SAMPLE_KEY_COUNT; i++) {
            assertThat(rebuilt.shardFor(sampleKey(i))).isEqualTo(ring.shardFor(sampleKey(i)));
        }
    }

    @Test
    void ownersArePinned() {
        // Arrange
        HashRing ring = murmurRing(SHARD_A, SHARD_B);

        // Act & Assert
        assertThat(ring.shardFor(YCSB_KEY)).contains(SHARD_A);
        assertThat(ring.shardFor(sampleKey(0))).contains(SHARD_B);
        assertThat(ring.shardFor(sampleKey(1))).contains(SHARD_B);
        assertThat(ring.shardFor(sampleKey(3))).contains(SHARD_A);
    }

    @Test
    void ownershipDoesNotDependOnAddOrder() {
        // Arrange
        HashRing ab = murmurRing(SHARD_A, SHARD_B);
        HashRing ba = murmurRing(SHARD_B, SHARD_A);

        // Act & Assert
        for (int i = 0; i < SAMPLE_KEY_COUNT; i++) {
            assertThat(ba.shardFor(sampleKey(i))).isEqualTo(ab.shardFor(sampleKey(i)));
        }
    }

    @Test
    void rejectsInvalidArguments() {
        // Act & Assert
        assertThatThrownBy(() -> new HashRing(new Murmur3HashFunction(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> murmurRing().addShard(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routingKeyIsRequired() {
        // Arrange
        HashRing ring = murmurRing(SHARD_A);

        // Act & Assert
        assertThatThrownBy(() -> ring.shardFor(null))
                .isInstanceOf(NullPointerException.class);
    }
}
