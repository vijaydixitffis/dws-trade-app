package com.dws.trade.model;

import java.time.LocalDate;

/** A trade as it is held in the store: the transmitted fields plus created date and expired flag. */
public record StoredTrade(
        String tradeId,
        int version,
        String counterPartyId,
        String bookId,
        LocalDate maturityDate,
        LocalDate createdDate,
        boolean expired) {
}
