package com.platform.subscription_manager.subscription.application;

import com.platform.subscription_manager.shared.ResourceNotFoundException;
import com.platform.subscription_manager.shared.UnprocessableEntityException;
import com.platform.subscription_manager.subscription.application.dtos.CreateSubscriptionDTO;
import com.platform.subscription_manager.subscription.application.dtos.SubscriptionResponseDTO;
import com.platform.subscription_manager.subscription.application.services.SubscriptionService;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.subscription.domain.enums.Plan;
import com.platform.subscription_manager.subscription.domain.enums.SubscriptionStatus;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import com.platform.subscription_manager.user.UserFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionService")
class SubscriptionServiceTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private UserFacade userFacade;

    @InjectMocks
    private SubscriptionService subscriptionService;

    private static final UUID USER_ID          = UUID.randomUUID();
    private static final UUID SUBSCRIPTION_ID  = UUID.randomUUID();
    private static final String TOKEN          = "tok_test_abc123";

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("Returns a populated SubscriptionResponseDTO on success")
        void happyPath() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.BASICO, TOKEN);
            var saved = Subscription.create(USER_ID, Plan.BASICO, TOKEN);

            when(userFacade.exists(USER_ID)).thenReturn(true);
            when(subscriptionRepository.existsByUserIdAndStatus(USER_ID, SubscriptionStatus.ACTIVE)).thenReturn(false);
            when(subscriptionRepository.save(any(Subscription.class))).thenReturn(saved);

            SubscriptionResponseDTO response = subscriptionService.create(dto);

            assertNotNull(response);
            assertEquals(SubscriptionStatus.ACTIVE, response.status());
            assertEquals(Plan.BASICO, response.plan());
            assertTrue(response.autoRenew());
            assertNotNull(response.startDate());
            assertNotNull(response.expiringDate());
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when user does not exist")
        void userNotFound() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.PREMIUM, TOKEN);

            when(userFacade.exists(USER_ID)).thenReturn(false);

            assertThrows(ResourceNotFoundException.class, () -> subscriptionService.create(dto));
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Throws UnprocessableEntityException when user already has an active subscription")
        void duplicateActiveSubscription() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.PREMIUM, TOKEN);

            when(userFacade.exists(USER_ID)).thenReturn(true);
            when(subscriptionRepository.existsByUserIdAndStatus(USER_ID, SubscriptionStatus.ACTIVE)).thenReturn(true);

            assertThrows(UnprocessableEntityException.class, () -> subscriptionService.create(dto));
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Does not check for duplicate subscription when user does not exist")
        void doesNotCheckDuplicateWhenUserMissing() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.BASICO, TOKEN);

            when(userFacade.exists(USER_ID)).thenReturn(false);

            assertThrows(ResourceNotFoundException.class, () -> subscriptionService.create(dto));
            verify(subscriptionRepository, never()).existsByUserIdAndStatus(any(), any());
        }
    }

    @Nested
    @DisplayName("cancel()")
    class Cancel {

        @Test
        @DisplayName("Sets autoRenew to false on the subscription")
        void happyPath() {
            var subscription = Subscription.create(USER_ID, Plan.BASICO, TOKEN);

            when(subscriptionRepository.findByIdAndUserId(SUBSCRIPTION_ID, USER_ID))
                .thenReturn(Optional.of(subscription));

            subscriptionService.cancel(SUBSCRIPTION_ID, USER_ID);

            assertFalse(subscription.isAutoRenew());
            verify(subscriptionRepository).save(subscription);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when subscription is not found")
        void subscriptionNotFound() {
            when(subscriptionRepository.findByIdAndUserId(SUBSCRIPTION_ID, USER_ID))
                .thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                () -> subscriptionService.cancel(SUBSCRIPTION_ID, USER_ID));
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when subscription belongs to another user")
        void subscriptionBelongsToAnotherUser() {
            UUID otherUser = UUID.randomUUID();

            when(subscriptionRepository.findByIdAndUserId(SUBSCRIPTION_ID, otherUser))
                .thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                () -> subscriptionService.cancel(SUBSCRIPTION_ID, otherUser));
            verify(subscriptionRepository, never()).save(any());
        }
    }
}


