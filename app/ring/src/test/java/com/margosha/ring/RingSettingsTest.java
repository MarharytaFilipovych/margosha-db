package com.margosha.ring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.margosha.ring.RingTestConstants.ADR_0005_HASH_FUNCTION;
import static com.margosha.ring.RingTestConstants.ADR_0005_VIRTUAL_NODES;
import static com.margosha.ring.RingTestConstants.SHARD_A;

import java.util.Properties;

import org.junit.jupiter.api.Test;

class RingSettingsTest {

    private static Properties properties(String hashFunction, String virtualNodes) {
        Properties properties = new Properties();
        if (hashFunction != null) {
            properties.setProperty("ring.hash-function", hashFunction);
        }
        if (virtualNodes != null) {
            properties.setProperty("ring.virtual-nodes", virtualNodes);
        }
        return properties;
    }

    @Test
    void ringPropertiesHoldTheAdr0005Setting() {
        // Act
        RingSettings settings = RingSettings.load();

        // Assert
        assertThat(settings.hashFunction()).isEqualTo(ADR_0005_HASH_FUNCTION);
        assertThat(settings.virtualNodes()).isEqualTo(ADR_0005_VIRTUAL_NODES);
    }

    @Test
    void settingsAreReadFromProperties() {
        // Arrange
        Properties properties = properties(ADR_0005_HASH_FUNCTION, " 64 ");

        // Act
        RingSettings settings = RingSettings.fromProperties(properties);

        // Assert
        assertThat(settings.hashFunction()).isEqualTo(ADR_0005_HASH_FUNCTION);
        assertThat(settings.virtualNodes()).isEqualTo(64);
    }

    @Test
    void missingOrInvalidPropertiesAreRejected() {
        // Act & Assert
        assertThatThrownBy(() -> RingSettings.fromProperties(properties(null, "128")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ring.hash-function");
        assertThatThrownBy(() -> RingSettings.fromProperties(properties(ADR_0005_HASH_FUNCTION, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ring.virtual-nodes");
        assertThatThrownBy(() -> RingSettings.fromProperties(properties(ADR_0005_HASH_FUNCTION, "many")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("many");
        assertThatThrownBy(() -> RingSettings.fromProperties(properties(ADR_0005_HASH_FUNCTION, "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    void virtualNodeCountIsConfigurable() {
        // Arrange
        HashRing ring = RingSettings.fromProperties(properties(ADR_0005_HASH_FUNCTION, "16")).newRing();

        // Act
        ring.addShard(SHARD_A);

        // Assert
        assertThat(ring.virtualNodes()).isEqualTo(16);
        assertThat(ring.size()).isEqualTo(16);
    }

    @Test
    void unknownHashFunctionIsRejected() {
        // Arrange
        RingSettings settings = RingSettings.fromProperties(properties("crc32", "128"));

        // Act & Assert
        assertThatThrownBy(settings::newRing)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crc32");
    }

    @Test
    void murmur3IsX64128BitFirst64Bits() {
        // Arrange
        HashFunction murmur = new Murmur3HashFunction();

        // Act & Assert
        assertThat(murmur.hash("The quick brown fox jumps over the lazy dog")).isEqualTo(0xe34bbc7bbc071b6cL);
        assertThat(murmur.hash("")).isZero();
    }
}
