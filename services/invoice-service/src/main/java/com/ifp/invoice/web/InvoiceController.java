package com.ifp.invoice.web;

import com.ifp.invoice.domain.Invoice;
import com.ifp.invoice.domain.InvoiceStatus;
import com.ifp.invoice.security.Actor;
import com.ifp.invoice.service.InvoiceService;
import com.ifp.invoice.web.dto.CancelInvoiceRequest;
import com.ifp.invoice.web.dto.InvoiceRequest;
import com.ifp.invoice.web.dto.InvoiceResponse;
import com.ifp.invoice.web.dto.InvoiceSummaryResponse;
import com.ifp.invoice.web.dto.PageResponse;
import com.ifp.invoice.web.dto.RejectInvoiceRequest;
import com.ifp.invoice.web.dto.StatusHistoryEntryResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<InvoiceResponse> create(Actor actor, @Valid @RequestBody InvoiceRequest request) {
        Invoice invoice = service.create(actor, request.toDetails());
        return ResponseEntity
                .created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(invoice.getId()).toUri())
                .eTag(ETags.of(invoice.getVersion()))
                .body(InvoiceResponse.from(invoice));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<InvoiceResponse> update(Actor actor, @PathVariable UUID id,
                                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                  @Valid @RequestBody InvoiceRequest request) {
        if (ifMatch == null) {
            throw new PreconditionRequiredException();
        }
        return withETag(service.update(actor, id, ETags.parseIfMatch(ifMatch), request.toDetails()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    public ResponseEntity<InvoiceResponse> get(Actor actor, @PathVariable UUID id) {
        return withETag(service.get(actor, id));
    }

    /** For sellers the result is always limited to their own invoices; {@code sellerId} is honoured only for admins. */
    @GetMapping
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    public PageResponse<InvoiceSummaryResponse> list(Actor actor,
                                                     @RequestParam(required = false) InvoiceStatus status,
                                                     @RequestParam(required = false) UUID sellerId,
                                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                     @RequestParam(defaultValue = "createdAt,desc") String sort) {
        var pageable = PageRequest.of(page, size, InvoiceSort.parse(sort));
        return PageResponse.from(service.list(actor, sellerId, status, pageable), InvoiceSummaryResponse::from);
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    public List<StatusHistoryEntryResponse> history(Actor actor, @PathVariable UUID id) {
        return service.history(actor, id).stream()
                .map(entry -> StatusHistoryEntryResponse.from(entry, actor.isAdmin()))
                .toList();
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<InvoiceResponse> submit(Actor actor, @PathVariable UUID id,
                                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withETag(service.submit(actor, id, ETags.parseIfMatch(ifMatch)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<InvoiceResponse> cancel(Actor actor, @PathVariable UUID id,
                                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                  @Valid @RequestBody(required = false) CancelInvoiceRequest request) {
        String reason = request == null ? null : request.reason();
        return withETag(service.cancel(actor, id, ETags.parseIfMatch(ifMatch), reason));
    }

    @PostMapping("/{id}/list-for-financing")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<InvoiceResponse> listForFinancing(Actor actor, @PathVariable UUID id,
                                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withETag(service.listForFinancing(actor, id, ETags.parseIfMatch(ifMatch)));
    }

    @PostMapping("/{id}/buyer-confirmation")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<InvoiceResponse> confirmBuyer(Actor actor, @PathVariable UUID id,
                                                        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withETag(service.confirmBuyer(actor, id, ETags.parseIfMatch(ifMatch)));
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<InvoiceResponse> verify(Actor actor, @PathVariable UUID id,
                                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withETag(service.verify(actor, id, ETags.parseIfMatch(ifMatch)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<InvoiceResponse> reject(Actor actor, @PathVariable UUID id,
                                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                  @Valid @RequestBody RejectInvoiceRequest request) {
        return withETag(service.reject(actor, id, ETags.parseIfMatch(ifMatch), request.reason()));
    }

    private static ResponseEntity<InvoiceResponse> withETag(Invoice invoice) {
        return ResponseEntity.ok().eTag(ETags.of(invoice.getVersion())).body(InvoiceResponse.from(invoice));
    }
}
