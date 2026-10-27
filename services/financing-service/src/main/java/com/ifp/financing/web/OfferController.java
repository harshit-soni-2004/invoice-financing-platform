package com.ifp.financing.web;

import com.ifp.financing.config.FinancingProperties;
import com.ifp.financing.domain.FinancingAgreement;
import com.ifp.financing.domain.FinancingOffer;
import com.ifp.financing.domain.OfferStatus;
import com.ifp.financing.domain.OfferTerms;
import com.ifp.financing.security.Actor;
import com.ifp.financing.service.OfferService;
import com.ifp.financing.web.dto.FinancingDtos.AgreementResponse;
import com.ifp.financing.web.dto.FinancingDtos.CreateOfferRequest;
import com.ifp.financing.web.dto.FinancingDtos.HistoryEntryResponse;
import com.ifp.financing.web.dto.FinancingDtos.OfferResponse;
import com.ifp.financing.web.dto.FinancingDtos.OpportunityResponse;
import com.ifp.financing.web.dto.FinancingDtos.PageResponse;
import com.ifp.financing.web.dto.FinancingDtos.ReasonRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/financing")
public class OfferController {

    private final OfferService offers;
    private final FinancingProperties properties;
    private final Clock clock;

    public OfferController(OfferService offers, FinancingProperties properties, Clock clock) {
        this.offers = offers;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/opportunities")
    @PreAuthorize("hasAnyRole('FINANCIER', 'ADMIN')")
    public PageResponse<OpportunityResponse> opportunities(Actor actor,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size,
                                                           @RequestParam(defaultValue = "dueDate,asc") String sort) {
        LocalDate today = LocalDate.now(clock.withZone(properties.businessZone()));
        var pageable = RequestSupport.page(page, size, sort, RequestSupport.OPPORTUNITY_SORT, "invoiceId");
        return PageResponse.from(offers.opportunities(actor, pageable),
                invoice -> OpportunityResponse.from(invoice, today, offers.maxAdvance(invoice)));
    }

    @GetMapping("/opportunities/{invoiceId}")
    @PreAuthorize("hasAnyRole('FINANCIER', 'ADMIN')")
    public OpportunityResponse opportunity(Actor actor, @PathVariable UUID invoiceId) {
        var invoice = offers.opportunity(actor, invoiceId);
        return OpportunityResponse.from(invoice, LocalDate.now(clock.withZone(properties.businessZone())), offers.maxAdvance(invoice));
    }

    @PostMapping("/offers")
    @PreAuthorize("hasRole('FINANCIER')")
    public ResponseEntity<OfferResponse> create(Actor actor, @Valid @RequestBody CreateOfferRequest request) {
        OfferTerms terms = new OfferTerms(request.advanceAmount(), request.annualRate(), request.processingFee(),
                request.installmentCount());
        FinancingOffer offer = offers.create(actor, request.invoiceId(), terms, request.expiresAt());
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(offer.getId()).toUri())
                .body(OfferResponse.from(offer));
    }

    /** Financiers see their own offers, sellers the offers on their invoices, admins everything. */
    @GetMapping("/offers")
    @PreAuthorize("hasAnyRole('SELLER', 'FINANCIER', 'ADMIN')")
    public PageResponse<OfferResponse> list(Actor actor,
                                            @RequestParam(required = false) UUID invoiceId,
                                            @RequestParam(required = false) OfferStatus status,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        var pageable = RequestSupport.page(page, size, sort, RequestSupport.OFFER_SORT, "id");
        return PageResponse.from(offers.list(actor, invoiceId, status, pageable), OfferResponse::from);
    }

    @GetMapping("/offers/{id}")
    @PreAuthorize("hasAnyRole('SELLER', 'FINANCIER', 'ADMIN')")
    public OfferResponse get(Actor actor, @PathVariable UUID id) {
        return OfferResponse.from(offers.get(actor, id));
    }

    @GetMapping("/offers/{id}/history")
    @PreAuthorize("hasAnyRole('SELLER', 'FINANCIER', 'ADMIN')")
    public List<HistoryEntryResponse> history(Actor actor, @PathVariable UUID id) {
        return offers.history(actor, id).stream().map(e -> HistoryEntryResponse.from(e, actor.isAdmin())).toList();
    }

    @PostMapping("/offers/{id}/withdraw")
    @PreAuthorize("hasRole('FINANCIER')")
    public OfferResponse withdraw(Actor actor, @PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest request) {
        return OfferResponse.from(offers.withdraw(actor, id, request == null ? null : request.reason()));
    }

    @PostMapping("/offers/{id}/reject")
    @PreAuthorize("hasRole('SELLER')")
    public OfferResponse reject(Actor actor, @PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest request) {
        return OfferResponse.from(offers.reject(actor, id, request == null ? null : request.reason()));
    }

    @PostMapping("/offers/{id}/accept")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<AgreementResponse> accept(Actor actor, @PathVariable UUID id) {
        FinancingAgreement agreement = offers.accept(actor, id);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/api/financing/agreements/{id}").buildAndExpand(agreement.getId()).toUri())
                .body(AgreementResponse.from(agreement));
    }
}
