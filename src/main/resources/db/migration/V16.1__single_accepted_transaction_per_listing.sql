-- FR-TX-06: multiple buyers can make offers, with one active reservation per listing.
CREATE UNIQUE INDEX transactions_accepted_listing_uq ON transactions (listing_id)
    WHERE status = 'ACCEPTED';
