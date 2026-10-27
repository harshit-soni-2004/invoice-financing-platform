package com.ifp.financing.web;

import com.ifp.financing.config.FinancingProperties;
import com.ifp.financing.domain.AgreementStatus;
import com.ifp.financing.domain.Disbursement;
import com.ifp.financing.domain.Repayment;
import com.ifp.financing.security.Actor;
import com.ifp.financing.service.AgreementService;
import com.ifp.financing.service.IdempotentExecutor;
import com.ifp.financing.web.dto.FinancingDtos.AgreementResponse;
import com.ifp.financing.web.dto.FinancingDtos.DisbursementResponse;
import com.ifp.financing.web.dto.FinancingDtos.HistoryEntryResponse;
import com.ifp.financing.web.dto.FinancingDtos.InstallmentResponse;
import com.ifp.financing.web.dto.FinancingDtos.PageResponse;
import com.ifp.financing.web.dto.FinancingDtos.ReasonRequest;
import com.ifp.financing.web.dto.FinancingDtos.RepaymentRequest;
import com.ifp.financing.web.dto.FinancingDtos.RepaymentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/financing/agreements")
public class AgreementController {

    private final AgreementService agreements;
    private final FinancingProperties properties;
    private final Clock clock;

    public AgreementController(AgreementService agreements, FinancingProperties properties, Clock clock) {
        this.agreements = agreements;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SELLER', 'FINANCIER', 'ADMIN')")
    public PageResponse<AgreementResponse> list(Actor actor,
                                                @RequestParam(required = false) AgreementStatus status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestParam(defaultValue = "createdAt,desc") String sort) {
        var pageable = RequestSupport.page(page, size, sort, RequestSupport.AGREEMENT_SORT, "id");
        return PageResponse.from(agreements.list(actor, status, pageable), AgreementResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SELLER', 'FINANCIER', 'ADMIN')")
    public AgreementResponse get(Actor actor, @PathVariable UUID id) {
        return AgreementResponse.from(agreements.get(actor, id));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('SELLER', 'FINANCIER', 'ADMIN')")
    public List<HistoryEntryResponse> history(Actor actor, @PathVariable UUID id) {
        return agreements.history(actor, id).stream().map(e -> HistoryEntryResponse.from(e, actor.isAdmin())).toList();
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('FINANCIER')")
    public AgreementResponse approve(Actor actor, @PathVariable UUID id) {
        return AgreementResponse.from(agreements.approve(actor, id));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('FINANCIER')")
    public AgreementResponse reject(Actor actor, @PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest request) {
        return AgreementResponse.from(agreements.reject(actor, id, request == null ? null : request.reason()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SELLER', 'FINANCIER')")
    public AgreementResponse cancel(Actor actor, @PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest request) {
        return AgreementResponse.from(agreements.cancel(actor, id, request == null ? null : request.reason()));
    }

    /** Simulated payout. Requires an Idempotency-Key; replays return the original disbursement with 200. */
    @PostMapping("/{id}/disburse")
    @PreAuthorize("hasRole('FINANCIER')")
    public ResponseEntity<DisbursementResponse> disburse(Actor actor, @PathVariable UUID id,
                                                         @RequestHeader(value = RequestSupport.IDEMPOTENCY_KEY, required = false) String key) {
        IdempotentExecutor.Result<Disbursement> result = agreements.disburse(actor, id, RequestSupport.idempotencyKey(key));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(RequestSupport.REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(DisbursementResponse.from(result.value()));
    }

    @GetMapping("/{id}/disbursement")
    @PreAuthorize("hasAnyRole('SELLER', 'FINANCIER', 'ADMIN')")
    public DisbursementResponse disbursement(Actor actor, @PathVariable UUID id) {
        return DisbursementResponse.from(agreements.disbursement(actor, id));
    }

    @GetMapping("/{id}/schedule")
    @PreAuthorize("hasAnyRole('SELLER', 'FINANCIER', 'ADMIN')")
    public List<InstallmentResponse> schedule(Actor actor, @PathVariable UUID id) {
        LocalDate today = LocalDate.now(clock.withZone(properties.businessZone()));
        return agreements.schedule(actor, id).stream().map(i -> InstallmentResponse.from(i, today)).toList();
    }

    @GetMapping("/{id}/repayments")
    @PreAuthorize("hasAnyRole('SELLER', 'FINANCIER', 'ADMIN')")
    public List<RepaymentResponse> repayments(Actor actor, @PathVariable UUID id) {
        return agreements.repayments(actor, id).stream().map(RepaymentResponse::from).toList();
    }

    /** Records a simulated repayment. Requires an Idempotency-Key; replays return the original record with 200. */
    @PostMapping("/{id}/repayments")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<RepaymentResponse> recordRepayment(Actor actor, @PathVariable UUID id,
                                                             @RequestHeader(value = RequestSupport.IDEMPOTENCY_KEY, required = false) String key,
                                                             @Valid @RequestBody RepaymentRequest request) {
        IdempotentExecutor.Result<Repayment> result = agreements.recordRepayment(actor, id, request.amount(),
                RequestSupport.idempotencyKey(key));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(RequestSupport.REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(RepaymentResponse.from(result.value()));
    }
}
