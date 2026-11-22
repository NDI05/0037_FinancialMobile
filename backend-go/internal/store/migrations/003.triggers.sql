CREATE TRIGGER trg_users_last_admin_update
         BEFORE UPDATE OF role, status ON users
         WHEN OLD.role = 'ADMIN' AND OLD.status = 'ACTIVE'
          AND (NEW.role <> 'ADMIN' OR NEW.status <> 'ACTIVE')
          AND (SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE') <= 1
         BEGIN
           SELECT RAISE(ABORT, 'last_active_admin');
         END
-- @statement
CREATE TRIGGER trg_users_last_admin_delete
         BEFORE DELETE ON users
         WHEN OLD.role = 'ADMIN' AND OLD.status = 'ACTIVE'
          AND (SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE') <= 1
         BEGIN
           SELECT RAISE(ABORT, 'last_active_admin');
         END
