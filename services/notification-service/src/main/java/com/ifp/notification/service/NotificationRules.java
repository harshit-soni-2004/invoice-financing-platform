package com.ifp.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ifp.messaging.EventEnvelope;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Decides who is told about which event, and what the message says. Messages are deliberately short and never contain
 * amounts, rates, account numbers or free-text reasons: the recipient signs in to see details.
 */
public final class NotificationRules {

    public record Planned(UUID recipientId, String subject, String body) {
    }

    private NotificationRules() {
    }

    public static List<Planned> plan(EventEnvelope event) {
        JsonNode p = event.payload();
        String invoice = "Invoice " + text(p, "invoiceNumber");
        List<Planned> out = new ArrayList<>();
        switch (event.eventType()) {
            case "UserRegistered" -> out.add(new Planned(id(p, "userId"), "Welcome to the Invoice Financing Platform",
                    "FINANCIER".equals(text(p, "role"))
                            ? "Your financier account has been created. An administrator will review it before you can make offers."
                            : "Your " + text(p, "role").toLowerCase() + " account has been created. You can sign in now."));
            case "InvoiceSubmitted" -> seller(out, p, invoice + " submitted",
                    invoice + " has been submitted for verification. We will let you know when it has been reviewed.");
            case "InvoiceVerified" -> seller(out, p, invoice + " verified",
                    invoice + " has been verified. You can now list it for financing.");
            case "InvoiceRejected" -> seller(out, p, invoice + " rejected",
                    invoice + " was rejected during verification. Sign in to see the reason.");
            case "InvoiceFinanced" -> seller(out, p, invoice + " financed", invoice + " is now financed.");
            case "InvoiceSettled" -> seller(out, p, invoice + " settled", invoice + " has been settled.");
            case "FinancingOfferCreated" -> seller(out, p, "New financing offer",
                    "You have received a new financing offer for one of your invoices.");
            case "FinancingOfferAccepted" -> financier(out, p, "Offer accepted",
                    "A seller accepted your offer. Please review and approve the agreement.");
            case "FinancingOfferRejected" -> financier(out, p, "Offer declined", "A seller declined your offer.");
            case "FinancingApproved" -> seller(out, p, "Financing approved",
                    "Your financing agreement has been approved and is awaiting disbursement.");
            case "FinancingRejected" -> seller(out, p, "Financing declined", "The financier declined your financing agreement.");
            case "FinancingCancelled" -> both(out, p, "Financing agreement cancelled", "A financing agreement was cancelled.");
            case "FinancingDisbursed" -> both(out, p, "Funds disbursed (simulated)",
                    "The advance for a financing agreement has been disbursed. No real money was moved.");
            case "FinancingRepaymentRecorded" -> financier(out, p, "Repayment recorded",
                    "A repayment was recorded against one of your agreements.");
            case "FinancingRepaymentDueSoon" -> seller(out, p, "Repayment due soon",
                    "Installment " + text(p, "sequenceNo") + " of a financing agreement is due on " + text(p, "dueDate") + ".");
            case "FinancingRepaymentOverdue" -> both(out, p, "Repayment overdue",
                    "Installment " + text(p, "sequenceNo") + " of a financing agreement was due on " + text(p, "dueDate")
                            + " and is not fully repaid.");
            case "FinancingSettled" -> both(out, p, "Financing settled", "A financing agreement has been fully repaid.");
            default -> {
                // Other events (offer expiry, internal steps) do not notify anyone.
            }
        }
        String reference = "\n\nReference: " + event.aggregateType().toLowerCase() + " " + event.aggregateId();
        return out.stream().map(n -> new Planned(n.recipientId(), headerSafe(n.subject()), n.body() + reference)).toList();
    }

    private static void seller(List<Planned> out, JsonNode p, String subject, String body) {
        out.add(new Planned(id(p, "sellerId"), subject, body));
    }

    private static void financier(List<Planned> out, JsonNode p, String subject, String body) {
        out.add(new Planned(id(p, "financierId"), subject, body));
    }

    private static void both(List<Planned> out, JsonNode p, String subject, String body) {
        seller(out, p, subject, body);
        financier(out, p, subject, body);
    }

    private static UUID id(JsonNode payload, String field) {
        return UUID.fromString(payload.path(field).asText());
    }

    private static String text(JsonNode payload, String field) {
        return payload.path(field).asText("");
    }

    /** Subjects include user-supplied invoice numbers; line breaks would allow header injection. */
    static String headerSafe(String subject) {
        String flat = subject.replaceAll("[\\r\\n\\t]+", " ").strip();
        return flat.length() > 200 ? flat.substring(0, 200) : flat;
    }
}
