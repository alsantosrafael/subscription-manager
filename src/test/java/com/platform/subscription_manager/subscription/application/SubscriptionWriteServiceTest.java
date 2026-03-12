package com.platform.subscription_manager.subscription.application;

import com.platform.subscription_manager.billing.BillingFacade;
import com.platform.subscription_manager.shared.domain.Plan;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.shared.domain.exceptions.ResourceNotFoundException;
import com.platform.subscription_manager.shared.domain.exceptions.UnprocessableEntityException;
import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.subscription.application.dtos.CreateSubscriptionDTO;
import com.platform.subscription_manager.subscription.application.services.SubscriptionWriteService;
import com.platform.subscription_manager.subscription.application.services.SubscriptionWriteService.SaveResult;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionWriteService")
class SubscriptionWriteServiceTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private UserFacade userFacade;
    @Mock private BillingFacade billingFacade;
    @Mock private ApplicationEventPublisher eventPublisher;

    @Spy
    private SubscriptionWriteService subscriptionWriteService =
        new SubscriptionWriteService(null, null, null);

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String TOKEN = "tok_test_abc123";

    @BeforeEach
    void injectMocksAndSelf() {
        ReflectionTestUtils.setField(subscriptionWriteService, "subscriptionRepository", subscriptionRepository);
        ReflectionTestUtils.setField(subscriptionWriteService, "userFacade", userFacade);
        ReflectionTestUtils.setField(subscriptionWriteService, "billingFacade", billingFacade);
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
        @DisplayName("Throws ConflictException when user already has an ACTIVE subscription")
        void duplicateActiveSubscription() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.PREMIUM, TOKEN);
            Subscription active = Subscription.create(USER_ID, Plan.PREMIUM, TOKEN);
            // status is ACTIVE by default in Subscription.create()

            when(userFacade.exists(USER_ID)).thenReturn(true);
            when(subscriptionRepository.findByUserId(USER_ID)).thenReturn(Optional.of(active));

            assertThrows(com.platform.subscription_manager.shared.domain.exceptions.ConflictException.class,
                () -> subscriptionWriteService.saveSubscription(dto));
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Creates fresh subscription when user has no prior record")
        void happyPath_newSubscription() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.BASICO, TOKEN);
            var saved = Subscription.create(USER_ID, Plan.BASICO, TOKEN);

            when(userFacade.exists(USER_ID)).thenReturn(true);
            when(subscriptionRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any())).thenReturn(saved);

            SaveResult result = subscriptionWriteService.saveSubscription(dto);

            assertNotNull(result.subscription());
            assertEquals(Plan.BASICO, result.subscription().getPlan());
            assertEquals(SubscriptionStatus.ACTIVE, result.subscription().getStatus());
            assertEquals(false, result.wasReactivated());
        }

        @Test
        @DisplayName("Reactivates CANCELED subscription in place — no new row inserted")
        void reactivatesCanceledSubscription() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.PREMIUM, TOKEN);
            Subscription existing = Subscription.create(USER_ID, Plan.BASICO, "old_token");
            ReflectionTestUtils.setField(existing, "status", SubscriptionStatus.CANCELED);
            ReflectionTestUtils.setField(existing, "autoRenew", false);

            when(userFacade.exists(USER_ID)).thenReturn(true);
            when(subscriptionRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
            when(subscriptionRepository.save(any())).thenReturn(existing);

            SaveResult result = subscriptionWriteService.saveSubscription(dto);

            assertEquals(true, result.wasReactivated());
            assertEquals(SubscriptionStatus.CANCELED, result.previousStatus());
            assertEquals(SubscriptionStatus.ACTIVE, result.subscription().getStatus());
            assertEquals(Plan.PREMIUM, result.subscription().getPlan());
            assertEquals(TOKEN, result.subscription().getPaymentToken());
        }

        @Test
        @DisplayName("Reactivates SUSPENDED subscription in place — preserves same row")
        void reactivatesSuspendedSubscription() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.BASICO, TOKEN);
            Subscription existing = Subscription.create(USER_ID, Plan.BASICO, "old_token");
            ReflectionTestUtils.setField(existing, "status", SubscriptionStatus.SUSPENDED);
            ReflectionTestUtils.setField(existing, "autoRenew", false);

            when(userFacade.exists(USER_ID)).thenReturn(true);
            when(subscriptionRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
            when(subscriptionRepository.save(any())).thenReturn(existing);

            SaveResult result = subscriptionWriteService.saveSubscription(dto);

            assertEquals(true, result.wasReactivated());
            assertEquals(SubscriptionStatus.SUSPENDED, result.previousStatus());
            assertEquals(SubscriptionStatus.ACTIVE, result.subscription().getStatus());
        }
    }

    @Nested
    @DisplayName("createAndCharge()")
    class CreateAndCharge {

        @Test
        @DisplayName("Returns SubscriptionUpdatedEvent on successful charge of new subscription")
        void happyPath_newSubscription() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.BASICO, TOKEN);
            var saved = Subscription.create(USER_ID, Plan.BASICO, TOKEN);
            var saveResult = new SaveResult(saved, false, null);

            doReturn(saveResult).when(subscriptionWriteService).saveSubscription(dto);
            when(billingFacade.chargeForNewSubscription(any(), any(), any()))
                .thenReturn(new BillingFacade.ChargeResult(true, "txn_123", null));

            SubscriptionUpdatedEvent event = subscriptionWriteService.createAndCharge(dto);

            assertNotNull(event);
            assertEquals(SubscriptionStatus.ACTIVE, event.status());
            assertEquals(Plan.BASICO, event.plan());
            verify(subscriptionRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("Restores row to CANCELED (never deletes) when initial charge is declined for a fresh subscription")
        void chargeDeclined_freshInsert() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.PREMIUM, TOKEN);
            var saved = Subscription.create(USER_ID, Plan.PREMIUM, TOKEN);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            var saveResult = new SaveResult(saved, false, null);

            doReturn(saveResult).when(subscriptionWriteService).saveSubscription(dto);
            when(billingFacade.chargeForNewSubscription(any(), any(), any()))
                .thenReturn(new BillingFacade.ChargeResult(false, null, "Cartão recusado"));
            when(subscriptionRepository.findById(saved.getId())).thenReturn(Optional.of(saved));

            assertThrows(UnprocessableEntityException.class,
                () -> subscriptionWriteService.createAndCharge(dto));

            // Row must NEVER be deleted — it is an auditable financial record
            verify(subscriptionRepository, never()).deleteById(any());
            // Must restore to CANCELED so the row reflects the declined attempt
            verify(subscriptionRepository).save(saved);
        }

        @Test
        @DisplayName("Restores previous status (not deletes) when reactivation charge is declined")
        void chargeDeclined_reactivation() {
            var dto = new CreateSubscriptionDTO(USER_ID, Plan.PREMIUM, TOKEN);
            var existing = Subscription.create(USER_ID, Plan.PREMIUM, TOKEN);
            ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
            var saveResult = new SaveResult(existing, true, SubscriptionStatus.CANCELED);

            doReturn(saveResult).when(subscriptionWriteService).saveSubscription(dto);
            when(billingFacade.chargeForNewSubscription(any(), any(), any()))
                .thenReturn(new BillingFacade.ChargeResult(false, null, "Cartão recusado"));
            // revertReactivation calls findById + save
            when(subscriptionRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

            assertThrows(UnprocessableEntityException.class,
                () -> subscriptionWriteService.createAndCharge(dto));

            // Must NOT delete — the row has billing history
            verify(subscriptionRepository, never()).deleteById(any());
            // Must save the restored row
            verify(subscriptionRepository).save(existing);
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
