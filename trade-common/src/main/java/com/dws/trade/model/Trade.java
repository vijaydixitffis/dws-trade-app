package com.dws.trade.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** A trade as it is transmitted to the store. */
public record Trade(
        @NotBlank String tradeId,
        @Min(1) int version,
        @NotBlank String counterPartyId,
        @NotBlank String bookId,
        @NotNull LocalDate maturityDate) {
}
