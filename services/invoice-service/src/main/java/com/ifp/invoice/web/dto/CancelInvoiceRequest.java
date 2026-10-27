package com.ifp.invoice.web.dto;

import jakarta.validation.constraints.Size;

public record CancelInvoiceRequest(@Size(max = 500) String reason) {
}
