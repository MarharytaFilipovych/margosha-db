package com.margosha.api.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import static org.springframework.util.StringUtils.hasText;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TableDefinition(
        @NotBlank(message = "Table name must not be empty")
        String name,

        @NotBlank(message = "Partition key must not be empty")
        String partitionKey,

        @Pattern(regexp = "(?s).*\\S.*", message = "Sort key must not be empty if given")
        String sortKey) {

    @JsonIgnore
    @AssertTrue(message = "Sort key must differ from the partition key")
    public boolean isSortKeyDifferentFromPartitionKey() {
        return partitionKey == null || !partitionKey.equals(sortKey);
    }

    public static TableDefinition of(String name, String partitionKey) {
        return new TableDefinition(name, partitionKey, null);
    }

    public boolean hasSortKey() {
        return hasText(sortKey);
    }

    public List<String> keyAttributes() {
        return hasSortKey() ? List.of(partitionKey, sortKey) : List.of(partitionKey);
    }
}
