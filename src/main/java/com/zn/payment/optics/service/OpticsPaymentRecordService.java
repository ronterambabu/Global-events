package com.zn.payment.optics.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zn.payment.optics.entity.OpticsPaymentRecord;
import com.zn.payment.optics.entity.OpticsPaymentRecord.PaymentStatus;
import com.zn.payment.optics.repository.OpticsPaymentRecordRepository;
	
@Service
@Transactional
public class OpticsPaymentRecordService {
    
    private static final Logger logger = LoggerFactory.getLogger(OpticsPaymentRecordService.class);

    @Autowired
    private OpticsPaymentRecordRepository paymentRecordRepository;

    /**
     * Create a new payment record from Stripe checkout session data
     */
    public OpticsPaymentRecord createFromStripeSession(String sessionId, String customerEmail, 
                                               BigDecimal amountTotalEuros, String currency,
                                               LocalDateTime stripeCreatedAt, 
                                               LocalDateTime stripeExpiresAt,
                                               String paymentStatus) {
        
        logger.info("Creating payment record for session: {}", sessionId);
        
        // Check if record already exists
        Optional<OpticsPaymentRecord> existing = paymentRecordRepository.findBySessionId(sessionId);
        if (existing.isPresent()) {
            logger.warn("Payment record already exists for session: {}", sessionId);
            return existing.get();
        }

        OpticsPaymentRecord record = OpticsPaymentRecord.fromStripeResponse(
            sessionId, customerEmail, amountTotalEuros, currency,
            stripeCreatedAt, stripeExpiresAt, paymentStatus
        );

        OpticsPaymentRecord saved = paymentRecordRepository.save(record);
        logger.info("Created payment record with ID: {} for session: {}", saved.getId(), sessionId);
        
        return saved;
    }

    /**
     * Update payment record from Stripe webhook event
     */
    public OpticsPaymentRecord updateFromWebhookEvent(String sessionId, String paymentIntentId, String eventStatus) {
        logger.info("Updating payment record for session: {} with status: {}", sessionId, eventStatus);
        
        Optional<OpticsPaymentRecord> recordOpt = paymentRecordRepository.findBySessionId(sessionId);
        if (recordOpt.isEmpty()) {
            logger.warn("Payment record not found for session: {}", sessionId);
            return null;
        }

        OpticsPaymentRecord record = recordOpt.get();
        record.updateFromStripeEvent(paymentIntentId, eventStatus);
        
        OpticsPaymentRecord saved = paymentRecordRepository.save(record);
        logger.info("Updated payment record ID: {} status to: {}", saved.getId(), saved.getStatus());
        
        return saved;
    }

    /**
     * Find payment record by session ID
     */
    @Transactional(readOnly = true)
    public Optional<OpticsPaymentRecord> findBySessionId(String sessionId) {
        return paymentRecordRepository.findBySessionId(sessionId);
    }

    /**
     * Find payment records by customer email
     */
    @Transactional(readOnly = true)
    public List<OpticsPaymentRecord> findByCustomerEmail(String customerEmail) {
        return paymentRecordRepository.findByCustomerEmailOrderByCreatedAtDesc(customerEmail);
    }

    /**
     * Find all payment records with specific status
     */
    @Transactional(readOnly = true)
    public List<OpticsPaymentRecord> findByStatus(PaymentStatus status) {
        return paymentRecordRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    /**
     * Find expired payment records
     */
    @Transactional(readOnly = true)
    public List<OpticsPaymentRecord> findExpiredRecords() {
        LocalDateTime now = LocalDateTime.now();
        return paymentRecordRepository.findExpiredRecords(now);
    }

    /**
     * Mark expired records as EXPIRED
     */
    public int markExpiredRecords() {
        List<OpticsPaymentRecord> expiredRecords = findExpiredRecords();
        int count = 0;
        
        for (OpticsPaymentRecord record : expiredRecords) {
            if (record.getStatus() == PaymentStatus.PENDING) {
                record.setStatus(PaymentStatus.EXPIRED);
                paymentRecordRepository.save(record);
                count++;
                logger.info("Marked payment record {} as expired", record.getId());
            }
        }
        
        logger.info("Marked {} payment records as expired", count);
        return count;
    }

    /**
     * Get payment statistics
     */
    @Transactional(readOnly = true)
    public PaymentStatistics getPaymentStatistics() {
        long totalRecords = paymentRecordRepository.count();
        long completedPayments = paymentRecordRepository.countByStatus(PaymentStatus.COMPLETED);
        long pendingPayments = paymentRecordRepository.countByStatus(PaymentStatus.PENDING);
        long failedPayments = paymentRecordRepository.countByStatus(PaymentStatus.FAILED);
        long expiredPayments = paymentRecordRepository.countByStatus(PaymentStatus.EXPIRED);
        
        BigDecimal totalAmount = paymentRecordRepository.sumAmountByStatus(PaymentStatus.COMPLETED);
        if (totalAmount == null) totalAmount = BigDecimal.ZERO;
        
        return new PaymentStatistics(totalRecords, completedPayments, pendingPayments, 
                                   failedPayments, expiredPayments, totalAmount);
    }

    /**
     * Inner class for payment statistics
     */
    public static class PaymentStatistics {
        private final long totalRecords;
        private final long completedPayments;
        private final long pendingPayments;
        private final long failedPayments;
        private final long expiredPayments;
        private final BigDecimal totalAmountInEuros; // Changed to store euros directly

        public PaymentStatistics(long totalRecords, long completedPayments, long pendingPayments,
                               long failedPayments, long expiredPayments, BigDecimal totalAmountInEuros) {
            this.totalRecords = totalRecords;
            this.completedPayments = completedPayments;
            this.pendingPayments = pendingPayments;
            this.failedPayments = failedPayments;
            this.expiredPayments = expiredPayments;
            this.totalAmountInEuros = totalAmountInEuros != null ? totalAmountInEuros : BigDecimal.ZERO;
        }

        // Getters
        public long getTotalRecords() { return totalRecords; }
        public long getCompletedPayments() { return completedPayments; }
        public long getPendingPayments() { return pendingPayments; }
        public long getFailedPayments() { return failedPayments; }
        public long getExpiredPayments() { return expiredPayments; }
        public BigDecimal getTotalAmountInEuros() { return totalAmountInEuros; }
        
        // Deprecated - kept for backward compatibility but now returns euros
        @Deprecated
        public double getTotalAmountInDollars() { 
            return totalAmountInEuros.doubleValue(); // Now returns euros, not dollars
        }
        
        // New method with correct naming
        public double getTotalAmountInEurosAsDouble() { 
            return totalAmountInEuros.doubleValue(); 
        }
    }

    /**
     * Find all payment records
     */
    @Transactional(readOnly = true)
    public List<OpticsPaymentRecord> findAllPayments() {
        return paymentRecordRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Find payment record by ID
     */
    @Transactional(readOnly = true)
    public Optional<OpticsPaymentRecord> findById(Long id) {
        return paymentRecordRepository.findById(id);
    }

    /**
     * Find recent payment records (last 24 hours)
     */
    @Transactional(readOnly = true)
    public List<OpticsPaymentRecord> findRecentPayments() {
        LocalDateTime yesterday = LocalDateTime.now().minusHours(24);
        return paymentRecordRepository.findByCreatedAtAfterOrderByCreatedAtDesc(yesterday);
    }
}
