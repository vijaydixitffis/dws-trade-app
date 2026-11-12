package com.dws.trade.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * A trade as it is transmitted to the store. The length limits match the database columns
 * ({@code VARCHAR(64)}), so anything that passes validation can be stored.
 */
public record Trade(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]*") String tradeId,
        @Min(1) int version,
        @NotBlank @Size(max = 64) String counterPartyId,
        @NotBlank @Size(max = 64) String bookId,
        @NotNull LocalDate maturityDate) {
}
