-- Defence in depth for the "append-only" guarantee.
-- The application already exposes no update/delete path for these tables (see the Repository interfaces and
-- Hibernate @Immutable). These triggers make the DATABASE itself reject tampering, even from a DBA console
-- or a compromised application account. Apply after the tables exist (first application start).
-- Corrections must be made with reversing ledger entries, never by editing history.

DELIMITER $$

CREATE TRIGGER trg_ledger_no_update BEFORE UPDATE ON ledger_entries FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ledger_entries is append-only'; END$$
CREATE TRIGGER trg_ledger_no_delete BEFORE DELETE ON ledger_entries FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ledger_entries is append-only'; END$$

CREATE TRIGGER trg_txn_no_update BEFORE UPDATE ON transactions FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'transactions is append-only'; END$$
CREATE TRIGGER trg_txn_no_delete BEFORE DELETE ON transactions FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'transactions is append-only'; END$$

CREATE TRIGGER trg_audit_no_update BEFORE UPDATE ON audit_logs FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_logs is append-only'; END$$
CREATE TRIGGER trg_audit_no_delete BEFORE DELETE ON audit_logs FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_logs is append-only'; END$$

DELIMITER ;

-- Recommended: the application DB user should only have SELECT, INSERT on these three tables.
-- GRANT SELECT, INSERT ON securebank.ledger_entries TO 'securebank_app'@'%';
