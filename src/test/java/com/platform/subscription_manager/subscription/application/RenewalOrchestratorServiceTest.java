package com.platform.subscription_manager.subscription.application;

import com.platform.subscription_manager.shared.domain.Plan;
import com.platform.subscription_manager.shared.infrastructure.messaging.RenewalRequestedEvent;
import com.platform.subscription_manager.subscription.application.services.RenewalOrchestratorService;
import com.platform.subscription_manager.subscription.domain.projections.EligibleRenewalRow;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RenewalOrchestratorService")
class RenewalOrchestratorServiceTest {

    @Nested
    @DisplayName("Pending renewals processing")
    class PendingRenewals {

        @Mock private SubscriptionRepository repository;
        @Mock private ApplicationEventPublisher eventPublisher;
        @Mock private TransactionTemplate transactionTemplate;
        private RenewalOrchestratorService orchestrator;

        @BeforeEach
        void setUp() {
            orchestrator = build(repository, eventPublisher, transactionTemplate);
        }

        @Test
        @DisplayName("Stops after one query when page is empty")
        void stopsOnEmptyPage() {
            when(repository.findEligibleForRenewal(any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of()));

            orchestrator.executeDailySweep();

            verify(repository, times(1)).findEligibleForRenewal(any(), anyInt(), any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Claims row atomically and publishes event when markBillingAttemptAtomic returns 1")
        void singleRow_claimedAndDispatched() {
            EligibleRenewalRow row = buildRow();
            stubTx();
            when(repository.findEligibleForRenewal(any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of(row), Pageable.ofSize(500), false))
                .thenReturn(new SliceImpl<>(List.of()));
            when(repository.markBillingAttemptAtomic(eq(row.id()), eq(row.billingAttempts()), any()))
                .thenReturn(1);

            orchestrator.executeDailySweep();

            verify(repository).markBillingAttemptAtomic(eq(row.id()), eq(row.billingAttempts()), any());
            verify(eventPublisher, times(1)).publishEvent(any(RenewalRequestedEvent.class));
        }

        @Test
        @DisplayName("CAS miss — markBillingAttemptAtomic returns 0 → no event published")
        void casMiss_noEventPublished() {
            EligibleRenewalRow row = buildRow();
            stubTx();
            when(repository.findEligibleForRenewal(any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of(row), Pageable.ofSize(500), false))
                .thenReturn(new SliceImpl<>(List.of()));
            when(repository.markBillingAttemptAtomic(any(), anyInt(), any())).thenReturn(0);

            orchestrator.executeDailySweep();

            verify(repository).markBillingAttemptAtomic(any(), anyInt(), any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("CAS miss does not count as dispatched — only claimed == 1 increments the counter")
        void casMiss_doesNotCountAsDispatched() {
            // Regression: before the fix, totalDispatched++ ran unconditionally after the lambda
            // because 'return' inside a Consumer lambda only exits the lambda, not the for-loop body.
            // With 3 rows all missing the CAS, the log would show "Total enviado: 3" instead of 0.
            // The observable proxy: exactly 0 events published when all rows are CAS misses.
            EligibleRenewalRow r1 = buildRow();
            EligibleRenewalRow r2 = buildRow();
            EligibleRenewalRow r3 = buildRow();
            stubTx();
            when(repository.findEligibleForRenewal(any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of(r1, r2, r3), Pageable.ofSize(500), false))
                .thenReturn(new SliceImpl<>(List.of()));
            when(repository.markBillingAttemptAtomic(any(), anyInt(), any())).thenReturn(0);

            orchestrator.executeDailySweep();

            // All 3 CAS attempts fired but none claimed — 0 events, not 3
            verify(repository, times(3)).markBillingAttemptAtomic(any(), anyInt(), any());
            verify(eventPublisher, never()).publishEvent(any(RenewalRequestedEvent.class));
        }

        @Test
        @DisplayName("Mixed batch — 2 claimed, 1 missed → 2 events published")
        void mixedBatch_partialDispatch() {
            EligibleRenewalRow r1 = buildRow();
            EligibleRenewalRow r2 = buildRow();
            EligibleRenewalRow r3 = buildRow();
            stubTx();
            when(repository.findEligibleForRenewal(any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of(r1, r2, r3), Pageable.ofSize(500), false))
                .thenReturn(new SliceImpl<>(List.of()));
            when(repository.markBillingAttemptAtomic(eq(r1.id()), anyInt(), any())).thenReturn(1);
            when(repository.markBillingAttemptAtomic(eq(r2.id()), anyInt(), any())).thenReturn(0);
            when(repository.markBillingAttemptAtomic(eq(r3.id()), anyInt(), any())).thenReturn(1);

            orchestrator.executeDailySweep();

            verify(eventPublisher, times(2)).publishEvent(any(RenewalRequestedEvent.class));
        }

        @Test
        @DisplayName("Drains all pages — increments page offset between iterations")
        void drainsMultiplePages() {
            EligibleRenewalRow r1 = buildRow();
            EligibleRenewalRow r2 = buildRow();
            EligibleRenewalRow r3 = buildRow();
            stubTx();
            when(repository.findEligibleForRenewal(any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of(r1, r2), Pageable.ofSize(500), true))
                .thenReturn(new SliceImpl<>(List.of(r3), Pageable.ofSize(500), false))
                .thenReturn(new SliceImpl<>(List.of()));
            when(repository.markBillingAttemptAtomic(any(), anyInt(), any())).thenReturn(1);

            orchestrator.executeDailySweep();

            verify(eventPublisher, times(3)).publishEvent(any(RenewalRequestedEvent.class));
        }

        @Test
        @DisplayName("Published event carries correct subscriptionId and plan")
        void eventCarriesCorrectFields() {
            EligibleRenewalRow row = buildRow();
            stubTx();
            when(repository.findEligibleForRenewal(any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of(row), Pageable.ofSize(500), false))
                .thenReturn(new SliceImpl<>(List.of()));
            when(repository.markBillingAttemptAtomic(any(), anyInt(), any())).thenReturn(1);

            orchestrator.executeDailySweep();

            ArgumentCaptor<RenewalRequestedEvent> captor = ArgumentCaptor.forClass(RenewalRequestedEvent.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
            assertEquals(row.id(), captor.getValue().subscriptionId());
            assertEquals(Plan.BASICO, captor.getValue().plan());
        }

        @Test
        @DisplayName("markBillingAttemptAtomic receives nextRetryAt = now + inFlightGuardMinutes")
        void nextRetryAtIsInFuture() {
            EligibleRenewalRow row = buildRow();
            LocalDateTime before = LocalDateTime.now();
            stubTx();
            when(repository.findEligibleForRenewal(any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of(row), Pageable.ofSize(500), false))
                .thenReturn(new SliceImpl<>(List.of()));
            when(repository.markBillingAttemptAtomic(any(), anyInt(), any())).thenReturn(1);

            orchestrator.executeDailySweep();

            ArgumentCaptor<LocalDateTime> nextRetryCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(repository).markBillingAttemptAtomic(any(), anyInt(), nextRetryCaptor.capture());
            assertTrue(nextRetryCaptor.getValue().isAfter(before.plusMinutes(4)),
                "nextRetryAt must be approximately now + 5 minutes");
        }

        @Test
        @DisplayName("One failing subscription does not abort the rest of the page")
        void singleErrorDoesNotAbortPage() {
            EligibleRenewalRow bad  = buildRow();
            EligibleRenewalRow good = buildRow();
            stubTx();
            when(repository.findEligibleForRenewal(any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of(bad, good), Pageable.ofSize(500), false))
                .thenReturn(new SliceImpl<>(List.of()));
            when(repository.markBillingAttemptAtomic(eq(bad.id()), anyInt(), any()))
                .thenThrow(new RuntimeException("db error"));
            when(repository.markBillingAttemptAtomic(eq(good.id()), anyInt(), any())).thenReturn(1);

            orchestrator.executeDailySweep();

            verify(eventPublisher, times(1)).publishEvent(any(RenewalRequestedEvent.class));
        }

        private void stubTx() {
            doAnswer(inv -> { ((java.util.function.Consumer<?>) inv.getArgument(0)).accept(null); return null; })
                .when(transactionTemplate).executeWithoutResult(any());
        }

        private EligibleRenewalRow buildRow() {
            return new EligibleRenewalRow(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Plan.BASICO,
                LocalDateTime.now().minusDays(1),
                0
            );
        }
    }

    private static RenewalOrchestratorService build(
            SubscriptionRepository repository,
            ApplicationEventPublisher eventPublisher,
            TransactionTemplate transactionTemplate) {

        RenewalOrchestratorService svc =
            new RenewalOrchestratorService(repository, eventPublisher, transactionTemplate);
        ReflectionTestUtils.setField(svc, "maxAttempts",          3);
        ReflectionTestUtils.setField(svc, "inFlightGuardMinutes", 5);
        return svc;
    }
}
