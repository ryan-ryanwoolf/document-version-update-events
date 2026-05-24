# AWS deployment (ECS Fargate + RDS + SQS)

The application **`aws`** profile uses **RDS PostgreSQL** and an **existing SQS standard queue**. ActiveMQ is not used.

## Prerequisites

1. **VPC** with private subnets for ECS tasks and RDS.
2. **RDS PostgreSQL 16** (or compatible) reachable from task security groups on port **5432**.
3. **SQS standard queue** (and optional DLQ + redrive policy) already created.
4. **ECR** repository and **ECS cluster** (Fargate).
5. **IAM task role** with SQS permissions on the queue ARN:
   - `sqs:ReceiveMessage`, `sqs:DeleteMessage`, `sqs:GetQueueAttributes`, `sqs:ChangeMessageVisibility`
   - `kms:Decrypt` if the queue uses SSE-KMS
6. **NAT gateway** or **VPC endpoints** so tasks can reach SQS (and ECR) from private subnets.

## 1. Build and push the image

From the repository root:

```bash
aws ecr get-login-password --region REGION | docker login --username AWS --password-stdin ACCOUNT_ID.dkr.ecr.REGION.amazonaws.com

docker build -t document-version-update-events .
docker tag document-version-update-events:latest ACCOUNT_ID.dkr.ecr.REGION.amazonaws.com/document-version-update-events:latest
docker push ACCOUNT_ID.dkr.ecr.REGION.amazonaws.com/document-version-update-events:latest
```

## 2. Configure RDS

- Create the database (e.g. `postgres`) and note the endpoint.
- Ensure the ECS task security group can connect to RDS on **5432**.
- Store `DB_PASSWORD` in **Secrets Manager** (recommended) or inject via task `secrets`.
- On first startup, **Flyway** creates schema `lexis-nexis-events` and runs migrations.

## 3. Configure SQS

- Use a **standard** queue URL (not FIFO).
- Set **visibility timeout** above your worst-case single-message processing time (e.g. 60–120s).
- Configure **DLQ** + **maxReceiveCount** for poison messages.

## 4. ECS task definition

Copy [`ecs-task-definition.json.example`](ecs-task-definition.json.example) and [`env.example`](env.example). Replace `ACCOUNT_ID`, `REGION`, RDS endpoint, queue URL, and IAM role ARNs.

Register the task definition:

```bash
aws ecs register-task-definition --cli-input-json file://ecs-task-definition.json
```

## 5. Create the service

- **Launch type:** Fargate  
- **Desired count:** ≥ 2 (competing SQS consumers)  
- **Load balancer:** ALB target group on container port **3030**, health check path `/actuator/health`  
- **Environment:** `SPRING_PROFILES_ACTIVE=aws` plus variables from `env.example`

## 6. Verify

```bash
curl "https://YOUR-ALB/document/search?q=test&limit=5"
```

Enqueue a spec-shaped message:

```bash
aws sqs send-message \
  --queue-url "$AWS_SQS_REVISION_EVENTS_QUEUE_URL" \
  --message-body '{"event_id":"evt-1","document_id":"doc-aws-001","sequence":1,"event_type":"CREATE","timestamp":"2026-05-19T12:00:00Z","payload":{"title":"AWS test","body":"From SQS"}}'
```

Then:

```bash
curl "https://YOUR-ALB/document/doc-aws-001"
```

## Smoke-test demo endpoints (optional)

Set `APP_DEMO_ENDPOINTS_ENABLED=true` only in non-production, then `POST /demo/revision-events/start` as in the main README.
