package com.platform.subscription_manager.subscription.application;

import com.platform.subscription_manager.shared.domain.Plan;
import com.platform.subscription_manager.shared.infrastructure.messaging.RenewalRequestedEvent;
import com.platform.subscription_manager.subscription.application.services.RenewalOrchestratorService;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    @DisplayName("Expiry sweep")
    class ExpirySweep {

        @Mock private SubscriptionRepository repository;
        @Mock private ApplicationEventPublisher eventPublisher;
        @Mock private TransactionTemplate transactionTemplate;
        private RenewalOrchestratorService orchestrator;

        @BeforeEach
        void setUp() {
            orchestrator = build(repository, eventPublisher, transactionTemplate);
            // Stub TX so the expiry lambda actually executes
            doAnswer(inv -> { ((java.util.function.Consumer<?>) inv.getArgument(0)).accept(null); return null; })
                .when(transactionTemplate).executeWithoutResult(any());
            when(repository.findEligibleForRenewal(any(), any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of()));
        }

        @Test
        @DisplayName("Calls expireCanceledSubscriptions with a timestamp close to now")
        void callsExpireWithNow() {
            orchestrator.executeDailySweep();

            ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(repository).expireCanceledSubscriptions(captor.capture());
            assertTrue(captor.getValue().isAfter(LocalDateTime.now().minusSeconds(5)));
        }

        @Test
        @DisplayName("Always delegates to expireCanceledSubscriptions — regression guard for SUSPENDED inclusion")
        void alwaysDelegates() {
            orchestrator.executeDailySweep();

            verify(repository, times(1)).expireCanceledSubscriptions(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pending renewals — Bug 3: drain all pages. Bug 1: fixed in-flight guard.
    // Each test stubs findEligibleForRenewal with exactly the slice it needs.
    // ─────────────────────────────────────────────────────────────────────────
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
            // No subscriptions → TransactionTemplate is never called → do not stub it
            when(repository.findEligibleForRenewal(any(), any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of()));

            orchestrator.executeDailySweep();

            verify(repository, times(1)).findEligibleForRenewal(any(), any(), anyInt(), any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Processes all subscriptions on a single page")
        void singlePageIsFullyProcessed() {
            Subscription sub = buildSub();
            stubTx();
            when(repository.findEligibleForRenewal(any(), any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of(sub.getId()), Pageable.ofSize(500), false))
                .thenReturn(new SliceImpl<>(List.of()));
            when(repository.findById(sub.getId())).thenReturn(Optional.of(sub));

            orchestrator.executeDailySweep();

            verify(eventPublisher, times(1)).publishEvent(any(RenewalRequestedEvent.class));
        }

        @Test
        @DisplayName("Drains all pages — always queries page 0, eligible set shrinks each iteration")
        void drainsMultiplePages() {
            Subscription sub1 = buildSub();
            Subscription sub2 = buildSub();
            Subscription sub3 = buildSub();
            stubTx();
            // Both calls hit page 0: processed subs drop out of eligibility after markBillingAttempt
            when(repository.findEligibleForRenewal(any(), any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of(sub1.getId(), sub2.getId()), Pageable.ofSize(500), true))
                .thenReturn(new SliceImpl<>(List.of(sub3.getId()), Pageable.ofSize(500), false))
                .thenReturn(new SliceImpl<>(List.of()));
            when(repository.findById(sub1.getId())).thenReturn(Optional.of(sub1));
            when(repository.findById(sub2.getId())).thenReturn(Optional.of(sub2));
            when(repository.findById(sub3.getId())).thenReturn(Optional.of(sub3));

            orchestrator.executeDailySweep();

            verify(eventPublisher, times(3)).publishEvent(any(RenewalRequestedEvent.class));
        }

        @Test
        @DisplayName("Published event carries correct subscriptionId and plan")
        void eventCarriesCorrectFields() {
            Subscription sub = buildSub();
            stubTx();
            when(repository.findEligibleForRenewal(any(), any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of(sub.getId()), Pageable.ofSize(500), false))
                .thenReturn(new SliceImpl<>(List.of()));
            when(repository.findById(sub.getId())).thenReturn(Optional.of(sub));

            orchestrator.executeDailySweep();

            ArgumentCaptor<RenewalRequestedEvent> captor = ArgumentCaptor.forClass(RenewalRequestedEvent.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
            assertEquals(sub.getId(), captor.getValue().subscriptionId());
            assertEquals(Plan.BASICO, captor.getValue().plan());
        }

        @Test
        @DisplayName("One failing subscription does not abort the rest of the page")
        void singleErrorDoesNotAbortPage() {
            Subscription bad  = buildSub();
            Subscription good = buildSub();
            stubTx();
            when(repository.findEligibleForRenewal(any(), any(), anyInt(), any()))
                .thenReturn(new SliceImpl<>(List.of(bad.getId(), good.getId()), Pageable.ofSize(500), false))
                .thenReturn(new SliceImpl<>(List.of()));
            when(repository.findById(bad.getId())).thenThrow(new RuntimeException("db error"));
            when(repository.findById(good.getId())).thenReturn(Optional.of(good));

            orchestrator.executeDailySweep();

            verify(eventPublisher, times(1)).publishEvent(any(RenewalRequestedEvent.class));
        }

        private void stubTx() {
            doAnswer(inv -> { ((java.util.function.Consumer<?>) inv.getArgument(0)).accept(null); return null; })
                .when(transactionTemplate).executeWithoutResult(any());
        }

        private Subscription buildSub() {
            Subscription sub = Subscription.create(UUID.randomUUID(), Plan.BASICO, "tok_test_success");
            ReflectionTestUtils.setField(sub, "id",           UUID.randomUUID());
            ReflectionTestUtils.setField(sub, "expiringDate", LocalDateTime.now().minusDays(1));
            ReflectionTestUtils.setField(sub, "nextRetryAt",  LocalDateTime.now().minusDays(1));
            return sub;
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
