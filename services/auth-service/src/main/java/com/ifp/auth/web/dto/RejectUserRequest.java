package com.ifp.auth.web.dto;

import jakarta.validation.constraints.Size;

/** Body of a rejection; the whole body and the reason are optional. */
public record RejectUserRequest(@Size(max = 500) String reason) {
}
