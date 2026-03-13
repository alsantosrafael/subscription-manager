package com.platform.subscription_manager.subscription.infrastructure.messaging;

import com.platform.subscription_manager.shared.domain.BillingHistoryStatus;
import com.platform.subscription_manager.shared.domain.Plan;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.shared.infrastructure.messaging.BillingResultEvent;
import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionResultListener")
class SubscriptionResultListenerTest {

    @Mock private SubscriptionRepository repository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private TransactionTemplate transactionTemplate;

    private SubscriptionResultListener listener;

    private static final int MAX_ATTEMPTS      = 3;
    private static final int BASE_DELAY_MINUTES = 1;
    private static final UUID USER_ID           = UUID.randomUUID();
    private static final String TOKEN           = "tok_test_success";

    @BeforeEach
    void setUp() {
        listener = new SubscriptionResultListener(repository, transactionTemplate, eventPublisher);
        ReflectionTestUtils.setField(listener, "maxAttempts",      MAX_ATTEMPTS);
        ReflectionTestUtils.setField(listener, "baseDelayMinutes", BASE_DELAY_MINUTES);

        // Run the TransactionTemplate lambda inline
        org.mockito.Mockito.doAnswer(inv -> {
            ((java.util.function.Consumer<?>) inv.getArgument(0)).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SUCCESS path
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("SUCCESS result")
    class SuccessResult {

        @Test
        @DisplayName("Calls renewSubscriptionAtomic with correct dates and publishes cache event")
        void atomicRenewalOnSuccess() {
            LocalDateTime expiring = LocalDateTime.now().minusDays(1);
            Subscription sub = buildSub(expiring, 0);

            BillingResultEvent event = new BillingResultEvent(
                sub.getId(), "txn_ok", BillingHistoryStatus.SUCCESS, expiring, null);

            when(repository.findById(sub.getId())).thenReturn(Optional.of(sub));
            when(repository.renewSubscriptionAtomic(eq(sub.getId()), eq(expiring), any()))
                .thenReturn(1);

            processRecord(event);

            verify(repository).renewSubscriptionAtomic(eq(sub.getId()), eq(expiring), any());
            verify(eventPublisher).publishEvent(any(SubscriptionUpdatedEvent.class));
        }

        @Test
        @DisplayName("Does NOT publish cache event when atomic update returns 0 (already renewed by another thread)")
        void noEventWhenAtomicUpdateMissed() {
            LocalDateTime expiring = LocalDateTime.now().minusDays(1);
            Subscription sub = buildSub(expiring, 0);

            BillingResultEvent event = new BillingResultEvent(
                sub.getId(), "txn_ok", BillingHistoryStatus.SUCCESS, expiring, null);

            when(repository.findById(sub.getId())).thenReturn(Optional.of(sub));
            when(repository.renewSubscriptionAtomic(any(), any(), any())).thenReturn(0);

            processRecord(event);

            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Skips stale event when expiringDate has already advanced (state barrier)")
        void skipsStaleEvent() {
            LocalDateTime currentExpiring = LocalDateTime.now().plusDays(25); // already renewed
            LocalDateTime referenceExpiring = LocalDateTime.now().minusDays(1);
            Subscription sub = buildSub(currentExpiring, 0);

            BillingResultEvent event = new BillingResultEvent(
                sub.getId(), "txn_ok", BillingHistoryStatus.SUCCESS, referenceExpiring, null);

            when(repository.findById(sub.getId())).thenReturn(Optional.of(sub));

            processRecord(event);

            verify(repository, never()).renewSubscriptionAtomic(any(), any(), any());
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FAILURE path — Bug 4: must use atomic queries, NOT repository.save()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FAILURE result — atomic queries, no @Version risk")
    class FailureResult {

        @Test
        @DisplayName("First failure calls incrementFailureAtomic — NOT repository.save()")
        void firstFailureUsesAtomicIncrement() {
            LocalDateTime expiring = LocalDateTime.now().minusDays(1);
            Subscription sub = buildSub(expiring, 0); // billingAttempts=0, attemptsAfter=1 < maxAttempts(3)

            BillingResultEvent event = new BillingResultEvent(
                sub.getId(), null, BillingHistoryStatus.FAILED, expiring, "card declined");

            when(repository.findById(sub.getId()))
                .thenReturn(Optional.of(sub));
            when(repository.incrementFailureAtomic(any(), any(), any(), anyInt())).thenReturn(1);

            processRecord(event);

            verify(repository).incrementFailureAtomic(eq(sub.getId()), eq(expiring), any(), anyInt());
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Third failure (maxAttempts reached) calls suspendSubscriptionAtomic — NOT repository.save()")
        void thirdFailureSuspendsAtomically() {
            LocalDateTime expiring = LocalDateTime.now().minusDays(1);
            Subscription sub = buildSub(expiring, 2);

            BillingResultEvent event = new BillingResultEvent(
                sub.getId(), null, BillingHistoryStatus.FAILED, expiring, "card declined");

            when(repository.findById(sub.getId()))
                .thenReturn(Optional.of(sub));
            when(repository.suspendSubscriptionAtomic(any(), any(), anyInt())).thenReturn(1);

            processRecord(event);

            verify(repository).suspendSubscriptionAtomic(eq(sub.getId()), eq(expiring), anyInt());
            verify(repository, never()).incrementFailureAtomic(any(), any(), any(), anyInt());
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("nextRetryAt passed to incrementFailureAtomic is in the future")
        void nextRetryAtIsInTheFuture() {
            LocalDateTime expiring = LocalDateTime.now().minusDays(1);
            Subscription sub = buildSub(expiring, 0);

            BillingResultEvent event = new BillingResultEvent(
                sub.getId(), null, BillingHistoryStatus.FAILED, expiring, "card declined");

            when(repository.findById(sub.getId())).thenReturn(Optional.of(sub));
            when(repository.incrementFailureAtomic(any(), any(), any(), anyInt())).thenReturn(1);

            processRecord(event);

            ArgumentCaptor<LocalDateTime> nextRetryCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(repository).incrementFailureAtomic(any(), any(), nextRetryCaptor.capture(), anyInt());
            assertTrue(nextRetryCaptor.getValue().isAfter(LocalDateTime.now()),
                "nextRetryAt must be in the future so the sweep will not immediately re-dispatch");
        }

        @Test
        @DisplayName("Failure on already-advanced expiringDate is ignored (state barrier)")
        void staleFailureEventIsIgnored() {
            LocalDateTime currentExpiring = LocalDateTime.now().plusDays(25); // already renewed
            LocalDateTime referenceExpiring = LocalDateTime.now().minusDays(1);
            Subscription sub = buildSub(currentExpiring, 0);

            BillingResultEvent event = new BillingResultEvent(
                sub.getId(), null, BillingHistoryStatus.FAILED, referenceExpiring, "card declined");

            when(repository.findById(sub.getId())).thenReturn(Optional.of(sub));

            processRecord(event);

            verify(repository, never()).incrementFailureAtomic(any(), any(), any(), anyInt());
            verify(repository, never()).suspendSubscriptionAtomic(any(), any(), anyInt());
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Publishes cache event after failure by re-fetching DB state")
        void publishesCacheEventAfterFailure() {
            LocalDateTime expiring = LocalDateTime.now().minusDays(1);
            Subscription sub = buildSub(expiring, 0);

            BillingResultEvent event = new BillingResultEvent(
                sub.getId(), null, BillingHistoryStatus.FAILED, expiring, "card declined");

            when(repository.findById(sub.getId())).thenReturn(Optional.of(sub));
            when(repository.incrementFailureAtomic(any(), any(), any(), anyInt())).thenReturn(1);

            processRecord(event);

            verify(eventPublisher).publishEvent(any(SubscriptionUpdatedEvent.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void processRecord(BillingResultEvent event) {
        ConsumerRecord<String, BillingResultEvent> record =
            new ConsumerRecord<>("subscription.billing-results", 0, 0L,
                event.subscriptionId().toString(), event);

        org.springframework.kafka.support.Acknowledgment ack =
            org.mockito.Mockito.mock(org.springframework.kafka.support.Acknowledgment.class);

        listener.onBillingResult(List.of(record), ack);
    }

    private Subscription buildSub(LocalDateTime expiringDate, int billingAttempts) {
        Subscription sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);
        // @GeneratedValue(UUID) only fires inside JPA — inject manually in unit tests
        ReflectionTestUtils.setField(sub, "id",             UUID.randomUUID());
        ReflectionTestUtils.setField(sub, "expiringDate",    expiringDate);
        ReflectionTestUtils.setField(sub, "billingAttempts", billingAttempts);
        ReflectionTestUtils.setField(sub, "status",          SubscriptionStatus.ACTIVE);
        return sub;
    }
}







