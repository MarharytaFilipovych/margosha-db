package com.margosha.ring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.margosha.ring.RingTestConstants.YCSB_KEY;

import com.margosha.api.model.PrimaryKey;
import org.junit.jupiter.api.Test;

class RoutingKeysTest {

    private static final String CUSTOMER_ID = "42";
    private static final String ORDER_ID = "order-1";
    private static final String OTHER_ORDER_ID = "order-2";

    private static final PrimaryKey ORDER_KEY = new PrimaryKey(CUSTOMER_ID, ORDER_ID);
    private static final PrimaryKey OTHER_ORDER_KEY = new PrimaryKey(CUSTOMER_ID, OTHER_ORDER_ID);
    private static final PrimaryKey CUSTOMER_KEY_WITHOUT_SORT_KEY = PrimaryKey.of(CUSTOMER_ID);

    @Test
    void samePrimaryKeyAlwaysMapsToSameRoutingKey() {
        // Arrange
        String first = RoutingKeys.of(ORDER_KEY);

        // Act & Assert
        for (int i = 0; i < 100; i++) {
            assertThat(RoutingKeys.of(new PrimaryKey(CUSTOMER_ID, ORDER_ID))).isEqualTo(first);
        }
    }

    @Test
    void routingKeyIsThePartitionKey() {
        // Act
        String routingKey = RoutingKeys.of(ORDER_KEY);

        // Assert
        assertThat(routingKey).isEqualTo(CUSTOMER_ID);
    }

    @Test
    void sortKeyDoesNotChangeTheRoutingKey() {
        // Act
        String withOrder = RoutingKeys.of(ORDER_KEY);
        String withOtherOrder = RoutingKeys.of(OTHER_ORDER_KEY);
        String withoutSortKey = RoutingKeys.of(CUSTOMER_KEY_WITHOUT_SORT_KEY);

        // Assert
        assertThat(withOrder).isEqualTo(withOtherOrder).isEqualTo(withoutSortKey);
    }

    @Test
    void ycsbKeyIsUsedAsIs() {
        // Act
        String routingKey = RoutingKeys.of(PrimaryKey.of(YCSB_KEY));

        // Assert
        assertThat(routingKey).isEqualTo(YCSB_KEY);
    }

    @Test
    void partitionKeyIsRequired() {
        // Act & Assert
        assertThatThrownBy(() -> RoutingKeys.of(new PrimaryKey(null, ORDER_ID)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RoutingKeys.of(new PrimaryKey(" ", ORDER_ID)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void primaryKeyIsRequired() {
        // Act & Assert
        assertThatThrownBy(() -> RoutingKeys.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
