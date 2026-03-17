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
        @DisplayName("No-op when no expiring subscriptions are found")
        void noExpiringSubscriptions_returnsZeroAndPublishesNoEvents() {
            when(repository.findExpiringSubscriptions())
                    .thenReturn(List.of());

            int result = service.expireCanceledSubscriptions();

            assertEquals(0, result);
            verify(repository, never()).expireCanceledSubscriptionsBatch(SubscriptionExpiryService.BATCH_SIZE);
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Single batch with 1 row: updates and publishes exactly 1 event")
        void singleBatch_onRow_updatesAndPublishesEvent() {
            // Arrange: One expiring subscription found
            ExpiringSubscriptionRow row = buildRow();
            when(repository.findExpiringSubscriptions())
                    .thenReturn(List.of(row));
            // Update returns 1 (< BATCH_SIZE), so loop terminates
            when(repository.expireCanceledSubscriptionsBatch(SubscriptionExpiryService.BATCH_SIZE))
                    .thenReturn(1);

            // Act
            int result = service.expireCanceledSubscriptions();

            // Assert: Total updated = 1, batch update called once, 1 event published
            assertEquals(1, result);
            verify(repository, times(1)).expireCanceledSubscriptionsBatch(SubscriptionExpiryService.BATCH_SIZE);
            verify(eventPublisher, times(1)).publishEvent(any(SubscriptionUpdatedEvent.class));
        }

        @Test
        @DisplayName("Multiple batches: processes rows across iterations until all are exhausted")
        void multipleBatches_fullBatchSize_continuesLoop() {
            // Arrange: First call returns BATCH_SIZE rows (so loop continues)
            List<ExpiringSubscriptionRow> firstBatch = buildRowList(SubscriptionExpiryService.BATCH_SIZE);
            List<ExpiringSubscriptionRow> secondBatch = buildRowList(100); // Less than BATCH_SIZE
            
            when(repository.findExpiringSubscriptions())
                    .thenReturn(firstBatch)
                    .thenReturn(secondBatch)
                    .thenReturn(List.of());
            
            when(repository.expireCanceledSubscriptionsBatch(SubscriptionExpiryService.BATCH_SIZE))
                    .thenReturn(SubscriptionExpiryService.BATCH_SIZE)  // First iteration: full batch
                    .thenReturn(100);  // Second iteration: partial batch, stops

            // Act
            int result = service.expireCanceledSubscriptions();

            // Assert: Total = 500 + 100, two calls to update, 600 events published
            assertEquals(600, result);
            verify(repository, times(2)).expireCanceledSubscriptionsBatch(SubscriptionExpiryService.BATCH_SIZE);
            verify(eventPublisher, times(600)).publishEvent(any(SubscriptionUpdatedEvent.class));
        }

        @Test
        @DisplayName("Update returns 0: loop terminates immediately (concurrent race condition)")
        void updateReturnsZero_loopTerminatesImmediate() {
            // Arrange: Metadata found, but UPDATE returns 0 (rows moved by concurrent process)
            ExpiringSubscriptionRow row = buildRow();
            when(repository.findExpiringSubscriptions())
                    .thenReturn(List.of(row));
            when(repository.expireCanceledSubscriptionsBatch(SubscriptionExpiryService.BATCH_SIZE))
                    .thenReturn(0);

            // Act
            int result = service.expireCanceledSubscriptions();

            // Assert: No progress made, no events published
            assertEquals(0, result);
            verify(repository, times(1)).expireCanceledSubscriptionsBatch(SubscriptionExpiryService.BATCH_SIZE);
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Published event contains correct fields from metadata row")
        void publishedEvent_containsAllMetadataFields() {
            // Arrange: Create row with specific values
            UUID subId   = UUID.randomUUID();
            UUID userId  = UUID.randomUUID();
            Plan plan    = Plan.PREMIUM;
            LocalDateTime startDate   = LocalDateTime.now().minusMonths(1);
            LocalDateTime expiryDate  = LocalDateTime.now().minusDays(1);
            
            ExpiringSubscriptionRow row = new ExpiringSubscriptionRow(
                    subId, userId, plan, startDate, expiryDate
            );

            when(repository.findExpiringSubscriptions())
                    .thenReturn(List.of(row));
            when(repository.expireCanceledSubscriptionsBatch(SubscriptionExpiryService.BATCH_SIZE))
                    .thenReturn(1);

            // Act
            service.expireCanceledSubscriptions();

            // Assert: Event has correct fields
            ArgumentCaptor<SubscriptionUpdatedEvent> captor =
                    ArgumentCaptor.forClass(SubscriptionUpdatedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());

            SubscriptionUpdatedEvent event = captor.getValue();
            assertEquals(subId,                       event.id());
            assertEquals(userId,                      event.userId());
            assertEquals(plan,                        event.plan());
            assertEquals(expiryDate,                  event.expiringDate());
            assertEquals(SubscriptionStatus.INACTIVE, event.status());
        }

        @Test
        @DisplayName("Only 'updated' rows get events: metadata count may differ from updated count")
        void publishesEventsOnlyForUpdatedRows() {
            // Arrange: 10 rows found but only 5 actually updated (others updated concurrently)
            List<ExpiringSubscriptionRow> rows = buildRowList(10);
            when(repository.findExpiringSubscriptions())
                    .thenReturn(rows);
            when(repository.expireCanceledSubscriptionsBatch(SubscriptionExpiryService.BATCH_SIZE))
                    .thenReturn(5);  // Only 5 were actually updated

            // Act
            int result = service.expireCanceledSubscriptions();

            // Assert: Only 5 events published (matching the 5 updated rows)
            assertEquals(5, result);
            verify(eventPublisher, times(5)).publishEvent(any(SubscriptionUpdatedEvent.class));
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

    /** Builds a single representative {@link ExpiringSubscriptionRow} as returned by findExpiringSubscriptions. */
    private ExpiringSubscriptionRow buildRow() {
        return new ExpiringSubscriptionRow(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Plan.BASICO,
                LocalDateTime.now().minusMonths(1),
                LocalDateTime.now().minusDays(1)
        );
    }

    /** Builds a list of N representative rows. */
    private List<ExpiringSubscriptionRow> buildRowList(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> buildRow())
                .toList();
    }
}
