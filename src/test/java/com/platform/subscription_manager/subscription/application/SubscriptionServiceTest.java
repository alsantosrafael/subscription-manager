package com.platform.subscription_manager.subscription.application;

import com.platform.subscription_manager.shared.domain.exceptions.ResourceNotFoundException;
import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.subscription.application.dtos.CreateSubscriptionDTO;
import com.platform.subscription_manager.subscription.application.dtos.SubscriptionResponseDTO;
import com.platform.subscription_manager.subscription.application.services.SubscriptionService;
import com.platform.subscription_manager.subscription.application.services.SubscriptionWriteService;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.shared.domain.Plan;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionService")
class SubscriptionServiceTest {

    @Mock private SubscriptionWriteService subscriptionWriteService;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks
    private SubscriptionService subscriptionService;

    private static final UUID USER_ID         = UUID.randomUUID();
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();
    private static final String TOKEN         = "tok_test_abc123";

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("Delegates to SubscriptionWriteService, publishes cache event and returns DTO")
        void happyPath() {
            var sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);
            var cacheEvent = new SubscriptionUpdatedEvent(
                sub.getId(), USER_ID, SubscriptionStatus.ACTIVE,
                Plan.BASICO, sub.getStartDate(), sub.getExpiringDate(), true);

            when(subscriptionWriteService.createAndCharge(any())).thenReturn(cacheEvent);
            doAnswer(inv -> { ((java.util.function.Consumer<?>) inv.getArgument(0)).accept(null); return null; })
                .when(transactionTemplate).executeWithoutResult(any());

            SubscriptionResponseDTO response = subscriptionService.create(
                new CreateSubscriptionDTO(USER_ID, Plan.BASICO, TOKEN));

            assertNotNull(response);
            assertEquals(SubscriptionStatus.ACTIVE, response.status());
            assertEquals(Plan.BASICO, response.plan());
            assertTrue(response.autoRenew());
            verify(eventPublisher).publishEvent(cacheEvent);
        }

        @Test
        @DisplayName("Propagates exceptions thrown by SubscriptionWriteService without publishing event")
        void propagatesWriteServiceException() {
            when(subscriptionWriteService.createAndCharge(any()))
                .thenThrow(new ResourceNotFoundException("User not found"));

            assertThrows(ResourceNotFoundException.class,
                () -> subscriptionService.create(new CreateSubscriptionDTO(USER_ID, Plan.BASICO, TOKEN)));

            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Nested
    @DisplayName("cancel()")
    class Cancel {

        @Test
        @DisplayName("Delegates to SubscriptionWriteService, publishes cache event")
        void happyPath() {
            var sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);
            var cacheEvent = new SubscriptionUpdatedEvent(
                SUBSCRIPTION_ID, USER_ID, SubscriptionStatus.CANCELED,
                Plan.BASICO, sub.getStartDate(), sub.getExpiringDate(), false);

            when(subscriptionWriteService.cancelSubscription(SUBSCRIPTION_ID, USER_ID))
                .thenReturn(cacheEvent);
            doAnswer(inv -> { ((java.util.function.Consumer<?>) inv.getArgument(0)).accept(null); return null; })
                .when(transactionTemplate).executeWithoutResult(any());

            subscriptionService.cancel(SUBSCRIPTION_ID, USER_ID);

            verify(subscriptionWriteService).cancelSubscription(SUBSCRIPTION_ID, USER_ID);
            verify(eventPublisher).publishEvent(cacheEvent);
        }

        @Test
        @DisplayName("Propagates ResourceNotFoundException when subscription is not found")
        void subscriptionNotFound() {
            when(subscriptionWriteService.cancelSubscription(SUBSCRIPTION_ID, USER_ID))
                .thenThrow(new ResourceNotFoundException("Subscription not found or belongs to another user"));

            assertThrows(ResourceNotFoundException.class,
                () -> subscriptionService.cancel(SUBSCRIPTION_ID, USER_ID));
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Nested
    @DisplayName("get()")
    class Get {

        @Test
        @DisplayName("Returns DTO from cache when key matches")
        void cacheHit() {
            var sub = Subscription.create(USER_ID, Plan.PREMIUM, TOKEN);
            var cachedEvent = new SubscriptionUpdatedEvent(
                SUBSCRIPTION_ID, USER_ID, SubscriptionStatus.ACTIVE,
                Plan.PREMIUM, sub.getStartDate(), sub.getExpiringDate(), true);

            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("subscription:user:" + USER_ID)).thenReturn(cachedEvent);

            Optional<SubscriptionResponseDTO> result = subscriptionService.get(SUBSCRIPTION_ID, USER_ID);

            assertTrue(result.isPresent());
            assertEquals(Plan.PREMIUM, result.get().plan());
            verify(subscriptionRepository, never()).findByIdAndUserId(any(), any());
        }

        @Test
        @DisplayName("Falls back to DB on cache miss and enforces userId ownership")
        void cacheMissFallsBackToDb() {
            var sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);

            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("subscription:user:" + USER_ID)).thenReturn(null);
            when(subscriptionRepository.findByIdAndUserId(SUBSCRIPTION_ID, USER_ID))
                .thenReturn(Optional.of(sub));

            Optional<SubscriptionResponseDTO> result = subscriptionService.get(SUBSCRIPTION_ID, USER_ID);

            assertTrue(result.isPresent());
            verify(subscriptionRepository).findByIdAndUserId(SUBSCRIPTION_ID, USER_ID);
        }

        @Test
        @DisplayName("Returns empty when subscription not found in DB and cache is empty")
        void notFound() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("subscription:user:" + USER_ID)).thenReturn(null);
            when(subscriptionRepository.findByIdAndUserId(SUBSCRIPTION_ID, USER_ID))
                .thenReturn(Optional.empty());

            Optional<SubscriptionResponseDTO> result = subscriptionService.get(SUBSCRIPTION_ID, USER_ID);

            assertFalse(result.isPresent());
        }
    }
}
