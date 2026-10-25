package com.margosha.api.model;

import static com.margosha.api.TestBuilder.fromJson;
import static com.margosha.api.TestBuilder.json;
import static com.margosha.api.TestBuilder.tree;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ModelSerializationTest {

    @ParameterizedTest
    @MethodSource("com.margosha.api.TestBuilder#modelsWithJson")
    void roundTripsThroughTheSpecJson(Object model, String specJson) {
        // Arrange
        Class<?> type = model.getClass();

        // Act
        String written = json(model);
        Object readBack = fromJson(written, type);
        Object readFromSpec = fromJson(specJson, type);

        // Assert
        assertThat(tree(written)).isEqualTo(tree(specJson));
        assertThat(readBack).isEqualTo(model);
        assertThat(readFromSpec).isEqualTo(model);
    }

    @ParameterizedTest
    @MethodSource("com.margosha.api.TestBuilder#recordParts")
    void recordSplitsIntoKeyAndAttributes(TableDefinition table, TableRecord record,
                                          PrimaryKey expectedKey, Attributes expectedAttributes) {
        // Act
        PrimaryKey key = record.keyIn(table);
        Attributes attributes = record.attributesIn(table);

        // Assert
        assertThat(key).isEqualTo(expectedKey);
        assertThat(attributes).isEqualTo(expectedAttributes);
    }
}
