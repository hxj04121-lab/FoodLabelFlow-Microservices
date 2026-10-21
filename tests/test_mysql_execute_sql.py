import importlib.util
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "deploy/mysql/execute_sql.py"


class FakeCursor:
    def __init__(self, cipher):
        self.cipher, self.executed = cipher, []

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql):
        self.executed.append(sql)

    def fetchone(self):
        return ("Ssl_cipher", self.cipher)


def load(cipher):
    cursor = FakeCursor(cipher)
    connection = types.SimpleNamespace(cursor=lambda: cursor, close=lambda: None)
    sys.modules["pymysql"] = types.SimpleNamespace(connect=lambda **kwargs: connection)
    spec = importlib.util.spec_from_file_location("execute_sql", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module, cursor


class ExecuteSqlTest(unittest.TestCase):
    def setUp(self):
        os.environ.update(MYSQL_HOST="db.example", MYSQL_ADMIN_USER="admin", MYSQL_ADMIN_PASSWORD="x")
        handle = tempfile.NamedTemporaryFile("w", delete=False, suffix=".sql")
        handle.write("CREATE DATABASE IF NOT EXISTS a;\nGRANT ALL PRIVILEGES ON a.* TO 'a'@'%';\nFLUSH PRIVILEGES;\n")
        handle.close()
        self.path = handle.name

    def tearDown(self):
        os.unlink(self.path)
        sys.modules.pop("pymysql", None)

    def test_runs_each_statement_over_tls(self):
        module, cursor = load("TLS_AES_256_GCM_SHA384")
        module.main(self.path)
        self.assertEqual(cursor.executed[1:], ["CREATE DATABASE IF NOT EXISTS a", "GRANT ALL PRIVILEGES ON a.* TO 'a'@'%'", "FLUSH PRIVILEGES"])

    def test_refuses_an_unencrypted_connection(self):
        module, cursor = load("")
        with self.assertRaises(SystemExit):
            module.main(self.path)
        self.assertEqual(len(cursor.executed), 1)
