# AI Bank Loan Approval — Complete Setup Guide
# Local Docker Compose · Camunda 8 Self-Managed · Keycloak · Web Modeler on :8070

---

## Port Map (your running environment)

| Service           | URL                                      |
|-------------------|------------------------------------------|
| Web Modeler       | http://localhost:8070                    |
| Zeebe REST        | http://localhost:8080                    |
| Operate           | http://localhost:8081                    |
| Tasklist          | http://localhost:8082                    |
| Keycloak Admin    | http://localhost:18080/auth              |
| Zeebe gRPC        | localhost:26500                          |
| Connectors        | http://localhost:8086 (internal)         |
| Identity          | http://localhost:8084                    |

Default login for all UIs: `demo / demo`
Keycloak admin: `admin / admin`

---

## STEP 1 — Get Your Anthropic API Key

1. Open https://console.anthropic.com/settings/keys
2. Click **Create Key** → name it `camunda-loan-approval`
3. Copy the key: `sk-ant-api03-XXXX...`
4. Keep this — you will add it in Step 2.

---

## STEP 2 — Add the Anthropic Secret to the Connectors Runtime

The Connectors container reads secrets from `connector-secrets.txt` in your Docker Compose root.

**Find your Docker Compose directory** (where your `docker-compose.yaml` lives) and edit `connector-secrets.txt`:

```bash
# In your docker-compose project folder:
echo "ANTHROPIC_API_KEY=sk-ant-api03-YOUR_KEY_HERE" >> connector-secrets.txt
```

Then restart the connectors container to pick up the new secret:

```bash
docker compose restart connectors
```

Verify it loaded:
```bash
docker compose logs connectors | grep "ANTHROPIC"
# Should see: Loaded secret ANTHROPIC_API_KEY
```

---

## STEP 3 — Create a Client App in Camunda Identity

Your Spring Boot worker needs a client ID + secret to authenticate with Zeebe via Keycloak.

### 3a. Open Identity

Go to: http://localhost:8084
Log in with `demo / demo`

### 3b. Create a new Application

1. Click **Applications** in the left sidebar
2. Click **+ Add application** (top right)
3. Fill in:
   - **Name**: `bank-loan-worker`
   - **Type**: `M2M` (Machine-to-Machine)
4. Click **Add**
5. You will see the app created. Click on it.
6. Go to the **Credentials** tab
7. Copy **Client ID**: `bank-loan-worker`
8. Click **Generate** next to Client Secret — copy the generated secret immediately (shown only once)

### 3c. Assign permissions

Still on the application page:
1. Click the **Access to APIs** tab
2. Click **Assign permissions**
3. Enable all permissions for:
   - `Zeebe API` → check **write:***, **read:***, **create:***
   - `Operate API` → check **read:***, **write:***
   - `Tasklist API` → check **read:***, **write:***
4. Click **Save**

### 3d. Update your .env file

Edit `/home/claude/bank-ai-loan-approval/.env`:
```
ZEEBE_CLIENT_ID=bank-loan-worker
ZEEBE_CLIENT_SECRET=<paste the secret from step 3b>
```

---

## STEP 4 — Deploy the BPMN, DMN, and Forms via Web Modeler

Your Web Modeler is running at http://localhost:8070 and you already have the AI Email Support Agent diagram open. Now we set up the Loan Approval process.

### 4a. Create a new Process Application

1. Open http://localhost:8070
2. Click **Home** (top left breadcrumb)
3. Click your project (e.g. "Camunda Sample Project")
4. Click **+ New** → **Create new** → **Process Application**
5. Name it: `AI Bank Loan Approval`
6. Click **Create**

### 4b. Import the DMN file

1. Inside the new process application, click **+ New** → **Upload files**
2. Upload: `src/main/resources/dmn/loan-decision.dmn`
3. It will appear as `loan-decision` in your project

### 4c. Import the Forms

1. Click **+ New** → **Upload files**
2. Upload both:
   - `src/main/resources/forms/loan-application.form`
   - `src/main/resources/forms/loan-specialist-review.form`

### 4d. Create the BPMN Process

1. Click **+ New** → **BPMN diagram**
2. Name it: `Bank AI Loan Approval`
3. Set Process ID: `bank-ai-loan-approval`
4. **IMPORTANT**: At the bottom of the Modeler screen, confirm the execution engine is set to `Camunda Cloud 8.8` or `8.9`

