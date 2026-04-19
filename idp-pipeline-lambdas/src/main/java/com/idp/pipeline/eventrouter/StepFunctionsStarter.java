package com.idp.pipeline.eventrouter;

import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

import java.util.logging.Logger;

/**
 * Starts an AWS Step Functions execution with the given JSON payload.
 */
public class StepFunctionsStarter {

    private static final Logger log = Logger.getLogger(StepFunctionsStarter.class.getName());

    private final SfnClient sfnClient;
    private final String stateMachineArn;

    public StepFunctionsStarter(SfnClient sfnClient, String stateMachineArn) {
        this.sfnClient = sfnClient;
        this.stateMachineArn = stateMachineArn;
    }

    /**
     * Starts a Step Functions execution.
     *
     * @param documentId  used as the execution name for traceability (must be unique per execution)
     * @param inputJson   JSON string to pass as the execution input
     * @return the ARN of the started execution
     * @throws software.amazon.awssdk.services.sfn.model.SfnException on AWS errors
     */
    public String startExecution(String documentId, String inputJson) {
        StartExecutionRequest request = StartExecutionRequest.builder()
                .stateMachineArn(stateMachineArn)
                .name("exec-" + documentId)
                .input(inputJson)
                .build();

        StartExecutionResponse response = sfnClient.startExecution(request);
        String executionArn = response.executionArn();
        log.info("Started Step Functions execution: " + executionArn);
        return executionArn;
    }
}
