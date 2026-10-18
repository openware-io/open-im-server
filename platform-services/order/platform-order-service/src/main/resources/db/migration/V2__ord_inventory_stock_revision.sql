-- standards/10: inventory's counter is not an optimistic lock; rename it away from the reserved name.
ALTER TABLE ord_inventory_stock RENAME COLUMN version TO revision;
