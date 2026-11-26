package com.fitpilot.agent.application;

import com.fitpilot.agent.adjustment.TrainingAdjustmentDtos.AdjustmentProposal;
import com.fitpilot.agent.adjustment.TrainingAdjustmentDtos.Evidence;
import com.fitpilot.agent.dto.AgentDtos.PendingActionView;
import com.fitpilot.agent.infrastructure.AgentRepository;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.plan.application.TrainingPlanService;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TrainingAdjustmentWorkflowIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("security.jwt.secret", () -> "integration-test-secret-key-with-32-bytes-minimum");
        registry.add("fitpilot.events.enabled", () -> "false");
        registry.add("fitpilot.rag.enabled", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TrainingPlanService plans;
    @Autowired AgentRepository agent;
    @Autowired PendingActionService pendingActions;
    @Autowired TrainingAdjustmentWorkflow workflow;
    @Autowired ConfirmationService confirmations;
    private long userId;
    private UUID sessionId;
    private TrainingPlanDtos.PlanView source;

    @BeforeEach
    void seed() {
        userId = jdbc.queryForObject("INSERT INTO users(username,password_hash) VALUES (?, 'test') RETURNING id",
                Long.class, UUID.randomUUID().toString());
        sessionId = UUID.randomUUID();
        agent.createSession(sessionId, userId, LocalDateTime.now());
        source = plans.activate(userId, plans.create(userId, plan("baseline")).id());
    }

    @Test
    void competingProposalsLeaveOnlyTheWinningActionAndAudit() throws Exception {
        var winner = proposal();
        var loser = proposal();
        UUID firstExecution = execution();
        UUID secondExecution = execution();

        assertThat(afterLocked(() -> create(firstExecution, winner), () -> create(secondExecution, loser)))
                .isInstanceOf(TrainingAdjustmentWorkflow.AlreadyPending.class);

        var actions = pendingActions.list(userId, sessionId);
        assertThat(actions).hasSize(1);
        assertPair(winner, actions.getFirst().id(), "AWAITING_CONFIRMATION", "AWAITING_CONFIRMATION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_adjustment WHERE user_id=?", Integer.class, userId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_pending_action WHERE user_id=?", Integer.class, userId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT execution_id FROM agent_tool_call WHERE execution_id IN (?,?)
                """, UUID.class, firstExecution, secondExecution)).isEqualTo(firstExecution);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void confirmAndRejectHaveOneWinner(boolean confirmFirst) throws Exception {
        var proposal = proposal();
        var action = create(execution(), proposal);
        Runnable confirm = () -> confirmations.confirm(userId, action.id(), action.confirmationToken());
        Runnable reject = () -> workflow.reject(userId, proposal.adjustmentId());

        assertError(afterLocked(confirmFirst ? confirm : reject, confirmFirst ? reject : confirm),
                ErrorCode.AGENT_ACTION_ALREADY_PROCESSED);

        assertPair(proposal, action.id(), confirmFirst ? "ACCEPTED" : "REJECTED",
                confirmFirst ? "EXECUTED" : "REJECTED");
        assertThat(draftCount()).isEqualTo(confirmFirst ? 1 : 0);
        assertThat(plans.active(userId).id()).isEqualTo(source.id());
    }

    @Test
    void changedSourceCommitsBothStaleStatesWithoutCreatingDraft() {
        var proposal = proposal();
        var action = create(execution(), proposal);
        plans.activate(userId, plans.create(userId, plan("replacement")).id());

        assertError(catchThrowable(() -> confirmations.confirm(userId, action.id(), action.confirmationToken())),
                ErrorCode.PLAN_ADJUSTMENT_STALE);
        assertPair(proposal, action.id(), "STALE", "STALE");
        assertThat(draftCount()).isZero();
    }

    @Test
    void replacingExpiredProposalRetiresBothOldRecords() {
        var expired = proposal();
        var oldAction = create(execution(), expired);
        jdbc.update("UPDATE agent_pending_action SET expires_at=now()-interval '1 minute' WHERE id=?", oldAction.id());
        var replacement = proposal();
        var newAction = create(execution(), replacement);

        assertPair(expired, oldAction.id(), "STALE", "STALE");
        assertPair(replacement, newAction.id(), "AWAITING_CONFIRMATION", "AWAITING_CONFIRMATION");
    }

    @Test
    void hidesLegacyOrphanAndRefusesTokenRotationAndConfirmation() {
        var proposal = proposal();
        var action = pendingActions.create(execution(), userId, TrainingAdjustmentWorkflow.TOOL,
                proposal, proposal.plan(), List.of());

        assertThat(pendingActions.list(userId, sessionId)).isEmpty();
        assertError(catchThrowable(() -> pendingActions.rotateToken(userId, action.id())),
                ErrorCode.AGENT_CONFIRMATION_INVALID);
        assertError(catchThrowable(() -> confirmations.confirm(userId, action.id(), action.confirmationToken())),
                ErrorCode.AGENT_ACTION_ALREADY_PROCESSED);
        assertThat(draftCount()).isZero();
    }

    private Throwable afterLocked(Runnable first, Runnable second) throws Exception {
        var secondPid = new CompletableFuture<Integer>();
        var transaction = new TransactionTemplate(transactions);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Throwable> follower = transaction.execute(ignored -> {
                first.run();
                var blocked = executor.submit(() -> catchThrowable(() -> transaction.executeWithoutResult(status -> {
                    jdbc.execute("SET LOCAL statement_timeout = '10s'");
                    secondPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    second.run();
                })));
                await().atMost(Duration.ofSeconds(5)).until(() -> secondPid.isDone()
                        && Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, secondPid.join())));
                return blocked;
            });
            return follower.get(12, TimeUnit.SECONDS);
        }
    }

    private PendingActionView create(UUID executionId, AdjustmentProposal proposal) {
        return workflow.create(executionId, userId, proposal, "RULE_WORKFLOW", false, "adaptive-v1");
    }

    private UUID execution() {
        UUID id = UUID.randomUUID();
        agent.startExecution(id, userId, sessionId, "ADJUST", List.of(TrainingAdjustmentWorkflow.TOOL),
                LocalDateTime.now());
        return id;
    }

    private AdjustmentProposal proposal() {
        var evidence = new Evidence(28, 8, .9, 70, 72, .97, 7.5, 6, 5, 1, 10000, 10000, 0, 0);
        return new AdjustmentProposal(UUID.randomUUID(), source.id(), source.version(), "DELOAD",
                evidence, List.of("fatigue"), plan("adjusted"));
    }

    private TrainingPlanDtos.CreateRequest plan(String name) {
        return new TrainingPlanDtos.CreateRequest(name, null, "STRENGTH", 8, List.of(
                new TrainingPlanDtos.DayRequest(1, "上肢", null, List.of(exercise(1L))),
                new TrainingPlanDtos.DayRequest(2, "下肢", null, List.of(exercise(3L)))));
    }

    private TrainingPlanDtos.ExerciseRequest exercise(long id) {
        return new TrainingPlanDtos.ExerciseRequest(id, 1, 3, 8, 12, BigDecimal.valueOf(7), 90, null);
    }

    private void assertPair(AdjustmentProposal proposal, UUID actionId, String proposalState, String actionState) {
        assertThat(jdbc.queryForMap("""
                SELECT a.status AS proposal_status,p.status AS action_status
                FROM plan_adjustment a JOIN agent_pending_action p ON p.id=a.pending_action_id AND p.user_id=a.user_id
                WHERE a.id=? AND a.user_id=? AND p.id=?
                """, proposal.adjustmentId(), userId, actionId))
                .containsEntry("proposal_status", proposalState).containsEntry("action_status", actionState);
    }

    private int draftCount() {
        return jdbc.queryForObject("SELECT count(*) FROM training_plan WHERE user_id=? AND status='DRAFT'",
                Integer.class, userId);
    }

    private void assertError(Throwable failure, ErrorCode code) {
        assertThat(failure).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(code));
    }
}
