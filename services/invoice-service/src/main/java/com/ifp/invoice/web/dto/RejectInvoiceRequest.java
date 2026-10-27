package com.ifp.invoice.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectInvoiceRequest(@NotBlank @Size(max = 500) String reason) {
}
