-- Append-only triggers: prevent UPDATE and DELETE on login_history
-- Using '//' as statement separator for Flyway trigger support
--flyway:delimiter=//

CREATE TRIGGER trg_login_history_no_update
    BEFORE UPDATE ON login_history
    FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'UPDATE not allowed on login_history (append-only)';
END//

CREATE TRIGGER trg_login_history_no_delete
    BEFORE DELETE ON login_history
    FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'DELETE not allowed on login_history (append-only)';
END//
