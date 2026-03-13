package com.platform.subscription_manager.billing.application.services;

import com.platform.subscription_manager.billing.BillingFacade;
import com.platform.subscription_manager.billing.domain.entity.BillingHistory;
import com.platform.subscription_manager.billing.domain.repositories.BillingHistoryRepository;
import com.platform.subscription_manager.billing.infrastructure.web.integrations.PaymentGatewayClient;
import com.platform.subscription_manager.shared.domain.BillingHistoryStatus;
import com.platform.subscription_manager.shared.domain.Plan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BillingFacadeImpl")
class BillingFacadeImplTest {

    @Mock private BillingHistoryRepository billingHistoryRepository;
    @Mock private PaymentGatewayClient paymentGatewayClient;

    private BillingFacadeImpl facade;

    /**
     * Mockito mock acting as the Spring AOP self-proxy that provides @Transactional boundaries.
     * Injected via reflection, matching how Spring wires it at runtime.
     */
    private BillingFacadeImpl selfProxy;

    @BeforeEach
    void setUp() {
        facade = new BillingFacadeImpl(paymentGatewayClient, billingHistoryRepository);
        selfProxy = Mockito.mock(BillingFacadeImpl.class);
        ReflectionTestUtils.setField(facade, "self", selfProxy);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Concurrent-charge race window
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Concurrent charge detection")
    class ConcurrentChargeDetection {

        @Test
        @DisplayName("insertPending returns 0 (row PENDING by another thread) → in-progress error, no gateway call")
        void concurrentRequest_rowStillPending_returnsInProgressError() {
            UUID subId = UUID.randomUUID();
            BillingHistory pending = buildPendingHistory();

            when(selfProxy.insertPending(eq(subId), any())).thenReturn(0);
            when(billingHistoryRepository.findByIdempotencyKey(any()))
                    .thenReturn(Optional.of(pending));

            BillingFacade.ChargeResult result = facade.chargeForNewSubscription(subId, Plan.BASICO, "tok_test");

            assertFalse(result.success(), "Must not succeed when concurrent request owns the slot");
            assertNotNull(result.errorMessage(), "Must return an error message to the caller");
            verifyNoInteractions(paymentGatewayClient);
            verify(selfProxy, never()).persistResult(any(), any(), any());
        }

        @Test
        @DisplayName("insertPending returns 0 but row is already SUCCESS → idempotent cached result, no gateway call")
        void concurrentRequest_rowAlreadySuccess_returnsCachedResult() {
            UUID subId = UUID.randomUUID();
            String txId = "txn_cached_ok";
            BillingHistory success = buildFinalHistory(BillingHistoryStatus.SUCCESS, txId);

            when(selfProxy.insertPending(eq(subId), any())).thenReturn(0);
            when(billingHistoryRepository.findByIdempotencyKey(any()))
                    .thenReturn(Optional.of(success));

            BillingFacade.ChargeResult result = facade.chargeForNewSubscription(subId, Plan.BASICO, "tok_test");

            assertTrue(result.success());
            assertEquals(txId, result.gatewayTransactionId());
            verifyNoInteractions(paymentGatewayClient);
        }

        @Test
        @DisplayName("insertPending returns 0, row already FAILED → cached failure returned, no gateway call")
        void concurrentRequest_rowAlreadyFailed_returnsCachedFailure() {
            UUID subId = UUID.randomUUID();
            BillingHistory failed = buildFinalHistory(BillingHistoryStatus.FAILED, null);

            when(selfProxy.insertPending(eq(subId), any())).thenReturn(0);
            when(billingHistoryRepository.findByIdempotencyKey(any()))
                    .thenReturn(Optional.of(failed));

            BillingFacade.ChargeResult result = facade.chargeForNewSubscription(subId, Plan.BASICO, "tok_test");

            assertFalse(result.success());
            verifyNoInteractions(paymentGatewayClient);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path — we own the PENDING row
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Charge execution (this thread owns the PENDING row)")
    class ChargeExecution {

        @Test
        @DisplayName("Gateway approves — persistResult(SUCCESS) called, success=true returned")
        void gatewayApproves_persistsSuccess() {
            UUID subId = UUID.randomUUID();
            String txId = "txn_new_ok";

            when(selfProxy.insertPending(eq(subId), any())).thenReturn(1);
            when(billingHistoryRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(paymentGatewayClient.charge(any(), any(), any()))
                    .thenReturn(new PaymentGatewayClient.GatewayResponse(txId, "SUCCESS", null));

            BillingFacade.ChargeResult result = facade.chargeForNewSubscription(subId, Plan.BASICO, "tok_valid");

            assertTrue(result.success());
            assertEquals(txId, result.gatewayTransactionId());
            verify(selfProxy).persistResult(any(), eq(BillingHistoryStatus.SUCCESS), eq(txId));
        }

        @Test
        @DisplayName("Gateway logical decline — persistResult(FAILED) called, success=false returned")
        void gatewayDeclines_persistsFailed() {
            UUID subId = UUID.randomUUID();

            when(selfProxy.insertPending(eq(subId), any())).thenReturn(1);
            when(billingHistoryRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(paymentGatewayClient.charge(any(), any(), any()))
                    .thenThrow(new PaymentGatewayClient.GatewayLogicalFailureException(
                            new PaymentGatewayClient.GatewayResponse(null, "FAILED", "card declined")));

            BillingFacade.ChargeResult result = facade.chargeForNewSubscription(subId, Plan.BASICO, "tok_declined");

            assertFalse(result.success());
            verify(selfProxy).persistResult(any(), eq(BillingHistoryStatus.FAILED), isNull());
        }

        @Test
        @DisplayName("Infrastructure failure — persistResult(FAILED) called, success=false returned")
        void infraFailure_persistsFailed() {
            UUID subId = UUID.randomUUID();

            when(selfProxy.insertPending(eq(subId), any())).thenReturn(1);
            when(billingHistoryRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(paymentGatewayClient.charge(any(), any(), any()))
                    .thenThrow(new RuntimeException("connection refused"));

            BillingFacade.ChargeResult result = facade.chargeForNewSubscription(subId, Plan.BASICO, "tok_valid");

            assertFalse(result.success());
            verify(selfProxy).persistResult(any(), eq(BillingHistoryStatus.FAILED), isNull());
        }

        @Test
        @DisplayName("Idempotency key includes subscriptionId, plan, and token hash — ensures per-attempt uniqueness")
        void idempotencyKey_includesSubscriptionAndPlanAndToken() {
            UUID subId = UUID.randomUUID();
            when(selfProxy.insertPending(eq(subId), any())).thenReturn(1);
            when(billingHistoryRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(paymentGatewayClient.charge(any(), any(), any()))
                    .thenReturn(new PaymentGatewayClient.GatewayResponse("txn", "SUCCESS", null));

            facade.chargeForNewSubscription(subId, Plan.PREMIUM, "tok_abc");

            // Key passed to insertPending must contain the subscriptionId and plan name
            org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(selfProxy).insertPending(eq(subId), keyCaptor.capture());
            String key = keyCaptor.getValue();
            assertTrue(key.contains(subId.toString()), "Key must include subscriptionId");
            assertTrue(key.contains("PREMIUM"), "Key must include plan name");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** PENDING rows: production code only reads getStatus() before short-circuiting. */
    private BillingHistory buildPendingHistory() {
        BillingHistory h = Mockito.mock(BillingHistory.class);
        when(h.getStatus()).thenReturn(BillingHistoryStatus.PENDING);
        return h;
    }

    /** Final-state rows (SUCCESS / FAILED): production code reads both status and txId. */
    private BillingHistory buildFinalHistory(BillingHistoryStatus status, String txId) {
        BillingHistory h = Mockito.mock(BillingHistory.class);
        when(h.getStatus()).thenReturn(status);
        when(h.getGatewayTransactionId()).thenReturn(txId);
        return h;
    }
}


