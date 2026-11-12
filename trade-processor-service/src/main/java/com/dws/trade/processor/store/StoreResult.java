package com.dws.trade.processor.store;

import com.dws.trade.model.StoredTrade;

/** What the store did: {@code replaced} is true when a row with the same version was overwritten. */
public record StoreResult(StoredTrade trade, boolean replaced) {
}
