package com.margosha.node.coordinator.agent;

import java.util.concurrent.CompletionException;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

@Slf4j
@RequiredArgsConstructor
public class AgentKeyReader {

	@NonNull
	private final SsmAsyncClient ssm;

	public @Nullable String read(@NonNull String parameter) {
		GetParameterRequest request = GetParameterRequest.builder().name(parameter).withDecryption(true).build();
		try {
			return ssm.getParameter(request).join().parameter().value();
		} catch (RuntimeException e) {
			Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
			log.warn("Could not read the agent key from SSM parameter {}, so every agent request is refused: {}",
					parameter, cause.getMessage());
			return null;
		}
	}
}
