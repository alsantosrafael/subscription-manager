package com.platform.subscription_manager.subscription.application.services;

import com.platform.subscription_manager.shared.domain.Plan;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.subscription.domain.projections.ExpiringSubscriptionRow;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionExpiryService")
class SubscriptionExpiryServiceTest {

    @Mock private SubscriptionRepository repository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private TransactionTemplate transactionTemplate;
    private SubscriptionExpiryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new SubscriptionExpiryService(repository, eventPublisher, transactionTemplate);
        // Make transactionTemplate.execute() actually invoke the callback so the lambda under
        // test runs synchronously — mirrors what the real Spring TX infrastructure does.
        when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core loop: expireCanceledSubscriptions
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("expireCanceledSubscriptions")
    class ExpireCanceledSubscriptions {

        @Test
        @DisplayName("Returns 0 and issues no DB writes when no subscriptions are expired")
        void emptyResult_isNoop() {
            when(repository.findExpiringSubscriptionIds(any()))
                    .thenReturn(new SliceImpl<>(List.of()));

            int result = service.expireCanceledSubscriptions();

            assertEquals(0, result);
            verify(repository, never()).expireCanceledSubscriptionsByIds(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Single batch — calls UPDATE with the exact IDs and publishes one INACTIVE event per row")
        void singleBatch_updatesAndPublishesEvents() {
            ExpiringSubscriptionRow row = buildRow();
            UUID expectedId = row.id();

            when(repository.findExpiringSubscriptionIds(any()))
                    .thenReturn(new SliceImpl<>(List.of(row), Pageable.ofSize(500), false))
                    .thenReturn(new SliceImpl<>(List.of()));
            when(repository.expireCanceledSubscriptionsByIds(any())).thenReturn(1);

            int result = service.expireCanceledSubscriptions();

            assertEquals(1, result);

            ArgumentCaptor<List<UUID>> idsCaptor = ArgumentCaptor.forClass(List.class);
            verify(repository).expireCanceledSubscriptionsByIds(idsCaptor.capture());
            assertEquals(List.of(expectedId), idsCaptor.getValue());

            verify(eventPublisher, times(1)).publishEvent(any(SubscriptionUpdatedEvent.class));
        }

        @Test
        @DisplayName("Multiple batches — always queries page 0, drains all rows across iterations")
        void multipleBatches_drainsAllRows() {
            ExpiringSubscriptionRow row1 = buildRow();
            ExpiringSubscriptionRow row2 = buildRow();
            ExpiringSubscriptionRow row3 = buildRow();

            when(repository.findExpiringSubscriptionIds(any()))
                    .thenReturn(new SliceImpl<>(List.of(row1, row2), Pageable.ofSize(500), true))
                    .thenReturn(new SliceImpl<>(List.of(row3), Pageable.ofSize(500), false))
                    .thenReturn(new SliceImpl<>(List.of()));
            when(repository.expireCanceledSubscriptionsByIds(any())).thenReturn(2).thenReturn(1);

            int result = service.expireCanceledSubscriptions();

            assertEquals(3, result);
            verify(repository, times(2)).expireCanceledSubscriptionsByIds(any());
            verify(eventPublisher, times(3)).publishEvent(any(SubscriptionUpdatedEvent.class));
        }

        @Test
        @DisplayName("Loop terminates safely when UPDATE returns 0 (rows already moved by concurrent process)")
        void updateReturnsZero_loopTerminates() {
            ExpiringSubscriptionRow row = buildRow();

            when(repository.findExpiringSubscriptionIds(any()))
                    .thenReturn(new SliceImpl<>(List.of(row), Pageable.ofSize(500), true));
            when(repository.expireCanceledSubscriptionsByIds(any())).thenReturn(0);

            int result = service.expireCanceledSubscriptions();

            assertEquals(0, result);
            // Only one attempt — loop stopped because batchCount == 0
            verify(repository, times(1)).expireCanceledSubscriptionsByIds(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Published event carries INACTIVE status and all fields from the DB row")
        void publishedEvent_hasCorrectFields() {
            UUID subId   = UUID.randomUUID();
            UUID userId  = UUID.randomUUID();
            Plan plan    = Plan.PREMIUM;
            LocalDateTime start   = LocalDateTime.now().minusMonths(1);
            LocalDateTime expires = LocalDateTime.now().minusDays(1);
            ExpiringSubscriptionRow row = new ExpiringSubscriptionRow(subId, userId, plan, start, expires);

            when(repository.findExpiringSubscriptionIds(any()))
                    .thenReturn(new SliceImpl<>(List.of(row), Pageable.ofSize(500), false))
                    .thenReturn(new SliceImpl<>(List.of()));
            when(repository.expireCanceledSubscriptionsByIds(any())).thenReturn(1);

            service.expireCanceledSubscriptions();

            ArgumentCaptor<SubscriptionUpdatedEvent> captor =
                    ArgumentCaptor.forClass(SubscriptionUpdatedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());

            SubscriptionUpdatedEvent evt = captor.getValue();
            assertEquals(subId,                       evt.id());
            assertEquals(userId,                      evt.userId());
            assertEquals(plan,                        evt.plan());
            assertEquals(expires,                     evt.expiringDate());
            assertEquals(SubscriptionStatus.INACTIVE, evt.status());
            assertEquals(false, evt.autoRenew(),
                    "autoRenew must be false — expiry event triggers Redis write-through to mark access revoked");
        }

        @Test
        @DisplayName("Always queries page 0 — never increments page offset between iterations")
        void alwaysQueriesPageZero() {
            ExpiringSubscriptionRow row = buildRow();

            when(repository.findExpiringSubscriptionIds(any()))
                    .thenReturn(new SliceImpl<>(List.of(row), Pageable.ofSize(500), true))
                    .thenReturn(new SliceImpl<>(List.of()));
            when(repository.expireCanceledSubscriptionsByIds(any())).thenReturn(1);

            service.expireCanceledSubscriptions();

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(repository, times(2)).findExpiringSubscriptionIds(pageableCaptor.capture());
            pageableCaptor.getAllValues().forEach(p ->
                    assertEquals(0, p.getPageNumber(),
                            "Must always query page 0 — updated rows leave the result set so " +
                            "incrementing the offset would skip batches"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scheduler entry point
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("runExpirySweep delegates to the paginated expiry loop")
    void runExpirySweep_delegates() {
        when(repository.findExpiringSubscriptionIds(any()))
                .thenReturn(new SliceImpl<>(List.of()));

        service.runExpirySweep();

        verify(repository, atLeastOnce()).findExpiringSubscriptionIds(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Builds a representative {@link ExpiringSubscriptionRow} as returned by findExpiringSubscriptionIds. */
    private ExpiringSubscriptionRow buildRow() {
        return new ExpiringSubscriptionRow(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Plan.BASICO,
                LocalDateTime.now().minusMonths(1),
                LocalDateTime.now().minusDays(1)
        );
    }
}

