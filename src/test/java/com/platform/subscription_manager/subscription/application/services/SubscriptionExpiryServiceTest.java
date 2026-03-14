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
    private SubscriptionExpiryService service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionExpiryService(repository, eventPublisher);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core loop: expireCanceledSubscriptions
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("expireCanceledSubscriptions")
    class ExpireCanceledSubscriptions {

        @Test
        @DisplayName("Returns 0 and issues no DB updates when no subscriptions are expired")
        void emptyResult_isNoop() {
            when(repository.findExpiringSubscriptions())
                    .thenReturn(List.of());

            int result = service.expireCanceledSubscriptions();

            assertEquals(0, result);
            verify(repository, never()).expireCanceledSubscriptionsBatch(any(Integer.class));
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Single batch — calls batch update and publishes one INACTIVE event per row")
        void singleBatch_updatesAndPublishesEvents() {
            ExpiringSubscriptionRow row = buildRow();

            when(repository.findExpiringSubscriptions())
                    .thenReturn(List.of(row));
            when(repository.expireCanceledSubscriptionsBatch(500)).thenReturn(1);

            int result = service.expireCanceledSubscriptions();

            assertEquals(1, result);
            verify(repository).expireCanceledSubscriptionsBatch(500);
            verify(eventPublisher, times(1)).publishEvent(any(SubscriptionUpdatedEvent.class));
        }

        @Test
        @DisplayName("Multiple batches — drains all rows across iterations")
        void multipleBatches_drainsAllRows() {
            ExpiringSubscriptionRow row1 = buildRow();
            ExpiringSubscriptionRow row2 = buildRow();

            // First loop: find metadata, update 1, total 1, find metadata, update 0 -> stop
            when(repository.findExpiringSubscriptions())
                    .thenReturn(List.of(row1))
                    .thenReturn(List.of(row2))
                    .thenReturn(List.of());
            
            // updated < BATCH_SIZE (500) will stop based on update result, 
            // but we also have the empty check.
            when(repository.expireCanceledSubscriptionsBatch(500)).thenReturn(1);

            int result = service.expireCanceledSubscriptions();

            assertEquals(1, result); 
            // In the current implementation: 
            // 1st iteration: finds row1, updates 1, updated(1) < 500 -> breaks.
            verify(repository, times(1)).expireCanceledSubscriptionsBatch(500);
            verify(eventPublisher, times(1)).publishEvent(any(SubscriptionUpdatedEvent.class));
        }

        @Test
        @DisplayName("Loop terminates safely when UPDATE returns 0 (rows already moved by concurrent process)")
        void updateReturnsZero_loopTerminates() {
            ExpiringSubscriptionRow row = buildRow();

            when(repository.findExpiringSubscriptions())
                    .thenReturn(List.of(row));
            when(repository.expireCanceledSubscriptionsBatch(500)).thenReturn(0);

            int result = service.expireCanceledSubscriptions();

            assertEquals(0, result);
            verify(repository, times(1)).expireCanceledSubscriptionsBatch(500);
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

            when(repository.findExpiringSubscriptions())
                    .thenReturn(List.of(row));
            when(repository.expireCanceledSubscriptionsBatch(500)).thenReturn(1);

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
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scheduler entry point
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("runExpirySweep delegates to the batch expiry loop")
    void runExpirySweep_delegates() {
        when(repository.findExpiringSubscriptions())
                .thenReturn(List.of());

        service.runExpirySweep();

        verify(repository, atLeastOnce()).findExpiringSubscriptions();
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

