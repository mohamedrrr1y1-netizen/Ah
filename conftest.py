import os
from pathlib import Path

# Isolated test database and development-only mail-code exposure; never use this configuration in production.
TEST_DB = Path("/tmp/hamo_ultimate_pytest.db")
TEST_DB.unlink(missing_ok=True)
os.environ["APP_ENV"] = "development"
os.environ["JWT_SECRET"] = "pytest-secret-that-is-long-enough-for-tests-0123456789"
os.environ["DATABASE_URL"] = f"sqlite:///{TEST_DB}"
os.environ["DATA_DIR"] = "/tmp/hamo_ultimate_test_data"
os.environ["ADMIN_EMAIL"] = "admin-test@example.com"
os.environ["ADMIN_USERNAME"] = "HAMO Test Admin"
os.environ["ADMIN_PASSWORD"] = "VeryStrongTestPassword-12345"