Now build the BPMN following the structure in Section 5 below.

---

## STEP 5 — BPMN Modelling: Element by Element

### 5.1 Start Event

1. Click the start event circle
2. In the right panel → **Form** tab
3. Select **Camunda Form** → choose `loan-application` (your uploaded form)
4. Set **Form key**: `loan-application-form`

### 5.2 Service Task: Fetch Past Conversations

1. Add a service task after the start event
2. Label: `Fetch past conversations from customer`
3. In the right panel → **Template** tab → **None** (plain service task)
4. Go to **Task definition** tab:
   - **Job type**: `fetch-past-conversations`
   - **Retries**: `3`
5. Output mapping: (automatic — worker returns Map which SDK auto-publishes)

### 5.3 Exclusive Gateway: Has Past Conversations?

1. Add a gateway diamond
2. Label: `Has past conversations?`
3. Two outgoing paths:
   - **Yes** (condition: `= hasPastConversations = true`) → leads to sub-process
   - **No** (default, also leads to sub-process)
   Both paths merge into the same sub-process.

### 5.4 Business Rule Task: DMN Pre-Check

1. Add a **Business Rule Task** (task with gear icon)
2. Label: `Evaluate application rules`
3. In right panel → **Implementation**:
   - **Decision ID**: `loanAutoDecision`
   - **Binding**: `deployment`
   - **Result variable**: `dmnResult`
   - **Result type**: `Single result`

### 5.5 AI Agent Ad-Hoc Sub-Process (THE KEY STEP)

1. Draw an expanded sub-process rectangle
2. Right-click it → **Change element type** → **Ad-hoc Sub-process**
3. Label the container: `Handle customer request`
4. In the right panel → **Template** → click the search icon → search for `AI Agent`
5. Select **AI Agent Sub-process**

Now configure the AI Agent properties:

**Model Provider section:**
```
Provider:   Anthropic
API Key:    secrets.ANTHROPIC_API_KEY
Model:      claude-sonnet-4-6
```

**System Prompt:**
```
You are an expert bank loan assessment agent for a regulated financial institution.

Your role:
1. Evaluate loan applications objectively and fairly.
2. Use the Query Knowledge Base tool to look up applicable lending policies.
3. Ask the Loan Specialist tool ONLY for edge cases requiring human judgment.
4. Save important findings to the Knowledge Base for future reference.
5. Always be transparent about your reasoning.

Current applicant data:
- Customer ID: {{customerId}}
- Name: {{applicantName}}
- Loan Amount Requested: ₹{{loanAmount}}
- Annual Income: ₹{{annualIncome}}
- Employment Status: {{employmentStatus}}
- Credit Score: {{creditScore}}
- Loan Purpose: {{loanPurpose}}
- Requested Tenure: {{tenureMonths}} months
- DMN Pre-check Result: {{dmnRecommendation}} ({{dmnReason}})
- Past Interactions: {{pastConversations}}

Instructions:
- First query the knowledge base for relevant lending policies.
- Cross-check the DMN pre-check result with policy details.
- If dmnRecommendation is "reject" and policy confirms it, recommend rejection directly.
- If dmnRecommendation is "approve" and criteria are clearly met, recommend approval.
- If there is any ambiguity, low confidence, or unusual circumstances, ask the loan specialist.
- Output MUST be valid JSON in this exact format:
  {
    "recommendation": "approve" | "review" | "reject",
    "confidence": <0-100>,
    "reasoning": "<concise explanation>",
    "conditions": "<any conditions or requirements, or null>",
    "riskFactors": "<identified risks, or null>"
  }
```

**User Prompt:**
```
= "Please assess this loan application and provide your recommendation."
```

**Memory:**
```
Type:           in-process
Max messages:   20
```

**Limits:**
```
Max model calls: 10
```

**Response section:**
```
Format:                    Text (with Parse as JSON checked)
Result variable:           agentAssessment
Include assistant message: ✓  (variable: agentResponse)
Include agent context:     ✓  (variable: agentContext)
```

### 5.6 Tools Inside the Ad-Hoc Sub-Process

Inside the ad-hoc sub-process box, add these three elements:

