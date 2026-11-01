package com.margosha.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ShardRegistration(
        @NotBlank(message = "Shard ID must not be empty")
        String shardId,
        
        @NotNull(message = "Shard address must not be empty")
        @Pattern(regexp = "https?://[^\\s/?#:@]+(:\\d+)?(/\\S*)?",
                message = "Shard address must be an http(s) URL with a host")
        String address) {
}
