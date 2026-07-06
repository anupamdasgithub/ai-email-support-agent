package io.camunda.example.loan.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the loan application data collected from the Camunda Form
 * and passed as process variables.
 *
 * All fields map directly to form field keys in the BPMN start-event form.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoanApplication {

    /** Unique customer identifier (e.g. CIF number or UUID). */
    private String customerId;

    /** Full legal name of the applicant. */
    private String applicantName;

    /** Email address for sending the decision. */
    private String applicantEmail;

    /** Requested loan amount in local currency (integer, no decimals). */
    private Long loanAmount;

    /** Purpose of the loan: home, education, business, vehicle, personal. */
    private String loanPurpose;

    /** Applicant's annual gross income. */
    private Long annualIncome;

    /** Employment status: employed | self-employed | unemployed. */
    private String employmentStatus;

    /** Credit score 300–900 (may be absent if fetched externally). */
    private Integer creditScore;

    /** Requested tenure in months. */
    private Integer tenureMonths;
}
