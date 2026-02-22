package com.platform.subscription_manager.subscription;

import com.platform.subscription_manager.shared.domain.BillingHistoryStatus;
import com.platform.subscription_manager.shared.domain.Plan;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.shared.infrastructure.messaging.BillingResultEvent;
import com.platform.subscription_manager.shared.infrastructure.messaging.RenewalRequestedEvent;
import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.subscription.application.services.RenewalOrchestratorService;
import com.platform.subscription_manager.subscription.domain.BillingCyclePolicy;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import com.platform.subscription_manager.subscription.infrastructure.messaging.SubscriptionResultListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scenario-based tests covering every scheduler + result-listener path defined in challenge.md.
 *
 * Split into two nested classes so each @BeforeEach stub is consumed by every test in its class:
 *
 * ResultListenerScenarios (1-4, 7-8) — exercises SubscriptionResultListener.
 *   TransactionTemplate is always called (listener wraps every batch in a TX).
 *
 * OrchestratorScenarios (5-6, 9-11) — exercises RenewalOrchestratorService.
 *   TransactionTemplate is only called when a subscription is dispatched;
 *   tests with empty pages do NOT open a TX, so the stub lives only in tests that need it.
 */
@DisplayName("Scheduler full-pipeline scenarios")
class SchedulerScenarioTest {

    private static final int MAX_ATTEMPTS       = 3;
    private static final int BASE_DELAY_MINUTES = 1;
    private static final int INFLIGHT_GUARD     = 5;

    // ─────────────────────────────────────────────────────────────────────────
    // Scenarios 1-4, 7-8  — result listener
    // TransactionTemplate is ALWAYS called here (listener wraps every batch).
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Result listener scenarios")
    @ExtendWith(MockitoExtension.class)
    class ResultListenerScenarios {

        @Mock private SubscriptionRepository repository;
        @Mock private ApplicationEventPublisher eventPublisher;
        @Mock private TransactionTemplate transactionTemplate;
        private SubscriptionResultListener listener;

        @BeforeEach
        void setUp() {
            listener = new SubscriptionResultListener(repository, transactionTemplate, eventPublisher);
            ReflectionTestUtils.setField(listener, "maxAttempts",      MAX_ATTEMPTS);
            ReflectionTestUtils.setField(listener, "baseDelayMinutes", BASE_DELAY_MINUTES);
            // Listener always wraps each batch in a TX — this stub is consumed by every test.
            doAnswer(inv -> { ((java.util.function.Consumer<?>) inv.getArgument(0)).accept(null); return null; })
                .when(transactionTemplate).executeWithoutResult(any());
        }

