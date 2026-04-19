package com.idp.ingestion.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * AWS DynamoDB client configuration.
 * Requirements: 8.1, 8.2
 */
@Configuration
public class DynamoDbConfig {

    @Value("${aws.region:eu-west-1}")
    private String awsRegion;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