**Tool 1: Query Knowledge Base**
- Service Task
- Label: `Query knowledge base`
- Job type: `query-knowledge-base`
- Mark as tool: In template properties, enable **"Ad-hoc tool"** toggle
- Tool description (for AI): `"Query the bank lending policy knowledge base. Use this to look up rules about credit scores, income requirements, loan limits, and employment criteria. Input: { query: string }"`

**Tool 2: Ask Loan Specialist**
- User Task
- Label: `Ask loan specialist`
- Form: select `loan-specialist-review` form
- Assignee: `demo` (or configure a real group)
- Mark as tool: enable **"Ad-hoc tool"** toggle
- Tool description: `"Escalate to a human loan specialist for review and decision. Use this when the case is ambiguous or requires human judgment. The specialist will review and provide a decision."`

**Tool 3: Save Answer to Knowledge Base**
- Service Task
- Label: `Save answer in knowledge base`
- Job type: `save-to-knowledge-base`
- Mark as tool: enable **"Ad-hoc tool"** toggle
- Tool description: `"Save a policy Q&A pair to the knowledge base for future reference. Input: { kbQuestion: string, kbAnswer: string }"`

### 5.7 Service Task: Save Interaction to Long-Term Memory

After the sub-process ends, add:
- Service Task: `Save customer interaction in long term memory`
- Job type: `save-customer-interaction`
- Retries: 3

### 5.8 Agent as a Judge Gateway

Add an exclusive gateway: `Agent decision outcome?`

Three outgoing sequence flows with FEEL conditions:

**Path 1: solved with confidence**
```
= agentAssessment.recommendation = "approve" and agentAssessment.confidence >= 75
```
→ leads to "Review case resolution" user task

**Path 2: needs review**
```
= agentAssessment.recommendation = "review" or (agentAssessment.recommendation = "approve" and agentAssessment.confidence < 75)
```
→ leads to "Finalize case manually" user task

**Path 3: needs human resolution** (default)
```
= agentAssessment.recommendation = "reject"
```
→ leads to "Human control required" event

### 5.9 User Task: Review Case Resolution

- Label: `Review case resolution`
- Assignee: `demo`
- (Optional) Attach a review form showing the AI assessment

### 5.10 Service Task: Final Answer to Customer

- Label: `Final answer to customer and close support`
- Use the **Camunda Email Connector** template:
  - From: `loans@yourbank.com`
  - To: `= applicantEmail`
  - Subject: `= "Loan Application Update for " + applicantName`
  - Body:
    ```
    Dear {{applicantName}},

    We have reviewed your loan application for ₹{{loanAmount}}.

    Decision: {{agentAssessment.recommendation}}
    {{agentAssessment.reasoning}}

    {{#if agentAssessment.conditions}}
    Conditions: {{agentAssessment.conditions}}
    {{/if}}

    Thank you for banking with us.
    ```

### 5.11 End Events

- `Case solved` — for approved/reviewed path
- `Human control triggered` — for rejected/escalated path
- `Case resolved by human` — for the human-controlled subprocess

---

## STEP 6 — Deploy from Web Modeler

