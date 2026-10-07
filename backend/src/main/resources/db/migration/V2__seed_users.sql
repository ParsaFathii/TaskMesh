-- TaskMesh local development accounts (BCrypt, cost 10).
-- Dev passwords are documented in the backend README; hashes only, never plaintext.
INSERT INTO users (username, email, password_hash, role) VALUES
  ('admin',    'admin@taskmesh.local',    '$2b$10$GvAjOm5CjRaDwR6OkWEnHeUGXE4lyO0K.W.9tAF.qufoGd9zQs4Si',    'ADMIN'),
  ('operator', 'operator@taskmesh.local', '$2b$10$hhh0eh5SCVY9aVaVCYNt7edBMSytQqxOqEgMLdV6FRg91/LyGga1O', 'OPERATOR'),
  ('user',     'user@taskmesh.local',     '$2b$10$He1T6hAH/Rywm/xFalVIHufz5ueMwOig.COFxTI7sCNzjX99ieIhq', 'USER')
ON CONFLICT (username) DO NOTHING;