        // 1. Happy path
        @Test
        @DisplayName("1. Happy path — success renews subscription by one month and resets counter")
        void happyPath() {
            LocalDateTime expiring = LocalDateTime.now().minusDays(1);
            Subscription sub = buildSub(expiring, 0);
            LocalDateTime expectedNext = BillingCyclePolicy.calculateNextExpiration(expiring);

            when(repository.findById(sub.getId())).thenReturn(Optional.of(sub));
            when(repository.renewSubscriptionAtomic(eq(sub.getId()), eq(expiring), any())).thenReturn(1);

            send(listener, sub.getId(), BillingHistoryStatus.SUCCESS, expiring, "txn_ok");

            verify(repository).renewSubscriptionAtomic(eq(sub.getId()), eq(expiring), eq(expectedNext));
            ArgumentCaptor<SubscriptionUpdatedEvent> captor = ArgumentCaptor.forClass(SubscriptionUpdatedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertEquals(SubscriptionStatus.ACTIVE, captor.getValue().status());
            assertEquals(expectedNext, captor.getValue().expiringDate());
        }

        // 2. First failure — must NOT suspend
        @Test
        @DisplayName("2. First failure — stays ACTIVE, counter at 1, nextRetryAt in future")
        void firstFailure_staysActive() {
            LocalDateTime expiring = LocalDateTime.now().minusDays(1);
            Subscription sub = buildSub(expiring, 0);

            when(repository.findById(sub.getId())).thenReturn(Optional.of(sub));
            when(repository.incrementFailureAtomic(eq(sub.getId()), eq(expiring), any(), any(), anyInt())).thenReturn(1);

            send(listener, sub.getId(), BillingHistoryStatus.FAILED, expiring, null);

            ArgumentCaptor<LocalDateTime> retryCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(repository).incrementFailureAtomic(eq(sub.getId()), eq(expiring), any(), retryCaptor.capture(), anyInt());
            verify(repository, never()).suspendSubscriptionAtomic(any(), any(), any(), anyInt());
            assertTrue(retryCaptor.getValue().isAfter(LocalDateTime.now()));
        }

        // 3. Second failure — backoff grows
        @Test
        @DisplayName("3. Second failure — still ACTIVE, backoff >= 4 minutes (2^2 * 1 min)")
        void secondFailure_backoffGrows() {
            LocalDateTime expiring = LocalDateTime.now().minusDays(1);
            Subscription sub = buildSub(expiring, 1); // 1 prior failure

            when(repository.findById(sub.getId())).thenReturn(Optional.of(sub));
            when(repository.incrementFailureAtomic(eq(sub.getId()), eq(expiring), any(), any(), anyInt())).thenReturn(1);

            LocalDateTime before = LocalDateTime.now();
            send(listener, sub.getId(), BillingHistoryStatus.FAILED, expiring, null);

            ArgumentCaptor<LocalDateTime> retryCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(repository).incrementFailureAtomic(eq(sub.getId()), eq(expiring), any(), retryCaptor.capture(), anyInt());
            verify(repository, never()).suspendSubscriptionAtomic(any(), any(), any(), anyInt());
            assertTrue(retryCaptor.getValue().isAfter(before.plusMinutes(3)));
        }

        // 4. Third failure — SUSPEND
        @Test
        @DisplayName("4. Third failure — SUSPENDED atomically, no increment")
        void thirdFailure_suspends() {
            LocalDateTime expiring = LocalDateTime.now().minusDays(1);
            Subscription sub = buildSub(expiring, 2); // 2 prior failures

            when(repository.findById(sub.getId())).thenReturn(Optional.of(sub));
            when(repository.suspendSubscriptionAtomic(eq(sub.getId()), eq(expiring), any(), anyInt())).thenReturn(1);

            send(listener, sub.getId(), BillingHistoryStatus.FAILED, expiring, null);

            verify(repository).suspendSubscriptionAtomic(eq(sub.getId()), eq(expiring), any(), anyInt());
            verify(repository, never()).incrementFailureAtomic(any(), any(), any(), any(), anyInt());
        }

        // 7. Stale event
        @Test
        @DisplayName("7. Stale result event (wrong referenceExpiringDate) is silently ignored")
        void staleEvent_ignored() {
            Subscription sub = buildSub(LocalDateTime.now().plusDays(25), 0); // already renewed

            when(repository.findById(sub.getId())).thenReturn(Optional.of(sub));

            send(listener, sub.getId(), BillingHistoryStatus.SUCCESS, LocalDateTime.now().minusDays(1), "late");

            verify(repository, never()).renewSubscriptionAtomic(any(), any(), any());
            verify(repository, never()).incrementFailureAtomic(any(), any(), any(), any(), anyInt());
            verify(repository, never()).suspendSubscriptionAtomic(any(), any(), any(), anyInt());
            verify(eventPublisher, never()).publishEvent(any());
        }

        // 8. Idempotent success
        @Test
        @DisplayName("8. Idempotent success — atomic update returns 0, no double-renewal or cache event")
        void idempotentSuccess_noDoubleRenewal() {
            LocalDateTime expiring = LocalDateTime.now().minusDays(1);
            Subscription sub = buildSub(expiring, 0);

            when(repository.findById(sub.getId())).thenReturn(Optional.of(sub));
            when(repository.renewSubscriptionAtomic(any(), any(), any())).thenReturn(0);

            send(listener, sub.getId(), BillingHistoryStatus.SUCCESS, expiring, "dup");

            verify(repository).renewSubscriptionAtomic(eq(sub.getId()), eq(expiring), any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        private Subscription buildSub(LocalDateTime expiringDate, int billingAttempts) {
            Subscription sub = Subscription.create(UUID.randomUUID(), Plan.PREMIUM, "tok");
            ReflectionTestUtils.setField(sub, "id",             UUID.randomUUID());
            ReflectionTestUtils.setField(sub, "expiringDate",   expiringDate);
            ReflectionTestUtils.setField(sub, "billingAttempts", billingAttempts);
            ReflectionTestUtils.setField(sub, "status",          SubscriptionStatus.ACTIVE);
            ReflectionTestUtils.setField(sub, "autoRenew",       true);
            return sub;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenarios 5-6, 9-11  — orchestrator sweep
    // TransactionTemplate is only called when a subscription IS dispatched (test 11).
    // Tests with empty pages never open a TX — no shared stub in @BeforeEach.
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Orchestrator sweep scenarios")
    @ExtendWith(MockitoExtension.class)
    class OrchestratorScenarios {

        @Mock private SubscriptionRepository repository;
        @Mock private ApplicationEventPublisher eventPublisher;
        @Mock private TransactionTemplate transactionTemplate;
        private RenewalOrchestratorService orchestrator;

        @BeforeEach
        void setUp() {
            orchestrator = new RenewalOrchestratorService(repository, eventPublisher, transactionTemplate);
            ReflectionTestUtils.setField(orchestrator, "maxAttempts",          MAX_ATTEMPTS);
            ReflectionTestUtils.setField(orchestrator, "inFlightGuardMinutes", INFLIGHT_GUARD);
        }

        // 5-6. Expiry sweep covers both ACTIVE-canceled and SUSPENDED
        @Test
        @DisplayName("5-6. expireCanceledSubscriptions is always called — covers CANCELED and SUSPENDED")
        void expirySweep_alwaysDelegates() {
            doAnswer(inv -> { ((java.util.function.Consumer<?>) inv.getArgument(0)).accept(null); return null; })
                .when(transactionTemplate).executeWithoutResult(any());
            when(repository.findEligibleForRenewal(any(), any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of()));

            orchestrator.executeDailySweep();

            verify(repository).expireCanceledSubscriptions(any());
        }

        // 9-10. No eligible subscriptions
        @Test
        @DisplayName("9-10. Empty result — sweep dispatches no events (nextRetryAt in future OR max attempts)")
        void noEligibleSubscriptions_isNoop() {
            doAnswer(inv -> { ((java.util.function.Consumer<?>) inv.getArgument(0)).accept(null); return null; })
                .when(transactionTemplate).executeWithoutResult(any());
            when(repository.findEligibleForRenewal(any(), any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of()));

            orchestrator.executeDailySweep();

            verify(repository, never()).findById(any());
            verify(eventPublisher, never()).publishEvent(any(RenewalRequestedEvent.class));
        }

        // 11. In-flight guard
        @Test
        @DisplayName("11. Sweep stamps nextRetryAt = now + inFlightGuard before dispatching")
        void sweepStampsInFlightGuard() {
            UUID id = UUID.randomUUID();
            Subscription sub = buildDueSub(id);
            LocalDateTime before = LocalDateTime.now();

            when(repository.findEligibleForRenewal(any(), any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of(id), Pageable.ofSize(500), false))
                .thenReturn(new SliceImpl<>(List.of()));
            when(repository.findById(id)).thenReturn(Optional.of(sub));
            doAnswer(inv -> { ((java.util.function.Consumer<?>) inv.getArgument(0)).accept(null); return null; })
                .when(transactionTemplate).executeWithoutResult(any());

            orchestrator.executeDailySweep();

            assertNotNull(sub.getNextRetryAt());
            assertTrue(sub.getNextRetryAt().isAfter(before.plusMinutes(INFLIGHT_GUARD).minusSeconds(2)));
            assertTrue(sub.getNextRetryAt().isBefore(before.plusMinutes(INFLIGHT_GUARD).plusSeconds(5)));
            assertEquals(0, sub.getBillingAttempts());
        }

        private Subscription buildDueSub(UUID id) {
            Subscription sub = Subscription.create(UUID.randomUUID(), Plan.PREMIUM, "tok");
            ReflectionTestUtils.setField(sub, "id",           id);
            ReflectionTestUtils.setField(sub, "expiringDate", LocalDateTime.now().minusDays(1));
            ReflectionTestUtils.setField(sub, "nextRetryAt",  LocalDateTime.now().minusDays(1));
            return sub;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared helper
    // ─────────────────────────────────────────────────────────────────────────
    private static void send(SubscriptionResultListener listener, UUID subId,
                             BillingHistoryStatus status, LocalDateTime reference, String txId) {
        var event  = new BillingResultEvent(subId, txId, status, reference, null);
        var record = new ConsumerRecord<>("subscription.billing-results", 0, 0L, subId.toString(), event);
        var ack    = mock(Acknowledgment.class);
        listener.onBillingResult(List.of(record), ack);
    }
}