1. In Web Modeler, open your `Bank AI Loan Approval` process
2. Click **Deploy & run** (blue button, top right)
3. A dialog appears:
   - **Cluster**: select your local self-managed cluster (auto-detected since you're running locally)
   - **Stage**: Development
4. Click **Deploy**
5. ✅ You should see "Successfully deployed"

If you see errors:
- Check the **Problems** panel at the bottom (14 warnings shown = normal, mostly connector secret refs)
- Errors (red) about missing form IDs mean the forms weren't uploaded — go back to step 4b/4c

---

## STEP 7 — Run the Spring Boot Worker

```bash
cd /path/to/bank-ai-loan-approval

# Load environment variables
export $(cat .env | grep -v '#' | xargs)

# Build
mvn clean package -DskipTests

# Run
java -jar target/bank-ai-loan-approval-1.0.0-SNAPSHOT.jar
```

Expected startup output:
```
INFO  LoanApprovalApplication     : Started LoanApprovalApplication
INFO  JobWorkerManager            : Registering job worker 'fetch-past-conversations'
INFO  JobWorkerManager            : Registering job worker 'save-customer-interaction'
INFO  JobWorkerManager            : Registering job worker 'query-knowledge-base'
INFO  JobWorkerManager            : Registering job worker 'save-to-knowledge-base'
INFO  ZeebeClientAutoConfiguration: Connected to Zeebe at localhost:26500
```

---

## STEP 8 — Test the Process End-to-End

### 8a. Start a process instance

1. Open Tasklist: http://localhost:8082
2. Log in: `demo / demo`
3. Click **Start process** → search for `AI Bank Loan Approval`
4. Fill in the form:
   ```
   Customer ID:        CUST-001
   Full Name:          Rahul Sharma
   Email:              rahul@example.com
   Loan Amount (INR):  1500000
   Loan Purpose:       home
   Annual Income:      800000
   Employment Status:  employed
   Credit Score:       720
   Tenure (months):    240
   ```
5. Click **Start**

### 8b. Watch it run in Operate

1. Open Operate: http://localhost:8081
2. Log in: `demo / demo`
3. Go to **Processes** → **AI Bank Loan Approval**
4. Click the running instance
5. Watch the token (blue dot) move:
   - `fetch-past-conversations` → your worker handles it
   - `Evaluate application rules` → DMN runs → sets `dmnRecommendation = "approve"`
   - AI Agent sub-process starts → Claude Sonnet 4.6 is called
   - Claude queries the knowledge base (your worker runs)
   - Claude produces `agentAssessment` JSON
   - Gateway routes based on recommendation + confidence
   - `Final answer` task runs

### 8c. Complete human tasks

If the process reaches a user task (e.g. "Ask loan specialist"):
1. Go to Tasklist
2. You'll see a task waiting
3. Click it, fill in the specialist form, click **Complete**
4. The AI Agent continues with the specialist's input

---

## STEP 9 — Verify the AI Agent is Using Claude

In Operate, click your running/completed instance → **Variables** tab.

You should see:
```json
agentAssessment: {
  "recommendation": "approve",
  "confidence": 85,
  "reasoning": "Applicant has a credit score of 720 which meets the 700+ threshold...",
  "conditions": "Maintain good credit standing for loan tenure",
  "riskFactors": null
}
agentResponse: "Based on my assessment of this loan application..."
agentContext: [{"role":"user","content":"..."},{"role":"assistant","content":"..."}]
dmnRecommendation: "approve"
hasPastConversations: false
```

---

## TROUBLESHOOTING

### Worker can't connect to Zeebe
```
Check: docker ps | grep zeebe
Check port: curl http://localhost:8080/actuator/health
Check token endpoint: curl http://localhost:18080/auth/realms/camunda-platform/.well-known/openid-configuration
Verify client secret in .env matches what Identity generated
```

### AI Agent connector returns "Provider not configured"
```
1. docker compose logs connectors | grep -i anthropic
2. Confirm connector-secrets.txt has ANTHROPIC_API_KEY=sk-ant-...
3. docker compose restart connectors
4. Verify the BPMN uses: secrets.ANTHROPIC_API_KEY  (not the key directly)
```

### DMN not found on deploy
```
Make sure loan-decision.dmn was uploaded and deployed in the same process application.
The Business Rule Task must have Binding = "deployment" not "latest" for local testing.
```

### Keycloak token endpoint 401
```
Keycloak URL in .env:  http://localhost:18080/auth/realms/camunda-platform/...
                                            ↑ note port 18080, not 8080
The Identity app type must be M2M (not User)
Client must have service account roles assigned in Identity
```

### 14 Problems in Modeler
These are warnings, not errors. Common causes:
- Connector secret references (secrets.ANTHROPIC_API_KEY) show as unresolved in Modeler — this is expected, they resolve at runtime in the Connectors container
- Click the Problems panel to see specific warnings; red ones block deployment, yellow ones don't

---

## QUICK REFERENCE: All Credentials Summary

After completing setup, you should have:

| What | Where | Value |
|---|---|---|
| Anthropic API Key | console.anthropic.com | `sk-ant-...` |
| Keycloak Admin | localhost:18080/auth | admin / admin |
| Demo user login | Operate/Tasklist/Modeler | demo / demo |
| Worker client-id | Identity app | `bank-loan-worker` |
| Worker client-secret | Identity → Credentials tab | (generated) |
| connector-secrets.txt | Docker Compose root | `ANTHROPIC_API_KEY=sk-ant-...` |
