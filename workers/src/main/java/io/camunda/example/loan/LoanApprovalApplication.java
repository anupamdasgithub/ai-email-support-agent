package io.camunda.example.loan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Bank AI Loan Approval worker application.
 *
 * <p>This Spring Boot app registers job workers that handle the custom service tasks
 * in the Camunda BPMN process. The AI Agent steps (Claude Sonnet 4.6) are handled
 * entirely by the Camunda Connectors runtime — no code needed for those.
 *
 * <p>Workers implemented here:
 * <ul>
 *   <li>{@code fetch-past-conversations} — loads customer history from a store</li>
 *   <li>{@code save-customer-interaction} — persists the AI conversation to long-term memory</li>
 *   <li>{@code query-knowledge-base} — looks up lending policies (stub, replace with real KB)</li>
 *   <li>{@code save-to-knowledge-base} — saves Q&A pairs back to the KB</li>
 * </ul>
 */
@SpringBootApplication
public class LoanApprovalApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoanApprovalApplication.class, args);
    }
}
