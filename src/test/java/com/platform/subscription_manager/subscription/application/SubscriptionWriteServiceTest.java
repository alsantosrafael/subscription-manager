package com.platform.subscription_manager.subscription.application;

import com.platform.subscription_manager.billing.BillingFacade;
import com.platform.subscription_manager.shared.domain.exceptions.ResourceNotFoundException;
import com.platform.subscription_manager.shared.domain.exceptions.UnprocessableEntityException;
import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.subscription.application.dtos.CreateSubscriptionDTO;
import com.platform.subscription_manager.subscription.application.services.SubscriptionWriteService;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.shared.domain.Plan;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import com.platform.subscription_manager.user.UserFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionWriteService")
class SubscriptionWriteServiceTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private UserFacade userFacade;
    @Mock private BillingFacade billingFacade;

    // @Spy so we can stub saveSubscription/deleteSubscription when testing createAndCharge
    // without re-testing their logic (which has its own nested tests below).
    @Spy
    private SubscriptionWriteService subscriptionWriteService =
        new SubscriptionWriteService(null, null, null);

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String TOKEN = "tok_test_abc123";

    @BeforeEach
    void injectMocksAndSelf() {
        // Inject real collaborators into the spy
        ReflectionTestUtils.setField(subscriptionWriteService, "subscriptionRepository", subscriptionRepository);
        ReflectionTestUtils.setField(subscriptionWriteService, "userFacade", userFacade);
        ReflectionTestUtils.setField(subscriptionWriteService, "billingFacade", billingFacade);
        // Wire self-reference to the spy itself so createAndCharge calls go through correctly
        ReflectionTestUtils.setField(subscriptionWriteService, "self", subscriptionWriteService);
    }

    @Nested
    @DisplayName("saveSubscription()")
    class SaveSubscription {

        @Test
        @DisplayName("Throws ResourceNotFoundException when user does not exist")
        void userNotFound() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.PREMIUM, TOKEN);
            when(userFacade.exists(USER_ID)).thenReturn(false);

            assertThrows(ResourceNotFoundException.class,
                () -> subscriptionWriteService.saveSubscription(dto));
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Throws UnprocessableEntityException when user already has an active subscription")
        void duplicateActiveSubscription() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.PREMIUM, TOKEN);
            when(userFacade.exists(USER_ID)).thenReturn(true);
            when(subscriptionRepository.existsByUserIdAndStatus(USER_ID, SubscriptionStatus.ACTIVE)).thenReturn(true);

            assertThrows(UnprocessableEntityException.class,
                () -> subscriptionWriteService.saveSubscription(dto));
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Saves and returns subscription on valid input")
        void happyPath() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.BASICO, TOKEN);
            var saved = Subscription.create(USER_ID, Plan.BASICO, TOKEN);

            when(userFacade.exists(USER_ID)).thenReturn(true);
            when(subscriptionRepository.existsByUserIdAndStatus(USER_ID, SubscriptionStatus.ACTIVE)).thenReturn(false);
            when(subscriptionRepository.save(any())).thenReturn(saved);

            Subscription result = subscriptionWriteService.saveSubscription(dto);

            assertNotNull(result);
            assertEquals(Plan.BASICO, result.getPlan());
            assertEquals(SubscriptionStatus.ACTIVE, result.getStatus());
        }
    }

    @Nested
    @DisplayName("createAndCharge()")
    class CreateAndCharge {

        @Test
        @DisplayName("Returns SubscriptionUpdatedEvent on successful charge")
        void happyPath() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.BASICO, TOKEN);
            var saved = Subscription.create(USER_ID, Plan.BASICO, TOKEN);

            doReturn(saved).when(subscriptionWriteService).saveSubscription(dto);
            when(billingFacade.chargeForNewSubscription(any(), any(), any()))
                .thenReturn(new BillingFacade.ChargeResult(true, "txn_123", null));

            SubscriptionUpdatedEvent event = subscriptionWriteService.createAndCharge(dto);

            assertNotNull(event);
            assertEquals(SubscriptionStatus.ACTIVE, event.status());
            assertEquals(Plan.BASICO, event.plan());
            verify(subscriptionRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("Deletes subscription and throws UnprocessableEntityException when charge is declined")
        void chargeDeclined() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.PREMIUM, TOKEN);
            var saved = Subscription.create(USER_ID, Plan.PREMIUM, TOKEN);

            doReturn(saved).when(subscriptionWriteService).saveSubscription(dto);
            when(billingFacade.chargeForNewSubscription(any(), any(), any()))
                .thenReturn(new BillingFacade.ChargeResult(false, null, "Cartão recusado"));

            assertThrows(UnprocessableEntityException.class,
                () -> subscriptionWriteService.createAndCharge(dto));

            verify(subscriptionRepository).deleteById(saved.getId());
        }

        @Test
        @DisplayName("Does not call gateway when user does not exist")
        void doesNotChargeWhenUserMissing() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.BASICO, TOKEN);
            when(userFacade.exists(USER_ID)).thenReturn(false);

            assertThrows(ResourceNotFoundException.class,
                () -> subscriptionWriteService.createAndCharge(dto));
            verify(billingFacade, never()).chargeForNewSubscription(any(), any(), any());
        }
    }
}

