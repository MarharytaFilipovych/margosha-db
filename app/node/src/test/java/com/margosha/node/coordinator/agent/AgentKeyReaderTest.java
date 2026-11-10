package com.margosha.node.coordinator.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

class AgentKeyReaderTest {

	private static final String PARAMETER = "/margosha/agent-key";

	private final SsmAsyncClient ssm = mock(SsmAsyncClient.class);
	private final AgentKeyReader reader = new AgentKeyReader(ssm);

	@Test
	void theKeyIsTheDecryptedValueOfTheParameter() {
		// Arrange
		GetParameterResponse response = GetParameterResponse.builder()
				.parameter(Parameter.builder().name(PARAMETER).value("key-from-ssm").build())
				.build();
		when(ssm.getParameter(any(GetParameterRequest.class))).thenReturn(CompletableFuture.completedFuture(response));

		// Act
		String key = reader.read(PARAMETER);

		// Assert
		assertThat(key).isEqualTo("key-from-ssm");
		verify(ssm).getParameter(GetParameterRequest.builder().name(PARAMETER).withDecryption(true).build());
	}

	@Test
	void aParameterThatCannotBeReadGivesNoKey() {
		// Arrange
		when(ssm.getParameter(any(GetParameterRequest.class))).thenReturn(CompletableFuture.failedFuture(
				ParameterNotFoundException.builder().message("Parameter not found").build()));

		// Act
		String key = reader.read(PARAMETER);

		// Assert
		assertThat(key).isNull();
	}
}
